# HTTP/2 Writer-Thread Coalescing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce HTTP/2 writer-thread per-frame socket-write cost and per-frame queue-contention cost by (1) buffering the socket OutputStream so multiple `writeFrame` calls accumulate into one syscall, and (2) draining the frame queue in batches with a single flush per batch.

**Architecture:** Two independent levers, sequenced so each is individually measurable.

1. **Lever A — Wrap `out` in `BufferedOutputStream`.** Today the writer thread calls `out.flush()` after every `writeFrame`, and `out` is `ThroughputOutputStream → socket.getOutputStream()` with no userspace buffering. Every frame is one (or more) raw socket writes. Wrapping in a generously sized `BufferedOutputStream` accumulates frame bytes; the existing `flush()` calls then trigger one socket write per batch instead of per frame. The 2026-05-19 JFR attributes ~13% of writer-thread CPU to `SocketDispatcher.write0`; this is the lever that targets it.

2. **Lever B — Replace `take()` with `take()` + `drainTo()`.** The writer takes the head frame (blocking, preserves idle-park), then opportunistically drains additional frames already queued by concurrent producers via `drainTo(batch, MAX_BATCH - 1)`. Writes the entire batch, then a single `flush()`. The 2026-05-19 JFR attributes ~18% of writer-thread CPU to producer/consumer contention on `LinkedBlockingQueue<HTTP2Frame>`; this is the lever that targets it.

The two levers are orthogonal: A reduces syscalls regardless of batching; B reduces queue lock acquisitions regardless of buffering. Applying them as separate commits gives an attributable measurement at each step. The 2026-05-19 finding describes both together as "option 1" but the implementation cleanly separates.

**Tech Stack:** Java 21 virtual threads, `java.io.BufferedOutputStream`, `java.util.concurrent.LinkedBlockingQueue.drainTo`.

**Reference:** `docs/specs/HTTP2.md` "Performance findings (2026-05-19)" → "writer-thread architecture for h2 DATA emission" → option 1. The 2026-05-21 lost-wakeup hypothesis in the same document is superseded (see correction 2026-05-26 in HTTP2.md); option 1 is now the sole remaining lever for the `h2-stream` / `h2-large-response` ceiling.

**Concrete target:** Helidon achieves 38k RPS on `h2-stream`, 36k on `h2-large-response`. Latte today: 4.1k / 4.1k. Success criterion: close 60–80% of the gap (≈24k–32k RPS on both).

---

## Scope check

This plan touches one production file (`HTTP2Connection.java`) and one test file. It does not change wire-protocol behavior or any public API. It does change the order in which bytes hit the socket (batched instead of per-frame), which has visible effects under TCP capture but not in the h2-level test suite. h2spec must still pass.

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java` | Modify | Add BufferedOutputStream wrap; extract writer loop to a testable method; add drainTo coalescing |
| `src/test/java/org/lattejava/http/tests/server/HTTP2WriterCoalescingTest.java` | Create | Unit tests for the extracted writer loop: single-flush-per-batch, sentinel-mid-batch, IOException-mid-batch |
| `docs/specs/HTTP2.md` | Modify | New "Performance findings (2026-05-26-or-later)" section with measured impact, peer comparison delta |

---

## Phase 1 — Lever A: BufferedOutputStream wrap

### Task 1: Wrap `out` in `BufferedOutputStream` before passing to writer + writer thread

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java:144` (the `out = new ThroughputOutputStream(...)` line)

The wrap goes between `ThroughputOutputStream` and `HTTP2FrameWriter` so byte-count accounting still sees every byte and the buffered layer is what `HTTP2FrameWriter` and the writer thread interact with.

Buffer size: **64 KiB**. Rationale: large enough that a batch of typical small frames (HEADERS + WINDOW_UPDATE + small DATA) all accumulate, but small enough that a single batch of large DATA frames at `peerMaxFrameSize` (default 16384, up to 16 MB in theory) doesn't sit in userspace for many MB before reaching the kernel. 64 KiB also matches a typical TCP send-buffer size, so even when it auto-flushes mid-batch under heavy DATA load, we're matching kernel-side natural batching.

- [ ] **Step 1: Read the construction site**

Run: `Read HTTP2Connection.java offset=140 limit=20`

Confirm line 144 reads:
```java
var out = new ThroughputOutputStream(socket.getOutputStream(), throughput);
```

- [ ] **Step 2: Wrap in BufferedOutputStream**

Edit `HTTP2Connection.java:144`. Change:

```java
var out = new ThroughputOutputStream(socket.getOutputStream(), throughput);
```

To:

```java
// 64 KiB userspace buffer between the frame writer and the socket. Without this, every writeFrame
// hit the socket as a separate write syscall — JFR (2026-05-19) attributed ~13% of writer-thread
// CPU to SocketDispatcher.write0. The BufferedOutputStream coalesces the frame-header + payload
// writes of a single writeFrame, AND coalesces multiple writeFrames between explicit flush() calls
// (Phase 2 of this plan exploits the latter via drainTo batching).
var out = new BufferedOutputStream(new ThroughputOutputStream(socket.getOutputStream(), throughput), 64 * 1024);
```

- [ ] **Step 3: Verify all `out.flush()` callsites still produce a socket write at the correct point**

Run: `grep -n "out\.flush\(\)\|outForThread\.flush\(\)" src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java`

Expected callsites (no new ones, no removed ones):
- Line 158 — after server-sends-first SETTINGS preface
- Line 184 — after ALPN/prior-knowledge SETTINGS preface
- Line 207 — after SETTINGS ACK
- Line 225 — inside writer thread, after each `writeFrame` (this is what Phase 2 will change)
- Line 586 — inside `sendGoAwayDirect`

`BufferedOutputStream.flush()` drains its internal buffer to the underlying stream and then calls flush on the underlying stream. All five sites continue to produce a socket write at the same moment they did before.

- [ ] **Step 4: Verify type compatibility — `out` is declared `var`, must remain a valid type for the writer thread closure**

The writer thread captures `out` via `OutputStream outForThread = out;` at line 215. `BufferedOutputStream` is an `OutputStream`, so this still compiles. No change needed there.

- [ ] **Step 5: Run the full test suite**

```bash
latte test --excludePerformance --excludeTimeouts
```

Expected: all tests pass (this branch baseline is 2887/2887, no regressions).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java
git commit -m "perf(http2): wrap writer OutputStream in 64KB BufferedOutputStream

Lever A of Plan F option 1 (writer-thread coalescing). Targets the
~13% writer-thread CPU attributed to SocketDispatcher.write0 in the
2026-05-19 JFR — every writeFrame was a raw socket syscall because
no userspace buffer sat between HTTP2FrameWriter and the socket.

64 KiB is sized to coalesce typical frame mixes (HEADERS + small DATA
+ WINDOW_UPDATE) without holding multi-MB of large-DATA bursts in
userspace. Existing out.flush() callsites continue to produce a
socket write at the same logical points (post-preface, post-ACK,
post-frame inside the writer loop, post-GOAWAY).

Phase 2 (drainTo batching) will exploit this buffer to amortize many
frames per flush."
```

---

### Task 2: Quick benchmark — does Lever A alone move the needle?

The point of separating levers is attribution. Measure A in isolation before adding B.

- [ ] **Step 1: Confirm the benchmark harness is in working order**

Run: `ls benchmarks/h2-scenarios/ 2>/dev/null || ls benchmarks/scenarios/h2-* 2>/dev/null`

If neither exists, fall back to running the existing `benchmarks/perf-test.sh` or check `benchmarks/README.md` for current invocation. Do not invent scenarios.

- [ ] **Step 2: Cool the machine, then run self-only h2-stream and h2-large-response, 3 trials × 30 s each**

The 2026-05-21 finding documents thermal throttling artifacts when running a full multi-vendor matrix in one sustained sweep. For attribution, only self matters at this step.

```bash
# Replace with the actual invocation per benchmarks/README.md
./benchmarks/run.sh --server=self --protocol=h2 --scenario=h2-stream --duration=30 --trials=3
./benchmarks/run.sh --server=self --protocol=h2 --scenario=h2-large-response --duration=30 --trials=3
```

Record best-of-3 for each scenario. Expected: meaningful but probably partial improvement. The 13% syscall cost is one of two stacked hotspots; eliminating it alone might give a 1.5–2× lift on these scenarios but not the full 9× target.

- [ ] **Step 3: Note the numbers in the task description; do NOT commit them to HTTP2.md yet**

Task 12 below is where the full findings entry lands, after Lever B is also in. Recording the Lever-A-only numbers here lets us attribute the delta correctly in Task 12.

---

## Phase 2 — Lever B: drainTo coalescing

### Task 3: Extract the writer-thread loop into a testable private method

The current writer thread is an inline lambda inside `run()` at HTTP2Connection.java:216-239 — impossible to unit-test without spinning up a full server. Extract it to a package-private static method so the test can drive it directly with a controlled queue and a controlled OutputStream.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java:216-239`

- [ ] **Step 1: Read the current lambda to capture its exact behavior**

Run: `Read HTTP2Connection.java offset=216 limit=24`

The lambda body is:
```java
try {
  while (true) {
    HTTP2Frame f = writerQueue.take();
    if (f instanceof HTTP2Frame.GoawayFrame g && g.lastStreamId() == -1) {
      // Sentinel: shut down the writer thread cleanly.
      return;
    }
    writerForThread.writeFrame(f);
    outForThread.flush();
  }
} catch (Exception e) {
  logger.debug("Writer thread ended unexpectedly; signaling reader", e);
} finally {
  writerDead = true;
  Thread readerThreadRef = readerThread;
  if (readerThreadRef != null) {
    readerThreadRef.interrupt();
  }
}
```

Note: `writerDead` and `readerThread` are instance fields. The interrupt-on-exit is part of the writer-dead teardown contract.

- [ ] **Step 2: Add a static method that contains the loop body**

This is a pure refactor — same behavior, just extracted. Make it package-private (no `private` keyword) so the test in the same package can call it.

Add immediately above the existing `run()` method, or wherever your package conventions place static helpers (the project's code-conventions.md says static methods come before instance methods). Place it accordingly.

```java
/**
 * Writer-thread loop body — drains {@code queue} into {@code writer} and flushes {@code out}, exiting cleanly when
 * the sentinel frame (a {@link HTTP2Frame.GoawayFrame} with {@code lastStreamId == -1}) is dequeued. Extracted to a
 * static method so the loop can be unit-tested without spinning up a full {@link HTTP2Connection}.
 *
 * <p>Returns normally on clean shutdown (sentinel observed) and on {@link InterruptedException}; rethrows {@link
 * IOException} from {@code writer} / {@code out} so the caller (the writer virtual-thread lambda) can run its
 * teardown finally block.
 */
static void runWriterLoop(BlockingQueue<HTTP2Frame> queue, HTTP2FrameWriter writer, OutputStream out) throws IOException, InterruptedException {
  while (true) {
    HTTP2Frame f = queue.take();
    if (f instanceof HTTP2Frame.GoawayFrame g && g.lastStreamId() == -1) {
      return;
    }
    writer.writeFrame(f);
    out.flush();
  }
}
```

- [ ] **Step 3: Replace the lambda body with a call to the new method**

Edit HTTP2Connection.java:216-226 (the try/while block). Change:

```java
writerThread = Thread.ofVirtual().name("h2-writer").start(() -> {
  try {
    while (true) {
      HTTP2Frame f = writerQueue.take();
      if (f instanceof HTTP2Frame.GoawayFrame g && g.lastStreamId() == -1) {
        // Sentinel: shut down the writer thread cleanly.
        return;
      }
      writerForThread.writeFrame(f);
      outForThread.flush();
    }
  } catch (Exception e) {
    logger.debug("Writer thread ended unexpectedly; signaling reader", e);
  } finally {
    writerDead = true;
    Thread readerThreadRef = readerThread;
    if (readerThreadRef != null) {
      readerThreadRef.interrupt();
    }
  }
});
```

To:

```java
writerThread = Thread.ofVirtual().name("h2-writer").start(() -> {
  try {
    runWriterLoop(writerQueue, writerForThread, outForThread);
  } catch (Exception e) {
    logger.debug("Writer thread ended unexpectedly; signaling reader", e);
  } finally {
    writerDead = true;
    Thread readerThreadRef = readerThread;
    if (readerThreadRef != null) {
      readerThreadRef.interrupt();
    }
  }
});
```

The behavior is identical: `runWriterLoop` throws on socket errors (caught by the lambda's catch), returns normally on sentinel or interrupt (lambda's finally runs either way).

- [ ] **Step 4: Run the full test suite — refactor must not change behavior**

```bash
latte test --excludePerformance --excludeTimeouts
```

Expected: all tests pass.

- [ ] **Step 5: Commit the refactor**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java
git commit -m "refactor(http2): extract writer-thread loop body into runWriterLoop

Pure refactor — no behavior change. Pulls the inline lambda body out
of the writer-thread spawn so the loop can be unit-tested without
constructing a full HTTP2Connection. Phase 2 of the writer-thread
coalescing plan will extend this method with drainTo batching."
```

---

### Task 4: Failing test — single flush for a queue of multiple frames

This is the contract Task 5 will implement. Write the test first; watch it fail against the current take-one-flush-each loop; then change the loop in Task 5.

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/HTTP2WriterCoalescingTest.java`

- [ ] **Step 1: Create the test file with a counting OutputStream and the failing test**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.h2.HTTP2Connection;
import org.lattejava.http.server.internal.h2.HTTP2Frame;
import org.lattejava.http.server.internal.h2.HTTP2FrameWriter;

import static org.testng.Assert.*;

/**
 * Unit tests for the writer-thread loop (HTTP2Connection.runWriterLoop). Verifies that the loop batches frames already
 * queued at drain time into a single flush, exits cleanly on the sentinel even when other frames are batched ahead of
 * it, and propagates IOException from a mid-batch write so the caller can tear down the connection.
 */
public class HTTP2WriterCoalescingTest {

  /**
   * Counting OutputStream — records bytes written and counts flush() calls. Used to assert the number of flushes per
   * batch.
   */
  static final class CountingOutputStream extends OutputStream {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    int flushes;

    @Override
    public void flush() {
      flushes++;
    }

    @Override
    public void write(int b) {
      bytes.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      bytes.write(b, off, len);
    }
  }

  /**
   * Throwing OutputStream — used to verify that an IOException from mid-batch propagates correctly.
   */
  static final class ThrowingOutputStream extends OutputStream {
    final int throwAfterBytes;

    int written;

    ThrowingOutputStream(int throwAfterBytes) {
      this.throwAfterBytes = throwAfterBytes;
    }

    @Override
    public void flush() {
    }

    @Override
    public void write(int b) throws IOException {
      maybeThrow(1);
      written++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      maybeThrow(len);
      written += len;
    }

    private void maybeThrow(int incoming) throws IOException {
      if (written + incoming > throwAfterBytes) {
        throw new IOException("simulated socket failure");
      }
    }
  }

  @Test(timeOut = 5_000)
  public void batched_frames_produce_single_flush() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>(128);
    var out = new CountingOutputStream();
    var writer = new HTTP2FrameWriter(out, new byte[9 + 16384]);

    // Pre-load 5 small DATA frames + the sentinel so drainTo grabs them all in one shot.
    for (int i = 1; i <= 5; i++) {
      queue.put(new HTTP2Frame.DataFrame(i, 0, ("frame-" + i).getBytes()));
    }
    queue.put(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));

    HTTP2Connection.runWriterLoop(queue, writer, out);

    // 5 frames written + sentinel observed → 1 flush, not 5.
    assertEquals(out.flushes, 1, "Expected one flush for the batch of 5 frames; got [" + out.flushes + "]");
    // Sanity: all 5 frames hit the byte stream (9-byte header + 7-byte payload each = 16 bytes per frame).
    assertEquals(out.bytes.size(), 5 * 16, "Expected 5 frames × 16 bytes; got [" + out.bytes.size() + "]");
  }
}
```

- [ ] **Step 2: Run the test — verify it FAILS against the current take-one-flush-each loop**

```bash
latte test --test=HTTP2WriterCoalescingTest
```

Expected: FAIL. `out.flushes` will be 5 (one per frame), not 1.

If the test PASSES at this point, something is wrong with the test setup — investigate before proceeding to Task 5.

---

### Task 5: Implement drainTo coalescing in runWriterLoop

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java` (the `runWriterLoop` static method from Task 3)

- [ ] **Step 1: Add the batch size constant**

Locate the static-field section of `HTTP2Connection` (near line 46-50 where `connectionSendWindowLock`, `MAX_RECENTLY_CLOSED` etc. live). Add:

```java
// Maximum number of frames the writer drains per loop iteration. The blocking head-take is unchanged; this caps
// the opportunistic drainTo that follows. 32 chosen so that even at peerMaxFrameSize=16384 a full batch is ~512KB,
// inside one TCP-window worth of data on a typical link; smaller batches reduce per-frame queue contention
// without holding many MB in userspace under sustained DATA bursts.
private static final int WRITER_BATCH_SIZE = 32;
```

Order this with the other static fields per the project's code-conventions.md (visibility then alphabetical). `static final int` constants typically go before instance fields.

- [ ] **Step 2: Replace the loop body with the take + drainTo pattern**

Change the existing `runWriterLoop`:

```java
static void runWriterLoop(BlockingQueue<HTTP2Frame> queue, HTTP2FrameWriter writer, OutputStream out) throws IOException, InterruptedException {
  while (true) {
    HTTP2Frame f = queue.take();
    if (f instanceof HTTP2Frame.GoawayFrame g && g.lastStreamId() == -1) {
      return;
    }
    writer.writeFrame(f);
    out.flush();
  }
}
```

To:

```java
static void runWriterLoop(BlockingQueue<HTTP2Frame> queue, HTTP2FrameWriter writer, OutputStream out) throws IOException, InterruptedException {
  List<HTTP2Frame> batch = new ArrayList<>(WRITER_BATCH_SIZE);
  while (true) {
    // Blocking head-take — preserves the idle-park behavior of the original loop. Wakes when a producer enqueues.
    HTTP2Frame head = queue.take();
    batch.add(head);
    // Non-blocking opportunistic drain — pulls whatever additional frames concurrent producers have already
    // enqueued. We do NOT wait for more; the cost of waiting would re-introduce per-frame latency. The win is
    // amortizing the syscall (Lever A buffer + this single flush) and the queue-lock acquisition across the batch.
    queue.drainTo(batch, WRITER_BATCH_SIZE - 1);

    for (HTTP2Frame f : batch) {
      if (f instanceof HTTP2Frame.GoawayFrame g && g.lastStreamId() == -1) {
        // Sentinel mid-batch: flush whatever came before it to ensure those frames reach the wire, then exit.
        // Frames after the sentinel in the batch are discarded — the contract is "writer-shutdown immediately"
        // and any post-sentinel work was racing the shutdown anyway.
        out.flush();
        return;
      }
      writer.writeFrame(f);
    }
    out.flush();
    batch.clear();
  }
}
```

Key invariants:
- **Frame order preserved.** drainTo returns frames in FIFO order; iteration preserves it.
- **Sentinel stops the writer at the same point it would have before.** If frames precede the sentinel in the batch they DO get written + flushed (this is a behavior change from the old loop, which would have exited on the same `take()` that returned the sentinel — but the old loop also could never have buffered frames behind the sentinel because each take pulled exactly one frame; the new behavior is strictly more useful: pre-sentinel work reaches the peer).
- **IOException is not caught.** `writeFrame` and `flush` propagate `IOException` to the caller (the lambda's `catch (Exception e)`), preserving the writer-dead teardown path.

- [ ] **Step 3: Run the test from Task 4 — verify it now PASSES**

```bash
latte test --test=HTTP2WriterCoalescingTest
```

Expected: PASS. `out.flushes == 1` and all 5 frames present in the byte stream.

- [ ] **Step 4: Run the full test suite — no regressions**

```bash
latte test --excludePerformance --excludeTimeouts
```

Expected: all 2887 baseline tests pass.

---

### Task 6: Test — sentinel mid-batch flushes preceding frames

Verify the "sentinel observed mid-batch" path: frames before the sentinel must reach the wire; frames after are discarded; the loop exits.

**Files:**
- Modify: `src/test/java/org/lattejava/http/tests/server/HTTP2WriterCoalescingTest.java`

- [ ] **Step 1: Add the test method**

Add to `HTTP2WriterCoalescingTest`:

```java
@Test(timeOut = 5_000)
public void sentinel_mid_batch_flushes_preceding_frames_then_exits() throws Exception {
  var queue = new LinkedBlockingQueue<HTTP2Frame>(128);
  var out = new CountingOutputStream();
  var writer = new HTTP2FrameWriter(out, new byte[9 + 16384]);

  // Queue: 3 frames, then sentinel, then 2 more frames that should NOT be written.
  queue.put(new HTTP2Frame.DataFrame(1, 0, "a".getBytes()));
  queue.put(new HTTP2Frame.DataFrame(2, 0, "b".getBytes()));
  queue.put(new HTTP2Frame.DataFrame(3, 0, "c".getBytes()));
  queue.put(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));
  queue.put(new HTTP2Frame.DataFrame(4, 0, "d".getBytes()));
  queue.put(new HTTP2Frame.DataFrame(5, 0, "e".getBytes()));

  HTTP2Connection.runWriterLoop(queue, writer, out);

  // 3 frames × 10 bytes (9 header + 1 payload) = 30. Frames 4 and 5 must NOT be present.
  assertEquals(out.bytes.size(), 30, "Pre-sentinel frames should hit the wire; got [" + out.bytes.size() + "] bytes");
  assertEquals(out.flushes, 1, "Expected exactly one flush before sentinel exit; got [" + out.flushes + "]");
  // Post-sentinel frames remain in the queue (discarded by the loop, but our test queue is observable).
  assertEquals(queue.size(), 2, "Post-sentinel frames should remain in queue, not be written");
}
```

- [ ] **Step 2: Run the test — verify it PASSES**

```bash
latte test --test=HTTP2WriterCoalescingTest
```

Expected: PASS.

If it fails: the new `runWriterLoop` implementation has a bug in the sentinel-mid-batch handling — fix the implementation, not the test.

---

### Task 7: Test — IOException mid-batch propagates to caller

Verify that an `IOException` raised mid-batch is thrown out of `runWriterLoop` so the writer-thread lambda's catch can run the teardown.

**Files:**
- Modify: `src/test/java/org/lattejava/http/tests/server/HTTP2WriterCoalescingTest.java`

- [ ] **Step 1: Add the test method**

```java
@Test(timeOut = 5_000)
public void io_exception_mid_batch_propagates() throws Exception {
  var queue = new LinkedBlockingQueue<HTTP2Frame>(128);
  // Throws on the 3rd frame. Each frame is 9 (header) + 5 (payload) = 14 bytes. 2 frames = 28 bytes ok;
  // the 3rd push past 28 bytes triggers the throw.
  var out = new ThrowingOutputStream(28);
  var writer = new HTTP2FrameWriter(out, new byte[9 + 16384]);

  for (int i = 1; i <= 5; i++) {
    queue.put(new HTTP2Frame.DataFrame(i, 0, "data!".getBytes()));
  }
  queue.put(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));

  assertThrows(IOException.class, () -> HTTP2Connection.runWriterLoop(queue, writer, out));
  // Sanity: the loop wrote frames 1 and 2 successfully before failing on frame 3.
  assertEquals(out.written, 28, "Expected 2 frames (28 bytes) before the throw; got [" + out.written + "]");
}
```

- [ ] **Step 2: Run the test — verify it PASSES**

```bash
latte test --test=HTTP2WriterCoalescingTest
```

Expected: PASS.

If it fails because the loop catches the exception: the implementation is wrong (it must NOT catch — propagation is the contract).

---

### Task 8: Commit Lever B

- [ ] **Step 1: Run the full suite once more for confidence**

```bash
latte test --excludePerformance --excludeTimeouts
```

Expected: all tests pass, including the three new ones in `HTTP2WriterCoalescingTest`.

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java \
        src/test/java/org/lattejava/http/tests/server/HTTP2WriterCoalescingTest.java
git commit -m "perf(http2): coalesce writer-thread queue drains into single-flush batches

Lever B of Plan F option 1. Replaces the take-one-flush-each loop in
HTTP2Connection.runWriterLoop with a take + drainTo pattern: head
take blocks (preserves idle-park), then drainTo grabs up to 31 more
frames already queued by concurrent producers. The entire batch is
written into the buffered output, then a single flush triggers one
socket write.

Targets the ~18% writer-thread CPU attributed to producer/consumer
contention on LinkedBlockingQueue<HTTP2Frame> in the 2026-05-19 JFR.
Each batched frame avoids one BlockingQueue.take() lock-acquire on
the consumer side; producer-side put() lock acquisitions are
unchanged.

Behavior preserved: frame FIFO order, sentinel-driven shutdown
(pre-sentinel frames in the batch are flushed before exit;
post-sentinel frames are discarded — they were racing the shutdown
anyway). IOException propagates to the writer-thread lambda for
teardown.

Tests added: batched_frames_produce_single_flush,
sentinel_mid_batch_flushes_preceding_frames_then_exits,
io_exception_mid_batch_propagates."
```

---

## Phase 3 — Validate and document

### Task 9: Run h2spec to confirm wire-protocol conformance

The writer-thread changes do not change frame ordering or contents, only flush timing — but h2spec is the cheap insurance.

- [ ] **Step 1: Run h2spec generic suite**

```bash
latte int-h2spec 2>&1 | tail -30
```

Or, if `int-h2spec` does not exist on this branch, fall back to the documented harness in `tools/install-h2spec.sh` and run h2spec directly.

Expected: same pass/fail counts as before this plan started (the project's documented baseline). The 3 flow-control failures noted in `HTTP2.md:616-635` remain (those are SETTINGS_INITIAL_WINDOW_SIZE-driven and unrelated to this work).

- [ ] **Step 2: If new failures appear, investigate before proceeding**

Re-read the failing test's expected wire trace; compare to the actual frame sequence. The most likely cause of any new failure is the sentinel-mid-batch behavior change (pre-sentinel frames now reach the wire that previously didn't — but the old loop also can't have buffered them, so this should be a non-issue in practice). If a real regression, narrow with the test in question.

---

### Task 10: Full benchmark matrix on a cool machine

- [ ] **Step 1: Cool the machine for 15 minutes, no other heavy processes**

The 2026-05-21 finding documents 21–50% throughput variance from thermal alone. Re-running on a hot machine will not produce attributable numbers.

- [ ] **Step 2: Run self h2 scenarios, 3 trials × 30 s, in this order**

The order matters: stream-y scenarios warm the writer thread similarly across vendors.

```bash
./benchmarks/run.sh --server=self --protocol=h2 --duration=30 --trials=3 \
  --scenarios=h2-hello,h2-high-stream-concurrency,h2-high-connection-concurrency,h2-compute,h2-io,h2-stream,h2-large-response,h2-tls-hello,h2-tls-high-stream-concurrency \
  > benchmarks/results/$(date +%Y-%m-%d)-h2-self-post-coalescing.txt
```

(Adjust the scenarios list to match the actual scripts present under `benchmarks/h2-scenarios/`.)

- [ ] **Step 3: Record best-of-3 RPS for each scenario**

Compare against the 2026-05-21 self column in `HTTP2.md:533`. Headline scenarios:
- `h2-stream` — target ≥24k (was 4.1k; Helidon 38k)
- `h2-large-response` — target ≥24k (was 4.1k; Helidon 36k)
- `h2-hello` — must not regress below ~150k
- `h2-high-stream-concurrency` — should improve (more frames per batch in this workload); must not regress below 413k
- `h2-tls-*` — must not regress (these were Latte's standout wins)

- [ ] **Step 4: If the gap to target is large (<50% closed), profile with JFR**

```bash
JAVA_OPTS="-XX:+FlightRecorder -XX:StartFlightRecording=filename=h2-stream-post.jfr,duration=30s,settings=profile" \
  ./benchmarks/run.sh --server=self --protocol=h2 --scenario=h2-stream --duration=35

jfr summary h2-stream-post.jfr
jfr print --events ExecutionSample h2-stream-post.jfr | head -40
```

Look for the new top hotspots. Expected: `write0` and queue-AQS samples both materially lower; new top sites may surface (likely TLS or HPACK encode under sustained load). Document any new hotspot as a follow-up; do not extend this plan to chase them.

---

### Task 11: Update HTTP2.md with measured impact

**Files:**
- Modify: `docs/specs/HTTP2.md` (add a new dated entry to the Performance summary section)

- [ ] **Step 1: Draft the findings entry**

Pattern from existing entries (2026-05-19, 2026-05-21):

```markdown
### Performance findings (YYYY-MM-DD): writer-thread BufferedOutputStream wrap + drainTo coalescing

**Context.** Plan F option 1 (writer-thread architecture work) landed in two stacked changes documented in `docs/superpowers/plans/2026-05-26-h2-writer-thread-coalescing.md`. Lever A: 64 KiB `BufferedOutputStream` between `HTTP2FrameWriter` and the socket (eliminates the per-frame raw write syscall). Lever B: `take() + drainTo()` batched drain of `writerQueue` with a single flush per batch (amortizes producer/consumer queue-lock acquisitions across the batch).

**Throughput delta (best-of-3, 30 s × 3 trials, self only, cool machine):**

| Scenario | Pre (2026-05-21) | Lever A only | Lever A + B | Lever-A delta | Combined delta |
|---|---:|---:|---:|---:|---:|
| `h2-stream` | 4.1k | ??? | ??? | ??? | ??? |
| `h2-large-response` | 4.1k | ??? | ??? | ??? | ??? |
| `h2-hello` | 154k | ??? | ??? | ??? | ??? |
| `h2-high-stream-concurrency` | 454k | ??? | ??? | ??? | ??? |
| `h2-tls-hello` | 328k | ??? | ??? | ??? | ??? |

(Fill the cells from Task 2 and Task 10 measurements.)

**Peer comparison post-change (carrying jetty/netty/tomcat/helidon/undertow from 2026-05-21):**

| Scenario | self | helidon | undertow | netty | Leader |
|---|---:|---:|---:|---:|---|
| `h2-stream` | ??? | 38k | 20k | 32k | ??? |
| `h2-large-response` | ??? | 36k | 32k | 30k | ??? |

**Verification.** `latte test --excludePerformance --excludeTimeouts` → all pass. `h2spec` results unchanged from 2026-05-21 baseline (3 remaining flow-control failures, all SETTINGS_INITIAL_WINDOW_SIZE-driven, unrelated).

**Scope and follow-ups.** [Fill in based on actual measurement.] If the gap to Helidon on h2-stream is still > 30% after this work, the writer-thread bottleneck is no longer the dominant factor; the next investigation target is the new top hotspot from the post-fix JFR (likely TLS encode or HPACK on the encode path).
```

- [ ] **Step 2: Commit the findings entry**

```bash
git add docs/specs/HTTP2.md
git commit -m "docs(http2): record writer-thread coalescing perf impact

Updates HTTP2.md Performance summary with the measured h2-stream /
h2-large-response improvement from the Lever A (BufferedOutputStream)
+ Lever B (drainTo coalescing) work landed in commits <hash-A> and
<hash-B>. Includes pre/post throughput table and updated peer
comparison."
```

---

## Self-review checklist

- ✅ Two independent levers separated for attributable measurement (Lever A buffer, Lever B drainTo)
- ✅ Refactor task (extract `runWriterLoop`) is a pure no-behavior-change commit before any optimization
- ✅ Each behavior change has a unit test, written before the implementation, with expected RED then GREEN
- ✅ Sentinel handling explicitly tested in `sentinel_mid_batch_flushes_preceding_frames_then_exits`
- ✅ IOException propagation explicitly tested in `io_exception_mid_batch_propagates`
- ✅ Wire-protocol conformance check via h2spec called out as Task 9
- ✅ Benchmark plan covers both the targeted scenarios (h2-stream / h2-large-response) and the non-regression scenarios (h2-hello, h2-tls-*)
- ✅ JFR follow-up step for the case where the gap is not closed enough
- ⚠️ Batch size constant `WRITER_BATCH_SIZE = 32` is chosen by reasoning, not measurement. Tune during Task 10 if a clearly-better value surfaces; do not tune without evidence.
- ⚠️ `BufferedOutputStream` size `64 * 1024` is chosen to match typical TCP send buffer. Tune during Task 10 if h2-stream / h2-large-response benefit visibly from a different size.
- ⚠️ Sentinel-mid-batch behavior is a strict improvement over the original loop (pre-sentinel frames in the same batch now reach the wire), but if a downstream test depends on the previous "any frame after the take() that returned the sentinel is silently dropped" behavior, it will surface in Task 9 (h2spec) or the full suite.

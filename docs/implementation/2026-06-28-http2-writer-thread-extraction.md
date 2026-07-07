# HTTP/2 Writer Thread Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the HTTP/2 outbound writer-thread machinery out of `HTTP2Connection` into a new `HTTP2WriterThread implements Runnable` that owns the outbound queue, the thread lifecycle, the drain/flush loop, the `closed` flag, and every enqueue policy — with no wire-behavior change.

**Architecture:** A new `HTTP2WriterThread` holds a private `BlockingQueue<HTTP2Frame>` and the virtual thread that serializes it to the socket. Producers (reader thread, handler threads, error-cleanup path) enqueue through methods (`enqueueOrCloseWriter`, `enqueueBlocking`, `tryEnqueue`) instead of sharing the raw queue. `HTTP2Connection` keeps protocol concerns (GOAWAY semantics, frame dispatch) and a single `volatile HTTP2WriterThread writer` reference. `HTTP2OutputStream` takes the writer object rather than a raw queue.

**Tech Stack:** Java 21 (virtual threads, blocking I/O), the Latte build tool, TestNG. Zero production dependencies.

## Global Constraints

- **Java 21**, virtual threads + blocking I/O (never NIO).
- **License header (new file):** MIT SPDX form — `/*\n * Copyright (c) 2026 The Latte Project\n * SPDX-License-Identifier: MIT\n */` as the very first bytes, no blank line above. `HTTP2Connection`, `HTTP2OutputStream`, and both tests are already MIT — do not touch their headers except the year stays `2026`.
- **Imports:** prefer `import module java.base;` / `import module org.lattejava.http;` over single-class imports; blank line between import groups; alphabetize within a group.
- **Acronyms:** full uppercase in identifiers (`HTTP2WriterThread`, not `Http2WriterThread`).
- **Order inside a class:** static fields → instance fields → constructors (by param count) → static methods → instance methods, each group ordered by visibility then alphabetically. Final instance fields precede non-final, each alphabetized (matches existing `HTTP2Connection`).
- **Error/log message values** wrapped in `[brackets]` — e.g. `logger.debug("Dropping frame [{}] — …", frame)`.
- **Indentation:** 2 spaces; continuation 4 spaces; target line length 120, do not wrap before 120.
- **Javadoc:** American-English sentences/punctuation on the class block and every public method.
- **Test access:** the `h2` package is `exports … to org.lattejava.http.tests`, so anything a test touches must be **`public`** (package-private is not visible to the test module).
- **Branch / commits:** work on the current `http2/writer-thread` branch; never commit to `main`. Conventional Commit subject lines. End every commit message with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

## File Structure

- **Create** `src/main/java/org/lattejava/http/server/internal/h2/HTTP2WriterThread.java` — the new `Runnable`; owns the queue, thread, loop, `closed` flag, enqueue policies.
- **Modify** `src/main/java/org/lattejava/http/server/internal/h2/HTTP2OutputStream.java` — take `HTTP2WriterThread` instead of `BlockingQueue<HTTP2Frame>`.
- **Modify** `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java` — own an `HTTP2WriterThread`; delete the moved code; swap all call sites.
- **Modify** `src/test/java/org/lattejava/http/tests/server/HTTP2WriterCoalescingTest.java` — drive `HTTP2WriterThread.run()` directly.
- **Modify** `src/test/java/org/lattejava/http/tests/server/HTTP2OutputStreamFragmentationTest.java` — build the output stream via `HTTP2WriterThread.forQueue(queue)`.

**Task ordering rationale:** Task 1 adds the new class and migrates its unit test while `HTTP2Connection` keeps its own (now parallel) writer code — the build stays green because `HTTP2Connection` still uses `runWriterLoop` internally and nothing references the old test seam. Task 2 is a single atomic swap: `HTTP2OutputStream`'s constructor signature changes, so every caller (the connection + the fragmentation test) must change in the same commit. This is a refactor guarded by the existing suite; each task's "test" is a green compile + the relevant tests.

---

## Task 1: Create `HTTP2WriterThread` and migrate its unit test

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2WriterThread.java`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2WriterCoalescingTest.java` (rewrite to drive the new class)

**Interfaces:**
- Consumes: `HTTP2Frame` (sealed frame types, same package), `HTTP2FrameWriter` (`void writeFrame(HTTP2Frame)`), `org.lattejava.http.log.Logger` (`void debug(String, Object...)` / `void debug(String, Throwable)`).
- Produces (relied on by Task 2 and the tests):
  - `public HTTP2WriterThread(HTTP2FrameWriter writer, OutputStream out, Thread readerThread, Logger logger)` — production ctor (creates the queue).
  - `public HTTP2WriterThread(HTTP2FrameWriter writer, OutputStream out, Thread readerThread, Logger logger, BlockingQueue<HTTP2Frame> queue)` — queue-injecting ctor (tests).
  - `public static HTTP2WriterThread forQueue(BlockingQueue<HTTP2Frame> queue)`
  - `public void start()`
  - `public boolean isClosed()`
  - `public void join(Duration timeout)`
  - `public boolean enqueueOrCloseWriter(HTTP2Frame frame)`
  - `public void enqueueBlocking(HTTP2Frame frame) throws InterruptedIOException`
  - `public boolean tryEnqueue(HTTP2Frame frame, long timeout, TimeUnit unit) throws InterruptedException`
  - `public boolean requestStop()`
  - `public void run()`

- [ ] **Step 1: Create `HTTP2WriterThread.java`**

Create `src/main/java/org/lattejava/http/server/internal/h2/HTTP2WriterThread.java` with exactly this content:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

/**
 * Owns the single virtual thread that serializes every outbound HTTP/2 frame for one connection. Producers — the reader
 * thread (PING acks, WINDOW_UPDATE, RST_STREAM, SETTINGS ack, GOAWAY) and the per-request handler threads (response
 * HEADERS, DATA, trailers) — enqueue frames through {@link #enqueueOrCloseWriter}, {@link #enqueueBlocking}, and
 * {@link #tryEnqueue}; the queue itself is private and never handed out.
 *
 * <p>{@link #run()} blocks for the next frame, opportunistically drains a batch, writes each frame through the
 * {@link HTTP2FrameWriter}, and flushes once per batch. It exits when it dequeues the writer-shutdown sentinel (a
 * {@link HTTP2Frame.GoawayFrame} with {@code lastStreamId == -1}, enqueued via {@link #requestStop()}). On exit —
 * whether the clean sentinel return or a mid-write {@link IOException} from a broken pipe / peer reset — it marks
 * itself closed and interrupts the reader thread so the connection tears down.
 *
 * @author Daniel DeGroff
 */
public class HTTP2WriterThread implements Runnable {
  // Maximum number of frames the writer drains per loop iteration. The blocking head-take is unchanged; this caps the
  // opportunistic drainTo that follows. 32 chosen so that even at peerMaxFrameSize=16384 a full batch is ~512KB, inside
  // one TCP-window worth of data on a typical link; smaller batches reduce per-frame queue contention without holding
  // many MB in userspace under sustained DATA bursts.
  private static final int BATCH_SIZE = 32;

  // The writer-shutdown sentinel: a GOAWAY whose lastStreamId is -1 (negative, never valid for a real GOAWAY). Its
  // encoding is private to this class — callers request shutdown via requestStop(), never by building this frame.
  private static final HTTP2Frame SHUTDOWN_SENTINEL = new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]);

  private final Logger logger;
  private final OutputStream out;
  private final BlockingQueue<HTTP2Frame> queue;
  private final Thread readerThread;
  private final HTTP2FrameWriter writer;

  private volatile boolean closed;
  private volatile Thread thread;

  /**
   * Production constructor. Creates the outbound queue, which never escapes this instance.
   *
   * @param writer       the wire-frame encoder.
   * @param out          the buffered socket output stream flushed once per batch.
   * @param readerThread the connection reader thread, interrupted when this writer closes.
   * @param logger       the connection logger.
   */
  public HTTP2WriterThread(HTTP2FrameWriter writer, OutputStream out, Thread readerThread, Logger logger) {
    this(writer, out, readerThread, logger, new LinkedBlockingQueue<>(128));
  }

  /**
   * Queue-injecting constructor for tests that need to pre-load frames and drive {@link #run()} directly. Production
   * code uses the four-argument constructor.
   */
  public HTTP2WriterThread(HTTP2FrameWriter writer, OutputStream out, Thread readerThread, Logger logger,
                           BlockingQueue<HTTP2Frame> queue) {
    this.writer = writer;
    this.out = out;
    this.readerThread = readerThread;
    this.logger = logger;
    this.queue = queue;
  }

  /**
   * Test seam — wraps a caller-supplied queue so a test can inject frames via {@link #enqueueBlocking} and drain them
   * for assertions, with no socket or thread. Only the enqueue methods are valid on the returned instance; the frame
   * writer, reader thread, and logger are {@code null}.
   *
   * @param queue the queue to expose through the enqueue methods.
   * @return a writer backed by {@code queue}.
   */
  public static HTTP2WriterThread forQueue(BlockingQueue<HTTP2Frame> queue) {
    return new HTTP2WriterThread(null, null, null, null, queue);
  }

  /**
   * Blocks until {@code frame} can be enqueued, surfacing an interrupt as {@link InterruptedIOException}. The block is
   * the intended queue-full back-pressure on a producing handler thread. Used for response DATA frames.
   *
   * @param frame the frame to enqueue.
   * @throws InterruptedIOException if the calling thread is interrupted while waiting.
   */
  public void enqueueBlocking(HTTP2Frame frame) throws InterruptedIOException {
    try {
      queue.put(frame);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException();
    }
  }

  /**
   * Enqueues {@code frame} with a bounded wait. Returns {@code false} (logging at debug) if the writer is already
   * closed, or if the queue stays full past the timeout — in which case the writer is declared closed. Reader-side and
   * response-header callers use this; most ignore the result on fire-and-forget paths.
   *
   * @param frame the frame to enqueue.
   * @return {@code true} if the frame was enqueued.
   */
  public boolean enqueueOrCloseWriter(HTTP2Frame frame) {
    if (closed) {
      logger.debug("Dropping frame [{}] — writer thread already closed", frame);
      return false;
    }

    try {
      if (!queue.offer(frame, 5, TimeUnit.SECONDS)) {
        logger.debug("Writer queue full for [5s]; declaring writer closed and dropping frame [{}]", frame);
        closed = true;
        return false;
      }
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Returns whether the writer has closed — set when the loop exits or when {@link #enqueueOrCloseWriter} times out.
   *
   * @return {@code true} once the writer is closed.
   */
  public boolean isClosed() {
    return closed;
  }

  /**
   * Waits up to {@code timeout} for the writer thread to finish, returning immediately if it was never started. Swallows
   * an interrupt (re-setting the calling thread's interrupt status) so teardown call sites stay terse.
   *
   * @param timeout the maximum time to wait.
   */
  public void join(Duration timeout) {
    Thread t = thread;
    if (t == null) {
      return;
    }
    try {
      t.join(timeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Enqueues the writer-shutdown sentinel so {@link #run()} flushes whatever is queued and then exits. A no-op if the
   * writer is already closed.
   *
   * @return {@code true} if the sentinel was enqueued.
   */
  public boolean requestStop() {
    return enqueueOrCloseWriter(SHUTDOWN_SENTINEL);
  }

  @Override
  public void run() {
    try {
      List<HTTP2Frame> batch = new ArrayList<>(BATCH_SIZE);
      while (true) {
        // Blocking head-take preserves the idle-park behavior; the non-blocking drainTo coalesces whatever concurrent
        // producers have already enqueued so the syscall and queue-lock are amortized across the batch.
        HTTP2Frame head = queue.take();
        batch.add(head);
        queue.drainTo(batch, BATCH_SIZE - 1);

        for (HTTP2Frame frame : batch) {
          if (frame instanceof HTTP2Frame.GoawayFrame goaway && goaway.lastStreamId() == -1) {
            // Sentinel: flush whatever preceded it, then exit. Frames after the sentinel in the batch are discarded.
            out.flush();
            return;
          }
          writer.writeFrame(frame);
        }
        out.flush();
        batch.clear();
      }
    } catch (Exception e) {
      logger.debug("Writer thread ended unexpectedly; signaling reader", e);
    } finally {
      closed = true;
      readerThread.interrupt();
    }
  }

  /**
   * Spawns the {@code h2-writer} virtual thread running this loop and captures the handle for {@link #join}.
   */
  public void start() {
    thread = Thread.ofVirtual().name("h2-writer").start(this);
  }

  /**
   * Best-effort timed enqueue that never closes the writer. Used only by the handler error-cleanup path, which must not
   * block connection teardown.
   *
   * @param frame   the frame to enqueue.
   * @param timeout the maximum time to wait.
   * @param unit    the unit of {@code timeout}.
   * @return {@code true} if the frame was accepted within the timeout.
   * @throws InterruptedException if interrupted while waiting.
   */
  public boolean tryEnqueue(HTTP2Frame frame, long timeout, TimeUnit unit) throws InterruptedException {
    return queue.offer(frame, timeout, unit);
  }
}
```

- [ ] **Step 2: Rewrite `HTTP2WriterCoalescingTest` to drive `HTTP2WriterThread.run()`**

Replace the entire contents of `src/test/java/org/lattejava/http/tests/server/HTTP2WriterCoalescingTest.java` with:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.log.AccumulatingLogger;
import org.lattejava.http.server.internal.h2.HTTP2Frame;
import org.lattejava.http.server.internal.h2.HTTP2FrameWriter;
import org.lattejava.http.server.internal.h2.HTTP2WriterThread;

import static org.testng.Assert.*;

/**
 * Unit tests for the writer-thread loop ({@link HTTP2WriterThread#run()}). Verifies that the loop batches frames already
 * queued at drain time into a single flush, exits cleanly on the sentinel even when other frames are batched ahead of
 * it, and — on a mid-batch write failure — marks the writer closed and interrupts the reader instead of propagating the
 * exception.
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
   * Throwing OutputStream — used to verify that a mid-batch IOException closes the writer. The byte threshold in the
   * test ({@code throwAfterBytes}) assumes {@link HTTP2FrameWriter} issues one {@code write(byte[], int, int)} call per
   * frame (header + payload concatenated into the shared write buffer). If that ever changes to split header and payload
   * into separate writes, the threshold in {@code io_exception_mid_batch_closes_writer_and_interrupts_reader} will need
   * to be recomputed.
   */
  static final class ThrowingOutputStream extends OutputStream {
    final int throwAfterBytes;
    int written;

    ThrowingOutputStream(int throwAfterBytes) {
      this.throwAfterBytes = throwAfterBytes;
    }

    @Override
    public void flush() {}

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
    var frameWriter = new HTTP2FrameWriter(out, new byte[9 + 16384]);
    var writerThread = new HTTP2WriterThread(frameWriter, out, new Thread(), new AccumulatingLogger(), queue);

    // Pre-load 5 small DATA frames + the sentinel so drainTo grabs them all in one shot.
    for (int i = 1; i <= 5; i++) {
      queue.put(new HTTP2Frame.DataFrame(i, 0, ("frame-" + i).getBytes()));
    }
    queue.put(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));

    writerThread.run();

    // 5 frames written + sentinel observed → 1 flush, not 5.
    assertEquals(out.flushes, 1, "Expected one flush for the batch of 5 frames; got [" + out.flushes + "]");
    // Sanity: all 5 frames hit the byte stream (9-byte header + 7-byte payload each = 16 bytes per frame).
    assertEquals(out.bytes.size(), 5 * 16, "Expected 5 frames × 16 bytes; got [" + out.bytes.size() + "]");
  }

  @Test(timeOut = 5_000)
  public void sentinel_mid_batch_flushes_preceding_frames_then_exits() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>(128);
    var out = new CountingOutputStream();
    var frameWriter = new HTTP2FrameWriter(out, new byte[9 + 16384]);
    var writerThread = new HTTP2WriterThread(frameWriter, out, new Thread(), new AccumulatingLogger(), queue);

    // Queue: 3 frames, then sentinel, then 2 more frames that should NOT be written.
    queue.put(new HTTP2Frame.DataFrame(1, 0, "a".getBytes()));
    queue.put(new HTTP2Frame.DataFrame(2, 0, "b".getBytes()));
    queue.put(new HTTP2Frame.DataFrame(3, 0, "c".getBytes()));
    queue.put(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));
    queue.put(new HTTP2Frame.DataFrame(4, 0, "d".getBytes()));
    queue.put(new HTTP2Frame.DataFrame(5, 0, "e".getBytes()));

    writerThread.run();

    // 3 frames × 10 bytes (9 header + 1 payload) = 30. Frames 4 and 5 must NOT be present.
    assertEquals(out.bytes.size(), 30, "Pre-sentinel frames should hit the wire; got [" + out.bytes.size() + "] bytes");
    assertEquals(out.flushes, 1, "Expected exactly one flush before sentinel exit; got [" + out.flushes + "]");
    // Post-sentinel frames were drained into the batch by drainTo but not written — the loop exits on the sentinel
    // before reaching them. They are not in the queue (drainTo moved them) and not in the output (loop exited).
    assertTrue(queue.isEmpty(), "Queue should be empty — drainTo moved post-sentinel frames into the batch, which was discarded on sentinel exit; got queue size [" + queue.size() + "]");
  }

  @Test(timeOut = 5_000)
  public void io_exception_mid_batch_closes_writer_and_interrupts_reader() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>(128);
    // Throws on the 3rd frame. Each frame is 9 (header) + 5 (payload) = 14 bytes. 2 frames = 28 bytes ok;
    // the 3rd push past 28 bytes triggers the throw.
    var out = new ThrowingOutputStream(28);
    var frameWriter = new HTTP2FrameWriter(out, new byte[9 + 16384]);
    var readerStub = new Thread();
    var writerThread = new HTTP2WriterThread(frameWriter, out, readerStub, new AccumulatingLogger(), queue);

    for (int i = 1; i <= 5; i++) {
      queue.put(new HTTP2Frame.DataFrame(i, 0, "data!".getBytes()));
    }
    queue.put(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));

    // run() catches the mid-batch IOException (its production contract), so it returns normally rather than throwing.
    writerThread.run();

    // Sanity: the loop wrote frames 1 and 2 successfully before failing on frame 3.
    assertEquals(out.written, 28, "Expected 2 frames (28 bytes) before the throw; got [" + out.written + "]");
    assertTrue(writerThread.isClosed(), "Writer should mark itself closed after a mid-batch write failure");
    assertTrue(readerStub.isInterrupted(), "Reader thread should be interrupted so the connection tears down");
  }
}
```

- [ ] **Step 3: Build and run the migrated unit test**

Run: `latte clean build && latte test --test=HTTP2WriterCoalescingTest`
Expected: build succeeds (`HTTP2Connection` still compiles unchanged — it keeps its own `runWriterLoop`), and all three `HTTP2WriterCoalescingTest` methods PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/h2/HTTP2WriterThread.java \
        src/test/java/org/lattejava/http/tests/server/HTTP2WriterCoalescingTest.java
git commit -m "$(cat <<'EOF'
refactor: add HTTP2WriterThread and migrate writer-loop unit test

Introduce HTTP2WriterThread (the outbound queue + virtual thread + drain
loop, owning the closed flag and the enqueue policies) and rewrite the
coalescing unit test to drive its run() directly. HTTP2Connection still
uses its own writer code until the next task wires it through.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Route `HTTP2Connection` and `HTTP2OutputStream` through `HTTP2WriterThread`

This task changes `HTTP2OutputStream`'s constructor signature, so every caller must change in the same commit. Apply all edits, then build + run the full suite once.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2OutputStream.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2OutputStreamFragmentationTest.java`

**Interfaces:**
- Consumes (from Task 1): all `HTTP2WriterThread` public methods listed in Task 1's Produces block.
- Produces:
  - `HTTP2OutputStream(HTTP2Stream stream, HTTP2WriterThread writer, int peerMaxFrameSize)`
  - `HTTP2OutputStream(HTTP2Stream stream, HTTP2WriterThread writer, HTTP2ConnectionWindow connectionWindow, int peerMaxFrameSize)`

- [ ] **Step 1: Update `HTTP2OutputStream` to take `HTTP2WriterThread`**

In `src/main/java/org/lattejava/http/server/internal/h2/HTTP2OutputStream.java`:

(a) Replace the field declaration:

```java
  private final BlockingQueue<HTTP2Frame> writerQueue;
```

with:

```java
  private final HTTP2WriterThread writer;
```

(b) Replace the two-argument (standalone/test) constructor's Javadoc + body. Change:

```java
  public HTTP2OutputStream(HTTP2Stream stream, BlockingQueue<HTTP2Frame> writerQueue, int peerMaxFrameSize) {
    this(stream, writerQueue, new HTTP2ConnectionWindow(Integer.MAX_VALUE), peerMaxFrameSize);
  }
```

to:

```java
  public HTTP2OutputStream(HTTP2Stream stream, HTTP2WriterThread writer, int peerMaxFrameSize) {
    this(stream, writer, new HTTP2ConnectionWindow(Integer.MAX_VALUE), peerMaxFrameSize);
  }
```

(c) Replace the four-argument constructor. Change:

```java
  public HTTP2OutputStream(HTTP2Stream stream, BlockingQueue<HTTP2Frame> writerQueue, HTTP2ConnectionWindow connectionWindow, int peerMaxFrameSize) {
    this.stream = stream;
    this.writerQueue = writerQueue;
    this.connectionWindow = connectionWindow;
    this.peerMaxFrameSize = peerMaxFrameSize;
  }
```

to:

```java
  public HTTP2OutputStream(HTTP2Stream stream, HTTP2WriterThread writer, HTTP2ConnectionWindow connectionWindow, int peerMaxFrameSize) {
    this.stream = stream;
    this.writer = writer;
    this.connectionWindow = connectionWindow;
    this.peerMaxFrameSize = peerMaxFrameSize;
  }
```

(d) Replace the private `enqueue` method. Change:

```java
  private void enqueue(HTTP2Frame.DataFrame frame) throws InterruptedIOException {
    try {
      writerQueue.put(frame);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException();
    }
  }
```

to:

```java
  private void enqueue(HTTP2Frame.DataFrame frame) throws InterruptedIOException {
    writer.enqueueBlocking(frame);
  }
```

(e) In the two-argument constructor's Javadoc, the sentence "Production code must use the `{@link #HTTP2OutputStream(HTTP2Stream, BlockingQueue, HTTP2ConnectionWindow, int)}` overload." references the old signature — update the `{@link}` target to `{@link #HTTP2OutputStream(HTTP2Stream, HTTP2WriterThread, HTTP2ConnectionWindow, int)}`.

- [ ] **Step 2: Update `HTTP2OutputStreamFragmentationTest` to build via `forQueue`**

In `src/test/java/org/lattejava/http/tests/server/HTTP2OutputStreamFragmentationTest.java`, every `new HTTP2OutputStream(stream, queue, …)` now passes a writer instead of the raw queue. For each of the five constructions, wrap the existing `queue` local with `HTTP2WriterThread.forQueue(queue)`:

- `new HTTP2OutputStream(stream, queue, connectionWindow, 16)` → `new HTTP2OutputStream(stream, HTTP2WriterThread.forQueue(queue), connectionWindow, 16)`
- `new HTTP2OutputStream(stream, queue, 16384)` → `new HTTP2OutputStream(stream, HTTP2WriterThread.forQueue(queue), 16384)` (appears three times — lines around 63, 101, 121)
- `new HTTP2OutputStream(stream, queue, 16)` → `new HTTP2OutputStream(stream, HTTP2WriterThread.forQueue(queue), 16)` (the tiny-max-frame case around line 76)

The `queue` declarations, the `queue.poll()/take()` drain assertions, and everything else stay exactly as they are — `enqueueBlocking()` lands frames on the very `queue` instance passed to `forQueue`. Add the import `import org.lattejava.http.server.internal.h2.HTTP2WriterThread;` in the same import group as the other `server.internal.h2` imports (alphabetical order).

- [ ] **Step 3: Rewire `HTTP2Connection` — fields**

In `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java`:

(a) Delete the constant (lines ~32-36):

```java
  // Maximum number of frames the writer drains per loop iteration. The blocking head-take is unchanged; this caps the
  // opportunistic drainTo that follows. 32 chosen so that even at peerMaxFrameSize=16384 a full batch is ~512KB,
  // inside one TCP-window worth of data on a typical link; smaller batches reduce per-frame queue contention
  // without holding many MB in userspace under sustained DATA bursts.
  private static final int WRITER_BATCH_SIZE = 32;
```

(b) Delete the field:

```java
  private final BlockingQueue<HTTP2Frame> writerQueue = new LinkedBlockingQueue<>(128);
```

(c) Delete the two volatile fields and their comments:

```java
  // Set true when the writer virtual-thread exits (either via the shutdown sentinel or an unexpected exception).
  // The reader checks this before each blocking enqueue to avoid parking forever on a full writerQueue.
  private volatile boolean writerDead;
```

```java
  // Writer thread handle, captured in run() so shutdown() (invoked from the acceptor thread) can join it after
  // enqueuing the graceful GOAWAY, guaranteeing the GOAWAY is flushed before the connection's thread is interrupted.
  private volatile Thread writerThread;
```

(d) Add the new field in the non-final group, alphabetically after `state` (i.e. immediately after the `state` field declaration):

```java
  // The outbound writer: owns the frame queue and the virtual thread that serializes frames to the socket. Built after
  // the SETTINGS exchange in run(); volatile because shutdown() reads it from the acceptor thread.
  private volatile HTTP2WriterThread writer;
```

- [ ] **Step 4: Rewire `HTTP2Connection` — delete `runWriterLoop` and `enqueueForWriter`**

(a) Delete the entire `runWriterLoop` static method, including its Javadoc (the block from `/**` … `Writer-thread loop body …` through the closing brace of the method — lines ~120-153).

(b) Delete the entire private `enqueueForWriter` method, including its Javadoc (lines ~484-510).

- [ ] **Step 5: Rewire `HTTP2Connection` — `run()` writer setup**

In `run()`, rename the local `HTTP2FrameWriter` from `writer` to `frameWriter`, and replace the writer-thread spawn with `HTTP2WriterThread` construction. Change this block:

```java
      var writer = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
      var reader = new HTTP2FrameReader(inputStream, buffers.frameReadBuffer());

      // Send our initial SETTINGS frame. The client connection preface was already read and validated by
      // ProtocolSelector, so we begin directly at the SETTINGS exchange.
      writer.writeFrame(new HTTP2Frame.SettingsFrame(0, localSettings.toPayload()));
      out.flush();

      // Read the peer's first SETTINGS frame.
      var firstFrame = reader.readFrame();
      if (!(firstFrame instanceof HTTP2Frame.SettingsFrame(int flags, byte[] payload)) || (flags & HTTP2Frame.FLAG_ACK) != 0) {
        logger.debug("Expected client SETTINGS frame after preface");
        // RFC 9113 §3.5 / §5.4.1: emit GOAWAY(PROTOCOL_ERROR) before closing.
        sendGoAwayDirect(writer, out, HTTP2ErrorCode.PROTOCOL_ERROR);
        // Half-close immediately so the kernel sends FIN — h2spec keeps writing preface bytes;
        // bytes arriving after the 50 ms finally-drain race the close and cause OS RST instead of FIN.
        try {
          socket.shutdownOutput();
        } catch (IOException ignore) { /* best effort */ }
        return;
      }
      peerSettings.applyPayload(payload);

      // RFC 9113 §4.2: outbound DATA frames may be up to peer's SETTINGS_MAX_FRAME_SIZE. Grow the write buffer
      // if the peer accepts larger frames than we configured locally; the writer holds a byte[] reference, so
      // we must rebuild it to pick up the new buffer. Safe to swap here — the writer thread has not started yet.
      if (peerSettings.maxFrameSize() > localSettings.maxFrameSize()) {
        buffers.ensureFrameWriteCapacity(peerSettings.maxFrameSize());
        writer = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
      }

      // Send SETTINGS ACK.
      writer.writeFrame(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
      out.flush();

      // Spawn the writer virtual-thread. It drains writerQueue and serializes frames to the socket.
      // It exits cleanly when it dequeues the writer-shutdown sentinel:
      //   a GoawayFrame with lastStreamId == -1 (negative, never valid for a real GOAWAY).
      // The thread reference is stored so the reader thread can join it before closing the socket,
      // guaranteeing that GOAWAY frames are fully flushed before the connection is torn down.
      HTTP2FrameWriter writerForThread = writer;
      OutputStream outForThread = out;
      writerThread = Thread.ofVirtual().name("h2-writer").start(() -> {
        try {
          runWriterLoop(writerQueue, writerForThread, outForThread);
        } catch (Exception e) {
          logger.debug("Writer thread ended unexpectedly; signaling reader", e);
        } finally {
          // Signal the reader and any handler-thread enqueuers that the writer is gone. Without this the reader
          // would park forever on a full writerQueue (broken-pipe / peer-reset mid-write deadlock). The reader's
          // finally block then interrupts any handler virtual-threads still waiting on the queue.
          writerDead = true;
          Thread readerThreadRef = readerThread;
          if (readerThreadRef != null) {
            readerThreadRef.interrupt();
          }
        }
      });
```

to:

```java
      var frameWriter = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
      var reader = new HTTP2FrameReader(inputStream, buffers.frameReadBuffer());

      // Send our initial SETTINGS frame. The client connection preface was already read and validated by
      // ProtocolSelector, so we begin directly at the SETTINGS exchange.
      frameWriter.writeFrame(new HTTP2Frame.SettingsFrame(0, localSettings.toPayload()));
      out.flush();

      // Read the peer's first SETTINGS frame.
      var firstFrame = reader.readFrame();
      if (!(firstFrame instanceof HTTP2Frame.SettingsFrame(int flags, byte[] payload)) || (flags & HTTP2Frame.FLAG_ACK) != 0) {
        logger.debug("Expected client SETTINGS frame after preface");
        // RFC 9113 §3.5 / §5.4.1: emit GOAWAY(PROTOCOL_ERROR) before closing.
        sendGoAwayDirect(frameWriter, out, HTTP2ErrorCode.PROTOCOL_ERROR);
        // Half-close immediately so the kernel sends FIN — h2spec keeps writing preface bytes;
        // bytes arriving after the 50 ms finally-drain race the close and cause OS RST instead of FIN.
        try {
          socket.shutdownOutput();
        } catch (IOException ignore) { /* best effort */ }
        return;
      }
      peerSettings.applyPayload(payload);

      // RFC 9113 §4.2: outbound DATA frames may be up to peer's SETTINGS_MAX_FRAME_SIZE. Grow the write buffer
      // if the peer accepts larger frames than we configured locally; the frame writer holds a byte[] reference, so
      // we must rebuild it to pick up the new buffer. Safe to swap here — the writer thread has not started yet.
      if (peerSettings.maxFrameSize() > localSettings.maxFrameSize()) {
        buffers.ensureFrameWriteCapacity(peerSettings.maxFrameSize());
        frameWriter = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
      }

      // Send SETTINGS ACK.
      frameWriter.writeFrame(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
      out.flush();

      // Start the writer: it drains its private queue and serializes frames to the socket, exiting on the
      // writer-shutdown sentinel (requestStop). It is stored in a field so the reader can join it before closing
      // the socket, guaranteeing GOAWAY frames are flushed before teardown, and so shutdown() can reach it.
      writer = new HTTP2WriterThread(frameWriter, out, Thread.currentThread(), logger);
      writer.start();
```

- [ ] **Step 6: Rewire `HTTP2Connection` — reader-loop check and frame-loop finally**

(a) In the frame loop, change:

```java
          if (writerDead) {
            logger.debug("Writer thread dead; reader exiting");
            break;
          }
```

to:

```java
          if (writer.isClosed()) {
            logger.debug("Writer thread closed; reader exiting");
            break;
          }
```

(b) In the inner `try` block's `finally` (the one that signals the writer to stop), change:

```java
      } finally {
        // Signal writer thread to exit cleanly. If the writer has already died, the sentinel is a no-op.
        enqueueForWriter(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));
      }
```

to:

```java
      } finally {
        // Signal the writer thread to exit cleanly. If the writer has already closed, this is a no-op.
        writer.requestStop();
      }
```

- [ ] **Step 7: Rewire `HTTP2Connection` — outer `finally` join**

Change:

```java
      try {
        if (writerThread != null) {
          writerThread.join(Duration.ofSeconds(5));
        }
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
```

to:

```java
      // Wait for the writer to flush its queue (including any GOAWAY) before closing the socket. join() is a no-op if
      // the writer was never started (e.g. a failure before the SETTINGS exchange).
      if (writer != null) {
        writer.join(Duration.ofSeconds(5));
      }
```

- [ ] **Step 8: Rewire `HTTP2Connection` — `shutdown()`**

Replace the body of `shutdown()`:

```java
  @Override
  public void shutdown() {
    enqueueForWriter(new HTTP2Frame.GoawayFrame(highestSeenStreamId, HTTP2ErrorCode.NO_ERROR.value, new byte[0]));
    // Writer-shutdown sentinel (lastStreamId == -1) so the writer flushes the GOAWAY above and then exits.
    enqueueForWriter(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));

    Thread w = writerThread;
    if (w != null) {
      try {
        w.join(Duration.ofSeconds(1));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
```

with:

```java
  @Override
  public void shutdown() {
    // shutdown() runs on the acceptor thread; writer is null until the SETTINGS exchange builds it. Snapshot + guard so
    // an early shutdown cannot NPE. Nothing can be flushed before the writer exists anyway.
    HTTP2WriterThread w = writer;
    if (w != null) {
      w.enqueueOrCloseWriter(new HTTP2Frame.GoawayFrame(highestSeenStreamId, HTTP2ErrorCode.NO_ERROR.value, new byte[0]));
      // Writer-shutdown sentinel so the writer flushes the GOAWAY above and then exits.
      w.requestStop();
      w.join(Duration.ofSeconds(1));
    }
  }
```

- [ ] **Step 9: Rewire `HTTP2Connection` — remaining enqueue call sites**

Swap each remaining `enqueueForWriter(...)` and direct-queue call. Apply these exact replacements (each appears once unless noted):

- In `goAway`: `enqueueForWriter(new HTTP2Frame.GoawayFrame(highestSeenStreamId, code.value, new byte[0]));` → `writer.enqueueOrCloseWriter(new HTTP2Frame.GoawayFrame(highestSeenStreamId, code.value, new byte[0]));`
- In `handleData` (two sites): `enqueueForWriter(new HTTP2Frame.WindowUpdateFrame(f.streamId(), delta));` → `writer.enqueueOrCloseWriter(new HTTP2Frame.WindowUpdateFrame(f.streamId(), delta));` and `enqueueForWriter(new HTTP2Frame.WindowUpdateFrame(0, f.payload().length));` → `writer.enqueueOrCloseWriter(new HTTP2Frame.WindowUpdateFrame(0, f.payload().length));`
- In `handleHeadersFrame`: `enqueueForWriter(new HTTP2Frame.RSTStreamFrame(f.streamId(), HTTP2ErrorCode.REFUSED_STREAM.value));` → `writer.enqueueOrCloseWriter(new HTTP2Frame.RSTStreamFrame(f.streamId(), HTTP2ErrorCode.REFUSED_STREAM.value));`
- In `handlePing`: `enqueueForWriter(new HTTP2Frame.PingFrame(HTTP2Frame.FLAG_ACK, f.opaqueData()));` → `writer.enqueueOrCloseWriter(new HTTP2Frame.PingFrame(HTTP2Frame.FLAG_ACK, f.opaqueData()));`
- In `handleSettings`: `enqueueForWriter(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));` → `writer.enqueueOrCloseWriter(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));`
- In `rstStream`: `enqueueForWriter(new HTTP2Frame.RSTStreamFrame(streamId, code.value));` → `writer.enqueueOrCloseWriter(new HTTP2Frame.RSTStreamFrame(streamId, code.value));`
- In `spawnHandlerThread`'s generic `catch (Exception e)` cleanup, change the direct offer:

```java
          if (!writerQueue.offer(new HTTP2Frame.RSTStreamFrame(stream.streamId(), HTTP2ErrorCode.INTERNAL_ERROR.value),
              100, TimeUnit.MILLISECONDS)) {
```

to:

```java
          if (!writer.tryEnqueue(new HTTP2Frame.RSTStreamFrame(stream.streamId(), HTTP2ErrorCode.INTERNAL_ERROR.value),
              100, TimeUnit.MILLISECONDS)) {
```

(the surrounding `try { … } catch (InterruptedException ie) { … }` stays.)

- In `HTTP2OutputProtocol.commitHeaders`:
  - `if (!enqueueForWriter(new HTTP2Frame.HeadersFrame(stream.streamId(), HTTP2Frame.FLAG_END_HEADERS, headerBlock))) {` → `if (!writer.enqueueOrCloseWriter(new HTTP2Frame.HeadersFrame(stream.streamId(), HTTP2Frame.FLAG_END_HEADERS, headerBlock))) {`
  - `sink = new HTTP2OutputStream(stream, writerQueue, connectionSendWindow, peerSettings.maxFrameSize());` → `sink = new HTTP2OutputStream(stream, writer, connectionSendWindow, peerSettings.maxFrameSize());`
- In `HTTP2OutputProtocol.commitTrailers`: `enqueueForWriter(new HTTP2Frame.HeadersFrame(stream.streamId(),` → `writer.enqueueOrCloseWriter(new HTTP2Frame.HeadersFrame(stream.streamId(),` (the rest of that statement, spanning two lines, is unchanged).

- [ ] **Step 10: Rewire `HTTP2Connection` — class Javadoc and stray comments**

(a) In the class Javadoc threading-model list, the writer bullet currently reads:

```java
 *   <li>A single writer virtual-thread serializes all outbound frames from {@link #writerQueue} to the
 *       socket. It exits when it dequeues the writer-shutdown sentinel (a GoawayFrame with lastStreamId == -1).</li>
```

Replace it with (the `{@link #writerQueue}` target no longer exists):

```java
 *   <li>A single {@link HTTP2WriterThread} serializes all outbound frames to the socket. It exits when it
 *       dequeues the writer-shutdown sentinel (a GoawayFrame with lastStreamId == -1).</li>
```

(b) In the `handlerThreads` field comment, the phrase "so handlers parked on writerQueue.put() or in HTTP2OutputStream's flow-control wait loop unblock" still reads fine conceptually, but update `writerQueue.put()` to `the writer queue` so it does not name a deleted field:

```java
  // teardown path interrupts every thread in this set so handlers parked on the writer queue or in HTTP2OutputStream's
```

(c) In the outer `finally`, the comment "Interrupt any handler virtual-threads still parked on writerQueue.put or in the per-stream send-window" — change `writerQueue.put` to `the writer queue`:

```java
      // Interrupt any handler virtual-threads still parked on the writer queue or in the per-stream send-window
```

- [ ] **Step 11: Build and run the full suite**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: compiles cleanly and the entire suite is green — in particular `HTTP2WriterCoalescingTest`, `HTTP2OutputStreamFragmentationTest`, `HTTP2BasicTest`, `HTTP2GoawayTest`, `HTTP2FlowControlTest`, `HTTP2RawFrameTest`, and the h2spec batch tests. There must be no remaining references to `writerQueue`, `writerThread`, `writerDead`, `runWriterLoop`, or `enqueueForWriter` in `HTTP2Connection.java` (verify with `grep -n "writerQueue\|writerThread\|writerDead\|runWriterLoop\|enqueueForWriter" src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java` → no output).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java \
        src/main/java/org/lattejava/http/server/internal/h2/HTTP2OutputStream.java \
        src/test/java/org/lattejava/http/tests/server/HTTP2OutputStreamFragmentationTest.java
git commit -m "$(cat <<'EOF'
refactor: route HTTP/2 outbound frames through HTTP2WriterThread

HTTP2Connection now owns an HTTP2WriterThread instead of a raw queue +
thread handle + dead flag + inline loop. HTTP2OutputStream takes the
writer object and enqueues via enqueueBlocking. Deletes runWriterLoop,
enqueueForWriter, writerQueue, writerThread, writerDead, and
WRITER_BATCH_SIZE from HTTP2Connection. No wire-behavior change.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage** (against `docs/design/2026-06-28-http2-writer-thread-extraction-design.md`):
- New `HTTP2WriterThread` class with private queue, `closed` flag, thread lifecycle, loop, three enqueue policies + `requestStop` + `forQueue` → Task 1, Step 1. ✓
- Queue private; producers route through methods → Task 1 (methods) + Task 2 Steps 1, 9. ✓
- `HTTP2OutputStream` takes the writer → Task 2, Step 1. ✓
- `HTTP2Connection` owns `volatile HTTP2WriterThread writer`, built after SETTINGS, local renamed to `frameWriter` → Task 2, Steps 3, 5. ✓
- `shutdown()` / `join()` early-`null` guard → Task 2, Steps 7, 8 (`if (writer != null)` / `HTTP2WriterThread w = writer; if (w != null)`). ✓
- Reader-interrupt-on-close, sentinel encoding private, `requestStop()` at both stop sites → Task 1 `run()`/`requestStop`, Task 2 Steps 6, 8. ✓
- Deletes `writerQueue`/`writerThread`/`writerDead`/`WRITER_BATCH_SIZE`/`runWriterLoop`/`enqueueForWriter` → Task 2, Steps 3, 4. ✓
- `HTTP2WriterCoalescingTest` drives `run()`; IOException test asserts close-signaling not `assertThrows` → Task 1, Step 2. ✓
- `HTTP2OutputStreamFragmentationTest` via `forQueue` → Task 2, Step 2. ✓
- Naming (`enqueueOrCloseWriter`/`enqueueBlocking`/`tryEnqueue`/`isClosed`/`closed`) → Task 1, Step 1. ✓
- MIT header on the new file → Task 1, Step 1 (Global Constraints). ✓

**Placeholder scan:** No `TBD`/`TODO`/"handle errors"/"similar to" — every code step shows full code. ✓

**Type consistency:** Method names and signatures in Task 2's call-site swaps (`enqueueOrCloseWriter`, `enqueueBlocking`, `tryEnqueue`, `requestStop`, `isClosed`, `join(Duration)`, `forQueue`, `start`) all match Task 1's definitions exactly. `HTTP2OutputStream` constructor signatures in Task 2 Step 1 match the usages in Step 2 (fragmentation test) and Step 9 (`commitHeaders`). ✓

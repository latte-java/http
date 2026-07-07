# HTTP/2 Flow-Control Window Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify all four flow-control windows behind one `HTTP2Window` class (design Item 1), then give the connection-level receive window real accounting, enforcement, and a configurable size defaulting to 1 MB (design Item 2).

**Architecture:** Per `docs/design/2026-07-05-window-refactor.md` (read it first). Item 1 replaces `HTTP2Stream`'s seven window methods and `HTTP2ConnectionWindow` with `HTTP2Window`, folding the wake-up into `increment()` and fixing two `INTERNAL_ERROR`-instead-of-`FLOW_CONTROL_ERROR` warts. Item 2 adds a tracked connection receive window: advertised via one stream-0 WINDOW_UPDATE, debited by flow-controlled length for every DATA frame in every stream state, replenished at half, enforced.

**Tech Stack:** Java 21, Latte build tool, TestNG.

## Global Constraints

- All work on branch `http2/windows` (already checked out). Commit per task; **never merge** — the branch waits for Brian's review.
- Conventional Commits, validated by the `commit-msg` hook (`latte build` once activates `.githooks`). Every commit body ends with the trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- New files start with exactly (before `package`, no blank line above):
  ```java
  /*
   * Copyright (c) 2026 The Latte Project
   * SPDX-License-Identifier: MIT
   */
  ```
- Style (`.claude/rules/code-conventions.md`): 2-space indent; 120-char target; acronyms full-uppercase; fields/methods alphabetized within visibility groups; one blank line between members; module imports (`import module java.base;`) preferred. Runtime values in messages in `[square brackets]`.
- **Module-level testing** (connection redesign design §10): new wire tests go in `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`, alphabetized. Its private helpers: `openH2CConnection(port)` (returns a socket with server SETTINGS + SETTINGS-ACK already drained), `writeFrameHeader(out, length, type, flags, streamId)`, `readUntilGoaway(in)` → GOAWAY error code, `readUntilRstStream(in)` → RST error code, `readUntilResponseHeaders(in)` → **stream ID** of the first response HEADERS (not an HTTP status), and the `MINIMAL_HPACK_GET` block. Sanctioned exception: `HTTP2FlowControlTest` is an existing internal test whose subject (window arithmetic, blocking acquire) is not wire-pinnable per case; it is migrated mechanically to the `HTTP2Window` API, per the design's §1.3 — do not delete it, do not grow it beyond the migration.
- Test commands: `latte test --test=HTTP2FlowControlTest`, `latte test --test=HTTP2RawFrameTest`, `latte test` (full, ~2970 tests), `latte clean int` (full integration). Known unrelated flake: `TransferEncodingSmugglingTest.chunked_with_trailing_space_accepted` — if it is the ONLY failure, `latte test --onlyFailed`; green in isolation → note it and treat the run as green.
- All new/changed types stay in the unexported `org.lattejava.http.server.internal.h2` package except the `HTTP2Configuration` knob (exported `org.lattejava.http.server`). Do not touch `module-info.java`.

## File Map

| File | Task | Disposition |
|---|---|---|
| `HTTP2Window.java` | 1 | Create |
| `HTTP2Stream.java` | 2 | Modify — window methods/fields → two `HTTP2Window` fields; Javadoc fix |
| `HTTP2OutputStream.java` | 2 | Modify — call sites + field type |
| `HTTP2WindowUpdateFrameHandler.java` | 2 | Modify — rewritten body |
| `HTTP2ConnectionFrameHandler.java` | 2, 3 | Modify — walk migration (2), overflow pre-check (3) |
| `HTTP2DataFrameHandler.java` | 2, 4 | Modify — receive-window migration + overrun mapping (2); flow-controlled length + connection accounting (4) |
| `HTTP2Connection.java` | 2, 4 | Modify — field type (2); advertise + wire connection flow control (4) |
| `HTTP2OutputProtocol.java`, `HTTP2HeaderFrameHandler.java`, `HTTP2HandlerDelegate.java` | 2 | Modify — `HTTP2ConnectionWindow` type references → `HTTP2Window` |
| `HTTP2ConnectionWindow.java` | 2 | Delete |
| `HTTP2StreamFrameHandlers.java` | 4 | Modify — add `connectionFlowControl` component |
| `HTTP2ConnectionFlowControl.java` | 4 | Create — debit/enforce/replenish helper |
| `HTTP2Frame.java` | 4 | Modify — `DataFrame` gains `flowControlledLength` (compact 3-arg ctor keeps outbound sites unchanged) |
| `HTTP2FrameReader.java` | 4 | Modify — populate `flowControlledLength` |
| `HTTP2Configuration.java` | 4 | Modify — `connectionWindowSize` knob |
| `HTTP2FlowControlTest.java` (test) | 2 | Modify — mechanical API migration |
| `HTTP2RawFrameTest.java` (test) | 2, 3, 4 | Modify — new wire tests |
| `docs/design/2026-07-05-window-refactor.md` | 5 | Modify — status → Implemented |

---

### Task 1: Create `HTTP2Window`

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Window.java`

**Interfaces:**
- Produces (every later task depends on these exact signatures): `HTTP2Window(int initial)`; `int acquire(int numberOfBytes, long timeoutMillis) throws InterruptedException`; `long available()`; `boolean decrement(int numberOfBytes)`; `void increment(int numberOfBytes)`; `boolean tryAcquire(int numberOfBytes)`.

- [ ] **Step 1: Create the file**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

/**
 * A flow-control window (RFC 9113 §6.9). One class serves all four windows on a connection: per-stream and
 * connection-level, send and receive. Synchronized throughout; send-side acquirers park on this object's monitor and
 * {@link #increment} wakes them (a no-op costing nanoseconds when nothing waits). Receive-side instances are
 * currently reader-thread-confined, but the synchronization is retained because nothing enforces that confinement.
 *
 * <p>The counter is a {@code long} because RFC 9113 §6.9.2 legitimately drives send windows negative (a
 * SETTINGS_INITIAL_WINDOW_SIZE decrease applies retroactively to open streams).
 */
public class HTTP2Window {
  private long window;

  public HTTP2Window(int initial) {
    this.window = initial;
  }

  /**
   * Atomically waits until the window is positive, then consumes {@code min(numberOfBytes, available)} octets and
   * returns the amount consumed — partial grants are the contract, backing the min-of-two-windows acquisition in
   * {@link HTTP2OutputStream}. {@code timeoutMillis} bounds each park, not the total wait, keeping the caller
   * responsive to interruption and connection teardown.
   */
  public synchronized int acquire(int numberOfBytes, long timeoutMillis) throws InterruptedException {
    while (window <= 0) {
      wait(timeoutMillis);
    }
    int grant = (int) Math.min(numberOfBytes, window);
    window -= grant;
    return grant;
  }

  public synchronized long available() {
    return window;
  }

  /**
   * Consumes {@code numberOfBytes} if fully available; consumes nothing and returns {@code false} otherwise.
   * Receive-side debits use this and map {@code false} to FLOW_CONTROL_ERROR at their scope (stream or connection).
   */
  public synchronized boolean decrement(int numberOfBytes) {
    return tryAcquire(numberOfBytes);
  }

  /**
   * Adds signed credit and wakes parked acquirers. Negative deltas are legal (RFC 9113 §6.9.2). Also serves as
   * "release" for returning surplus acquired credit. Throws past 2^31-1 as a defensive backstop — protocol-level
   * overflow is pre-checked by callers, which map it to the RFC error codes.
   */
  public synchronized void increment(int numberOfBytes) {
    long next = window + numberOfBytes;
    if (next > Integer.MAX_VALUE) {
      throw new IllegalStateException("Window overflow past 2^31-1: [" + window + "] + [" + numberOfBytes + "]");
    }
    window = next;
    notifyAll();
  }

  /**
   * Non-blocking, all-or-nothing acquire: consumes {@code numberOfBytes} and returns {@code true} only if the full
   * amount is available; otherwise consumes nothing and returns {@code false}. Backs the single-frame fast path in
   * {@link HTTP2OutputStream}.
   */
  public synchronized boolean tryAcquire(int numberOfBytes) {
    if (window < numberOfBytes) {
      return false;
    }
    window -= numberOfBytes;
    return true;
  }
}
```

(`decrement` delegating to `tryAcquire` is deliberate — identical semantics, distinct name for receive-side intent;
`synchronized` is reentrant so the delegation is safe.)

- [ ] **Step 2: Build**

Run: `latte build`
Expected: exit 0.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/h2/HTTP2Window.java
git commit -m "refactor: add HTTP2Window, the unified flow-control window"
```

---

### Task 2: Migrate all windows to `HTTP2Window`; stream-receive overrun becomes FLOW_CONTROL_ERROR

Design §1.2 migration table plus error-mapping fix 1. Everything here is behavior-preserving EXCEPT the one
TDD-pinned change: a DATA frame exceeding the stream receive window now yields `RST_STREAM(FLOW_CONTROL_ERROR)`
instead of today's accidental `GOAWAY(INTERNAL_ERROR)`.

**Files:**
- Modify: `HTTP2Stream.java`, `HTTP2OutputStream.java`, `HTTP2WindowUpdateFrameHandler.java`,
  `HTTP2ConnectionFrameHandler.java`, `HTTP2DataFrameHandler.java`, `HTTP2Connection.java`,
  `HTTP2OutputProtocol.java`, `HTTP2HeaderFrameHandler.java`, `HTTP2HandlerDelegate.java`
  (all under `src/main/java/org/lattejava/http/server/internal/h2/`)
- Delete: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2ConnectionWindow.java`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`,
  `src/test/java/org/lattejava/http/tests/server/HTTP2FlowControlTest.java`

**Interfaces:**
- Consumes: `HTTP2Window` (Task 1).
- Produces: `HTTP2Stream.sendWindow()` / `HTTP2Stream.receiveWindow()` returning `HTTP2Window` (the seven old window
  methods are gone); every former `HTTP2ConnectionWindow` reference is typed `HTTP2Window`.

- [ ] **Step 1: Write the failing wire test** (add to `HTTP2RawFrameTest`, alphabetized):

```java
/**
 * RFC 9113 §6.9.1 — a DATA frame larger than the stream's available receive window is a stream error of type
 * FLOW_CONTROL_ERROR. Today the underflow throws internally and surfaces as GOAWAY(INTERNAL_ERROR); the HTTP2Window
 * migration maps it correctly. Debit and replenish run inline on the reader thread, so the only reachable overrun is
 * a single frame exceeding available credit — hence the tiny configured window.
 */
@Test
public void data_exceeding_stream_window_triggers_flow_control_error() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> {
    req.getInputStream().readAllBytes();
    res.setStatus(200);
  };
  try (var server = makeServer("http", handler, listener).withHTTP2(h2 -> h2.withInitialWindowSize(1024)).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // Open stream 1 with a body pending.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 /* END_HEADERS */, 1);
      out.write(MINIMAL_HPACK_GET);
      // 2048-byte DATA against a 1024-byte stream window — overruns in one frame.
      writeFrameHeader(out, 2048, 0x0 /* DATA */, 0x1 /* END_STREAM */, 1);
      out.write(new byte[2048]);
      out.flush();

      sock.setSoTimeout(5000);
      int errorCode = readUntilRstStream(sock.getInputStream());
      assertEquals(errorCode, 0x3, "Expected RST_STREAM(FLOW_CONTROL_ERROR=0x3) for stream window overrun; got: " + errorCode);
    }
  }
}
```

If `withInitialWindowSize(1024)` fails validation (check the setter's range in `HTTP2Configuration`), use the
smallest legal value and a DATA frame twice its size; if the legal floor is ≥ 16,384, report NEEDS_CONTEXT instead of
improvising.

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=HTTP2RawFrameTest`
Expected: the new test FAILS — the server emits GOAWAY(INTERNAL_ERROR), so `readUntilRstStream` returns -1 or times
out. Every other test passes.

- [ ] **Step 3: Migrate `HTTP2Stream`.** Delete the two `long` fields `receiveWindow`/`sendWindow` and all seven
window methods (`acquireSendWindow`, `consumeSendWindow`, `consumeReceiveWindow`, `incrementReceiveWindow`,
`incrementSendWindow`, `receiveWindow()`, `releaseSendWindow`, `sendWindow()`, `tryAcquireSendWindow` — nine members
total). Add two final fields (alphabetized into the final group) and accessors:

```java
private final HTTP2Window receiveWindow;

private final HTTP2Window sendWindow;
```

constructor: `this.receiveWindow = new HTTP2Window(initialReceiveWindow); this.sendWindow = new HTTP2Window(initialSendWindow);`

```java
public HTTP2Window receiveWindow() {
  return receiveWindow;
}

public HTTP2Window sendWindow() {
  return sendWindow;
}
```

Replace the class Javadoc's stale threading sentence ("the handler thread reads the receive window") with:
"Window state lives in two {@link HTTP2Window} fields: handler threads acquire from the send window, the reader
thread debits and replenishes the receive window."

- [ ] **Step 4: Migrate the call sites.** Exact mappings (design §1.2 table):

| File | Old | New |
|---|---|---|
| `HTTP2OutputStream` | `stream.tryAcquireSendWindow(size)` | `stream.sendWindow().tryAcquire(size)` |
| `HTTP2OutputStream` | `stream.releaseSendWindow(x)` (3 sites) | `stream.sendWindow().increment(x)` |
| `HTTP2OutputStream` | `stream.acquireSendWindow(want, 100)` | `stream.sendWindow().acquire(want, 100)` |
| `HTTP2OutputStream` | `HTTP2ConnectionWindow` field/ctor types + `new HTTP2ConnectionWindow(Integer.MAX_VALUE)` (test overload) | `HTTP2Window` / `new HTTP2Window(Integer.MAX_VALUE)` |
| `HTTP2ConnectionFrameHandler` | SETTINGS walk: `s.incrementSendWindow(delta); synchronized (s) { s.notifyAll(); }` | `s.sendWindow().increment(delta);` |
| `HTTP2ConnectionFrameHandler` | `HTTP2ConnectionWindow` field/ctor type | `HTTP2Window` |
| `HTTP2Connection` | `new HTTP2ConnectionWindow(65535)` + field type | `new HTTP2Window(65535)` / `HTTP2Window` |
| `HTTP2OutputProtocol`, `HTTP2HeaderFrameHandler`, `HTTP2HandlerDelegate` | `HTTP2ConnectionWindow` field/ctor types | `HTTP2Window` |
| `HTTP2DataFrameHandler` | see Step 5 | see Step 5 |

`HTTP2WindowUpdateFrameHandler`'s `handle` becomes (full body — zero-check, pre-check, folded notify):

```java
public HTTP2Result handle(HTTP2Stream stream, HTTP2Frame.WindowUpdateFrame f) {
  if (f.windowSizeIncrement() == 0) {
    return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR); // §6.9
  }
  if (stream.sendWindow().available() + f.windowSizeIncrement() > Integer.MAX_VALUE) {
    return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.FLOW_CONTROL_ERROR); // §6.9.1
  }
  stream.sendWindow().increment(f.windowSizeIncrement());
  return HTTP2Result.OK;
}
```

Then `git rm src/main/java/org/lattejava/http/server/internal/h2/HTTP2ConnectionWindow.java` and chase compile
errors to zero — they are the checklist. If an error points at a file not in this task's list, STOP and report
BLOCKED.

- [ ] **Step 5: The receive-side mapping in `HTTP2DataFrameHandler`** — replace
`stream.consumeReceiveWindow(f.data().length);` with:

```java
      if (!stream.receiveWindow().decrement(f.data().length)) {
        // RFC 9113 §6.9.1 — the peer sent more than the stream window granted; stream error FLOW_CONTROL_ERROR.
        return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.FLOW_CONTROL_ERROR);
      }
```

and the replenish block's three receive-window calls with `stream.receiveWindow().available()` (twice) and
`stream.receiveWindow().increment(delta)`.

- [ ] **Step 6: Migrate `HTTP2FlowControlTest` mechanically.** Every window call moves to the `HTTP2Window` API via
`s.sendWindow().X` / `s.receiveWindow().X`. Semantics mappings: `consumeSendWindow(n)` →
`assertTrue(s.sendWindow().decrement(n))`; the underflow case `expectThrows(IllegalStateException.class, () ->
s.consumeSendWindow(101))` → `assertFalse(s.sendWindow().decrement(101))` followed by
`assertEquals(s.sendWindow().available(), 100)` (no-mutate contract); `incrementSendWindow` overflow `expectThrows`
stays (increment still throws); `sendWindow()`/`receiveWindow()` value asserts → `.available()`. Preserve each
test's intent — do not delete cases.

- [ ] **Step 7: Run the migrated and new tests, then the full suite**

Run: `latte test --test=HTTP2FlowControlTest` → all pass. `latte test --test=HTTP2RawFrameTest` → Step-1 test now
PASSES. `latte test` → 0 failures.

- [ ] **Step 8: Commit**

```bash
git add -A src/main/java/org/lattejava/http/server/internal/h2/ src/test/java/org/lattejava/http/tests/server/
git commit -m "refactor: migrate all flow-control windows to HTTP2Window"
```

---

### Task 3: SETTINGS-increase overflow becomes FLOW_CONTROL_ERROR

Design §1.2 error-mapping fix 2.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2ConnectionFrameHandler.java`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`

**Interfaces:**
- Consumes: `s.sendWindow().available()` / `.increment(delta)` from Task 2.

- [ ] **Step 1: Write the failing wire test:**

```java
/**
 * RFC 9113 §6.9.2 — a SETTINGS_INITIAL_WINDOW_SIZE increase that pushes an open stream's send window past 2^31-1 is
 * a connection error FLOW_CONTROL_ERROR. The window must first be raised above its initial value via WINDOW_UPDATE,
 * because the SETTINGS delta alone can only reach exactly 2^31-1.
 */
@Test
public void settings_increase_overflowing_stream_window_triggers_flow_control_error() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  CountDownLatch release = new CountDownLatch(1);
  HTTPHandler handler = (req, res) -> {
    try {
      release.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException ignore) {
    }
    res.setStatus(200);
  };
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // Open stream 1 (handler parks, keeping the stream live).
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS|END_STREAM */, 1);
      out.write(MINIMAL_HPACK_GET);
      // Raise stream 1's send window 1000 above the 65535 baseline.
      writeFrameHeader(out, 4, 0x8 /* WINDOW_UPDATE */, 0, 1);
      out.write(new byte[]{0, 0, 0x3, (byte) 0xe8}); // increment = 1000
      // SETTINGS INITIAL_WINDOW_SIZE = 2^31-1: delta pushes stream 1 to 66535 + (2^31-1 - 65535) > 2^31-1.
      writeFrameHeader(out, 6, 0x4 /* SETTINGS */, 0, 0);
      out.write(new byte[]{0x0, 0x4, (byte) 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff});
      out.flush();

      sock.setSoTimeout(5000);
      int errorCode = readUntilGoaway(sock.getInputStream());
      assertEquals(errorCode, 0x3, "Expected GOAWAY(FLOW_CONTROL_ERROR=0x3) for SETTINGS-induced window overflow; got: " + errorCode);
      release.countDown();
    }
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=HTTP2RawFrameTest`
Expected: FAIL — today `increment` throws the defensive `IllegalStateException`, surfacing as
GOAWAY(INTERNAL_ERROR=0x2), so the assertion reads 0x2.

- [ ] **Step 3: Add the pre-check in the SETTINGS walk** (`HTTP2ConnectionFrameHandler.handleSettings`) — the loop
body becomes:

```java
      for (HTTP2Stream s : registry.liveStreams()) {
        // RFC 9113 §6.9.2 — an increase that would push a stream window past 2^31-1 is a connection error.
        if (s.sendWindow().available() + delta > Integer.MAX_VALUE) {
          return new HTTP2Result.ConnectionError(HTTP2ErrorCode.FLOW_CONTROL_ERROR);
        }
        s.sendWindow().increment(delta);
      }
```

(Partial increments to earlier streams before a trip are harmless — the connection dies.)

- [ ] **Step 4: Run to verify it passes, then the full suite**

Run: `latte test --test=HTTP2RawFrameTest` → PASS. `latte test` → 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/h2/HTTP2ConnectionFrameHandler.java src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java
git commit -m "fix: SETTINGS-induced stream window overflow is FLOW_CONTROL_ERROR"
```

---

### Task 4: Connection-level receive window — knob, accounting, enforcement, replenish

Design Item 2 in full. This is the behavior-bearing task; its six wire tests come from design §2.5.

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2ConnectionFlowControl.java`
- Modify: `src/main/java/org/lattejava/http/server/HTTP2Configuration.java`,
  `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Frame.java`,
  `src/main/java/org/lattejava/http/server/internal/h2/HTTP2FrameReader.java`,
  `src/main/java/org/lattejava/http/server/internal/h2/HTTP2StreamFrameHandlers.java`,
  `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Stream.java`,
  `src/main/java/org/lattejava/http/server/internal/h2/HTTP2DataFrameHandler.java`,
  `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`

**Interfaces:**
- Consumes: `HTTP2Window` (Task 1); `HTTP2StreamFrameHandlers` record components as they exist after the connection
  redesign (`dataHandler, headerHandler, rateLimits, rstStreamHandler, windowUpdateHandler`).
- Produces: `HTTP2Frame.DataFrame(int streamId, int flags, byte[] data, int flowControlledLength)` with a compact
  3-arg constructor defaulting `flowControlledLength = data.length`; `HTTP2Configuration.getConnectionWindowSize()`
  / `withConnectionWindowSize(int)`; `HTTP2ConnectionFlowControl.onData(int flowControlledLength)` returning
  `HTTP2Result`; bundle component `connectionFlowControl`.

- [ ] **Step 1: Write the six failing wire tests** (add to `HTTP2RawFrameTest`, alphabetized). A small local helper
for reading one frame header is used by two of them; add it next to the other private helpers:

```java
/** Reads one 9-byte frame header; returns {type, flags, streamId, length}. */
private int[] readFrameHeader(InputStream in) throws Exception {
  byte[] h = in.readNBytes(9);
  assertEquals(h.length, 9, "EOF while reading a frame header");
  int length = ((h[0] & 0xFF) << 16) | ((h[1] & 0xFF) << 8) | (h[2] & 0xFF);
  return new int[]{h[3] & 0xFF, h[4] & 0xFF,
      ((h[5] & 0x7F) << 24) | ((h[6] & 0xFF) << 16) | ((h[7] & 0xFF) << 8) | (h[8] & 0xFF), length};
}
```

```java
/** Design §2.2 — by default the server advertises a 1 MB connection window right after the SETTINGS exchange. */
@Test
public void connection_window_advertised_at_default_one_megabyte() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      sock.setSoTimeout(5000);
      var in = sock.getInputStream();
      int[] frame = readFrameHeader(in); // first frame after SETTINGS + ACK
      assertEquals(frame[0], 0x8, "Expected a WINDOW_UPDATE frame; got type: " + frame[0]);
      assertEquals(frame[2], 0, "Expected stream 0");
      byte[] payload = in.readNBytes(4);
      int increment = ((payload[0] & 0x7F) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
      assertEquals(increment, 1_048_576 - 65_535, "Expected the default 1MB advertisement delta; got: " + increment);
    }
  }
}

/** Design §2.2 — configured to exactly 65,535, no stream-0 WINDOW_UPDATE is advertised. */
@Test
public void connection_window_at_minimum_advertises_nothing() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).withHTTP2(h2 -> h2.withConnectionWindowSize(65_535)).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // Send a request; the FIRST server frame must be its response HEADERS, not a WINDOW_UPDATE.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
      out.write(MINIMAL_HPACK_GET);
      out.flush();
      sock.setSoTimeout(5000);
      int[] frame = readFrameHeader(sock.getInputStream());
      assertEquals(frame[0], 0x1, "Expected response HEADERS as the first frame (no advertisement); got type: " + frame[0]);
    }
  }
}

/** Design §2.1 bug 1 / §2.5 — an upload burst beyond the old 64 KB connection window completes under the default. */
@Test
public void upload_beyond_old_connection_window_completes() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  AtomicInteger received = new AtomicInteger();
  HTTPHandler handler = (req, res) -> {
    received.set(req.getInputStream().readAllBytes().length);
    res.setStatus(200);
  };
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 /* END_HEADERS */, 1);
      out.write(MINIMAL_HPACK_GET);
      // 20 DATA frames x 16384 = 327,680 bytes — 5x the old connection window, blasted without reading credit.
      byte[] chunk = new byte[16_384];
      for (int i = 0; i < 20; i++) {
        boolean last = i == 19;
        writeFrameHeader(out, chunk.length, 0x0, last ? 0x1 : 0x0, 1);
        out.write(chunk);
      }
      out.flush();
      sock.setSoTimeout(5000);
      assertEquals(readUntilResponseHeaders(sock.getInputStream()), 1);
      assertEquals(received.get(), 20 * 16_384, "Handler must receive the full body");
    }
  }
}

/** Design §2.2 replenish-at-half — far fewer stream-0 WINDOW_UPDATEs than one per DATA frame. */
@Test
public void connection_window_replenishes_at_half_not_per_frame() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> {
    req.getInputStream().readAllBytes();
    res.setStatus(200);
  };
  try (var server = makeServer("http", handler, listener)
      .withHTTP2(h2 -> h2.withConnectionWindowSize(131_072).withInitialWindowSize(1 << 20)).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4, 1);
      out.write(MINIMAL_HPACK_GET);
      byte[] chunk = new byte[16_384];
      for (int i = 0; i < 16; i++) { // 262,144 bytes = 2x the connection window
        boolean last = i == 15;
        writeFrameHeader(out, chunk.length, 0x0, last ? 0x1 : 0x0, 1);
        out.write(chunk);
      }
      out.flush();
      sock.setSoTimeout(5000);
      // Count stream-0 WINDOW_UPDATEs until the response HEADERS arrives.
      var in = sock.getInputStream();
      int connectionWindowUpdates = 0;
      while (true) {
        int[] frame = readFrameHeader(in);
        byte[] payload = in.readNBytes(frame[3]);
        assertEquals(payload.length, frame[3]);
        if (frame[0] == 0x8 && frame[2] == 0) {
          connectionWindowUpdates++;
        }
        if (frame[0] == 0x1) {
          break; // response HEADERS
        }
      }
      // 262,144 bytes / (131,072/2 per replenish) + 1 slack = at most 5; the old per-frame echo would emit 16.
      assertTrue(connectionWindowUpdates <= 5,
          "Expected replenish-at-half (<=5 stream-0 WINDOW_UPDATEs); got: " + connectionWindowUpdates);
    }
  }
}

/** Design §2.1 bug 4 / §2.5 — DATA at a forgotten stream debits AND replenishes; the connection window no longer
 * leaks. Reuses the reset-then-evict shape of data_on_forgotten_closed_stream_is_ignored, then pushes a full
 * window of ignored DATA before a normal upload. */
@Test
public void ignored_data_does_not_leak_connection_window() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> {
    req.getInputStream().readAllBytes();
    res.setStatus(200);
  };
  try (var server = makeServer("http", handler, listener)
      .withHTTP2(h2 -> h2.withConnectionWindowSize(65_535).withInitialWindowSize(1 << 20)).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // Open stream 1 then reset it — subsequent DATA on it is "ignored" per §6.1.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4, 1);
      out.write(MINIMAL_HPACK_GET);
      writeFrameHeader(out, 4, 0x3 /* RST_STREAM */, 0, 1);
      out.write(new byte[]{0, 0, 0, 0x8});
      // A full connection window of DATA at the dead stream: 4 x 16,384 = 65,536 > 65,535.
      byte[] chunk = new byte[16_384];
      for (int i = 0; i < 4; i++) {
        writeFrameHeader(out, chunk.length, 0x0, 0x0, 1);
        out.write(chunk);
      }
      // Now a real upload on stream 3 — without debit+replenish for the ignored frames, the server's ledger is
      // exhausted and (pre-fix) the peer-visible window was: this request would stall.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4, 3);
      out.write(MINIMAL_HPACK_GET);
      for (int i = 0; i < 4; i++) {
        boolean last = i == 3;
        writeFrameHeader(out, chunk.length, 0x0, last ? 0x1 : 0x0, 3);
        out.write(chunk);
      }
      out.flush();
      sock.setSoTimeout(5000);
      assertEquals(readUntilResponseHeaders(sock.getInputStream()), 3, "Upload after ignored DATA must complete");
    }
  }
}

/** Design §2.2 enforcement — one frame larger than the connection window's available credit. Only reachable by
 * config: large advertised MAX_FRAME_SIZE, minimum connection window, large stream windows so the connection debit
 * (which runs first) trips. */
@Test
public void data_exceeding_connection_window_triggers_flow_control_error() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> {
    req.getInputStream().readAllBytes();
    res.setStatus(200);
  };
  try (var server = makeServer("http", handler, listener)
      .withHTTP2(h2 -> h2.withMaxFrameSize(1 << 20).withConnectionWindowSize(65_535).withInitialWindowSize(1 << 20))
      .start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4, 1);
      out.write(MINIMAL_HPACK_GET);
      // 70,000 bytes in ONE frame (legal against our advertised 1MB MAX_FRAME_SIZE) > 65,535 connection window.
      writeFrameHeader(out, 70_000, 0x0, 0x1, 1);
      out.write(new byte[70_000]);
      out.flush();
      sock.setSoTimeout(5000);
      int errorCode = readUntilGoaway(sock.getInputStream());
      assertEquals(errorCode, 0x3, "Expected GOAWAY(FLOW_CONTROL_ERROR=0x3) for connection window overrun; got: " + errorCode);
    }
  }
}
```

The padding-leak fix (design §2.1 bug 3) is pinned inside `upload_beyond_old_connection_window_completes` and
`connection_window_replenishes_at_half_not_per_frame` indirectly; add one direct case: copy
`upload_beyond_old_connection_window_completes` as `padded_upload_completes_without_window_leak`, with the DATA
frames PADDED (flags `0x1|0x8` on the last frame, `0x8` otherwise; payload = 1 pad-length byte `(byte) 200` + 16,183
data bytes + 200 padding bytes, frame length still 16,384; expect the handler to receive `20 * 16_183` bytes).

- [ ] **Step 2: Run to verify current behavior**

Run: `latte test --test=HTTP2RawFrameTest`
Expected: the advertisement, replenish-count, ignored-DATA, and enforcement tests FAIL (no advertisement exists, the
echo replenish emits per-frame updates, ignored DATA replenishes nothing, no enforcement exists).
`upload_beyond_old_connection_window_completes` may pass today only because the per-frame echo keeps pace — note
actual results in the report.

- [ ] **Step 3: `DataFrame` gains `flowControlledLength`.** In `HTTP2Frame.java`:

```java
  record DataFrame(int streamId, int flags, byte[] data, int flowControlledLength) implements HTTP2Frame {
    /** Outbound frames: the flow-controlled length is the payload length (the server never pads). Public because
        test-package callers construct outbound frames with this form. */
    public DataFrame(int streamId, int flags, byte[] data) {
      this(streamId, flags, data, data.length);
    }
  }
```

In `HTTP2FrameReader.java`, the DATA arm's two constructions become (the raw header `length` IS the
flow-controlled size, §6.9.1 — padding and the pad-length byte included):

```java
          yield new DataFrame(streamId, flags, copyOfRange(buffer, 1, 1 + dataLen), length);
        }
        yield new DataFrame(streamId, flags, copyOf(buffer, length), length);
```

- [ ] **Step 4: The `HTTP2Configuration` knob.** Field `private int connectionWindowSize = 1_048_576;`
(alphabetized), getter `getConnectionWindowSize()`, and (matching the class's existing setter style):

```java
  /**
   * Sets the connection-level receive flow-control window advertised to the client (RFC 9113 §6.9). Bounds the
   * unacknowledged request-body bytes in flight across all streams on one connection; raise it for high-throughput
   * uploads over high-latency links. Defaults to 1 MB.
   *
   * @param connectionWindowSize the window in octets, in [65535, 2^31-1].
   * @return this.
   */
  public HTTP2Configuration withConnectionWindowSize(int connectionWindowSize) {
    if (connectionWindowSize < 65_535) {
      throw new IllegalArgumentException("connectionWindowSize must be in [65535, " + Integer.MAX_VALUE + "]. The protocol cannot shrink the connection window below its initial default. Got [" + connectionWindowSize + "]");
    }
    this.connectionWindowSize = connectionWindowSize;
    return this;
  }
```

- [ ] **Step 5: Create `HTTP2ConnectionFlowControl.java`:**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

/**
 * Connection-level receive flow control (RFC 9113 §6.9.1): debits the window by each DATA frame's flow-controlled
 * length, enforces overrun as a connection error, and replenishes at half via a stream-0 WINDOW_UPDATE. Called from
 * the DATA arm of {@link HTTP2Stream#handleFrame} for every DATA frame in every stream state — flow control is
 * connection state independent of stream outcome. Reader-thread-confined.
 */
public class HTTP2ConnectionFlowControl {
  private final int connectionWindowSize;

  private final HTTP2Window window;

  private final HTTP2WriterThread writer;

  public HTTP2ConnectionFlowControl(int connectionWindowSize, HTTP2WriterThread writer) {
    this.connectionWindowSize = connectionWindowSize;
    this.window = new HTTP2Window(connectionWindowSize);
    this.writer = writer;
  }

  /**
   * Accounts for one received DATA frame. Returns {@link HTTP2Result#OK}, or a connection error of type
   * FLOW_CONTROL_ERROR when the peer overruns the advertised window.
   */
  public HTTP2Result onData(int flowControlledLength) {
    if (!window.decrement(flowControlledLength)) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.FLOW_CONTROL_ERROR);
    }
    long available = window.available();
    if (available < connectionWindowSize / 2) {
      int delta = (int) (connectionWindowSize - available);
      window.increment(delta);
      writer.enqueueOrCloseWriter(new HTTP2Frame.WindowUpdateFrame(0, delta));
    }
    return HTTP2Result.OK;
  }
}
```

- [ ] **Step 6: Wire it.** `HTTP2StreamFrameHandlers` gains a `HTTP2ConnectionFlowControl connectionFlowControl`
component (alphabetized into the record). In `HTTP2Stream.handleData`, the FIRST statement — before the empty-DATA
rate limit and the state switch — becomes:

```java
    // RFC 9113 §6.9 — connection-level flow control applies to every DATA frame regardless of stream state.
    HTTP2Result connectionResult = handlers.connectionFlowControl().onData(f.flowControlledLength());
    if (!(connectionResult instanceof HTTP2Result.Ok)) {
      return connectionResult;
    }
```

In `HTTP2Connection.run()`: construct `var connectionFlowControl = new HTTP2ConnectionFlowControl(
configuration.getHTTP2Configuration().getConnectionWindowSize(), writer);` after the writer starts, pass it into the
bundle, and advertise:

```java
      // Advertise the configured connection receive window (design §2.2): the connection window can only be raised
      // by a stream-0 WINDOW_UPDATE — SETTINGS_INITIAL_WINDOW_SIZE does not apply to it (RFC 9113 §6.9.2).
      int connectionWindowSize = configuration.getHTTP2Configuration().getConnectionWindowSize();
      if (connectionWindowSize > 65_535) {
        writer.enqueueOrCloseWriter(new HTTP2Frame.WindowUpdateFrame(0, connectionWindowSize - 65_535));
      }
```

In `HTTP2DataFrameHandler`: delete the per-frame echo (`writer.enqueueOrCloseWriter(new
HTTP2Frame.WindowUpdateFrame(0, f.data().length));` and its comment), and switch the stream-side accounting to
flow-controlled length — the receive-window `decrement` argument and the two `f.data().length > 0` replenish guards
become `f.flowControlledLength()` (the content-length accounting `incrementDataCount(f.data().length)` and the
`pipe.offer(f.data(), ...)` stay on the stripped payload — content-length counts data, not padding).

- [ ] **Step 7: Run the new tests, then the full suite**

Run: `latte test --test=HTTP2RawFrameTest` → all Step-1 tests PASS. `latte test` → 0 failures. Note: existing tests
that assert per-frame stream-0 WINDOW_UPDATE echoes (if any exist in the flow-control or basic suites) will surface
here — update them to the replenish-at-half contract and record each in the report.

- [ ] **Step 8: Commit**

```bash
git add -A src/main/java/org/lattejava/http/ src/test/java/org/lattejava/http/tests/server/
git commit -m "feat: configurable connection receive window with real accounting and enforcement"
```

---

### Task 5: Integration pass and design closeout

**Files:**
- Modify: `docs/design/2026-07-05-window-refactor.md`

- [ ] **Step 1:** Run `latte clean int` (background it; can exceed 10 minutes). Expected exit 0, 0 failures. Known
flake rule from Global Constraints applies. Any other failure: BLOCKED with the test name and output.

- [ ] **Step 2:** In `docs/design/2026-07-05-window-refactor.md` change `- **Status:** Approved — implementation
pending` to `- **Status:** Implemented`. Commit ONLY after Step 1 is green:

```bash
git add docs/design/2026-07-05-window-refactor.md
git commit -m "docs: mark window refactor implemented"
```

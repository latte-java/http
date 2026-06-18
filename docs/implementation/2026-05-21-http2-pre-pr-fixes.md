# HTTP/2 Branch Pre-PR Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the punch list of pre-PR fixes for the `robotdan/http2` branch — one failing test, one smuggling guard, a series of silent-failure tightenings, and two test-coverage backfills — so the branch is ready to open as a PR.

**Architecture:** Each fix is test-first (write a failing regression test, then make the smallest change that makes it pass) and gets its own commit. The h2 reader thread is the single point of contention on a connection, so several fixes target queue-blocking patterns (`pipe.put`, `writerQueue.put`) that can deadlock the connection under adversarial conditions. Protocol-error fixes follow RFC 9113: stream-level malformations → RST_STREAM, connection-level → GOAWAY-then-close.

**Tech Stack:** Java 21, Latte build tool, TestNG. All work happens in the existing worktree at `/Users/robotdan/dev/latte-java/http/.claude/worktrees/http2`.

---

## File Structure

Files modified (with one-line responsibility per file):

- `src/main/java/org/lattejava/http/server/io/HTTPInputStream.java` — skip `FixedLengthInputStream` wrapping on h2 so HTTP2InputStream's END_STREAM is authoritative; harden `drain()` against null `request`.
- `src/main/java/org/lattejava/http/server/internal/HTTP1Worker.java` — reject h2c-Upgrade requests with a body before switching protocols.
- `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java` — emit GOAWAY on unhandled reader exceptions, signal reader on writer death, replace blocking `pipe.put`/`writerQueue.put` with bounded offers, RST_STREAM malformed content-length, debug-log swallowed exceptions, lock the response-HEADERS state-check + enqueue.
- `src/main/java/org/lattejava/http/server/internal/HPACKDecoder.java` — convert `IllegalStateException` for HPACK index 0 to `IOException` so the reader maps it to COMPRESSION_ERROR.
- `src/main/java/org/lattejava/http/server/internal/HTTPServerThread.java` — close the accepted socket when `ProtocolSelector.select()` throws.

Tests added:

- `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java` — add response-trailers wire test (IG-1) and harden adversarial-frame tests where they back the fixes.
- `src/test/java/org/lattejava/http/tests/server/H2SpecHarnessTest.java` — lock the h2spec known-failure set with `assertEquals` (IG-2).
- `src/test/java/org/lattejava/http/tests/server/ProtocolSwitchTest.java` — h2c-Upgrade with body returns 400.
- `src/test/java/org/lattejava/http/tests/server/HTTP2SecurityTest.java` — HPACK index 0 yields COMPRESSION_ERROR; malformed content-length yields RST_STREAM(PROTOCOL_ERROR).

---

## Phase 1 — Must fix before opening PR

### Task 1: Fix h2 request-trailers race (HTTP2RawFrameTest.request_trailers_accepted_not_reset_as_stream_closed)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/io/HTTPInputStream.java:195-251`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java:215-267` (existing failing test — no new test needed)

**Root cause confirmed:** When an h2 request carries `content-length: N`, `HTTPInputStream.initialize()` wraps the underlying stream in a `FixedLengthInputStream` (line 214). `FixedLengthInputStream.read` returns -1 immediately when `bytesRemaining <= 0` (FixedLengthInputStream.java:39-41) — it never drains the underlying `HTTP2InputStream`'s EOF sentinel. So the handler's `readAllBytes()` returns at exactly content-length bytes, before the trailers HEADERS frame has been received by the reader. The handler then calls `req.getTrailer(...)` and gets null. h2's frame layer already enforces content-length (`HTTP2Connection.handleData:579,592`), so `FixedLengthInputStream` is redundant — h2 streams should EOF only on END_STREAM.

- [ ] **Step 1: Run the existing failing test to confirm baseline**

Run: `latte test --test=HTTP2RawFrameTest`
Expected: PASS (it's flaky — passes in isolation, fails under full-suite load). Then run `latte clean int --excludePerformance --excludeTimeouts` and confirm the failure surfaces.

- [ ] **Step 2: Modify `HTTPInputStream.initialize()` to skip FixedLengthInputStream when on h2**

In `src/main/java/org/lattejava/http/server/io/HTTPInputStream.java`, change the `if (hasBody)` branch in `initialize()`:

```java
private void initialize() throws IOException {
  initialized = true;

  boolean hasBody = request.hasBody();
  if (hasBody) {
    Long contentLength = request.getContentLength();
    if (request.isChunked()) {
      logger.trace("Client indicated it was sending an entity-body in the request. Handling body using chunked encoding.");
      ChunkedInputStream chunked = new ChunkedInputStream(pushbackInputStream, chunkedBufferSize, maxRequestChunkSize);
      chunkedDelegate = chunked;
      delegate = chunked;
      if (instrumenter != null) {
        instrumenter.chunkedRequest();
      }
    } else if (maximumContentLength == -1) {
      // HTTP/2 sentinel. The frame layer (HTTP2Connection.handleData) enforces content-length against DATA frame
      // payload totals, and HTTP2InputStream signals EOF only when END_STREAM arrives (on DATA or on trailers HEADERS).
      // Wrapping in FixedLengthInputStream here would EOF at content-length bytes — before request trailers can be
      // delivered, breaking RFC 9113 §8.1 trailer semantics.
      delegate = pushbackInputStream;
    } else {
      logger.trace("Client indicated it was sending an entity-body in the request. Handling body using Content-Length header {}.", contentLength);
      delegate = new FixedLengthInputStream(pushbackInputStream, contentLength);
    }

    for (String contentEncoding : request.getContentEncodings().reversed()) {
      if (contentEncoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
        delegate = new InflaterInputStream(delegate);
      } else if (contentEncoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
        delegate = new GZIPInputStream(delegate);
      }
    }

    if (contentLength != null && maximumContentLength != -1 && contentLength > maximumContentLength) {
      String detailedMessage = "The maximum request size has been exceeded. The reported Content-Length is [" + contentLength + "] and the maximum request size is [" + maximumContentLength + "] bytes.";
      throw new ContentTooLargeException(maximumContentLength, detailedMessage);
    }
  } else {
    if (maximumContentLength == -1) {
      delegate = pushbackInputStream;
    } else {
      logger.trace("Client indicated it was NOT sending an entity-body in the request");
      delegate = InputStream.nullInputStream();
    }
  }
}
```

- [ ] **Step 3: Run the trailer test in isolation 20 times to verify the fix is not flaky**

Run: `for i in $(seq 1 20); do latte test --test=HTTP2RawFrameTest 2>&1 | tail -3; done`
Expected: All 20 runs show `Failures: 0`.

- [ ] **Step 4: Run the full test suite to verify nothing else regressed**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/io/HTTPInputStream.java
git commit -m "Fix h2 request-trailer race by skipping FixedLengthInputStream on h2

FixedLengthInputStream returns EOF strictly at content-length bytes and
never drains the underlying HTTP2InputStream's END_STREAM sentinel. This
caused the handler's readAllBytes() to return before the trailers HEADERS
frame arrived, so req.getTrailer() returned null. The h2 frame layer
already enforces content-length against DATA payload totals, so the
FixedLengthInputStream wrapper is redundant on h2."
```

---

### Task 2: Reject h2c-Upgrade requests with a body (smuggling guard for Plan E TODO at HTTP1Worker.java:193)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP1Worker.java:170-206` (h2c upgrade dispatch)
- Test: `src/test/java/org/lattejava/http/tests/server/ProtocolSwitchTest.java` (new test)

**Risk confirmed:** When the h2c upgrade dispatch at HTTP1Worker.java:175-205 emits 101 Switching Protocols, any unread bytes left on the socket from the original HTTP/1.1 request body are then interpreted by `HTTP2Connection.run()` as HTTP/2 frames. A client (or attacker) can send an `Upgrade: h2c` request with `Content-Length: N` and N bytes of crafted HTTP/2 frame bytes; after the 101, the new h2 reader consumes those bytes as the connection preface or first frames. This is a request-smuggling / protocol-confusion footgun. The full Plan E fix would map the upgrade request into stream 1; this task adds the minimal guard.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/ProtocolSwitchTest.java` (or append to an existing one) with this method:

```java
@Test
public void h2c_upgrade_with_request_body_rejected_with_400() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cUpgradeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = new Socket("127.0.0.1", server.getActualPort())) {
      var out = sock.getOutputStream();
      String preamble =
          "POST / HTTP/1.1\r\n"
          + "Host: localhost\r\n"
          + "Connection: Upgrade, HTTP2-Settings\r\n"
          + "Upgrade: h2c\r\n"
          + "HTTP2-Settings: \r\n"
          + "Content-Length: 4\r\n"
          + "\r\n"
          + "body";
      out.write(preamble.getBytes());
      out.flush();

      sock.setSoTimeout(5000);
      var in = sock.getInputStream();
      byte[] buf = new byte[256];
      int n = in.read(buf);
      assertTrue(n > 0, "Expected a response from the server");
      String response = new String(buf, 0, n);
      assertTrue(response.startsWith("HTTP/1.1 400"),
          "Expected 400 Bad Request for h2c-Upgrade with body; got [" + response.split("\r\n")[0] + "]");
    }
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=ProtocolSwitchTest`
Expected: FAIL — server returns 101 then garbage instead of 400.

- [ ] **Step 3: Add the body-presence guard in HTTP1Worker before the 101 switch**

In `src/main/java/org/lattejava/http/server/internal/HTTP1Worker.java`, modify the `if (upgrade != null && upgrade.equalsIgnoreCase("h2c"))` block (around line 174) to add a body check at the top:

```java
if (upgrade != null && upgrade.equalsIgnoreCase("h2c")) {
  // RFC 9113 §3.2 — h2c Upgrade does NOT permit a request body to carry over. The original HTTP/1.1 body bytes
  // would remain on the socket after the 101, and the new HTTP/2 reader would mis-interpret them as frames
  // (request smuggling / protocol confusion). Until Plan E maps the original request into stream 1, refuse any
  // h2c-Upgrade that declares a body.
  if (request.hasBody()) {
    closeSocketOnError(response, Status.BadRequest);
    return;
  }
  // ... existing settings-parsing and switchProtocols code unchanged
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `latte test --test=ProtocolSwitchTest`
Expected: PASS.

- [ ] **Step 5: Verify the h2c-no-body path still works**

Run: `latte test --test=HTTP2H2cUpgradeTest`
Expected: All tests PASS (no body in those existing tests).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP1Worker.java src/test/java/org/lattejava/http/tests/server/ProtocolSwitchTest.java
git commit -m "Reject h2c-Upgrade requests with a body (smuggling guard)

Until Plan E maps the original HTTP/1.1 request into implicit stream 1,
any body bytes carried in the upgrade request would remain on the socket
after the 101 and be mis-read as HTTP/2 frames by the new connection.
Reject with 400 Bad Request when Content-Length or Transfer-Encoding
declares a body."
```

---

## Phase 2 — Should fix before merge

### Task 3: Emit GOAWAY on unhandled reader-thread exceptions (R1)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java:340-370` (main `run()` try/finally)
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java` (new test)

**Risk confirmed:** The outer `catch (Exception e) { logger.debug(...) }` at HTTP2Connection.java:365-366 swallows `IllegalStateException` from stream-window underflow, `HTTP2Stream.applyEvent` races, HPACK decode IOException, and `RuntimeException` from a future handler — connection tears down with TCP FIN, no GOAWAY. RFC 9113 §5.4.1 requires GOAWAY for connection errors. Spec-compliance + diagnostic visibility issue.

- [ ] **Step 1: Read HTTP2Connection.java:340-405 to identify the inner and outer catch blocks**

Open `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java` and locate the `catch (Exception e) { logger.debug("HTTP/2 connection ended", e); }` near line 365. This is the outer catch in `run()`; everything above already either calls `goAway(...)` or is wrapped in a smaller try.

- [ ] **Step 2: Write a failing test that triggers a connection-level error and expects a GOAWAY frame**

In `HTTP2RawFrameTest.java`, add:

```java
/**
 * RFC 9113 §5.4.1 — any unhandled exception during connection processing MUST result in a GOAWAY frame
 * with an appropriate error code (INTERNAL_ERROR for unexpected exceptions) before the socket is closed.
 * Today an IllegalStateException escapes silently and the connection drops without a GOAWAY.
 */
@Test
public void unhandled_reader_exception_emits_goaway_internal_error() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> { throw new IllegalStateException("simulate fatal reader-path bug"); };
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = openH2cConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // Standard HEADERS to trigger the handler.
      byte[] headers = new byte[]{
          (byte) 0x82, // :method GET
          (byte) 0x84, // :path /
          (byte) 0x86, // :scheme http
          (byte) 0x41, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't' // :authority localhost
      };
      writeFrameHeader(out, headers.length, 0x1, 0x4 | 0x1, 1);
      out.write(headers);
      out.flush();

      sock.setSoTimeout(5000);
      var in = sock.getInputStream();
      // Read frames until we get a GOAWAY or the socket closes. We must NOT see the socket close
      // without a GOAWAY arriving first.
      boolean sawGoaway = false;
      while (true) {
        int type = readFrameTypeOrEOF(in);
        if (type == -1) break;
        if (type == 0x7) { sawGoaway = true; break; }
      }
      assertTrue(sawGoaway, "Expected GOAWAY frame before socket close — got bare EOF");
    }
  }
}
```

`readFrameTypeOrEOF` is a small helper that reads 9 bytes of frame header and returns the type byte, or -1 on EOF. Add it as a private helper in the same test class if not already present.

- [ ] **Step 3: Run the test to verify it fails**

Run: `latte test --test=HTTP2RawFrameTest#unhandled_reader_exception_emits_goaway_internal_error`
Expected: FAIL — handler-thrown IllegalStateException is caught by the spawnHandlerThread `catch (Exception)` which sends RST_STREAM, not GOAWAY. Adjust the test to throw a connection-level exception instead (e.g., trigger HPACK lookup(0) which currently escapes — see Task 6 for the cleaner path). If this test can't be made to fail cleanly without also fixing Task 6, swap their order.

- [ ] **Step 4: Modify the outer catch to emit GOAWAY before falling through to cleanup**

In `HTTP2Connection.java`, near line 365, change:

```java
} catch (Exception e) {
  logger.debug("HTTP/2 connection ended", e);
}
```

to:

```java
} catch (Throwable t) {
  // RFC 9113 §5.4.1 — any unhandled error during connection processing is a connection error.
  // Emit GOAWAY(INTERNAL_ERROR) so the peer learns the connection died deliberately, not from a
  // bare TCP FIN that looks indistinguishable from a network glitch.
  logger.warn("Unhandled exception in HTTP/2 reader; emitting GOAWAY(INTERNAL_ERROR)", t);
  goAway(HTTP2ErrorCode.INTERNAL_ERROR);
}
```

Note: `goAway` is idempotent (line 498-500 short-circuits if `goawaySent`), so this is safe even if the inner catch already emitted one.

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=HTTP2RawFrameTest#unhandled_reader_exception_emits_goaway_internal_error`
Expected: PASS.

- [ ] **Step 6: Run the full h2 test suite to confirm no regression**

Run: `latte test --test=HTTP2RawFrameTest,HTTP2BasicTest,HTTP2SecurityTest,HTTP2H2SpecBatch3Test,HTTP2H2SpecBatch4Test`
Expected: 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java
git commit -m "Emit GOAWAY(INTERNAL_ERROR) on unhandled HTTP/2 reader exceptions

The outer reader-loop catch was logging at debug and falling through to
socket close, leaving the peer with a bare TCP FIN. RFC 9113 §5.4.1
requires GOAWAY for connection errors. Catches Throwable so HPACK
unchecked exceptions and stream-window underflow are surfaced as
INTERNAL_ERROR rather than silently dropping the connection."
```

---

### Task 4: Signal reader on writer-thread death (R2)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java:214-228` (writer-thread body) and reader-side `writerQueue.put` call sites
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`

**Risk confirmed:** Writer thread at HTTP2Connection.java:225-227 catches `Exception`, logs at debug, exits. The reader's `writerQueue.put(...)` on every WINDOW_UPDATE, SETTINGS ACK, PING ACK, RST_STREAM, GOAWAY will block forever once the 128-slot LinkedBlockingQueue fills. The `d72cd3d` handler-leak fix only handles reader-dying-first; this is writer-dying-first. Convert reader-side `put` → `offer(timeout)` with abort-on-failure, AND have the writer-death path interrupt the reader.

- [ ] **Step 1: Write a failing test that wedges the writer and asserts the reader does not hang**

In `HTTP2RawFrameTest.java`:

```java
/**
 * When the writer thread dies (broken pipe), the reader must not park indefinitely on writerQueue.put().
 * Reproduction: open an h2c connection, ABORT the socket from the client side mid-request so the writer's
 * next writeFrame() fails. Then send enough frames from a second connection (no — actually: send WINDOW_UPDATEs
 * from the same connection) to fill the writer queue. The reader must detect the dead writer within a few
 * seconds and tear down rather than parking forever.
 */
@Test(timeOut = 10_000)
public void reader_does_not_hang_when_writer_dies() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).start()) {
    Socket sock = openH2cConnection(server.getActualPort());
    var out = sock.getOutputStream();
    // Send a HEADERS so a stream exists, then close the socket abruptly.
    byte[] headers = new byte[]{(byte)0x82, (byte)0x84, (byte)0x86, (byte)0x41, 0x09, 'l','o','c','a','l','h','o','s','t'};
    writeFrameHeader(out, headers.length, 0x1, 0x4 | 0x1, 1);
    out.write(headers);
    out.flush();
    sock.setSoLinger(true, 0);
    sock.close();
    // Within the test timeout, the server-side connection should clean up and the virtual thread should exit.
    // The test passes if the timeOut does not fire.
    Thread.sleep(2000);
  }
}
```

This test is timing-sensitive; the intent is that without the fix the server-side reader hangs and we leak a virtual thread (detectable via thread dump in a manual run), and with the fix the connection cleans up. A more deterministic test would inject a writer that immediately fails — consider adding a test hook on `HTTP2Connection` if making this deterministic is hard.

- [ ] **Step 2: Run the test to confirm baseline (may pass even without the fix due to test imprecision)**

Run: `latte test --test=HTTP2RawFrameTest#reader_does_not_hang_when_writer_dies`

- [ ] **Step 3: Add a `writerDead` flag and have the writer thread set it + interrupt the reader on exit**

In `HTTP2Connection.java`, add a field near the other connection-state fields:

```java
private volatile boolean writerDead;
private volatile Thread readerThread;
```

In `run()`, capture the reader thread reference at the top of the frame-handling loop (before the writer thread is spawned, so the writer can reference it):

```java
readerThread = Thread.currentThread();
```

Modify the writer-thread body (currently lines 214-228) to set the flag and interrupt the reader on exit:

```java
writerThread = Thread.ofVirtual().name("h2-writer").start(() -> {
  try {
    while (true) {
      HTTP2Frame f = writerQueue.take();
      if (f instanceof HTTP2Frame.GoawayFrame g && g.lastStreamId() == -1) {
        return;
      }
      writerForThread.writeFrame(f);
      outForThread.flush();
    }
  } catch (Exception e) {
    logger.debug("Writer thread ended unexpectedly; signaling reader", e);
  } finally {
    writerDead = true;
    Thread reader = readerThread;
    if (reader != null) {
      reader.interrupt();
    }
  }
});
```

- [ ] **Step 4: Convert reader-side writerQueue.put calls to offer(timeout) with writerDead guard**

The reader-side `writerQueue.put(...)` sites (lines 504, 612, 619, 658, 685, 717, 1012) all follow the same pattern. Introduce a helper at the bottom of HTTP2Connection.java:

```java
/**
 * Enqueue a frame for the writer thread. Returns false (and logs at debug) if the writer is dead or the queue
 * stays full past the timeout — caller decides what to do (typically: return, the connection is tearing down).
 */
private boolean enqueueForWriter(HTTP2Frame f) {
  if (writerDead) {
    return false;
  }
  try {
    if (!writerQueue.offer(f, 5, java.util.concurrent.TimeUnit.SECONDS)) {
      logger.debug("Writer queue full for [5s]; assuming writer death");
      writerDead = true;
      return false;
    }
    return true;
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    return false;
  }
}
```

Replace each reader-side `writerQueue.put(...)` (NOT the handler-side ones — those are handled in Task 5) with `enqueueForWriter(...)`. Inspect each catch block — most can collapse to a single `if (!enqueueForWriter(frame)) return;`.

- [ ] **Step 5: Add `writerDead` check at the top of the reader's frame loop**

Inside the `while (true)` reader loop near line 240, before the `readFrame()` call:

```java
if (writerDead) {
  logger.debug("Writer thread dead; reader exiting");
  break;
}
```

- [ ] **Step 6: Run the new test plus the full h2 suite**

Run: `latte test --test=HTTP2RawFrameTest,HTTP2BasicTest`
Expected: 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java
git commit -m "Signal reader when h2 writer thread dies to prevent deadlock

Reader thread enqueues to writerQueue on every WINDOW_UPDATE, SETTINGS
ACK, PING ACK, RST_STREAM, and GOAWAY. If the writer dies (broken pipe,
peer reset), the queue fills and the reader parks on put() forever.
Adds a volatile writerDead flag set in the writer's finally block,
which interrupts the reader and short-circuits the new enqueueForWriter
helper that replaces the blocking put() calls."
```

---

### Task 5: Prevent slow-handler from stalling the connection (R4)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java:584-588` (`handleData` pipe.put)
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2BasicTest.java` or `HTTP2RawFrameTest.java` (concurrency test)

**Risk confirmed:** Reader thread blocks on `pipe.put(payload)` when a handler's 16-slot pipe is full. Since there's only one reader per connection, this freezes ALL streams on the connection — adversarial / mismatched-tempo client can wedge the connection by attaching to a slow handler. **Perf upside under multi-stream load:** with this fix in place, slow streams no longer block fast streams; the `h2-stream` benchmark in particular should show improvement, and the gap to Helidon documented in docs/specs/HTTP2.md (2026-05-21 perf findings, ~9× behind) is partly explained by exactly this — the writer-path bottleneck dominates, but reader-side cross-stream stalls compound it. **Downside of `offer(timeout) → RST_STREAM`:** the slow handler stream gets cancelled rather than back-pressured. RFC 9113 §5.2 flow control is the intended back-pressure mechanism (peer can't send more than initial window), so legitimate slow handlers should not hit this — only handlers that genuinely fail to read their stream within a reasonable timeout. The timeout should be configurable; 5–10 s default is safe.

- [ ] **Step 1: Write a failing test that runs two concurrent streams where one stalls and proves the other still progresses**

In `HTTP2BasicTest.java`:

```java
/**
 * One slow stream must not stall other streams on the same connection. Stream 1's handler blocks indefinitely
 * without reading its body, filling its 16-slot input pipe; stream 3 must still receive its response promptly.
 */
@Test(timeOut = 10_000)
public void slow_handler_does_not_stall_other_streams() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  CountDownLatch slowHandlerStarted = new CountDownLatch(1);
  CountDownLatch releaseSlowHandler = new CountDownLatch(1);
  HTTPHandler handler = (req, res) -> {
    if (req.getPath().equals("/slow")) {
      slowHandlerStarted.countDown();
      try { releaseSlowHandler.await(); } catch (InterruptedException ignored) {}
    }
    res.setStatus(200);
  };
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = openH2cConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // Stream 1: POST /slow with body — handler blocks before reading body, so pipe fills.
      writeHeadersAndBodyForStream(out, 1, "/slow", new byte[20 * 1024]);
      assertTrue(slowHandlerStarted.await(2, TimeUnit.SECONDS));
      // Stream 3: GET /fast — must complete despite stream 1 being stuck.
      writeGetForStream(out, 3, "/fast");
      out.flush();
      sock.setSoTimeout(3000);
      int responseStream = readUntilResponseHeaders(sock.getInputStream());
      assertEquals(responseStream, 3, "Stream 3 must respond despite stream 1 being stuck");
      releaseSlowHandler.countDown();
    }
  }
}
```

You will need to add the `writeHeadersAndBodyForStream` and `writeGetForStream` helpers to the test base. Both encode standard HEADERS for h2c and a DATA frame.

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=HTTP2BasicTest#slow_handler_does_not_stall_other_streams`
Expected: FAIL — the test times out because stream 3's response is blocked behind stream 1's stuck pipe.put.

- [ ] **Step 3: Replace `pipe.put` with `offer(timeout)` and RST_STREAM on timeout**

In `HTTP2Connection.java:584-588`, change:

```java
try {
  pipe.put(f.payload());
} catch (InterruptedException e) {
  Thread.currentThread().interrupt();
}
```

to:

```java
try {
  if (!pipe.offer(f.payload(), configuration.getHTTP2HandlerReadTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
    // The handler is not consuming its body. Cancel the stream rather than blocking the reader thread,
    // which would stall every other stream on this connection.
    logger.debug("h2 handler [{}] failed to consume body within timeout; sending RST_STREAM(CANCEL)", f.streamId());
    rstStream(f.streamId(), HTTP2ErrorCode.CANCEL);
    streams.remove(f.streamId());
    streamPipes.remove(f.streamId());
    return;
  }
} catch (InterruptedException e) {
  Thread.currentThread().interrupt();
  return;
}
```

- [ ] **Step 4: Add the configuration knob `withHTTP2HandlerReadTimeout(Duration)`**

In `src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java`, add a `private Duration http2HandlerReadTimeout = Duration.ofSeconds(10);` field (alphabetized with other `http2*` fields), with `getHTTP2HandlerReadTimeout()` and `withHTTP2HandlerReadTimeout(Duration)` accessors. 10 s default chosen to be well above legitimate handler latency under flow-control back-pressure.

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=HTTP2BasicTest#slow_handler_does_not_stall_other_streams`
Expected: PASS.

- [ ] **Step 6: Run the full benchmark scenario to confirm no perf regression (optional but recommended)**

Run the `h2-stream` benchmark and confirm throughput is at least as good as before the change. The fix should improve concurrency, not regress single-stream throughput.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java src/test/java/org/lattejava/http/tests/server/HTTP2BasicTest.java
git commit -m "Prevent slow h2 handler from stalling the connection reader

Reader thread used to block on pipe.put when a handler's 16-slot input
pipe filled, freezing every other stream on the connection. Switches to
offer(timeout) backed by a configurable HTTP2HandlerReadTimeout (default
10s); on timeout the offending stream is cancelled with RST_STREAM(CANCEL)
and the reader proceeds. Flow control is the intended back-pressure
mechanism — this is a safety net for handlers that genuinely fail to
read their stream."
```

---

### Task 6: HPACK index 0 → COMPRESSION_ERROR instead of unchecked exception (R6/S6)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HPACKDecoder.java:89-97`
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java` — map `IOException` from HPACK decode to GOAWAY(COMPRESSION_ERROR)
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2SecurityTest.java` or `HPACKDecoderTest.java`

**Risk confirmed:** `HPACKDecoder.lookup(int)` at line 91 throws `IllegalStateException` for index 0. Attacker can send a HEADERS frame with HPACK byte sequence `0x80` (indexed-name with index 0) — currently this escapes as an unchecked exception, caught by HTTP2Connection's outer catch (which after Task 3 becomes a GOAWAY(INTERNAL_ERROR), but the spec calls for COMPRESSION_ERROR per RFC 7541 §2.1). Test BEFORE fix to catch behavior; fix to use IOException so the existing PROTOCOL_ERROR / COMPRESSION_ERROR mapping fires.

- [ ] **Step 1: Write a unit test in HPACKDecoderTest that asserts IOException on index 0**

In `src/test/java/org/lattejava/http/tests/server/HPACKDecoderTest.java`:

```java
@Test(expectedExceptions = IOException.class,
      expectedExceptionsMessageRegExp = ".*index.*0.*")
public void index_zero_throws_ioexception_per_rfc_7541_section_2_1() throws Exception {
  HPACKDecoder decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
  // 0x80 = indexed header field representation with index 0 (RFC 7541 §6.1).
  decoder.decode(new byte[]{(byte) 0x80});
}
```

- [ ] **Step 2: Run the unit test to verify it fails (currently throws IllegalStateException)**

Run: `latte test --test=HPACKDecoderTest#index_zero_throws_ioexception_per_rfc_7541_section_2_1`
Expected: FAIL — `IllegalStateException` thrown but `IOException` expected.

- [ ] **Step 3: Change HPACKDecoder.lookup to throw IOException**

In `src/main/java/org/lattejava/http/server/internal/HPACKDecoder.java`, change:

```java
private HPACKDynamicTable.HeaderField lookup(int index) {
  if (index == 0) {
    throw new IllegalStateException("HPACK index [0] is invalid per RFC 7541 §2.1");
  }
  ...
}
```

to:

```java
private HPACKDynamicTable.HeaderField lookup(int index) throws IOException {
  if (index == 0) {
    throw new IOException("HPACK index [0] is invalid per RFC 7541 §2.1");
  }
  ...
}
```

Then add `throws IOException` to any callers in HPACKDecoder that need it (`readNameValue`, the main `decode` loop). All callers are already inside HPACKDecoder's `decode` method which is declared `throws IOException`, so the propagation is mechanical.

- [ ] **Step 4: Add a wire-level test in HTTP2SecurityTest asserting GOAWAY(COMPRESSION_ERROR)**

```java
@Test
public void hpack_index_zero_yields_goaway_compression_error() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  try (var server = makeServer("http", (req, res) -> res.setStatus(200), listener).start()) {
    try (var sock = openH2cConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // HEADERS payload with a single byte 0x80 — indexed header field, index 0 (invalid).
      writeFrameHeader(out, 1, 0x1, 0x4 | 0x1, 1);
      out.write(new byte[]{(byte) 0x80});
      out.flush();
      sock.setSoTimeout(3000);
      var goaway = readUntilGoaway(sock.getInputStream());
      assertEquals(goaway.errorCode(), HTTP2ErrorCode.COMPRESSION_ERROR.value,
          "Expected GOAWAY(COMPRESSION_ERROR) for HPACK index 0");
    }
  }
}
```

- [ ] **Step 5: Wire HPACK IOException → COMPRESSION_ERROR in the reader**

In `HTTP2Connection.java`, around `finalizeHeaderBlock` callers (line 645, 308), wrap the HPACK decode call to map IOException to GOAWAY(COMPRESSION_ERROR). The cleanest place is inside `finalizeHeaderBlock` itself:

```java
private void finalizeHeaderBlock(int streamId, int flags, ByteArrayOutputStream headerAccum, HPACKDecoder decoder, HPACKEncoder encoder) throws IOException {
  List<HPACKDynamicTable.HeaderField> fields;
  try {
    fields = decoder.decode(headerAccum.toByteArray());
  } catch (IOException e) {
    logger.debug("HPACK decode failed: [{}]", e.getMessage());
    goAway(HTTP2ErrorCode.COMPRESSION_ERROR);
    return;
  }
  // ... existing logic unchanged
}
```

- [ ] **Step 6: Run both tests**

Run: `latte test --test=HPACKDecoderTest,HTTP2SecurityTest`
Expected: 0 failures.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HPACKDecoder.java src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java src/test/java/org/lattejava/http/tests/server/HPACKDecoderTest.java src/test/java/org/lattejava/http/tests/server/HTTP2SecurityTest.java
git commit -m "Map HPACK index-0 and decode failures to GOAWAY(COMPRESSION_ERROR)

HPACK index 0 used to throw IllegalStateException and escape via the
outer reader catch. Convert to IOException so finalizeHeaderBlock's
new try/catch can map it to GOAWAY(COMPRESSION_ERROR) per RFC 7541
§2.1 and RFC 9113 §4.3."
```

---

### Task 7: RST_STREAM(PROTOCOL_ERROR) on malformed content-length (R7)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java:450-460`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2SecurityTest.java`

**RFC research:** RFC 9113 §8.1.2.6 mandates malformed messages — including unparseable content-length — be treated as a stream error of type PROTOCOL_ERROR. nghttp2, Caddy, and Apache Traffic Server all reject malformed content-length on h2; the consensus is clear. Current code silently leaves `declaredContentLength == -1` and lets the handler run, which means the DATA-frame-layer overflow check (line 579) is the only protection — but that check is against `-1` (unlimited) so it does nothing. Real spec violation, fix unconditionally.

Sources:
- [RFC 9113: HTTP/2](https://www.rfc-editor.org/rfc/rfc9113.html)
- [nghttp2 issue #1408 — invalid content-length](https://github.com/nghttp2/nghttp2/issues/1408)
- [Caddy h2spec failures](https://github.com/caddyserver/caddy/issues/2132)

- [ ] **Step 1: Write a failing test**

In `HTTP2SecurityTest.java`:

```java
@Test
public void malformed_content_length_yields_rst_stream_protocol_error() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  try (var server = makeServer("http", (req, res) -> res.setStatus(200), listener).start()) {
    try (var sock = openH2cConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // POST with content-length: "abc" (literal-with-indexing, name idx 28, value "abc").
      byte[] headers = new byte[]{
          (byte) 0x83, // :method POST
          (byte) 0x84, // :path /
          (byte) 0x86, // :scheme http
          (byte) 0x41, 0x09, 'l','o','c','a','l','h','o','s','t',
          (byte) 0x5c, 0x03, 'a','b','c' // content-length: abc
      };
      writeFrameHeader(out, headers.length, 0x1, 0x4, 1);
      out.write(headers);
      out.flush();

      sock.setSoTimeout(3000);
      var frame = readFirstFrame(sock.getInputStream());
      assertEquals(frame.type(), 0x3, "Expected RST_STREAM");
      assertEquals(frame.errorCode(), HTTP2ErrorCode.PROTOCOL_ERROR.value);
    }
  }
}
```

- [ ] **Step 2: Run the test — should FAIL with no RST_STREAM**

Run: `latte test --test=HTTP2SecurityTest#malformed_content_length_yields_rst_stream_protocol_error`
Expected: FAIL.

- [ ] **Step 3: Modify the content-length parse in HTTP2Connection.java**

Change lines 450-460 from:

```java
for (var f : fields) {
  if (f.name().equals("content-length")) {
    try {
      stream.setDeclaredContentLength(Long.parseLong(f.value()));
    } catch (NumberFormatException ignore) {
      // Malformed content-length — let handler deal with it.
    }
    break;
  }
}
```

to:

```java
for (var f : fields) {
  if (f.name().equals("content-length")) {
    try {
      long cl = Long.parseLong(f.value());
      if (cl < 0) {
        // Negative content-length is malformed.
        rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
        return;
      }
      stream.setDeclaredContentLength(cl);
    } catch (NumberFormatException e) {
      // RFC 9113 §8.1.2.6 — malformed content-length is a stream error of type PROTOCOL_ERROR.
      logger.debug("Malformed content-length [{}] on stream [{}]", f.value(), streamId);
      rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
      return;
    }
    break;
  }
}
```

Note: the `return` exits `finalizeHeaderBlock` before the stream is registered + handler spawned. This is correct — we never want the handler to run for a malformed request.

- [ ] **Step 4: Run the test**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java src/test/java/org/lattejava/http/tests/server/HTTP2SecurityTest.java
git commit -m "Reject malformed h2 content-length with RST_STREAM(PROTOCOL_ERROR)

Per RFC 9113 §8.1.2.6, an unparseable or negative content-length is a
stream error. Was silently ignored, letting the handler run with
declaredContentLength=-1 which disabled DATA-frame overflow protection.
Matches behavior of nghttp2, Caddy, Apache Traffic Server."
```

---

## Phase 3 — Nice-to-have improvements

### Task 8: Track dropped RST_STREAMs in handler exception path (R3 improvement)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java:910-916` (spawnHandlerThread exception path)

**Improvement options considered:**
1. Replace `offer` (no timeout) with `offer(100ms timeout)` — keeps the connection healthy but slightly delays teardown.
2. Log when `offer` returns false — minimal cost, lets ops see dropped RST_STREAMs.
3. Both — combine.

Recommended: option 3. Cost is negligible.

- [ ] **Step 1: Update the handler exception path**

In `HTTP2Connection.java:910-916`, change:

```java
} catch (Exception e) {
  logger.error("h2 handler exception", e);
  writerQueue.offer(new HTTP2Frame.RSTStreamFrame(stream.streamId(), HTTP2ErrorCode.INTERNAL_ERROR.value));
  streams.remove(stream.streamId());
  streamPipes.remove(stream.streamId());
}
```

to:

```java
} catch (Exception e) {
  logger.error("h2 handler exception on stream [" + stream.streamId() + "]", e);
  try {
    if (!writerQueue.offer(new HTTP2Frame.RSTStreamFrame(stream.streamId(), HTTP2ErrorCode.INTERNAL_ERROR.value),
                            100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
      logger.debug("Dropped RST_STREAM for stream [{}] — writer queue full or dead", stream.streamId());
    }
  } catch (InterruptedException ie) {
    Thread.currentThread().interrupt();
  }
  streams.remove(stream.streamId());
  streamPipes.remove(stream.streamId());
}
```

- [ ] **Step 2: Run the test suite**

Run: `latte test --test=HTTP2RawFrameTest,HTTP2SecurityTest`
Expected: 0 failures.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java
git commit -m "Log when handler-error RST_STREAM is dropped due to full writer queue"
```

---

### Task 9: Debug-log swallowed IllegalStateException on trailers applyEvent (R6)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java:434-438`

- [ ] **Step 1: Edit the swallowed exception**

Change:

```java
try {
  existingStream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_END_STREAM);
} catch (IllegalStateException ignored) {
  // Race with concurrent RST_STREAM — stream is already closed; nothing to do.
}
```

to:

```java
try {
  existingStream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_END_STREAM);
} catch (IllegalStateException e) {
  logger.debug("Trailers HEADERS ignored on stream [{}] in state [{}]", existingStream.streamId(), existingStream.state(), e);
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java
git commit -m "Debug-log trailers applyEvent IllegalStateException for diagnosability"
```

---

### Task 10: SETTINGS ACK delivery on interrupt (R8)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java:715-719`

**RFC research:** RFC 9113 §6.5.3 requires a peer's SETTINGS frame be ACK'd. The InterruptedException-during-shutdown case is benign (the socket is closing anyway), so this is more about diagnosability than spec strictness. Major implementations (nghttp2, h2o) tear down the connection rather than silently dropping the ACK, which has the same observable outcome from the peer's perspective. Recommended: log the dropped ACK at debug; the connection is going down regardless. Don't busy-retry — that's worse.

Sources:
- [RFC 9113: HTTP/2 §6.5.3](https://www.rfc-editor.org/rfc/rfc9113.html)

- [ ] **Step 1: Update the SETTINGS ACK enqueue**

Change:

```java
try {
  writerQueue.put(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
} catch (InterruptedException ignore) {
  Thread.currentThread().interrupt();
}
```

to:

```java
if (!enqueueForWriter(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]))) {
  logger.debug("SETTINGS ACK dropped — writer queue blocked, connection tearing down");
}
```

(This depends on Task 4's `enqueueForWriter` helper being in place.)

- [ ] **Step 2: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java
git commit -m "Log dropped SETTINGS ACK at debug instead of silently swallowing"
```

---

### Task 11: Eliminate closed-stream HEADERS race (S2)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java:1000-1024` (LazyHeaderOutputStream emit)

**Risk re-confirmed:** Between the state-check at line 1007 and `applyEvent` at line 1017, the reader thread can process a RST_STREAM and move the stream to CLOSED. The HEADERS frame in `writerQueue` will be written to the wire — RFC 9113 §5.1 says no frames (other than PRIORITY) MAY be sent on a closed stream. **Performance cost of fix:** the fix adds a `synchronized(stream)` around the check + enqueue. Acquire is uncontended in the happy path (only one writer per stream); cost is one monitor enter per response header emission — negligible at h1.1's response-header tier and one-per-stream on h2.

- [ ] **Step 1: Write a failing test or verify by inspection**

A deterministic test requires the client to RST_STREAM at exactly the right moment between the check and the enqueue — hard to reproduce. Skip the test if reproduction is impractical; this is a code-review-confirmed race.

- [ ] **Step 2: Take the stream monitor around the check + applyEvent + enqueue**

In `HTTP2Connection.java`, change the section around lines 1000-1024:

```java
byte[] headerBlock;
synchronized (encoder) {
  headerBlock = encoder.encode(respFields);
}
synchronized (stream) {
  if (stream.state() == HTTP2Stream.State.CLOSED) {
    streamReset = true;
    return;
  }
  try {
    stream.applyEvent(HTTP2Stream.Event.SEND_HEADERS_NO_END_STREAM);
  } catch (IllegalStateException e) {
    streamReset = true;
    return;
  }
  if (!enqueueForWriter(new HTTP2Frame.HeadersFrame(stream.streamId(), HTTP2Frame.FLAG_END_HEADERS, headerBlock))) {
    streamReset = true;
    return;
  }
}
delegate = new HTTP2OutputStream(stream, writerQueue, peerSettings.maxFrameSize());
```

Key change: `applyEvent` runs BEFORE the enqueue inside the same synchronized block. If applyEvent throws (stream closed), we never enqueue. The reader's `handleRSTStream` must also synchronize on the same stream object when applying the closing event — verify that `HTTP2Stream.applyEvent` is itself synchronized (it is, per HTTP2Stream.java) — so the lock is contested correctly.

- [ ] **Step 3: Run the h2 suite**

Run: `latte test --test=HTTP2RawFrameTest,HTTP2BasicTest,HTTP2H2SpecBatch3Test,HTTP2H2SpecBatch4Test`
Expected: 0 failures.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java
git commit -m "Close write-side HEADERS race against client RST_STREAM

Move the stream state check, applyEvent, and writerQueue.offer into a
single synchronized(stream) block. Prevents the writer thread from
sending HEADERS to the wire after the stream has transitioned to CLOSED."
```

---

### Task 12: Harden HTTPInputStream.drain() against null request (I1)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/io/HTTPInputStream.java:112-143`

- [ ] **Step 1: Write a unit test**

In `src/test/java/org/lattejava/http/tests/io/HTTPInputStreamTest.java` (create if missing):

```java
@Test
public void drain_handles_null_request_without_npe() throws Exception {
  // EmptyHTTPInputStream is the canonical no-request subclass.
  var stream = EmptyHTTPInputStream.INSTANCE;
  assertEquals(stream.drain(), 0);
}
```

This already passes because EmptyHTTPInputStream overrides drain(). The point is to lock in the contract.

- [ ] **Step 2: Add the defensive null-check in HTTPInputStream.drain()**

Change line 123 from `if (!request.hasBody())` to:

```java
if (request == null || !request.hasBody()) {
  return 0;
}
```

- [ ] **Step 3: Run the unit test plus full suite**

Run: `latte test --test=HTTPInputStreamTest`
Expected: PASS. Then full suite.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/lattejava/http/server/io/HTTPInputStream.java src/test/java/org/lattejava/http/tests/io/HTTPInputStreamTest.java
git commit -m "Harden HTTPInputStream.drain() against null request

EmptyHTTPInputStream uses the no-arg constructor which leaves request
null. The drain() fast-path now no-ops on null instead of NPE'ing — any
future no-body subclass that forgets to override drain() degrades
gracefully rather than crashing."
```

---

### Task 13: Close socket when ProtocolSelector.select() throws (I4)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPServerThread.java:104,121-123`

**Risk confirmed:** At HTTPServerThread.java:104, `ProtocolSelector.select(clientSocket, ...)` can throw `IOException` (e.g. from SSLSocket.startHandshake() or peeking the h2c preface). The outer catch at line 121 logs at debug and continues; `clientSocket` is never closed. File descriptor leak per malformed connection attempt.

- [ ] **Step 1: Wrap the select() call to close the socket on failure**

Change line 104 from:

```java
ClientConnection conn = ProtocolSelector.select(clientSocket, configuration, context, instrumenter, listener, throughput);
```

to:

```java
ClientConnection conn;
try {
  conn = ProtocolSelector.select(clientSocket, configuration, context, instrumenter, listener, throughput);
} catch (IOException e) {
  logger.debug("Protocol selection failed; closing socket", e);
  try {
    clientSocket.close();
  } catch (IOException ignore) {
  }
  continue;
}
```

- [ ] **Step 2: Verify with a manual test (TLS handshake failure)**

This is hard to unit-test (requires a real TLS handshake that fails). Defer the test; the code path is simple enough to verify by inspection.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTPServerThread.java
git commit -m "Close accepted socket when ProtocolSelector.select() throws

Previously a failing TLS handshake or h2c-preface peek would leak the
file descriptor — IOException escaped to the outer catch which only
logged and continued."
```

---

### Task 14: Add h2 response-trailers wire test (IG-1)

**Files:**
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`

- [ ] **Step 1: Write the test**

```java
/**
 * RFC 9113 §8.1 — response trailers MUST be sent as a HEADERS frame with END_STREAM after the final
 * DATA frame, which itself must NOT have END_STREAM. Currently exercised only indirectly via gRPC tests.
 */
@Test
public void response_trailers_sent_as_headers_frame_after_final_data() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> {
    res.setStatus(200);
    res.setHeader("TE", "trailers"); // signal we will emit trailers
    res.setTrailer("x-checksum", "abc123");
    try (var out = res.getOutputStream()) {
      out.write("hello".getBytes());
    }
  };
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = openH2cConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      byte[] headers = new byte[]{(byte)0x82, (byte)0x84, (byte)0x86, (byte)0x41, 0x09, 'l','o','c','a','l','h','o','s','t'};
      writeFrameHeader(out, headers.length, 0x1, 0x4 | 0x1, 1);
      out.write(headers);
      out.flush();

      sock.setSoTimeout(3000);
      var in = sock.getInputStream();
      // Read frames in order: response HEADERS (no END_STREAM), DATA (no END_STREAM), trailers HEADERS (END_STREAM).
      var frames = readAllFramesUntilEndStream(in);
      // Find the last DATA and the trailers HEADERS.
      var lastData = frames.stream().filter(f -> f.type() == 0x0).reduce((a, b) -> b).orElseThrow();
      assertEquals(lastData.flags() & 0x1, 0, "Last DATA frame MUST NOT have END_STREAM when trailers follow");
      var trailers = frames.stream().filter(f -> f.type() == 0x1).reduce((a, b) -> b).orElseThrow();
      assertEquals(trailers.flags() & 0x1, 0x1, "Trailers HEADERS frame MUST have END_STREAM");
      // Decode trailers and verify x-checksum.
      HPACKDecoder dec = new HPACKDecoder(new HPACKDynamicTable(4096));
      var fields = dec.decode(trailers.payload());
      boolean found = fields.stream().anyMatch(f -> f.name().equals("x-checksum") && f.value().equals("abc123"));
      assertTrue(found, "Expected x-checksum: abc123 in trailer block");
    }
  }
}
```

You may need to add `readAllFramesUntilEndStream` helper if not present.

- [ ] **Step 2: Run the test**

Run: `latte test --test=HTTP2RawFrameTest#response_trailers_sent_as_headers_frame_after_final_data`
Expected: PASS (the implementation already supports this — the test pins behavior).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java
git commit -m "Add h2 response-trailers wire test"
```

---

### Task 15: Pin h2spec known-failure set (IG-2)

**Files:**
- Modify: `src/test/java/org/lattejava/http/tests/server/H2SpecHarnessTest.java`

- [ ] **Step 1: Inspect current H2SpecHarnessTest assertions**

Open the file and confirm which assertion currently locks the failure count.

- [ ] **Step 2: Add assertEquals on the exact failure set**

Add (or replace count-based assertion with):

```java
assertEquals(failureIds, Set.of("http2/6.5.3/1", "http2/6.9.1/1", "http2/6.9.2/1"),
    "h2spec failure set drifted — update HTTP2.md bug ledger");
```

The exact test IDs may need adjustment based on the harness's parsing — verify against an actual h2spec run output.

- [ ] **Step 3: Run the harness test**

Run: `latte test --test=H2SpecHarnessTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/org/lattejava/http/tests/server/H2SpecHarnessTest.java
git commit -m "Pin h2spec known-failure set so drift fails the test"
```

---

## Final verification

- [ ] **Run the full test suite**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: 0 failures, 2895+ tests pass (test count grew due to additions in Tasks 2, 5, 6, 7, 12, 14).

- [ ] **Run the h2 benchmark suite (optional, to detect perf regressions from Tasks 4/5/11)**

Run: `benchmarks/perf-test.sh` (or the targeted h2 scenarios from `docs/specs/HTTP2.md`).
Expected: throughput within ±5% of the 2026-05-21 baseline; Task 5 may show concurrency improvement in `h2-stream`.

- [ ] **Open the PR**

The branch is now ready. Use the `commit-commands:commit-push-pr` skill or `gh pr create` manually.

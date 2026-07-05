# HTTP/2 Connection Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose the 1,086-line `HTTP2Connection` into a thin dispatch loop over `HTTP2FrameHandler`s, per the approved design in `docs/design/2026-07-03-connection-redesign.md` (read it first — §5's frame × state matrix is the behavioral authority).

**Architecture:** New sealed `HTTP2Result` + `HTTP2FrameHandler` dispatch; header-block assembly moves into `HTTP2FrameReader`; `HTTP2StreamRegistry` owns the roster; `HTTP2Stream` validates frames against its state machine and delegates to four per-connection stream-frame handlers; `HTTP2Tools` holds the stateless extractions; `HTTP2Connection` shrinks to orchestration.

**Tech Stack:** Java 21, Latte build tool, TestNG. Zero production dependencies.

## Global Constraints

- All work on branch `http2/refactor-connection`. Commit after every task; **never merge anywhere** — the branch sits until Brian reviews it in detail.
- Commit messages: Conventional Commits (`refactor:`, `test:`, `docs:`), validated by the `commit-msg` hook (run `latte build` once to activate `.githooks`). End every commit body with the `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` trailer.
- Every new file starts with exactly this header (see `.claude/rules/copyright.md`), before `package`:
  ```java
  /*
   * Copyright (c) 2026 The Latte Project
   * SPDX-License-Identifier: MIT
   */
  ```
- Code style (see `.claude/rules/code-conventions.md`): 2-space indent, 120-char target lines, full-uppercase acronyms (`HTTP2Foo`, never `Http2Foo`), fields/methods alphabetized within visibility groups, one blank line between members, prefer `import module java.base;` / `import module org.lattejava.http;` over class imports.
- Runtime values in exception/log messages wrapped in square brackets: `"stream [" + id + "]"` (see `.claude/rules/error-messages.md`).
- **Testing is module-level only** (design §10): behavior is verified through a running server and raw frames over a socket. Do NOT add unit tests for the new internal classes. New wire tests go in `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`, which already has the needed private helpers: `openH2CConnection(port)`, `writeFrameHeader(out, length, type, flags, streamId)`, `readUntilGoaway(in)`, `readUntilResponseHeaders(in)`, `readUntilRstStream(in)`, and the `MINIMAL_HPACK_GET` HPACK block constant.
- Test commands: `latte test --test=HTTP2RawFrameTest` (one class), `latte test` (all), `latte clean int` (full integration). Success = exit code 0, TestNG reports 0 failures.
- All new types live in `org.lattejava.http.server.internal.h2` (package `src/main/java/org/lattejava/http/server/internal/h2/`), which is NOT exported in `module-info.java` — do not touch `module-info.java`.
- The existing HTTP/2 socket suites (h2spec batches, security, flow control, rate limits, GOAWAY, raw-frame, ALPN/h2c/preface tests) must pass unmodified after every task. They are the conformance net.

## File Map

| File | Task | Disposition |
|---|---|---|
| `HTTP2Result.java` | 1 | Create — sealed result type |
| `HTTP2FrameHandler.java` | 1 | Create — dispatch interface |
| `HTTP2FrameReader.java` | 2 | Modify — header-block assembly |
| `HTTP2FrameReaderTest.java` (tests) | 2 | Modify — 3-arg constructor, lone-fragment cases |
| `HTTP2Tools.java` | 3 | Create — negotiateSettings / validateHeaders / buildRequest |
| `HTTP2OutputProtocol.java` | 4 | Create (extract inner class) |
| `HTTP2StreamFrameHandlers.java` | 5 | Create — handler bundle record |
| `HTTP2StreamRegistry.java` | 5 | Create |
| `HTTP2HeaderFrameHandler.java` | 5 | Create |
| `HTTP2DataFrameHandler.java` | 5 | Create |
| `HTTP2WindowUpdateFrameHandler.java` | 5 | Create |
| `HTTP2RSTStreamFrameHandler.java` | 5 | Create |
| `HTTP2ConnectionFrameHandler.java` | 5 | Create |
| `HTTP2HandlerDelegate.java` | 5 | Create (extract spawnHandlerThread body) |
| `HTTP2Stream.java` | 5, 6 | Modify — handleFrame, pipe, provenance, back-reference |
| `HTTP2Connection.java` | 2, 3, 4, 6 | Modify, then largely rewrite in 6 |
| `HTTP2RawFrameTest.java` (tests) | 2, 3, 6, 7 | Modify — new wire tests |
| `HTTP2StreamStateMachineTest.java` (tests) | 7 | Delete |
| `docs/design/2026-07-03-connection-redesign.md` | 8 | Modify — status → Implemented |

---

### Task 1: `HTTP2Result` and `HTTP2FrameHandler`

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Result.java`
- Create: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2FrameHandler.java`

**Interfaces:**
- Consumes: `HTTP2ErrorCode`, `HTTP2Frame` (existing).
- Produces: `HTTP2Result` (sealed: `Ok` / `ConnectionError(HTTP2ErrorCode code)` / `StreamError(int streamId, HTTP2ErrorCode code)` / `Shutdown`; constants `HTTP2Result.OK`, `HTTP2Result.SHUTDOWN`) and `HTTP2FrameHandler` (`HTTP2Result handleFrame(HTTP2Frame frame) throws IOException`). Every later task builds on these exact names.

- [ ] **Step 1: Create `HTTP2Result.java`**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

/**
 * The outcome of handling one HTTP/2 frame. Returned by every {@link HTTP2FrameHandler}; consumed only by the
 * {@code HTTP2Connection} dispatch loop, which is the single place error frames are emitted:
 * {@link ConnectionError} becomes a GOAWAY and ends the connection, {@link StreamError} becomes an RST_STREAM and the
 * loop continues, {@link Shutdown} exits the loop cleanly (peer GOAWAY), and {@link Ok} continues.
 */
public sealed interface HTTP2Result {
  HTTP2Result OK = new Ok();

  HTTP2Result SHUTDOWN = new Shutdown();

  record ConnectionError(HTTP2ErrorCode code) implements HTTP2Result {
  }

  record Ok() implements HTTP2Result {
  }

  record Shutdown() implements HTTP2Result {
  }

  record StreamError(int streamId, HTTP2ErrorCode code) implements HTTP2Result {
  }
}
```

- [ ] **Step 2: Create `HTTP2FrameHandler.java`**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;

/**
 * A dispatch target for inbound HTTP/2 frames. Implemented by {@code HTTP2ConnectionFrameHandler} (stream-0 frames)
 * and {@code HTTP2Stream} (everything else). Implementations never emit error frames themselves — they report the
 * outcome as an {@link HTTP2Result} and the connection dispatch loop emits.
 */
public interface HTTP2FrameHandler {
  HTTP2Result handleFrame(HTTP2Frame frame) throws IOException;
}
```

- [ ] **Step 3: Build**

Run: `latte build`
Expected: exit 0.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/h2/HTTP2Result.java src/main/java/org/lattejava/http/server/internal/h2/HTTP2FrameHandler.java
git commit -m "refactor: add HTTP2Result and HTTP2FrameHandler dispatch types"
```

---

### Task 2: Header-block assembly in `HTTP2FrameReader`

Design §4.11. After this task, `readFrame()` never returns a `ContinuationFrame` or a HEADERS fragment — HEADERS
arrives complete. `HTTP2Connection`'s existing CONTINUATION branches go dead (removed in Task 6, harmless now).

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2FrameReader.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java` (constructor call + one catch clause)
- Modify: `src/test/java/org/lattejava/http/tests/server/HTTP2FrameReaderTest.java`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`

**Interfaces:**
- Produces: `HTTP2FrameReader(InputStream in, byte[] buffer, int maxHeaderListSize)` (3-arg constructor replaces the 2-arg one) and nested `HTTP2FrameReader.HeaderListSizeException extends IOException`. Later tasks rely on: `readFrame()` only ever returns HEADERS with END_HEADERS set and a complete fragment.

- [ ] **Step 1: Write the failing wire tests** — add these methods to `HTTP2RawFrameTest` (same style as its existing tests; frame type/flag constants inline like the rest of the file):

```java
/**
 * RFC 9113 §4.3 — a header block split across HEADERS + CONTINUATION is one field block; the request must succeed
 * with the fragments reassembled.
 */
@Test
public void headers_split_across_continuations_yields_request() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // HEADERS on stream 1: first 3 bytes of the block, END_STREAM but NOT END_HEADERS.
      writeFrameHeader(out, 3, 0x1 /* HEADERS */, 0x1 /* END_STREAM */, 1);
      out.write(MINIMAL_HPACK_GET, 0, 3);
      // CONTINUATION on stream 1: the rest, END_HEADERS.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length - 3, 0x9 /* CONTINUATION */, 0x4 /* END_HEADERS */, 1);
      out.write(MINIMAL_HPACK_GET, 3, MINIMAL_HPACK_GET.length - 3);
      out.flush();

      sock.setSoTimeout(5000);
      int status = readUntilResponseHeaders(sock.getInputStream());
      assertEquals(status, 200, "Expected 200 from a split header block; got: " + status);
    }
  }
}

/**
 * RFC 9113 §4.3 — no frame of any type, on any stream (including stream 0), may interleave inside a header block.
 */
@Test
public void ping_interleaved_in_header_block_triggers_protocol_error() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // HEADERS on stream 1 without END_HEADERS — header block is open.
      writeFrameHeader(out, 3, 0x1 /* HEADERS */, 0x1 /* END_STREAM */, 1);
      out.write(MINIMAL_HPACK_GET, 0, 3);
      // PING interleaved mid-block — connection error PROTOCOL_ERROR.
      writeFrameHeader(out, 8, 0x6 /* PING */, 0, 0);
      out.write(new byte[8]);
      out.flush();

      sock.setSoTimeout(5000);
      int errorCode = readUntilGoaway(sock.getInputStream());
      assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for PING mid header block; got: " + errorCode);
    }
  }
}

/**
 * RFC 9113 §4.3 — CONTINUATION for a different stream inside an open header block is a connection error.
 */
@Test
public void continuation_on_wrong_stream_triggers_protocol_error() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      writeFrameHeader(out, 3, 0x1 /* HEADERS */, 0x1 /* END_STREAM */, 1);
      out.write(MINIMAL_HPACK_GET, 0, 3);
      writeFrameHeader(out, MINIMAL_HPACK_GET.length - 3, 0x9 /* CONTINUATION */, 0x4 /* END_HEADERS */, 3);
      out.write(MINIMAL_HPACK_GET, 3, MINIMAL_HPACK_GET.length - 3);
      out.flush();

      sock.setSoTimeout(5000);
      int errorCode = readUntilGoaway(sock.getInputStream());
      assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for CONTINUATION on wrong stream; got: " + errorCode);
    }
  }
}

/**
 * CVE-2024-27316 guard — cumulative HEADERS+CONTINUATION fragments beyond maxHeaderListSize (derived from
 * maxRequestHeaderSize) must produce GOAWAY(ENHANCE_YOUR_CALM=0xb) without buffering the rest of the block.
 */
@Test
public void header_block_exceeding_max_header_list_size_triggers_enhance_your_calm() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).withMaxRequestHeaderSize(1024).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // 800-byte junk fragment (never decoded — the cap fires pre-decode), no END_HEADERS.
      byte[] junk = new byte[800];
      writeFrameHeader(out, junk.length, 0x1 /* HEADERS */, 0x1 /* END_STREAM */, 1);
      out.write(junk);
      // Second 800-byte fragment pushes the cumulative block past 1024.
      writeFrameHeader(out, junk.length, 0x9 /* CONTINUATION */, 0x4 /* END_HEADERS */, 1);
      out.write(junk);
      out.flush();

      sock.setSoTimeout(5000);
      int errorCode = readUntilGoaway(sock.getInputStream());
      assertEquals(errorCode, 0xb, "Expected GOAWAY(ENHANCE_YOUR_CALM=0xb) for oversized header block; got: " + errorCode);
    }
  }
}
```

(A bare-CONTINUATION test already exists: `continuation_without_preceding_headers_triggers_protocol_error`.)

- [ ] **Step 2: Run the new tests to see the split-headers one pass and note current behavior**

Run: `latte test --test=HTTP2RawFrameTest`
Expected: the four new tests PASS already (today the connection-level guard produces the same wire behavior) —
except possibly timing-sensitive details. If all four pass, they are the pin that keeps Task 2 honest. If any fail,
STOP and re-read `HTTP2Connection.run()` — the test encodes wrong frame bytes.

- [ ] **Step 3: Restructure `HTTP2FrameReader`**

1. Rename the existing `readFrame()` method to `private HTTP2Frame readRawFrame()` (body unchanged).
2. Add field `private final int maxHeaderListSize;` and replace the constructor:

```java
public HTTP2FrameReader(InputStream in, byte[] buffer, int maxHeaderListSize) {
  this.in = in;
  this.buffer = buffer;
  this.maxHeaderListSize = maxHeaderListSize;
}
```

3. Add the new public `readFrame()` (RFC 9113 §4.3 / §6.10 assembly — design §4.11):

```java
/**
 * Reads the next logical frame. A HEADERS frame without END_HEADERS is assembled here: CONTINUATION frames are read
 * and concatenated until END_HEADERS, and the returned {@link HTTP2Frame.HeadersFrame} always carries the complete
 * header block with END_HEADERS set (END_STREAM comes from the initial HEADERS — RFC 9113 permits it nowhere else in
 * the sequence). CONTINUATION therefore never escapes this reader.
 */
public HTTP2Frame readFrame() throws IOException {
  HTTP2Frame frame = readRawFrame();
  if (frame instanceof ContinuationFrame) {
    // RFC 9113 §6.10: CONTINUATION with no open header block is a connection error PROTOCOL_ERROR.
    throw new ProtocolException("CONTINUATION frame without a preceding HEADERS frame");
  }
  if (!(frame instanceof HeadersFrame headers)) {
    return frame;
  }
  if (headers.headerBlockFragment().length > maxHeaderListSize) {
    throw new HeaderListSizeException("Header block exceeds [" + maxHeaderListSize + "] bytes");
  }
  if ((headers.flags() & FLAG_END_HEADERS) != 0) {
    return headers;
  }

  // Assemble: RFC 9113 §4.3 — the following frames MUST be CONTINUATION on the same stream, no interleaving of any
  // other frame type or stream, until END_HEADERS.
  ByteArrayOutputStream accumulator = new ByteArrayOutputStream(headers.headerBlockFragment().length * 2);
  accumulator.write(headers.headerBlockFragment());
  while (true) {
    HTTP2Frame next = readRawFrame();
    if (!(next instanceof ContinuationFrame continuation) || continuation.streamId() != headers.streamId()) {
      throw new ProtocolException("Frame [" + next.getClass().getSimpleName() + "] on stream [" + next.streamId() +
          "] interleaved in the header block of stream [" + headers.streamId() + "]");
    }
    accumulator.write(continuation.headerBlockFragment());
    if (accumulator.size() > maxHeaderListSize) {
      throw new HeaderListSizeException("Header block exceeds [" + maxHeaderListSize + "] bytes");
    }
    if ((continuation.flags() & FLAG_END_HEADERS) != 0) {
      return new HeadersFrame(headers.streamId(), headers.flags() | FLAG_END_HEADERS, accumulator.toByteArray());
    }
  }
}
```

4. Add the nested exception next to the existing two:

```java
/**
 * Thrown when a cumulative header block exceeds the advertised SETTINGS_MAX_HEADER_LIST_SIZE while being assembled
 * (CVE-2024-27316 guard). The connection handler must respond with GOAWAY(ENHANCE_YOUR_CALM).
 */
public static class HeaderListSizeException extends IOException {
  public HeaderListSizeException(String message) {
    super(message);
  }
}
```

- [ ] **Step 4: Update the two production/test call sites**

In `HTTP2Connection.run()` the reader construction becomes:

```java
var reader = new HTTP2FrameReader(inputStream, buffers.frameReadBuffer(), localSettings.maxHeaderListSize());
```

In the frame loop's catch chain, add between the existing `FrameSizeException` and `ProtocolException` catches:

```java
} catch (HTTP2FrameReader.HeaderListSizeException e) {
  // CVE-2024-27316: cumulative header block over SETTINGS_MAX_HEADER_LIST_SIZE — detected during assembly.
  goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
  break;
```

In `HTTP2FrameReaderTest`: append `, Integer.MAX_VALUE` as the third argument to every `new HTTP2FrameReader(...)`
call (raw-parse tests want no cap). Any test that feeds a lone HEADERS without END_HEADERS and asserts the returned
fragment must either append a CONTINUATION with END_HEADERS to its input bytes or assert the merged result — read
each such case and preserve its intent under the new contract.

- [ ] **Step 5: Run the reader and raw-frame suites**

Run: `latte test --test=HTTP2FrameReaderTest` then `latte test --test=HTTP2RawFrameTest`
Expected: PASS. The four Step-1 tests now exercise the reader path.

- [ ] **Step 6: Run the full suite**

Run: `latte test`
Expected: 0 failures. The h2spec batch tests around §6.10 now pass via the reader instead of the connection guard.

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/org/lattejava/http/server/internal/h2/ src/test/java/org/lattejava/http/tests/server/
git commit -m "refactor: assemble HEADERS+CONTINUATION blocks inside HTTP2FrameReader"
```

---

### Task 3: `HTTP2Tools` — negotiation, header validation, request building

Design §4.7. Pure extractions; `HTTP2Connection` keeps working and shrinks.

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Tools.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`

**Interfaces:**
- Produces (exact signatures later tasks call):
  - `static HTTP2Result negotiateSettings(HTTP2FrameReader reader, HTTP2FrameWriter frameWriter, OutputStream out, HTTP2Settings localSettings, HTTP2Settings peerSettings, Logger logger) throws IOException`
  - `static boolean validateHeaders(List<HPACKDynamicTable.HeaderField> fields, boolean isTrailer)` — pure; returns false on any RFC 9113 §8.1.2.* violation; the caller emits `StreamError(PROTOCOL_ERROR)`.
  - `static HTTPRequest buildRequest(List<HPACKDynamicTable.HeaderField> fields, HTTPContext context, String contextPath, String scheme, int port, String remoteAddress)`

- [ ] **Step 1: Write the failing wire test** — negotiation-phase malformed SETTINGS today dies as a silent close; the design requires a GOAWAY with the right code. Add to `HTTP2RawFrameTest`:

```java
/**
 * RFC 9113 §6.5.2 — SETTINGS_INITIAL_WINDOW_SIZE above 2^31-1 in the client's opening SETTINGS must produce
 * GOAWAY(FLOW_CONTROL_ERROR=0x3). Today the connection closes silently during negotiation; the redesign returns the
 * error through HTTP2Tools.negotiateSettings and run() emits the GOAWAY directly.
 */
@Test
public void malformed_settings_at_negotiation_triggers_flow_control_error() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = new Socket("localhost", server.getActualPort())) {
      var out = sock.getOutputStream();
      // h2c prior-knowledge preface, then a SETTINGS frame with INITIAL_WINDOW_SIZE (id 0x4) = 2^31.
      out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
      writeFrameHeader(out, 6, 0x4 /* SETTINGS */, 0, 0);
      out.write(new byte[]{0x0, 0x4, (byte) 0x80, 0x0, 0x0, 0x0}); // id=4, value=0x80000000
      out.flush();

      sock.setSoTimeout(5000);
      int errorCode = readUntilGoaway(sock.getInputStream());
      assertEquals(errorCode, 0x3, "Expected GOAWAY(FLOW_CONTROL_ERROR=0x3) for INITIAL_WINDOW_SIZE overflow; got: " + errorCode);
    }
  }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=HTTP2RawFrameTest`
Expected: the new test FAILS (socket closes with no GOAWAY → `readUntilGoaway` throws or times out).

- [ ] **Step 3: Create `HTTP2Tools`** — MIT header, package `org.lattejava.http.server.internal.h2`, `import module java.base;` + `import module org.lattejava.http;` + `import org.lattejava.http.server.internal.*;` as needed. Content:

```java
/**
 * Stateless HTTP/2 helpers extracted from the connection: SETTINGS negotiation, RFC 9113 §8.1.2 header validation,
 * and HTTPRequest construction from a decoded header list. All methods are pure given their parameters.
 */
public final class HTTP2Tools {
  private HTTP2Tools() {
  }

  /**
   * Runs the server side of the SETTINGS exchange: sends the local SETTINGS, requires the peer's first frame to be a
   * non-ACK SETTINGS, applies it, and ACKs. Never emits error frames and never touches the socket — a
   * {@link HTTP2Result.ConnectionError} tells the caller which GOAWAY to send. IOExceptions from a dead peer
   * propagate; there is nobody to send a GOAWAY to.
   */
  public static HTTP2Result negotiateSettings(HTTP2FrameReader reader, HTTP2FrameWriter frameWriter, OutputStream out,
                                              HTTP2Settings localSettings, HTTP2Settings peerSettings, Logger logger)
      throws IOException {
    frameWriter.writeFrame(new HTTP2Frame.SettingsFrame(0, localSettings.toPayload()));
    out.flush();

    HTTP2Frame firstFrame;
    try {
      firstFrame = reader.readFrame();
    } catch (HTTP2FrameReader.FrameSizeException e) {
      logger.debug("Frame size violation before SETTINGS: [{}]", e.getMessage());
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.FRAME_SIZE_ERROR);
    } catch (HTTP2FrameReader.HeaderListSizeException e) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    } catch (HTTP2FrameReader.ProtocolException e) {
      logger.debug("Protocol violation before SETTINGS: [{}]", e.getMessage());
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR);
    }

    if (!(firstFrame instanceof HTTP2Frame.SettingsFrame(int flags, byte[] payload)) || (flags & HTTP2Frame.FLAG_ACK) != 0) {
      logger.debug("Expected client SETTINGS frame after preface");
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR);
    }

    try {
      peerSettings.applyPayload(payload);
    } catch (HTTP2Settings.HTTP2SettingsException e) {
      logger.debug("Invalid client SETTINGS: [{}]", e.getMessage());
      return new HTTP2Result.ConnectionError(e.errorCode);
    }

    frameWriter.writeFrame(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
    out.flush();
    return HTTP2Result.OK;
  }

  public static boolean validateHeaders(List<HPACKDynamicTable.HeaderField> fields, boolean isTrailer) {
    // Body: HTTP2Connection.validateHeaders (currently HTTP2Connection.java:902-979) moved verbatim, with every
    // `rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR); return false;` pair collapsed to `return false;`.
  }

  public static HTTPRequest buildRequest(List<HPACKDynamicTable.HeaderField> fields, HTTPContext context,
                                         String contextPath, String scheme, int port, String remoteAddress) {
    // Body: HTTP2Connection.buildRequestFromHeaders (currently HTTP2Connection.java:410-429) moved verbatim; the
    // scheme/port/remoteAddress parameters replace the reads of listener.getCertificate(), listener.getPort(), and
    // socket.getInetAddress().getHostAddress().
  }
}
```

The two "moved verbatim" bodies are real moves — copy the code out of `HTTP2Connection`, do not retype it.

- [ ] **Step 4: Rewire `HTTP2Connection.run()`** — replace the block from `frameWriter.writeFrame(new HTTP2Frame.SettingsFrame(0, localSettings.toPayload()));` through `out.flush();` after the SETTINGS ACK (currently lines 146-176) with:

```java
HTTP2Result negotiation = HTTP2Tools.negotiateSettings(reader, frameWriter, out, localSettings, peerSettings, logger);
if (negotiation instanceof HTTP2Result.ConnectionError(HTTP2ErrorCode code)) {
  // The writer thread does not exist yet, so the GOAWAY is written directly, then the output side is half-closed so
  // the kernel sends FIN — h2spec keeps writing preface bytes and a plain close would race into an OS RST.
  sendGoAwayDirect(frameWriter, out, code);
  try {
    socket.shutdownOutput();
  } catch (IOException ignore) { /* best effort */ }
  return;
}

// RFC 9113 §4.2: outbound DATA frames may be up to the peer's SETTINGS_MAX_FRAME_SIZE. Grow the write buffer if the
// peer accepts larger frames than we configured locally; the frame writer holds a byte[] reference, so it is rebuilt
// to pick up the new buffer. Safe here — the writer thread has not started yet.
if (peerSettings.maxFrameSize() > localSettings.maxFrameSize()) {
  buffers.ensureFrameWriteCapacity(peerSettings.maxFrameSize());
  frameWriter = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
}
```

Then delete the private `validateHeaders` and `buildRequestFromHeaders` methods and repoint their callers:
- In `finalizeHeaderBlock`, both `if (!validateHeaders(fields, streamId, true))`-style calls become
  `if (!HTTP2Tools.validateHeaders(fields, true)) { rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR); return; }`
  (and the same with `false` for the non-trailer call).
- `buildRequestFromHeaders(fields, streamId)` becomes:
  ```java
  HTTPRequest request = HTTP2Tools.buildRequest(fields, context, configuration.getContextPath(),
      listener.getCertificate() != null ? "https" : "http", listener.getPort(),
      socket.getInetAddress().getHostAddress());
  ```

- [ ] **Step 5: Run the new test, then the full suite**

Run: `latte test --test=HTTP2RawFrameTest` — the Step-1 test now PASSES.
Run: `latte test`
Expected: 0 failures.

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/org/lattejava/http/server/internal/h2/ src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java
git commit -m "refactor: extract HTTP2Tools (settings negotiation, header validation, request building)"
```

---

### Task 4: Extract `HTTP2OutputProtocol` to a top-level class

Design §4.9. Behavior-preserving move of the inner class (currently `HTTP2Connection.java:988-1085`).

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2OutputProtocol.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java`

**Interfaces:**
- Produces: `HTTP2OutputProtocol implements HTTPOutputProtocol` with constructor
  `HTTP2OutputProtocol(HTTPResponse response, HTTP2Stream stream, HPACKEncoder encoder, HTTP2WriterThread writer, HTTP2ConnectionWindow connectionSendWindow, HTTP2Settings peerSettings, Logger logger)`.
  `peerSettings` is the live mutable object — `commitHeaders` reads `peerSettings.maxFrameSize()` at call time, preserving today's mid-connection SETTINGS dynamics.

- [ ] **Step 1: Move the class.** Create the file with the MIT header and the class body moved verbatim from the inner class, with these mechanical changes: class is `public class HTTP2OutputProtocol implements HTTPOutputProtocol` (drop `private`); add final fields + constructor for the five outer-class references it used (`writer`, `connectionSendWindow`, `peerSettings`, `logger`, plus the existing `response`/`stream`/`encoder` params); `new HTTP2OutputStream(stream, writer, connectionSendWindow, peerSettings.maxFrameSize())` stays byte-identical apart from now reading fields. Preserve the class-level Javadoc.

- [ ] **Step 2: Repoint both construction sites** in `HTTP2Connection.spawnHandlerThread` (the happy path and the `HTTPProcessingException` error path):

```java
var protocol = new HTTP2OutputProtocol(response, stream, encoder, writer, connectionSendWindow, peerSettings, logger);
```

Delete the inner class.

- [ ] **Step 3: Run the full suite**

Run: `latte test`
Expected: 0 failures.

- [ ] **Step 4: Commit**

```bash
git add -A src/main/java/org/lattejava/http/server/internal/h2/
git commit -m "refactor: extract HTTP2OutputProtocol to a top-level class"
```

---

### Task 5: Registry, stream rework, and the frame handlers (additive — not yet wired)

Everything new compiles alongside the old `HTTP2Connection` logic; nothing calls it until Task 6. `HTTP2Stream`
changes are additive (the old constructor and methods remain until Task 6 removes the leftovers). All files: MIT
header, module imports, alphabetized members.

**Files:**
- Create: `HTTP2StreamFrameHandlers.java`, `HTTP2StreamRegistry.java`, `HTTP2HeaderFrameHandler.java`,
  `HTTP2DataFrameHandler.java`, `HTTP2WindowUpdateFrameHandler.java`, `HTTP2RSTStreamFrameHandler.java`,
  `HTTP2ConnectionFrameHandler.java`, `HTTP2HandlerDelegate.java` (all under `src/main/java/org/lattejava/http/server/internal/h2/`)
- Modify: `HTTP2Stream.java`

**Interfaces:**
- Consumes: `HTTP2Result`/`HTTP2FrameHandler` (Task 1), `HTTP2Tools` (Task 3), `HTTP2OutputProtocol` (Task 4).
- Produces (Task 6 wires exactly these):
  - `record HTTP2StreamFrameHandlers(HTTP2DataFrameHandler dataHandler, HTTP2HeaderFrameHandler headerHandler, HTTP2RateLimitsTracker rateLimits, HTTP2RSTStreamFrameHandler rstStreamHandler, HTTP2WindowUpdateFrameHandler windowUpdateHandler)`
  - `HTTP2StreamRegistry(HTTP2Settings localSettings, HTTP2Settings peerSettings, HTTP2StreamFrameHandlers handlers)` with `HTTP2Stream lookup(int streamId)`, `boolean open(HTTP2Stream stream)`, `void remove(int streamId)`, `void close(int streamId)`, `Collection<HTTP2Stream> liveStreams()`, `int highestSeenStreamId()`
  - `HTTP2Stream(int streamId, State initialState, boolean remembered, int initialReceiveWindow, int initialSendWindow, HTTP2StreamFrameHandlers handlers, HTTP2StreamRegistry registry)` implementing `HTTP2FrameHandler`; wrappers `boolean open()`, `void deregister()`, `void close()`; pipe accessors `BlockingQueue<byte[]> pipe()` / `void setPipe(...)`
  - `HTTP2ConnectionFrameHandler(HTTP2ConnectionWindow connectionSendWindow, Logger logger, HTTP2Settings peerSettings, HTTP2RateLimitsTracker rateLimits, HTTP2StreamRegistry registry, HTTP2WriterThread writer)` implementing `HTTP2FrameHandler`
  - `HTTP2HeaderFrameHandler` methods `HTTP2Result handleNewStream(HTTP2Stream stream, HTTP2Frame.HeadersFrame frame)` and `HTTP2Result handleTrailers(HTTP2Stream stream, HTTP2Frame.HeadersFrame frame)`
  - `HTTP2DataFrameHandler.handle(HTTP2Stream, HTTP2Frame.DataFrame)`, `HTTP2WindowUpdateFrameHandler.handle(HTTP2Stream, HTTP2Frame.WindowUpdateFrame)`, `HTTP2RSTStreamFrameHandler.handle(HTTP2Stream, HTTP2Frame.RSTStreamFrame)` — all returning `HTTP2Result`
  - `HTTP2HandlerDelegate implements Runnable`

- [ ] **Step 1: `HTTP2StreamFrameHandlers.java`** — the bundle record above, one line per component, plus Javadoc:
"Per-connection stream-frame collaborators, shared by every stream the registry materializes."

- [ ] **Step 2: `HTTP2StreamRegistry.java`**

```java
/**
 * The stream roster for one connection: live streams, recently-closed memory, the highest client stream-id seen, and
 * the MAX_CONCURRENT_STREAMS bound. The registry classifies stream IDs and materializes stream objects — it never
 * interprets frames; all frame policy lives in {@link HTTP2Stream}.
 *
 * <p>Threading: {@link #lookup}, {@link #open}, and {@link #close} are reader-thread-only. {@link #remove} may be
 * called from handler virtual-threads (completion cleanup). {@link #highestSeenStreamId()} is read by the acceptor
 * thread during shutdown.
 */
public class HTTP2StreamRegistry {
  private static final int MAX_RECENTLY_CLOSED = 100;

  private final HTTP2StreamFrameHandlers handlers;

  private final Map<Integer, HTTP2Stream> live = new ConcurrentHashMap<>();

  private final HTTP2Settings localSettings;

  private final HTTP2Settings peerSettings;

  private final Deque<Integer> recentlyClosed = new ArrayDeque<>();

  private volatile int highestSeenStreamId;

  public HTTP2StreamRegistry(HTTP2Settings localSettings, HTTP2Settings peerSettings, HTTP2StreamFrameHandlers handlers) {
    this.localSettings = localSettings;
    this.peerSettings = peerSettings;
    this.handlers = handlers;
  }

  /**
   * Removes {@code streamId} from the roster and records it in recently-closed memory (reader thread only — the
   * RST_STREAM path). Contrast with {@link #remove}, which leaves no memory.
   */
  public void close(int streamId) {
    live.remove(streamId);
    recentlyClosed.addLast(streamId);
    if (recentlyClosed.size() > MAX_RECENTLY_CLOSED) {
      recentlyClosed.removeFirst();
    }
  }

  public int highestSeenStreamId() {
    return highestSeenStreamId;
  }

  public Collection<HTTP2Stream> liveStreams() {
    return live.values();
  }

  /**
   * Returns the stream for {@code streamId}: the live one, or a transient flyweight in the RFC 9113 state the ID is
   * in — CLOSED (remembered) if recently closed, CLOSED (forgotten) if at-or-below the highest seen ID, IDLE above it.
   */
  public HTTP2Stream lookup(int streamId) {
    HTTP2Stream stream = live.get(streamId);
    if (stream != null) {
      return stream;
    }
    if (recentlyClosed.contains(streamId)) {
      return materialize(streamId, HTTP2Stream.State.CLOSED, true);
    }
    if (streamId <= highestSeenStreamId) {
      return materialize(streamId, HTTP2Stream.State.CLOSED, false);
    }
    return materialize(streamId, HTTP2Stream.State.IDLE, false);
  }

  /**
   * Registers an opening stream. Bumps the highest seen ID even when refusing at the MAX_CONCURRENT_STREAMS cap —
   * the ID is consumed either way, so a retry on the same ID is a monotonicity PROTOCOL_ERROR (RFC 9113 permits
   * retrying a refused request only on a new stream).
   */
  public boolean open(HTTP2Stream stream) {
    highestSeenStreamId = Math.max(highestSeenStreamId, stream.streamId());
    if (live.size() >= localSettings.maxConcurrentStreams()) {
      return false;
    }
    live.put(stream.streamId(), stream);
    return true;
  }

  /** Removes {@code streamId} without closed-stream memory — the handler-thread completion path. */
  public void remove(int streamId) {
    live.remove(streamId);
  }

  private HTTP2Stream materialize(int streamId, HTTP2Stream.State state, boolean remembered) {
    return new HTTP2Stream(streamId, state, remembered, localSettings.initialWindowSize(),
        peerSettings.initialWindowSize(), handlers, this);
  }
}
```

- [ ] **Step 3: Rework `HTTP2Stream.java` (additive).** Add fields `private final boolean remembered;`,
`private final HTTP2StreamFrameHandlers handlers;`, `private final HTTP2StreamRegistry registry;`,
`private volatile BlockingQueue<byte[]> pipe;` and the new constructor from the Interfaces block (the old 3-arg
constructor stays until Task 6, delegating: `this(streamId, State.IDLE, false, initialReceiveWindow,
initialSendWindow, null, null)`). Add `implements HTTP2FrameHandler` and:

```java
/**
 * Validates {@code frame} against this stream's RFC 9113 §5.1 state (the design §5 matrix) and delegates legal
 * frames to the per-connection handlers. Frame-type rate limits run before state validation, preserving today's
 * check ordering (flood frames aimed at dead streams still count).
 */
@Override
public HTTP2Result handleFrame(HTTP2Frame frame) throws IOException {
  return switch (frame) {
    case HTTP2Frame.HeadersFrame f -> handleHeaders(f);
    case HTTP2Frame.DataFrame f -> handleData(f);
    case HTTP2Frame.WindowUpdateFrame f -> handleWindowUpdate(f);
    case HTTP2Frame.RSTStreamFrame f -> handleRST(f);
    case HTTP2Frame.PriorityFrame ignored -> HTTP2Result.OK;   // §5.3 advisory, legal in every state
    case HTTP2Frame.PushPromiseFrame ignored -> new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR);
    default -> HTTP2Result.OK;                                  // UnknownFrame §5.5; SETTINGS/PING/GOAWAY never route here
  };
}

private HTTP2Result handleData(HTTP2Frame.DataFrame f) {
  // Empty-DATA flood guard runs before state checks, as today.
  if (f.payload().length == 0 && (f.flags() & HTTP2Frame.FLAG_END_STREAM) == 0 && handlers.rateLimits().recordEmptyData()) {
    return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
  }
  return switch (state()) {
    case IDLE -> (streamId & 1) == 1
        ? new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR)  // §5.1 — DATA on an idle client stream
        : HTTP2Result.OK;                                                 // even IDs: ignore per §6.1
    case CLOSED -> remembered
        ? new HTTP2Result.ConnectionError(HTTP2ErrorCode.STREAM_CLOSED)   // §5.1 — recently closed
        : HTTP2Result.OK;                                                 // forgotten: ignore per §6.1
    case HALF_CLOSED_REMOTE -> new HTTP2Result.StreamError(streamId, HTTP2ErrorCode.STREAM_CLOSED); // §5.1
    case OPEN, HALF_CLOSED_LOCAL -> handlers.dataHandler().handle(this, f);
  };
}

private HTTP2Result handleHeaders(HTTP2Frame.HeadersFrame f) throws IOException {
  return switch (state()) {
    case IDLE -> (streamId & 1) == 0
        ? new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR)  // §5.1.1 — client streams are odd
        : handlers.headerHandler().handleNewStream(this, f);
    case CLOSED -> remembered
        ? new HTTP2Result.ConnectionError(HTTP2ErrorCode.STREAM_CLOSED)   // §5.1
        : new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR); // §5.1.1 monotonicity
    case OPEN, HALF_CLOSED_LOCAL -> (f.flags() & HTTP2Frame.FLAG_END_STREAM) != 0
        ? handlers.headerHandler().handleTrailers(this, f)
        : new HTTP2Result.StreamError(streamId, HTTP2ErrorCode.STREAM_CLOSED); // §8.1 — not trailers, not legal
    case HALF_CLOSED_REMOTE -> new HTTP2Result.StreamError(streamId, HTTP2ErrorCode.STREAM_CLOSED); // §5.1
  };
}

private HTTP2Result handleRST(HTTP2Frame.RSTStreamFrame f) {
  // Rapid-reset guard (CVE-2023-44487) runs before state checks, as today.
  if (handlers.rateLimits().recordRstStream()) {
    return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
  }
  return switch (state()) {
    case IDLE -> new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR); // §6.4 — RST on an idle stream
    case CLOSED -> HTTP2Result.OK;                                               // benign either way
    default -> handlers.rstStreamHandler().handle(this, f);
  };
}

private HTTP2Result handleWindowUpdate(HTTP2Frame.WindowUpdateFrame f) {
  // WINDOW_UPDATE flood guard runs before state checks, as today.
  if (handlers.rateLimits().recordWindowUpdate()) {
    return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
  }
  return switch (state()) {
    case IDLE -> (streamId & 1) == 1
        ? new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR)  // §5.1
        : HTTP2Result.OK;
    case CLOSED -> HTTP2Result.OK;                                        // §6.9 — tolerated after close
    default -> handlers.windowUpdateHandler().handle(this, f);
  };
}
```

Plus the roster wrappers and pipe accessors:

```java
/** Removes this stream from the roster and records closed-stream memory. Reader thread only. */
public void close() {
  registry.close(streamId);
}

/** Removes this stream from the roster without closed memory — handler-thread completion path. */
public void deregister() {
  registry.remove(streamId);
}

/** Registers this stream with the roster; false means the MAX_CONCURRENT_STREAMS cap refused it. */
public boolean open() {
  return registry.open(this);
}

public BlockingQueue<byte[]> pipe() {
  return pipe;
}

public void setPipe(BlockingQueue<byte[]> pipe) {
  this.pipe = pipe;
}
```

- [ ] **Step 4: The three small stream-frame handlers.** Bodies are today's `HTTP2Connection` logic with
`goAway(X)`/`rstStream(id, X)` + `return` rewritten as `return new HTTP2Result.ConnectionError(X)` /
`new HTTP2Result.StreamError(stream.streamId(), X)` / `HTTP2Result.OK`:

`HTTP2WindowUpdateFrameHandler` (no constructor dependencies):

```java
public class HTTP2WindowUpdateFrameHandler {
  public HTTP2Result handle(HTTP2Stream stream, HTTP2Frame.WindowUpdateFrame f) {
    if (f.windowSizeIncrement() == 0) {
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR); // §6.9
    }
    if (stream.sendWindow() + f.windowSizeIncrement() > Integer.MAX_VALUE) {
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.FLOW_CONTROL_ERROR); // §6.9.1
    }
    stream.incrementSendWindow(f.windowSizeIncrement());
    synchronized (stream) {
      stream.notifyAll();
    }
    return HTTP2Result.OK;
  }
}
```

`HTTP2RSTStreamFrameHandler` (constructor: `Logger logger`) — from `handleRSTStream` (`HTTP2Connection.java:683-716`):
apply `RECV_RST_STREAM` tolerating `IllegalStateException` with the existing debug log; `stream.close()`; put the EOF
sentinel on `stream.pipe()` if non-null (interrupt-safe, as today); return `HTTP2Result.OK`. (The rate limit and
idle/closed cases moved into `HTTP2Stream.handleRST`.)

`HTTP2DataFrameHandler` (constructor: `HTTP2Configuration http2Configuration, HTTP2Settings localSettings, Logger logger, HTTP2WriterThread writer`) —
from `handleData` (`HTTP2Connection.java:570-651`), live-path only (stream-0/rate/closed/idle cases moved up):
content-length overrun → `StreamError(PROTOCOL_ERROR)`; `consumeReceiveWindow`; `pipe.offer` with
`http2Configuration.getHandlerReadTimeout()`, on timeout `stream.deregister()` + `StreamError(CANCEL)`; on END_STREAM
`dataLengthMatches` check → `StreamError(PROTOCOL_ERROR)` on mismatch, EOF sentinel, `RECV_DATA_END_STREAM`; then the
replenish-at-half-window WINDOW_UPDATE enqueues verbatim; return `HTTP2Result.OK`. A null `stream.pipe()` (DATA after
END_STREAM-on-HEADERS while OPEN is impossible, but defensive) returns `HTTP2Result.OK` as today's ignore path.

- [ ] **Step 5: `HTTP2ConnectionFrameHandler.java`**

```java
/**
 * Handles every frame the dispatch loop routes to stream 0: SETTINGS, PING, connection-window WINDOW_UPDATE, and
 * GOAWAY. Stream-addressed frame types arriving with stream ID 0 are connection errors here.
 */
public class HTTP2ConnectionFrameHandler implements HTTP2FrameHandler {
  private final HTTP2ConnectionWindow connectionSendWindow;

  private final Logger logger;

  private final HTTP2Settings peerSettings;

  private final HTTP2RateLimitsTracker rateLimits;

  private final HTTP2StreamRegistry registry;

  private final HTTP2WriterThread writer;

  // Constructor assigning all six, parameters in field order.

  @Override
  public HTTP2Result handleFrame(HTTP2Frame frame) {
    return switch (frame) {
      case HTTP2Frame.SettingsFrame f -> handleSettings(f);
      case HTTP2Frame.PingFrame f -> handlePing(f);
      case HTTP2Frame.WindowUpdateFrame f -> handleWindowUpdate(f);
      case HTTP2Frame.GoawayFrame ignored -> HTTP2Result.SHUTDOWN;
      case HTTP2Frame.UnknownFrame ignored -> HTTP2Result.OK; // §5.5
      default -> new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR); // DATA/HEADERS/PUSH_PROMISE on stream 0
    };
  }

  private HTTP2Result handlePing(HTTP2Frame.PingFrame f) {
    if ((f.flags() & HTTP2Frame.FLAG_ACK) != 0) {
      return HTTP2Result.OK;
    }
    if (rateLimits.recordPing()) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    }
    writer.enqueueOrCloseWriter(new HTTP2Frame.PingFrame(HTTP2Frame.FLAG_ACK, f.opaqueData()));
    return HTTP2Result.OK;
  }

  private HTTP2Result handleSettings(HTTP2Frame.SettingsFrame f) {
    if ((f.flags() & HTTP2Frame.FLAG_ACK) != 0) {
      return HTTP2Result.OK;
    }
    if (rateLimits.recordSettings()) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    }
    int oldInitialWindow = peerSettings.initialWindowSize();
    try {
      peerSettings.applyPayload(f.payload());
    } catch (HTTP2Settings.HTTP2SettingsException e) {
      logger.debug("Invalid SETTINGS from peer: [{}]", e.getMessage());
      return new HTTP2Result.ConnectionError(e.errorCode);
    }
    int delta = peerSettings.initialWindowSize() - oldInitialWindow;
    if (delta != 0) {
      // RFC 9113 §6.9.2 — adjust open streams' send-windows by the delta and wake blocked writers.
      for (HTTP2Stream s : registry.liveStreams()) {
        s.incrementSendWindow(delta);
        synchronized (s) {
          s.notifyAll();
        }
      }
    }
    writer.enqueueOrCloseWriter(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
    return HTTP2Result.OK;
  }

  private HTTP2Result handleWindowUpdate(HTTP2Frame.WindowUpdateFrame f) {
    if (rateLimits.recordWindowUpdate()) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    }
    if (f.windowSizeIncrement() == 0) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR); // §6.9
    }
    if (connectionSendWindow.available() + f.windowSizeIncrement() > Integer.MAX_VALUE) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.FLOW_CONTROL_ERROR); // §6.9.1
    }
    connectionSendWindow.increment(f.windowSizeIncrement());
    return HTTP2Result.OK;
  }
}
```

- [ ] **Step 6: `HTTP2HandlerDelegate.java`** — `implements Runnable`; constructor
`(HTTPServerConfiguration configuration, HTTP2ConnectionWindow connectionSendWindow, HPACKEncoder encoder, Set<Thread> handlerThreads, Logger logger, HTTP2Settings peerSettings, HTTPRequest request, HTTPResponse response, HTTP2Stream stream, HTTP2WriterThread writer)`.
`run()` is the lambda body of `spawnHandlerThread` (`HTTP2Connection.java:829-895`) moved verbatim, with
`streams.remove(...)` + `streamPipes.remove(...)` pairs replaced by `stream.deregister()`, `rstStream(id, code)`
replaced by `writer.enqueueOrCloseWriter(new HTTP2Frame.RSTStreamFrame(stream.streamId(), code.value))`, and output
protocols built via Task 4's constructor.

- [ ] **Step 7: `HTTP2HeaderFrameHandler.java`** — the big one. Constructor:
`(HTTPServerConfiguration configuration, HTTP2ConnectionWindow connectionSendWindow, HTTPContext context, HPACKDecoder decoder, HPACKEncoder encoder, AtomicLong handledRequests, Set<Thread> handlerThreads, Instrumenter instrumenter, HTTPListenerConfiguration listener, Logger logger, HTTP2Settings peerSettings, Socket socket, HTTP2WriterThread writer)`.

```java
/**
 * Handles complete header blocks (the frame reader assembles fragments — a HeadersFrame here always carries the
 * whole block). Two entry points: a new stream's request headers, and trailers on an existing stream.
 */
public HTTP2Result handleNewStream(HTTP2Stream stream, HTTP2Frame.HeadersFrame f) throws IOException {
  boolean opened = stream.open();

  List<HPACKDynamicTable.HeaderField> fields;
  try {
    fields = decoder.decode(f.headerBlockFragment());
  } catch (IOException e) {
    // RFC 7541 §2.1 / RFC 9113 §4.3 — HPACK decode failure is a connection error, refused stream or not.
    logger.debug("HPACK decode failed on stream [{}]: [{}]", stream.streamId(), e.getMessage());
    return new HTTP2Result.ConnectionError(HTTP2ErrorCode.COMPRESSION_ERROR);
  }

  if (!opened) {
    // MAX_CONCURRENT_STREAMS refusal. The block was still HPACK-decoded above — RFC 9113 §4.3 requires processing
    // every header block to keep the shared dynamic table synchronized — but the fields are discarded.
    return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.REFUSED_STREAM);
  }

  if (!HTTP2Tools.validateHeaders(fields, false)) {
    // The stream was registered by open() above; today's code registers last, so error paths must deregister or a
    // malformed-request flood permanently consumes MAX_CONCURRENT_STREAMS slots. Same applies to the content-length
    // and state-transition error returns below (Task 5 review finding).
    stream.deregister();
    return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
  }

  HTTPRequest request = HTTP2Tools.buildRequest(fields, context, configuration.getContextPath(),
      listener.getCertificate() != null ? "https" : "http", listener.getPort(),
      socket.getInetAddress().getHostAddress());
  stream.setRequest(request);

  // §8.1.2.6 content-length capture: today's loop from HTTP2Connection.finalizeHeaderBlock (lines 484-503) moved
  // verbatim, with the two rstStream(...)+return error paths becoming
  // `stream.deregister(); return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);`.

  // State transition, END_STREAM vs not — from finalizeHeaderBlock lines 505-516; the IllegalStateException catch
  // becomes `stream.deregister(); return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.STREAM_CLOSED);`.

  // Multipart deep-copy, input-stream setup (EmptyHTTPInputStream vs pipe + HTTP2InputStream + PushbackInputStream +
  // HTTPInputStream) — from finalizeHeaderBlock lines 519-535 moved verbatim, except the pipe goes to
  // `stream.setPipe(pipe)` instead of a streamPipes map.

  HTTPResponse response = new HTTPResponse();
  Thread.ofVirtual().name("h2-handler-" + stream.streamId()).start(new HTTP2HandlerDelegate(configuration,
      connectionSendWindow, encoder, handlerThreads, logger, peerSettings, request, response, stream, writer));
  handledRequests.incrementAndGet();
  return HTTP2Result.OK;
}

public HTTP2Result handleTrailers(HTTP2Stream stream, HTTP2Frame.HeadersFrame f) {
  // Decode (COMPRESSION_ERROR on failure, as above), then validateHeaders(fields, true) →
  // StreamError(PROTOCOL_ERROR); then today's trailers delivery from finalizeHeaderBlock lines 448-472 moved
  // verbatim: addTrailer loop, EOF sentinel on stream.pipe() (null-safe), RECV_HEADERS_END_STREAM with the tolerated
  // IllegalStateException + debug log. Return HTTP2Result.OK.
}
```

Note the deliberate behavior change vs. today (design §9): the refused path decodes before returning
`REFUSED_STREAM`, and decode happens before validation in both paths — HPACK state must advance even for streams we
reject. Everything else is a move.

- [ ] **Step 8: Build**

Run: `latte build`
Expected: exit 0. Nothing invokes the new classes yet; the suite is unaffected (`latte test` optional here).

- [ ] **Step 9: Commit**

```bash
git add -A src/main/java/org/lattejava/http/server/internal/h2/
git commit -m "refactor: add stream registry, frame handlers, and stream frame dispatch (unwired)"
```

---

### Task 6: Rewrite `HTTP2Connection` onto the dispatch loop

The cut-over. Design §4.10's loop verbatim; all superseded code deleted.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Stream.java` (remove transitional leftovers)
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1-5, exactly as specified there.

- [ ] **Step 1: Write the failing wire test** — mid-stream HEADERS without END_STREAM must be a *stream* error (§5 matrix, OPEN column), which today's code already does — but add the pin now because this task is where the matrix becomes the implementation:

```java
/**
 * RFC 9113 §8.1 — a second HEADERS on an open stream without END_STREAM is not trailers and must produce
 * RST_STREAM(STREAM_CLOSED=0x5) on that stream, not a connection error.
 */
@Test
public void mid_stream_headers_without_end_stream_triggers_rst_stream_closed() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      // Open stream 1 without END_STREAM (body pending) — stream is OPEN.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 /* END_HEADERS */, 1);
      out.write(MINIMAL_HPACK_GET);
      // Second HEADERS on stream 1, END_HEADERS but no END_STREAM — illegal mid-stream HEADERS.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 /* END_HEADERS */, 1);
      out.write(MINIMAL_HPACK_GET);
      out.flush();

      sock.setSoTimeout(5000);
      int errorCode = readUntilRstStream(sock.getInputStream());
      assertEquals(errorCode, 0x5, "Expected RST_STREAM(STREAM_CLOSED=0x5) for mid-stream HEADERS; got: " + errorCode);
    }
  }
}
```

Run: `latte test --test=HTTP2RawFrameTest` — expected PASS today (it pins behavior across the cut-over).

- [ ] **Step 2: Rewrite `run()`.** Field changes first: delete `streamPipes`, `streams`, `recentlyClosedStreams`,
`highestSeenStreamId`, `MAX_RECENTLY_CLOSED`, `peerSettings`' initializer stays; add
`private final AtomicLong handledRequests = new AtomicLong();` (replacing the `long` field; `getHandledRequests()`
returns `handledRequests.get()`), `private volatile HTTP2StreamRegistry registry;`. After the writer thread starts,
wire the collaborators:

```java
HPACKDecoder decoder = new HPACKDecoder(new HPACKDynamicTable(localSettings.headerTableSize()));
HPACKEncoder encoder = new HPACKEncoder(new HPACKDynamicTable(peerSettings.headerTableSize()));

var headerHandler = new HTTP2HeaderFrameHandler(configuration, connectionSendWindow, context, decoder, encoder,
    handledRequests, handlerThreads, instrumenter, listener, logger, peerSettings, socket, writer);
var handlers = new HTTP2StreamFrameHandlers(
    new HTTP2DataFrameHandler(configuration.getHTTP2Configuration(), localSettings, logger, writer),
    headerHandler, rateLimits, new HTTP2RSTStreamFrameHandler(logger), new HTTP2WindowUpdateFrameHandler());
registry = new HTTP2StreamRegistry(localSettings, peerSettings, handlers);
var connectionFrameHandler = new HTTP2ConnectionFrameHandler(connectionSendWindow, logger, peerSettings, rateLimits,
    registry, writer);
```

Then replace everything from the old HPACK setup through the end of the frame loop (including the
`headerBlockStreamId` machinery, the entire frame `switch`, and the `HTTP2SettingsException` catch — negotiation now
owns that) with the design §4.10 loop:

```java
try {
  frames:
  while (true) {
    state = HTTPConnection.State.Read;
    if (writer.isClosed()) {
      logger.debug("Writer thread closed; reader exiting");
      break;
    }

    HTTP2Frame frame;
    try {
      frame = reader.readFrame(); // HEADERS arrives as one complete, assembled header block
    } catch (HTTP2FrameReader.FrameSizeException e) {
      goAway(HTTP2ErrorCode.FRAME_SIZE_ERROR); // §4.2
      break;
    } catch (HTTP2FrameReader.HeaderListSizeException e) {
      goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM); // CVE-2024-27316
      break;
    } catch (HTTP2FrameReader.ProtocolException e) {
      goAway(HTTP2ErrorCode.PROTOCOL_ERROR); // §5.4.1
      break;
    }

    HTTP2FrameHandler handler = frame.streamId() == 0 ? connectionFrameHandler : registry.lookup(frame.streamId());
    HTTP2Result result = handler.handleFrame(frame);

    switch (result) {
      case HTTP2Result.Ok ignored -> {
      }
      case HTTP2Result.StreamError(int id, HTTP2ErrorCode code) -> rstStream(id, code);
      case HTTP2Result.ConnectionError(HTTP2ErrorCode code) -> {
        goAway(code);
        break frames;
      }
      case HTTP2Result.Shutdown ignored -> {
        return; // Peer GOAWAY — drain and exit.
      }
    }
  }
} catch (Throwable t) {
  logger.error("Unhandled exception in HTTP/2 reader; emitting GOAWAY(INTERNAL_ERROR)", t);
  goAway(HTTP2ErrorCode.INTERNAL_ERROR);
} finally {
  writer.requestStop();
}
```

Delete the now-orphaned methods: `finalizeHeaderBlock`, `handleContinuationFrame`, `handleData`,
`handleHeadersFrame`, `handlePing`, `handleRSTStream`, `handleSettings`, `handleWindowUpdate`, `isRecentlyClosed`,
`markClosed`, `spawnHandlerThread`. Keep: `goAway`, `rstStream`, `sendGoAwayDirect`, `shutdown()` (its
`highestSeenStreamId` read becomes `HTTP2StreamRegistry r = registry; ... r != null ? r.highestSeenStreamId() : 0` —
same null-guard pattern as `writer`; same in `goAway`), the teardown `finally`, and the `HTTPConnection` accessors.
Refresh the class Javadoc's threading-model section to name the new collaborators (preserve, don't drop, the doc —
see design §11 house rules).

- [ ] **Step 3: Strip `HTTP2Stream` transitional leftovers** — delete the old 3-arg constructor (all production
callers are gone). Everything else added in Task 5 stays.

- [ ] **Step 4: Run the full suite**

Run: `latte test`
Expected: 0 failures. Debugging guidance: a wrong GOAWAY/RST code in an h2spec batch test means a §5 matrix cell is
mis-encoded in `HTTP2Stream.handleFrame` — fix the switch, not the test. The matrix (design §5) supersedes today's
code in the one corner where today is inconsistent (DATA on HALF_CLOSED_REMOTE with a live pipe).

- [ ] **Step 5: Commit**

```bash
git add -A src/main/java/org/lattejava/http/server/internal/h2/ src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java
git commit -m "refactor: rewrite HTTP2Connection onto the HTTP2FrameHandler dispatch loop"
```

---

### Task 7: Matrix gap tests, §9 HPACK-synchronization test, retire the state-machine unit test

**Files:**
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`
- Delete: `src/test/java/org/lattejava/http/tests/server/HTTP2StreamStateMachineTest.java`

**Interfaces:**
- Consumes: the running server only (module-level tests).

- [ ] **Step 1: Add the REFUSED_STREAM + HPACK-synchronization test** (design §9 — this is the one deliberate
behavior change; it FAILS against pre-Task-6 code and PASSES now):

```java
/**
 * Design §9 / RFC 9113 §4.3 — a header block on a stream refused at MAX_CONCURRENT_STREAMS must still be
 * HPACK-decoded (dynamic-table state is connection-wide), so a later request that references the dynamic table
 * entries added by the refused block must decode correctly.
 */
@Test
public void refused_stream_header_block_still_updates_hpack_state() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  CountDownLatch release = new CountDownLatch(1);
  AtomicReference<String> customHeader = new AtomicReference<>();
  HTTPHandler handler = (req, res) -> {
    if (req.getHeader("x-a") != null) {
      customHeader.set(req.getHeader("x-a"));
    } else {
      try {
        release.await(10, TimeUnit.SECONDS); // hold stream 1 open so the cap stays occupied
      } catch (InterruptedException ignore) {
      }
    }
    res.setStatus(200);
  };
  try (var server = makeServer("http", handler, listener).withHTTP2(h2 -> h2.withMaxConcurrentStreams(1)).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      var in = sock.getInputStream();
      sock.setSoTimeout(5000);

      // Stream 1: occupies the single concurrent-stream slot; handler blocks on the latch.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS|END_STREAM */, 1);
      out.write(MINIMAL_HPACK_GET);
      out.flush();

      // Stream 3: refused at the cap. Its block adds "x-a: 1" to the dynamic table via literal-with-incremental-
      // indexing (0x40, new name). RFC 9113 §4.3: the server must decode this block even though it refuses the
      // stream.
      byte[] literalXA = {
          (byte) 0x40, 0x03, 'x', '-', 'a', 0x01, '1'
      };
      byte[] refusedBlock = new byte[MINIMAL_HPACK_GET.length + literalXA.length];
      System.arraycopy(MINIMAL_HPACK_GET, 0, refusedBlock, 0, MINIMAL_HPACK_GET.length);
      System.arraycopy(literalXA, 0, refusedBlock, MINIMAL_HPACK_GET.length, literalXA.length);
      writeFrameHeader(out, refusedBlock.length, 0x1, 0x4 | 0x1, 3);
      out.write(refusedBlock);
      out.flush();
      int rstCode = readUntilRstStream(in);
      assertEquals(rstCode, 0x7, "Expected RST_STREAM(REFUSED_STREAM=0x7) at the concurrency cap; got: " + rstCode);

      // Release stream 1 and let it finish so the slot frees up. NOTE: readUntilResponseHeaders returns the STREAM
      // ID of the first response HEADERS frame, not the HTTP status (Task 2 discovered this helper contract).
      release.countDown();
      assertEquals(readUntilResponseHeaders(in), 1, "Expected the released stream 1 response first");

      // Stream 5: references the x-a entry the REFUSED block created. Careful index math: MINIMAL_HPACK_GET's own
      // :authority literal (0x41, incremental indexing) adds an entry DURING stream 5's decode, shifting x-a from
      // index 62 to 63 — so the reference is 0x80 | 63 = 0xBF. If the server skipped decoding stream 3's refused
      // block, x-a is absent and this decodes a duplicate :authority (PROTOCOL_ERROR) instead.
      byte[] indexedBlock = new byte[MINIMAL_HPACK_GET.length + 1];
      System.arraycopy(MINIMAL_HPACK_GET, 0, indexedBlock, 0, MINIMAL_HPACK_GET.length);
      indexedBlock[MINIMAL_HPACK_GET.length] = (byte) 0xBF;
      writeFrameHeader(out, indexedBlock.length, 0x1, 0x4 | 0x1, 5);
      out.write(indexedBlock);
      out.flush();

      assertEquals(readUntilResponseHeaders(in), 5, "Expected a response on stream 5 (helper returns stream ID)");
      assertEquals(customHeader.get(), "1", "Dynamic-table entry from the refused block must decode on stream 5");
    }
  }
}
```

- [ ] **Step 2: Add the monotonicity-after-refusal test:**

```java
/**
 * Design §4.4 — a refused stream ID is consumed: retrying HEADERS on the same ID is a monotonicity violation,
 * GOAWAY(PROTOCOL_ERROR). RFC 9113 permits retrying a refused request only on a new stream ID.
 */
@Test
public void headers_reusing_refused_stream_id_triggers_protocol_error() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  CountDownLatch release = new CountDownLatch(1);
  HTTPHandler handler = (req, res) -> {
    try {
      release.await(10, TimeUnit.SECONDS);
    } catch (InterruptedException ignore) {
    }
    res.setStatus(200);
  };
  try (var server = makeServer("http", handler, listener).withHTTP2(h2 -> h2.withMaxConcurrentStreams(1)).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      var in = sock.getInputStream();
      sock.setSoTimeout(5000);

      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
      out.write(MINIMAL_HPACK_GET);
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 3); // refused at cap
      out.write(MINIMAL_HPACK_GET);
      out.flush();
      assertEquals(readUntilRstStream(in), 0x7);

      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 3); // reuse of a consumed ID
      out.write(MINIMAL_HPACK_GET);
      out.flush();

      int errorCode = readUntilGoaway(in);
      assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for reusing a refused stream ID; got: " + errorCode);
      release.countDown();
    }
  }
}
```

- [ ] **Step 3: Add the forgotten-stream (eviction) test:**

```java
/**
 * Design §4.4 rule 3 — once a closed stream ID is evicted from the 100-entry recently-closed memory, DATA on it is
 * ignored per §6.1 (not STREAM_CLOSED), and the connection keeps serving. Raises rstStreamMax so 101 client resets
 * don't trip the rapid-reset guard.
 */
@Test
public void data_on_forgotten_closed_stream_is_ignored() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  // The handler reads the body: the 101 reset streams park in the body read until the RST EOFs their pipe, and by
  // then the stream is CLOSED so the response is suppressed (HTTP2OutputProtocol.commitHeaders null-sinks reset
  // streams). Net effect: the ONLY response frames on the wire come from the final request, so the assertion below
  // genuinely proves the post-eviction request was served.
  HTTPHandler handler = (req, res) -> {
    req.getInputStream().readAllBytes();
    res.setStatus(200);
  };
  try (var server = makeServer("http", handler, listener)
      .withHTTP2(h2 -> h2.withRateLimits(rl -> rl.withRSTStreamMax(1000))).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      var in = sock.getInputStream();
      sock.setSoTimeout(5000);

      // Open and reset 101 streams (IDs 1..201) — stream 1 falls out of the recently-closed memory.
      for (int id = 1; id <= 201; id += 2) {
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 /* END_HEADERS, no END_STREAM */, id);
        out.write(MINIMAL_HPACK_GET);
        writeFrameHeader(out, 4, 0x3 /* RST_STREAM */, 0, id);
        out.write(new byte[]{0, 0, 0, 0x8}); // CANCEL
      }
      // DATA on evicted stream 1 — must be ignored, not STREAM_CLOSED.
      writeFrameHeader(out, 3, 0x0 /* DATA */, 0x1 /* END_STREAM */, 1);
      out.write(new byte[]{1, 2, 3});
      // The connection must still serve a fresh request.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 203);
      out.write(MINIMAL_HPACK_GET);
      out.flush();

      assertEquals(readUntilResponseHeaders(in), 203,
          "Expected a response on stream 203 — the connection must survive DATA on a forgotten stream (helper returns stream ID)");
    }
  }
}
```

Check the exact `HTTP2RateLimits` setter name before using `withRSTStreamMax` — read
`src/main/java/org/lattejava/http/server/HTTP2RateLimits.java` and use its actual `with*` method for the RST_STREAM
limit.

- [ ] **Step 4: Add the malformed-HEADERS slot-leak test** (pins the Task 5 review fix — error paths after
`registry.open` must deregister):

```java
/**
 * Task 5 review finding — a HEADERS block that opens a stream but fails validation (here: unparseable
 * content-length, §8.1.2.6) must release its MAX_CONCURRENT_STREAMS slot when it is RST. Otherwise a
 * malformed-request flood permanently consumes the cap and well-formed requests get spuriously REFUSED.
 */
@Test
public void malformed_headers_do_not_leak_concurrency_slots() throws Exception {
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
  HTTPHandler handler = (req, res) -> res.setStatus(200);
  try (var server = makeServer("http", handler, listener).withHTTP2(h2 -> h2.withMaxConcurrentStreams(2)).start()) {
    try (var sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      var in = sock.getInputStream();
      sock.setSoTimeout(5000);

      // HPACK literal-without-indexing, name = static index 28 (content-length), value "abc" (unparseable).
      byte[] badContentLength = {(byte) 0x0f, (byte) 0x0d, 0x03, 'a', 'b', 'c'};
      byte[] malformed = new byte[MINIMAL_HPACK_GET.length + badContentLength.length];
      System.arraycopy(MINIMAL_HPACK_GET, 0, malformed, 0, MINIMAL_HPACK_GET.length);
      System.arraycopy(badContentLength, 0, malformed, MINIMAL_HPACK_GET.length, badContentLength.length);

      // Two malformed streams — enough to fill the cap of 2 if the slots leak.
      for (int id = 1; id <= 3; id += 2) {
        writeFrameHeader(out, malformed.length, 0x1, 0x4 | 0x1 /* END_HEADERS|END_STREAM */, id);
        out.write(malformed);
      }
      out.flush();
      assertEquals(readUntilRstStream(in), 0x1, "Expected RST_STREAM(PROTOCOL_ERROR) for malformed content-length");
      assertEquals(readUntilRstStream(in), 0x1, "Expected RST_STREAM(PROTOCOL_ERROR) for malformed content-length");

      // A well-formed request must now succeed — the two malformed streams must not occupy the cap.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 5);
      out.write(MINIMAL_HPACK_GET);
      out.flush();
      assertEquals(readUntilResponseHeaders(in), 5, "Expected a response on stream 5 — malformed streams leaked the cap");
    }
  }
}
```

- [ ] **Step 5: Delete the internal state-machine test** (design §10 — wire tests now pin these transitions):

```bash
git rm src/test/java/org/lattejava/http/tests/server/HTTP2StreamStateMachineTest.java
```

- [ ] **Step 6: Run the full suite**

Run: `latte test`
Expected: 0 failures, including the four new tests.

- [ ] **Step 7: Commit**

```bash
git add -A src/test/java/org/lattejava/http/tests/server/
git commit -m "test: wire-level coverage for stream lifecycle matrix and refused-stream HPACK sync"
```

---

### Task 8: Full integration pass and design-doc closeout

**Files:**
- Modify: `docs/design/2026-07-03-connection-redesign.md`

- [ ] **Step 1: Full clean integration build**

Run: `latte clean int`
Expected: exit 0, all tests pass (this includes the performance/timeout-tagged tests — budget time accordingly).

- [ ] **Step 2: Sanity-check the shrink** — `wc -l src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java` should report roughly 250-350 lines. If it is still over ~450, something that the design moved out is still living there; find it and move it.

- [ ] **Step 3: Mark the design implemented** — in `docs/design/2026-07-03-connection-redesign.md` change
`- **Status:** Approved — implementation pending` to `- **Status:** Implemented`.

- [ ] **Step 4: Commit**

```bash
git add docs/design/2026-07-03-connection-redesign.md
git commit -m "docs: mark connection redesign implemented"
```

Do NOT merge, do NOT delete the branch — it waits for Brian's detailed review.

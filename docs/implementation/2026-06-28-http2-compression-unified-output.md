# Unified HTTPOutputStream with HTTP/2 Compression — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rewrite `HTTPOutputStream` into a single protocol-agnostic response body pipeline shared by HTTP/1 and HTTP/2 via an output-protocol seam, giving HTTP/2 full gzip/deflate parity with HTTP/1.

**Architecture:** `HTTPOutputStream` owns the commit lifecycle, HEAD/204/304 body suppression, and compression (gzip/deflate selection + `Content-Encoding`/`Vary`). Two protocol-specific concerns — emitting response headers and providing the framing body sink — move behind an `HTTPOutputProtocol` interface implemented by `HTTP1OutputProtocol` (text preamble + chunked framing + socket buffer) and `HTTP2OutputProtocol` (HPACK `HEADERS` frame + `HTTP2OutputStream` DATA framing). `ChunkedOutputStream` becomes a pure data-chunk framer; the chunk terminator + trailers move to `HTTP1OutputProtocol.commitTrailers()`. `HTTPResponse.rawOutputStream` is deleted so both protocols route through `setOutputStream(...)`.

**Tech Stack:** Java 21 (virtual threads, blocking I/O, `import module`), zero production dependencies. Build: **Latte** (`latte clean build`, `latte test`). Tests: **TestNG** via `BaseTest`.

**Design doc:** `docs/design/2026-06-28-http2-compression-unified-output-design.md`

## Global Constraints

- **Java 21**; use `import module java.base;` / module imports over class imports (per `.claude/rules/code-conventions.md`).
- **2-space indent, 4-space continuation; 120-col target.** Alphabetize fields/methods within visibility groups; no blank lines between fields.
- **Acronyms fully upper-cased** in identifiers (`HTTPOutputProtocol`, not `HttpOutputProtocol`).
- **Error/log/`toString` runtime values wrapped in `[brackets]`**, not quotes (per `.claude/rules/error-messages.md`).
- **License headers:** `HTTPOutputStream`, `HTTPResponse`, `ChunkedOutputStream` come from FusionAuth's java-http — **keep** their existing `Copyright (c) <years>, FusionAuth, All Rights Reserved` + Apache-2.0 headers and `@author` lines; do NOT convert to MIT. New files (`HTTPOutputProtocol`, `HTTP1OutputProtocol`) and additions inside `HTTP2Connection.java` (already MIT) use the MIT `The Latte Project` header:
  ```java
  /*
   * Copyright (c) 2026 The Latte Project
   * SPDX-License-Identifier: MIT
   */
  ```
- **Never commit to `main`.** Work stays on branch `http2/compression`. Conventional Commit subject lines (`feat:`, `refactor:`, `test:`); the `commit-msg` hook validates.
- **No HTTP/1 wire-output change.** The existing test suite is the regression gate for the refactor.

## Baseline (run before starting)

- [ ] Confirm the suite is green before any change:

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: BUILD SUCCESS, all tests pass. If anything is red here, stop and investigate — do not start on a red baseline.

## File Structure

**New files:**
- `src/main/java/org/lattejava/http/server/internal/HTTPOutputProtocol.java` — the seam interface (MIT). One responsibility: the contract `HTTPOutputStream` delegates header emission, trailer emission, flush, and commit-state to.
- `src/main/java/org/lattejava/http/server/internal/h1/HTTP1OutputProtocol.java` — HTTP/1 strategy (MIT). Owns the moved `ServerToSocketOutputStream`, the text-preamble + Content-Length/Transfer-Encoding/chunked framing decision, and chunk-terminator/trailer emission.

**Modified files:**
- `src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java` — rewritten to be protocol-agnostic (keep FusionAuth/Apache header).
- `src/main/java/org/lattejava/http/io/ChunkedOutputStream.java` — pure data-chunk framer (keep FusionAuth/Apache header).
- `src/main/java/org/lattejava/http/server/HTTPResponse.java` — delete `rawOutputStream` (keep FusionAuth/Apache header).
- `src/main/java/org/lattejava/http/server/internal/h1/HTTP1Connection.java` — wire `HTTP1OutputProtocol`.
- `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java` — replace `LazyHeaderOutputStream` with a nested `HTTP2OutputProtocol`; wire unified `HTTPOutputStream` (MIT file).

**Modified tests:**
- `src/test/java/org/lattejava/http/tests/io/ChunkedOutputStreamTrailersTest.java` — retarget trailer assertions (trailers no longer emitted by `ChunkedOutputStream`).

**New tests:**
- `src/test/java/org/lattejava/http/tests/server/HTTP2CompressionTest.java` — HTTP/2 gzip/deflate parity.

---

## Task 1: Unify the HTTP/1 output path behind `HTTPOutputProtocol`

This is one coherent refactor: the seam, both moved concerns, the `ChunkedOutputStream` change, and the wiring land together because they are mutually dependent at compile time. The HTTP/1 wire output must not change; the existing suite is the gate. There is no new behavior in this task, so the verification is "the suite stays green" rather than a new failing test.

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTPOutputProtocol.java`
- Create: `src/main/java/org/lattejava/http/server/internal/h1/HTTP1OutputProtocol.java`
- Modify: `src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java` (full rewrite)
- Modify: `src/main/java/org/lattejava/http/io/ChunkedOutputStream.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h1/HTTP1Connection.java:113-117`
- Modify test: `src/test/java/org/lattejava/http/tests/io/ChunkedOutputStreamTrailersTest.java`

**Interfaces:**
- Produces: `HTTPOutputProtocol` with `OutputStream commitHeaders(boolean closing, boolean suppressBody)`, `void prepareTrailers()`, `void commitTrailers()`, `void forceFlush()`, `boolean wroteToClient()`, `default void reset()`.
- Produces: `HTTP1OutputProtocol(HTTPRequest request, HTTPResponse response, OutputStream socketOut, HTTPBuffers buffers, Instrumenter instrumenter, Runnable writeObserver)`.
- Produces: `HTTPOutputStream(HTTPServerConfiguration configuration, HTTPRequest request, HTTPResponse response, HTTPOutputProtocol protocol)`.
- Consumes (from existing code): `HTTPTools.writeResponsePreamble`, `HTTPBuffers.responseBuffer()/chunkBuffer()/chuckedOutputStream()`, `Instrumenter.wroteToClient(long)/chunkedResponse()`, `HTTPValues.ControlBytes.{ChunkedTerminator,EmptyChunk,ColonSpace,CRLF}`.

- [ ] **Step 1: Create the seam interface**

Create `src/main/java/org/lattejava/http/server/internal/HTTPOutputProtocol.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * Protocol-specific seam for {@link org.lattejava.http.server.io.HTTPOutputStream}. The shared output stream owns the
 * commit lifecycle, body suppression, and compression; it delegates header emission and body framing to a per-response
 * implementation of this interface — {@code HTTP1OutputProtocol} for HTTP/1.1 and {@code HTTP2OutputProtocol} for
 * HTTP/2.
 *
 * @author Brian Pontarelli
 */
public interface HTTPOutputProtocol {
  /**
   * Lazily emits the response headers and returns the framing sink for the body. Called once, from
   * {@code HTTPOutputStream.commit()}, after the shared code has applied the compression decision (Content-Encoding /
   * Vary set and Content-Length removed when compressing).
   *
   * @param closing     true when invoked from {@code close()} before any body byte was written.
   * @param suppressBody true for a HEAD request (framing headers mirror a GET, but no body bytes follow).
   * @return the body sink that the (possibly gzip-wrapped) body bytes are written to. Never null.
   */
  OutputStream commitHeaders(boolean closing, boolean suppressBody) throws IOException;

  /**
   * Emits any trailers after the body sink has been closed. Symmetric across protocols — each writes its own trailer
   * bytes here (HTTP/1.1: the chunk terminator plus trailer fields; HTTP/2: a HEADERS + END_STREAM frame).
   */
  void commitTrailers() throws IOException;

  /**
   * Forces buffered bytes out to the client (HTTP/1.1: flushes the socket buffer; HTTP/2: no-op — frames are already
   * enqueued to the writer).
   */
  void forceFlush() throws IOException;

  /**
   * Signals that trailers will follow, before the body sink is closed. HTTP/2 only — the END_STREAM flag rides on the
   * final DATA frame, so the decision must precede the body close. HTTP/1.1 defers everything to
   * {@link #commitTrailers()} and implements this as a no-op.
   */
  void prepareTrailers() throws IOException;

  /**
   * @return true once any byte (or, for HTTP/2, the response HEADERS frame) has been sent to the client, after which
   *     the response can no longer be reset.
   */
  boolean wroteToClient();

  /**
   * Resets pre-commit framing state for HTTP/1.1 keep-alive reuse (HTTP/2 reuses nothing and implements this as a
   * no-op).
   */
  default void reset() {
  }
}
```

- [ ] **Step 2: Make `ChunkedOutputStream` a pure data-chunk framer**

In `src/main/java/org/lattejava/http/io/ChunkedOutputStream.java`: remove the `trailers` field and the `setTrailers` method, and replace `close()` so it flushes the final data chunk only — no terminator, and it does NOT close the delegate (its owner writes the terminator and flushes). Keep the FusionAuth/Apache header. The class becomes:

```java
public class ChunkedOutputStream extends OutputStream {
  private final byte[] buffer;
  private final FastByteArrayOutputStream chunkOutputStream;
  private final OutputStream delegate;
  private int bufferIndex;
  private boolean closed;

  public ChunkedOutputStream(OutputStream delegate, byte[] buffer, FastByteArrayOutputStream chuckOutputStream) {
    this.delegate = delegate;
    this.buffer = buffer;
    this.chunkOutputStream = chuckOutputStream;
  }

  /**
   * Flushes the final buffered data chunk. Does NOT write the last-chunk marker, trailers, or the terminating CRLF, and
   * does NOT close the delegate — the owning {@code HTTP1OutputProtocol.commitTrailers()} writes the chunk terminator
   * (and any trailers) and flushes the socket sink.
   */
  @Override
  public void close() throws IOException {
    if (!closed) {
      flush();
    }
    closed = true;
  }

  @Override
  public void flush() throws IOException {
    if (closed) {
      return;
    }

    if (bufferIndex > 0) {
      chunkOutputStream.write(Integer.toHexString(bufferIndex).getBytes(StandardCharsets.US_ASCII));
      chunkOutputStream.write(HTTPValues.ControlBytes.CRLF);
      chunkOutputStream.write(buffer, 0, bufferIndex);
      chunkOutputStream.write(HTTPValues.ControlBytes.CRLF);
      delegate.write(chunkOutputStream.bytes(), 0, chunkOutputStream.size());
      chunkOutputStream.reset();
      bufferIndex = 0;
    }

    delegate.flush();
  }

  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  @Override
  public void write(byte[] b, int offset, int length) throws IOException {
    int index = offset;
    while (index < length) {
      int wrote = Math.min(buffer.length - bufferIndex, length - index);
      System.arraycopy(b, index, buffer, bufferIndex, wrote);
      bufferIndex += wrote;
      index += wrote;

      if (bufferIndex >= buffer.length) {
        flush();
      }
    }
  }

  @Override
  public void write(int b) throws IOException {
    if (bufferIndex < buffer.length) {
      buffer[bufferIndex++] = (byte) b;
    }
  }
}
```

- [ ] **Step 3: Create `HTTP1OutputProtocol`**

Create `src/main/java/org/lattejava/http/server/internal/h1/HTTP1OutputProtocol.java`. This holds the moved `ServerToSocketOutputStream` (verbatim from `HTTPOutputStream`, but it now sets the outer `wroteToClient` field), the HTTP/1 framing decision (moved from the old `commit()`), and the chunk terminator + trailers (moved from the old `ChunkedOutputStream.close()`).

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h1;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.io.ChunkedOutputStream;
import org.lattejava.http.server.internal.*;

/**
 * HTTP/1.1 implementation of {@link HTTPOutputProtocol}. Writes the text response preamble, decides Content-Length vs.
 * chunked Transfer-Encoding framing, buffers body bytes to the socket, and emits the chunk terminator and any trailers.
 *
 * @author Brian Pontarelli
 */
public class HTTP1OutputProtocol implements HTTPOutputProtocol {
  private final HTTPBuffers buffers;
  private final Instrumenter instrumenter;
  private final HTTPRequest request;
  private final HTTPResponse response;
  private final ServerToSocketOutputStream serverToSocket;
  private ChunkedOutputStream chunkedSink;
  private boolean wroteToClient;

  public HTTP1OutputProtocol(HTTPRequest request, HTTPResponse response, OutputStream socketOut, HTTPBuffers buffers,
                             Instrumenter instrumenter, Runnable writeObserver) {
    this.request = request;
    this.response = response;
    this.buffers = buffers;
    this.instrumenter = instrumenter;
    this.serverToSocket = new ServerToSocketOutputStream(socketOut, buffers, writeObserver);
  }

  @Override
  public OutputStream commitHeaders(boolean closing, boolean suppressBody) throws IOException {
    int status = response.getStatus();
    boolean noBodyStatus = status == 204 || status == 304;
    // framingBody: compute Content-Length/Transfer-Encoding as if a body follows. True for a writing GET AND for HEAD
    // (which must mirror GET headers). False for 204/304 and a body-less close().
    boolean framingBody = !noBodyStatus && !closing;
    // emitBytes: real body bytes will be written, so a real chunked sink is installed. HEAD computes framing but emits
    // no bytes, so it must NOT install a ChunkedOutputStream (else commitTrailers would write a stray terminator).
    boolean emitBytes = framingBody && !suppressBody;
    boolean chunked = false;

    if (noBodyStatus) {
      // 204/304 must not carry Content-Length, Transfer-Encoding, or a body (RFC 9110 §15.3.5 / §15.4.5).
      response.removeHeader(HTTPValues.Headers.ContentLength);
      response.removeHeader(HTTPValues.Headers.TransferEncoding);
    } else {
      // RFC 7230 §3.3.3: a sender must not send Content-Length with Transfer-Encoding. TE wins.
      boolean handlerSetTransferEncoding = response.getHeader(HTTPValues.Headers.TransferEncoding) != null;
      if (handlerSetTransferEncoding) {
        response.removeHeader(HTTPValues.Headers.ContentLength);
      }

      if (!framingBody) {
        // close() with no bytes written.
        if (suppressBody) {
          // HEAD: preserve handler framing; only default to Content-Length: 0 when nothing was set.
          if (response.getContentLength() == null && !handlerSetTransferEncoding) {
            response.setContentLength(0L);
          }
        } else {
          // GET that produced no bytes: strip any declared Transfer-Encoding and force Content-Length: 0.
          if (handlerSetTransferEncoding) {
            response.removeHeader(HTTPValues.Headers.TransferEncoding);
          }
          response.setContentLength(0L);
        }
      } else {
        // Body framing (compression already applied by HTTPOutputStream: Content-Encoding/Vary set, Content-Length
        // removed when compressing).
        if (response.hasTrailers()) {
          if (response.getContentLength() != null) {
            response.removeHeader(HTTPValues.Headers.ContentLength);
          }
          response.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
          chunked = true;
          // RFC 9110 §6.5: announce trailer names, gated on the client accepting trailers (TE: trailers).
          if (request != null && request.acceptsTrailers()) {
            response.setHeader(HTTPValues.Headers.Trailer, String.join(", ", response.getTrailers().keySet()));
          }
        } else if (handlerSetTransferEncoding) {
          chunked = true;
        } else if (response.getContentLength() == null) {
          response.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
          chunked = true;
        }
      }
    }

    HTTPTools.writeResponsePreamble(response, serverToSocket);

    if (!emitBytes) {
      // HEAD / 204 / 304 / body-less close: framing headers are written; no body sink is used.
      return serverToSocket;
    }

    if (chunked) {
      chunkedSink = new ChunkedOutputStream(serverToSocket, buffers.chunkBuffer(), buffers.chuckedOutputStream());
      if (instrumenter != null) {
        instrumenter.chunkedResponse();
      }
      return chunkedSink;
    }

    return serverToSocket;
  }

  @Override
  public void commitTrailers() throws IOException {
    // Only chunked responses carry a chunk terminator. HEAD (no chunkedSink) and Content-Length responses skip this.
    if (chunkedSink == null) {
      return;
    }

    Map<String, List<String>> trailers = response.getTrailers();
    if (trailers.isEmpty()) {
      serverToSocket.write(HTTPValues.ControlBytes.ChunkedTerminator);
    } else {
      serverToSocket.write(HTTPValues.ControlBytes.EmptyChunk);
      for (var entry : trailers.entrySet()) {
        for (String value : entry.getValue()) {
          serverToSocket.write(entry.getKey().getBytes(StandardCharsets.US_ASCII));
          serverToSocket.write(HTTPValues.ControlBytes.ColonSpace);
          serverToSocket.write(value.getBytes(StandardCharsets.US_ASCII));
          serverToSocket.write(HTTPValues.ControlBytes.CRLF);
        }
      }
      serverToSocket.write(HTTPValues.ControlBytes.CRLF);
    }
  }

  @Override
  public void forceFlush() throws IOException {
    serverToSocket.forceFlush();
  }

  @Override
  public void prepareTrailers() {
    // HTTP/1.1 appends trailers after the last-chunk marker, so there is nothing to do before the body close.
  }

  @Override
  public void reset() {
    serverToSocket.reset();
    chunkedSink = null;
  }

  @Override
  public boolean wroteToClient() {
    return wroteToClient;
  }

  /**
   * Buffers body and preamble bytes and writes them to the socket. Sets {@link #wroteToClient} the first time bytes
   * actually reach the socket, which gates response reset. Moved verbatim from the former inner class of
   * {@code HTTPOutputStream}.
   */
  private class ServerToSocketOutputStream extends OutputStream {
    private final byte[] buffer;
    private final OutputStream delegate;
    private final byte[] intsAreDumb = new byte[1];
    private final Runnable writeObserver;
    private int bufferIndex;

    public ServerToSocketOutputStream(OutputStream delegate, HTTPBuffers buffers, Runnable writeObserver) {
      this.delegate = delegate;
      this.buffer = buffers.responseBuffer();
      this.bufferIndex = 0;
      this.writeObserver = writeObserver;
    }

    @Override
    public void close() throws IOException {
      forceFlush();
    }

    @Override
    public void flush() throws IOException {
      if (buffer == null || bufferIndex >= (buffer.length * 0.90)) {
        forceFlush();
      }
    }

    public void forceFlush() throws IOException {
      if (buffer == null || bufferIndex == 0) {
        return;
      }

      wroteToClient = true;
      delegate.write(buffer, 0, bufferIndex);
      delegate.flush();
      bufferIndex = 0;
    }

    public void reset() {
      bufferIndex = 0;
    }

    @Override
    public void write(byte[] b, int offset, int length) throws IOException {
      writeObserver.run();

      if (buffer == null) {
        delegate.write(b, offset, length);
      } else {
        do {
          int remaining = buffer.length - bufferIndex;
          int toWrite = Math.min(remaining, length);
          System.arraycopy(b, offset, buffer, bufferIndex, toWrite);
          bufferIndex += toWrite;
          offset += toWrite;
          length -= toWrite;

          if (bufferIndex >= buffer.length) {
            forceFlush();
          }
        } while (length > 0);
      }
    }

    @Override
    public void write(int b) throws IOException {
      intsAreDumb[0] = (byte) b;
      write(intsAreDumb, 0, 1);
    }

    @Override
    public void write(byte[] b) throws IOException {
      write(b, 0, b.length);
    }
  }
}
```

- [ ] **Step 4: Rewrite `HTTPOutputStream`**

Replace the body of `src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java` (keep the FusionAuth/Apache header and `@author`). The class now owns lifecycle + compression and delegates the rest:

```java
public class HTTPOutputStream extends OutputStream {
  private final List<String> acceptEncodings;
  private final Instrumenter instrumenter;
  private final HTTPOutputProtocol protocol;
  private final HTTPResponse response;
  private boolean bodySuppressed;
  private boolean closed;
  private boolean committed;
  private boolean compress;
  private OutputStream delegate;
  private boolean suppressBody;

  public HTTPOutputStream(HTTPServerConfiguration configuration, HTTPRequest request, HTTPResponse response, HTTPOutputProtocol protocol) {
    this.acceptEncodings = request.getAcceptEncodings();
    this.compress = configuration.isCompressByDefault();
    this.instrumenter = configuration.getInstrumenter();
    this.response = response;
    this.protocol = protocol;
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;

    commit(true);
    protocol.prepareTrailers();
    if (delegate != null) {
      delegate.close();
    }
    protocol.commitTrailers();
    protocol.forceFlush();
  }

  @Override
  public void flush() throws IOException {
    if (delegate != null) {
      delegate.flush();
    }
  }

  /**
   * Commits the response (writing the preamble/HEADERS if not yet written) and forces all buffered bytes to the client.
   */
  public void forceFlush() throws IOException {
    commit(false);
    if (delegate != null) {
      delegate.flush();
    }
    protocol.forceFlush();
  }

  public boolean isCommitted() {
    return protocol.wroteToClient();
  }

  public boolean isCompress() {
    return compress;
  }

  public void setCompress(boolean compress) {
    if (committed) {
      throw new IllegalStateException("The HTTPResponse compression configuration cannot be modified once bytes have been written to it.");
    }

    this.compress = compress;
  }

  public void reset() {
    if (protocol.wroteToClient()) {
      throw new IllegalStateException("The HTTPOutputStream can't be reset after it has been committed, meaning at least one byte was written back to the client.");
    }

    bodySuppressed = false;
    closed = false;
    committed = false;
    compress = false;
    delegate = null;
    protocol.reset();
    // suppressBody is intentionally preserved across reset() so HEAD error responses keep suppressing the body.
  }

  public void setSuppressBody(boolean suppressBody) {
    if (committed) {
      throw new IllegalStateException("The HTTPResponse body suppression cannot be modified once bytes have been written to it.");
    }

    this.suppressBody = suppressBody;
  }

  public boolean willCompress() {
    if (compress) {
      for (String encoding : acceptEncodings) {
        if (encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Gzip) || encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Deflate)) {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public void write(byte[] buffer, int offset, int length) throws IOException {
    commit(false);

    if (bodySuppressed) {
      return;
    }

    delegate.write(buffer, offset, length);

    if (instrumenter != null) {
      instrumenter.wroteToClient(length);
    }
  }

  @Override
  public void write(int b) throws IOException {
    commit(false);

    if (bodySuppressed) {
      return;
    }

    delegate.write(b);

    if (instrumenter != null) {
      instrumenter.wroteToClient(1);
    }
  }

  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  /**
   * Latently builds the body pipeline on the first write or on close. Applies RFC body-suppression, decides
   * compression (the only protocol-agnostic body transform), then hands header emission + framing to the protocol and
   * wraps the returned sink with gzip/deflate when compressing.
   */
  private void commit(boolean closing) throws IOException {
    if (committed) {
      return;
    }

    committed = true;

    int status = response.getStatus();
    boolean noBodyStatus = status == 204 || status == 304;
    bodySuppressed = suppressBody || noBodyStatus;

    // framingBody: headers are computed as if a body follows (true for a writing GET and for HEAD mirroring GET).
    boolean framingBody = !noBodyStatus && !closing;
    // emitBytes: real body bytes will be written, so a compression delegate is installed.
    boolean emitBytes = framingBody && !suppressBody;

    boolean gzip = false;
    boolean deflate = false;
    if (framingBody && compress) {
      for (String encoding : acceptEncodings) {
        if (encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Gzip)) {
          response.setHeader(HTTPValues.Headers.ContentEncoding, HTTPValues.ContentEncodings.Gzip);
          response.setHeader(HTTPValues.Headers.Vary, HTTPValues.Headers.AcceptEncoding);
          response.removeHeader(HTTPValues.Headers.ContentLength);
          gzip = true;
          break;
        } else if (encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Deflate)) {
          response.setHeader(HTTPValues.Headers.ContentEncoding, HTTPValues.ContentEncodings.Deflate);
          response.setHeader(HTTPValues.Headers.Vary, HTTPValues.Headers.AcceptEncoding);
          response.removeHeader(HTTPValues.Headers.ContentLength);
          deflate = true;
          break;
        }
      }
    }

    OutputStream sink = protocol.commitHeaders(closing, suppressBody);

    if (!emitBytes) {
      delegate = sink;
      return;
    }

    if (gzip) {
      try {
        delegate = new GZIPOutputStream(sink, true);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else if (deflate) {
      delegate = new DeflaterOutputStream(sink, true);
    } else {
      delegate = sink;
    }
  }
}
```

Update the imports: the rewritten class needs `import module java.base;`, `import module org.lattejava.http;`, and `import org.lattejava.http.server.internal.*;` (for `HTTPOutputProtocol` / `Instrumenter`). Remove any now-unused imports (e.g. `ChunkedOutputStream`, `HTTPBuffers` if no longer referenced).

- [ ] **Step 5: Wire `HTTP1Connection`**

In `src/main/java/org/lattejava/http/server/internal/h1/HTTP1Connection.java`, replace lines 113-117 (the output-stream construction):

```java
        // Set up the output stream so that if we fail we have the opportunity to write a response that contains a status code.
        var throughputOutputStream = new ThroughputOutputStream(socket.getOutputStream(), throughput);
        response = new HTTPResponse();

        var protocol = new HTTP1OutputProtocol(request, response, throughputOutputStream, buffers, instrumenter, () -> state = HTTPConnection.State.Write);
        HTTPOutputStream outputStream = new HTTPOutputStream(configuration, request, response, protocol);
        response.setOutputStream(outputStream);
```

- [ ] **Step 6: Retarget `ChunkedOutputStreamTrailersTest`**

`ChunkedOutputStream` no longer emits trailers, so any assertion in `src/test/java/org/lattejava/http/tests/io/ChunkedOutputStreamTrailersTest.java` that calls `setTrailers(...)` or expects a `0\r\n<trailers>\r\n` terminator from `ChunkedOutputStream.close()` no longer compiles/holds. Read the file, then: keep/adjust the pure-framing assertions (data chunks, the no-op `close()` that writes only the final data chunk), and move the trailer-on-the-wire assertions to an HTTP/1 server-level trailers test if one exists (e.g. an existing trailers integration test) — otherwise delete the now-invalid trailer assertions here and rely on the existing HTTP/1 trailers integration coverage. Do not assert `ChunkedOutputStream` writes any terminator.

- [ ] **Step 7: Build**

Run: `latte clean build`
Expected: BUILD SUCCESS (compiles).

- [ ] **Step 8: Run the HTTP/1 regression gate**

Run: `latte test --test=CompressionTest` then `latte test --test=ChunkedOutputStreamTrailersTest`
Expected: PASS. Then run the full suite: `latte clean int --excludePerformance --excludeTimeouts`
Expected: all green — identical HTTP/1 wire behavior. If a trailers/HEAD/204/304 test fails, diff the failing wire output against `main` for that case and reconcile the framing branch in `HTTP1OutputProtocol.commitHeaders` / `commitTrailers`.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTPOutputProtocol.java \
        src/main/java/org/lattejava/http/server/internal/h1/HTTP1OutputProtocol.java \
        src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java \
        src/main/java/org/lattejava/http/io/ChunkedOutputStream.java \
        src/main/java/org/lattejava/http/server/internal/h1/HTTP1Connection.java \
        src/test/java/org/lattejava/http/tests/io/ChunkedOutputStreamTrailersTest.java
git commit -m "refactor: Extract HTTPOutputProtocol seam; unify HTTP/1 output path"
```

---

## Task 2: Route HTTP/2 through the unified pipeline (enables compression)

Replace the HTTP/2 `LazyHeaderOutputStream` with an `HTTP2OutputProtocol` and wire `HTTP2Connection` to build a unified `HTTPOutputStream`. Because `HTTPOutputStream` already does compression, HTTP/2 gains gzip/deflate the moment it routes through it. Delete `HTTPResponse.rawOutputStream`. The existing `HTTP2*` tests are the gate (the JDK client sends no `Accept-Encoding`, so they exercise the no-compression path unchanged); compression itself is proven in Task 3.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java` (replace `LazyHeaderOutputStream` with nested `HTTP2OutputProtocol`; update `spawnHandlerThread` + the `HTTPProcessingException` path)
- Modify: `src/main/java/org/lattejava/http/server/HTTPResponse.java` (delete `rawOutputStream`)

**Interfaces:**
- Consumes: `HTTPOutputProtocol` (Task 1), `HTTPOutputStream(configuration, request, response, protocol)` (Task 1).
- Consumes (existing in `HTTP2Connection`): `enqueueForWriter(HTTP2Frame)`, `writerQueue`, `connectionSendWindow`, `peerSettings`, `localSettings`, `logger`; `HPACKEncoder`, `HTTP2Stream`, `HTTP2OutputStream`, `HTTP2Frame.HeadersFrame`, `HTTP2Frame.FLAG_END_HEADERS`/`FLAG_END_STREAM`, `HTTPValues.Headers.ConnectionSpecificHeaders`.
- Produces: nested `HTTP2OutputProtocol implements HTTPOutputProtocol`.

- [ ] **Step 1: Replace `LazyHeaderOutputStream` with `HTTP2OutputProtocol`**

In `HTTP2Connection.java`, delete the `LazyHeaderOutputStream` inner class (lines ~1073-1192) and add this nested class in its place (it is an inner class so it keeps access to `enqueueForWriter`, `writerQueue`, `connectionSendWindow`, `peerSettings`, and `logger`, exactly as `LazyHeaderOutputStream` did). It implements `HTTPOutputProtocol`:

```java
  /**
   * HTTP/2 implementation of {@link HTTPOutputProtocol}. On the first write or flush the response status and headers
   * are HPACK-encoded and enqueued as a HEADERS frame (lazy emission preserves bidi-streaming); body bytes flow into an
   * {@link HTTP2OutputStream} that fragments DATA frames under flow control. Trailers ride a trailing HEADERS frame.
   *
   * <p>RFC 9113 §8.1 — HEADERS must precede DATA. This class enforces that invariant.
   */
  private class HTTP2OutputProtocol implements HTTPOutputProtocol {
    private final HPACKEncoder encoder;
    private final HTTPResponse response;
    private final HTTP2Stream stream;
    private HTTP2OutputStream sink;
    private boolean streamReset;
    private boolean wroteToClient;

    HTTP2OutputProtocol(HTTPResponse response, HTTP2Stream stream, HPACKEncoder encoder) {
      this.response = response;
      this.stream = stream;
      this.encoder = encoder;
    }

    @Override
    public OutputStream commitHeaders(boolean closing, boolean suppressBody) {
      // Build response HEADERS from the response state at first write/flush.
      List<HPACKDynamicTable.HeaderField> respFields = new ArrayList<>();
      respFields.add(new HPACKDynamicTable.HeaderField(":status", String.valueOf(response.getStatus())));
      for (var entry : response.getHeadersMap().entrySet()) {
        String lowerKey = entry.getKey().toLowerCase(Locale.ROOT);
        if (HTTPValues.Headers.ConnectionSpecificHeaders.contains(lowerKey)) {
          logger.debug("Stripping h1.1-only response header [{}] on h2 emission", entry.getKey());
          continue;
        }
        for (String v : entry.getValue()) {
          respFields.add(new HPACKDynamicTable.HeaderField(lowerKey, v));
        }
      }

      // HPACKEncoder mutates a shared dynamic table and is not thread-safe across handler threads.
      byte[] headerBlock;
      synchronized (encoder) {
        headerBlock = encoder.encode(respFields);
      }

      // RFC 9113 §5.1 — take the stream monitor across the state check, transition, AND enqueue so a concurrent
      // RECV_RST_STREAM cannot interleave.
      synchronized (stream) {
        if (stream.state() == HTTP2Stream.State.CLOSED) {
          streamReset = true;
          return OutputStream.nullOutputStream();
        }
        try {
          stream.applyEvent(HTTP2Stream.Event.SEND_HEADERS_NO_END_STREAM);
        } catch (IllegalStateException ignored) {
          streamReset = true;
          return OutputStream.nullOutputStream();
        }
        if (!enqueueForWriter(new HTTP2Frame.HeadersFrame(stream.streamId(), HTTP2Frame.FLAG_END_HEADERS, headerBlock))) {
          streamReset = true;
          return OutputStream.nullOutputStream();
        }
      }

      wroteToClient = true;
      sink = new HTTP2OutputStream(stream, writerQueue, connectionSendWindow, peerSettings.maxFrameSize());
      return sink;
    }

    @Override
    public void commitTrailers() {
      if (streamReset || !response.hasTrailers()) {
        return;
      }

      List<HPACKDynamicTable.HeaderField> trailerFields = new ArrayList<>();
      for (var entry : response.getTrailers().entrySet()) {
        for (String v : entry.getValue()) {
          trailerFields.add(new HPACKDynamicTable.HeaderField(entry.getKey(), v));
        }
      }
      byte[] trailerBlock;
      synchronized (encoder) {
        trailerBlock = encoder.encode(trailerFields);
      }
      enqueueForWriter(new HTTP2Frame.HeadersFrame(stream.streamId(),
          HTTP2Frame.FLAG_END_HEADERS | HTTP2Frame.FLAG_END_STREAM, trailerBlock));
    }

    @Override
    public void forceFlush() {
      // No-op: HTTP/2 frames are enqueued to the writer queue as they are produced; there is no extra buffer to push.
    }

    @Override
    public void prepareTrailers() {
      // The END_STREAM flag rides the final DATA frame, so suppress it now if trailers will follow.
      if (!streamReset && sink != null && response.hasTrailers()) {
        sink.setTrailersFollow(true);
      }
    }

    @Override
    public boolean wroteToClient() {
      return wroteToClient;
    }
  }
```

- [ ] **Step 2: Wire `spawnHandlerThread` to the unified stream**

In `HTTP2Connection.spawnHandlerThread`, replace the `LazyHeaderOutputStream` usage. The current block:

```java
        var lazyOut = new LazyHeaderOutputStream(response, stream, encoder);
        response.setRawOutputStream(lazyOut);

        configuration.getHandler().handle(request, response);

        // Ensure the output is closed even if the handler did not call out.close() explicitly.
        lazyOut.close();
```

becomes:

```java
        var protocol = new HTTP2OutputProtocol(response, stream, encoder);
        response.setOutputStream(new HTTPOutputStream(configuration, request, response, protocol));

        configuration.getHandler().handle(request, response);

        // Ensure the output is closed even if the handler did not call out.close() explicitly.
        response.getOutputStream().close();
```

And in the `HTTPProcessingException` catch block, the current error-response emission:

```java
          response.setStatus(e.getStatus());
          var lazyOut = new LazyHeaderOutputStream(response, stream, encoder);
          response.setRawOutputStream(lazyOut);
          lazyOut.close();
```

becomes:

```java
          response.setStatus(e.getStatus());
          var errorProtocol = new HTTP2OutputProtocol(response, stream, encoder);
          response.setOutputStream(new HTTPOutputStream(configuration, request, response, errorProtocol));
          response.getOutputStream().close();
```

Add the imports the file now needs at the top of `HTTP2Connection.java`: `import org.lattejava.http.server.io.HTTPOutputStream;` (and confirm `org.lattejava.http.server.internal.*;` already imported for `HTTPOutputProtocol`). Leave the existing `stream.applyEvent(HTTP2Stream.Event.SEND_DATA_END_STREAM)` and stream/pipe cleanup after the close exactly as they are.

- [ ] **Step 3: Delete `rawOutputStream` from `HTTPResponse`**

In `src/main/java/org/lattejava/http/server/HTTPResponse.java` (keep the FusionAuth/Apache header):
- Remove the field `private OutputStream rawOutputStream;` and the method `setRawOutputStream(OutputStream)`.
- `getOutputStream()` → `return outputStream;`
- `close()` → `if (writer != null) { writer.close(); } else { outputStream.close(); }`
- `flush()` → `outputStream.forceFlush();` (drop the `rawOutputStream != null` branch).
- `isCommitted()` → `return outputStream.isCommitted();`
- `isCompress()` → `return outputStream.isCompress();`
- `willCompress()` → `return outputStream.willCompress();`
- `setCompress(boolean)` → `outputStream.setCompress(compress);`
- `reset()` → drop the `rawOutputStream == null` guards: `if (outputStream.isCommitted()) { throw ... }` then clear cookies/headers/exception, `outputStream.reset();`, restore status/statusMessage/writer.
- Update the Javadoc on `isCompress()`, `willCompress()`, `isCommitted()` that says "Always false on the HTTP/2 path" — that is no longer true; describe the single behavior.

- [ ] **Step 4: Build**

Run: `latte clean build`
Expected: BUILD SUCCESS. If `LazyHeaderOutputStream` or `setRawOutputStream` is referenced anywhere else, grep and fix:
Run: `grep -rn "LazyHeaderOutputStream\|setRawOutputStream\|rawOutputStream" src/`
Expected: no remaining references in `src/main`.

- [ ] **Step 5: Run the HTTP/2 regression gate**

Run: `latte test --test=HTTP2BasicTest`
Expected: PASS. Then the broader h2 set:
Run: `latte test --test=HTTP2FlowControlTest` and `latte test --test=HTTP2OutputStreamFragmentationTest` and `latte test --test=H2SpecHarnessTest`
Expected: PASS. Then the full suite:
Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java \
        src/main/java/org/lattejava/http/server/HTTPResponse.java
git commit -m "feat: Route HTTP/2 responses through unified HTTPOutputStream"
```

---

## Task 3: HTTP/2 compression tests (new behavior, TDD)

Prove HTTP/2 now compresses with gzip/deflate at parity with HTTP/1, honoring `Accept-Encoding` and `compressByDefault`, and that `setCompress(false)` disables it. The JDK `HttpClient` does not auto-decompress, so the test sends `Accept-Encoding` explicitly and gunzips/inflates the body itself.

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/HTTP2CompressionTest.java`

**Interfaces:**
- Consumes (from `BaseTest`): `makeServer(scheme, handler, listener)`, cert fields `certificate`, `intermediateCertificate`, `rootCertificate`, `keyPair`, and `ungzip(byte[])` / `inflate(byte[])`; `SecurityTools.clientContext(rootCertificate)`.

- [ ] **Step 1: Write the failing gzip test**

Create `src/test/java/org/lattejava/http/tests/server/HTTP2CompressionTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Verifies HTTP/2 response compression parity with HTTP/1 (gzip + deflate), driven over TLS-ALPN with the JDK client.
 *
 * @author Brian Pontarelli
 */
public class HTTP2CompressionTest extends BaseTest {
  private HttpClient h2Client() {
    var sslContext = SecurityTools.clientContext(rootCertificate);
    return HttpClient.newBuilder().sslContext(sslContext).version(HttpClient.Version.HTTP_2).build();
  }

  private HTTPServer startServer(HTTPHandler handler) {
    var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());
    return makeServer("https", handler, listener).start();
  }

  @Test
  public void gzip_compresses_h2_response() throws Exception {
    String body = "Hello world! ".repeat(500);
    HTTPHandler handler = (req, res) -> {
      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setStatus(200);
      try (OutputStream out = res.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (var server = startServer(handler)) {
      int port = server.getActualPort();
      var resp = h2Client().send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/"))
                     .header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Gzip)
                     .build(),
          HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2,
          "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
      assertEquals(resp.headers().firstValue("content-encoding").orElse(""), HTTPValues.ContentEncodings.Gzip);
      assertEquals(resp.headers().firstValue("vary").orElse(""), HTTPValues.Headers.AcceptEncoding);
      assertFalse(resp.headers().firstValue("content-length").isPresent(),
          "Content-Length must be removed when compressing");
      assertEquals(new String(ungzip(resp.body()), StandardCharsets.UTF_8), body);
    }
  }
}
```

- [ ] **Step 2: Run it (should already pass once Task 2 landed)**

Run: `latte test --test=HTTP2CompressionTest`
Expected: PASS. Compression is enabled by Task 2 (HTTP/2 routes through `HTTPOutputStream`, which compresses when `compressByDefault` is true and the client accepts gzip). If it FAILS with no `content-encoding`, verify `request.getAcceptEncodings()` is populated for h2 (it is, via `buildRequestFromHeaders` → `addHeader` → `decodeHeader`) and that Task 2 set `response.setOutputStream(...)` rather than leaving a raw stream.

- [ ] **Step 3: Add the deflate, disable, and streaming cases**

Add these methods to `HTTP2CompressionTest`:

```java
  @Test
  public void deflate_compresses_h2_response() throws Exception {
    String body = "deflate me ".repeat(500);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try (OutputStream out = res.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (var server = startServer(handler)) {
      int port = server.getActualPort();
      var resp = h2Client().send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/"))
                     .header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Deflate)
                     .build(),
          HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2);
      assertEquals(resp.headers().firstValue("content-encoding").orElse(""), HTTPValues.ContentEncodings.Deflate);
      assertEquals(new String(inflate(resp.body()), StandardCharsets.UTF_8), body);
    }
  }

  @Test
  public void setCompress_false_disables_h2_compression() throws Exception {
    String body = "uncompressed ".repeat(500);
    HTTPHandler handler = (req, res) -> {
      res.setCompress(false);
      res.setStatus(200);
      try (OutputStream out = res.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (var server = startServer(handler)) {
      int port = server.getActualPort();
      var resp = h2Client().send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/"))
                     .header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Gzip)
                     .build(),
          HttpResponse.BodyHandlers.ofString());

      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2);
      assertFalse(resp.headers().firstValue("content-encoding").isPresent(), "compression disabled — no Content-Encoding");
      assertEquals(resp.body(), body);
    }
  }

  @Test
  public void gzip_compresses_streamed_h2_response() throws Exception {
    // Multiple write+flush calls across more than one DATA frame, all gzip-compressed end to end.
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try {
        OutputStream out = res.getOutputStream();
        for (int i = 0; i < 200; i++) {
          out.write(("chunk-" + i + "\n").getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
        out.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    var expected = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      expected.append("chunk-").append(i).append("\n");
    }

    try (var server = startServer(handler)) {
      int port = server.getActualPort();
      var resp = h2Client().send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/"))
                     .header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Gzip)
                     .build(),
          HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2);
      assertEquals(resp.headers().firstValue("content-encoding").orElse(""), HTTPValues.ContentEncodings.Gzip);
      assertEquals(new String(ungzip(resp.body()), StandardCharsets.UTF_8), expected.toString());
    }
  }
```

- [ ] **Step 4: Run the full new test class**

Run: `latte test --test=HTTP2CompressionTest`
Expected: all four methods PASS.

- [ ] **Step 5: Full regression**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: all green (HTTP/1 + HTTP/2 + new compression tests).

- [ ] **Step 6: Commit**

```bash
git add src/test/java/org/lattejava/http/tests/server/HTTP2CompressionTest.java
git commit -m "test: HTTP/2 gzip/deflate compression parity"
```

---

## Self-Review (completed during planning)

**Spec coverage:**
- Unified protocol-agnostic `HTTPOutputStream` + seam → Task 1.
- Compression owned by `HTTPOutputStream` (gzip/deflate, Content-Encoding/Vary, Content-Length removal) → Task 1 Step 4 `commit()`.
- `HTTP1OutputProtocol` (preamble, framing, ServerToSocket) → Task 1 Step 3.
- `ChunkedOutputStream` → pure data framer; terminator/trailers in `commitTrailers` → Task 1 Steps 2-3, design §6.
- `prepareTrailers` h2-only / `commitTrailers` symmetric → interface (Task 1 Step 1), h2 impl (Task 2 Step 1).
- `HTTP2OutputProtocol` replacing `LazyHeaderOutputStream` → Task 2 Step 1.
- Delete `rawOutputStream`; single-behavior `HTTPResponse` API → Task 2 Step 3.
- HEAD/204/304 suppression preserved → `commit()` + `commitHeaders()` framing branches (Task 1).
- Behavior change (h2 compresses by default) → covered + verified by Task 3 (and design §10 audit note: existing h2 tests send no Accept-Encoding).
- HTTP/2 compression tests (gzip, deflate, disable, streaming) → Task 3.

**Type consistency:** `commitHeaders(boolean closing, boolean suppressBody)`, `prepareTrailers()`, `commitTrailers()`, `forceFlush()`, `wroteToClient()`, `reset()` are used identically in the interface (Task 1 Step 1), `HTTP1OutputProtocol` (Task 1 Step 3), `HTTP2OutputProtocol` (Task 2 Step 1), and the `HTTPOutputStream` call sites (Task 1 Step 4). The `HTTPOutputStream(configuration, request, response, protocol)` constructor signature matches both wiring sites (Task 1 Step 5, Task 2 Step 2).

**Known caveats baked into the plan:**
- HTTP/2 empty-body and 204/304 keep today's HEADERS + empty-DATA(END_STREAM) shape (no HEADERS+END_STREAM optimization) — `HTTP2OutputProtocol.commitHeaders` always emits HEADERS without END_STREAM and the body close emits END_STREAM (design §4.4, §12).
- `HTTP2OutputProtocol` is an inner class of `HTTP2Connection` (not a standalone file), because it needs `enqueueForWriter` / `writerQueue` / `connectionSendWindow` / `peerSettings` access — same rationale as the `LazyHeaderOutputStream` it replaces. This is the one deviation from the design's "separate file" framing; it stays MIT (the file is MIT).

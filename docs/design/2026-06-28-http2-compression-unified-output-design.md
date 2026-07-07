# Unified HTTPOutputStream: HTTP/2 Compression via a Protocol Output Seam

- **Status:** Draft (design)
- **Created:** 2026-06-28
- **Branch:** `http2/compression`
- **Decisions locked:** Full unification (delete `rawOutputStream`); full HTTP/1 compression parity for HTTP/2.

## 1. Goal

Give HTTP/2 responses the same gzip/deflate compression behavior as HTTP/1, by **rewriting `HTTPOutputStream` into a single, protocol-agnostic response body pipeline** shared by both protocols. The HTTP/1.1 text preamble vs. HPACK `HEADERS` frame, and chunked framing vs. `DATA` framing, become two narrow protocol seams; everything else — the commit lifecycle, body suppression, and compression — is owned once, in `HTTPOutputStream`.

### Success criteria

1. A handler that writes a gzip-capable response gets compressed bytes on both HTTP/1 and HTTP/2, with identical `Content-Encoding` / `Vary` headers.
2. `response.setCompress(...)`, `isCompress()`, `willCompress()`, `isCommitted()`, and `reset()` have **one** behavior, independent of protocol. `HTTPResponse.rawOutputStream` is deleted.
3. Every existing HTTP/1 conformance behavior is preserved (HEAD, 204/304, chunked, trailers, expect-continue bypass, error-reset, keep-alive reuse).
4. Every existing HTTP/2 behavior is preserved (lazy `HEADERS`, `DATA` fragmentation + flow control, trailers as `HEADERS`+END_STREAM, connection-specific header stripping, stream-reset races).

## 2. Current state

### HTTP/1 (`HTTP1Connection` → `HTTPOutputStream`)
`HTTPOutputStream` (in `server.io`, from FusionAuth's java-http) owns three concerns interleaved in `commit()`:
- **Preamble:** `HTTPTools.writeResponsePreamble(response, delegate)` (text status line + headers).
- **Framing:** `Content-Length` / `Transfer-Encoding` decisions and `ChunkedOutputStream`; the inner `ServerToSocketOutputStream` buffers and writes to the socket.
- **Compression:** picks gzip/deflate from `Accept-Encoding` + the `compress` flag, sets `Content-Encoding`/`Vary`, removes `Content-Length`, wraps the delegate with `GZIPOutputStream`/`DeflaterOutputStream`.

### HTTP/2 (`HTTP2Connection.spawnHandlerThread` → `LazyHeaderOutputStream` → `HTTP2OutputStream`)
- `response.setRawOutputStream(lazyOut)` — bypasses `HTTPOutputStream` entirely.
- `LazyHeaderOutputStream.ensureHeadersSent()` lazily HPACK-encodes status+headers (stripping connection-specific headers), takes the stream monitor to atomically check state + transition `SEND_HEADERS` + enqueue the `HEADERS` frame, then builds an `HTTP2OutputStream`.
- `HTTP2OutputStream` buffers, fragments against `MAX_FRAME_SIZE`, and blocks on the per-stream + connection send windows while enqueuing `DATA` frames.
- Trailers: `HEADERS` frame with END_STREAM after the final (non-END_STREAM) `DATA`.
- **No compression at all.** `HTTPResponse`'s compress/commit methods no-op whenever `rawOutputStream` is set.

### Key facts that make unification work
- **The "commit on first write" model is already identical.** `HTTPOutputStream.commit()` and `ensureHeadersSent()` both defer header emission to the first body byte (or close).
- **Compression is protocol-agnostic.** `Content-Encoding: gzip` is end-to-end; compressed bytes go into `DATA` frames exactly as they go into the chunked/identity HTTP/1 body. The body chain is `[optional gzip/deflate] → [protocol framing sink]` on both sides — only the bottom differs.
- **`Accept-Encoding` is already available on HTTP/2.** `buildRequestFromHeaders` calls `request.addHeader("accept-encoding", v)`, which routes through `decodeHeader` → `setAcceptEncodings`, so `request.getAcceptEncodings()` is populated for h2.
- **`compressByDefault` defaults to `true`** (`HTTPServerConfiguration`), so after this change h2 compresses by default for gzip-capable clients. See §10 (behavior change).

## 3. Architecture: the protocol output seam

`HTTPOutputStream` keeps ownership of the lifecycle + compression and delegates exactly two things to a per-protocol strategy: **emit the response headers** and **provide the framing body sink**. Trailers and force-flush are folded into the same seam.

```
            handler writes
                  │
                  ▼
        ┌───────────────────────┐
        │   HTTPOutputStream     │  shared: commit lifecycle, HEAD/204/304
        │                        │  suppression, compress decision + headers,
        │                        │  setCompress/willCompress/isCommitted API,
        │                        │  instrumenter.wroteToClient
        └───────────┬────────────┘
                    │ writes pass through optional gzip/deflate, then to:
                    ▼
        ┌───────────────────────┐
        │  HTTPOutputProtocol    │  seam (internal interface)
        │  (strategy)            │
        └───────────────────────┘
           ╱                   ╲
   HTTP1OutputProtocol     HTTP2OutputProtocol
   - writeResponsePreamble  - HPACK HEADERS frame (today's ensureHeadersSent)
   - TE/Content-Length,     - strips connection-specific headers
     ChunkedOutputStream    - HTTP2OutputStream sink (DATA + flow control)
     (pure data framer)     - prepareTrailers: setTrailersFollow (END_STREAM)
   - ServerToSocket→socket  - commitTrailers: HEADERS+END_STREAM
   - commitTrailers writes
     0\r\n+trailers+\r\n     - stream-reset handling
```

### 3.1 The seam interface (internal)

Placed in `org.lattejava.http.server.internal` (not exported) so it can reference both `server.io` types (`ChunkedOutputStream`) and be implemented against `internal.h2` types. `HTTPOutputStream` (exported) referencing an internal type is fine within the module — exports govern only *external* visibility, and only the server constructs `HTTPOutputStream`.

The strategy is constructed per-response and holds its own `HTTPResponse` (plus protocol context), so the seam methods take no `response` parameter.

```java
interface HTTPOutputProtocol {
  /**
   * Lazily emit the response headers and return the framing sink for the body.
   * Called once from HTTPOutputStream.commit(), AFTER the shared code has applied
   * HEAD/204/304 suppression and the compression decision (Content-Encoding/Vary set,
   * Content-Length removed if compressing).
   *
   * The strategy:
   *   - applies its own framing-header rules (h1: Transfer-Encoding/Content-Length/chunked; h2: none),
   *   - emits headers (h1: text preamble; h2: HPACK HEADERS frame),
   *   - returns the OutputStream the (later gzip-wrapped) body bytes go to and retains a reference to it.
   *
   * @param bodyWillFollow false for HEAD/204/304 or a pre-write close() (no body bytes).
   * @return the framing body sink, or a no-op sink when no body will follow.
   */
  OutputStream commitHeaders(boolean bodyWillFollow, boolean closing) throws IOException;

  /**
   * Pre-close hook: signal that trailers will follow, BEFORE the body sink is closed. Evaluated at close time
   * (NOT at commitHeaders time) so trailers the handler added after the first write are honored.
   *   - h1: NO-OP. HTTP/1.1 appends trailers after the last-chunk marker, so the decision defers entirely to
   *         commitTrailers (below).
   *   - h2: if response.hasTrailers(), HTTP2OutputStream.setTrailersFollow(true) so the final DATA frame omits
   *         END_STREAM. HTTP/2 carries END_STREAM as a flag on the LAST DATA frame, so this MUST be decided
   *         before the body sink is closed — it cannot move into commitTrailers. This is the one irreducibly
   *         pre-close step, and it is h2-only.
   * No-op when the body was suppressed.
   */
  void prepareTrailers() throws IOException;

  /**
   * Post-close hook: emit trailer bytes after the body sink is closed. Symmetric across protocols — each
   * protocol writes its own trailer bytes here.
   *   - h1: write the full chunk-stream terminator to the socket sink — 0\r\n + trailer field lines + \r\n,
   *         or just 0\r\n\r\n when there are none. ChunkedOutputStream is now a pure data-chunk framer that does
   *         NOT write this terminator (see §6), so commitTrailers owns the entire end-of-message sequence.
   *   - h2: if response.hasTrailers(), emit the HEADERS+END_STREAM trailers frame.
   * No-op when the body was suppressed.
   */
  void commitTrailers() throws IOException;

  /** Push buffered bytes to the client (h1: flush socket buffer; h2: flush DATA frames). */
  void forceFlush() throws IOException;

  /** Reset for keep-alive reuse before commit (h1: reset socket buffer; h2: no-op). */
  default void reset() {}
}
```

`commitHeaders` returns the sink rather than exposing it as a field so the chunked-vs-identity decision (h1) stays encapsulated with the `Transfer-Encoding` header that announces it. The two trailer methods exist because the protocols put the "trailers follow" signal at different points relative to the body close: h1 can defer everything to `commitTrailers` (post-close) because the `0\r\n` marker is followed by trailers on the wire; h2 must commit the `END_STREAM` placement *before* the final DATA frame, hence the h2-only `prepareTrailers`. The trailer *bytes* are written in `commitTrailers` for **both** protocols, so "how are trailers emitted?" has one answer per protocol, in one place.

**Trailer timing note.** Because `prepareTrailers`/`commitTrailers` evaluate `hasTrailers()` at close time, trailers added any time before `close()` are emitted on both protocols. The one residual asymmetry (pre-existing, unchanged): h1's `Trailer` response header — which lists the trailer field names and is written into the preamble — can only reflect trailers present at first-write time. h2 has no such header.

### 3.2 Shared `HTTPOutputStream` after the rewrite

Constructor drops `delegate`, `buffers`, and `writeObserver` (all now protocol-owned); takes the strategy instead:

```java
public HTTPOutputStream(HTTPServerConfiguration configuration, HTTPRequest request,
                        HTTPResponse response, HTTPOutputProtocol protocol)
```

It keeps: `acceptEncodings` (from `request.getAcceptEncodings()`), `compress` (`config.isCompressByDefault()`), `instrumenter`, `response`, `committed`, `bodySuppressed`, `suppressBody`, `wroteOneByteToClient`, and the `delegate` reference (now the gzip/deflate-wrapped strategy sink). `ServerToSocketOutputStream` **moves into `HTTP1OutputProtocol`**.

Shared `commit(boolean closing)` responsibilities:
1. `committed = true`.
2. RFC 9110 suppression (shared semantics): `noBodyStatus = status == 204 || status == 304`; `bodySuppressed = suppressBody || noBodyStatus`; for 204/304 remove `Content-Length` + `Transfer-Encoding`.
3. **Compression decision** (shared, body-only, not closing): walk `acceptEncodings`; on gzip/deflate set `Content-Encoding` + `Vary: Accept-Encoding`, remove `Content-Length`, record which encoder to install.
4. `OutputStream sink = protocol.commitHeaders(!bodySuppressed && !closing, closing)`.
5. If body follows and compressing: `delegate = new GZIPOutputStream(sink, true)` or `new DeflaterOutputStream(sink, true)`; else `delegate = sink`.

`willCompress()` / `isCompress()` / `setCompress()` / `setSuppressBody()` / `isCommitted()` are unchanged in spirit. `write/flush/close/forceFlush` keep their structure, delegating flush/forceFlush/trailers to the strategy.

## 4. Data flow

### 4.1 Write (first byte)
`write()` → `commit(false)` → (shared suppression + compression) → `protocol.commitHeaders(bodyWillFollow=true, closing=false)`:
- **h1:** apply TE/CL/chunked rules (including setting the `Trailer` header when trailers are present and the client accepts them); if chunked, build `ChunkedOutputStream(serverToSocket)` and call `instrumenter.chunkedResponse()`; `writeResponsePreamble(response, serverToSocket)`; return chunked-or-`serverToSocket`. The strategy retains the `serverToSocket` reference so `commitTrailers()` can write the chunk terminator to it after the body.
- **h2:** build HPACK header list from `response` (skip connection-specific headers), synchronize on the shared encoder to encode, take the stream monitor to atomically check `state != CLOSED` + `SEND_HEADERS_NO_END_STREAM` + enqueue the `HEADERS` frame; if the stream was reset, return a no-op sink and mark suppressed; else return a fresh `HTTP2OutputStream`.

Shared code then wraps the returned sink with gzip/deflate (top of chain) and writes the byte. Result chain: **h1** `gzip → chunked → serverToSocket → socket`; **h2** `gzip → HTTP2OutputStream → writerQueue`.

### 4.2 Flush
`HTTPOutputStream.flush()` → `delegate.flush()` (gzip syncFlush pushes through to the sink). `forceFlush()` → `commit(false)` then `protocol.forceFlush()` (h1 flushes the socket buffer; h2 flushes pending `DATA`).

### 4.3 Close + trailers (ordering is critical)
`HTTPOutputStream.close()`:
1. `commit(true)` — emits headers even if nothing was written.
2. If `!bodySuppressed`: `protocol.prepareTrailers()` — h2 sets `setTrailersFollow(true)` so the final DATA frame omits END_STREAM; h1 no-op.
3. `delegate.close()` — closes gzip (writes the gzip trailer into the sink as ordinary body bytes), which cascades to the sink:
   - **h1:** `ChunkedOutputStream.close()` flushes the final data chunk and stops — it writes **no** terminator and does **not** close the socket sink (it is now a pure data-chunk framer, §6).
   - **h2:** `HTTP2OutputStream.close()` sends the final `DATA` frame, omitting END_STREAM when trailers follow.
4. If `!bodySuppressed`: `protocol.commitTrailers()` — both protocols emit their trailer bytes here:
   - **h1:** write `0\r\n` + trailer lines + `\r\n` (or `0\r\n\r\n` when none) to the socket sink, then flush it.
   - **h2:** emit the `HEADERS`+END_STREAM frame when trailers exist.

The gzip trailer still lands *inside* the body framing (step 3, as data bytes), while the chunk terminator / h2 trailers frame land *after* it (step 4). Evaluating `hasTrailers()` in steps 2 and 4 (close time, not first-write time) preserves today's behavior where late-added trailers still work (§3.1).

### 4.4 Empty body
`close()` with no prior write: `commit(true)` runs `commitHeaders(bodyWillFollow=false, closing=true)`.
- **h1:** preamble written; GET → `Content-Length: 0` (current logic), HEAD → preserve handler framing.
- **h2:** emit `HEADERS` (no END_STREAM), return `HTTP2OutputStream`; shared close → empty `DATA` with END_STREAM. Preserves current h2 behavior. (A future optimization could emit `HEADERS`+END_STREAM directly; out of scope to limit behavioral risk.)

## 5. `HTTPResponse` changes (delete the wart)
- Remove field `rawOutputStream` and method `setRawOutputStream`.
- `getOutputStream()` → returns `outputStream`.
- `close()` → `writer != null ? writer.close() : outputStream.close()`.
- `flush()` → `outputStream.forceFlush()`.
- `isCommitted()`, `isCompress()`, `willCompress()`, `setCompress()`, `reset()` → drop the `rawOutputStream == null` branches; always delegate to `outputStream`.
- Update Javadoc that claims compression is "Always false on the HTTP/2 path."

## 6. Wiring changes
- **`HTTP1Connection`** (line ~116): build an `HTTP1OutputProtocol` (holding the `ThroughputOutputStream`, `HTTPBuffers`, the `() -> state = Write` observer, `instrumenter`) and `new HTTPOutputStream(config, request, response, h1Protocol)`; `response.setOutputStream(...)`. Behavior identical.
- **`HTTP2Connection.spawnHandlerThread`:** replace `LazyHeaderOutputStream` + `setRawOutputStream` with an `HTTP2OutputProtocol` (holding `stream`, `writerQueue`, `connectionSendWindow`, `peerMaxFrameSize`, the shared `encoder`, `localSettings`, `logger`, and the `enqueueForWriter`/`HeadersFrame` emission) and `new HTTPOutputStream(config, request, response, h2Protocol)`; `response.setOutputStream(...)`. The post-close `stream.applyEvent(SEND_DATA_END_STREAM)` and stream/pipe cleanup stay in `spawnHandlerThread`.
- **`LazyHeaderOutputStream`** is deleted; its logic moves verbatim into `HTTP2OutputProtocol.commitHeaders` / `commitTrailers`. `HTTP2OutputProtocol` is a **nested class of `HTTP2Connection`** (like the `LazyHeaderOutputStream` it replaces) because it needs `enqueueForWriter` / `writerQueue` / `connectionSendWindow` / `peerSettings` access; `HTTP1OutputProtocol` and the `HTTPOutputProtocol` interface are standalone files.
- **`HTTP2OutputStream`** is unchanged (it is the h2 framing sink).
- **`ChunkedOutputStream`** becomes a pure data-chunk framer: remove `setTrailers` and the `trailers` field, and change `close()` to flush the final data chunk only — it writes **no** last-chunk marker, **no** trailers, and does **not** close its delegate (the socket sink). The end-of-message sequence (`0\r\n` + trailer fields + `\r\n`) moves verbatim into `HTTP1OutputProtocol.commitTrailers()`, which writes it to the socket sink the protocol already holds, reusing the existing `ChunkedTerminator` / `EmptyChunk` constants. `HTTPOutputStream` is the only consumer, so this is contained. Net effect: trailer emission is co-located with the `Trailer`-header decision and symmetric with the h2 trailer path, and `ChunkedOutputStream` has a single responsibility.
- Implementation step: grep for other `new HTTPOutputStream(` / `setRawOutputStream(` / `ChunkedOutputStream` / `setTrailers(` call sites (tests, expect-continue is unaffected — it bypasses via `writeResponsePreamble`).

## 7. Header & framing rule split

| Rule | Owner |
|---|---|
| HEAD body suppression; 204/304 no body + strip CL/TE | **Shared** (`HTTPOutputStream`) |
| gzip/deflate selection, `Content-Encoding`/`Vary`, remove `Content-Length` | **Shared** |
| `Transfer-Encoding` vs `Content-Length`, chunked-when-no-CL, handler-set-TE, trailers-force-chunked | **H1** strategy |
| Strip connection-specific headers, HPACK encode, stream-state-atomic `HEADERS` enqueue | **H2** strategy |
| Chunk terminator + trailers (`0\r\n`…`\r\n`) | **H1** strategy `commitTrailers` (not `ChunkedOutputStream`) |
| `HEADERS`+END_STREAM trailers | **H2** strategy `commitTrailers` |

Ordering note: shared compression runs first and removes `Content-Length`; the H1 strategy then sees no `Content-Length` and selects chunked — exactly the current sequencing.

## 8. Compression specifics
- Encoders: `GZIPOutputStream(sink, /*syncFlush=*/true)` and `DeflaterOutputStream(sink, /*syncFlush=*/true)`, matching HTTP/1 today (per-flush sync so streaming handlers see bytes promptly).
- Selection honors `Accept-Encoding` order as the current h1 loop does (gzip and deflate only; `x-gzip` already normalized to gzip on the request side).
- `Content-Length` is always removed when compressing (compressed size is unknown while streaming) — fine for h2 (length is optional there).
- `Vary: Accept-Encoding` is set on both protocols for cache correctness.

## 9. Error handling & edge cases
- **Stream reset before `HEADERS` (h2):** `commitHeaders` detects `state == CLOSED` / failed enqueue, returns a no-op sink, and marks the response suppressed so subsequent writes/close are no-ops — same as today's `streamReset`.
- **Flow-control blocking (h2):** unchanged; blocking lives in `HTTP2OutputStream`. The handler runs on its per-stream virtual thread.
- **Encoder concurrency (h2):** the shared `HPACKEncoder`/dynamic table is mutated under `synchronized (encoder)` inside the strategy, identical to today.
- **GZIP construction failure:** wrapped as `RuntimeException` as today.
- **`reset()` / error path (h1):** `closeSocketOnError` → `response.reset()` → `outputStream.reset()` → `protocol.reset()` resets the socket buffer; preamble re-emitted on the synthetic error response. h2 has its own handler-thread error path and does not call `reset()`.
- **Keep-alive reuse (h1):** `HTTPOutputStream` is reconstructed per connection iteration (unchanged); `reset()` preserves `suppressBody` for HEAD error responses as today.

## 10. Risks & behavior change
- **h2 now compresses by default.** With `compressByDefault = true`, any h2 client sending `Accept-Encoding: gzip` (browsers, curl) now receives gzip. This is the intended parity behavior. Document in release notes. Existing h2 tests use the JDK `HttpClient`, which does **not** send `Accept-Encoding` by default, so they are unaffected — to verify, audit h2 tests for any that explicitly assert uncompressed bodies while sending an accept-encoding header.
- **Touching the most sensitive output path.** `HTTPOutputStream` backs all HTTP/1 conformance. The rewrite is TDD-driven with the existing suite (`CompressionTest`, chunked/trailers, HEAD/204/304, expect-continue) as the regression gate; no HTTP/1 wire behavior may change.
- **License headers.** `HTTPOutputStream`, `HTTPResponse`, and `ChunkedOutputStream` come from FusionAuth's java-http and are Apache-2.0 *licensed* with FusionAuth as the copyright holder — java-http is FusionAuth's own project, not an Apache Software Foundation (apache.org) project. **Keep** their existing Apache-2.0 / FusionAuth headers and `@author` lines; do not convert them to MIT, even though the `HTTPOutputStream` rewrite and the `ChunkedOutputStream` refactor are substantial. The brand-new standalone files (`HTTPOutputProtocol`, `HTTP1OutputProtocol`) get the MIT `The Latte Project` header; `HTTP2OutputProtocol` is a nested class inside the already-MIT `HTTP2Connection`.

## 11. Testing strategy
1. **HTTP/1 regression:** entire existing suite green, unchanged — the primary safety net.
2. **HTTP/2 regression:** all `HTTP2*` tests green, unchanged.
3. **New h2 compression tests** (`HTTP2CompressionTest`, mirroring `CompressionTest`): client sends `Accept-Encoding: gzip` / `deflate`; assert `Content-Encoding` + `Vary` present and the decompressed body matches; cover single-write, multi-write+flush streaming, a body spanning multiple `DATA` frames, `setCompress(false)` disabling, and `withCompressByDefault(false)` disabling.
4. **h2 compression + trailers** together: gzip body then `HEADERS`+END_STREAM trailers, in the correct frame order.
5. **Strategy unit tests** where cheap: drive `HTTP2OutputProtocol` against a fake stream/queue (as `HTTP2OutputStreamFragmentationTest` does) to assert `HEADERS`-before-`DATA` and trailer ordering without a full server.
6. **Trailer-emission relocation:** `ChunkedOutputStreamTrailersTest` currently asserts trailer bytes via `ChunkedOutputStream`. Since that logic moves, retarget those assertions at `HTTP1OutputProtocol.commitTrailers()` (or an HTTP/1 trailers integration test through the server); `ChunkedOutputStream`'s own test then covers only data-chunk framing. The on-the-wire chunked+trailers output must be byte-identical to today — diff against a captured baseline.

## 12. Out of scope
- Brotli / zstd (no encoder selection exists today).
- Request-body decompression changes (`HTTPInputStream` is untouched).
- Content-type/size-threshold gating of compression (not done for h1 today; parity means not adding it here).
- The empty-body `HEADERS`+END_STREAM micro-optimization for h2.

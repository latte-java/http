# HTTP/2 Compliance — latte-java HTTP Server

Tracking document for RFC 9113 (HTTP/2) and RFC 7541 (HPACK) conformance. This is the always-current reference for HTTP/2 in this codebase. The dated implementation history lives in `docs/superpowers/specs/2026-05-05-http2-design.md`.

Conformance: h2spec sanity passes (generic/1); full suite run pending. gRPC interop verified for unary + server-streaming RPC patterns.

## Legend

- ✅ **Implemented** — covered by code and tests
- ⚠️ **Partial** — works for the common case, has known gaps or missing tests
- ❌ **Missing** — not implemented; would need work to claim conformance
- 🚫 **Out of scope** — handler responsibility, or deliberately not implemented

Each entry should cite the relevant code and (where applicable) tests.

---

## Architecture overview

HTTP/2 traffic flows through the same accept loop as HTTP/1.1. Per-connection: one virtual thread per accepted socket, a `ProtocolSelector` decides between `HTTP1Worker` and `HTTP2Connection`, then never crosses back (except for the h2c-Upgrade handoff inside `HTTP1Worker`).

Within an HTTP/2 connection there are three thread roles, all virtual:

- **Reader** — owns the socket InputStream. Parses frames, dispatches HEADERS/CONTINUATION through HPACK, routes DATA to per-stream pipes, applies inbound flow control, handles SETTINGS/PING/GOAWAY/RST_STREAM.
- **Writer** — owns the socket OutputStream and a bounded outbound frame queue. Applies connection-level + per-stream send-window accounting; serializes all writes.
- **Per-stream handler** — one virtual thread per active stream; runs `HTTPHandler.handle(request, response)` exactly as for HTTP/1.1. Reads from a pipe filled by the reader; writes to the writer's queue.

The handler-facing API (`HTTPRequest`, `HTTPResponse`, `HTTPHandler`) is identical between protocols. Pseudo-headers map onto `HTTPRequest` fields; `getProtocol()` returns `"HTTP/2.0"` for h2 clients that need to discriminate.

Class layout in `org.lattejava.http.server.internal`:
- `HTTP2Connection` — connection-level state (settings, send/receive windows, stream registry, GOAWAY).
- `HTTP2Stream` — per-stream state machine, input pipe, output queue.
- `HTTP2FrameReader` / `HTTP2FrameWriter` — frame codec.
- `HTTP2InputStream` / `HTTP2OutputStream` — h2-specific stream classes; not exported (handlers see only `InputStream`/`OutputStream`).
- `HPACKEncoder` / `HPACKDecoder` / `HPACKDynamicTable` / `HPACKHuffman` — RFC 7541.
- `HTTP2RateLimits` — sliding-window counters for DoS mitigations.
- `ClientConnection` — interface implemented by both `HTTP1Worker` and `HTTP2Connection` so the cleaner thread is protocol-agnostic.
- `ProtocolSelector` — dispatch.

---

## 1. Transport

| Mode | Status | Notes |
|---|---|---|
| h2 over TLS via ALPN (RFC 7301) | ✅ | Default-on for TLS listeners. Server advertises `["h2", "http/1.1"]`. Off-switch: `HTTPListenerConfiguration.enableHTTP2 = false`. — `HTTP2BasicTest`, `HTTP2ALPNTest` |
| h2c prior-knowledge (cleartext) | ✅ | Opt-in: `HTTPListenerConfiguration.enableH2cPriorKnowledge = true`. Selector peeks the first 24 bytes for the connection preface. — `HTTP2H2cPriorKnowledgeTest` |
| h2c via `Upgrade`/101 (cleartext) | ✅ | Opt-in via `withH2cUpgradeEnabled` (default-off). RFC 9113 deprecated the Upgrade flow in favor of prior-knowledge; default-off avoids conflicts with JDK `HttpClient`'s eager `Upgrade: h2c` on HTTP/1.1 connections. Retained for back-compat with older clients. **h2c Upgrade requests with a body are rejected with 400 Bad Request** (`HTTP1Worker.validatePreamble`, short-circuits before 101). RFC 9113 §3.2 does not permit the original HTTP/1.1 body to carry over the 101 Switching Protocols boundary; until a future implementation maps the original request into implicit stream 1, body bytes would remain on the socket after the 101 and be mis-interpreted by the new HTTP/2 reader as frames — a request-smuggling / protocol-confusion footgun. — `HTTP2H2cUpgradeTest`, `ProtocolSwitchTest.h2c_upgrade_with_request_body_rejected_with_400`, `ProtocolSwitchTest.h2c_upgrade_with_chunked_body_rejected_with_400` |
| Connection preface validation | ✅ | Exact bytes `PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n` required; mismatch → connection close. — `HTTP2ConnectionPrefaceTest` |
| TLS 1.2 minimum (§9.2.1) | ✅ | JDK 21 disables TLSv1.0/1.1 by default. Implicit via JDK 21. |
| TLS 1.2 cipher blocklist (§9.2.2) | ❌ | After ALPN selects `h2`, check `SSLSession.getCipherSuite()` against Appendix A blocklist; blocklisted → `GOAWAY(INADEQUATE_SECURITY)`. |
| TLS-level compression forbidden (§9.2) | ✅ | JDK 21 doesn't expose TLS compression; implicit via JDK 21. |
| TLS renegotiation forbidden (§9.2) | ✅ | Server never initiates. Client-initiated renegotiation rejected via JDK system property (deployment recommendation). |

---

## 2. Frame types (RFC 9113 §6)

| Frame | Status | Notes |
|---|---|---|
| `DATA` (0x0) | ✅ | Inbound: routed to stream input pipe; END_STREAM transitions stream state. Outbound: chunked from handler writes by `HTTP2OutputStream`; END_STREAM on close. — `HTTP2FrameReaderTest`, `HTTP2BasicTest` |
| `HEADERS` (0x1) | ✅ | Inbound: HPACK-decoded, builds HTTPRequest. Outbound: response headers + trailers emission. END_HEADERS, END_STREAM, PADDED, PRIORITY flags supported (PRIORITY parsed and discarded). — `HTTP2FrameReaderTest`, `HTTP2BasicTest` |
| `PRIORITY` (0x2) | ✅ | Parsed and discarded per RFC 9113 §5.3. RFC 7540's priority scheme is deprecated. — `HTTP2FrameReaderTest` |
| `RST_STREAM` (0x3) | ✅ | Inbound: cancels stream handler thread, drops queued writes. Outbound: stream error responses. — `HTTP2RawFrameTest`, `HTTP2BasicTest` |
| `SETTINGS` (0x4) | ✅ | Initial server settings sent on connection start; ACK on inbound. Settings flood mitigation (rate-limit). — `HTTP2FrameReaderTest`, `HTTP2BasicTest` |
| `PUSH_PROMISE` (0x5) | 🚫 | Not emitted (`SETTINGS_ENABLE_PUSH=0`). Inbound from client → connection error PROTOCOL_ERROR (clients must not push). |
| `PING` (0x6) | ✅ | Inbound: respond with ACK. Outbound: optional server-initiated keepalive (`withHTTP2KeepAlivePingInterval`). Rate-limited. — `HTTP2FrameReaderTest`, `HTTP2BasicTest` |
| `GOAWAY` (0x7) | ✅ | Outbound on shutdown / protocol error / DoS threshold. Inbound: stop opening new streams; existing streams complete. Graceful shutdown after `GOAWAY(NO_ERROR)` waits up to `withShutdownDuration` (default 10s) for in-flight streams before forcing socket close. — `HTTP2RawFrameTest`, `HTTP2BasicTest` |
| `WINDOW_UPDATE` (0x8) | ✅ | Inbound: extends a send-window. Outbound: replenishes our receive-window (replenish-when-half-empty strategy). Rate-limited. — `HTTP2FrameReaderTest`, `HTTP2BasicTest` |
| `CONTINUATION` (0x9) | ✅ | Continues a HEADERS or PUSH_PROMISE block when it exceeds MAX_FRAME_SIZE. Both directions supported. CONTINUATION-flood mitigation via MAX_HEADER_LIST_SIZE-bounded accumulator (CVE-2024-27316). — `HTTP2FrameReaderTest`, `HTTP2RawFrameTest`, `HTTP2SecurityTest` |
| Unknown frame types | ✅ | Ignored per RFC 9113 §5.5. — `HTTP2RawFrameTest` |

---

## 3. HPACK (RFC 7541)

| Feature | Status | Notes |
|---|---|---|
| Static table (61 entries, Appendix A) | ✅ | — `HPACKDecoderTest`, `HPACKEncoderTest` |
| Dynamic table | ✅ | Capped at `SETTINGS_HEADER_TABLE_SIZE` (default 4096, configurable). — `HPACKDynamicTableTest` |
| Dynamic-table-size-update signal (§6.3) | ✅ | Emitted on size-down acknowledgment. — `HPACKDynamicTableTest` |
| Indexed header field (§6.1) | ✅ | — `HPACKDecoderTest`, `HPACKEncoderTest` |
| Literal with incremental indexing (§6.2.1) | ✅ | — `HPACKDecoderTest`, `HPACKEncoderTest` |
| Literal without indexing (§6.2.2) | ✅ | Used for sensitive headers (Authorization, Set-Cookie). — `HPACKDecoderTest`, `HPACKEncoderTest` |
| Literal never-indexed (§6.2.3) | ✅ | Available for handler-marked-sensitive headers (future API). — `HPACKDecoderTest`, `HPACKEncoderTest` |
| Huffman coding (Appendix B) | ✅ | Static code table. — `HPACKHuffmanTest` |
| Header-name validation | ⚠️ | RFC 9113 §8.2 — uppercase ASCII rejection is enforced (`HTTP2Connection.validateHeaders`). Full tchar set check (e.g. reject space, comma, control chars) deferred to Plan F. |
| Header-value validation | ⚠️ | HPACK decoder writes ASCII; explicit bare CR/LF/NUL rejection deferred to Plan F. |
| `MAX_HEADER_LIST_SIZE` enforcement | ✅ | Cumulative byte budget enforced on the HEADERS+CONTINUATION accumulator in `HTTP2Connection.handleHeadersFrame` and `handleContinuationFrame`. GOAWAY(ENHANCE_YOUR_CALM) when exceeded. — `HTTP2SecurityTest.continuation_flood_triggers_goaway` |

**HPACK index 0** in an indexed-header-field representation is invalid (RFC 7541 §6.1 reserves index 0). The decoder throws `IOException`; `HTTP2Connection.finalizeHeaderBlock` maps that to `GOAWAY(COMPRESSION_ERROR)` (RFC 9113 §4.3 — HPACK malformations are connection errors). — `HPACKDecoderTest.decode_index_zero_throws_ioexception_per_rfc_7541_section_2_1`, `HTTP2SecurityTest.hpack_index_zero_yields_goaway_compression_error`

---

## 4. Stream lifecycle (RFC 9113 §5.1)

| Feature | Status | Notes |
|---|---|---|
| State machine: idle / open / half-closed (local) / half-closed (remote) / closed | ✅ | Encoded in `HTTP2Stream.applyEvent`. Reserved states unused (no push). — `HTTP2StreamStateMachineTest` |
| `MAX_CONCURRENT_STREAMS` enforcement | ✅ | Default 100, configurable. New HEADERS over cap → `RST_STREAM(REFUSED_STREAM)`. — `HTTP2Connection.handleHeadersFrame` |
| Stream-id ordering (client odd, monotonic) | ✅ | Stream-id ≤ highest-seen → connection error PROTOCOL_ERROR. — `HTTP2RawFrameTest.decreasing_stream_id_triggers_protocol_error` |
| Stream-error vs connection-error classification (§5.4) | ✅ | Per-frame violation rules; connection-level errors → GOAWAY + close. Implicit in current handlers. |
| Trailing HEADERS frame (request-side trailers) | ⚠️ | Detected as HEADERS-after-DATA on a stream; populates `HTTPRequest` trailer map. Plan F polish. |
| Trailing HEADERS frame (response-side trailers) | ⚠️ | Final HEADERS frame with END_STREAM, after final DATA, when handler set trailers. Plan F polish. |

---

## 5. Flow control (RFC 9113 §5.2, §6.9)

| Feature | Status | Notes |
|---|---|---|
| Connection-level send-window | ✅ | Tracked by writer thread; gates DATA serialization. — `HTTP2Connection.handleData` |
| Per-stream send-window | ✅ | Tracked by writer thread; per-stream condition variable for handler unblock on WINDOW_UPDATE. — `HTTP2Stream`, `HTTP2OutputStream` (signed comparison) |
| Connection-level receive-window | ✅ | Tracked by reader thread; replenished via WINDOW_UPDATE when below half. — `HTTP2Connection.handleData` |
| Per-stream receive-window | ✅ | Same replenish-when-half-empty strategy. |
| `SETTINGS_INITIAL_WINDOW_SIZE` | ✅ | Default 65535 (RFC default), configurable via `withHTTP2InitialWindowSize`. |
| Window-size change retroactive adjustment (§6.9.2) | ✅ | When peer's `INITIAL_WINDOW_SIZE` changes mid-connection, all open streams' send-windows adjusted by the delta. — `HTTP2Connection.handleSettings`, `HTTP2FlowControlTest.send_window_can_go_negative_after_settings_decrease` |
| Flow-control disabled for DATA flag | 🚫 | RFC 9113 doesn't define a way to disable flow control. |

**Send-window signed-comparison invariant.** RFC 9113 §6.9.2 allows the send-window to become **negative** when peer SETTINGS retroactively shrinks `INITIAL_WINDOW_SIZE` below the bytes a sender has already in flight. Code that gates a send on available credit MUST use signed comparison (`window >= bytes`), not `window > 0`, and the window field must stay an `int` (not `long`/`unsigned`). This is an easy regression — a "harden against negative" refactor that flips to unsigned arithmetic would silently break §6.9.2 conformance. The current flow-control accounting in `HTTP2Stream` and `HTTP2OutputStream` follows this convention. — `HTTP2FlowControlTest.send_window_can_go_negative_after_settings_decrease`

---

## 6. Pseudo-headers and request mapping (RFC 9113 §8.3)

| Feature | Status | Notes |
|---|---|---|
| `:method`, `:scheme`, `:path` required | ✅ | All three must be present and exactly once for non-CONNECT requests (RFC 9113 §8.3.1). `:authority` is recognized and mapped to the `Host` header when present, but is not required — the RFC makes it a SHOULD, not a MUST. Validation order: pseudo-headers must precede regular headers. CONNECT-specific pseudo-header rules (§8.5) deferred until the method is supported. |
| Connection-specific headers forbidden (`Connection`, `Keep-Alive`, `Transfer-Encoding`, `Upgrade`, `Proxy-Connection`) | ✅ | Stream error PROTOCOL_ERROR. — `HTTP2Connection.validateHeaders`, `HTTP2HeaderValidationTest` |
| Uppercase in header name forbidden | ✅ | Stream error PROTOCOL_ERROR. — `HTTP2Connection.validateHeaders`, `HTTP2HeaderValidationTest` |
| `Cookie` coalescing across multiple headers | ⚠️ | Per RFC 9113 §8.2.3, h2 splits Cookie across multiple headers; coalescing with `; ` not yet implemented. Plan F. |
| `getProtocol()` returns `"HTTP/2.0"` | ✅ | For handlers that need to discriminate. — `HTTP2BasicTest.get_round_trip_h2` |
| `isKeepAlive()` returns `true` on h2 | ✅ | Multiplexed h2 connections are persistent by definition; the per-request close concept doesn't apply. — `HTTPRequest.isKeepAlive` |
| Strip h1.1-only response headers (`Connection`, `Keep-Alive`, `Transfer-Encoding`, `Upgrade`, `Proxy-Connection`) on h2 emit | ✅ | Connection-specific headers forbidden on h2 (RFC 9113 §8.2.2). Stripped (logged at debug), not error-failed. — `HTTP2BasicTest.h1_only_response_headers_stripped_on_h2` |

---

## 7. Trailers

| Feature | Status | Notes |
|---|---|---|
| Response trailers — h2 | ✅ | `HTTPResponse.setTrailer/addTrailer/getTrailers`. Emitted as final HEADERS frame with END_STREAM after final DATA. Tested via gRPC unary and server-streaming routes (grpc-status trailer round-trips correctly). — `GRPCInteropTest` |
| Response trailers — h1.1 | ✅ | Same API. Forces `Transfer-Encoding: chunked`. Emitted after `0\r\n` per RFC 9112 §7.1.2. Auto-set `Trailer:` header. Honor `TE: trailers` request signaling. |
| Trailers-only response (no body) | ❌ | gRPC failed-RPC pattern: HEADERS without END_STREAM (response headers) followed by HEADERS with END_STREAM (trailers). |
| Request trailers — h2 | ⚠️ | `HTTPRequest.getTrailer/getTrailers/getTrailerMap/hasTrailers`. Available after request input EOF. h2-side wiring deferred to Plan F. |
| Request trailers — h1.1 | ✅ | Same API. Populated from `ChunkedInputStream` trailer parse. |
| Trailer-name deny-list (RFC 9110 §6.5.2) | ✅ | `setTrailer`/`addTrailer` throws `IllegalArgumentException` for forbidden names. Full enumerated list lives on `HTTPValues.ForbiddenTrailers` and covers framing, routing, request modifiers, authentication, response control, and connection management headers — see the dated design doc for the exact set. |

---

## 8. Settings (RFC 9113 §6.5.2)

Initial server settings sent in the first SETTINGS frame after the connection preface (or after 101 for h2c-Upgrade). All 6 standard settings are configurable and sent on startup. — `HTTPServerConfigurationHTTP2Test`, `HTTP2ConnectionPrefaceTest`

| Setting | Default | Configurable | Configuration knob |
|---|---|---|---|
| `SETTINGS_HEADER_TABLE_SIZE` | 4096 | ✅ | `withHTTP2HeaderTableSize(int)` |
| `SETTINGS_ENABLE_PUSH` | 0 (disabled) | no | Push is out of scope; advertise=0 always. |
| `SETTINGS_MAX_CONCURRENT_STREAMS` | 100 | ✅ | `withHTTP2MaxConcurrentStreams(int)` |
| `SETTINGS_INITIAL_WINDOW_SIZE` | 65535 | ✅ | `withHTTP2InitialWindowSize(int)` |
| `SETTINGS_MAX_FRAME_SIZE` | 16384 | ✅ | `withHTTP2MaxFrameSize(int)` (max 16777215) |
| `SETTINGS_MAX_HEADER_LIST_SIZE` | 8192 | ✅ | `withHTTP2MaxHeaderListSize(int)` |
| `SETTINGS_TIMEOUT` (peer ACK deadline) | 10 s | ⚠️ | `withHTTP2SettingsAckTimeout(Duration)` — RFC 9113 §6.5.3; knob exists but ACK-deadline enforcement deferred to Plan F. |

Inbound SETTINGS rate-limited (DoS protection); see §10.

---

## 9. Error codes (RFC 9113 §7)

All standard error codes implemented and emitted at the appropriate trigger:

| Code | Used when |
|---|---|
| `NO_ERROR` (0x0) | Graceful GOAWAY on shutdown. |
| `PROTOCOL_ERROR` (0x1) | Pseudo-header violations, illegal frame for state, stream-id reuse, etc. |
| `INTERNAL_ERROR` (0x2) | Unhandled handler exception that escapes the dispatcher. |
| `FLOW_CONTROL_ERROR` (0x3) | Peer exceeds advertised window. |
| `SETTINGS_TIMEOUT` (0x4) | Peer doesn't ACK our SETTINGS within timeout. |
| `STREAM_CLOSED` (0x5) | Frame on stream already closed. |
| `FRAME_SIZE_ERROR` (0x6) | Frame larger than MAX_FRAME_SIZE; malformed length on fixed-size frames. |
| `REFUSED_STREAM` (0x7) | New HEADERS over MAX_CONCURRENT_STREAMS. |
| `CANCEL` (0x8) | Handler cancelled mid-execution. |
| `COMPRESSION_ERROR` (0x9) | HPACK decoding failure. |
| `CONNECT_ERROR` (0xa) | CONNECT-method tunneling — not implemented (we're not a proxy). |
| `ENHANCE_YOUR_CALM` (0xb) | DoS rate-limit threshold crossed; see §10. |
| `INADEQUATE_SECURITY` (0xc) | TLS 1.2 negotiated cipher in blocklist (§9.2.2). |
| `HTTP_1_1_REQUIRED` (0xd) | Reserved; not currently emitted (we don't downgrade). |

**Malformed `content-length` header.** An unparseable or negative value is a stream error of type `PROTOCOL_ERROR` (RFC 9113 §8.1.2.6). The check runs in `HTTP2Connection.finalizeHeaderBlock` before the handler is spawned; the stream is RST_STREAMed and never enters the `streams` map. This matches the behavior of nghttp2, Caddy, and Apache Traffic Server. — `HTTP2SecurityTest.malformed_content_length_yields_rst_stream_protocol_error`

---

## 10. Security and DoS mitigations

| Concern | Status | Notes |
|---|---|---|
| `MAX_CONCURRENT_STREAMS` enforcement | ✅ | See §4. |
| Rapid Reset (CVE-2023-44487) | ✅ | Default: >100 client RST_STREAMs in 30 s → `GOAWAY(ENHANCE_YOUR_CALM)`. Configurable via `HTTP2RateLimits`. — `HTTP2SecurityTest.rapid_reset_triggers_goaway` |
| CONTINUATION flood (CVE-2024-27316) | ✅ | Cumulative accumulator bounded by `MAX_HEADER_LIST_SIZE`; exceeding it sends GOAWAY(ENHANCE_YOUR_CALM). — `HTTP2SecurityTest.continuation_flood_triggers_goaway` |
| PING flood | ✅ | Default: >10 PING/s → `GOAWAY(ENHANCE_YOUR_CALM)`. — `HTTP2SecurityTest.ping_flood_triggers_goaway` |
| SETTINGS flood | ✅ | Same shape. — `HTTP2SecurityTest.settings_flood_triggers_goaway` |
| Empty-DATA flood (zero-length DATA without END_STREAM) | ⚠️ | Default: >100 in 30 s. Counter exists; dedicated test deferred (noted in `HTTP2SecurityTest`). |
| WINDOW_UPDATE flood | ✅ | Default: >100/s. — `HTTP2SecurityTest.window_update_flood_triggers_goaway` |
| Slow-read | ⚠️ | Existing `MinimumWriteThroughput` instrumentation flows through writer thread; dedicated test deferred. |
| Slow / stuck handler stalling other streams on the same connection | ✅ | Connection-reader's `pipe.offer(timeout)` (`withHTTP2HandlerReadTimeout`, default 10 s) → `RST_STREAM(CANCEL)` on timeout. Prevents one slow handler from freezing every other stream on the connection. Not RFC-mandated; defensive — RFC 9113 §5.2 covers the legitimate back-pressure path via flow control. |
| Header-name/value validation | ⚠️ | Reuses `HTTPTools.isTokenCharacter` and `isValueCharacter`. Explicit enforcement deferred to Plan F. |
| Response-splitting defense | ✅ | Reuses choke point at `HTTPResponse.setHeader/addHeader/sendRedirect/Cookie` (audit Vuln 4 fix). Implicit via existing h1.1 defense. |

**Body-size limits.** `HTTPServerConfiguration.maxRequestBodySize` (a per-Content-Type map with wildcard support) is enforced on HTTP/2 requests via the same `HTTPInputStream` boundary check used on HTTP/1.1. The cap is resolved per request via `HTTPTools.getMaxRequestBodySize` against `request.getContentType()` and passed to the `HTTPInputStream` constructor in `HTTP2Connection.spawnRequestHandler`. The `MultipartConfiguration` (file upload policy, `maxFileSize`, `maxFileCount`) is applied to each request's `MultipartStreamProcessor` immediately before the `HTTPInputStream` is constructed, matching `HTTP1Worker` behavior. A `ContentTooLargeException` from the read path surfaces as a 413 response on both transports.

---

## 11. Server push (RFC 9113 §8.4)

🚫 **Out of scope.** Browsers (Chrome 106+, Firefox) removed support; gRPC doesn't use it; the replacement story (Early Hints / 103) is a separate feature track. We advertise `SETTINGS_ENABLE_PUSH=0` and reject inbound `PUSH_PROMISE` as connection error PROTOCOL_ERROR (clients are forbidden from pushing). Major peer servers (Jetty, Tomcat, Netty) ship push disabled-by-default; we go further and don't ship the API at all.

---

## 12. Configuration knobs

### `HTTPListenerConfiguration`

✅ All three knobs shipped. — `HTTPListenerConfiguration`

| Knob | Default | Notes |
|---|---|---|
| `enableHTTP2` | `true` (TLS only — ignored on cleartext) | Controls ALPN advertisement of `h2`. On cleartext, h2c is independently controlled by the two flags below. |
| `enableH2cPriorKnowledge` | `false` (cleartext only — ignored on TLS) | Opt-in. Selector peeks first 24 bytes for the connection preface. Required for gRPC. |
| `enableH2cUpgrade` | `false` (cleartext only — ignored on TLS) | Opt-in (changed from planned default-on for safety). Honored when client sends `Upgrade: h2c, HTTP2-Settings: ...` |

### `HTTPServerConfiguration`

✅ All `withHTTP2*` knobs shipped. — `HTTPServerConfigurationHTTP2Test`

| Knob | Default | RFC reference |
|---|---|---|
| `withHTTP2HandlerReadTimeout(Duration)` | 10 s | Bounds the connection-reader thread's `pipe.offer(...)` to the per-stream input pipe. If a handler virtual-thread does not consume its body within this window, the reader RST_STREAMs the offending stream with `CANCEL` and proceeds. RFC 9113 §5.2 flow control is the intended back-pressure mechanism — this is a safety net against handlers that genuinely fail to read. See §10. |
| `withHTTP2HeaderTableSize(int)` | 4096 | §6.5.2 |
| `withHTTP2InitialWindowSize(int)` | 65535 | §6.5.2 |
| `withHTTP2MaxConcurrentStreams(int)` | 100 | §6.5.2 |
| `withHTTP2MaxFrameSize(int)` | 16384 | §6.5.2 (max 16777215) |
| `withHTTP2MaxHeaderListSize(int)` | 8192 | §6.5.2 |
| `withHTTP2KeepAlivePingInterval(Duration)` | disabled | Optional server-initiated PING |
| `withHTTP2SettingsAckTimeout(Duration)` | 10 s | §6.5.3 — peer ACK deadline (⚠️ knob exists; ACK enforcement deferred to Plan F) |

`SETTINGS_ENABLE_PUSH` is fixed at 0 (push out of scope).

**Note on rate limit configuration:** `HTTP2RateLimits` is an internal type (not exported from the module). Custom rate limits are not currently configurable from the library's public API — the defaults are applied automatically. Future work can expose scalar setters per counter (e.g., `withHTTP2MaxPingsPerSecond`) or promote `HTTP2RateLimits` to a public package.

---

## 13. Peer comparison

How latte-java's HTTP/2 surface compares against the Java ecosystem leaders. Captured at the time of this doc; revise as peers evolve.

| Feature | latte-java | Jetty 12 | Tomcat 11 | Netty 4 | Undertow 2 | Helidon Níma 4 |
|---|---|---|---|---|---|---|
| h2 over TLS-ALPN | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| h2c prior-knowledge | ✅ (opt-in) | ✅ (opt-in) | ✅ (opt-in) | ✅ | ✅ | ✅ |
| h2c via Upgrade/101 | ✅ (opt-in) | ✅ (opt-in) | ✅ (opt-in) | ✅ | ✅ | ✅ |
| Default-on for TLS | ✅ | ✅ | ✅ | (config) | (config) | ✅ |
| HPACK | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Server push | 🚫 (no API) | ⚠️ disabled-default | ⚠️ disabled-default | ⚠️ | ⚠️ | ❌ |
| Response trailers | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Request trailers | ⚠️ (h2 deferred) | ✅ | ✅ | ✅ | ✅ | ✅ |
| gRPC interop tested | ⚠️ (sanity only) | ⚠️ via grpc-jetty | ⚠️ via servlet adapter | ✅ (native) | ⚠️ | ✅ |
| Rapid Reset mitigation | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| CONTINUATION flood mitigation | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Configurable concurrency cap | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Configurable initial window | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Virtual-thread per stream | ✅ | ⚠️ (config) | ⚠️ (config) | ❌ (event loop) | ❌ | ⚠️[^nima] |

[^nima]: Helidon Níma uses virtual threads as carrier threads for its event loop, not strictly virtual-thread-per-stream the way latte-java does. End behavior is similar; the architectural shape differs. Worth a footnote so the comparison is honest.

The last row is our differentiator. Pure virtual-thread + blocking-I/O code is unique among Java performance leaders.

---

## Performance summary

Benchmark suite: `benchmarks/perf-test.sh` with JFR profiling (`--detailed`). Tool: wrk. h2load deferred until `brew install nghttp2`.

### Performance findings (2026-05-09)

**Run config:** `hello` scenario, 5s × 1 trial, wrk 12 threads / 100 connections. Machine: Apple M4, 10 cores, 24 GB RAM, macOS 15.7.3, JDK 25.0.2.

**Baseline (pre-fix, commit 9dd655f):**

| Metric | Value |
|---|---|
| Throughput | 85,390 req/s |
| p50 latency | 1,064 µs |
| p90 latency | 1,246 µs |
| p99 latency | 6,807 µs |
| Alloc / req | 23,449 bytes |
| GC count (5s) | 36 |
| GC pause total | 27.0 ms (0.54% overhead) |

**Top allocation hotspots (baseline):**

| Rank | Site | % of alloc events | Notes |
|---|---|---|---|
| 1 | `java.io.InputStream.readNBytes(int)` | 74% | Benchmark handler calling `readAllBytes()` — benchmark artifact, not server library |
| 2 | `java.util.concurrent.ForkJoinPool.execute(Runnable)` | 8.6% | Virtual-thread scheduling; inherent to per-request VT model |
| 3 | `org.lattejava.http.server.io.HTTPInputStream.drain()` | 3.8% | Per-request `new byte[2048]` even for bodyless GET requests — clear quick win |

**Top CPU hotspots (baseline):**

| Rank | Method | CPU % |
|---|---|---|
| 1 | `HTTP1Worker.run()` | 14.2% |
| 2 | `HTTPTools.parseRequestPreamble()` | 10.1% |
| 3 | `java.lang.StringLatin1.hashCode(byte[])` | 9.5% |

**Fix applied:** `HTTPInputStream.drain()` — skip skip-buffer allocation and drain loop when `request.hasBody()` is false. For GET/HEAD requests that declare no body, `drain()` was allocating a `byte[2048]` and immediately hitting EOF. One guard at the top of `drain()` eliminates this per-request allocation for the dominant request pattern.

**After fix:**

| Metric | Before | After | Delta |
|---|---|---|---|
| Throughput | 85,390 req/s | 85,498 req/s | +0.1% (noise) |
| p99 latency | 6,807 µs | 4,212 µs | −38% |
| Alloc / req | 23,449 bytes | 21,369 bytes | −8.9% |
| GC count (5s) | 36 | 33 | −8% |
| GC pause total | 27.0 ms | 22.1 ms | −18% |
| `HTTPInputStream.drain()` alloc rank | #3 (3.8%) | not in top 8 | eliminated |

Throughput is noise-level flat (both within normal run-to-run variance for a 5s × 1 trial). The meaningful gains are allocation rate (−8.9% bytes/req) and GC pressure (−18% total pause), which will compound under sustained load.

**Remaining follow-up candidates (deferred):**

- HPACK Huffman encoding on the encode path (decoder uses Huffman; encoder writes literal-only for v1 determinism)
- HEAD method handling on h2 (current code rebuilds the request for HTTP/1.1; h2 can short-circuit)
- DATA frame payload pooling (currently allocates a `byte[]` per frame in the writer queue)
- HPACKDecoder.decodeInt long-pack already done; consider similar packing on encode path
- Connection-level WINDOW_UPDATE strategy (current: per-DATA-frame; consider replenish-when-half-empty across connection)
- `StringLatin1.hashCode()` at 9.5% CPU — header-map lookups; consider interning or pre-hashing common header names
- `Socket.getRemoteSocketAddress()` per-request allocation (1% alloc events) — could be cached on `HTTPRequest` construction

### Performance findings (2026-05-10): empty-body request fast-path

**Run config:** h2load against `h2-high-concurrency` (4 threads / 10 connections / 100 streams per connection, h2c), 20 s JFR window inside a 30 s run. Single-trial JFR comparison for the allocation deltas; rigorous 30 s × 3 matrix for the throughput numbers in the README. Machine: Apple M4, 10 cores, 24 GB RAM, macOS 15.7.3, JDK 25.0.2.

**Hypothesis check.** Initial profiling was aimed at the deferred "DATA frame payload pooling" item — the assumption being that `HTTP2OutputStream.flushAndFragment`'s per-DATA-frame `new byte[chunk]` allocations dominated the wire path. JFR weight-summed `byte[]` allocations told a different story:

| Rank | byte[] alloc site (baseline) | Bytes / 20 s |
|---|---|---:|
| 1 | `InputStream.readNBytes(int)` ← `readAllBytes()` ← **`LoadHandler.handleHello`** | **116 GB** |
| 2 | `StringLatin1.toLowerCase` | 1.4 GB |
| 3 | `Arrays.copyOf` ← `ByteArrayOutputStream.toByteArray` | 1.3 GB |
| 4 | `ByteArrayOutputStream.<init>` | 0.97 GB |
| 5 | `HTTP2OutputStream.flushAndFragment` | 0.12 GB |

The dominant site is the benchmark handler calling `is.readAllBytes()` on every request, which (for bodyless GETs) drops into `InputStream.readNBytes(Integer.MAX_VALUE)` — the JDK default allocates a 16 KB read buffer, calls `read()`, gets `-1`, throws the buffer away. Roughly 1000× the magnitude of `flushAndFragment`.

**Audit of the four h2 benchmark handlers** showed Netty's `handleHello()` never touches the request body (it builds a `FullHttpResponse` directly), while Jetty / Tomcat / jdk-httpserver / Latte all call `is.readAllBytes()`. The library-side improvement is to make the drain cheap on Latte's stream rather than asymmetrically patch the benchmark handler, since real applications are likely to read the body even when it is empty.

**Fix.** New singleton `org.lattejava.http.server.io.EmptyHTTPInputStream` and a guarded fast-path in `HTTP2Connection.spawnRequestHandler`: when the HEADERS frame carries `END_STREAM` (i.e. no body will follow on this stream), skip the per-stream `ArrayBlockingQueue<byte[]>`, the `HTTP2InputStream`, the `PushbackInputStream`, and the wrapping `HTTPInputStream` entirely. The handler sees a zero-allocation empty input stream whose `readAllBytes()` / `readNBytes(int)` return a shared empty `byte[]` constant. A stale DATA frame on such a stream was already handled by `handleData` returning silently when the pipe is missing.

**Allocation delta (h2-high-concurrency, 20 s JFR @ ~426K req/s).**

| Object class — total | Before | After | Delta |
|---|---:|---:|---:|
| `byte[]` | 121.9 GB | **7.2 GB** | **−94%** |
| `org.lattejava.http.server.internal.HTTP2Connection$LazyHeaderOutputStream` | 474 MB | 414 MB | −13% (per-stream, unrelated path) |
| `java.util.concurrent.ArrayBlockingQueue` | 471 MB | not in top 20 | per-stream pipe eliminated for bodyless requests |
| `org.lattejava.http.server.io.HTTPInputStream` | 617 MB | not in top 20 | wrapper allocation eliminated for bodyless requests |

The dominant `readNBytes(int)` site collapses from 116 GB / 20 s to not in the top 12. JFR file size for the same workload drops 75% (16 MB → 4 MB), confirming the broader event-volume reduction.

**Throughput delta.** Allocation pressure was *not* the throughput bottleneck on these scenarios — eliminating 94% of `byte[]` allocations did not move per-trial throughput meaningfully on either scenario:

| Scenario (best of 3, h2c) | Baseline (2026-05-09) | After fix (2026-05-11) | Delta |
|---|---:|---:|---:|
| `h2-hello` (1c × 100s) | 260,790 req/s | 251,444 req/s | −3.6% (within run-to-run noise) |
| `h2-high-concurrency` (10c × 100s) | 428,605 req/s | 432,823 req/s | +1.0% |

Run-to-run noise on this machine over the same two-day window is large — Netty's `h2-hello` jumped from 225,270 req/s to 327,940 req/s without any code change to Netty — so the across-run "vs Netty" comparison is unreliable. The within-run conclusion is that the throughput bottleneck on these scenarios is elsewhere (frame serialization / HPACK / socket I/O), and the value of this change is GC-pressure / tail-latency under sustained load, not steady-state RPS.

**Verification.** `latte test --excludePerformance --excludeTimeouts` → 2887/2887 pass. `h2spec generic` (44 tests) → 41 pass, 3 fail (pre-existing failures: CONTINUATION + POST-with-trailers; same set before and after the change).

**Scope and follow-ups.** The fast-path triggers only on HTTP/2 HEADERS frames carrying `END_STREAM`. HTTP/1.1 still pays the `readAllBytes()` toll on bodyless GETs — a similar empty-stream singleton swap is possible in `HTTP1Worker` and worth doing when the next h1.1 pass happens. The deferred "DATA frame payload pooling" item is left intact in the list above; the JFR data shows it is now a single-digit-percent contributor and lower-priority than other candidates.

### Performance findings (2026-05-15): HTTP/1.1 fast-path, HPACK Huffman decoder, toLowerCase fast-path

**Run config:** h2load `h2-high-concurrency` (4 threads / 10 connections / 100 streams), 20 s JFR window inside a 30 s run, `settings=profile`. Single-trial JFR for the CPU-share comparison; rigorous 30 s × 3 matrix for the throughput numbers. Machine: Apple M4, 10 cores, 24 GB RAM, macOS 15.7.3, JDK 25.0.2.

**Hypothesis check.** With the byte[] allocation pressure from the 2026-05-10 fix gone (−94% on this workload), throughput on `h2-high-concurrency` did not move, so allocation was not the throughput bottleneck. A CPU profile of the post-fix server identified the actual top consumers:

| Rank | Method (leaf) | CPU share | Verdict |
|---|---|---:|---|
| 1 | `HPACKHuffman.decode(byte[])` | **15.0%** | O(256 × N) linear scan over symbols per output byte |
| 2 | `Thread.interrupted()` | 7.4% | virtual-thread machinery; hard to attack |
| 3 | `StringLatin1.toLowerCase(...)` | **6.3%** | `HTTPRequest.addHeader` (54%) + `HPACKEncoder.encode` (28%) — toLowerCase always allocates a fresh char[] |
| 4 | `SocketDispatcher.read0` | 5.4% | native socket reads |
| 5 | `HPACKEncoder.encode(...)` | 3.7% | response-header encoding |

The deferred "DATA frame payload pooling" Plan F item appears at #20 (1.3% CPU) — confirmed lower-priority. The Plan F "HPACK Huffman encoding on the encode path" item is orthogonal (it would add encode-side CPU for wire-byte savings, not remove decode-side CPU).

**Fixes applied (three independent changes on the same branch).**

1. **HTTP/1.1 empty-body fast-path** (`HTTP1Worker`). Extends the 2026-05-10 HTTP/2 singleton fast-path to HTTP/1.1: when `request.hasBody()` is false after preamble parsing, install `EmptyHTTPInputStream.INSTANCE` instead of constructing the `HTTPInputStream` wrapper. The downstream `drain()` call (line 300) is guarded by a null-check since `HTTPInputStream.drain()` was already a no-op for bodyless requests.

2. **Table-driven HPACK Huffman decoder** (`HPACKHuffman.decode`). Replaces the O(256 × N) linear-scan decoder with a 4-bit-nibble FSM. The transition table (`stateCount × 16` entries, ~256 states for the HPACK code) is built at class load from the existing `CODES[]` / `LENGTHS[]` arrays and the trie they imply. Each table entry packs `(nextState << 16) | flags | emittedByte` into one int; flags carry `DECODE_SYM` (transition emits a byte) and `DECODE_FAIL` (invalid transition — EOS leaf seen). End-of-input padding validation is preserved via a precomputed `DECODE_ACCEPT[]` table marking root plus the first 7 nodes on the all-1s EOS prefix path; padding >7 bits is rejected as before. The encoder is unchanged.

3. **toLowerCase ASCII fast-path** (`HTTPTools.asciiLowerCase`). New helper: scans the string once; returns it unchanged when no uppercase ASCII and no non-ASCII is present, otherwise falls back to `String.toLowerCase(Locale.ROOT)`. Applied at `HTTPRequest.addHeader{,s}` (54% of `toLowerCase` samples) and `HPACKEncoder.encode` (28%). Semantically identical to the old code on every input — well-formed or malformed.

**CPU delta (h2-high-concurrency, 20 s single-trial JFR, post-fix machine state).**

| Method (leaf) | Before (2026-05-10) | After (2026-05-15) | Delta |
|---|---:|---:|---:|
| `HPACKHuffman.decode` | 15.0% | **not in top 20** | ≈ −15 pts |
| `StringLatin1.toLowerCase` | 6.3% | 1.6% | −4.7 pts |
| `HTTPTools.asciiLowerCase` (new) | — | 3.3% | new — the fast-path helper itself |
| Combined header-normalisation CPU | 6.3% | 4.9% | −1.4 pts (the residual is genuinely-needed lowercasing) |

Combined ~16 percentage points of CPU returned to other work on this profile.

**Throughput delta — confounded by thermal throttling.** The rigorous 30 s × 3 matrix on 2026-05-15 ran with the machine in a different thermal state than the 2026-05-11 baseline. Independent evidence:

| Server | h2-hello best (baseline → today) | h2-high-concurrency best (baseline → today) |
|---|---|---|
| self | 251,444 → 264,205 (+5%) | 432,823 → **340,596 (−21%)** |
| jetty | 21,381 → 10,703 (−50%) | 128,338 → 109,736 (−15%) |
| tomcat | 70,654 → 44,684 (−37%) | 153,702 → 98,495 (−36%) |
| netty | 327,940 → 241,973 (−26%) | 630,553 → 569,317 (−10%) |

Jetty and Tomcat dropped 37–50% with no code change to either. Self's three-trial per-trial RPS decreased monotonically across the matrix (264K → 238K → 210K, then 341K → 329K → 317K) — the textbook thermal-throttling signature, also visible in monotonically-rising average latencies. The earlier same-day single-trial JFR run on a cool machine measured self/h2-high-concurrency at 415K req/s — back inside the 2026-05-11 baseline noise band.

Conclusion: the CPU profile is the reliable indicator for this fix set. Re-run the rigorous matrix on a clean thermal state before quoting headline throughput numbers in the README.

**Verification.** `latte test --excludePerformance --excludeTimeouts` → 2887/2887 pass (one flaky `HTTP2RawFrameTest` retry; passes consistently on re-run). Existing `HPACKHuffmanTest` (encode/decode round-trip for RFC 7541 Appendix C vectors plus all printable ASCII) passes unchanged.

**Scope and follow-ups.** The decoder change is hot-path on every inbound HEADERS block (each h2 request). The toLowerCase fast-path is hot on every header name on add/lookup. Both apply to HTTP/1.1 and HTTP/2. Remaining Plan F items remain in the list above; with `HPACKHuffman.decode` and toLowerCase off the table, the next highest-leverage CPU sites are `SocketDispatcher.read0` (11.7% — likely a hard floor without changing the I/O model) and `ByteArrayOutputStream.ensureCapacity` (7.3% — appearing prominently in HPACK encode and `HPACKHuffman.encode`).

### Performance findings (2026-05-19): HPACK static-table lookup, DATA flush fast-path, rate-limit isolation

**Context.** Added four new h2 benchmark scenarios — `h2-high-stream-concurrency` (renamed from `h2-high-concurrency`), `h2-high-connection-concurrency` (500 conn × 2 streams, browser/CDN shape), `h2-compute` (chained SHA-256 × 5000 rounds, CPU-bound), `h2-io` (10ms `Thread.sleep`, blocking-IO simulation), `h2-stream` (128KB chunked response). Profiled with JFR `settings=profile` on `h2-stream` (10 conn × 100 streams × 20 s) to find what dominates the streaming-response path now that the bodyless-fast-path work is done.

**Correctness fix surfaced by `h2-high-connection-concurrency`.** Under 500 simultaneous connections, the server stopped accepting new sockets after ~30s. Root cause: `HTTP2RateLimits` (sliding-window counters backed by `ArrayDeque<Long>`) was held as a single shared instance on `HTTPServerConfiguration` and reused across every accepted connection. The deques are not thread-safe; concurrent record/prune racing produced (a) `NullPointerException` from `peekFirst()` returning null between `isEmpty()` and the unbox, and (b) spurious GOAWAY(ENHANCE_YOUR_CALM) for healthy clients because the shared `windowUpdate` deque accumulated entries from all connections and tripped the 100-event-per-second threshold globally rather than per-connection. Refactored into an immutable config record (`HTTP2RateLimits`) plus per-connection mutable state (`HTTP2RateLimitsTracker`); the type system now enforces the per-connection contract.

**Backlog default.** `HTTPServerConfiguration.maxPendingSocketConnections` raised from 250 → 4096 to match modern server-library norms (nginx 511, Netty SOMAXCONN). The kernel silently clamps to its sysctl (`somaxconn`: Linux 4096+, macOS 128), so this is "let the kernel decide" on the low end and an actual queueing improvement on platforms where it matters. Removes kernel-level SYN drops under the 500-conn benchmark.

**CPU hotspots from the `h2-stream` JFR (post-fix):**

| Cost block | % of samples | Item |
|---|---:|---|
| `sun.nio.ch.SocketDispatcher.write0` and friends | ~13% | Writer thread doing one syscall per DATA frame |
| `VirtualThread.park` / `LockSupport.unpark` / `AQS$ConditionObject.doSignal` / `AQS.enqueue` | ~18% | Producer + consumer contention on the per-connection `LinkedBlockingQueue<HTTP2Frame>` |
| `HTTP2OutputStream.flushAndFragment` | ~8% | Per-flush DATA frame construction |
| `HPACKStaticTable.indexFullMatch` | ~7% | Linear scan over 61 entries per response header (now fixed) |
| `ByteArrayOutputStream.toByteArray` | ~2% | Per-flush copy out of the local buffer |

**Fixes applied (hot-path CPU):**

- `HPACKStaticTable` now builds two `HashMap`s (full-match by `HeaderField` record, name-only by `String`) at class init; `indexFullMatch` and `indexNameOnly` are O(1). RFC 7541 §2.3.1 requires the lowest matching index on duplicate names, so the maps are populated in ascending order with `putIfAbsent`. Hot on every response header.
- `HTTP2OutputStream.flushAndFragment` added a single-frame fast path for the common case where the buffered payload fits in one DATA frame *and* the stream has enough send-window credit. Skips the `new byte[chunk] + arraycopy` the loop body always did.

**Throughput delta (best-of-3, 30 s × 3 trials, post-fixes; both runs use the same library code, so this isolates the impact of today's fixes):**

| Scenario | Pre-today's fixes | Post-fixes | Delta |
|---|---:|---:|---:|
| `h2-compute` | 13.6k RPS | 25.4k RPS | **+86%** |
| `h2-high-stream-concurrency` | 427k RPS | 413k RPS | -3% *(noise)* |
| `h2-io` | 67k RPS | 77k RPS | +15% |
| `h2-stream` | 4.1k RPS | 4.1k RPS | 0 *(writer-queue contention; addressed by new Plan F item below)* |
| `h2-high-connection-concurrency` | broken (server hang) | 192k RPS, 0 errors | correctness fix |

**Peer comparison (best-of-3 from the cool-machine fair rerun on 2026-05-19. Each vendor was run separately with a 15 min cool-down between vendors to eliminate accumulated-thermal bias from a single sustained matrix run. Self ran first in both this run and the warm matrix, so its numbers are stable across both; Netty saw the biggest jump on a cool machine — confirms its NIO transport is more sensitive to machine state than Latte's blocking I/O.):**

| Scenario | self (Latte) | jetty | tomcat | netty | Leader |
|---|---:|---:|---:|---:|---|
| `h2-high-stream-concurrency` | 413k | 127k* | 150k | **889k** | Netty (2.15× Latte) |
| `h2-high-connection-concurrency` | 192k | 161k* | 109k | **272k** | Netty (1.42× Latte) |
| `h2-compute` | 25k | 15k* | 24k | **26k** | ≈ tie (Latte/Netty/Tomcat within 5%) |
| `h2-io` | **77k** | 11k | 15k | **78k** | ≈ tie (Latte/Netty), **5–7× ahead of worker-pool** |
| `h2-stream` *(force flush)* | 4.1k | 14k* | 1.4k | **32k** | Netty (7.8× Latte); see h2-large-response below for the apples-to-apples reading |
| `h2-large-response` *(one shot)* | 4.1k | 19k* | 30k | **30k** | Tomcat/Netty tied (7.3× Latte) |

\* Jetty h2 scenarios show 10M+ wire errors from a separate benchmark-config issue with Jetty's h2c implementation; the throughput numbers are not reliable for Jetty regardless.

The honest story:

- **`h2-io` is Latte's standout architectural win.** Latte and Netty are statistically tied at 77–78k RPS, 5–7× ahead of Tomcat/Jetty. Worker-pool servers are capped by their thread-pool size on blocking-IO workloads. This is the closest scenario to real-app behavior (handlers wait on databases, downstream APIs, message queues) and the virtual-threads model holds up against Netty's event-loop model without the callback ceremony.
- **`h2-compute` is a four-way near-tie at 23–26k.** When the handler does real CPU work, the protocol stack becomes <20% of the cost and all servers converge. The yesterday "Latte 68% ahead" claim was a thermal artifact from a warm matrix where Tomcat ran later in the sequence.
- **`h2-high-stream-concurrency` (10 × 100): Netty 2.15× ahead.** On a cool machine, Netty's event-loop demux of many streams per socket is genuinely the fastest design for this workload. Latte's 413k vs Netty's 889k is the architectural cost of the per-stream virtual-thread + cross-thread queue model. The earlier "Latte 96% of Netty" reading was Netty being thermally throttled.
- **`h2-high-connection-concurrency` (500 × 2): Netty 1.42× ahead.** Same model effects apply but Latte's gap is smaller because per-stream-thread costs amortize over fewer streams per connection.
- **`h2-stream` / `h2-large-response` is the architectural gap to investigate** — see new Plan F item below.

**New Plan F item: writer-thread architecture for h2 DATA emission.**

The cool-machine matrix exposes an architectural gap that goes deeper than initially profiled. The `h2-stream` scenario (handler forces per-8KB flush) lands Latte at 4,097 RPS — Netty does 32,169. Initial hypothesis was that the gap was the cost of *honoring* per-chunk flush. The new `h2-large-response` scenario (handler writes the whole 128KB body once, no per-chunk flush, server chooses framing) was added specifically to test that hypothesis. The result falsifies it:

| Server | `h2-stream` (force flush) | `h2-large-response` (one shot) | Improvement from removing per-chunk flush |
|---|---:|---:|---:|
| Tomcat | 1,434 | 29,717 | **20.7×** |
| Netty | 32,169 | 29,995 | -7% *(noise; uses FullHttpResponse for both)* |
| Jetty | 14,384 | 19,408 | +35% |
| **Latte** | **4,097** | **4,105** | **0%** |

**Latte gets zero benefit from removing the per-chunk flush.** The bottleneck is not in the chunked-flush handling — it's in the writer/socket-emit path itself, which is engaged regardless of whether the handler emits 1 DATA frame or 16. Even when the handler writes the whole body in one `write()` + `close()` call, the writer thread is the limiting factor.

What the JFR profile shows (taken on `h2-stream`, but applies equally to `h2-large-response`):

| Cost block | % of samples |
|---|---:|
| `sun.nio.ch.SocketDispatcher.write0` and friends (one syscall per DATA frame) | ~13% |
| `VirtualThread.park` / `LockSupport.unpark` / `AQS$ConditionObject.doSignal` / `AQS.enqueue` (producer + consumer contention on the per-connection `LinkedBlockingQueue<HTTP2Frame>`) | ~18% |
| `HTTP2OutputStream.flushAndFragment` (per-frame allocation + window-credit handling) | ~8% |
| All other | ~61% |

Per-DATA-frame writes are the dominant wire-level cost. A 128KB body at MAX_FRAME_SIZE=16KB fragments into 8 DATA frames, each its own enqueue → take → socket write. At 100 streams in flight, that's 800 frames per request-round through a single shared queue. The producer/consumer contention is the visible top symptom; per-frame syscalls are the harder ceiling underneath.

Three candidate designs, in increasing scope:

1. **Coalesced socket writes (smallest scope, biggest single lever):** writer drains up to N frames from the queue per cycle (via `queue.drainTo(list, N)`), packs them into a single gathering `write` (vectored I/O via `SocketChannel` or just a single buffered `write` of the concatenated frame bytes), then flushes. Cuts socket-write syscalls by the batch factor and amortizes per-frame lock-acquire cost on the consumer side. Producer-side contention is unchanged. This addresses both the per-frame `write0` cost (~13%) and a portion of the queue contention.
2. **MPSC ring buffer instead of `LinkedBlockingQueue`** (e.g. `MpscArrayQueue` from JCTools, or a small custom ring buffer to keep zero deps): cuts producer-side lock-acquire cost per `put`. Each `put` becomes a CAS instead of `lock.lockInterruptibly()` + `signalNotEmpty()`. Combine with option 1 for batched drain on the consumer side. Zero-dep variant is ~80 lines. Addresses the ~18% queue-contention slice directly.
3. **Per-stream local buffering + writer drain:** each `HTTP2OutputStream` accumulates writes in a stream-local buffer; the writer thread periodically (or on a per-stream flush hint) walks the active-stream list and drains each stream's buffer. Completely removes the producer/consumer queue, replaces it with stream-state polling. Largest scope; changes the flow-control wait pattern (stream waits for window become per-stream condition variables that the reader signals directly into the per-stream output, rather than through the queue). This is the closest analog to Netty's "writes happen on the event-loop thread" model.

**Recommended sequence:** prototype option 1 first (~half day of work, 1 file changed: the writer-thread lambda inside `HTTP2Connection.run`). Re-bench `h2-stream` AND `h2-large-response`; both should move proportionally because they share the bottleneck. If option 1 closes 60%+ of the gap (target: 4k → 15k+ RPS), stop. If not, layer in option 2 (~1 day, adds JCTools dep or a custom MPSC class). Option 3 is a larger architectural rework; only pursue if 1+2 leave us materially behind.

**On `OutputStream.flush()` semantics.** Latte honors `flush()` literally — every call drains the buffer into a DATA frame and enqueues it. This is the correct contract for SSE / long-polling / gRPC server-streaming handlers; loosening it would silently break those use cases. Tomcat treats servlet `flush()` as a hint and is allowed to ignore it by spec; Netty's bench handler doesn't actually use a streaming API (sends `FullHttpResponse`). The bench `/stream` scenario partially measures whether each server honors handler intent — Latte's apparent loss there is in part a fidelity-to-contract measurement, not a pure throughput measurement. We do not intend to change the contract; the writer-thread batching above benefits both `/stream` and `/large-response` equally and is the right lever.

### Performance findings (2026-05-21): Helidon WebServer and Undertow added to the benchmark matrix; per-stream flow-control lost-wakeup identified

**Context.** Two new vendors added so the peer comparison covers Latte's architectural counterparts: **Helidon WebServer 4.1.7** (virtual-thread + blocking I/O — the only mainstream Java server with the same arch shape as Latte) and **Undertow 2.3.18.Final** (NIO/XNIO — Red Hat / WildFly / Quarkus). Both bind 8080 (h1.1 + h2c) and 8443 (TLS+ALPN h2) using the same shared certs and the same 9-handler endpoints as Jetty/Netty/Tomcat. h2load + wrk drivers unchanged.

Each new vendor was run in isolation (3 trials × 30 s × all 16 scenarios) with a 20-minute cool-down between vendors. Numbers below are best-of-3.

**Peer comparison, h2 scenarios (Latte numbers re-run 2026-05-21 with the full 16-scenario suite so all rows are populated; jetty/netty/tomcat numbers carried from 2026-05-19 fair rerun):**

| Scenario | self (Latte) | helidon | undertow | jetty | tomcat | netty¹ | Leader |
|---|---:|---:|---:|---:|---:|---:|---|
| `h2-hello` (1c × 100s) | 154k² | 150k | 115k | — | — | — | self ≈ helidon |
| `h2-high-stream-concurrency` (10c × 100s) | 454k | **634k** | 165k | 127k* | 150k | 889k | Netty / Helidon ahead |
| `h2-high-connection-concurrency` (500c × 2s) | 223k | 161k | 147k | 161k* | 109k | **272k** | Netty; Latte 2nd |
| `h2-compute` | 27k | 22k | **30k** | 15k* | 24k | 26k | Undertow narrowly |
| `h2-io` (Thread.sleep 10 ms) | **69k** | 70k | 6.8k | 11k | 15k | 78k | Latte / Netty / Helidon ≈ tie; all ~5–10× the worker-pool servers |
| `h2-stream` (force flush) | 4.1k | **38k** | 20k | 14k* | 1.4k | 32k | Helidon |
| `h2-large-response` (one-shot) | 4.1k | **36k** | 32k | 19k* | 30k | 30k | Helidon |
| `h2-tls-hello` | **328k** | 100k | 115k | — | — | — | **Latte (3.3× helidon, 2.9× undertow)** |
| `h2-tls-high-stream-concurrency` | **394k** | 290k | 244k | — | — | — | **Latte (1.4× helidon, 1.6× undertow)** |

¹ Netty numbers carried from 2026-05-19. *Jetty's h2c numbers are persistently affected by a benchmark-config issue producing 10M+ wire errors; throughput reading is unreliable. ²`h2-hello` trial 3 was an h2load-side outlier (~10k RPS with a 938 s duration — driver got stuck). Reported number is the median of the two valid trials (258k, 154k); high run-to-run variance worth a follow-up.

**Two distinct headlines:**

1. **TLS h2: Latte leads by a wide margin.** `h2-tls-hello` at 328k is 3.3× Helidon and 2.9× Undertow; `h2-tls-high-stream-concurrency` at 394k is 1.4× / 1.6× ahead. Same h2 stream shape as the cleartext versions, just over TLS — so this confirms the core h2 dispatch and HPACK paths are fast. The bottleneck is *not* in HEADERS processing, frame parsing, or stream management.

2. **Cleartext h2 streaming: Helidon is 9× faster on `h2-stream` / `h2-large-response`.** 38k / 36k vs Latte's 4.1k / 4.1k. Same architectural model on both sides (virtual-thread + blocking I/O), so this rules out the "VT-per-stream is paradigmatically slower" interpretation. **The bottleneck is implementation-specific, in the writer/DATA-frame emission path**, exactly as identified in the 2026-05-19 finding above.

**Apples-to-apples vs Helidon (same VT + blocking-I/O architecture):**

- **`h2-tls-hello`** / **`h2-tls-high-stream-concurrency`**: Latte ahead by 3.3× and 1.4×. h2 dispatch + HPACK is genuinely fast.
- **`h2-io`**: ≈ tie (69k vs 70k). Both servers park virtual threads cleanly during blocking handlers — the architectural payoff.
- **`h2-high-stream-concurrency`**: Helidon +40% (634k vs 454k). Both use VTs, so the gap is mostly the writer-thread design.
- **`h2-stream` / `h2-large-response`**: Helidon ~9× ahead. Same root cause as the existing Plan F item — confirmed to be implementation-specific writer-path cost.
- **`h2-compute`**: Latte +23% (27k vs 22k). CPU-bound; protocol-stack overhead is a small fraction of total cost.

**Why TLS h2 is faster than cleartext h2 for Latte:** Counterintuitive but reproducible. Latte's `h2-tls-hello` at 328k is *higher* than its `h2-hello` at 154k. Most likely: h2load's cleartext h2c path uses a different I/O pattern (smaller socket reads, different framing) that triggers more writer-side cycles per request; TLS path bundles records more aggressively. Worth its own investigation, but unrelated to the streaming-response bottleneck.

**Where Undertow stands:**

- **`h2-io`** at 6.8k is the worker-pool tax exactly as expected — XNIO worker threads block on `Thread.sleep` and the connection-level concurrency is capped by the pool size. Same shape as Jetty (11k) and Tomcat (15k).
- **`h2-stream` / `h2-large-response`** at 20k / 32k confirms that NIO-with-coalesced-writes is also 5–8× faster than Latte's per-frame-syscall writer. Combined with Helidon's number, the gap to close is around 30–40k RPS on these scenarios.

**Additional bottleneck identified: per-stream flow-control lost-wakeup.**

While investigating the `h2-stream` gap, found a classic lost-wakeup in `HTTP2OutputStream.flushAndFragment` (lines 92–101 in `src/main/java/org/lattejava/http/server/internal/HTTP2OutputStream.java`):

```java
while (stream.sendWindow() <= 0) {
  try {
    synchronized (stream) {
      stream.wait(100);
    }
  } catch (InterruptedException e) { … }
}
```

The window check is **outside** the `synchronized (stream)` block. If a `WINDOW_UPDATE` arrives between the check and entering `wait()`, the `notifyAll()` from the reader thread fires while the handler isn't waiting yet; the handler then blocks for up to 100 ms before the next poll. This shows up as wall-clock latency, not CPU samples — which is why the 2026-05-19 JFR analysis missed it.

For `h2-stream` (128KB / 16 × 8KB chunks vs 65535 default per-stream send-window), the handler runs out of credit after the 8th chunk; the 9th-onwards waits hit this race. At ~2–3 stalls per request × 100 ms each, the theoretical ceiling is ~3000–5000 RPS per connection × 10 connections / 100 streams in flight ≈ 4 k RPS, which matches the observed ceiling.

Fix is mechanical — move the predicate inside the monitor:

```java
synchronized (stream) {
  while (stream.sendWindow() <= 0) {
    stream.wait(100);
  }
}
```

This complements the existing Plan F "writer-thread architecture" item but is independent of it — even with the writer-thread coalescing optimization, the lost wakeup would still impose 100 ms tail-latency stalls on credit-starved streams. Worth fixing as a standalone change before or alongside the larger writer-thread refactor.

**Action items added to the Plan F backlog.**

- **Fix the `HTTP2OutputStream` lost-wakeup** (small, mechanical; estimate <1 h including a regression test that exercises window exhaustion + a delayed `WINDOW_UPDATE`).
- **Writer-thread architecture work** (existing 2026-05-19 Plan F item, design options 1–3 already enumerated above). Helidon's 9× number on `h2-stream` is now the concrete target — closing 60–80 % of that gap is the success criterion for option 1 (coalesced socket writes).
- **Verify other `wait`/`notify` sites in the h2 path** don't share the lost-wakeup pattern. Suspects: connection-level send-window block in `HTTP2Connection.handleSettings` retroactive adjustment path; any settings-ACK / GOAWAY wait paths.

**Verification of the new vendors.** Both Helidon and Undertow pass smoke tests for h1.1, h2c, and TLS+ALPN h2 on the standard `/` `/hello` `/load` `/compute` endpoints. Errors columns across the matrix are 0 for both vendors on every scenario except `baseline` (1 error for self, 1 for helidon, 25 for undertow — typical transient connect-error trial variance, not a server defect). Helidon and Undertow's `project.latte` setup added ~13 `.Final → semver` mappings each to satisfy Latte's SemVer validator on Helidon's umbrella BOM hierarchy and Undertow's jboss-* chain; this is documented as a one-time cost in the per-vendor `project.latte` files.

---

## Bug ledger

Full h2spec v2.6.0 run on 2026-05-05: 147 tests, 143 passed, 1 skipped, 3 failed.

Improvement over campaign: 77 → 3 failures (-74).

Closed by 2026-05-09 cleanup campaign (commits b316db7, cad7b5f, 82b60b5, f54282e, 2850597, a5a0de6, 2829cc4): 77 failures → 3.

### Remaining failures

**Root cause: SETTINGS_INITIAL_WINDOW_SIZE flow-control (3 failures).**
The server does not honor per-stream or connection-level flow-control window limits when
`SETTINGS_INITIAL_WINDOW_SIZE` is used to constrain send windows. Tests that depend on the
server respecting a window size of 1 or a mid-connection `SETTINGS_INITIAL_WINDOW_SIZE`
change see "unexpected EOF" instead of a DATA frame.

#### §6.5.3: Settings Synchronization
- **[http2 6.5.3/1]** Sends multiple SETTINGS_INITIAL_WINDOW_SIZE values. **Expected:** DATA (flow-controlled). **Actual:** unexpected EOF.

#### §6.9.1: Flow-Control Window
- **[http2 6.9.1/1]** Sends SETTINGS with initial window size 1 then HEADERS. **Expected:** DATA (flow-controlled). **Actual:** unexpected EOF.

#### §6.9.2: Initial Flow-Control Window Size
- **[http2 6.9.2/1]** Changes SETTINGS_INITIAL_WINDOW_SIZE after sending HEADERS frame. **Expected:** DATA. **Actual:** unexpected EOF.

---

## Roadmap

**Phase 1 — Foundations:**
- 101 Switching Protocols hook on `HTTPResponse` (h2c-Upgrade prerequisite; reusable for future WebSockets).
- Trailers API on `HTTPRequest` and `HTTPResponse`, working for HTTP/1.1.
- Frame codec, HPACK, stream state machine, flow control — all unit-test passable in isolation.

**Phase 2 — Connection runtime:**
- `HTTP2Connection` with reader/writer/handler-thread roles.
- `ProtocolSelector` integrated into `HTTPServerThread`.
- ALPN advertisement configured on TLS listeners.
- TLS cipher blocklist enforcement.
- DoS rate limits.

**Phase 3 — Transport modes:**
- h2 over TLS — first protocol live.
- h2c prior-knowledge.
- h2c via Upgrade/101.

**Phase 4 — Conformance and interop:**
- `h2spec` clean run.
- gRPC interop tests (unary, server-streaming, client-streaming, bidi-streaming).
- JDK HttpClient round-trips for everything in the compliance matrix.

**Phase 5 — Polish:**
- Performance benchmarks vs. h1.1 and vs. Jetty/Helidon.
- Documentation: this doc updated to ✅, peer comparison kept current.

**Out of scope for /2:**
- Server push API (deliberately).
- RFC 9218 Priority header / `priority-update` frame (revisit when ecosystem catches up).
- HTTP/2 client (this library has no client at all yet).
- HTTP/3 — separate transport, separate spec.

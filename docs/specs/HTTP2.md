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
| h2c via `Upgrade`/101 (cleartext) | ✅ | Opt-in via `withH2cUpgradeEnabled` (default-off). RFC 9113 deprecated the Upgrade flow in favor of prior-knowledge; default-off avoids conflicts with JDK `HttpClient`'s eager `Upgrade: h2c` on HTTP/1.1 connections. Retained for back-compat with older clients. — `HTTP2H2cUpgradeTest` |
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
| Header-name validation | ⚠️ | RFC 9113 §8.2 — HPACK decoder writes lowercase + ASCII; explicit validation (reject non-lowercase tchar → PROTOCOL_ERROR) deferred to Plan F. |
| Header-value validation | ⚠️ | HPACK decoder writes ASCII; explicit bare CR/LF/NUL rejection deferred to Plan F. |
| `MAX_HEADER_LIST_SIZE` enforcement | ✅ | Cumulative byte budget enforced on the HEADERS+CONTINUATION accumulator in `HTTP2Connection.handleHeadersFrame` and `handleContinuationFrame`. GOAWAY(ENHANCE_YOUR_CALM) when exceeded. — `HTTP2SecurityTest.continuation_flood_triggers_goaway` |

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

---

## 6. Pseudo-headers and request mapping (RFC 9113 §8.3)

| Feature | Status | Notes |
|---|---|---|
| `:method`, `:scheme`, `:path`, `:authority` required | ✅ | All four must be present and exactly once. Validation order: pseudo-headers must precede regular headers. |
| Connection-specific headers forbidden (`Connection`, `Keep-Alive`, `Transfer-Encoding`, `Upgrade`, `Proxy-Connection`) | ⚠️ | Stream error PROTOCOL_ERROR. Runtime check incomplete; Plan F polish. |
| Uppercase in header name forbidden | ⚠️ | Stream error PROTOCOL_ERROR. Runtime check incomplete; Plan F polish. |
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
| Header-name/value validation | ⚠️ | Reuses `HTTPTools.isTokenCharacter` and `isValueCharacter`. Explicit enforcement deferred to Plan F. |
| Response-splitting defense | ✅ | Reuses choke point at `HTTPResponse.setHeader/addHeader/sendRedirect/Cookie` (audit Vuln 4 fix). Implicit via existing h1.1 defense. |

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
| CONTINUATION flood mitigation | ⚠️ (partial) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Configurable concurrency cap | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Configurable initial window | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Virtual-thread per stream | ✅ | ⚠️ (config) | ⚠️ (config) | ❌ (event loop) | ❌ | ⚠️[^nima] |

[^nima]: Helidon Níma uses virtual threads as carrier threads for its event loop, not strictly virtual-thread-per-stream the way latte-java does. End behavior is similar; the architectural shape differs. Worth a footnote so the comparison is honest.

The last row is our differentiator. Pure virtual-thread + blocking-I/O code is unique among Java performance leaders.

---

## Performance summary

Benchmark suite: `benchmarks/h2load-scenarios/hello.sh` (sample). Full benchmark run gated on user approval — see `benchmarks/h2load-scenarios/README.md`.

Performance follow-ups deferred until a baseline run is collected:
- HPACK Huffman encoding on the encode path (decoder uses Huffman; encoder writes literal-only for v1 determinism)
- HEAD method handling on h2 (current code rebuilds the request for HTTP/1.1; h2 can short-circuit)
- DATA frame payload pooling (currently allocates a `byte[]` per frame in the writer queue)
- HPACKDecoder.decodeInt long-pack already done; consider similar packing on encode path
- Connection-level WINDOW_UPDATE strategy (current: per-DATA-frame; consider replenish-when-half-empty across connection)

---

## Bug ledger

No open bugs. The following production bugs were found by gRPC interop testing and fixed in commit `b316db7`:

- **HTTP2FrameReader — PADDED and PRIORITY frame prefix stripping (RFC 9113 §6.2).** gRPC Netty sends HEADERS with the PRIORITY flag set (5 extra bytes before the HPACK block), which caused the HPACKDecoder to read past the end of the fragment and throw ArrayIndexOutOfBoundsException. DATA frames also get PADDED stripping. **Fixed.**
- **HTTP2OutputStream — trailer emission ordering (RFC 9113 §8.1).** When trailers follow the response body, the final DATA frame must omit END_STREAM so the subsequent HEADERS (trailers) frame can carry it instead. **Fixed.**

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

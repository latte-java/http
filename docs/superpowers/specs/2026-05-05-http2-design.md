# HTTP/2 Support — Design

**Date:** 2026-05-05
**Branch:** `robotdan/http2`
**Status:** Brainstormed; awaiting review before writing implementation plan

---

## Motivation

The latte-java HTTP server is HTTP/1.1 only. To be a serious choice in the Java ecosystem alongside Jetty, Tomcat, Netty, Undertow, and Helidon Níma, we need HTTP/2. Beyond protocol parity, HTTP/2 unlocks gRPC interop — a significant capability gap today — and matches what every modern client (browsers, JDK `HttpClient`, mobile SDKs) prefers when given the choice.

The architectural opportunity is that our virtual-thread + blocking-I/O model maps cleanly onto HTTP/2's per-stream concurrency. Where Jetty and Netty ship complex async/event-loop machinery to multiplex streams onto a fixed thread pool, we get to spend a virtual thread per stream and write code that reads top-to-bottom. Helidon Níma is the closest peer in this regard.

This document is the implementation blueprint for the work. It is dated and scoped to delivering HTTP/2 support; once shipped, the always-current reference will be `docs/specs/HTTP2.md`.

---

## Decisions made during brainstorming

| Decision | Choice |
|---|---|
| Transport surface | Full: h2 over TLS-ALPN, h2c prior-knowledge, h2c via `Upgrade`/101 |
| 101 Switching Protocols hook | In scope (prerequisite for h2c-Upgrade; reusable for future WebSockets) |
| Server push (PUSH_PROMISE) | Out of scope. Advertise `SETTINGS_ENABLE_PUSH=0`. Reject inbound PUSH_PROMISE as PROTOCOL_ERROR. |
| Trailers in API (response) | Implement for both h1.1 and h2. Closes existing ❌ in HTTP1.1.md §3. |
| Trailers in API (request) | Implement symmetrically for both protocols. |
| gRPC interop | Explicit goal. Round-trip test against `grpc-java` for unary, server-streaming, client-streaming, bidi-streaming. |
| Default for `enableHTTP2` (TLS) | `true` |
| Default for `enableH2cUpgrade` (cleartext) | `true` |
| Default for `enableH2cPriorKnowledge` (cleartext) | `false` (opt-in) |
| Architectural approach | Sibling workers (`HTTP1Worker`, `HTTP2Connection`) with extracted shared utilities. No common abstract class. |
| Doc shape | Two: this dated blueprint + long-lived `docs/specs/HTTP2.md` |

---

## Architectural overview

The accept loop in `HTTPServerThread` does not change: one accept thread per listener, virtual thread per accepted socket. What changes is what the per-connection virtual thread does *after* the socket is accepted (and TLS handshaken, when applicable).

A small `ProtocolSelector` runs first and dispatches to one of two workers:

- `HTTP1Worker` (renamed from today's `HTTPWorker`) — handles HTTP/1.1.
- `HTTP2Connection` — new. Handles HTTP/2.

The selector never crosses back. The single exception is `Upgrade: h2c`, which is handled inside `HTTP1Worker` after a request has been parsed: the worker writes a 101 response, hands its socket to a fresh `HTTP2Connection`, and exits.

### Selector logic

1. **TLS path.** After handshake, read `SSLSocket.getApplicationProtocol()`.
   - `"h2"` → `HTTP2Connection`.
   - `"http/1.1"`, `null`, or `""` → `HTTP1Worker`. (No ALPN extension on the client side counts as null. Historical default for TLS-without-ALPN is HTTP/1.1.)
2. **Cleartext path with `enableH2cPriorKnowledge=true`.** Peek the first 24 bytes (the HTTP/2 connection preface, `PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`). Match → `HTTP2Connection` with the peeked bytes replayed. No match → `HTTP1Worker` with the peeked bytes replayed.
3. **Cleartext path with `enableH2cPriorKnowledge=false`.** No peek. `HTTP1Worker` immediately.

### Class layout (new and renamed)

Under `org.lattejava.http.server.internal/`:

- `HTTP1Worker` — renamed from `HTTPWorker`. Internal class, not part of the public API (the package isn't exported), so the rename is safe. Otherwise mostly unchanged; gains a small `Upgrade: h2c` branch.
- `HTTP2Connection` — new. Owns frame I/O, HPACK state, flow control, stream registry, GOAWAY logic.
- `HTTP2Stream` — new. Per-stream state including the state machine (RFC 9113 §5.1), input pipe (DATA → handler), output queue (handler → frames), reference to the active handler thread.
- `HTTP2FrameReader` / `HTTP2FrameWriter` — frame codec.
- `HPACKEncoder` / `HPACKDecoder` — header coding (own component, hand-written per zero-dependency policy).
- `HTTP2RateLimits` — sliding-window counters for DoS mitigations.
- `ProtocolSelector` — dispatch entry point.
- Small extracted utilities (`ExpectHandler`, `HandlerInvoker` — exact decomposition resolved during implementation) for the few pieces both workers share.

### Reuse boundary

Above the dotted line stays shared:
- `HTTPRequest`, `HTTPResponse`, `HTTPHandler`, `HTTPContext`, `HTTPServerConfiguration`, `HTTPListenerConfiguration`.
- `Expect: 100-continue` handling and validator integration.
- Compression policy (`Accept-Encoding`-driven gzip/deflate of response bodies).
- Header-name/value validation (`HTTPTools.isTokenCharacter`, `isValueCharacter`, response-splitting defenses) — invoked by both workers and by `HPACKEncoder`/`HPACKDecoder`.
- Cookie handling and charset/i18n parsing.

Below the line is protocol-specific:
- Framing (CRLF lines vs. binary frames), HPACK vs. literal headers, message-vs-stream lifecycle, flow control, connection-level multiplexing.

This is Approach A from brainstorming: sibling workers + extracted utilities, no inheritance hierarchy.

---

## Threading model on a single h2 connection

Three virtual-thread roles per connection:

- **Reader (1)** — owns the socket `InputStream`. Blocks on `readFrame()`. Dispatches:
  - HEADERS / CONTINUATION → HPACK decode, build `HTTPRequest`, spawn handler thread.
  - DATA → push bytes onto the target stream's input pipe.
  - SETTINGS → apply, send ACK.
  - PING → enqueue a PONG via the writer.
  - WINDOW_UPDATE → adjust connection or stream send-window.
  - RST_STREAM → cancel the target stream's handler thread.
  - GOAWAY → record peer's last-stream-id, decline new streams, drain.
  - PRIORITY → parse and discard (RFC 9113 §5.3).
  - PUSH_PROMISE → connection error PROTOCOL_ERROR (clients can't push to servers).
  - Unknown frame types → ignore (RFC 9113 §5.5).
- **Writer (1)** — owns the socket `OutputStream` and a bounded outbound frame queue. Pulls frames, applies connection-level + per-stream flow-control accounting, emits bytes. Handler-side output goes through this queue, never directly to the socket. Centralization gives us per-frame ordering, flow-control coordination at one site, and a single point for write-throughput accounting (existing `Throughput` instrumentation drops in).
- **Stream handlers (1 per active stream)** — spawned by the reader on HEADERS-with-`END_HEADERS`. Run `HTTPHandler.handle(request, response)`. Read body via an `HTTP2InputStream` backed by the per-stream pipe; write via an `HTTP2OutputStream` whose target is the writer queue. (These are h2-specific concrete classes; the handler-facing types remain `InputStream`/`OutputStream` so handlers don't see the difference.) End on handler return or on RST_STREAM-induced interruption.

### Backpressure and flow control coordination

The writer holds the connection-level send-window and per-stream send-windows. When a stream's handler tries to write DATA that would exceed available window, the handler's `HTTP2OutputStream.write` blocks (waits on a per-stream condition). When WINDOW_UPDATE arrives, the reader signals the relevant condition, unblocking the handler.

Inbound flow control mirrors: each DATA frame consumed reduces our advertised connection-level and per-stream receive-windows. When either drops below half its initial size, the reader enqueues a WINDOW_UPDATE bringing it back to full. Simple replenish-when-half-empty strategy; matches what most servers do.

---

## Frame layer

Frame format per RFC 9113 §4.1: 9-byte header (`length`, `type`, `flags`, `stream_id` with reserved bit) + payload up to `SETTINGS_MAX_FRAME_SIZE`.

`HTTP2FrameReader.readFrame()` reads the 9-byte header, validates length against `MAX_FRAME_SIZE` (default 16384, configurable up to 16777215), reads payload into a buffer drawn from `HTTPBuffers`, and returns a typed `HTTP2Frame` record. Type-specific decoding is dispatched via a static map. Validation of malformed frames (e.g. RST_STREAM with payload length ≠ 4) emits `GOAWAY(FRAME_SIZE_ERROR)` or `RST_STREAM(PROTOCOL_ERROR)` per RFC.

`HTTP2FrameWriter.writeFrame()` does the inverse, serializing into the socket OutputStream. Frames in the writer queue are drained sequentially; the writer is the only thread that touches the socket OutputStream after the connection preface exchange.

Connection preface handling:
- For h2 over TLS: server sends its initial SETTINGS frame after the TLS handshake completes. Client sends its preface (`PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n`) followed by its SETTINGS. Server receives preface (validates exact match) then continues normally.
- For h2c prior-knowledge: same as TLS path; the 24-byte preface is the first thing on the wire.
- For h2c via Upgrade: server sends its initial SETTINGS *after* writing the 101 response. Client's `HTTP2-Settings` header (base64-encoded SETTINGS payload from the original HTTP/1.1 Upgrade request) is decoded and applied as the peer's initial SETTINGS. The Upgrade'd request becomes implicit stream 1.

---

## HPACK

`HPACKEncoder` and `HPACKDecoder` are hand-written (zero-dependency policy precludes Netty's HPACK). They implement RFC 7541:
- Static table (61 entries).
- Dynamic table sized by `SETTINGS_HEADER_TABLE_SIZE` (default 4096; configurable via `withHTTP2HeaderTableSize`).
- Huffman coding (RFC 7541 Appendix B static code) for indexed-string and literal-string-with-Huffman.
- Six representation forms (indexed, literal-with-incremental-indexing, literal-without-indexing, literal-never-indexed, dynamic-table-size-update).

Decoder enforces `SETTINGS_MAX_HEADER_LIST_SIZE` cumulatively across HEADERS + CONTINUATION frames; over-budget → `RST_STREAM(PROTOCOL_ERROR)` and stream rejection (or connection error if the violation predates stream identification, e.g. invalid pseudo-header).

Decoder reuses `HTTPTools.isTokenCharacter` for header-name validation (RFC 9113 §8.2 mandates lowercase + tchar; we tighten to "lowercase tchar" in the decoder). Decoder reuses `HTTPTools.isValueCharacter` for header-value validation. Pseudo-header validation:
- Exactly the four request pseudo-headers `:method`, `:scheme`, `:path`, `:authority`. No others. None duplicated.
- All pseudo-headers MUST appear before any regular header.
- Connection-specific headers (`Connection`, `Keep-Alive`, `Transfer-Encoding`, `Upgrade`, `Proxy-Connection`) are forbidden — stream error PROTOCOL_ERROR.

Encoder uses incremental indexing for stable headers (Server, Content-Type, etc.) and never-indexed for sensitive headers (we may surface a hint API later; for now use literal-without-indexing for `Set-Cookie` and `Authorization`). Default behavior is RFC-compliant; tuning is a follow-up.

---

## Stream state machine

Per RFC 9113 §5.1. States: `idle`, `open`, `half-closed (local)`, `half-closed (remote)`, `closed`. (`reserved (local)`/`reserved (remote)` aren't used since we don't push.)

Transitions encoded in `HTTP2Stream.applyEvent(StreamEvent)`. Events:
- `RECV_HEADERS` (END_STREAM=0): idle → open.
- `RECV_HEADERS` (END_STREAM=1): idle → half-closed (remote).
- `RECV_DATA` (END_STREAM=1): open → half-closed (remote); half-closed (local) → closed.
- `SEND_HEADERS` (END_STREAM=0): open → open; half-closed (remote) → half-closed (remote).
- `SEND_HEADERS` (END_STREAM=1): half-closed (remote) → closed.
- `SEND_DATA` (END_STREAM=1): half-closed (remote) → closed.
- `RECV_RST_STREAM` / `SEND_RST_STREAM`: any non-idle → closed (with disposition).

Receiving a frame illegal in the current state is a stream or connection error per RFC 9113 §5.1 (e.g. DATA on idle is connection error; HEADERS on closed is stream error). The state machine is the authoritative gate; other code never branches on state directly.

`MAX_CONCURRENT_STREAMS` (default 100, configurable) caps simultaneously-open streams. New HEADERS over the cap → `RST_STREAM(REFUSED_STREAM)`. The cap counts streams in `open`, `half-closed (local)`, and `half-closed (remote)` states.

Stream-id rules:
- Client-initiated streams use odd ids; server-initiated even (we never initiate a stream — push is out of scope).
- Stream ids monotonically increase. Receiving a stream-id ≤ highest-seen-from-peer is connection error PROTOCOL_ERROR.

---

## Pseudo-header → HTTPRequest mapping

The HPACK decoder produces an ordered list of (name, value) pairs. The HEADERS-frame handler builds an `HTTPRequest`:

| h2 pseudo-header | HTTPRequest field |
|---|---|
| `:method` | `setMethod(...)` |
| `:scheme` | recorded for absolute-URL construction; mapped to `isTLS()` when `https` |
| `:authority` | parsed into host + port; synthesized as `Host:` header for downstream code that reads `request.getHeader("Host")` |
| `:path` | split into path + query string; populates `setPath` and parameter map |

Regular headers go through the existing header collection (case-insensitive lookup means downstream code is unaffected). Multiple `Cookie` h2 headers are coalesced (semicolon-joined) per RFC 9113 §8.2.3 before exposure.

The handler-facing API does not change. A handler written for HTTP/1.1 works on HTTP/2 with no modifications. `HTTPRequest.getProtocol()` returns `"HTTP/2.0"` so handlers that need to discriminate can.

---

## Trailers API

New on `HTTPResponse`:

```java
void setTrailer(String name, String value);
void addTrailer(String name, String value);
Map<String, List<String>> getTrailers();
```

New on `HTTPRequest`:

```java
String getTrailer(String name);
List<String> getTrailers(String name);
Map<String, List<String>> getTrailerMap();
boolean hasTrailers();   // true once trailers have been received (after body)
```

### h2 emission (response trailers)

After the final DATA frame, emit a HEADERS frame with `END_STREAM` carrying the trailer fields. If trailers are the only content (no body bytes, no DATA frames at all), emit a single HEADERS frame with `END_STREAM` containing both the response pseudo-headers + regular headers + trailers, *or* a HEADERS frame with response headers (no `END_STREAM`) followed by a HEADERS frame with `END_STREAM` containing only trailers — the latter matches gRPC's "trailers-only" response shape (used when the response has no payload, e.g. failed RPCs).

### h1.1 emission (response trailers)

Setting any trailer forces `Transfer-Encoding: chunked` (auto-set if not already). After the terminating `0\r\n` chunk, emit trailer-fields per RFC 9112 §7.1.2. Auto-set the `Trailer:` header listing trailer field names per RFC 9110 §6.5. Honor `TE: trailers` request signaling — if absent, drop trailers and continue (the existing `❌ TE: trailers signaling` item in HTTP1.1.md §3 closes here).

### h2 receive (request trailers)

Detected as a HEADERS frame on a stream after DATA has begun (or as the final HEADERS in a HEADERS-only request that signals trailer-fields via the END_STREAM flag on the second HEADERS). Populated into the request's trailer map; available to the handler after the request input stream returns EOF.

### h1.1 receive (request trailers)

Already parsed by `ChunkedInputStream` (today they are parse-and-discard). Modify to populate the request's trailer map. Available to the handler after the input stream returns EOF.

### Trailer-name restrictions

RFC 9110 §6.5.2 forbids trailer fields that affect framing (`Transfer-Encoding`, `Content-Length`), routing (`Host`), authentication (`Authorization`), payload processing (`Content-Encoding`), caching directives, and connection management. We enforce the deny-list at API entry (`setTrailer`/`addTrailer` throws `IllegalArgumentException` for forbidden names).

---

## h2c Upgrade handoff (HTTP/1.1 → HTTP/2)

RFC 9113 actually *removed* the Upgrade-based h2c flow that RFC 7540 §3.2 defined — current clients are expected to use prior-knowledge instead. Every major Java server still ships Upgrade for back-compat with older clients, so we will too.

### 101 Switching Protocols hook (prerequisite work)

A new method on `HTTPResponse`:

```java
void switchProtocols(String protocol, Map<String, String> additionalHeaders, ProtocolSwitchHandler handler);
```

`ProtocolSwitchHandler` is a functional interface receiving the underlying `Socket` after the 101 has been written and flushed. The handler is responsible for the post-Upgrade conversation. After `switchProtocols(...)` is called, the worker writes:

```
HTTP/1.1 101 Switching Protocols
Connection: Upgrade
Upgrade: <protocol>
<additionalHeaders>

```

…flushes, then invokes the `ProtocolSwitchHandler` on the same socket. Worker exits its keep-alive loop after the handler returns.

This hook is generic. h2c-Upgrade is the first consumer; future WebSockets work will be the second.

### h2c-Upgrade-specific wiring

`HTTP1Worker` checks the parsed request for `Upgrade: h2c` + `HTTP2-Settings: <base64>` after `validatePreamble`. If present and `enableH2cUpgrade=true`:

1. Build the implicit-stream-1 request from the parsed HTTP/1.1 request (method, path, body if any).
2. Decode `HTTP2-Settings` into a `HTTP2Settings` object representing the peer's initial settings.
3. Call `response.switchProtocols("h2c", emptyMap(), socket -> new HTTP2Connection(socket, listenerConfig, peerSettings, implicitStream1).run())`.

`HTTP2Connection` initializes with stream 1 already in `half-closed (remote)` state (the upgrading request body, if any, was already consumed by the HTTP/1.1 worker; the response stream is open for the server to write).

### Mismatched cases

- `Upgrade: h2c` without `HTTP2-Settings:` → 400 Bad Request (RFC 7540 §3.2.1 mandates the SETTINGS payload).
- `HTTP2-Settings:` malformed or oversized → 400 Bad Request.
- Both headers present but `enableH2cUpgrade=false` → ignore the upgrade, serve as plain HTTP/1.1 (the request is otherwise valid).

---

## Configuration knobs

### New on `HTTPListenerConfiguration`

| Knob | Default | Notes |
|---|---|---|
| `enableHTTP2` | `true` (TLS only — ignored on cleartext) | Controls ALPN advertisement of `h2`. On cleartext, h2c is independently controlled by the two flags below. |
| `enableH2cPriorKnowledge` | `false` (cleartext only — ignored on TLS) | Opt-in. Selector peeks first 24 bytes for the connection preface. Required for gRPC. |
| `enableH2cUpgrade` | `true` (cleartext only — ignored on TLS) | Honored when client sends `Upgrade: h2c, HTTP2-Settings: ...` |

### New on `HTTPServerConfiguration`

| Knob | Default | RFC 9113 ref |
|---|---|---|
| `withHTTP2HeaderTableSize(int)` | 4096 | §6.5.2 — `SETTINGS_HEADER_TABLE_SIZE` |
| `withHTTP2InitialWindowSize(int)` | 65535 | §6.5.2 — `SETTINGS_INITIAL_WINDOW_SIZE`. RFC default. Larger (e.g. 1 MiB) gives better throughput at cost of more buffering; we default-RFC and let users tune. |
| `withHTTP2MaxConcurrentStreams(int)` | 100 | §6.5.2 — `SETTINGS_MAX_CONCURRENT_STREAMS` |
| `withHTTP2MaxFrameSize(int)` | 16384 | §6.5.2 — `SETTINGS_MAX_FRAME_SIZE` |
| `withHTTP2MaxHeaderListSize(int)` | 8192 | §6.5.2 — `SETTINGS_MAX_HEADER_LIST_SIZE` |
| `withHTTP2RateLimits(HTTP2RateLimits)` | (sensible defaults — see Security) | DoS counters bundle |
| `withHTTP2KeepAlivePingInterval(Duration)` | disabled | Optional server-initiated PING |

`SETTINGS_ENABLE_PUSH` is fixed at `0` and not configurable (we don't implement push).

---

## Security

### TLS requirements (RFC 9113 §9.2)

- TLS 1.2 minimum. JDK 21 default disables TLSv1.0/1.1 via `jdk.tls.disabledAlgorithms`; we don't loosen.
- TLS-level compression is forbidden. JDK 21 doesn't expose this; implicit.
- Renegotiation is forbidden on h2 connections. Server-initiated: we never initiate. Client-initiated: set `jdk.tls.rejectClientInitiatedRenegotiation=true` (or document as a deployment recommendation; we don't override JDK system properties from library code).
- TLS 1.2 cipher-suite blocklist (Appendix A — long list of weak/old ciphers). After ALPN selects `h2`, check `SSLSession.getCipherSuite()` against the blocklist. If blocklisted, send `GOAWAY(INADEQUATE_SECURITY)` and close. TLS 1.3 cipher suites are inherently fine.

### DoS mitigations

Each named CVE-class attack gets a sliding-window rate counter on `HTTP2RateLimits`. Counters are per-connection; over-threshold triggers `GOAWAY(ENHANCE_YOUR_CALM)` and connection close.

| Attack | RFC ref / CVE | Default threshold | Counter |
|---|---|---|---|
| Concurrent stream cap | §5.1.2 | 100 | not a rate counter — instantaneous cap |
| Rapid Reset | CVE-2023-44487 | 100 RST_STREAM in 30 s | client-initiated stream resets |
| CONTINUATION flood | CVE-2024-27316 | 16 CONTINUATION per HEADERS block | per-block count + cumulative bytes ≤ MAX_HEADER_LIST_SIZE |
| PING flood | — | 10 PING/s | inbound PING |
| SETTINGS flood | — | 10 SETTINGS/s | inbound SETTINGS |
| Empty-DATA flood | — | 100 zero-length DATA in 30 s | inbound DATA with length 0 and END_STREAM=0 |
| WINDOW_UPDATE flood | — | 100 WINDOW_UPDATE/s | inbound WINDOW_UPDATE |

Slow-read against open streams is caught by the existing `MinimumWriteThroughput` instrumentation; we extend the throughput accounting to flow through the writer thread.

### Header-validation reuse

`HTTPTools.isTokenCharacter`, `isValueCharacter`, and the response-splitting defenses already in place are invoked from the HPACK decoder (incoming) and the HPACK encoder (outgoing). Vuln 4 from the security audit (response splitting) protects h2 by reuse — the choke point at `HTTPResponse.setHeader/addHeader/sendRedirect` runs before headers flow into the encoder.

---

## Test plan

Five layers:

### 1. Unit tests
- HPACK encoder/decoder against RFC 7541 test vectors (Appendix C).
- Frame codec: round-trip every frame type with edge-case lengths and flag combinations.
- Stream state machine: enumerate transitions and assert correct event mapping.
- Flow-control accounting: send-window decrement on DATA, replenish on WINDOW_UPDATE, block-and-resume.
- `HTTP2RateLimits` sliding-window counters: threshold crossing, expiry, isolation across connections.

### 2. Integration via JDK HttpClient
JDK 21's `HttpClient` speaks h2 natively — no extra dependency. Mirrors how today's tests use `HttpClient` for h1.1.
- Basic GET/POST/PUT/DELETE round-trip.
- Large body (>= INITIAL_WINDOW_SIZE) — exercises flow control.
- Response trailers received correctly.
- Request trailers sent and surfaced to handler.
- Concurrent streams from a single client connection.
- GOAWAY on graceful server shutdown — pending responses complete; new requests fail cleanly.

### 3. Raw-socket
For things `HttpClient` hides:
- Exact connection-preface byte sequence accepted; malformed preface → connection close.
- Exact GOAWAY framing on shutdown.
- Malformed frames trigger correct error codes (FRAME_SIZE_ERROR, PROTOCOL_ERROR, etc.).
- Unknown frame types ignored (RFC 9113 §5.5).
- PRIORITY frames parsed and discarded without error.
- PUSH_PROMISE inbound → connection close with PROTOCOL_ERROR.

Reuses existing `BaseSocketTest.Builder` patterns.

### 4. Conformance via h2spec
[`h2spec`](https://github.com/summerwind/h2spec) runs RFC 9113 conformance against a live server. Standard practice for Java h2 servers.
- New target in `project.latte`: `int h2spec` boots a server on a random port and runs the suite. CI integration.
- Initial run: capture failures, file each as bug ledger entry. Iterate to clean.

### 5. gRPC interop
A small `grpc-java` adapter using our handler API. Tests:
- Unary RPC round-trip.
- Server-streaming RPC.
- Client-streaming RPC.
- Bidi-streaming RPC.

Requires `grpc-java` as a test-only dependency.

### Security-specific tests

Each named DoS mitigation gets a test that drives the attack and asserts `GOAWAY(ENHANCE_YOUR_CALM)` (or appropriate code) within the configured limit:
- Rapid Reset: open and `RST_STREAM` 200 streams; expect GOAWAY by ~100.
- CONTINUATION flood: send 20 CONTINUATION frames before END_HEADERS; expect connection close at the configured limit.
- PING flood: send 100 PINGs in 1 second; expect GOAWAY.
- Settings flood, empty-DATA flood, WINDOW_UPDATE flood: same pattern.
- TLS cipher blocklist: configure server with a blocklisted cipher (TLS 1.2), connect, expect `GOAWAY(INADEQUATE_SECURITY)`.

### Performance baseline
Add an h2 row to `docs/plans/benchmark-spec.md`: same workload as h1.1, plus a high-concurrency stream variant. Directional target: parity with h1.1 on unary; significantly above on high-concurrency.

---

## HTTP/1.1 spec drift (review during this work)

We promised to review HTTP1.1.md for drift. Verification against current code (read during brainstorming):

| HTTP1.1.md item | Spec status | Code reality | Action |
|---|---|---|---|
| §6 "Reject bare CR" | ⚠️ | ✅ — `HeaderValue` state transitions on `\r` only to `HeaderCR`, which only accepts `\n`. Bare CR within a value is rejected. | Add explicit test; flip to ✅ |
| §6 "Reject whitespace before `:`" | ⚠️ | ✅ — `HeaderName` only accepts token chars or `:`. SP before `:` fails. | Add explicit test; flip to ✅ |
| §6 "Reject obs-fold" | ⚠️ | ✅ — `HeaderLF` requires CR or token char; SP/HTAB at line-start fails. | Add explicit test; flip to ✅ |
| §6 "Empty Host value" | ⚠️ | Untested; behavior unclear | Test, add validation if missing |
| §3 "Chunk extensions" | ⚠️ | ✅ Parsed and ignored | Add explicit test |
| §9 "Reject other Expect values" | ⚠️ | ❌ — HTTPWorker.java:148 silently ignores any non-`100-continue` Expect | Implement: respond 417 to unsupported expectations |
| §1 "OPTIONS asterisk-form test" | ⚠️ | Parser accepts; no test | Add test |
| §3 "Response trailers (sending)" | ❌ | ❌ — no API today | **Closes here** (see Trailers API above) |
| §3 "TE: trailers request signaling" | ❌ | ❌ | **Closes here** (h1.1 emission honors TE) |
| §4 "Upgrade / 101 Switching Protocols" | ❌ | ❌ | **Closes here** (101 hook prerequisite) |

The first seven items (drift / verification / one open) are scoped to a parallel sibling spec: `docs/superpowers/specs/2026-05-05-http11-conformance-cleanup-design.md`. That work runs independently of HTTP/2 — nothing about HTTP/2 forces it to ship first or together.

The last three items flip *because of* HTTP/2 work (trailers API, 101 hook). They're delivered as part of this design.

---

## Files touched

### New files

```
src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java
src/main/java/org/lattejava/http/server/internal/HTTP2Stream.java
src/main/java/org/lattejava/http/server/internal/HTTP2FrameReader.java
src/main/java/org/lattejava/http/server/internal/HTTP2FrameWriter.java
src/main/java/org/lattejava/http/server/internal/HTTP2Frame.java
src/main/java/org/lattejava/http/server/internal/HTTP2Settings.java
src/main/java/org/lattejava/http/server/internal/HTTP2RateLimits.java
src/main/java/org/lattejava/http/server/internal/HTTP2ErrorCode.java
src/main/java/org/lattejava/http/server/internal/HPACKEncoder.java
src/main/java/org/lattejava/http/server/internal/HPACKDecoder.java
src/main/java/org/lattejava/http/server/internal/HPACKDynamicTable.java
src/main/java/org/lattejava/http/server/internal/HPACKHuffman.java
src/main/java/org/lattejava/http/server/internal/ProtocolSelector.java
src/main/java/org/lattejava/http/server/io/HTTP2InputStream.java                    // h2-specific request-body reader (backed by per-stream pipe)
src/main/java/org/lattejava/http/server/io/HTTP2OutputStream.java                   // h2-specific response writer (enqueues DATA frames to writer)
src/main/java/org/lattejava/http/server/ProtocolSwitchHandler.java                  // public — handler-visible

src/test/java/org/lattejava/http/tests/server/HTTP2BasicTest.java
src/test/java/org/lattejava/http/tests/server/HTTP2FlowControlTest.java
src/test/java/org/lattejava/http/tests/server/HTTP2TrailersTest.java
src/test/java/org/lattejava/http/tests/server/HTTP2GoawayTest.java
src/test/java/org/lattejava/http/tests/server/HTTP2SecurityTest.java                // DoS mitigations
src/test/java/org/lattejava/http/tests/server/HTTP2UpgradeTest.java                 // h2c via Upgrade
src/test/java/org/lattejava/http/tests/server/HTTP2PriorKnowledgeTest.java
src/test/java/org/lattejava/http/tests/server/HPACKTest.java                        // RFC 7541 vectors
src/test/java/org/lattejava/http/tests/server/HTTP2FrameCodecTest.java
src/test/java/org/lattejava/http/tests/server/HTTP2StreamStateMachineTest.java
src/test/java/org/lattejava/http/tests/server/GRPCInteropTest.java                  // grpc-java adapter
src/test/java/org/lattejava/http/tests/server/HTTP2H2spec.java                      // h2spec runner (or via project.latte target)
```

### Renamed

```
src/main/java/org/lattejava/http/server/internal/HTTPWorker.java
  → src/main/java/org/lattejava/http/server/internal/HTTP1Worker.java
```

### Modified

```
src/main/java/org/lattejava/http/server/HTTPListenerConfiguration.java   // add enableHTTP2 / enableH2cUpgrade / enableH2cPriorKnowledge
src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java     // add HTTP/2 knobs (settings, rate limits, ping interval)
src/main/java/org/lattejava/http/server/HTTPRequest.java                 // request trailer accessors; getProtocol() returns HTTP/2.0 for h2
src/main/java/org/lattejava/http/server/HTTPResponse.java                // response trailer accessors; switchProtocols(...)
src/main/java/org/lattejava/http/server/internal/HTTPServerThread.java   // dispatch through ProtocolSelector instead of constructing HTTPWorker directly
src/main/java/org/lattejava/http/server/internal/HTTPBuffers.java        // possibly: frame-buffer pool
src/main/java/org/lattejava/http/server/io/HTTPInputStream.java          // h1.1 trailer plumbing — surface populated trailers from ChunkedInputStream
src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java         // h1.1 trailer emission; auto-set Trailer header
src/main/java/org/lattejava/http/io/ChunkedInputStream.java              // populate request trailer map on terminator
src/main/java/org/lattejava/http/io/ChunkedOutputStream.java             // emit trailer-fields after final 0\r\n
src/main/java/org/lattejava/http/util/HTTPTools.java                     // tighten validation invocations from HPACK
src/main/java/org/lattejava/http/security/SecurityTools.java             // SSLContext.serverContext: configure ALPN advertisement based on listener config
src/main/java/module-info.java                                            // export ProtocolSwitchHandler if it's in a public package

project.latte                                                             // grpc-java test dep, h2spec int target
docs/specs/HTTP1.1.md                                                     // flip ⚠️ items where verified ✅; flip closed ❌ items to ✅
docs/specs/HTTP2.md                                                       // new long-lived spec
docs/plans/benchmark-spec.md                                              // h2 entries
```

---

## Out of scope

- **Server push (PUSH_PROMISE) handler API.** Advertised disabled via `SETTINGS_ENABLE_PUSH=0`; inbound PUSH_PROMISE rejected with PROTOCOL_ERROR.
- **RFC 9218 Priority header / `priority-update` frame.** No major peer implements yet; revisit when ecosystem catches up.
- **HTTP/3.** Separate transport, separate spec.
- **HTTP/2 client.** This library has no HTTP client at all yet (per CLAUDE.md). Server-only scope. When the client lands, it gets its own h2 design doc.
- **HTTP/1.0 → HTTP/2.** No client speaks h2 over an HTTP/1.0 base; not specified.
- **Connection coalescing on the server side beyond what RFC mandates.** RFC 9113 §9.1.1 lets a client multiplex requests for multiple authorities over one TLS connection if the cert covers them. Server-side this is "accept the requests as they come"; we don't actively advertise alternative authorities.

---

## Open questions surfaced for review

None blocking. Review feedback welcome on:
- Default for `withHTTP2InitialWindowSize` — RFC default (65535) vs. throughput-tuned default (e.g. 1 MiB matching Jetty).
- Threshold values for DoS rate counters — we'll calibrate after first running implementation; the table in Security is a starting point, not a final SLA.
- Class decomposition specifics in `HTTP2Connection` — likely extract a `HTTP2StreamRegistry` collaborator if the connection class grows past ~600 lines.

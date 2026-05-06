# HTTP/1.1 Compliance — latte-java HTTP Server

Tracking document for RFC 9110 (HTTP Semantics), RFC 9111 (Caching, mostly handler responsibility), and RFC 9112 (HTTP/1.1 Message Syntax) conformance.

## Legend

- ✅ **Implemented** — covered by code and tests
- ⚠️ **Partial** — works for the common case, has known gaps or missing tests
- ❌ **Missing** — not implemented; would need work to claim conformance
- 🚫 **Out of scope** — handler responsibility, or deliberately not implemented (proxy-only features, etc.)

Each entry should cite the relevant code and (where applicable) tests.

---

## 1. Methods (RFC 9110 §9)

| Method | Status | Notes |
|---|---|---|
| GET | ✅ | Routed to handler. |
| HEAD | ✅ | Auto-handled — rewrites method to GET, suppresses body bytes. `HTTPWorker.java:139-144`, `HeadRequestContractTest`. |
| POST | ✅ | Routed to handler. |
| PUT | ✅ | Routed to handler. |
| DELETE | ✅ | Routed to handler. |
| PATCH | ✅ | Routed to handler. |
| OPTIONS | ✅ | `RequestPreambleConformanceTest.options_asterisk_form_accepted` |
| TRACE | 🚫 | Registered as a method but no built-in implementation. RFC 9110 §9.3.8 says servers SHOULD support; we leave it to the handler (or refuse). Per modern guidance often disabled for security. |
| CONNECT | 🚫 | Proxy-only. Not implemented; this is not a proxy. |

---

## 2. Request-target forms (RFC 9112 §3.2)

| Form | Status | Notes |
|---|---|---|
| origin-form (`/path?query`) | ✅ | Primary form; covered by most tests. |
| absolute-form (`http://host/path`) | ⚠️ | Parser accepts (any printable ASCII passes `HTTPTools.isURICharacter`), but no tests confirm the handler sees the expected path. Required for proxy-style requests. |
| authority-form (`host:port`) | ⚠️ | Used with CONNECT only. Parser accepts; no tests. |
| asterisk-form (`*`) | ✅ | `RequestPreambleConformanceTest.options_asterisk_form_accepted` |

---

## 3. Message framing (RFC 9112 §6)

| Feature | Status | Notes |
|---|---|---|
| `Content-Length` request body | ✅ | `HTTPInputStream` + `FixedLengthInputStream`. |
| `Transfer-Encoding: chunked` request body | ✅ | `ChunkedInputStream`. Strict TE parsing — only exact `chunked` accepted. `HTTPWorker.validatePreamble`. |
| Reject CL+TE coexistence | ✅ | `HTTPWorker.java:423-429`. Smuggling defense. |
| Reject multiple Content-Length headers | ✅ | `HTTPWorker.java:439-446`. |
| Reject multiple Transfer-Encoding headers | ✅ | `HTTPWorker.java:406-412`. |
| Reject non-`chunked` TE values | ✅ | `HTTPWorker.java:414-421`. |
| Chunk extensions (`5;name=value`) | ✅ | `RequestPreambleConformanceTest.chunk_extensions_parsed_and_discarded` |
| Request trailers | ⚠️ | `ChunkedInputStream` parses-and-discards. Not exposed to handlers. RFC 9110 §6.5 allows MAY; conformant. |
| `Content-Length` response | ✅ | Set by handler or auto by output stream. |
| `Transfer-Encoding: chunked` response | ✅ | `ChunkedOutputStream`. Auto-applied when handler doesn't set CL. |
| Strip CL/TE on 204/304 | ✅ | `HTTPOutputStream.java:212-215`. |
| TE wins over CL on response | ✅ | `HTTPOutputStream.java:217-221`. |
| Response trailers (sending) | ❌ | No API. RFC 9110 §6.5 makes this MAY-support — conformant to skip, but limits handler flexibility. |
| `TE: trailers` request signaling | ❌ | Not honored. Required for a server that emits trailers. |

---

## 4. Connection management (RFC 9110 §7.6, RFC 9112 §9)

| Feature | Status | Notes |
|---|---|---|
| Persistent connections (HTTP/1.1 default) | ✅ | `HTTPWorker.keepSocketAlive`. |
| HTTP/1.0 explicit `Connection: keep-alive` | ✅ | `HTTPRequest.isKeepAlive` HTTP/1.0 path. |
| `Connection: close` honored | ✅ | Server closes after response. |
| Multi-token Connection header parsing | ✅ | `HTTPRequest.isKeepAlive` parses comma-separated tokens, aggregates across multiple Connection lines, case-insensitive. `HTTP11SocketTest.connection_close_token_*`, `HTTP10SocketTest.connection_keep_alive_token_among_others_HTTP10`. |
| `Connection: keep-alive` in HTTP/1.1 (no-op) | ✅ | Treated as default. |
| Keep-alive timeout | ✅ | `HTTPServerConfiguration.withKeepAliveTimeoutDuration`. |
| Max requests per connection | ✅ | `HTTPServerConfiguration.withMaxRequestsPerConnection`. |
| HTTP pipelining | ⚠️ | Server processes one request at a time per connection. RFC says SHOULD support, but most clients abandoned pipelining; low practical impact. |
| `Upgrade` header / 101 Switching Protocols | ❌ | No handler hook to take over the socket. Blocks WebSocket, h2c, and any Upgrade-based protocol. |

---

## 5. Required and recommended response headers (RFC 9110 §6.6)

| Header | Status | Notes |
|---|---|---|
| `Date` (origin server with clock — MUST) | ✅ | Auto-set in IMF-fixdate (RFC 1123) format before handler invocation. Cached at one-second resolution via `DateTools.currentHTTPDate`. Handler can override with its own value or call `removeHeader` to suppress. Configurable via `withSendDateHeader` (default `true`). `DateHeaderTest`. |
| `Server` | 🚫 | Handler responsibility. |
| `Vary: Accept-Encoding` on compressed responses | ✅ | `HTTPOutputStream.java:245`, `:251`, `:289`. |
| `Content-Encoding` on compressed responses | ✅ | `HTTPOutputStream`. |
| `Connection` echo on each response | ✅ | `HTTPWorker.java:130`. |

---

## 6. Request validation (RFC 9112 §3, §5)

| Feature | Status | Notes |
|---|---|---|
| `Host` header required | ✅ | `HTTPWorker.java:383-388`. |
| Reject duplicate `Host` headers | ✅ | `HTTPWorker.java:390-397`. |
| Empty `Host` value handling | ✅ | Rejected with 400. `RequestPreambleConformanceTest.empty_host_value_rejected` |
| Protocol version `HTTP/1.0` and `HTTP/1.1` accepted | ✅ | `HTTPWorker.java:374-381`. |
| `505 HTTP Version Not Supported` for other versions | ✅ | Same. |
| Reject control characters in header names | ✅ | `BareLineFeedHeaderTest.control_characters_in_header_name`. |
| Reject bare LF as line terminator | ✅ | `BareLineFeedHeaderTest`. |
| Reject bare CR | ✅ | `RequestPreambleConformanceTest.bare_cr_in_header_value_rejected` |
| Reject whitespace before `:` (`Foo : bar`) | ✅ | `RequestPreambleConformanceTest.whitespace_before_colon_rejected` |
| Reject obs-fold (line-folded headers) | ✅ | `RequestPreambleConformanceTest.obs_fold_rejected` |
| Reject invalid characters in field-value | ✅ | `HTTPTools.isValueCharacter` excludes bare CR/LF; obs-text accepted as legacy compatibility. |
| Maximum header size | ✅ | `withMaxRequestHeaderSize`. |

---

## 7. Status code framing (RFC 9110 §15, §6.4.1)

| Status class | Suppress body | Notes |
|---|---|---|
| 1xx Informational | ⚠️ | 100-continue handled separately by Expect path. 101/103 — server has no path that returns these. If a handler does, no auto suppression. |
| 204 No Content | ✅ | `HTTPOutputStream.java:212`. |
| 304 Not Modified | ✅ | Same. |
| Other 2xx/3xx/4xx/5xx | ✅ | Body emitted normally. |

---

## 8. Content negotiation and encoding

| Feature | Status | Notes |
|---|---|---|
| Request `Content-Encoding: gzip` decompression | ✅ | `HTTPInputStream.java:181-185`. |
| Request `Content-Encoding: deflate` decompression | ✅ | Same. |
| Reject unknown `Content-Encoding` | ✅ | `HTTPWorker.java:460-471` returns 415. |
| Response gzip/deflate | ✅ | Driven by `Accept-Encoding`. |
| `Accept-Language` parsing | ✅ | `HTTPRequest.java:768-776`. Handler responsibility for actual i18n. |
| `Accept-Charset` | 🚫 | Deprecated by RFC 9110; handler responsibility if needed. |

---

## 9. Expect / 100-continue (RFC 9110 §10.1.1)

| Feature | Status | Notes |
|---|---|---|
| `Expect: 100-continue` | ✅ | `HTTPWorker.java:146-160`, `ExpectValidator`. |
| Custom validator hook | ✅ | `HTTPServerConfiguration.withExpectValidator`. |
| `417 Expectation Failed` path | ✅ | `ExpectTest`. |
| Reject other Expect values | ✅ | RFC 9110 §10.1.1. `ExpectTest.expect_other_value_returns_417` |

---

## 10. Range requests (RFC 9110 §14)

| Feature | Status | Notes |
|---|---|---|
| `Range` request handling | ❌ | Not implemented. |
| `206 Partial Content` | ❌ | Not implemented. |
| `Accept-Ranges` advertisement | ❌ | Not set. |
| `If-Range` | ❌ | Not implemented. |

RFC says servers MAY support ranges; not required for compliance, but commonly expected.

---

## 11. Conditional requests (RFC 9110 §13)

`If-Match`, `If-None-Match`, `If-Modified-Since`, `If-Unmodified-Since` — 🚫 handler responsibility. Server does no automatic precondition evaluation.

---

## 12. Authentication (RFC 9110 §11)

🚫 — Basic, Digest, etc. are handler / middleware responsibility.

---

## 13. Caching (RFC 9111)

🚫 — Origin server emits `Cache-Control`/`ETag`/etc. via the handler. We don't do automatic cache-revalidation, since this isn't a proxy.

---

## 14. Response generation (RFC 9112 §4)

| Feature | Status | Notes |
|---|---|---|
| Status line generation | ✅ | `HTTPTools.writeStatusLine`. |
| Reject CR/LF in status message, headers, cookies | ✅ | Response-splitting defense. `HTTPTools.validateResponseFieldValue`. |
| Header field-name validation | ✅ | `HTTPTools.validateResponseHeaderName`. |

---

## Bug ledger

No open issues at this time.

### Resolved

1. ~~`HTTPRequest.isKeepAlive()` — multi-token `Connection` header.~~ Exact-match comparison misclassified legal values like `Connection: close, upgrade`. Fixed by parsing the value as a token list per RFC 9110 §7.6.1.

---

## Roadmap

Items grouped by effort and value:

**Required for "compliant" claim:** complete.

**Low-effort hardening:** complete (Plan A 2026-05-05).

**Medium-effort features:**
- Response trailers API + `TE: trailers` honoring
- Range requests / 206 / `Accept-Ranges`
- `Upgrade` / 101 hook (prerequisite for WebSockets, h2c)

**Out of scope for /1.1:**
- WebSockets (RFC 6455) — separate spec, builds on Upgrade
- HTTP/2, HTTP/3 — separate transports

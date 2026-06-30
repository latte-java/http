# Configuration Cleanup Design

- **Created:** 2026-06-30
- **Status:** Implemented
- **Branch:** `http2/configuration`

## 1. Problem

Server configuration is scattered and inconsistent:

- **Two access surfaces.** Some knobs are reached through `Configurable` (the fluent interface implemented by both `HTTPServer` and `HTTPServerConfiguration`), while the HTTP/2 knobs live only on `HTTPServerConfiguration` as bespoke `withHTTP2*` methods that never made it onto `Configurable`. So `server.withChunkedBufferSize(...)` works but `server.withHTTP2MaxFrameSize(...)` does not.
- **No protocol separation.** Shared, HTTP/1-only, and HTTP/2-only options are all flattened onto `HTTPServerConfiguration` with no signal of which protocol they affect. A reader cannot tell that `chunkedBufferSize` is meaningless under HTTP/2 or that `maxFrameSize` is meaningless under HTTP/1.
- **Mixed shapes.** `MultipartConfiguration` is a class with a full `with*` builder. `HTTP2Settings` is a class with a partial builder fused to wire-protocol logic. `HTTP2RateLimits` is a `record` with no builder. The nine HTTP/2 knobs are plain setters on `HTTPServerConfiguration`.
- **HTTP/2 options that duplicate shared concepts.** `maxHeaderListSize` (default *unlimited*) is the HTTP/2 spelling of the shared `maxRequestHeaderSize` (default 128 KB) — the same "max total header bytes" DoS guard, with inconsistent defaults.
- **Dead configuration.** `http2KeepAlivePingInterval` and `http2SettingsAckTimeout` have setters and getters but no production consumer; only tests reference them.

## 2. Goals

1. Keep `Configurable` implemented by `HTTPServer` and `HTTPServerConfiguration`, but narrow its `with*` methods to **shared** configuration only.
2. Introduce two protocol sub-configurations, `HTTP1Configuration` and `HTTP2Configuration`, each with a `MultipartConfiguration`-style `with*` builder and self-validating setters.
3. The sub-configurations are **instantiated with their defaults when `HTTPServerConfiguration` is constructed** — never `new`'d by the developer, never null.
4. Developers mutate a sub-configuration through a **`Consumer` lambda**: `withHTTP1(Consumer<HTTP1Configuration>)` / `withHTTP2(Consumer<HTTP2Configuration>)`.
5. Eliminate HTTP/2 knobs that duplicate a shared concept; HTTP/2 reads the shared value directly.
6. Drop unwired configuration until it has a real consumer.

This is a **clean breaking change** (the library is pre-1.0, HTTP client unimplemented). No deprecated shims are retained; all call sites and tests are updated.

## 3. Classification of every configuration option

Legend for **Protocol**: `Shared` = affects the transport/handler layer used by both protocols; `H1` = mechanism exists only in HTTP/1.x; `H2` = mechanism exists only in HTTP/2.

### 3.1 Shared — stay on `Configurable` / `HTTPServerConfiguration`

| Option | Protocol | Lives now | Goes to | Purpose |
|---|---|---|---|---|
| `baseDir` | Shared | `Configurable` + `HSC` | unchanged | Base directory passed to `HTTPContext`. |
| `compressByDefault` | Shared | `Configurable` + `HSC` | unchanged | Default gzip/deflate behavior; consumed by the unified `HTTPOutputStream` (both protocols). |
| `contextPath` | Shared | `Configurable` + `HSC` | unchanged | URI prefix exposed via `HTTPRequest.getContextPath()`. |
| `handler` | Shared | `Configurable` + `HSC` | unchanged | The `HTTPHandler` that processes requests. |
| `initialReadTimeout` | Shared | `Configurable` + `HSC` | unchanged | Socket read timeout for the first byte, applied before protocol detection. |
| `instrumenter` | Shared | `Configurable` + `HSC` | unchanged | Metrics/event hook. |
| `loggerFactory` | Shared | `Configurable` + `HSC` | unchanged | Logger factory for all server classes. |
| `listeners` (`withListener`) | Shared | `Configurable` + `HSC` | unchanged | Listener list; per-listener protocol flags stay on `HTTPListenerConfiguration` (see §6). |
| `maxBytesToDrain` | Shared | `Configurable` + `HSC` | unchanged | Body-drain cap in the shared `HTTPInputStream`, used by both protocols. |
| `maxPendingSocketConnections` | Shared | `Configurable` + `HSC` | unchanged | Server-socket accept backlog. |
| `maxRequestBodySize` | Shared | `Configurable` + `HSC` | unchanged | Per-Content-Type request body cap. |
| `maxRequestHeaderSize` | Shared | `Configurable` + `HSC` | unchanged | Max total request header bytes. **Now also the source for HTTP/2's advertised `SETTINGS_MAX_HEADER_LIST_SIZE`** (see §5.1). |
| `minimumReadThroughput` | Shared | `Configurable` + `HSC` | unchanged | Slow-loris read-rate floor. |
| `minimumWriteThroughput` | Shared | `Configurable` + `HSC` | unchanged | Slow-read write-rate floor. |
| `multipartConfiguration` | Shared | `Configurable` + `HSC` | unchanged | Multipart parsing policy (`MultipartConfiguration`). |
| `processingTimeoutDuration` | Shared | `Configurable` + `HSC` | unchanged | Handler watchdog: read-complete to first-write. |
| `readThroughputCalculationDelayDuration` | Shared | `Configurable` + `HSC` | unchanged | Warm-up delay before enforcing read throughput. |
| `requestBufferSize` | Shared | `Configurable` + `HSC` | unchanged | Transport read buffer in the shared `HTTPBuffers`. |
| `responseBufferSize` | Shared | `Configurable` + `HSC` | unchanged | Error-recovery response buffer in the shared `HTTPBuffers`. |
| `sendDateHeader` | Shared | `Configurable` + `HSC` | unchanged | Auto-emit RFC 1123 `Date` (RFC 9110 §6.6.1, applies to both). |
| `shutdownDuration` | Shared | `Configurable` + `HSC` | unchanged | Graceful-shutdown wait. |
| `unexpectedExceptionHandler` | Shared | `Configurable` + `HSC` | unchanged | Handler-thread exception policy. |
| `writeThroughputCalculationDelayDuration` | Shared | `Configurable` + `HSC` | unchanged | Warm-up delay before enforcing write throughput. |

### 3.2 HTTP/1 — move to `HTTP1Configuration`

| Option | Protocol | Lives now | Goes to | Purpose |
|---|---|---|---|---|
| `chunkedBufferSize` | H1 | `Configurable` + `HSC` | `HTTP1Configuration` | Read-buffer size for decoding `chunked` request bodies. Chunked Transfer-Encoding is forbidden in HTTP/2. |
| `maxRequestChunkSize` | H1 | `Configurable` + `HSC` | `HTTP1Configuration` | Per-chunk size cap (anti-smuggling) for `chunked` requests. |
| `maxResponseChunkSize` | H1 | `Configurable` + `HSC` | `HTTP1Configuration` | Chunk size for `chunked` responses; HTTP/2 frames bodies by `maxFrameSize` instead. |
| `keepAliveTimeoutDuration` | H1 | `Configurable` + `HSC` | `HTTP1Configuration` | Idle socket timeout between HTTP/1 keep-alive requests. |
| `maxRequestsPerConnection` | H1 | `Configurable` + `HSC` | `HTTP1Configuration` | Max requests on one HTTP/1 keep-alive connection. |
| `expectValidator` | H1 | `Configurable` + `HSC` | `HTTP1Configuration` | `Expect: 100-continue` validator; consumed only by `HTTP1Connection`. |

### 3.3 HTTP/2 — move to `HTTP2Configuration`

| Option | Protocol | Lives now | Goes to | Purpose |
|---|---|---|---|---|
| `headerTableSize` | H2 | `withHTTP2HeaderTableSize` → `HTTP2Settings` | `HTTP2Configuration` | HPACK dynamic-table size advertised in SETTINGS. Default 4096. |
| `initialWindowSize` | H2 | `withHTTP2InitialWindowSize` → `HTTP2Settings` | `HTTP2Configuration` | Initial per-stream flow-control window. Default 65535. |
| `maxConcurrentStreams` | H2 | `withHTTP2MaxConcurrentStreams` → `HTTP2Settings` | `HTTP2Configuration` | Max concurrent streams per connection. Default 100. |
| `maxFrameSize` | H2 | `withHTTP2MaxFrameSize` → `HTTP2Settings` | `HTTP2Configuration` | Max frame payload the server will receive. Default 16384. |
| `handlerReadTimeout` | H2 | `withHTTP2HandlerReadTimeout` → `HSC` field | `HTTP2Configuration` | Reader wait for a handler to drain a DATA frame from its per-stream pipe before `RST_STREAM(CANCEL)`. Default 10s. |
| `rateLimits` | H2 | `HTTP2RateLimits` record (internal) | `HTTP2Configuration` → `HTTP2RateLimits` (builder class) | Five frame-flood DoS limits (RST_STREAM, PING, SETTINGS, empty DATA, WINDOW_UPDATE). See §4.3. |

### 3.4 Removed entirely

| Option | Protocol | Lives now | Disposition | Reason |
|---|---|---|---|---|
| `maxHeaderListSize` | H2 | `withHTTP2MaxHeaderListSize` → `HTTP2Settings` | **Removed** | Duplicates shared `maxRequestHeaderSize`; HTTP/2 derives its value from it (§5.1). |
| `http2KeepAlivePingInterval` | H2 | `withHTTP2KeepAlivePingInterval` → `HSC` field | **Removed** | No production consumer — unwired (§5.2). |
| `http2SettingsAckTimeout` | H2 | `withHTTP2SettingsAckTimeout` → `HSC` field | **Removed** | No production consumer — unwired (§5.2). |
| `multipartBufferSize` | Shared | `@Deprecated withMultipartBufferSize` / `getMultipartBufferSize` on `HSC` | **Removed** | Already deprecated in favor of `MultipartConfiguration.multipartBufferSize`; clean break removes it. |

### 3.5 Stays internal, not user-exposed

| Option | Protocol | Lives now | Disposition | Reason |
|---|---|---|---|---|
| `enablePush` | H2 | `HTTP2Settings` field | Stays internal | Server never pushes; always advertised as `0`. Not a developer knob. |

## 4. Proposed structure

### 4.1 `Configurable` (narrowed)

`Configurable<T>` keeps every **Shared** `with*` default method from §3.1 and gains two new entry points that delegate to the configuration:

```java
default T withHTTP1(Consumer<HTTP1Configuration> consumer) {
  configuration().withHTTP1(consumer);
  return (T) this;
}

default T withHTTP2(Consumer<HTTP2Configuration> consumer) {
  configuration().withHTTP2(consumer);
  return (T) this;
}
```

The HTTP/1-only methods (`withChunkedBufferSize`, `withMaxRequestChunkSize`, `withMaxResponseChunkSize`, `withKeepAliveTimeoutDuration`, `withMaxRequestsPerConnection`, `withExpectValidator`) are **removed from `Configurable`** and reachable only via `withHTTP1(...)`.

### 4.2 `HTTPServerConfiguration`

```java
private final HTTP1Configuration http1 = new HTTP1Configuration();
private final HTTP2Configuration http2 = new HTTP2Configuration();

public HTTP1Configuration getHTTP1Configuration() { return http1; }
public HTTP2Configuration getHTTP2Configuration() { return http2; }

@Override
public HTTPServerConfiguration withHTTP1(Consumer<HTTP1Configuration> consumer) {
  Objects.requireNonNull(consumer, "...");
  consumer.accept(http1);
  return this;
}

@Override
public HTTPServerConfiguration withHTTP2(Consumer<HTTP2Configuration> consumer) {
  Objects.requireNonNull(consumer, "...");
  consumer.accept(http2);
  return this;
}
```

The sub-configs are `final`, default-populated, never null. The getters give internal connection code read access; the `Consumer` overloads give developers fluent mutation. The old `getHTTP2Settings()` / `getHTTP2RateLimits()` / nine `withHTTP2*` methods are deleted.

### 4.3 `HTTP1Configuration` and `HTTP2Configuration`

Both are public classes in `org.lattejava.http.server` (must be exported so consumers can name the lambda parameter type). Both follow the `MultipartConfiguration` shape: private fields seeded with defaults, self-validating `with*` setters returning `this`, plain getters.

`HTTP2Configuration` additionally owns a default-populated `HTTP2RateLimits` and exposes it through a nested `Consumer`, keeping the access pattern uniform at every level:

```java
public class HTTP2Configuration {
  private int headerTableSize = 4096;
  private Duration handlerReadTimeout = Duration.ofSeconds(10);
  private int initialWindowSize = 65535;
  private int maxConcurrentStreams = 100;
  private int maxFrameSize = 16384;
  private final HTTP2RateLimits rateLimits = new HTTP2RateLimits();

  public HTTP2RateLimits getRateLimits() { return rateLimits; }

  public HTTP2Configuration withRateLimits(Consumer<HTTP2RateLimits> consumer) {
    consumer.accept(rateLimits);
    return this;
  }

  public HTTP2Configuration withMaxFrameSize(int size) { /* validate [16384, 16777215] */ }
  // ... withHeaderTableSize, withInitialWindowSize, withMaxConcurrentStreams, withHandlerReadTimeout
}
```

### 4.4 `HTTP2RateLimits`: record → builder class

`HTTP2RateLimits` becomes a public builder class in `org.lattejava.http.server` (moved out of `internal.h2`), with default-seeded fields and `with*` setters:

```java
public class HTTP2RateLimits {
  private int emptyDataMax = 100;
  private long emptyDataWindowMs = 30_000L;
  private int pingMax = 10;
  private long pingWindowMs = 1_000L;
  private int rstStreamMax = 100;
  private long rstStreamWindowMs = 30_000L;
  private int settingsMax = 10;
  private long settingsWindowMs = 1_000L;
  private int windowUpdateMax = 100;
  private long windowUpdateWindowMs = 1_000L;
  // getters + with* setters
}
```

`HTTP2RateLimitsTracker` stays internal; the connection builds it from the configured limits (`new HTTP2RateLimitsTracker(config.getHTTP2Configuration().getRateLimits())`) rather than via a `newTracker()` factory on the public type.

### 4.5 `HTTP2Settings` stays internal (wire object)

`HTTP2Settings` remains in `internal.h2` as the mutable wire-protocol object: it encodes the server's SETTINGS payload, decodes/tracks the peer's SETTINGS via `applyPayload`, and feeds HPACK table sizing and flow-control windows. It is **derived from** `HTTP2Configuration`, not authored by the developer. The connection builds the initial local settings:

```java
HTTP2Settings local = HTTP2Settings.fromConfiguration(
    config.getHTTP2Configuration(),
    config.getMaxRequestHeaderSize());   // → maxHeaderListSize
```

`enablePush` continues to be forced to `0` inside `HTTP2Settings`.

## 5. Critical analysis: HTTP/2 options that should not have been added

### 5.1 `maxHeaderListSize` duplicated `maxRequestHeaderSize`

Both cap the total size of a request's header block as a DoS guard. HTTP/1 already had `maxRequestHeaderSize` (default 128 KB, raw header bytes). HTTP/2 added `maxHeaderListSize` (default *unlimited* / `Integer.MAX_VALUE`, the RFC 9113 uncompressed header-list size). Two knobs, two defaults, one concept — and the HTTP/2 default left the header-flood guard wide open.

**Resolution:** remove `maxHeaderListSize`. HTTP/2 advertises `SETTINGS_MAX_HEADER_LIST_SIZE` and enforces its cumulative header guard from the shared `maxRequestHeaderSize`. A shared value of `-1` (disabled) maps to `Integer.MAX_VALUE` on the wire. One knob now governs header-size limits for both protocols, and HTTP/2 inherits the safe 128 KB default. (The two sizes are measured slightly differently — raw bytes vs. RFC 9113's name+value+32 accounting — but as a single bound this is acceptable and far safer than unlimited.)

### 5.2 `http2KeepAlivePingInterval` and `http2SettingsAckTimeout` were unwired

Both have setters/getters but no production consumer; only tests assert the round-trip. Carrying configuration with no behavior misleads users into thinking a feature exists.

**Resolution:** remove both. When server-initiated keep-alive PINGs and SETTINGS-ACK enforcement are actually implemented, they return as `HTTP2Configuration` knobs. Note: HTTP/2 keep-alive (PING frames) is a distinct mechanism from HTTP/1's idle-socket `keepAliveTimeoutDuration`; they are intentionally **not** unified.

### 5.3 Considered but kept separate

- **`handlerReadTimeout` vs. `processingTimeoutDuration`.** Different mechanisms: `handlerReadTimeout` is a per-stream pipe-backpressure safety net unique to HTTP/2 multiplexing; `processingTimeoutDuration` is the shared read-complete-to-first-write watchdog. Kept distinct, `handlerReadTimeout` lives in `HTTP2Configuration`.
- **`maxFrameSize` vs. `maxResponseChunkSize`.** Different wire concepts (HTTP/2 framing vs. HTTP/1 chunked encoding). Kept separate in their respective sub-configs.
- **`maxConcurrentStreams` vs. `maxRequestsPerConnection`.** Streams (concurrent, HTTP/2) vs. sequential requests on a keep-alive socket (HTTP/1). Different semantics; kept separate.

### 5.4 `HTTP2RateLimits` fields do not collapse into shared/HTTP/1 config

All five limits count occurrences of a specific HTTP/2 frame type within a sliding time window: `rstStreamMax` (RST_STREAM — the Rapid Reset / CVE-2023-44487 guard), `pingMax` (PING), `settingsMax` (SETTINGS), `emptyDataMax` (zero-length DATA), and `windowUpdateMax` (WINDOW_UPDATE). Each frame type exists only in HTTP/2's binary framing layer, so none has a shared or HTTP/1 equivalent. The shared options that sound adjacent measure different units or operate at a different layer, and folding any in would conflate distinct protections:

- `maxRequestsPerConnection` (H1) is a lifetime *total*, not a sliding-window *rate*; its HTTP/2 analog (total streams) is bounded by `maxConcurrentStreams`, not by a frame-flood counter.
- `minimumReadThroughput` / `minimumWriteThroughput` are *bytes/second* floors, not *frames/window* ceilings.
- `maxPendingSocketConnections` is the socket accept backlog — a different layer.

`rstStreamMax` and `maxConcurrentStreams` both mitigate Rapid Reset but are **complementary** (sliding-window flood guard vs. concurrency cap), not duplicative — both are retained. All ten `HTTP2RateLimits` values therefore stay whole inside `HTTP2Configuration`.

## 6. Out of scope

- **`HTTPListenerConfiguration` per-listener flags** (`http2Enabled`, `h2cPriorKnowledge`, TLS/cert/port). These are genuinely per-listener (one server can mix HTTP/2-enabled and HTTP/2-disabled listeners), so they stay on `HTTPListenerConfiguration` and are not folded into the server-wide sub-configs.
- The wasteful eager allocation of `requestBuffer` in `HTTPBuffers` for HTTP/2 connections (which never read it) is a separate I/O-layer cleanup, not a configuration concern.

## 7. Resulting developer experience

```java
new HTTPServer()
    .withHandler(handler)
    .withMaxRequestHeaderSize(64 * 1024)            // shared — also caps HTTP/2 header lists
    .withHTTP1(h1 -> h1
        .withKeepAliveTimeoutDuration(Duration.ofSeconds(30))
        .withMaxRequestsPerConnection(50_000))
    .withHTTP2(h2 -> h2
        .withMaxConcurrentStreams(50)
        .withInitialWindowSize(1 << 20)
        .withRateLimits(rl -> rl.withPingMax(20)))
    .start();
```

## 8. Migration impact

- **Production code:** `HTTP2Connection` and any code reading `getHTTP2Settings()` / `getHTTP2RateLimits()` switch to `getHTTP2Configuration()` and the derived `HTTP2Settings`. `HTTPInputStream`/`HTTP1Connection` read the moved HTTP/1 knobs from `getHTTP1Configuration()`.
- **Tests:** `HTTPServerConfigurationHTTP2Test`, `HTTP2SecurityTest`, `HTTP2H2SpecBatch3Test`, `HTTP2BasicTest`, and any test calling the removed `withHTTP2*` / `withChunkedBufferSize` / `withExpectValidator` / etc. methods are rewritten to the `withHTTP1(...)` / `withHTTP2(...)` form. The `maxHeaderListSize` assertions become assertions on the derived value from `maxRequestHeaderSize`.
- **`module-info.java`:** `HTTP2RateLimits` moves from `internal.h2` to the exported `org.lattejava.http.server` package; verify exports remain correct and `internal.h2` stays unexported.
- **Copyright headers:** `HTTPServerConfiguration` and `Configurable` are FusionAuth Apache-2.0 files — preserve those headers. New files (`HTTP1Configuration`, `HTTP2Configuration`), the relocated `HTTP2RateLimits`, and the Latte-original `HTTP2Settings` use the MIT `The Latte Project` header.

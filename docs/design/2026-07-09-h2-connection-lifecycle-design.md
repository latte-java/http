# HTTP/2 Connection Lifecycle Design

- **Created:** 2026-07-09
- **Status:** Approved
- **Branch:** `fix/h2-connection-lifecycle`

## 1. Problem

HTTP/2 connections cannot stay open longer than a few seconds, in direct violation of RFC 9113 §9.1 ("servers are
encouraged to maintain open connections for as long as possible"). Two accidental mechanisms race to kill every healthy
H2 connection, and the fallout is logged as a SEVERE internal error:

1. **Leftover 2-second SO_TIMEOUT.** The acceptor sets `SO_TIMEOUT` to `initialReadTimeoutDuration` (default 2 s) on
   every accepted socket (`HTTPServerAcceptorThread:91`). `HTTP1Connection` resets it to the keep-alive timeout when it
   goes idle (`HTTP1Connection:240`); `HTTP2Connection` never resets it. Any 2-second gap between frames — a browser
   idling after a page load — throws `SocketTimeoutException` out of the frame loop.
2. **Misapplied reaper throughput check.** `HTTP2Connection` reports `State.Read` for its entire life — never
   `KeepAlive`, `Process`, or `Write` — so `ConnectionReaperThread` applies the slow-reader eviction
   (`readThroughput < minimumReadThroughput`, default 16 KB/s) at all times. `Throughput.readThroughput()` is a
   lifetime average (`bytesRead / (lastReadInstant − firstReadInstant)`), so any idle gap followed by any byte (the
   SETTINGS ACK, a PING, the next request) collapses the computed rate and the reaper closes the socket out from under
   the parked reader. The `State.KeepAlive` Javadoc documents this exact failure mode; H2 was never taught the
   transition.

Both paths, plus ordinary client disconnects (`EOFException`), funnel into the frame loop's `catch (Throwable)`
(`HTTP2Connection:222`), which logs SEVERE "Unhandled exception in HTTP/2 reader" and emits `GOAWAY(INTERNAL_ERROR)` —
usually to a socket that is already closed.

Related gaps found in the same review:

- **PINGs cannot keep a connection alive.** Wire handling is correct per §6.7 (validated, ACKed, flood-limited at
  10/s), but each 17-byte PING lowers the lifetime-average throughput, so a PING-keep-alive client (gRPC style) is
  evicted *faster*. Sustaining 16 KB/s on PINGs alone would take ~1,000 pings/s, which the rate limit rightly kills.
- **No deliberate lifetime policy.** `HTTP2Configuration` has no idle timeout, no max connection age, and no
  max-requests-per-connection (H1 has `maxRequestsPerConnection`). Once the two accidental killers are fixed, nothing
  stops a client from holding a connection forever with periodic PINGs.
- **Client GOAWAY tears down in-flight streams.** On a client GOAWAY the loop returns immediately and teardown
  interrupts every handler thread (`HTTP2Connection:240`); §6.8 expects already-open streams to complete.
- **The reaper cannot reason about H2.** An H2 connection is concurrently reading, processing, and writing across
  multiplexed streams. No single `HTTPConnection.State` scalar describes it, and the facts a correct verdict needs
  (open-stream count, per-stream ages, per-phase I/O rates) are visible only inside `HTTP2Connection`.

## 2. RFC 9113 requirements

- **§9.1** — connections are persistent; servers should keep them open as long as possible and may close idle ones.
  Closing an idle connection gracefully means GOAWAY before close (§6.8: "endpoints SHOULD always send a GOAWAY frame
  before closing a connection").
- **§6.7** — PING must be answered with an ACK carrying the same payload; ACKs must not be answered. Responses SHOULD
  get higher priority than other frames.
- **§6.8** — GOAWAY carries the highest stream ID that will be processed; streams at or below it should be allowed to
  complete. `NO_ERROR` is the code for graceful shutdown.

## 3. Goals and non-goals

**Goals**

1. Healthy idle H2 connections survive indefinitely up to a configured keep-alive timeout, then close gracefully with
   `GOAWAY(NO_ERROR)`.
2. Lifecycle events (idle expiry, client disconnect, eviction, server shutdown) never log SEVERE and never emit
   `GOAWAY(INTERNAL_ERROR)`. That path is reserved for genuine bugs.
3. PINGs are answered but do not extend connection life — idle expiry is measured from stream activity, not bytes.
4. Deliberate bounds on how long a client can hold a connection: keep-alive timeout, max requests per connection, and
   an optional max connection age.
5. The reaper remains a single thread with a single roster; liveness policy moves behind a new `HTTPConnection`
   contract and is implemented once in a shared base class, reusing the current `Throughput` math, graces, and floors.
   The only amendment is a per-phase counter reset (§6).
6. Slow-loris protection for H2: a client trickling bytes mid-frame or starving a response of WINDOW_UPDATEs is
   evicted.
7. Client GOAWAY drains in-flight streams instead of interrupting them.

**Non-goals**

- No server-initiated PING probes (liveness probing of quiet clients) — possible follow-up.
- No re-tuning of the eviction policy values — the floors, graces, and timeouts keep their current defaults on both
  protocols.
- No changes to HPACK, flow control, the writer thread, or frame codecs beyond the timestamps in §4.5.
- No graceful-drain rework of *server* shutdown (`shutdown()` already sends GOAWAY(NO_ERROR); unchanged).

## 4. Design

### 4.1 Reader-loop exception classification

The frame loop currently catches its three protocol exceptions and lets everything else hit `catch (Throwable)`.
Reclassify:

| Exception | Meaning | Action |
|---|---|---|
| `FrameSizeException` / `HeaderListSizeException` / `ProtocolException` | Protocol violation | GOAWAY with mapped code (unchanged) |
| `SocketTimeoutException` | No bytes for `keepAliveTimeoutDuration` | Idle logic (§4.2): expire or continue |
| `EOFException` | Client closed without GOAWAY | DEBUG log, tear down, no GOAWAY (peer is gone) |
| `SocketException` / `SSLException` | Local close (eviction, shutdown) or TLS teardown | DEBUG log, tear down |
| Other `IOException` | Connection ended | DEBUG log, tear down |
| Any other `Throwable` | Genuine bug | SEVERE log + `GOAWAY(INTERNAL_ERROR)` (unchanged) |

This change alone stops the SEVERE spam and is independently shippable.

### 4.2 H2 keep-alive: idle timeout and PING policy

`keepAliveTimeoutDuration` moves from `HTTP1Configuration` up to `HTTPServerConfiguration` (default **20 seconds**,
unchanged): both protocols handle idle connections with the same policy, so the knob is shared rather than duplicated.
This is a clean breaking move with no deprecation shim, per the 2026-06-30 configuration-cleanup convention —
`withHTTP1(c -> c.withKeepAliveTimeoutDuration(...))` becomes the top-level `withKeepAliveTimeoutDuration(...)`, and
`HTTP1Connection`'s keep-alive SO_TIMEOUT reads the shared getter. For H2 it replaces the leftover 2-second
initial-read timeout after the SETTINGS exchange. The blocking model needs no timer thread; the reader manages
SO_TIMEOUT per loop iteration:

- **Live streams open:** `SO_TIMEOUT = keepAliveTimeoutDuration`, flat. A timeout here means total silence while work
  is nominally in flight — not idleness (the client may be quietly waiting on a long-poll response); continue the read
  loop and let the processing backstop (§4.5) judge a genuinely wedged connection.
- **Zero live streams:** `SO_TIMEOUT` is set to the *remaining idle budget* — `keepAliveTimeoutDuration − (now −
  idleSince)`, clamped to ≥ 1 ms — recomputed after every dispatched frame. `idleSince` is the instant the live-stream
  count last transitioned to zero (or the end of the SETTINGS exchange). Stream-0 traffic (PING, SETTINGS,
  WINDOW_UPDATE) is dispatched normally but never moves `idleSince`; only opening a stream resets the clock. On expiry
  — the shrinking read timeout fires, or a frame dispatch finds the deadline passed — enqueue `GOAWAY(NO_ERROR)`,
  drain, close, DEBUG log.

A byte is a byte at the socket layer: PING traffic necessarily resets the kernel read timer, so the deadline is
enforced by shrinking the budget each iteration rather than by waiting for a full-duration silence. Because the budget
decreases monotonically while idle, no PING cadence can postpone the wakeup — expiry lands at the deadline exactly, not
at up to 2× as a flat SO_TIMEOUT would allow (a ping arriving just before the deadline would re-arm a full period).
PINGs are ACKed up to the moment of expiry; they just cannot move the deadline. The per-frame `setSoTimeout` call only
happens on idle connections dispatching occasional stream-0 frames — negligible.

This is spec-clean. RFC 9113 §6.7 obligates the PING receiver only to ACK with the identical payload (which we do until
close) and defines PING as a sender-side probe — "determining whether an idle connection is still functional" — not a
lifetime-extension signal. §9.1 permits servers to terminate idle connections and leaves "idle" to server policy; §6.8
asks only that GOAWAY precede the close. Precedent runs stricter: gRPC servers by default treat pings without active
calls as abuse (GOAWAY "too_many_pings"); this design ACKs everything and expires gracefully.

Resuming a read after `SocketTimeoutException` on an `SSLSocket` is safe: `SSLSocketInputRecord` buffers partial
records, so a timeout that lands mid-record does not desynchronize the stream. (A client deliberately trickling bytes
to sit mid-record is the slow-loris case, handled by §4.5 — eviction, not resumption.)

### 4.3 Reaper contract: connection owns policy, reaper owns enforcement

An H2 connection is in multiple H1-style states at once, so the `state()` scalar leaves the `HTTPConnection` interface.
A separate H2 reaper class was rejected: the protocol is unknown at accept time (the roster holds a
`ConnectionDispatcher` until ALPN/preface negotiation resolves), and the scheduling machinery would be duplicated for
~30 lines of policy difference. Instead the contract inverts:

```java
public interface HTTPConnection extends Runnable {
  // getHandledRequests(), getSocket(), getStartInstant(), shutdown() unchanged; state() removed.

  /**
   * Evaluates this connection's liveness. Called by the reaper on each pass. Returns null when the connection is
   * healthy, or the reason it must be evicted.
   */
  EvictionReason check(long now);

  /**
   * Tears this connection down in a protocol-appropriate way. Must not block the calling (reaper) thread and must be
   * idempotent.
   */
  void evict(EvictionReason reason);
}

public enum EvictionReason { SlowRead, SlowWrite, ProcessingTimeout, MaxAge }
```

`ConnectionReaperThread` keeps: the 2-second cadence, dead-thread removal, calling `check()`, logging the reason,
instrumentation (`connectionClosed()`), and calling `evict()`. It no longer contains any per-state policy and no longer
touches the socket directly. Both protocol connections implement `check()` by extending a shared base class (§4.4);
only the gates feeding it are protocol-specific.

`ConnectionDispatcher.check()` returns null until the delegate exists (negotiation stays bounded by the initial-read
SO_TIMEOUT, exactly as the current `Negotiating` exemption works), then delegates both methods.

### 4.4 Shared liveness policy — `BaseHTTPConnection`

The liveness checks are today's reaper checks — same `Throughput` math, same graces, same floors — moved from the
reaper into an abstract `BaseHTTPConnection` (in `server/internal`) that both connections extend. The move is forced by
one fact: the reaper picks today's check from a single `state()` scalar, and an H2 connection can be in several H1
states at once. Each state therefore becomes an independently answerable gate the subclass supplies:

| Gate | H1 (its state machine, unchanged) | H2 (§4.5) |
|---|---|---|
| `readingRequest()` | `state == Read` | reader mid-frame, or a live stream's request side incomplete |
| `writingResponse()` | `state == Write` | a stream's response started and unfinished |
| `processing()` | `state == Process` | live-stream count above zero |
| `lastProgressInstant()` | `throughput.lastUsed()` | `lastUsed()` excluding stream-0 reads (§4.5) |

`check(now)` is the current reaper body with the state switch replaced by the gates — mutually exclusive for H1
exactly as today; for H2 several can be open at once and every applicable check runs:

1. **SlowRead** — `readingRequest()` and `throughput.readThroughput(now) < minimumReadThroughput`.
2. **SlowWrite** — `writingResponse()` and `throughput.writeThroughput(now) < minimumWriteThroughput`.
3. **ProcessingTimeout** — `processing()` and `now − lastProgressInstant() > processingTimeoutDuration`.
4. **MaxAge** — `maxConnectionAgeDuration` configured and `now − startInstant` past it. Checked here (not in a read
   loop) because a PINGing client never surfaces from a socket timeout. The setting lives on `HTTPServerConfiguration`
   and applies to both protocols; it defaults to off, so H1 sees no change unless enabled.

The one amendment to the battle-tested math is a per-phase counter reset (§6). `readThroughput()` divides total bytes
by the span since the connection's first read; on a connection that lives for hours that arithmetic *is* the
false-eviction bug from §1 — serve a request, idle 30 s, and the next request samples at ~17 B/s and is evicted
mid-flight. `writeThroughput()` shares the shape: it divides by `now − firstWroteInstant`, stamped by the connection's
*first* response, so every later response is measured over a denominator containing the idle gaps. H1 carries both
flaws today across keep-alive gaps — the `KeepAlive` exemption prevents sampling while idle but does not cleanse the
counters when the next request re-opens `Read`. Resetting the read counters when `readingRequest()` opens (and write
counters when `writingResponse()` opens) makes every sample look like the first request on a fresh connection —
precisely the case where the current math is proven. The graces (`readThroughputCalculationDelay` / `writeThroughputCalculationDelay`) stay inside
`Throughput`, untouched.

For H1 the gates reproduce today's policy exactly: the `State` enum becomes a private `HTTP1Connection` field (it
remains the right model for a sequential protocol), `KeepAlive` is simply all gates closed, and the phase resets land
on existing transitions (entering `Read` for a new request, committing a response). `HTTP1Connection.evict(reason)`
closes the socket, as the reaper does today.

### 4.5 H2 gates — `HTTP2Connection`

Idle expiry is handled by the reader loop (§4.2); the shared `check()` (§4.4) is the backstop for stuck and slow
connections. H2 computes its signals so that H1's policy applies at the level where H2 actually has one client — the
connection's input stream. Frames from many streams, WINDOW_UPDATEs, and PINGs all ride one TCP stream; when that
stream is slow while the client owes us bytes, the client is slow, exactly as an H1 connection mid-request.

1. **`readingRequest()`** — the client owes inbound bytes when the reader is mid-frame (`HTTP2FrameReader` keeps a
   volatile mid-frame marker, set at a frame's first byte and cleared at its last), or when at least one live stream's
   request side is incomplete (the client has not yet sent END_STREAM or RST_STREAM). Gate closed means the client
   owes nothing and no read check applies — long-poll and streaming-response clients that are legitimately read-silent
   are never rated. This is the H2 generalization of H1's states: mid-frame corresponds to "first byte of a request
   arrived", an incomplete request side to "mid-body". All inbound bytes count toward the rate, stream-0 frames
   included — an attacker cannot ride that generosity to a 16 KB/s floor with PINGs without sending ~1,000/s, which
   the 10/s PING rate limit kills first. The gate-plus-rate shape subsumes the byte-trickle slow-loris (trickling a
   frame holds the gate open at a near-zero rate) and — unlike a per-frame deadline — also catches drip-feeding
   *across* frames: holding a request open while trickling tiny PING or WINDOW_UPDATE frames keeps the gate open and
   fails the floor. `maxFrameSize` needs no special casing; a client above the floor completes frames of any
   permitted size without tripping anything. The documented casualty matches H1's: a client that holds a request body
   open and sends sparse bursts below the floor (H1 equivalent: a sparse chunked upload) needs
   `minimumReadThroughput` lowered.
2. **`writingResponse()`** — at least one live stream's response has started but not finished. Feeds SlowWrite, which
   catches a client accepting a response one WINDOW_UPDATE trickle at a time — the trickled writes keep
   `lastProgressInstant` fresh and would otherwise dodge ProcessingTimeout.
3. **`processing()` / `lastProgressInstant`** — work is in flight while the live-stream count is above zero. Progress
   means response bytes written or stream-level inbound movement (DATA, a stream opening or closing) — not raw reads,
   so a wedged connection cannot be held as a zombie by drip-fed stream-0 frames. Long-poll handlers that exceed the
   default 10 s require raising `processingTimeoutDuration`, exactly as they already do on H1.

One rule unifies §4.2 and these checks: **stream-0 traffic (PING, SETTINGS, connection-level WINDOW_UPDATE) proves the
transport works but never extends a lifetime clock.** It does not move `idleSince`, does not count as processing
progress, and can only help a client meet the read floor in amounts the frame rate limits already cap.

### 4.6 Graceful eviction — `HTTP2Connection.evict(reason)`

Sets an idempotent `evicting` flag, then attempts `tryEnqueue(GOAWAY(NO_ERROR), 0ms)` (never blocks the reaper), and
spawns a short-lived virtual thread that requests writer stop, joins it briefly (≤1 s), and closes the socket. The
parked reader wakes with `SocketException`, which §4.1 now classifies as a DEBUG-level lifecycle event. `MaxAge`
eviction uses `NO_ERROR`; the slow/stuck reasons use `ENHANCE_YOUR_CALM`. `HTTP1Connection.evict()` remains a plain
socket close.

### 4.7 Client GOAWAY drains instead of interrupting

On a client `GoawayFrame`: if `liveStreams == 0`, exit as today. Otherwise set a `draining` flag and keep dispatching —
existing streams continue (DATA, WINDOW_UPDATE, RST_STREAM still flow); new HEADERS are refused with
`RST_STREAM(REFUSED_STREAM)`. When the live-stream count reaches zero, exit. The keep-alive timeout and processing
backstop bound the drain, so a client that sends GOAWAY and then stalls cannot hold the connection.

### 4.8 Bounding total connection work

- **`maxRequestsPerConnection`** moves from `HTTP1Configuration` up to `HTTPServerConfiguration` (default **100,000**,
  unchanged) — the same clean breaking move as `keepAliveTimeoutDuration`, no shim. The policy is shared; enforcement
  stays protocol-appropriate: H1 keeps its current behavior (`Connection: close` on the response that hits the limit),
  H2 enforces at stream open in `HTTP2HeaderFrameHandler` — when the count is reached, send `GOAWAY(NO_ERROR)` with
  the current `highestSeenStreamId`, enter the §4.7 draining mode, and let in-flight streams finish.
- **`maxConnectionAgeDuration`** (new on `HTTPServerConfiguration`, default **null = unlimited**). Backstop for
  load-balanced deployments that rotate connections; enforced by the shared `check()` (§4.4) on both protocols.

## 5. Configuration summary

| Setting | Class | Default | Purpose |
|---|---|---|---|
| `keepAliveTimeoutDuration` | `HTTPServerConfiguration` (moved from `HTTP1Configuration`) | 20 s | Shared idle policy: H1 keep-alive SO_TIMEOUT; H2 zero-stream idle expiry |
| `maxRequestsPerConnection` | `HTTPServerConfiguration` (moved from `HTTP1Configuration`) | 100,000 | Shared limit: H1 `Connection: close` at the limit; H2 graceful GOAWAY + drain after N streams |
| `maxConnectionAgeDuration` | `HTTPServerConfiguration` (new) | null (off) | Hard lifetime backstop for both protocols; graceful GOAWAY on H2 |
| `initialReadTimeoutDuration` | `HTTPServerConfiguration` (existing) | 2 s | Now bounds only negotiation + preface + SETTINGS exchange for H2 |
| `processingTimeoutDuration` | `HTTPServerConfiguration` (existing) | 10 s | Progress deadline while work is in flight, both protocols (§4.4 check 3) |
| `minimumWriteThroughput` | `HTTPServerConfiguration` (existing) | 16 KB/s | Slow-writer floor, both protocols, applied to the current write phase while a response is in progress (§4.4 check 2) |
| `minimumReadThroughput` | `HTTPServerConfiguration` (existing) | 16 KB/s | Slow-reader floor, both protocols, applied to the current read phase while inbound bytes are owed (§4.4 check 1) |

## 6. Throughput phase epochs

`Throughput` keeps its counters, math, and graces; it gains phase epochs for the shared `check()` (§4.4): a
`resetRead()` invoked when the `readingRequest()` gate opens and a `resetWrite()` invoked when the `writingResponse()`
gate opens, each clearing that direction's byte count and instants. `readThroughput(now)` / `writeThroughput(now)` are
unchanged — they now measure "since this phase began" instead of "since the connection's first byte," which is the
measurement the current code already makes on the first request of a connection. `lastUsed()` survives unchanged as
the progress clock. Counters are written by whichever thread performs the I/O and read by the reaper; the existing
`synchronized` methods already cover this.

## 7. Behavior changes

- Idle H2 connections now survive past 2 seconds and close after 20 seconds of zero streams with `GOAWAY(NO_ERROR)`
  instead of dying with `GOAWAY(INTERNAL_ERROR)`.
- Client disconnects, evictions, and shutdowns log at DEBUG, not SEVERE.
- A PING-keep-alive client is no longer evicted within seconds — and also can no longer hold a connection past the
  keep-alive deadline.
- In-flight requests survive a client GOAWAY.
- `HTTPConnection` (unexported, `server/internal`) loses `state()` and gains `check()`/`evict()`. The `State` enum
  moves into `HTTP1Connection`. `HTTPServerAcceptorThread` and `HTTP1Connection` are FusionAuth-inherited files: the
  refactor preserves their Apache-2.0 headers and original `@author` tags.
- **Breaking (public API):** `keepAliveTimeoutDuration` and `maxRequestsPerConnection` move from `HTTP1Configuration`
  to `HTTPServerConfiguration` with no shims; callers of the `withHTTP1(...)` setters switch to the top-level ones.
  One consequence of sharing: these policies cannot be tuned per protocol.
- H1 eviction sampling now resets per phase (§6). Floors, graces, timeouts, and the `Throughput` math are unchanged;
  behavior differs only where the un-reset lifetime average mis-measured — an idle keep-alive gap no longer poisons
  the next request's sample. `maxConnectionAgeDuration` also becomes available on H1 (off by default, so no change
  unless enabled).

## 8. Testing plan

Module-level tests (server + real sockets/JDK HTTP client), following the existing H2 test conventions; slow tests go
in the existing timeout-excluded group.

1. **Regression, original bug:** idle H2 connection survives well past `initialReadTimeoutDuration` and several reaper
   passes, then serves a second request on the same connection.
2. **Idle gap regression:** request → 3 s idle → second request succeeds on the same connection (kills the
   throughput-collapse eviction).
3. **Idle expiry:** with a short configured keep-alive, a zero-stream connection receives `GOAWAY(NO_ERROR)` then a
   clean close at the deadline.
4. **PING transparency:** client PINGs every 500 ms with no streams; all PINGs are ACKed; connection still expires at
   the keep-alive deadline. Same with a ping cadence just under the keep-alive timeout — expiry still lands at the
   deadline, not at 2× (the shrinking-budget case). Separately: PINGs during an idle gap do not cause eviction before
   the deadline.
5. **Slow reader:** (a) client sends a frame header then trickles payload bytes → evicted after the grace; (b) client
   opens a request, withholds END_STREAM, and drips PINGs/WINDOW_UPDATEs → evicted (gate held open, rate below floor);
   (c) an upload sustaining `minimumReadThroughput` — including a max-size frame under a raised `maxFrameSize` — is
   unaffected; (d) a long-poll client that has fully sent its request and stays silent is never rate-checked (gate
   closed).
6. **Slow writer:** client stops sending WINDOW_UPDATEs mid-download (or trickles them) → evicted; a client consuming
   steadily below 16 KB/s only via small windows is the documented casualty, same as H1.
7. **Client GOAWAY drain:** GOAWAY sent while a response is streaming → the response completes, then the connection
   closes; a new stream after GOAWAY is refused with `REFUSED_STREAM`.
8. **Clean disconnect hygiene:** client closes the socket with and without GOAWAY → no SEVERE log records (assert via
   the test log capture), no `GOAWAY(INTERNAL_ERROR)` on the wire.
9. **`maxRequestsPerConnection`:** N+1st stream is refused after `GOAWAY(NO_ERROR)`; in-flight streams complete.
10. **`maxConnectionAgeDuration`:** connection kept busy past the age → graceful GOAWAY.
11. **H1 policy preserved:** the existing H1 suite passes; only tests that pinned the un-reset lifetime counters (if
    any) may be rewritten against the per-phase reset.
12. **H1 idle-gap regression:** keep-alive connection, request → long idle gap → second request; a reaper sample
    during the second request's read does not evict (the un-reset lifetime average would have). Write-side twin: a
    large second response after the gap is not evicted mid-write (`writeThroughput` divides by `now −
    firstWroteInstant`, which the first response stamped before the gap).

## 9. Open questions

1. **Keep-alive default.** The setting is now shared, so raising it for H2 raises it for H1 too. H2 connections are
   more expensive to re-establish (TLS + ALPN + SETTINGS); nginx effectively allows ~75 s, Jetty 30 s. Is the shared
   20 s default too aggressive for browser reuse?
2. **SlowWrite in v1?** With the shared `check()` it is H1's existing write check behind the `writingResponse()` gate
   — essentially free. Kept as a question only in case the H2 "response in progress" qualifier proves noisy in
   practice.
3. **GOAWAY code for slow/stuck evictions.** `ENHANCE_YOUR_CALM` vs `NO_ERROR` — spec permits either; `ENHANCE_YOUR_CALM`
   is more diagnostic for the peer but some clients surface it as an error to callers.

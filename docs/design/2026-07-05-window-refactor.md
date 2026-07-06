# HTTP/2 Flow-Control Window Refactor

- **Created:** 2026-07-05
- **Status:** Implemented
- **Builds on:** `http2/refactor-connection` (connection redesign, pending detailed review)

This document collects flow-control window improvements as numbered items, ordered by implementation sequence —
later items build on earlier ones.

## Item 1: One window class — `HTTP2Window` for all four windows

### 1.1 Problem

Window state is implemented three different ways for what is one concept:

- `HTTP2Stream` carries seven synchronized window methods (~60 lines) unrelated to its real job (state machine,
  roster, dispatch), and parks blocked writers on the stream's own monitor — the same monitor
  `HTTP2OutputProtocol.commitHeaders` uses for state-transition atomicity, two unrelated concerns on one lock.
- The send-window wake-up protocol is split across classes: `HTTP2WindowUpdateFrameHandler` and the SETTINGS-delta
  walk in `HTTP2ConnectionFrameHandler` each call `incrementSendWindow(delta)` and then separately
  `synchronized (stream) { stream.notifyAll(); }`. Forgetting the second half is a silent hang.
- `HTTP2ConnectionWindow` duplicates the same accounting for the connection send window — and it is already, almost
  method for method, the right abstraction (`acquire`/`tryAcquire`/`increment`/`available`, synchronized, notify
  folded into `increment`).
- `HTTP2Stream`'s class Javadoc claims "the handler thread reads the receive window" — no such call site exists; all
  receive-window access is reader-thread-confined today. The synchronization is kept deliberately (the confinement is
  conventional, and a future replenish-on-consume design would put handler threads on the receive window), but the
  documentation must describe reality.

### 1.2 Design

One class, four instances. Windows behave identically regardless of direction or level; a receive-side instance
simply never calls the acquire methods.

```java
/**
 * A flow-control window (RFC 9113 §6.9). One class serves all four windows on a connection: per-stream and
 * connection-level, send and receive. Synchronized throughout; send-side acquirers park on this object's monitor
 * and increment() wakes them. Receive-side instances are currently reader-thread-confined, but the synchronization
 * is retained because nothing enforces that confinement.
 */
public class HTTP2Window {
  public HTTP2Window(int initial) { ... }

  /** Waits until positive, then consumes and returns min(numberOfBytes, available) — partial grants are the
      contract; HTTP2OutputStream takes min(stream, connection) credit and returns the surplus via increment().
      timeoutMillis bounds each park (responsiveness to interrupt/teardown), not the total wait. */
  public synchronized int acquire(int numberOfBytes, long timeoutMillis) throws InterruptedException { ... }

  public synchronized long available() { ... }

  /** Consumes numberOfBytes if fully available without mutating otherwise; false = would-underflow. Receive-side
      debits use this and map false to FLOW_CONTROL_ERROR at their scope. */
  public synchronized boolean decrement(int numberOfBytes) { ... }

  /** Adds signed credit and wakes parked acquirers. Negative deltas are legal (SETTINGS_INITIAL_WINDOW_SIZE
      decrease, §6.9.2). Throws IllegalStateException past 2^31-1 as a defensive backstop — protocol-level overflow
      is pre-checked by callers, which map it to the RFC error codes. Also serves as "release" for returning
      surplus acquired credit. */
  public synchronized void increment(int numberOfBytes) { ... }

  /** All-or-nothing non-blocking acquire — the single-frame fast path. */
  public synchronized boolean tryAcquire(int numberOfBytes) { ... }
}
```

Signature notes vs. the obvious sketch: `acquire` returns the grant and `tryAcquire` returns success — both
contracts are load-bearing in `HTTP2OutputStream.flushAndFragment` (the min-of-two-windows dance at lines 97-139,
including surplus release when the connection window is the tighter bound). There is no separate `release` method —
release is `increment`. `notifyAll()` inside `increment()` is unconditional: with no waiters it is a mark-word check
and return (waiting requires an inflated monitor, so a never-waited-on object short-circuits), so receive-side
instances pay nanoseconds for it.

**Field changes.**

- `HTTP2Stream`: the seven window methods and both `long` counters are deleted, replaced by two final fields with
  plain accessors: `HTTP2Window receiveWindow`, `HTTP2Window sendWindow` (initialized from the same values the
  constructor receives today). The class becomes purely state machine + roster + dispatch. The stale threading
  claim in its Javadoc is corrected as part of the move.
- `HTTP2Connection`: `connectionSendWindow` becomes an `HTTP2Window`; Item 2's connection receive window is an
  `HTTP2Window`. `HTTP2ConnectionWindow` is deleted.

**Call-site migration (mechanical).**

| Today                                                                                                                | Becomes                                                                       |
|----------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| `stream.acquireSendWindow(w, t)` / `tryAcquireSendWindow` / `releaseSendWindow` (`HTTP2OutputStream`)                | `stream.sendWindow().acquire(w, t)` / `tryAcquire` / `increment`              |
| `connectionWindow.acquire/tryAcquire/increment` (`HTTP2OutputStream`, handlers)                                      | unchanged semantics, type becomes `HTTP2Window`                               |
| `incrementSendWindow(delta)` + separate `synchronized (s) { s.notifyAll(); }` (WINDOW_UPDATE handler, SETTINGS walk) | `s.sendWindow().increment(delta)` — notify folded in, the split-brain retired |
| `consumeReceiveWindow(n)` (throws on underflow)                                                                      | `if (!stream.receiveWindow().decrement(n)) → StreamError(FLOW_CONTROL_ERROR)` |
| `incrementReceiveWindow` / `receiveWindow()` reads (replenish-at-half)                                               | `receiveWindow().increment` / `.available()`                                  |
| `consumeSendWindow`                                                                                                  | retired (no production caller; acquisition already consumes)                  |

**Error-mapping fixes riding along (same family, all latent `INTERNAL_ERROR`-instead-of-`FLOW_CONTROL_ERROR`
warts).**

1. Peer overruns a *stream* receive window: today `consumeReceiveWindow` throws `IllegalStateException`, surfacing
   as `GOAWAY(INTERNAL_ERROR)`; with `decrement` returning false, the DATA path returns
   `StreamError(FLOW_CONTROL_ERROR)` (§6.9.1). (The connection-scope equivalent is specified in Item 2.)
2. A SETTINGS increase that pushes a stream send window past 2³¹−1 is a connection error `FLOW_CONTROL_ERROR`
   (§6.9.2); today the SETTINGS walk has no pre-check, so the defensive throw again surfaces as `INTERNAL_ERROR`.
   The walk gains the pre-check and returns `ConnectionError(FLOW_CONTROL_ERROR)`.

### 1.3 Testing

Module-level, per the redesign's §10 doctrine. The extraction itself is behavior-preserving and pinned by the
existing flow-control and h2spec suites. The two error-mapping fixes are new wire-observable behavior and get raw
frame tests: (1) a single DATA frame larger than the stream's available window — deterministic via config, e.g.
`initialWindowSize` of 1,024 and a 2,048-byte DATA frame → `RST_STREAM(FLOW_CONTROL_ERROR)` (debit and replenish run
inline on the reader thread, so overruns are only reachable when one frame exceeds available credit; there is no
"paused replenish" window to engineer); (2) open a stream, drain most of its send window, then send SETTINGS raising
`INITIAL_WINDOW_SIZE` so the adjusted window exceeds 2³¹−1 → `GOAWAY(FLOW_CONTROL_ERROR)`.
`HTTP2FlowControlTest` and `HTTP2OutputStreamFragmentationTest` construct streams and call window methods directly;
they get the same mechanical migration as the call sites above.

## Item 2: Connection-level receive window — configurable size, real accounting

### 2.1 Problem

The connection-level receive window is pinned at the RFC 9113 default of 65,535 octets for the life of every
connection, and it is not tracked — the server blindly echoes a stream-0 WINDOW_UPDATE for each DATA frame's payload
length (`HTTP2DataFrameHandler`). Four consequences:

1. **Upload throughput ceiling.** A client can never have more unacknowledged request-body bytes in flight than the
   connection window, so aggregate upload throughput per connection is capped at `65535 / RTT`, shared across all
   streams on the connection:

   | Client RTT | Upload ceiling |
   |------------|----------------|
   | 1 ms (LAN) | ~520 Mbit/s    |
   | 10 ms      | ~52 Mbit/s     |
   | 50 ms      | ~10 Mbit/s     |
   | 100 ms     | ~5 Mbit/s      |

   This also makes the existing per-stream `initialWindowSize` knob nearly useless for uploads over 64 KB — the
   connection window binds first. Downloads and sub-64 KB request bodies are unaffected.

2. **No enforcement.** Because nothing counts the window, a peer that overruns it cannot be detected as the
   `FLOW_CONTROL_ERROR` RFC 9113 §6.9.1 prescribes.

3. **Window-leak bug: padding.** Flow control debits the *entire* DATA payload including the Pad Length byte and
   padding (§6.9.1), but the frame reader strips padding before the payload reaches the handler, and the echo
   replenish credits only the stripped length. Every padded DATA frame permanently shrinks the peer-visible
   connection window by its padding size.

4. **Window-leak bug: ignored DATA.** DATA on a forgotten/closed stream is ignored per §6.1 without any replenish —
   but the peer debited its view of the connection window when it sent the frame. Each such frame permanently
   shrinks the window. Enough of them stall all uploads on the connection forever.

Fixing 2-4 requires tracking the window; once it is tracked, making its size configurable is nearly free — and it is
the industry-standard mechanism: Go's HTTP/2 server defaults to a 1 MB connection window, browsers advertise 12-15 MB
as clients, all via exactly this mechanism (the connection window can only be resized by stream-0 WINDOW_UPDATE;
`SETTINGS_INITIAL_WINDOW_SIZE` applies to stream windows only, §6.9.2).

### 2.2 Design

**Configuration.** `HTTP2Configuration` gains `connectionWindowSize` with a self-validating
`withConnectionWindowSize(int)` builder setter. Valid range **[65,535, 2³¹−1]** — the protocol provides no way to
shrink the connection window below its initial default (no negative increments), so smaller values are
unimplementable and rejected.

**Default: 1 MB (1,048,576 octets)** — matching Go's HTTP/2 server. Uploads get a ~16× ceiling raise out of the box;
the added exposure is bounded by kernel socket buffering, not userspace, since the per-stream pipes still bound our
copies. A default that silently caps uploads at 10 Mbit/s on a 50 ms link would be a worse surprise than a modestly
larger TCP buffer footprint.

**Advertising.** First flight, coalesced with the server preface: `HTTP2Tools.negotiateSettings` writes
`WINDOW_UPDATE(0, connectionWindowSize - 65_535)` immediately after the local SETTINGS, before the flush, when
`connectionWindowSize > 65_535`. One frame, once per connection, in the same TCP segment as the SETTINGS — the grant
is causally independent of the peer's SETTINGS, and Go, nginx, and browsers all advertise this way. (Originally
landed as a post-handshake writer-queue enqueue; moved into the handshake as polish.)

**Accounting.** The connection-level receive window is an `HTTP2Window` (Item 1), owned by the connection and passed
to the stream-frame handler bundle. All debits and replenishes run on the reader thread (the class is synchronized
regardless — Item 1's confinement stance). The debit happens for **every DATA frame on a non-zero stream, in every
stream state** — live, closed, forgotten, refused — because flow control is connection state independent of stream
outcome (§6.9). Concretely, the debit runs at the top of `HTTP2Stream.handleFrame`'s DATA arm, before the empty-DATA
rate limit and the state switch, so transient flyweights debit exactly like live streams. (DATA on stream 0 routes
to `HTTP2ConnectionFrameHandler` and kills the connection; no accounting needed.)

**Correct debit size.** The debit is the frame's *flow-controlled length* — the raw payload length including padding
— not the stripped payload. `HTTP2FrameReader` surfaces this as a new component on the DATA frame record (the raw
`length` field it already read from the frame header), populated during the existing parse. This fixes leak bug 3.

**Enforcement.** If `decrement(flowControlledLength)` returns false — the peer sent more than we granted — the DATA
arm returns `ConnectionError(FLOW_CONTROL_ERROR)` (§6.9.1). A compliant peer can never trip this (we replenish at
half); only a peer ignoring our advertised window sees it.

**Replenish-at-half.** When `available()` drops below `connectionWindowSize / 2`, enqueue
`WINDOW_UPDATE(0, connectionWindowSize - available)` and `increment` the window back to `connectionWindowSize` — the
same strategy the per-stream window already uses. This replaces the per-frame echo, fixing leak bug 4 (ignored DATA
now debits and replenishes like any other DATA) and eliminating the chattiness of one stream-0 WINDOW_UPDATE per
DATA frame (~65,000 frames for a 1 GB upload today).

**Per-stream replenish also switches to flow-controlled length.** The stream-level replenish math in
`HTTP2DataFrameHandler` has the same padding leak (bug 3 at stream scope); it moves to the same flow-controlled
length. No other stream-window behavior changes.

### 2.3 What does not change

- Back-pressure architecture: the per-stream pipes remain the mechanism that slows the reader when a handler stalls;
  a larger connection window changes how many bytes may sit in the kernel's TCP buffer, not our userspace copies.
- The send side: the connection send window and peer-driven WINDOW_UPDATE handling are untouched (beyond Item 1's
  type change).
- Per-stream window sizing and `initialWindowSize` semantics.

### 2.4 Considered and rejected

- **Adapting the window to request `content-length`.** Rejected on three grounds: the connection window is a one-way
  ratchet (no negative increments), so per-request adaptation degenerates into "grow to the largest upload ever
  seen" — a static knob reaches the same steady state without the machinery; it only helps requests that declare a
  length, doing nothing for streaming/gRPC-style uploads; and it lets a client-supplied header size our advertised
  buffering. A per-stream variant (bump one stream's window on a large declared length) avoids the ratchet but still
  only helps declared-length uploads; not worth a second mechanism.
- **BDP probing (gRPC-style adaptive windows).** The principled adaptive design — measure RTT via PING against
  delivered bytes and size the window to the measured bandwidth-delay product. Real work, real value at very large
  scale; unnecessary once a static knob can be set above the BDP a deployment cares about. Revisit only with
  evidence.

### 2.5 Testing (module-level, per the connection redesign's §10 doctrine)

Wire tests in the `HTTP2RawFrameTest` style; no internal-class tests:

- **Advertising:** with the default, the client reads `WINDOW_UPDATE(0, 983041)` (1,048,576 − 65,535) in the
  server's first flight, between the preface SETTINGS and the SETTINGS ACK; with an explicit `connectionWindowSize`,
  the corresponding delta; configured to exactly 65,535, no stream-0 WINDOW_UPDATE at all.
- **Ceiling lifted:** upload a body larger than 65,535 bytes in DATA frames exceeding the old window without waiting
  for replenishes (up to the configured window); the request completes.
- **Replenish-at-half:** upload a known byte count and assert the client observes few stream-0 WINDOW_UPDATEs
  (bounded by `bytes / (window/2) + 1`), not one per frame.
- **Padding leak fixed:** send the entire configured window as maximally padded DATA frames across several requests;
  without the flow-controlled-length fix the connection window leaks to zero and the final request's body stalls
  (socket timeout); with it, all requests complete.
- **Ignored-DATA leak fixed:** open/reset a stream, send window-sized DATA at the closed (evicted) stream ID, then
  run a normal upload; it completes because the ignored DATA was debited and replenished.
- **Enforcement:** debit and replenish run inline on the reader thread, so the connection window can only be
  overrun by a single frame exceeding available credit — deterministic via config: `withMaxFrameSize(1 << 20)`,
  `connectionWindowSize` at the 65,535 minimum, `initialWindowSize` large (so the stream window does not trip
  first — the connection debit runs before the stream debit), then one ~70,000-byte DATA frame →
  `GOAWAY(FLOW_CONTROL_ERROR)`. With default config this path is a defensive backstop that compliant and even
  most non-compliant traffic cannot reach.

### 2.6 Impact summary

Clients uploading large bodies over non-trivial RTT gain throughput linearly with the configured window until their
link saturates (e.g. 100 MB at 50 ms RTT: ~76 s minimum today → link-limited with a 4 MB window). Two pre-existing
window-leak bugs are fixed as a byproduct of real accounting. Small-request and download-only clients see no change.

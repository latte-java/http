# HTTP/2 Connection Redesign

- **Created:** 2026-07-03
- **Status:** Implemented
- **Branch:** `http2/refactor-connection`

## 1. Problem

`HTTP2Connection` is 1,086 lines and owns every concern of the protocol: connection setup, SETTINGS negotiation, the
frame-dispatch loop, all nine frame-type handlers, stream lifecycle policy (creation, monotonicity, recently-closed
memory, concurrency cap), HPACK codec ownership, header-block accumulation, request construction, header validation,
handler-thread spawning, the response output protocol (as an inner class), error emission (GOAWAY vs. RST_STREAM), and
socket teardown. Error handling is smeared across the frame loop as a mix of `goAway()` + `break`, `return`, and a
`goawaySent` flag check at the loop bottom. The class is hard to review against RFC 9113 because the per-frame rules,
the per-stream rules, and the connection rules are interleaved in one method.

## 2. Goals and non-goals

**Goals**

1. Reduce `HTTP2Connection` to an orchestrator: initialize, negotiate, dispatch loop, tear down (~250 lines).
2. Move frame semantics into per-frame handler classes implementing a common `HTTP2FrameHandler` interface.
3. Make `HTTP2Stream` the authority on its own lifecycle: it validates every frame against its RFC 9113 §5.1 state
   machine and decides what the frame means.
4. Make `HTTP2StreamRegistry` the authority on the stream *roster*: live streams, closed-stream memory,
   `highestSeenStreamId`, and the `MAX_CONCURRENT_STREAMS` cap. The registry answers "give me stream N" — it never
   decides what a frame means.
5. Replace imperative error emission with a sealed `HTTP2Result` returned by every handler; the dispatch loop is the
   single place that emits GOAWAY/RST_STREAM and decides loop exit.
6. Preserve wire behavior: the full HTTP/2 test suite (h2spec batches, security, flow control, rate limits — ~4,800
   lines) passes unchanged, except where a test asserts on internal APIs that moved (§10).

**Non-goals**

- No new protocol features (server push, PRIORITY scheduling, keep-alive PING).
- No changes to `HTTP2FrameWriter`, `HTTP2WriterThread`, `HTTP2Settings`, `HTTP2ConnectionWindow`,
  `HTTP2RateLimitsTracker`, `HTTP2InputStream`, `HTTP2OutputStream`, `HTTP2ErrorCode`, or the HPACK classes.
  (`HTTP2FrameReader` is the one codec change: it gains header-block assembly — §4.11.)
- No changes to configuration or public API. Everything here stays in the unexported `server/internal/h2` package.

## 3. Architecture overview

```
run()
 ├─ initialize: BufferedOutputStream, HTTPBuffers, HTTP2FrameWriter, HTTP2FrameReader
 ├─ HTTP2Tools.negotiateSettings(...)          — SETTINGS exchange → HTTP2Result; on error run() emits GOAWAY
 │                                                directly + half-closes; on OK run() rebuilds the frame writer
 │                                                if the peer allows larger frames
 ├─ start HTTP2WriterThread
 ├─ wire per-connection collaborators:
 │    HPACK codecs → stream frame handlers → HTTP2StreamRegistry → HTTP2ConnectionFrameHandler
 └─ dispatch loop:
      frame = reader.readFrame()   — complete frames only; HEADERS+CONTINUATION assembled in the reader (§4.11)
      handler = frame.streamId() == 0 ? connectionFrameHandler : registry.lookup(frame.streamId())
      result  = handler.handleFrame(frame)
      Ok → continue   StreamError → RST_STREAM   ConnectionError → GOAWAY + exit   Shutdown → exit
```

Dispatch is uniform: everything is an `HTTP2FrameHandler`. Stream-0 frames go to the connection frame handler;
everything else goes to the stream the registry returns. The registry always returns a stream — a live one, or a
transient flyweight in the correct RFC state (`IDLE` or `CLOSED`) for IDs it does not have, so the stream state machine
is the single place frame legality is decided. This matches RFC 9113's model, where every stream ID has a state at all
times and `IDLE`/`CLOSED` are real states.

## 4. New and changed types

All types live in `org.lattejava.http.server.internal.h2`. New files carry the MIT `The Latte Project` header. Names
keep the `HTTP2` prefix: a class literally named `Stream` would shadow `java.util.stream.Stream` under the project's
`import module java.base` convention.

### 4.1 `HTTP2Result` (new, sealed interface)

The return value of every frame handler. The dispatch loop is the only consumer.

```java
public sealed interface HTTP2Result {
  HTTP2Result OK = new Ok();
  HTTP2Result SHUTDOWN = new Shutdown();

  record Ok() implements HTTP2Result {}

  record ConnectionError(HTTP2ErrorCode code) implements HTTP2Result {}   // → GOAWAY(code), exit loop

  record StreamError(int streamId, HTTP2ErrorCode code) implements HTTP2Result {}  // → RST_STREAM, continue

  record Shutdown() implements HTTP2Result {}                             // peer GOAWAY → exit loop cleanly
}
```

This removes the `goawaySent`-flag loop-bottom check, the `break`-vs-`return` distinction, and the
`HTTP2SettingsException` catch wrapper — `HTTP2ConnectionFrameHandler` converts that exception to
`ConnectionError(e.errorCode)` itself. `goAway()` stays idempotent in `HTTP2Connection` as a backstop (the
`Throwable` catch still emits `GOAWAY(INTERNAL_ERROR)`).

### 4.2 `HTTP2FrameHandler` (new, interface)

```java
public interface HTTP2FrameHandler {
  HTTP2Result handleFrame(HTTP2Frame frame) throws IOException;
}
```

Implemented by `HTTP2ConnectionFrameHandler` and `HTTP2Stream` — the two dispatch targets. (The four stream-frame
handlers of §4.6 are invoked by the stream with `(stream, frame)` and are plain collaborators, not
`HTTP2FrameHandler` implementations.) Handlers are
**per-connection instances** wired once in `run()` — the logic is stateless but the dependencies (writer queue, rate
limits, HPACK state, settings, registry) are per-connection, so constructor injection replaces threading a context
parameter through every call.

### 4.3 `HTTP2ConnectionFrameHandler` (new)

Handles every frame the loop routes to stream 0. One class with a private method per frame type — the connection-level
frames are each under ~25 lines, so separate classes per type would be fragmentation without isolation benefit.

| Frame | Behavior (unchanged from today) |
|---|---|
| SETTINGS | ACK-flagged: ignore. Rate limit → `ConnectionError(ENHANCE_YOUR_CALM)`. Apply payload (`HTTP2SettingsException` → `ConnectionError(e.errorCode)`); on `INITIAL_WINDOW_SIZE` delta, walk `registry.liveStreams()` adjusting send windows + `notifyAll` (§6.9.2); enqueue SETTINGS ACK. |
| PING | ACK-flagged: ignore. Rate limit → `ConnectionError(ENHANCE_YOUR_CALM)`. Else enqueue PING ACK. |
| WINDOW_UPDATE (id 0) | Rate limit → `ConnectionError(ENHANCE_YOUR_CALM)` (checked first, as today). Zero increment → `ConnectionError(PROTOCOL_ERROR)`; overflow past 2³¹−1 → `ConnectionError(FLOW_CONTROL_ERROR)`; else increment `connectionSendWindow`. |
| GOAWAY | `SHUTDOWN`. |
| DATA / HEADERS (id 0) | `ConnectionError(PROTOCOL_ERROR)` (§6.1, §6.2). CONTINUATION never reaches dispatch (§4.11). |
| PUSH_PROMISE | `ConnectionError(PROTOCOL_ERROR)` — clients must not push. |
| UnknownFrame | `OK` (§5.5). |

Dependencies: writer thread, rate-limits tracker, peer settings, connection send window, registry, logger.

### 4.4 `HTTP2StreamRegistry` (new)

Owns the roster and nothing else: the live-stream map, the recently-closed deque (cap 100), `highestSeenStreamId`, and
the `MAX_CONCURRENT_STREAMS` bound. It classifies IDs; it never interprets frames.

```java
public class HTTP2StreamRegistry {
  HTTP2Stream lookup(int streamId);      // live stream, or a transient flyweight in IDLE or CLOSED state
  boolean open(HTTP2Stream stream);      // registers a stream; false when live count >= maxConcurrentStreams;
                                         // bumps highestSeenStreamId
  void remove(int streamId);             // deregister only — handler-thread completion path (no closed memory)
  void close(int streamId);              // deregister + record in recently-closed memory — reader-thread path
  Collection<HTTP2Stream> liveStreams(); // for the SETTINGS window-delta walk
  int highestSeenStreamId();             // for GOAWAY last-stream-id (volatile; read by acceptor thread)
}
```

`open` bumps `highestSeenStreamId` **even when it refuses** at the cap — the ID is consumed either way, so a client
that retries the same ID gets today's monotonicity `PROTOCOL_ERROR` (RFC 9113 permits retrying a refused request only
on a *new* stream).

`lookup` classification, in order:

1. Live map hit → that stream.
2. ID in recently-closed memory → transient stream in `CLOSED`, `remembered = true`.
3. ID ≤ `highestSeenStreamId` → transient stream in `CLOSED`, `remembered = false` (closed long ago or never
   legally opened — even IDs land here too once `highestSeenStreamId` passes them).
4. ID > `highestSeenStreamId` → transient stream in `IDLE`.

Transient streams are cheap throwaway objects constructed with the same handler bundle as live streams (§4.5); most are
consulted for one frame and discarded. The `remove`/`close` split preserves today's semantics exactly: streams that
complete normally on a handler thread are deregistered without entering closed memory (a later HEADERS reuse of that ID
fails the monotonicity classification, rule 3), while reader-thread closures (RST_STREAM) are remembered for §5.1
STREAM_CLOSED detection. The live map stays a `ConcurrentHashMap` (handler threads deregister; the reader walks it);
the deque and `highestSeenStreamId` bump remain reader-thread-confined, `highestSeenStreamId` volatile for
`shutdown()`.

### 4.5 `HTTP2Stream` (changed)

Keeps: state machine (`State`, `Event`, `transition`), send/receive windows, content-length accounting, request
reference. Gains:

- **`implements HTTP2FrameHandler`.** `handleFrame(frame)` first validates the frame against the current state (and
  the `remembered` bit for transient CLOSED streams), returning the appropriate `HTTP2Result` for illegal frames; legal
  frames are delegated to the per-connection frame handlers (§4.6). The full decision matrix is §5.
- **Its body pipe.** The `Map<Integer, BlockingQueue<byte[]>> streamPipes` connection field is deleted; the pipe
  becomes a stream field. One roster, not two parallel maps.
- **Construction state.** `streamId`, window sizes, `State` (IDLE for new/transient-idle, CLOSED for transient-closed),
  `remembered` flag, the handler bundle, and the registry back-reference (§4.6 wiring).

The `transition()` table stops throwing `IllegalStateException` for frame-driven events — those become `HTTP2Result`
errors decided in `handleFrame` before the transition is applied. It still throws for programming errors (events the
server itself generates out of order).

### 4.6 Stream-frame handlers (new, four classes)

Per-connection instances shared by all streams on the connection; `HTTP2Stream.handleFrame` delegates to them after
state validation, passing itself. They contain today's `finalizeHeaderBlock`, `handleData`, stream-level
`handleWindowUpdate`, and `handleRSTStream` logic. (The accumulation halves of today's `handleHeadersFrame` and
`handleContinuationFrame` move into the reader's assembly loop, §4.11 — no handler sees a fragment.)

- **`HTTP2HeaderFrameHandler`** — HEADERS, which always carries a complete header block (§4.11). Opening path:
  `stream.open()`; refusal at the cap → `StreamError(REFUSED_STREAM)`, but the block is still HPACK-decoded
  and discarded for decoder synchronization (§9) — no pipe/request/thread allocations. Accepted path: HPACK decode
  (failure → `ConnectionError(COMPRESSION_ERROR)`), `HTTP2Tools.validateHeaders`, then either the trailers path
  (deliver to request, EOF the pipe, `RECV_HEADERS_END_STREAM`) or the new-request path (`HTTP2Tools.buildRequest`,
  content-length capture, state transition, input-stream setup — `EmptyHTTPInputStream` when END_STREAM rode the
  HEADERS — then spawn an `HTTP2HandlerDelegate` virtual thread). Increments the shared handled-requests counter.
- **`HTTP2DataFrameHandler`** — empty-DATA rate limit → `ConnectionError(ENHANCE_YOUR_CALM)`; content-length overrun →
  `StreamError(PROTOCOL_ERROR)`; pipe offer with `handlerReadTimeout`, timeout → `StreamError(CANCEL)` +
  `stream.deregister()`; END_STREAM → length check, EOF sentinel, `RECV_DATA_END_STREAM`; replenish-at-half-window
  WINDOW_UPDATEs for the stream and connection windows.
- **`HTTP2WindowUpdateFrameHandler`** — zero increment → `StreamError(PROTOCOL_ERROR)`; overflow →
  `StreamError(FLOW_CONTROL_ERROR)`; else increment send window + `notifyAll`.
- **`HTTP2RSTStreamFrameHandler`** — rapid-reset rate limit → `ConnectionError(ENHANCE_YOUR_CALM)`;
  `RECV_RST_STREAM` (already-CLOSED tolerated, logged); `stream.close()`; EOF the pipe.

**Wiring:** handlers never reference the registry. The only roster operations they perform — open, deregister,
close — always have the stream in hand, and every `HTTP2Stream` carries a final back-reference to the registry that
created it (the registry passes `this` at materialization), exposed as narrow wrappers: `stream.open()`,
`stream.deregister()`, `stream.close()`. Construction in `run()` is therefore linear — stream-frame handlers (writer,
rate limits, codecs, config) → registry (handler bundle) → connection frame handler (registry) — with no two-phase
wiring step. The stream ↔ registry reference cycle exists only in the runtime object graph, where it is harmless.

### 4.7 `HTTP2Tools` (new, static utilities)

Stateless functions extracted from `HTTP2Connection`, all pure given their parameters:

- `negotiateSettings(reader, frameWriter, out, localSettings, peerSettings, logger)` → `HTTP2Result` — sends local
  SETTINGS; requires the peer's first frame to be a non-ACK SETTINGS, else `ConnectionError(PROTOCOL_ERROR)`; applies
  the peer payload (`HTTP2SettingsException` → `ConnectionError(e.errorCode)` — today that case is only logged, a
  small conformance improvement); maps first-frame `FrameSizeException`/`ProtocolException` to the same codes the
  dispatch loop uses (today: silent close); sends the SETTINGS ACK; returns `OK`. Pure protocol over streams — no
  socket, no buffers. Error *emission* is the caller's job: `run()` reacts
  to a `ConnectionError` with `sendGoAwayDirect` (the writer thread does not exist yet) plus the `shutdownOutput()`
  half-close (the h2spec FIN-vs-RST dance), then returns. On `OK`, `run()` regrows the frame-write buffer and rebuilds
  the frame writer when the peer's MAX_FRAME_SIZE exceeds ours (RFC 9113 §4.2), then starts the writer thread — which
  is why the thread starts only after negotiation.
- `validateHeaders(fields, isTrailer)` — the §8.1.2.* checks, returning the offending rule or null (the caller maps a
  violation to `StreamError(PROTOCOL_ERROR)`).
- `buildRequest(fields, context, contextPath, scheme, port, remoteAddress)` — pseudo-header mapping to `HTTPRequest`.

### 4.8 `HTTP2HandlerDelegate` (new)

The extracted body of today's `spawnHandlerThread` `Runnable`: registers itself in the connection's handler-thread set,
builds the `HTTP2OutputProtocol` + `HTTPOutputStream`, invokes the application `HTTPHandler`, closes output,
`SEND_DATA_END_STREAM` (already-reset tolerated), deregisters the stream — plus the two existing error paths
(`HTTPProcessingException` → status-coded error response + `RST_STREAM(NO_ERROR)` when the client is still uploading;
`Exception` → best-effort `RST_STREAM(INTERNAL_ERROR)` via `tryEnqueue`). Spawned by `HTTP2HeaderFrameHandler` as
`Thread.ofVirtual().name("h2-handler-" + streamId)`.

### 4.9 `HTTP2OutputProtocol` (extracted)

Today's inner class, moved to a top-level class unchanged in behavior. Constructor takes what it read from the outer
class: response, stream, encoder, writer thread, connection send window, peer max-frame-size, logger.

### 4.10 `HTTP2Connection` (rewritten)

What remains: fields for the socket/config/context/instrumenter/listener/throughput/input-stream, the
handler-thread set, the handled-requests counter, `readerThread`, `state`, volatile `writer` and `registry`
references, and:

- `run()` — initialization, `negotiateSettings`, writer start, collaborator wiring, the dispatch loop below, and the
  unchanged teardown `finally` (writer sentinel + join, handler-thread interrupts, `shutdownOutput`/drain/close).
- `goAway(code)` (idempotent), `rstStream(id, code)`, and the pre-writer-thread `sendGoAwayDirect(frameWriter, out,
  code)` used only for negotiation failures — the connection's three emission primitives.
- `shutdown()` — unchanged, reading `registry.highestSeenStreamId()` (guarding null registry, as with `writer` today).
- `HTTPConnection` accessors.

```java
frames:
while (true) {
  state = HTTPConnection.State.Read;
  if (writer.isClosed()) break;

  HTTP2Frame frame;
  try {
    frame = reader.readFrame();  // §4.11 — HEADERS arrives as one complete, assembled header block
  } catch (HTTP2FrameReader.FrameSizeException e) {
    goAway(HTTP2ErrorCode.FRAME_SIZE_ERROR);
    break;
  } catch (HTTP2FrameReader.HeaderListSizeException e) {
    goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    break;
  } catch (HTTP2FrameReader.ProtocolException e) {
    goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
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
      return;
    }
  }
}
```

The `Throwable` catch (→ `GOAWAY(INTERNAL_ERROR)`) and the writer `requestStop()` `finally` are unchanged.

### 4.11 `HTTP2FrameReader` (changed): header-block assembly

`readFrame()` no longer surfaces HEADERS fragments or CONTINUATION frames. When it parses a HEADERS frame without
END_HEADERS, it keeps reading within the same call and returns one complete logical `HeadersFrame`:

- The next frame must be CONTINUATION on the same stream — anything else, of any type on any stream, is a
  `ProtocolException` (§4.3 interleaving), which the dispatch loop already maps to `GOAWAY(PROTOCOL_ERROR)`. Identical
  wire behavior to today's connection-level guard.
- Fragments are concatenated (padding and PRIORITY prefixes already stripped by the raw parse). When the cumulative
  block exceeds `maxHeaderListSize` — a new constructor parameter — a new `HeaderListSizeException` is thrown, mapped
  to `GOAWAY(ENHANCE_YOUR_CALM)`: today's CVE-2024-27316 guard, one layer down.
- The returned `HeadersFrame` carries END_HEADERS and the END_STREAM flag of the initial HEADERS frame — the spec
  permits END_STREAM nowhere else in the sequence.
- A CONTINUATION with no open block is a `ProtocolException` (§6.10). CONTINUATION on stream 0 already throws in the
  raw parse. PUSH_PROMISE does not start assembly — clients must not push, and dispatch rejects the frame itself.

The `ContinuationFrame` record becomes internal to the assembly loop and never reaches dispatch, the §5 matrix, or the
stream. The reader stays stateless *between* calls — assembly is a loop within one `readFrame()` invocation — and
blocking mid-block is fine because §4.3 forbids the connection from processing anything else mid-block anyway. Memory
is bounded by `maxHeaderListSize`; a slow-drip CONTINUATION attack is covered by the existing `minimumReadThroughput`
enforcement on the underlying stream. Precedent: Go's `net/http2` `ReadMetaFrame` and Netty's header aggregation take
exactly this approach.

One observable timing shift: stream-level reactions to a HEADERS (e.g. the `REFUSED_STREAM` RST at the cap) are
emitted after the whole block is read rather than after its first fragment. Protocol-legal — the client must finish
sending the block regardless — and nothing in the suite asserts the earlier timing.

## 5. Frame × stream-state decision matrix

The authority for `HTTP2Stream.handleFrame`. "Remembered CLOSED" is a transient stream for an ID in recently-closed
memory; "forgotten CLOSED" is a transient for an ID ≤ `highestSeenStreamId` that is neither live nor remembered.
Preserves today's behavior case for case, with one recorded divergence: a zero-increment WINDOW_UPDATE aimed at an
idle or closed stream. Today's code checks the zero-increment before classifying the stream and answers
`RST_STREAM(PROTOCOL_ERROR)` in every state; this matrix classifies first, so that frame now gets the state's cell —
`ConnectionError(PROTOCOL_ERROR)` on an idle client stream (RFC 9113 §5.1: any such frame on idle is a connection
error) and ignored on closed/idle-even streams (§5.1 permits it). The new behavior is closer to the RFC, no
conformance test exercises the corner, and it is kept deliberately.

| Frame | IDLE (transient) | OPEN / HALF_CLOSED_LOCAL (live) | HALF_CLOSED_REMOTE (live) | Remembered CLOSED | Forgotten CLOSED |
|---|---|---|---|---|---|
| HEADERS | Even ID → `ConnectionError(PROTOCOL_ERROR)`. Odd → open flow: `stream.open()` (cap → `StreamError(REFUSED_STREAM)`, §9), decode, build request | END_STREAM set → trailers path; else `StreamError(STREAM_CLOSED)` (§8.1) | `StreamError(STREAM_CLOSED)` | `ConnectionError(STREAM_CLOSED)` | `ConnectionError(PROTOCOL_ERROR)` (monotonicity §5.1.1) |
| DATA | Odd → `ConnectionError(PROTOCOL_ERROR)` (§5.1); even → `Ok` (ignore, §6.1) | Deliver to pipe (§4.6) | `StreamError(STREAM_CLOSED)` | `ConnectionError(STREAM_CLOSED)` | `Ok` (ignore, §6.1) |
| WINDOW_UPDATE | Odd → `ConnectionError(PROTOCOL_ERROR)`; even → `Ok` | Zero/overflow checks; else credit + notify | (same) | `Ok` | `Ok` |
| RST_STREAM | `ConnectionError(PROTOCOL_ERROR)` (§6.4) | Close: `RECV_RST_STREAM`, `stream.close()`, EOF pipe | (same) | `Ok` | `Ok` |
| PRIORITY | `Ok` (§5.3 advisory — including on idle streams) | `Ok` | `Ok` | `Ok` | `Ok` |
| PUSH_PROMISE | `ConnectionError(PROTOCOL_ERROR)` (clients must not push) | (same) | (same) | (same) | (same) |
| UnknownFrame | `Ok` (§5.5) | `Ok` | `Ok` | `Ok` | `Ok` |

Notes:

- CONTINUATION has no row: it never reaches dispatch (§4.11).
- Rate-limit triggers (PING/SETTINGS handled at connection level; RST_STREAM, empty DATA, WINDOW_UPDATE here) can
  upgrade any of the above to `ConnectionError(ENHANCE_YOUR_CALM)` — checks run in the same order as today (e.g.
  RST_STREAM rate limit before idle-stream detection).
- HEADERS on a live stream without END_STREAM, and trailer HEADERS on HALF_CLOSED_REMOTE, are `StreamError` — stream
  errors, not connection errors — matching today's `rstStream(STREAM_CLOSED)` path.

## 6. Why header-block contiguity lives in the frame reader

RFC 9113 §6.10 / §4.3: after HEADERS without END_HEADERS, the next frames on the **connection** — any type, any
stream, including stream-0 frames — must be CONTINUATIONs on the same stream until END_HEADERS. This rule cannot be
enforced by a stream: while stream 5's block is open, a PING arriving next is a *connection* error, and no stream
object sees frames addressed to stream 0 or to other streams. It is a wire-sequence property, so it is enforced at the
wire layer: `HTTP2FrameReader` assembles the sequence (§4.11) and everything above it — dispatch, registry, stream,
handlers — deals only in complete header blocks. CONTINUATION does not exist above the reader.

This also dissolves the refused-stream reachability problem: a stream rejected at the MAX_CONCURRENT_STREAMS cap is
never registered, but its header block arrives at `HTTP2HeaderFrameHandler` already complete, so decode-and-discard
(§9) needs no special routing.

## 7. Threading model and invariants

Unchanged from today; restated here because the refactor must preserve them:

- **Reader thread** (the connection's virtual thread): the dispatch loop, header-block assembly (inside
  `readFrame()`), all frame handlers, HPACK decode, registry classification, recently-closed memory, request
  construction, and handler-thread spawning.
- **Writer thread**: sole socket writer; producers enqueue frames. Started only after SETTINGS negotiation.
- **Handler virtual threads** (`HTTP2HandlerDelegate`): application handler, response frames via the writer queue,
  `stream.deregister()` on completion, HPACK **encoder** access under `synchronized (encoder)`, stream window waits under
  the stream monitor.
- **Acceptor thread**: `shutdown()` — writer enqueue + join, `registry.highestSeenStreamId()`.
- `HTTP2Stream` stays internally `synchronized`; the registry's live map stays `ConcurrentHashMap`;
  `highestSeenStreamId` and the `writer`/`registry` fields stay volatile.

## 8. Error handling summary

| Source | Path |
|---|---|
| Frame-parse and assembly violations (`FrameSizeException`, `ProtocolException`, `HeaderListSizeException`) | Caught at `readFrame()` → GOAWAY with the matching code, exit. |
| Handler-detected protocol violations | `HTTP2Result.ConnectionError` / `StreamError` → loop emits GOAWAY / RST_STREAM. |
| Peer SETTINGS parameter violations | Negotiation phase: `ConnectionError` returned to `run()`, which emits GOAWAY directly (no writer thread yet) and half-closes. Post-negotiation: `ConnectionError(e.errorCode)` from the SETTINGS handler. |
| Application handler exceptions | Inside `HTTP2HandlerDelegate` (unchanged): `HTTPProcessingException` → error response; else RST_STREAM(INTERNAL_ERROR). |
| Anything unhandled on the reader thread | `Throwable` catch → GOAWAY(INTERNAL_ERROR), teardown. |

`goAway` remains idempotent (first code wins); the writer-shutdown sentinel and teardown ordering are untouched.

## 9. Known quirk surfaced by this redesign: HPACK state on refused streams

Today, a HEADERS frame rejected by the MAX_CONCURRENT_STREAMS cap returns before accumulating its fragment, and the
stream's CONTINUATION frames then append to a stale connection-level accumulator. RFC 9113 §4.3 requires a receiver to
process every header block — even for streams it resets — because HPACK dynamic-table updates are connection state;
skipping a block desynchronizes the decoder and corrupts every subsequent request's headers.

The redesign makes the fix natural: the reader hands `HTTP2HeaderFrameHandler` the complete header block even for a
stream refused at the cap (§4.11), so the refused path simply HPACK-decodes the block (maintaining decoder state) and
discards the fields instead of building a request.
**This is the one deliberate behavior change in the rewrite**, with a dedicated test (open streams to the cap, send a
refused HEADERS using dynamic-table entries, verify the next accepted request decodes correctly).

## 10. Testing

**Module-level first.** Behavior is verified through the public surface — a started `HTTPServer` and raw HTTP/2
frames over a socket, the `HTTP2RawFrameTest` / h2spec-batch style — not by unit-testing internal classes. The
redesign's types (`HTTP2Result`, the handlers, the registry, `HTTP2Tools`, the reworked `HTTP2Stream`) get **no
dedicated internal tests**: every §5 matrix cell and §4.11 assembly rule is observable on the wire (errors as
GOAWAY/RST codes; "ignore" cells by the connection continuing to serve a subsequent request). Tests that reach into
internals would couple the suite to the exact structure this refactor exists to change.

- The existing socket-level suites (`HTTP2BasicTest`, `HTTP2H2SpecBatch3/4Test`, `HTTP2SecurityTest`,
  `HTTP2FlowControlTest`, `HTTP2RateLimitsTest`, `HTTP2GoawayTest`, `HTTP2RawFrameTest`, ALPN/h2c/preface/compression/
  header-validation tests) are the conformance net and must pass unmodified.
- New wire-level tests fill the gaps, each traceable to a §5 cell or §4.11 rule: frames on remembered-closed vs.
  forgotten-closed streams (including eviction past the 100-entry memory), REFUSED_STREAM at the cap and the
  monotonicity error on ID reuse, CONTINUATION assembly (interleaved frame mid-block including stream-0 frames, bare
  CONTINUATION, cumulative size over `maxHeaderListSize`, END_STREAM propagation from the initial HEADERS), negotiation
  failures (non-SETTINGS first frame, malformed SETTINGS parameter → GOAWAY with the right code — today a silent
  close), and the §9 HPACK-synchronization scenario (cap at 1, refused HEADERS carrying dynamic-table entries, next
  request must decode correctly).
- Internal-class tests are kept only where they already exist against APIs this redesign does not change: the HPACK
  suite, `HTTP2SettingsTest`, `HTTP2WriterCoalescingTest`, and `HTTP2FrameReaderTest`'s raw single-frame parsing cases
  (its construction gains the cap parameter, and lone-HEADERS-fragment cases are updated for the assembly contract —
  assembly *behavior* is covered at the wire).
- `HTTP2StreamStateMachineTest` is retired, not rewritten: frame-driven transitions are wire-observable and pinned by
  the matrix tests above.

## 11. Migration

Big-bang rewrite on `http2/refactor-connection`. All work stays on this branch until Brian has reviewed everything in
detail — merging is out of scope for this design:

1. Add the new types (`HTTP2Result`, `HTTP2FrameHandler`, `HTTP2StreamRegistry`, `HTTP2ConnectionFrameHandler`, the
   four stream-frame handlers, `HTTP2Tools`, `HTTP2HandlerDelegate`, top-level `HTTP2OutputProtocol`).
2. Extend `HTTP2FrameReader` with header-block assembly (§4.11); rewrite `HTTP2Stream` (state validation +
   delegation, pipe ownership) and `HTTP2Connection` (orchestrator).
3. Delete from `HTTP2Connection`: all `handle*` methods, `finalizeHeaderBlock`, `validateHeaders`,
   `buildRequestFromHeaders`, `spawnHandlerThread`, the inner `HTTP2OutputProtocol`, `streamPipes`, `streams`,
   `recentlyClosedStreams`, `highestSeenStreamId`, `isRecentlyClosed`/`markClosed`.
4. Update the suite per §10: retire `HTTP2StreamStateMachineTest`, update `HTTP2FrameReaderTest` construction and
   lone-fragment cases, add the new wire-level tests.
5. `latte clean int` green; h2spec suites are the acceptance gate.

House rules that apply: existing files in `internal/h2` are MIT `The Latte Project` — new files use the same header;
class-level Javadoc on surviving classes is preserved and updated, not dropped; 2-space indent, acronym naming,
alphabetized members, bracketed runtime values in error messages.

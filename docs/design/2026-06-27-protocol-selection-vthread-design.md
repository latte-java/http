# Protocol selection on the virtual thread — design

**Date:** 2026-06-27
**Status:** Approved for planning
**Scope:** `org.lattejava.http.server.internal` (acceptor, protocol selection, connection lifecycle) plus the `h1`/`h2` connection classes' names and shared interface.

## Problem

Protocol selection currently runs on the per-listener accept thread, which is a single platform thread (`HTTPServerThread`, one per listener). The accept loop calls `ProtocolSelector.select()` synchronously, and that method blocks on:

- `sslSocket.startHandshake()` — the full TLS handshake (multiple network round-trips) for the TLS path, so it can read the ALPN-selected protocol.
- `pushback.readNBytes(24)` — reading the HTTP/2 connection preface for the h2c prior-knowledge cleartext path.

The virtual thread is spawned only *after* `select()` returns. Because there is one accept thread per listener, a single slow or hostile client that completes the TCP handshake and then drags out the TLS handshake (or the preface) serializes connection acceptance for the entire listener. Each stall is bounded per-connection by `SO_TIMEOUT` (the initial-read timeout, default 2s), but the accept loop cannot accept any other connection during that window. This is the classic accept-loop anti-pattern: blocking I/O belongs on the per-connection worker, not the shared acceptor.

The blocking step was introduced with the HTTP/2 work: protocol selection needs the ALPN result (or the preface) *before* it can choose between HTTP/1.1 and HTTP/2, and that decision was placed on the accept thread. The decision must precede dispatch, but it does not need to run on the *accept* thread — it can run as the first action on the spawned virtual thread.

This change also takes the opportunity to make the naming of the connection types consistent and self-describing, since the two protocol handlers are peers but use different nouns today (`HTTP1Worker` vs `HTTP2Connection`).

## Goals

- The per-listener accept loop performs only non-blocking work. Every blocking step (TLS handshake, h2c preface peek) runs on the per-connection virtual thread.
- A slow/stalled handshake costs one cheap virtual thread, never the listener's accept thread.
- No behavior change to HTTP/1.1 or HTTP/2 request handling — only *where* protocol selection runs and *what the types are called*.
- Consistent, intention-revealing names across the connection types and the server-side lifecycle classes.

## Non-goals

- No changes to HPACK, frame codecs, or any `HTTP2*` protocol internals beyond renames already implied by the connection rename.
- No changes to the `Throughput*` streams, request/response parsing, or the handler contract.
- No unrelated refactoring outside the selection/lifecycle path.

## Architecture

### Accept loop (the renamed `HTTPServerAcceptorThread`)

The loop does only non-blocking work, then hands the socket to a virtual thread:

```
Socket clientSocket = socket.accept();
clientSocket.setSoTimeout(initialReadTimeout);
Throughput throughput = new Throughput(...);
ConnectionDispatcher dispatcher = new ConnectionDispatcher(clientSocket, configuration, context, instrumenter, listener, throughput);
Thread vthread = Thread.ofVirtual().name("HTTP client [" + clientSocket.getRemoteSocketAddress() + "]").unstarted(dispatcher);
clients.add(new ClientConnection(vthread, dispatcher, throughput));   // tracked before it can run
vthread.start();
```

Notable changes from today:

- No `startHandshake`, no `readNBytes`, and no `try/catch` around selection on the accept thread. The `SecurityTools.configureALPN(...)` call also leaves the acceptor (see below).
- The virtual thread is created `unstarted()`, registered in the `clients` deque, then started. This removes the existing benign race where a connection could finish before it was registered, and guarantees the reaper tracks the connection from the moment it can run.

### `ConnectionDispatcher` (new) — runs on the virtual thread

`ConnectionDispatcher implements HTTPConnection, Runnable`. The acceptor registers it immediately and starts it on the virtual thread. It performs the (now off-acceptor) blocking selection, builds the real protocol connection, and delegates the `HTTPConnection` contract to it:

```
private volatile HTTPConnection delegate;   // null until protocol is chosen
private final long startInstant = System.currentTimeMillis();

run():
  try {
    delegate = ProtocolSelector.select(socket, configuration, context, instrumenter, listener, throughput);  // BLOCKS here now
    delegate.run();
  } catch (IOException e) {
    logger.debug("Protocol selection failed; closing socket", e);
    closeQuietly(socket);                   // FD cleanup moves here from the acceptor
  }

state()              -> delegate != null ? delegate.state() : State.Negotiating
getSocket()          -> socket
getStartInstant()    -> delegate != null ? delegate.getStartInstant() : startInstant
getHandledRequests() -> delegate != null ? delegate.getHandledRequests() : 0
shutdown()           -> if (delegate != null) delegate.shutdown()
```

`delegate` is `volatile` because it is written by the virtual thread and read by the `ConnectionReaperThread` and the acceptor's shutdown loop.

### `ProtocolSelector` (unchanged logic, now called from the virtual thread)

`ProtocolSelector.select()` keeps its existing branch logic (TLS-ALPN dispatch and h2c prior-knowledge peek) and continues to return an `HTTPConnection`. It is kept as a separate stateless helper — rather than folded into the dispatcher — so the protocol-decision logic remains unit-testable without running a connection.

One addition: `select()` now performs `configureALPN()` for `SSLSocket`s itself, immediately before `startHandshake()`. ALPN configuration must precede the handshake, and the handshake now runs inside `select()` on the virtual thread, so the `configureALPN` call moves out of the acceptor. After this change the acceptor does no TLS work at all.

### New `HTTPConnection.State.Negotiating` (correctness-critical)

The reaper evicts `Read`-state connections whose measured throughput is below the configured minimum. During a TLS handshake, bytes flow on the raw socket but **not** through our `ThroughputInputStream` (the `SSLSocket` performs its own internal reads on the underlying stream), so a throughput sample taken mid-handshake reads ~0 bytes/s. If the dispatcher reported `Read` before its delegate existed, the reaper would evict legitimate in-progress handshakes.

Therefore the dispatcher reports a new state, `HTTPConnection.State.Negotiating`, until the delegate is built. The reaper only acts on `Read`, `Write`, and `Process`; any other state (today `KeepAlive`, now also `Negotiating`) falls through and is never evicted. The negotiation phase is bounded solely by `SO_TIMEOUT` — a stalled handshake read throws `SocketTimeoutException`, the dispatcher's catch closes the socket, and the dead thread is reaped. This matches today's `SO_TIMEOUT`-bounded behavior; it just runs on the virtual thread.

The `KeepAlive` state keeps its existing meaning and Javadoc (idle between requests on a persistent HTTP/1.1 socket); `Negotiating` gets its own Javadoc explaining why the throughput check must not apply during the handshake.

### `shutdown()` on the `HTTPConnection` interface

Today the acceptor's shutdown path is `if (client.runnable() instanceof HTTP2Connection h2) h2.shutdown();`. Since the registered runnable is now the dispatcher, `shutdown()` becomes a method on the `HTTPConnection` interface:

- `HTTP2Connection.shutdown()` — enqueue `GOAWAY(NO_ERROR)` (current behavior).
- `HTTP1Connection.shutdown()` — no-op (HTTP/1.1 shutdown relies on socket close / interrupt).
- `ConnectionDispatcher.shutdown()` — delegate to `delegate` if set, else no-op.

The acceptor's shutdown loop then calls `client.connection().shutdown()` unconditionally, removing the `instanceof`.

## Naming map

| Current                                            | New                                           | Rationale                                                                                      |
|----------------------------------------------------|-----------------------------------------------|------------------------------------------------------------------------------------------------|
| `ClientConnection` (interface)                     | `HTTPConnection`                              | Symmetric supertype of `HTTP1Connection` / `HTTP2Connection`.                                  |
| `HTTP1Worker`                                      | `HTTP1Connection`                             | Peer of `HTTP2Connection`; same noun.                                                          |
| `HTTP2Connection`                                  | *(unchanged)*                                 | Already correct.                                                                               |
| `HTTP1Worker.WorkerState` (private enum)           | *deleted* → use `HTTPConnection.State`        | Removes the duplicate enum and the switch conversion in `state()`.                             |
| —                                                  | `HTTPConnection.State.Negotiating` (new)      | Handshake phase; reaper skips the throughput check.                                            |
| `HTTPServerThread`                                 | `HTTPServerAcceptorThread`                    | Names its job (the accept loop); keeps the `HTTPServer*` family and `Thread` suffix.           |
| `HTTPServerThread.HTTPServerCleanerThread` (inner) | `ConnectionReaperThread`                      | It reaps dead / slow / timed-out connections; keeps the `Thread` suffix.                       |
| `HTTPServerThread.ClientInfo` (record)             | `ClientConnection`                            | The freed-up name; the server's per-client tracking record `(thread, connection, throughput)`. |
| —                                                  | `ConnectionDispatcher` (new)                  | Virtual-thread Runnable: negotiates the protocol, then delegates to the real connection.       |
| `ProtocolSelector`                                 | *(unchanged; now also calls `configureALPN`)* | Stateless protocol-decision helper.                                                            |

Note: the `ClientConnection` record holds a field of type `HTTPConnection`, read as `client.connection().state()`, etc. The two names denote distinct concepts — the server's per-client bookkeeping versus the protocol handler — and do not collide in use.

`record ClientConnection(Thread thread, HTTPConnection connection, Throughput throughput)` retains the existing helper accessors (`getAge()`, `getHandledRequests()`, `getStartInstant()`), now reading through `connection()`.

## Error handling and lifecycle

- **Selection failure (FD cleanup):** moves into `ConnectionDispatcher.run()`'s catch, which closes the socket. The dead virtual thread is then removed by `ConnectionReaperThread` on its next pass (it already removes `!isAlive` entries).
- **Server shutdown:** the acceptor's exit loop calls `client.connection().shutdown()` for every tracked connection (GOAWAY for HTTP/2, no-op for HTTP/1.1), then `client.thread().interrupt()`. A connection still negotiating during shutdown has a no-op `shutdown()` and is unblocked by the interrupt or `SO_TIMEOUT`.
- **`HTTP2Connection` constructor cleanup:** change the boxed `Boolean prefaceAlreadyConsumed` parameter to a primitive `boolean prefaceConsumed`. The `null` case disappears because the dispatcher (via `ProtocolSelector`) always passes an explicit value. Small change, same blast radius.

## Testing

Existing tests must remain green; together they exercise every branch of `select()` through the new dispatcher:

- `CoreTest` — HTTP/1.1 cleartext and the TLS path via `BaseTest`.
- `HTTP2ConnectionPrefaceTest` — TLS→h2 preface handling.
- `HTTP2H2cPriorKnowledgeTest` — h2c prior-knowledge match and non-match (HTTP/1.1) fallback.
- `HTTP2H2SpecBatch4Test`, `HTTP2IdleStreamErrorsTest` — HTTP/2 conformance over the selected path.

New coverage:

- **Acceptor stays non-blocking:** a client that completes the TCP connection but stalls the TLS handshake must not delay acceptance of a second client on the same listener. This is the regression the change targets.
- **Negotiation is reaped only by `SO_TIMEOUT`:** a connection stuck in the handshake is closed by `SO_TIMEOUT`, and a connection reporting `Negotiating` is not evicted by the reaper's throughput check.

Run with `latte test`; CI form `latte clean int --excludePerformance --excludeTimeouts`.

# Protocol Selection on the Virtual Thread Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move protocol selection (TLS-ALPN handshake / h2c preface peek) off the single per-listener accept thread and onto the per-connection virtual thread, and rename the connection types and lifecycle classes so they are consistent and self-describing.

**Architecture:** The acceptor does only non-blocking work, then hands the raw socket to a new `ConnectionDispatcher` that runs on a virtual thread. The dispatcher performs the blocking protocol negotiation, builds the real `HTTP1Connection`/`HTTP2Connection`, and delegates the `HTTPConnection` contract to it. The dispatcher is registered with the reaper before it starts, reporting a new `Negotiating` state during the handshake so the slow-reader throughput check does not evict in-progress handshakes.

**Tech Stack:** Java 21, virtual threads, blocking I/O (not NIO), TestNG, the Latte build tool. Zero production dependencies.

## Global Constraints

- Java 21 (`project.latte`). Use `latte` for all builds — never Maven/Gradle.
- Indentation: 2 spaces; continuation indent: 4 spaces. Target line length 120; do not wrap before 120.
- Acronyms are fully uppercase in identifiers (`HTTPConnection`, not `HttpConnection`).
- Within a class: static fields, instance fields (each group ordered by visibility then alphabetically), constructors (by parameter count), then methods (by visibility then alphabetically). No blank lines between fields.
- Prefer module imports (`import module java.base;`) over class imports.
- Runtime values in log/exception/`toString` messages go in square brackets: `[value]`.
- Copyright headers: brand-new files use the MIT SPDX header `Copyright (c) 2026 The Latte Project`. Files inherited from java-http keep their Apache-2.0 header and original `@author` — never rewrite an inherited header. `HTTPServerThread.java`, `HTTP1Worker.java`, `BaseTest.java`, `CoreTest.java`, `HTTP2H2SpecBatch4Test.java` are Apache-2.0; `ClientConnection.java`, `ProtocolSelector.java`, `HTTP2Connection.java` carry their existing headers — preserve them through renames.
- Tests are TestNG. Timing-sensitive tests use `@Test(groups = "timeouts")` and are excluded from the CI fast run.
- Commit after every task with a Conventional Commit subject. Work stays on the current branch `http2/fork-then-decide`; never commit to `main`.
- Spec: `docs/design/2026-06-27-protocol-selection-vthread-design.md`.

---

## File Structure

**Renamed (git mv + content):**
- `…/internal/ClientConnection.java` → `…/internal/HTTPConnection.java` — the connection interface (now `extends Runnable`, gains `Negotiating` state + `shutdown()`).
- `…/internal/h1/HTTP1Worker.java` → `…/internal/h1/HTTP1Connection.java` — HTTP/1.1 connection handler.
- `…/internal/HTTPServerThread.java` → `…/internal/HTTPServerAcceptorThread.java` — the accept loop; its inner cleaner → `ConnectionReaperThread`, its record `ClientInfo` → `ClientConnection`.

**Created:**
- `…/internal/ConnectionDispatcher.java` — virtual-thread Runnable that negotiates the protocol then delegates.
- `…/tests/server/AcceptorDispatchTest.java` — regression tests for non-blocking accept + negotiation reaping.

**Modified (no rename):**
- `…/internal/ProtocolSelector.java` — gains `configureALPN`; return type follows the interface rename.
- `…/internal/h2/HTTP2Connection.java` — interface rename; `@Override shutdown()`; `Boolean prefaceAlreadyConsumed` → `boolean prefaceConsumed`.
- `…/server/HTTPServer.java` — `HTTPServerThread` → `HTTPServerAcceptorThread`.
- `…/tests/server/CoreTest.java`, `…/tests/server/HTTP2H2SpecBatch4Test.java` — comment/Javadoc references only.

Path prefix `…` = `src/main/java/org/lattejava/http` (or `src/test/java/org/lattejava/http` for tests).

---

## Task 1: Establish the `HTTPConnection` interface

Rename the `ClientConnection` interface to `HTTPConnection`, make it `extends Runnable` (so callers can run a connection without casting), and update all implementors and references. Pure structural change — no behavior change.

**Files:**
- Rename: `src/main/java/org/lattejava/http/server/internal/ClientConnection.java` → `HTTPConnection.java`
- Modify: `…/internal/h1/HTTP1Worker.java`, `…/internal/h2/HTTP2Connection.java`, `…/internal/HTTPServerThread.java`, `…/internal/ProtocolSelector.java`

**Interfaces:**
- Produces: `public interface HTTPConnection extends Runnable` with methods `long getHandledRequests()`, `Socket getSocket()`, `long getStartInstant()`, `State state()`, and nested `enum State { KeepAlive, Process, Read, Write }`.

- [ ] **Step 1: Rename the interface file and declaration**

```bash
git mv src/main/java/org/lattejava/http/server/internal/ClientConnection.java \
       src/main/java/org/lattejava/http/server/internal/HTTPConnection.java
```

In `HTTPConnection.java`, change the declaration (preserve the existing Apache/`@author` header and the `State` enum + its Javadoc):

```java
public interface HTTPConnection extends Runnable {
```

- [ ] **Step 2: Update `HTTP1Worker` to implement `HTTPConnection`**

In `…/internal/h1/HTTP1Worker.java`:
- Line 31: `public class HTTP1Worker implements ClientConnection, Runnable {` → `public class HTTP1Worker implements HTTPConnection {`
- In `state()` (lines 308–314), replace every `ClientConnection.State` with `HTTPConnection.State`.

- [ ] **Step 3: Update `HTTP2Connection` to implement `HTTPConnection`**

In `…/internal/h2/HTTP2Connection.java`, replace every `ClientConnection` with `HTTPConnection` (occurrences: class declaration line 29 → `implements HTTPConnection, Runnable`; the `state` field line 80; `state()` return type line 152; line 271). Leave `, Runnable` in place here — it is redundant but harmless; removing it is optional.

- [ ] **Step 4: Update the acceptor and selector references**

In `…/internal/HTTPServerThread.java`:
- Line 106: `ClientConnection conn;` → `HTTPConnection conn;`
- Line 121: `.start((Runnable) conn);` → `.start(conn);` (cast no longer needed — `HTTPConnection extends Runnable`)
- Line 175 (record field type): `ClientConnection runnable` → `HTTPConnection runnable`
- Line 216: `ClientConnection worker = client.runnable();` → `HTTPConnection worker = client.runnable();`
- Lines 217, 229, 236, 242: `ClientConnection.State` → `HTTPConnection.State`

In `…/internal/ProtocolSelector.java`:
- Line 34 Javadoc: `{@link ClientConnection}` → `{@link HTTPConnection}`
- Line 37: `public static ClientConnection select(` → `public static HTTPConnection select(`

- [ ] **Step 5: Build**

Run: `latte clean build`
Expected: BUILD SUCCESS, no compile errors.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: Rename ClientConnection interface to HTTPConnection and extend Runnable"
```

---

## Task 2: Rename `HTTP1Worker` → `HTTP1Connection`

Make the HTTP/1.1 handler a naming peer of `HTTP2Connection`. Mechanical rename across the file, its constructor, its logger, and references.

**Files:**
- Rename: `…/internal/h1/HTTP1Worker.java` → `…/internal/h1/HTTP1Connection.java`
- Modify: `…/internal/ProtocolSelector.java`, `…/tests/server/CoreTest.java`, `…/tests/server/HTTP2H2SpecBatch4Test.java`

**Interfaces:**
- Consumes: `HTTPConnection` (Task 1).
- Produces: `public class HTTP1Connection` with both existing constructors unchanged in signature except the class name.

- [ ] **Step 1: Rename the file**

```bash
git mv src/main/java/org/lattejava/http/server/internal/h1/HTTP1Worker.java \
       src/main/java/org/lattejava/http/server/internal/h1/HTTP1Connection.java
```

- [ ] **Step 2: Rename the class, constructors, and logger**

In `HTTP1Connection.java` (preserve the Apache/`@author` header):
- Line 31: `public class HTTP1Worker implements HTTPConnection {` → `public class HTTP1Connection implements HTTPConnection {`
- Both constructors (lines 56 and 66): `public HTTP1Worker(` → `public HTTP1Connection(`
- Line 75: `getLogger(HTTP1Worker.class)` → `getLogger(HTTP1Connection.class)`

- [ ] **Step 3: Update `ProtocolSelector` construction sites**

In `…/internal/ProtocolSelector.java`, replace all three `new HTTP1Worker(` (lines 50, 65, 78) with `new HTTP1Connection(`.

- [ ] **Step 4: Update stale comments in tests**

- `…/tests/server/CoreTest.java` line 358: `HTTP1Worker.state()` → `HTTP1Connection.state()`
- `…/tests/server/HTTP2H2SpecBatch4Test.java` line 154: `{@link HTTP1Worker}` → `{@link HTTP1Connection}`

- [ ] **Step 5: Build**

Run: `latte clean build`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: Rename HTTP1Worker to HTTP1Connection"
```

---

## Task 3: Drop `WorkerState`; use `HTTPConnection.State` directly in `HTTP1Connection`

Remove the private `WorkerState` enum and the switch in `state()` — the duplicate of `HTTPConnection.State`. Behavior is unchanged.

**Files:**
- Modify: `…/internal/h1/HTTP1Connection.java`

- [ ] **Step 1: Replace the field**

Line 54: `private volatile WorkerState workerState;` → `private volatile HTTPConnection.State state;`

- [ ] **Step 2: Replace the constructor initializer**

Line 77: `this.workerState = WorkerState.Read;` → `this.state = HTTPConnection.State.Read;`

- [ ] **Step 3: Replace all assignments and reads of `workerState` in `run()` and the catch blocks**

Replace each occurrence as follows (lines 116, 122, 171, 181, 195, 196, 235, 237, 264, 268, 269, 271, 273):
- `workerState = WorkerState.Read` → `state = HTTPConnection.State.Read`
- `workerState = WorkerState.Write` → `state = HTTPConnection.State.Write`
- `workerState = WorkerState.Process` → `state = HTTPConnection.State.Process`
- `workerState = WorkerState.KeepAlive` → `state = HTTPConnection.State.KeepAlive`
- `workerState == WorkerState.KeepAlive` → `state == HTTPConnection.State.KeepAlive`
- `workerState == WorkerState.Read` → `state == HTTPConnection.State.Read`
- Any bare `workerState` used only for logging (e.g. lines 196, 237, 264, 271, 273) → `state`

After this step there must be zero occurrences of `workerState` or `WorkerState` except the enum declaration removed in Step 5. Verify: `grep -n "workerState\|WorkerState" src/main/java/org/lattejava/http/server/internal/h1/HTTP1Connection.java` should show only the enum at the bottom.

- [ ] **Step 4: Simplify `state()`**

Replace the method body (lines 307–315) with:

```java
  @Override
  public HTTPConnection.State state() {
    return state;
  }
```

- [ ] **Step 5: Delete the `WorkerState` enum**

Remove the entire declaration (lines 522–527):

```java
  private enum WorkerState {
    KeepAlive,
    Process,
    Read,
    Write
  }
```

- [ ] **Step 6: Build**

Run: `latte clean build`
Expected: BUILD SUCCESS. Final check: `grep -c "WorkerState" src/main/java/org/lattejava/http/server/internal/h1/HTTP1Connection.java` prints `0`.

- [ ] **Step 7: Run the keep-alive reaper regression test (verifies state reporting is intact)**

Run: `latte test --test=CoreTest`
Expected: PASS (includes the keep-alive cleaner regression at CoreTest:317).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: Replace HTTP1Connection private WorkerState with HTTPConnection.State"
```

---

## Task 4: Rename the acceptor types

Rename `HTTPServerThread` → `HTTPServerAcceptorThread`, its inner `HTTPServerCleanerThread` → `ConnectionReaperThread`, and its record `ClientInfo` → `ClientConnection` (the name freed by Task 1), including the record's `runnable` field → `connection`.

**Files:**
- Rename: `…/internal/HTTPServerThread.java` → `…/internal/HTTPServerAcceptorThread.java`
- Modify: `…/server/HTTPServer.java`, `…/internal/h1/HTTP1Connection.java` (one comment), `…/tests/server/CoreTest.java` (comments)

**Interfaces:**
- Produces: `public class HTTPServerAcceptorThread extends Thread`; nested `record ClientConnection(Thread thread, HTTPConnection connection, Throughput throughput)` with accessors `getAge()`, `getHandledRequests()`, `getStartInstant()`; nested `private class ConnectionReaperThread extends Thread`.

- [ ] **Step 1: Rename the file**

```bash
git mv src/main/java/org/lattejava/http/server/internal/HTTPServerThread.java \
       src/main/java/org/lattejava/http/server/internal/HTTPServerAcceptorThread.java
```

- [ ] **Step 2: Rename the outer class + constructor**

In `HTTPServerAcceptorThread.java` (preserve the Apache/`@author` header):
- Line 29: `public class HTTPServerThread extends Thread {` → `public class HTTPServerAcceptorThread extends Thread {`
- Line 52: `public HTTPServerThread(` → `public HTTPServerAcceptorThread(`

- [ ] **Step 3: Rename the inner reaper class**

- Line 30: `private final HTTPServerCleanerThread cleaner;` → `private final ConnectionReaperThread cleaner;`
- Line 63: `this.cleaner = new HTTPServerCleanerThread();` → `this.cleaner = new ConnectionReaperThread();`
- Line 190: `private class HTTPServerCleanerThread extends Thread {` → `private class ConnectionReaperThread extends Thread {`
- Line 191: `public HTTPServerCleanerThread() {` → `public ConnectionReaperThread() {`

- [ ] **Step 4: Rename the `ClientInfo` record and its `runnable` field → `connection`**

Replace the record (originally lines 175–188) with:

```java
  record ClientConnection(Thread thread, HTTPConnection connection, Throughput throughput) {
    public long getAge() {
      return System.currentTimeMillis() - connection().getStartInstant();
    }

    public long getHandledRequests() {
      return connection().getHandledRequests();
    }

    public long getStartInstant() {
      return connection().getStartInstant();
    }
  }
```

Update every usage in the file:
- Line 32: `private final Deque<ClientInfo> clients = new ConcurrentLinkedDeque<>();` → `Deque<ClientConnection>`
- Line 123: `clients.add(new ClientInfo(client, conn, throughput));` → `clients.add(new ClientConnection(client, conn, throughput));`
- Line 146: `for (ClientInfo client : clients) {` → `for (ClientConnection client : clients) {`
- Line 147: `if (client.runnable() instanceof HTTP2Connection h2) {` → `if (client.connection() instanceof HTTP2Connection h2) {`
- Line 151: `for (ClientInfo client : clients) {` → `for (ClientConnection client : clients) {`
- Line 204: `ClientInfo client = iterator.next();` → `ClientConnection client = iterator.next();`
- Line 216: `HTTPConnection worker = client.runnable();` → `HTTPConnection worker = client.connection();`

After this step: `grep -n "ClientInfo\|runnable()\|HTTPServerCleanerThread\|HTTPServerThread" src/main/java/org/lattejava/http/server/internal/HTTPServerAcceptorThread.java` should print nothing.

- [ ] **Step 5: Update `HTTPServer`**

In `…/server/HTTPServer.java`:
- Line 32: `private final List<HTTPServerThread> servers = new ArrayList<>();` → `List<HTTPServerAcceptorThread>`
- Line 47: `for (HTTPServerThread thread : servers) {` → `for (HTTPServerAcceptorThread thread : servers) {`
- Line 108: `HTTPServerThread server = new HTTPServerThread(configuration, context, listener);` → `HTTPServerAcceptorThread server = new HTTPServerAcceptorThread(configuration, context, listener);`

- [ ] **Step 6: Update stale comments**

- `…/internal/h1/HTTP1Connection.java` line ~280: comment `When the HTTPServerThread shuts down` → `When the HTTPServerAcceptorThread shuts down`.
- `…/tests/server/CoreTest.java` lines 359 and 386: `HTTPServerThread cleaner` → `ConnectionReaperThread`.

- [ ] **Step 7: Build**

Run: `latte clean build`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: Rename acceptor types (HTTPServerAcceptorThread, ConnectionReaperThread, ClientConnection record)"
```

---

## Task 5: Add `shutdown()` to `HTTPConnection`; simplify the acceptor shutdown loop

Hoist graceful shutdown onto the interface so the acceptor no longer needs an `instanceof HTTP2Connection` check.

**Files:**
- Modify: `…/internal/HTTPConnection.java`, `…/internal/h1/HTTP1Connection.java`, `…/internal/h2/HTTP2Connection.java`, `…/internal/HTTPServerAcceptorThread.java`

**Interfaces:**
- Produces: `void shutdown()` on `HTTPConnection` — HTTP/2 emits GOAWAY, HTTP/1.1 is a no-op.

- [ ] **Step 1: Add `shutdown()` to the interface**

In `…/internal/HTTPConnection.java`, add between `getStartInstant()` and `state()` (keeps methods alphabetical):

```java
  /**
   * Initiates a graceful, in-band shutdown of this connection if the protocol supports one. HTTP/2 emits
   * {@code GOAWAY(NO_ERROR)}; HTTP/1.1 has no such signal and implements this as a no-op. Called by
   * {@link HTTPServerAcceptorThread} during server shutdown, before the connection's thread is interrupted.
   */
  void shutdown();
```

- [ ] **Step 2: Implement the no-op in `HTTP1Connection`**

In `…/internal/h1/HTTP1Connection.java`, add immediately before `state()`:

```java
  /**
   * HTTP/1.1 has no graceful in-band shutdown signal; the connection is torn down by the socket close and thread
   * interrupt that {@link HTTPServerAcceptorThread} performs at server shutdown. Intentionally a no-op.
   */
  @Override
  public void shutdown() {
  }
```

- [ ] **Step 3: Mark `HTTP2Connection.shutdown()` as an override**

In `…/internal/h2/HTTP2Connection.java`, add `@Override` directly above the existing `public void shutdown() {` (line 161):

```java
  @Override
  public void shutdown() {
    enqueueForWriter(new HTTP2Frame.GoawayFrame(highestSeenStreamId, HTTP2ErrorCode.NO_ERROR.value, new byte[0]));
  }
```

- [ ] **Step 4: Simplify the acceptor shutdown loop and drop the now-unused h2 import**

In `…/internal/HTTPServerAcceptorThread.java`, replace the graceful-shutdown loop (originally lines 146–150):

```java
    for (ClientConnection client : clients) {
      if (client.connection() instanceof HTTP2Connection h2) {
        h2.shutdown();
      }
    }
```

with:

```java
    for (ClientConnection client : clients) {
      client.connection().shutdown();
    }
```

Then remove the now-unused import line `import org.lattejava.http.server.internal.h2.*;` (the `instanceof HTTP2Connection` was its only use). Verify: `grep -n "h2\.\|HTTP2Connection" src/main/java/org/lattejava/http/server/internal/HTTPServerAcceptorThread.java` prints nothing.

- [ ] **Step 5: Build**

Run: `latte clean build`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Verify graceful HTTP/2 shutdown still works**

Run: `latte test --test=HTTP2H2cPriorKnowledgeTest`
Expected: PASS (exercises HTTP/2 connection setup; GOAWAY-on-shutdown path is unchanged).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: Hoist graceful shutdown() onto HTTPConnection, drop instanceof in acceptor"
```

---

## Task 6: Make `HTTP2Connection` preface flag a primitive `boolean`

Replace the boxed `Boolean prefaceAlreadyConsumed` with a primitive `boolean prefaceConsumed`. The `null` case is gone now that selection always passes an explicit value.

**Files:**
- Modify: `…/internal/h2/HTTP2Connection.java`, `…/internal/ProtocolSelector.java`

- [ ] **Step 1: Rename the field**

In `…/internal/h2/HTTP2Connection.java` line 60: `private final boolean prefaceAlreadyConsumed;` → `private final boolean prefaceConsumed;`

- [ ] **Step 2: Change the constructor parameter and initializer**

- Line 86: `HTTPListenerConfiguration listener, Throughput throughput, Boolean prefaceAlreadyConsumed) throws IOException {` → `HTTPListenerConfiguration listener, Throughput throughput, boolean prefaceConsumed) throws IOException {`
- Line 97: `this.prefaceAlreadyConsumed = Boolean.TRUE.equals(prefaceAlreadyConsumed);` → `this.prefaceConsumed = prefaceConsumed;`

- [ ] **Step 3: Update the run() guard**

Line 193: `if (!prefaceAlreadyConsumed) {` → `if (!prefaceConsumed) {`

- [ ] **Step 4: Update the two construction sites in `ProtocolSelector`**

In `…/internal/ProtocolSelector.java`:
- Line 46 (TLS-ALPN h2 path): `return new HTTP2Connection(socket, configuration, context, instrumenter, listener, throughput, null);` → replace trailing `null` with `false`.
- Line 69 (h2c prior-knowledge match): `return new HTTP2Connection(socket, configuration, context, instrumenter, listener, throughput, /*prefaceConsumed=*/true);` — the literal stays `true`; the inline comment is already correct.

- [ ] **Step 5: Build**

Run: `latte clean build`
Expected: BUILD SUCCESS. Verify: `grep -c "prefaceAlreadyConsumed" src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java` prints `0`.

- [ ] **Step 6: Verify both preface paths**

Run: `latte test --test=HTTP2ConnectionPrefaceTest`
Expected: PASS (covers TLS→h2 preface read and the prior-knowledge skip).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: Use primitive boolean prefaceConsumed in HTTP2Connection"
```

---

## Task 7: Move protocol selection onto the virtual thread

The core change. Add the `Negotiating` state, move `configureALPN` into `ProtocolSelector.select()`, create `ConnectionDispatcher`, and rewire the acceptor to register the dispatcher and start it on a virtual thread instead of selecting on the accept thread.

**Files:**
- Modify: `…/internal/HTTPConnection.java` (add `Negotiating`)
- Modify: `…/internal/ProtocolSelector.java` (call `configureALPN`)
- Create: `…/internal/ConnectionDispatcher.java`
- Modify: `…/internal/HTTPServerAcceptorThread.java` (rewire accept loop)

**Interfaces:**
- Consumes: `HTTPConnection` (Task 1, now `extends Runnable`, has `shutdown()` from Task 5), `HTTPConnection.State` values, `ProtocolSelector.select(...)`, `Throughput`.
- Produces: `public class ConnectionDispatcher implements HTTPConnection` with constructor `ConnectionDispatcher(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter, HTTPListenerConfiguration listener, Throughput throughput)`.

- [ ] **Step 1: Add the `Negotiating` state**

In `…/internal/HTTPConnection.java`, insert `Negotiating` into the `State` enum immediately after `KeepAlive` (keeps it alphabetical), with this Javadoc:

```java
    /**
     * The connection is performing protocol negotiation (the TLS-ALPN handshake or the h2c preface read) on its
     * virtual thread, before any protocol handler exists. The slow-reader throughput check MUST NOT apply in this
     * state: handshake bytes flow on the raw socket, not through {@code ThroughputInputStream}, so a throughput sample
     * taken now would read ~0 bytes/s and the reaper would evict a legitimate in-progress handshake. This phase is
     * bounded instead by the socket-level {@code SO_TIMEOUT} (the initial-read timeout) — a stalled handshake read
     * throws {@code SocketTimeoutException}, which closes the socket.
     */
    Negotiating,
```

- [ ] **Step 2: Move `configureALPN` into `select()`**

In `…/internal/ProtocolSelector.java`, in the `if (socket instanceof SSLSocket sslSocket) {` branch, add the ALPN configuration before the handshake:

```java
    if (socket instanceof SSLSocket sslSocket) {
      // Configure ALPN, then force the handshake so ALPN selection has happened. Both run here — on the caller's
      // virtual thread (ConnectionDispatcher), never on the accept thread.
      SecurityTools.configureALPN(sslSocket, listener);
      sslSocket.startHandshake();

      String proto = sslSocket.getApplicationProtocol();
```

(`SecurityTools` resolves via the existing `import module org.lattejava.http;`.)

- [ ] **Step 3: Create `ConnectionDispatcher`**

Create `src/main/java/org/lattejava/http/server/internal/ConnectionDispatcher.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

/**
 * Runs on the per-connection virtual thread. Performs protocol selection (the TLS-ALPN handshake or the h2c preface
 * peek) off the accept thread, then delegates to the resolved {@link org.lattejava.http.server.internal.h1.HTTP1Connection}
 * or {@link org.lattejava.http.server.internal.h2.HTTP2Connection}.
 *
 * <p>It implements {@link HTTPConnection} so {@link HTTPServerAcceptorThread} can register it with the reaper the
 * instant the connection is accepted — before the blocking negotiation runs. Until the delegate exists, the dispatcher
 * reports {@link HTTPConnection.State#Negotiating} so the reaper's throughput check does not evict an in-progress
 * handshake; the handshake is bounded by the socket {@code SO_TIMEOUT}.
 *
 * @author Brian Pontarelli
 */
public class ConnectionDispatcher implements HTTPConnection {
  private final HTTPServerConfiguration configuration;
  private final HTTPContext context;
  private final Instrumenter instrumenter;
  private final HTTPListenerConfiguration listener;
  private final Logger logger;
  private final Socket socket;
  private final long startInstant;
  private final Throughput throughput;
  private volatile HTTPConnection delegate;

  public ConnectionDispatcher(Socket socket, HTTPServerConfiguration configuration, HTTPContext context,
                              Instrumenter instrumenter, HTTPListenerConfiguration listener, Throughput throughput) {
    this.socket = socket;
    this.configuration = configuration;
    this.context = context;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.throughput = throughput;
    this.logger = configuration.getLoggerFactory().getLogger(ConnectionDispatcher.class);
    this.startInstant = System.currentTimeMillis();
  }

  @Override
  public long getHandledRequests() {
    HTTPConnection d = delegate;
    return d != null ? d.getHandledRequests() : 0;
  }

  @Override
  public Socket getSocket() {
    return socket;
  }

  @Override
  public long getStartInstant() {
    HTTPConnection d = delegate;
    return d != null ? d.getStartInstant() : startInstant;
  }

  @Override
  public void run() {
    try {
      HTTPConnection selected = ProtocolSelector.select(socket, configuration, context, instrumenter, listener, throughput);
      delegate = selected;
      selected.run();
    } catch (IOException e) {
      // Protocol selection failed: TLS handshake error, h2c-preface peek error, or a slow-loris client that never
      // finished the handshake within the initial-read SO_TIMEOUT. Close the socket so the file descriptor does not
      // leak; the reaper removes this now-dead thread on its next pass.
      logger.debug("Protocol selection failed; closing socket", e);
      try {
        socket.close();
      } catch (IOException ignore) {
      }
    }
  }

  @Override
  public void shutdown() {
    HTTPConnection d = delegate;
    if (d != null) {
      d.shutdown();
    }
  }

  @Override
  public State state() {
    HTTPConnection d = delegate;
    return d != null ? d.state() : State.Negotiating;
  }
}
```

- [ ] **Step 4: Rewire the accept loop**

In `…/internal/HTTPServerAcceptorThread.java`, replace the body from the ALPN block through the `clients.add(...)` line (originally lines 93–123) with:

```java
        if (logger.isTraceEnabled()) {
          String listenerAddress = listener.getBindAddress().toString() + ":" + listener.getPort();
          logger.trace("[{}] Accepted inbound connection. [{}] existing connections.", listenerAddress, clients.size());
        }

        if (instrumenter != null) {
          instrumenter.acceptedConnection();
        }

        Throughput throughput = new Throughput(configuration.getReadThroughputCalculationDelay().toMillis(), configuration.getWriteThroughputCalculationDelay().toMillis());

        // Protocol selection (TLS-ALPN handshake / h2c preface peek) is BLOCKING, so it runs inside ConnectionDispatcher
        // on the per-connection virtual thread — never on this accept thread. The dispatcher is created unstarted and
        // registered with the reaper before it starts, so the connection is tracked from the moment it can run.
        ConnectionDispatcher dispatcher = new ConnectionDispatcher(clientSocket, configuration, context, instrumenter, listener, throughput);
        Thread client = Thread.ofVirtual()
                              .name("HTTP client [" + clientSocket.getRemoteSocketAddress() + "]")
                              .unstarted(dispatcher);
        clients.add(new ClientConnection(client, dispatcher, throughput));
        client.start();
```

This removes: the `if (clientSocket instanceof SSLSocket sslSocket) { SecurityTools.configureALPN(...); }` block, the `HTTPConnection conn;` declaration, the `try { conn = ProtocolSelector.select(...); } catch (IOException e) { … continue; }` block, and the `.start(conn)` call (the dispatcher is started via `client.start()` after registration). The `Socket clientSocket = socket.accept();` and `clientSocket.setSoTimeout(...)` lines just above are unchanged.

- [ ] **Step 5: Confirm the reaper needs no change**

No edit. The reaper acts only on `Read`, `Write`, and `Process`; `Negotiating` (like `KeepAlive`) falls through and is never evicted. Confirm by reading the reaper's `if (state == …)` chain in `HTTPServerAcceptorThread.java` — there is no `else` that closes the connection for unmatched states.

- [ ] **Step 6: Build**

Run: `latte clean build`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Run the full suite (skip slow groups)**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: PASS. These tests drive every branch of `select()` through the new dispatcher (TLS→http/1.1, TLS→h2, h2c match, h2c non-match fallback).

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: Run protocol selection on the connection's virtual thread via ConnectionDispatcher"
```

---

## Task 8: Test — the acceptor stays non-blocking during a stalled handshake

A client that completes TCP but stalls the TLS handshake must not delay acceptance of other clients. This is the regression the change targets.

**Files:**
- Create: `…/tests/server/AcceptorDispatchTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/AcceptorDispatchTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import java.security.cert.Certificate;

import static org.testng.Assert.*;

/**
 * Verifies that protocol selection runs on the per-connection virtual thread, not the accept thread: a client that
 * stalls the TLS handshake must not block acceptance of other connections, and a connection still negotiating must be
 * bounded by SO_TIMEOUT rather than evicted by the reaper's slow-reader throughput check.
 *
 * @author Brian Pontarelli
 */
public class AcceptorDispatchTest extends BaseTest {
  @Test(groups = "timeouts")
  public void acceptorNotBlockedByStalledHandshake() throws Exception {
    // Initial-read SO_TIMEOUT of 20s: under the old (accept-thread) behavior the stalled handshake would hold the
    // accept loop for ~20s, so the second request would take ~20s. Under the new behavior it is instant.
    HTTPServer server = startTLSServer(Duration.ofSeconds(20));
    Socket staller = null;
    try {
      // Connect TCP to the TLS port but send no ClientHello — the server-side handshake blocks on its virtual thread.
      staller = new Socket("127.0.0.1", 4242);
      assertTrue(staller.isConnected());

      // A normal HTTPS request on a second connection must complete quickly while the staller is still pending.
      HttpClient client = makeClient("https", null);
      long start = System.currentTimeMillis();
      HttpResponse<String> response = client.send(
          HttpRequest.newBuilder().uri(makeURI("https", "")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      long elapsed = System.currentTimeMillis() - start;

      assertEquals(response.statusCode(), 200);
      assertTrue(elapsed < 5000, "Second request took [" + elapsed + "] ms; the accept thread was blocked by the stalled handshake.");
    } finally {
      if (staller != null) {
        staller.close();
      }
      server.close();
    }
  }

  private HTTPServer startTLSServer(Duration initialReadTimeout) {
    var certChain = new Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(4242, certChain, keyPair.getPrivate());
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> res.setStatus(200))
        .withInitialReadTimeout(initialReadTimeout)
        .withKeepAliveTimeoutDuration(ServerTimeout)
        .withProcessingTimeoutDuration(ServerTimeout)
        .withMinimumReadThroughput(200 * 1024)
        .withMinimumWriteThroughput(200 * 1024)
        .withReadThroughputCalculationDelayDuration(Duration.ofSeconds(1))
        .withWriteThroughputCalculationDelayDuration(Duration.ofSeconds(1))
        .withLoggerFactory(FileLoggerFactory.FACTORY)
        .withListener(listener);
    server.start();
    return server;
  }
}
```

- [ ] **Step 2: Run against the new code to verify it passes**

Run: `latte test --test=AcceptorDispatchTest`
Expected: `acceptorNotBlockedByStalledHandshake` PASSES (elapsed well under 5000 ms).

- [ ] **Step 3: Sanity-check the test actually guards the regression (optional, revert after)**

Temporarily make selection blocking again by moving the `ProtocolSelector.select(...)` call from `ConnectionDispatcher.run()` into the acceptor loop (or `git stash` Task 7's acceptor change), rerun the test, and confirm it FAILS with an elapsed time near 20000 ms. Then restore. This confirms the assertion is meaningful. (If you skip this, note that the threshold separation is 5s vs 20s.)

- [ ] **Step 4: Commit**

```bash
git add src/test/java/org/lattejava/http/tests/server/AcceptorDispatchTest.java
git commit -m "test: Acceptor stays non-blocking while a TLS handshake stalls"
```

---

## Task 9: Test — a negotiating connection is not reaped by the throughput check

Confirms the `Negotiating` state suppresses the reaper's slow-reader eviction, leaving SO_TIMEOUT as the only bound.

**Files:**
- Modify: `…/tests/server/AcceptorDispatchTest.java`

- [ ] **Step 1: Add the test method**

Add to `AcceptorDispatchTest`:

```java
  @Test(groups = "timeouts")
  public void negotiatingConnectionSurvivesReaperCycles() throws Exception {
    // Large SO_TIMEOUT (20s) so the handshake itself will not time out during the test window. The reaper cycles every
    // ~2s with a 200 KB/s minimum read throughput. If a negotiating connection were reported as Read, the reaper would
    // measure ~0 bytes/s and close it on the first cycle (~2-3s). With State.Negotiating it must stay open.
    HTTPServer server = startTLSServer(Duration.ofSeconds(20));
    Socket staller = null;
    try {
      staller = new Socket("127.0.0.1", 4242);
      staller.setSoTimeout(6000); // longer than two reaper cycles

      // Read one byte. If the server wrongly reaped the negotiating connection, the stream closes and read() returns
      // -1 promptly. If correct, the server holds the connection open and our own read times out after 6s.
      InputStream in = staller.getInputStream();
      try {
        int b = in.read();
        fail("Server closed the negotiating connection (read returned [" + b + "]); it was reaped before SO_TIMEOUT.");
      } catch (SocketTimeoutException expected) {
        // Correct: the connection was still open after >2 reaper cycles, bounded only by SO_TIMEOUT.
      }
    } finally {
      if (staller != null) {
        staller.close();
      }
      server.close();
    }
  }
```

- [ ] **Step 2: Run both tests**

Run: `latte test --test=AcceptorDispatchTest`
Expected: both methods PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/lattejava/http/tests/server/AcceptorDispatchTest.java
git commit -m "test: Negotiating connections are bounded by SO_TIMEOUT, not reaped"
```

---

## Task 10: Final full verification

- [ ] **Step 1: Full CI-equivalent build**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: PASS.

- [ ] **Step 2: Run the timing-sensitive groups explicitly**

Run: `latte test --test=AcceptorDispatchTest` and `latte test --test=CoreTest`
Expected: PASS (including the `timeouts`-group reaper/keep-alive tests).

- [ ] **Step 3: Confirm no stale names remain**

Run:
```bash
grep -rn "HTTP1Worker\|HTTPServerThread\|HTTPServerCleanerThread\|ClientInfo\|WorkerState\|prefaceAlreadyConsumed" src/main src/test
```
Expected: no output (every occurrence has been renamed; remaining `ClientConnection` references are the record, and `HTTPConnection` is the interface).

---

## Self-Review

**Spec coverage:**
- "Accept loop does only non-blocking work" → Task 7 Step 4. ✓
- "ConnectionDispatcher on the vthread, delegates" → Task 7 Step 3. ✓
- "ProtocolSelector gains configureALPN" → Task 7 Step 2. ✓
- "Negotiating state, correctness rationale" → Task 7 Step 1 (+ Task 9 test). ✓
- "shutdown() on the interface, removes instanceof" → Task 5. ✓
- "unstarted → register → start" → Task 7 Step 4. ✓
- "Boolean → boolean prefaceConsumed" → Task 6. ✓
- Naming map (interface, HTTP1Connection, acceptor, reaper, record, dispatcher) → Tasks 1–4, 7. ✓
- Testing: existing suite green (Task 7 Step 7) + non-blocking acceptor (Task 8) + negotiation reaping (Task 9). ✓

**Placeholder scan:** No TBD/TODO; every code-changing step shows exact code or exact find/replace pairs. ✓

**Type consistency:** `HTTPConnection` (interface, `extends Runnable`), `HTTPConnection.State` (with `Negotiating`), `ConnectionDispatcher` constructor signature, and the `ClientConnection` record `(Thread, HTTPConnection, Throughput)` with accessor `connection()` are used identically across Tasks 1, 4, 5, 7. The dispatcher's `selected.run()` relies on `HTTPConnection extends Runnable` from Task 1. ✓

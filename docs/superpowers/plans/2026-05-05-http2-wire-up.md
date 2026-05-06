# HTTP/2 Wire-Up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Take the protocol-layer primitives delivered by Plan C and wire them into a working HTTP/2 server. After this plan, real HTTP/2 traffic flows end-to-end over all three transport modes (h2 over TLS-ALPN, h2c prior-knowledge, h2c via Upgrade/101). DoS mitigations enforced. Configuration knobs exposed. Integration tests via JDK `HttpClient` and raw socket. **No conformance / interop work yet** — that's Plan E.

**Architecture:** A new `ProtocolSelector` runs after socket accept (and after TLS handshake on TLS listeners) and dispatches to either the renamed `HTTP1Worker` or the new `HTTP2Connection`. `HTTP2Connection` owns the per-connection state and spawns three virtual-thread roles per connection: reader, writer, and one handler-thread per stream. Inbound DATA flows reader→stream pipe (`ArrayBlockingQueue<byte[]>`)→`HTTP2InputStream`→handler. Outbound writes flow handler→`HTTP2OutputStream`→writer queue→socket. A small `ClientConnection` interface lets the existing cleaner thread treat both protocols uniformly.

**Tech Stack:** Java 21 virtual threads, JDK `SSLSocket` ALPN (`SSLParameters.setApplicationProtocols`), JDK `HttpClient` for h2-aware integration tests, `BaseSocketTest` for raw-socket tests.

**Reference spec:** `docs/superpowers/specs/2026-05-05-http2-design.md` — read sections "Architectural overview" through "Configuration knobs" before starting.

**Depends on:** Plans B (101 hook + trailers) and C (protocol-layer primitives) merged.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `src/main/java/org/lattejava/http/server/internal/ClientConnection.java` | Create | Interface: `state()`, `getSocket()`, `getStartInstant()`, `getHandledRequests()` |
| `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java` → `HTTP1Worker.java` | Rename | Implements `ClientConnection`; gains `Upgrade: h2c` branch |
| `src/main/java/org/lattejava/http/server/internal/ProtocolSelector.java` | Create | Dispatch entry point (TLS ALPN + cleartext preface peek) |
| `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java` | Create | Connection-level state; reader+writer threads; stream registry; GOAWAY |
| `src/main/java/org/lattejava/http/server/internal/HTTP2InputStream.java` | Create | Per-stream input; drains `ArrayBlockingQueue<byte[]>` filled by reader |
| `src/main/java/org/lattejava/http/server/internal/HTTP2OutputStream.java` | Create | Per-stream output; enqueues DATA frames; blocks on flow-control |
| `src/main/java/org/lattejava/http/server/internal/HTTP2RateLimits.java` | Create | Sliding-window counters for the six DoS classes |
| `src/main/java/org/lattejava/http/server/internal/HTTPServerThread.java` | Modify | Dispatch through `ProtocolSelector`; ALPN setup on accepted `SSLSocket`; `ClientInfo` holds `ClientConnection` |
| `src/main/java/org/lattejava/http/server/HTTPListenerConfiguration.java` | Modify | Add `enableHTTP2` / `enableH2cUpgrade` / `enableH2cPriorKnowledge` |
| `src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java` | Modify | Add `withHTTP2*` knobs and `withHTTP2RateLimits` |
| `src/main/java/org/lattejava/http/server/HTTPRequest.java` | Modify | `isKeepAlive()` returns `true` for h2; trailers already in place from Plan B |
| `src/main/java/org/lattejava/http/server/HTTPResponse.java` | Modify | Strip h1.1-only headers when emitting on h2 (handled inside `HTTP2OutputStream` actually — keep `HTTPResponse` clean) |
| `src/main/java/org/lattejava/http/security/SecurityTools.java` | Modify | Helper to set ALPN on an `SSLSocket` based on a listener config |
| `src/test/java/org/lattejava/http/tests/server/HTTP2BasicTest.java` | Create | GET / POST / large body / concurrent streams via JDK `HttpClient` |
| `src/test/java/org/lattejava/http/tests/server/HTTP2H2cUpgradeTest.java` | Create | h2c via Upgrade/101 |
| `src/test/java/org/lattejava/http/tests/server/HTTP2H2cPriorKnowledgeTest.java` | Create | h2c prior-knowledge |
| `src/test/java/org/lattejava/http/tests/server/HTTP2ALPNTest.java` | Create | Verify TLS-ALPN selection |
| `src/test/java/org/lattejava/http/tests/server/HTTP2GoawayTest.java` | Create | Graceful shutdown emits GOAWAY |
| `src/test/java/org/lattejava/http/tests/server/HTTP2SecurityTest.java` | Create | Rapid Reset, CONTINUATION flood, PING flood, etc. |
| `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java` | Create | Raw socket: malformed frames, preface validation |

---

## Phase 1 — Cleaner-thread refactor (no behavior change)

Get the abstraction in place before adding the second protocol.

### Task 1: Add `ClientConnection` interface

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/ClientConnection.java`

- [ ] **Step 1: Write the file**

```java
/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import java.net.Socket;

/**
 * Implemented by both HTTP/1.1 and HTTP/2 worker classes so the cleaner thread can monitor either uniformly.
 *
 * @author Daniel DeGroff
 */
public interface ClientConnection {
  long getHandledRequests();

  Socket getSocket();

  long getStartInstant();

  /**
   * Aggregated state across the connection's threads. For HTTP/1.1 this is the worker's state; for HTTP/2 this is the worst-case role state across reader/writer/active handlers (Read if any thread is blocked reading, Write if any is blocked writing, otherwise Process).
   */
  State state();

  enum State { Process, Read, Write }
}
```

- [ ] **Step 2: Compile**

Run: `latte clean build`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/ClientConnection.java
git commit -m "Add ClientConnection interface for protocol-agnostic cleanup"
```

---

### Task 2: Rename `HTTPWorker` → `HTTP1Worker`; implement `ClientConnection`

**Files:**
- Rename: `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java` → `HTTP1Worker.java`
- Modify: every reference site

- [ ] **Step 1: Move the existing `HTTPWorker.State` enum to `ClientConnection.State`**

In Task 1 we created `ClientConnection.State`. The existing `HTTPWorker.State` enum has the same three values (Read / Write / Process). Modify `HTTPWorker` to:
- Remove its inner `State` enum
- Use `ClientConnection.State` directly
- Implement `ClientConnection`
- Have `state()` return the current `ClientConnection.State`

- [ ] **Step 2: Rename the class file**

```bash
git mv src/main/java/org/lattejava/http/server/internal/HTTPWorker.java src/main/java/org/lattejava/http/server/internal/HTTP1Worker.java
```

Update the class name on line 29 from `class HTTPWorker` to `class HTTP1Worker`. Update the constructor accordingly.

- [ ] **Step 3: Update all references**

Run: `grep -rn "HTTPWorker" src/`
Expected: hits in `HTTPServerThread.java` and possibly tests. Update each:
- `HTTPServerThread.java` line 101: `new HTTPWorker(...)` → `new HTTP1Worker(...)`
- `HTTPServerThread.java`: `HTTPWorker.State` → `ClientConnection.State`
- `HTTPServerThread.ClientInfo` record: change typed parameter from `HTTPWorker` to `ClientConnection`

- [ ] **Step 4: Compile and run all tests**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: ALL PASS — no behavior change.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Rename HTTPWorker to HTTP1Worker; implement ClientConnection"
```

---

## Phase 2 — `ProtocolSelector` and listener-config wiring

### Task 3: Add `enableHTTP2` / `enableH2cUpgrade` / `enableH2cPriorKnowledge` to `HTTPListenerConfiguration`

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPListenerConfiguration.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/HTTPListenerConfigurationHTTP2Test.java`:

```java
/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTPListenerConfigurationHTTP2Test {
  @Test
  public void defaults() {
    var c = new HTTPListenerConfiguration(80);
    assertTrue(c.isHTTP2Enabled());                  // default-on, ignored on cleartext for ALPN purposes
    assertFalse(c.isH2cPriorKnowledgeEnabled());     // opt-in
    assertTrue(c.isH2cUpgradeEnabled());             // default-on for cleartext
  }

  @Test
  public void withers_set_flags() {
    var c = new HTTPListenerConfiguration(80)
        .withHTTP2Enabled(false)
        .withH2cPriorKnowledgeEnabled(true)
        .withH2cUpgradeEnabled(false);
    assertFalse(c.isHTTP2Enabled());
    assertTrue(c.isH2cPriorKnowledgeEnabled());
    assertFalse(c.isH2cUpgradeEnabled());
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HTTPListenerConfigurationHTTP2Test`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Add fields, accessors, and `with*` methods**

Add three fields with their defaults (alphabetized with existing fields):

```java
private boolean h2cPriorKnowledgeEnabled = false;

private boolean h2cUpgradeEnabled = true;

private boolean http2Enabled = true;
```

Add accessors and withers (alphabetized among existing public methods):

```java
public boolean isH2cPriorKnowledgeEnabled() { return h2cPriorKnowledgeEnabled; }

public boolean isH2cUpgradeEnabled() { return h2cUpgradeEnabled; }

public boolean isHTTP2Enabled() { return http2Enabled; }

public HTTPListenerConfiguration withH2cPriorKnowledgeEnabled(boolean enabled) {
  this.h2cPriorKnowledgeEnabled = enabled;
  return this;
}

public HTTPListenerConfiguration withH2cUpgradeEnabled(boolean enabled) {
  this.h2cUpgradeEnabled = enabled;
  return this;
}

public HTTPListenerConfiguration withHTTP2Enabled(boolean enabled) {
  this.http2Enabled = enabled;
  return this;
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HTTPListenerConfigurationHTTP2Test`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add HTTP2/h2c enable flags to HTTPListenerConfiguration"
```

---

### Task 4: Add ALPN helper to `SecurityTools` and wire it on accepted `SSLSocket`

**Files:**
- Modify: `src/main/java/org/lattejava/http/security/SecurityTools.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPServerThread.java`

- [ ] **Step 1: Add the ALPN helper**

In `SecurityTools.java`, alongside `serverContext`:

```java
/**
 * Configure ALPN on an accepted SSLSocket based on the listener config. Advertises ["h2", "http/1.1"] when HTTP/2 is enabled, ["http/1.1"] otherwise. Returns the same socket for chaining.
 */
public static SSLSocket configureALPN(SSLSocket socket, HTTPListenerConfiguration listener) {
  SSLParameters params = socket.getSSLParameters();
  if (listener.isHTTP2Enabled()) {
    params.setApplicationProtocols(new String[]{"h2", "http/1.1"});
  } else {
    params.setApplicationProtocols(new String[]{"http/1.1"});
  }
  socket.setSSLParameters(params);
  return socket;
}
```

- [ ] **Step 2: Call from `HTTPServerThread`**

In `HTTPServerThread.run()`, after `Socket clientSocket = socket.accept();` and before constructing the worker:

```java
if (clientSocket instanceof SSLSocket sslSocket) {
  SecurityTools.configureALPN(sslSocket, listener);
}
```

- [ ] **Step 3: Compile**

Run: `latte clean build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Configure ALPN on accepted SSLSocket from listener config"
```

---

### Task 5: `ProtocolSelector` — TLS ALPN dispatch

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java` (minimal stub — filled in Task 7)
- Create: `src/main/java/org/lattejava/http/server/internal/ProtocolSelector.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPServerThread.java`

**Compile-order note:** `ProtocolSelector` references `HTTP2Connection` directly. Land a minimal stub class in step 0 below before the selector — Task 7 fills in the real implementation. Without the stub, this task does not compile.

- [ ] **Step 0: Land a minimal `HTTP2Connection` stub**

```java
/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

/**
 * Stub implementation. Real wiring lands in Task 7.
 */
public class HTTP2Connection implements ClientConnection, Runnable {
  private final Socket socket;

  private final long startInstant = System.currentTimeMillis();

  public HTTP2Connection(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter, HTTPListenerConfiguration listener, Throughput throughput, Boolean prefaceAlreadyConsumed) throws IOException {
    this.socket = socket;
  }

  @Override
  public long getHandledRequests() { return 0; }

  @Override
  public Socket getSocket() { return socket; }

  @Override
  public long getStartInstant() { return startInstant; }

  @Override
  public ClientConnection.State state() { return ClientConnection.State.Read; }

  @Override
  public void run() {
    // Real implementation lands in Task 7. For now: close the socket cleanly so anyone reaching this branch sees a clear shutdown rather than a hang.
    try { socket.close(); } catch (IOException ignore) {}
  }
}
```

This compiles and lets the selector reference `HTTP2Connection` immediately. Tests that exercise the h2 path will fail until Task 7, which is expected.

- [ ] **Step 1: Implement the selector**

The peek read is already time-bounded by the existing `clientSocket.setSoTimeout((int) configuration.getInitialReadTimeoutDuration().toMillis())` in `HTTPServerThread.run()`. We catch `SocketTimeoutException` explicitly and fall through to HTTP/1.1 — never block forever waiting for a slowloris client to finish the preface.

```java
/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

import javax.net.ssl.SSLSocket;
import org.lattejava.http.io.PushbackInputStream;

public class ProtocolSelector {
  private static final byte[] HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  public static ClientConnection select(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter, HTTPListenerConfiguration listener, Throughput throughput) throws IOException {
    if (socket instanceof SSLSocket sslSocket) {
      // Force handshake so ALPN selection has happened.
      sslSocket.startHandshake();
      String proto = sslSocket.getApplicationProtocol();
      if ("h2".equals(proto)) {
        return new HTTP2Connection(socket, configuration, context, instrumenter, listener, throughput, null);
      }
      // null, "", or "http/1.1" all → HTTP/1.1
      return new HTTP1Worker(socket, configuration, context, instrumenter, listener, throughput);
    }

    // Cleartext path
    if (listener.isH2cPriorKnowledgeEnabled()) {
      var pushback = new PushbackInputStream(socket.getInputStream(), instrumenter);
      byte[] peek = new byte[HTTP2_PREFACE.length];
      int n;
      try {
        n = pushback.readNBytes(peek, 0, peek.length);
      } catch (SocketTimeoutException timeout) {
        // Slowloris-style client never finished the preface within the initial-read timeout. Fall back to HTTP/1.1, which has its own preamble parser with its own timeout — not our concern here.
        return new HTTP1Worker(socket, configuration, context, instrumenter, listener, throughput);
      }
      if (n == HTTP2_PREFACE.length && Arrays.equals(peek, HTTP2_PREFACE)) {
        // Match: hand a connection that doesn't expect to re-read the preface (we've already consumed it).
        return new HTTP2Connection(socket, configuration, context, instrumenter, listener, throughput, /*prefaceConsumed=*/true);
      }
      // No match: replay the bytes for HTTP/1.1.
      pushback.push(peek, 0, n);
      return new HTTP1Worker(socket, configuration, context, instrumenter, listener, throughput, pushback);
    }

    return new HTTP1Worker(socket, configuration, context, instrumenter, listener, throughput);
  }

  private ProtocolSelector() {}
}
```

(Note: `HTTP1Worker` may need a constructor overload accepting a pre-built `PushbackInputStream` so the selector's already-read bytes are not lost. Add it.)

- [ ] **Step 2: Replace direct `HTTP1Worker` construction with selector call**

In `HTTPServerThread.run()`:

```java
ClientConnection conn = ProtocolSelector.select(clientSocket, configuration, context, instrumenter, listener, throughput);
Thread client = Thread.ofVirtual()
                      .name("HTTP client [" + clientSocket.getRemoteSocketAddress() + "]")
                      .start((Runnable) conn);  // both HTTP1Worker and HTTP2Connection are Runnables
clients.add(new ClientInfo(client, conn, throughput));
```

(Make `HTTP2Connection` and `HTTP1Worker` both implement `Runnable` and `ClientConnection` — add `Runnable` to the interface or compose, your choice.)

- [ ] **Step 3: Compile and run existing tests for regressions**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: ALL HTTP/1.1 tests PASS. The h2 path compiles (thanks to the stub from Step 0) but any test that actually exercises an h2 round-trip will fail until Task 7 — that's expected at this checkpoint.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Add ProtocolSelector with TLS-ALPN and h2c prior-knowledge dispatch (h2 branch stubbed)"
```

---

## Phase 3 — `HTTP2RateLimits`

### Task 6: Sliding-window counters

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2RateLimits.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/lattejava/http/tests/server/HTTP2RateLimitsTest.java`:

```java
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2RateLimits;

import static org.testng.Assert.*;

public class HTTP2RateLimitsTest {
  @Test
  public void under_threshold_returns_false() {
    var rl = HTTP2RateLimits.defaults();
    for (int i = 0; i < 99; i++) {
      assertFalse(rl.recordRstStream());
    }
  }

  @Test
  public void over_threshold_returns_true() {
    var rl = HTTP2RateLimits.defaults();
    for (int i = 0; i < 100; i++) {
      rl.recordRstStream();
    }
    assertTrue(rl.recordRstStream());
  }

  @Test
  public void window_expires_old_events() throws Exception {
    var rl = new HTTP2RateLimits(/*rstStreamMax=*/3, /*rstStreamWindowMs=*/100, /*pingMax=*/10, /*pingWindowMs=*/1000, /*settingsMax=*/10, /*settingsWindowMs=*/1000, /*emptyDataMax=*/100, /*emptyDataWindowMs=*/30000, /*windowUpdateMax=*/100, /*windowUpdateWindowMs=*/1000);
    rl.recordRstStream();
    rl.recordRstStream();
    rl.recordRstStream();
    Thread.sleep(150); // exceed window
    assertFalse(rl.recordRstStream(), "Old events should have expired");
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HTTP2RateLimitsTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement `HTTP2RateLimits`**

```java
package org.lattejava.http.server.internal;

import module java.base;

/**
 * Per-connection sliding-window counters for the six DoS-class attacks. Each counter records timestamps and prunes any older than the configured window. Returns true from `record*` if the threshold is crossed — caller emits GOAWAY(ENHANCE_YOUR_CALM).
 *
 * Not thread-safe; the reader thread is the sole caller for inbound counters.
 */
public class HTTP2RateLimits {
  private final ArrayDeque<Long> emptyData = new ArrayDeque<>();
  private final int emptyDataMax;
  private final long emptyDataWindowMs;
  private final ArrayDeque<Long> ping = new ArrayDeque<>();
  private final int pingMax;
  private final long pingWindowMs;
  private final ArrayDeque<Long> rstStream = new ArrayDeque<>();
  private final int rstStreamMax;
  private final long rstStreamWindowMs;
  private final ArrayDeque<Long> settings = new ArrayDeque<>();
  private final int settingsMax;
  private final long settingsWindowMs;
  private final ArrayDeque<Long> windowUpdate = new ArrayDeque<>();
  private final int windowUpdateMax;
  private final long windowUpdateWindowMs;

  public HTTP2RateLimits(int rstStreamMax, long rstStreamWindowMs, int pingMax, long pingWindowMs, int settingsMax, long settingsWindowMs, int emptyDataMax, long emptyDataWindowMs, int windowUpdateMax, long windowUpdateWindowMs) {
    this.rstStreamMax = rstStreamMax;
    this.rstStreamWindowMs = rstStreamWindowMs;
    this.pingMax = pingMax;
    this.pingWindowMs = pingWindowMs;
    this.settingsMax = settingsMax;
    this.settingsWindowMs = settingsWindowMs;
    this.emptyDataMax = emptyDataMax;
    this.emptyDataWindowMs = emptyDataWindowMs;
    this.windowUpdateMax = windowUpdateMax;
    this.windowUpdateWindowMs = windowUpdateWindowMs;
  }

  public static HTTP2RateLimits defaults() {
    // Defaults from docs/specs/HTTP2.md §10.
    return new HTTP2RateLimits(100, 30_000L, 10, 1_000L, 10, 1_000L, 100, 30_000L, 100, 1_000L);
  }

  public boolean recordEmptyData() { return record(emptyData, emptyDataMax, emptyDataWindowMs); }
  public boolean recordPing() { return record(ping, pingMax, pingWindowMs); }
  public boolean recordRstStream() { return record(rstStream, rstStreamMax, rstStreamWindowMs); }
  public boolean recordSettings() { return record(settings, settingsMax, settingsWindowMs); }
  public boolean recordWindowUpdate() { return record(windowUpdate, windowUpdateMax, windowUpdateWindowMs); }

  private static boolean record(ArrayDeque<Long> q, int max, long windowMs) {
    long now = System.currentTimeMillis();
    long cutoff = now - windowMs;
    while (!q.isEmpty() && q.peekFirst() < cutoff) {
      q.removeFirst();
    }
    q.addLast(now);
    return q.size() > max;
  }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HTTP2RateLimitsTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add HTTP2RateLimits with sliding-window counters for DoS classes"
```

---

## Phase 4 — `HTTP2Connection` skeleton

The full connection class is large. We build it incrementally.

### Task 7: `HTTP2Connection` constructor + connection preface validation + initial SETTINGS

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/HTTP2ConnectionPrefaceTest.java`:

```java
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTP2ConnectionPrefaceTest extends BaseTest {
  @Test
  public void valid_preface_then_settings_completes_handshake() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    var server = makeServerWithListener(new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true), handler).start();
    int port = server.getActualPort();

    try (var sock = new Socket("127.0.0.1", port)) {
      var out = sock.getOutputStream();
      // Connection preface
      out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
      // Empty SETTINGS frame
      out.write(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0});
      out.flush();

      // Read the server's initial SETTINGS frame
      var in = sock.getInputStream();
      byte[] header = in.readNBytes(9);
      assertEquals(header[3], 0x4, "Frame type should be SETTINGS");
    } finally {
      server.close();
    }
  }

  @Test
  public void invalid_preface_closes_connection() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    var server = makeServerWithListener(new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true), handler).start();
    int port = server.getActualPort();

    try (var sock = new Socket("127.0.0.1", port)) {
      var out = sock.getOutputStream();
      out.write("WRONG * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
      out.flush();

      // Server should close.
      var in = sock.getInputStream();
      assertEquals(in.read(), -1);
    } finally {
      server.close();
    }
  }
}
```

(`makeServerWithListener` may need to be added to `BaseTest` — small helper that takes a custom `HTTPListenerConfiguration`.)

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HTTP2ConnectionPrefaceTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement the constructor + `run()` skeleton**

```java
/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

public class HTTP2Connection implements ClientConnection, Runnable {
  private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

  private final HTTPBuffers buffers;

  private final HTTPServerConfiguration configuration;

  private final HTTPContext context;

  private final HTTP2Settings localSettings;
  private final HTTP2Settings peerSettings = HTTP2Settings.defaults();

  private final HTTPListenerConfiguration listener;

  private final Logger logger;

  private final boolean prefaceAlreadyConsumed;

  private final HTTP2RateLimits rateLimits;

  private final Map<Integer, HTTP2Stream> streams = new ConcurrentHashMap<>();

  private final Throughput throughput;

  private final Socket socket;

  private final long startInstant;

  private long handledRequests;

  private volatile ClientConnection.State state = ClientConnection.State.Read;

  public HTTP2Connection(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter, HTTPListenerConfiguration listener, Throughput throughput, Boolean prefaceAlreadyConsumed) throws IOException {
    this.socket = socket;
    this.configuration = configuration;
    this.context = context;
    this.listener = listener;
    this.throughput = throughput;
    this.buffers = new HTTPBuffers(configuration);
    this.logger = configuration.getLoggerFactory().getLogger(HTTP2Connection.class);
    this.localSettings = configuration.getHTTP2Settings();
    this.rateLimits = configuration.getHTTP2RateLimits();
    this.prefaceAlreadyConsumed = Boolean.TRUE.equals(prefaceAlreadyConsumed);
    this.startInstant = System.currentTimeMillis();
  }

  @Override
  public long getHandledRequests() { return handledRequests; }

  @Override
  public Socket getSocket() { return socket; }

  @Override
  public long getStartInstant() { return startInstant; }

  @Override
  public ClientConnection.State state() { return state; }

  @Override
  public void run() {
    try {
      var in = socket.getInputStream();
      var out = socket.getOutputStream();

      // Read and validate the connection preface (skip if already consumed by ProtocolSelector).
      if (!prefaceAlreadyConsumed) {
        byte[] received = in.readNBytes(PREFACE.length);
        if (!Arrays.equals(received, PREFACE)) {
          throw new HTTP2ProtocolException("Invalid HTTP/2 connection preface");
        }
      }

      // Send our initial SETTINGS frame
      var writer = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
      byte[] settingsPayload = encodeSettings(localSettings);
      writer.writeFrame(new HTTP2Frame.SettingsFrame(0, settingsPayload));
      out.flush();

      // Spawn reader and writer threads — implemented in next tasks.
      // For now: read the first frame (must be SETTINGS) and ACK it, then loop.
      var reader = new HTTP2FrameReader(in, buffers.frameReadBuffer());
      var firstFrame = reader.readFrame();
      if (!(firstFrame instanceof HTTP2Frame.SettingsFrame settings) || (settings.flags() & HTTP2Frame.FLAG_ACK) != 0) {
        throw new HTTP2ProtocolException("Expected client SETTINGS frame after preface");
      }
      peerSettings.applyPayload(settings.payload());
      writer.writeFrame(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0])); // ACK their settings

      // Frame-handling loop — populated in Task 9.
      runFrameLoop(reader, writer);
    } catch (Exception e) {
      logger.debug("HTTP/2 connection ended", e);
    } finally {
      try { socket.close(); } catch (IOException ignore) {}
    }
  }

  private void runFrameLoop(HTTP2FrameReader reader, HTTP2FrameWriter writer) throws IOException {
    // Stub for Task 9.
  }

  private static byte[] encodeSettings(HTTP2Settings s) {
    // Encode all six standard settings as a 36-byte payload.
    var out = new java.io.ByteArrayOutputStream();
    writeSetting(out, HTTP2Settings.SETTINGS_HEADER_TABLE_SIZE, s.headerTableSize());
    writeSetting(out, HTTP2Settings.SETTINGS_ENABLE_PUSH, 0); // we never push
    writeSetting(out, HTTP2Settings.SETTINGS_MAX_CONCURRENT_STREAMS, s.maxConcurrentStreams());
    writeSetting(out, HTTP2Settings.SETTINGS_INITIAL_WINDOW_SIZE, s.initialWindowSize());
    writeSetting(out, HTTP2Settings.SETTINGS_MAX_FRAME_SIZE, s.maxFrameSize());
    writeSetting(out, HTTP2Settings.SETTINGS_MAX_HEADER_LIST_SIZE, s.maxHeaderListSize());
    return out.toByteArray();
  }

  private static void writeSetting(java.io.ByteArrayOutputStream out, int id, int value) {
    out.write((id >> 8) & 0xFF); out.write(id & 0xFF);
    out.write((value >> 24) & 0xFF); out.write((value >> 16) & 0xFF);
    out.write((value >> 8) & 0xFF); out.write(value & 0xFF);
  }

  public static class HTTP2ProtocolException extends RuntimeException {
    public HTTP2ProtocolException(String msg) { super(msg); }
  }
}
```

(Add `getHTTP2Settings()` and `getHTTP2RateLimits()` to `HTTPServerConfiguration` — they're referenced above. Default-construct `HTTP2Settings.defaults()` and `HTTP2RateLimits.defaults()` if not configured. Task 11 covers configuration knobs in detail.)

- [ ] **Step 4: Update `ProtocolSelector` to honor `prefaceAlreadyConsumed`**

The selector consumes the 24 bytes when peeking. Pass `true` to the `HTTP2Connection` constructor in that path.

- [ ] **Step 5: Run to verify pass**

Run: `latte test --test=HTTP2ConnectionPrefaceTest`
Expected: BOTH PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add HTTP2Connection with preface validation and initial SETTINGS exchange"
```

---

### Task 8: `HTTP2InputStream` and `HTTP2OutputStream`

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2InputStream.java`
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2OutputStream.java`

- [ ] **Step 1: Write `HTTP2InputStream`**

```java
/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * Per-stream input. Backed by an ArrayBlockingQueue<byte[]> filled by the connection reader thread. Zero-length byte[] is the EOF sentinel.
 */
public class HTTP2InputStream extends InputStream {
  private static final byte[] EOF_SENTINEL = new byte[0];

  private final BlockingQueue<byte[]> queue;

  private byte[] current;

  private int currentPos;

  private boolean eof;

  public HTTP2InputStream(BlockingQueue<byte[]> queue) {
    this.queue = queue;
  }

  public static byte[] eofSentinel() { return EOF_SENTINEL; }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    int n = read(one, 0, 1);
    return n == -1 ? -1 : one[0] & 0xFF;
  }

  @Override
  public int read(byte[] dst, int off, int len) throws IOException {
    if (eof) return -1;
    if (current == null || currentPos >= current.length) {
      try {
        current = queue.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }
      currentPos = 0;
      if (current.length == 0) {
        eof = true;
        return -1;
      }
    }
    int copy = Math.min(len, current.length - currentPos);
    System.arraycopy(current, currentPos, dst, off, copy);
    currentPos += copy;
    return copy;
  }
}
```

- [ ] **Step 2: Write `HTTP2OutputStream`**

```java
/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * Per-stream output. Buffers writes locally; on flush/close, fragments against peer MAX_FRAME_SIZE and enqueues DATA frames to the connection writer queue. Blocks on stream send-window when out of credits; the connection reader signals the per-stream condition on WINDOW_UPDATE.
 */
public class HTTP2OutputStream extends OutputStream {
  private final BlockingQueue<HTTP2Frame> writerQueue;
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private final HTTP2Stream stream;
  private final int peerMaxFrameSize;
  private boolean closed;

  public HTTP2OutputStream(HTTP2Stream stream, BlockingQueue<HTTP2Frame> writerQueue, int peerMaxFrameSize) {
    this.stream = stream;
    this.writerQueue = writerQueue;
    this.peerMaxFrameSize = peerMaxFrameSize;
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    flushAndFragment(/*endStream=*/true);
  }

  @Override
  public void flush() throws IOException {
    flushAndFragment(/*endStream=*/false);
  }

  @Override
  public void write(int b) throws IOException { buffer.write(b); }

  @Override
  public void write(byte[] b, int off, int len) throws IOException { buffer.write(b, off, len); }

  private void flushAndFragment(boolean endStream) throws IOException {
    byte[] all = buffer.toByteArray();
    buffer.reset();
    int off = 0;
    while (off < all.length) {
      int chunk = Math.min(peerMaxFrameSize, all.length - off);
      // Block on flow-control if needed.
      while (stream.sendWindow() < chunk) {
        try {
          synchronized (stream) { stream.wait(100); } // signaled by reader on WINDOW_UPDATE
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException();
        }
      }
      stream.consumeSendWindow(chunk);
      byte[] piece = new byte[chunk];
      System.arraycopy(all, off, piece, 0, chunk);
      off += chunk;
      boolean last = (off >= all.length) && endStream;
      try {
        writerQueue.put(new HTTP2Frame.DataFrame(stream.streamId(), last ? HTTP2Frame.FLAG_END_STREAM : 0, piece));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }
    }
    // If endStream and the buffer was empty, still emit a zero-length DATA frame with END_STREAM.
    if (endStream && all.length == 0) {
      try {
        writerQueue.put(new HTTP2Frame.DataFrame(stream.streamId(), HTTP2Frame.FLAG_END_STREAM, new byte[0]));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }
    }
  }
}
```

- [ ] **Step 3: Compile**

Run: `latte clean build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Add HTTP2InputStream and HTTP2OutputStream"
```

---

### Task 9: Implement `runFrameLoop` — frame dispatch, HEADERS handling, handler spawn, writer thread

This is the largest single task in the plan. Read the design doc's "Threading model on a single h2 connection" section before starting.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java`

- [ ] **Step 1: Spawn writer thread**

In `run()`, before `runFrameLoop`, create a bounded `LinkedBlockingQueue<HTTP2Frame>` (capacity ~128 or configurable) and a writer virtual thread:

```java
var writerQueue = new LinkedBlockingQueue<HTTP2Frame>(128);
Thread writerThread = Thread.ofVirtual().name("h2-writer").start(() -> {
  try {
    while (true) {
      HTTP2Frame f = writerQueue.take();
      if (f instanceof HTTP2Frame.GoawayFrame g && g.errorCode() == HTTP2ErrorCode.NO_ERROR.value && g.lastStreamId() == -1) {
        break; // sentinel — clean shutdown
      }
      writer.writeFrame(f);
      out.flush();
    }
  } catch (Exception e) {
    logger.debug("Writer thread ended", e);
  }
});
```

- [ ] **Step 2: Implement `runFrameLoop`**

```java
private void runFrameLoop(HTTP2FrameReader reader, HTTP2FrameWriter writer, BlockingQueue<HTTP2Frame> writerQueue) throws IOException {
  HPACKDynamicTable decoderTable = new HPACKDynamicTable(localSettings.headerTableSize());
  HPACKDecoder decoder = new HPACKDecoder(decoderTable);

  // Header-block accumulation across HEADERS + CONTINUATION frames.
  ByteArrayOutputStream headerAccum = new ByteArrayOutputStream();
  Integer headerBlockStreamId = null;

  while (true) {
    state = ClientConnection.State.Read;
    HTTP2Frame frame = reader.readFrame();

    // RFC 9113 §6.10 — once a HEADERS or PUSH_PROMISE frame without END_HEADERS has been received, the next frame on the connection MUST be a CONTINUATION on the same stream. Anything else is a connection error PROTOCOL_ERROR.
    if (headerBlockStreamId != null) {
      boolean isContinuationOnSameStream = frame instanceof HTTP2Frame.ContinuationFrame cont && cont.streamId() == headerBlockStreamId;
      if (!isContinuationOnSameStream) {
        goAway(writerQueue, HTTP2ErrorCode.PROTOCOL_ERROR);
        return;
      }
    }

    switch (frame) {
      case HTTP2Frame.SettingsFrame f -> handleSettings(f, writerQueue);
      case HTTP2Frame.PingFrame f -> handlePing(f, writerQueue);
      case HTTP2Frame.WindowUpdateFrame f -> handleWindowUpdate(f);
      case HTTP2Frame.RstStreamFrame f -> handleRstStream(f, writerQueue);
      case HTTP2Frame.GoawayFrame f -> { return; /* drain and exit */ }
      case HTTP2Frame.HeadersFrame f -> {
        handleHeaders(f, headerAccum, decoder, writerQueue);
        headerBlockStreamId = (f.flags() & HTTP2Frame.FLAG_END_HEADERS) == 0 ? f.streamId() : null;
      }
      case HTTP2Frame.ContinuationFrame f -> {
        handleContinuation(f, headerAccum, decoder, writerQueue);
        if ((f.flags() & HTTP2Frame.FLAG_END_HEADERS) != 0) {
          headerBlockStreamId = null;
        }
      }
      case HTTP2Frame.DataFrame f -> handleData(f);
      case HTTP2Frame.PriorityFrame ignored -> {} // RFC 9113 §5.3 — parse and discard
      case HTTP2Frame.PushPromiseFrame ignored -> goAway(writerQueue, HTTP2ErrorCode.PROTOCOL_ERROR);
      case HTTP2Frame.UnknownFrame ignored -> {} // RFC 9113 §5.5
    }
  }
}
```

Add a corresponding raw-frame test in Task 17:

```java
@Test
public void interleaved_frame_during_headers_continuation_triggers_goaway() throws Exception {
  // Send HEADERS without END_HEADERS, then a DATA frame on a different stream — expect GOAWAY(PROTOCOL_ERROR).
}
```

- [ ] **Step 3: Implement each handler method**

This step is about 200 lines of code. Each handler:
- `handleSettings`: apply payload, ACK; rate-limit.
- `handlePing`: enqueue PING ACK; rate-limit.
- `handleWindowUpdate`: increment connection or stream send-window; signal stream condition.
- `handleRstStream`: cancel handler thread; rate-limit (rapid-reset).
- `handleHeaders` / `handleContinuation`: accumulate fragment; on END_HEADERS, decode HPACK, build `HTTPRequest`, spawn handler thread.
- `handleData`: route bytes to per-stream input pipe; consume receive-window; on END_STREAM enqueue EOF sentinel; rate-limit empty-data.

Implement each. Reference the design doc's "Threading model" and the RFC sections cited there.

For `handleHeaders` specifically:

```java
private void handleHeaders(HTTP2Frame.HeadersFrame f, ByteArrayOutputStream headerAccum, HPACKDecoder decoder, BlockingQueue<HTTP2Frame> writerQueue) throws IOException {
  if (streams.size() >= localSettings.maxConcurrentStreams()) {
    writerQueue.add(new HTTP2Frame.RstStreamFrame(f.streamId(), HTTP2ErrorCode.REFUSED_STREAM.value));
    return;
  }

  headerAccum.reset();
  headerAccum.write(f.headerBlockFragment());

  if ((f.flags() & HTTP2Frame.FLAG_END_HEADERS) == 0) {
    // Wait for CONTINUATION
    // (set a per-connection accumulating-stream-id field for the loop to consult on the next iteration)
    return;
  }

  var fields = decoder.decode(headerAccum.toByteArray());
  HTTPRequest request = buildRequestFromHeaders(fields, f.streamId());
  HTTP2Stream stream = new HTTP2Stream(f.streamId(), localSettings.initialWindowSize(), peerSettings.initialWindowSize());
  streams.put(f.streamId(), stream);
  if ((f.flags() & HTTP2Frame.FLAG_END_STREAM) != 0) {
    stream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_END_STREAM);
  } else {
    stream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_NO_END_STREAM);
  }

  spawnHandlerThread(request, stream, writerQueue);
  handledRequests++;
}

private HTTPRequest buildRequestFromHeaders(List<HPACKDynamicTable.HeaderField> fields, int streamId) {
  HTTPRequest req = new HTTPRequest(context, configuration.getContextPath(), /* scheme */ "http", listener.getPort(), socket.getInetAddress().getHostAddress());
  for (var field : fields) {
    String name = field.name();
    String value = field.value();
    switch (name) {
      case ":method" -> req.setMethod(HTTPMethod.of(value));
      case ":path" -> {
        int q = value.indexOf('?');
        if (q < 0) {
          req.setPath(value);
        } else {
          req.setPath(value.substring(0, q));
          // populate URL parameters from value.substring(q + 1) — reuse HTTPTools.parseQueryString
        }
      }
      case ":scheme" -> { /* recorded; affects req.isTLS mapping */ }
      case ":authority" -> req.addHeader("Host", value);
      default -> req.addHeader(name, value);
    }
  }
  // h2 always returns "HTTP/2.0" from getProtocol(); add an internal marker so the response path knows.
  req.setProtocol("HTTP/2.0");
  return req;
}

private void spawnHandlerThread(HTTPRequest request, HTTP2Stream stream, BlockingQueue<HTTP2Frame> writerQueue) {
  var pipe = new LinkedBlockingQueue<byte[]>(16);  // capacity tied to receive window; refine when wiring flow control
  request.setInputStream(new HTTP2InputStream(pipe));
  // Stash pipe on stream for handleData to use.
  stream.setInputPipe(pipe); // requires adding the field+accessor on HTTP2Stream

  HTTPResponse response = new HTTPResponse();
  HTTP2OutputStream out = new HTTP2OutputStream(stream, writerQueue, peerSettings.maxFrameSize());
  // Wrap with HTTPOutputStream-equivalent? For h2, simpler to wire HTTP2OutputStream directly behind HTTPResponse.
  response.setOutputStream(out); // requires a setOutputStream(OutputStream) overload or adapter

  Thread.ofVirtual().name("h2-handler-" + stream.streamId()).start(() -> {
    try {
      configuration.getHandler().handle(request, response);
      response.close(); // closes HTTP2OutputStream → flushes with END_STREAM
    } catch (Exception e) {
      logger.error("h2 handler exception", e);
      writerQueue.add(new HTTP2Frame.RstStreamFrame(stream.streamId(), HTTP2ErrorCode.INTERNAL_ERROR.value));
    } finally {
      streams.remove(stream.streamId());
    }
  });
}
```

(Note: `HTTPRequest.setProtocol` and `setPath` may need to be added if absent. Check current API; the design doc requires `getProtocol()` to return `"HTTP/2.0"` for h2 streams, so a setter is the cleanest hook.)

- [ ] **Step 4: Compile**

Run: `latte clean build`
Expected: SUCCESS — there will be lots of fixes needed for the integration. Iterate until clean.

- [ ] **Step 5: Run the existing preface test**

Run: `latte test --test=HTTP2ConnectionPrefaceTest`
Expected: STILL PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Implement HTTP2Connection frame loop, HPACK header dispatch, handler spawn, writer thread"
```

---

## Phase 5 — Integration tests

### Task 10: HTTP/2 basic round-trip via JDK HttpClient

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/HTTP2BasicTest.java`

JDK 21's `HttpClient` speaks h2 natively when `Version.HTTP_2` is set. The test server uses TLS + ALPN.

- [ ] **Step 1: Write the tests**

```java
/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTP2BasicTest extends BaseTest {
  @Test
  public void get_round_trip_h2() throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getProtocol(), "HTTP/2.0");
      res.setStatus(200);
      res.getOutputStream().write("hello".getBytes());
      res.getOutputStream().close();
    };

    try (var ignored = makeServer("https", handler).start()) {
      var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(insecureSSLContext()).build();
      var resp = client.send(HttpRequest.newBuilder(makeURI("https", "/")).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.body(), "hello");
      assertEquals(resp.version(), HttpClient.Version.HTTP_2);
    }
  }

  @Test
  public void post_with_body_h2() throws Exception {
    HTTPHandler handler = (req, res) -> {
      byte[] body = req.getInputStream().readAllBytes();
      res.setStatus(200);
      res.getOutputStream().write(body);
      res.getOutputStream().close();
    };

    try (var ignored = makeServer("https", handler).start()) {
      var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(insecureSSLContext()).build();
      var body = "x".repeat(100_000);
      var resp = client.send(HttpRequest.newBuilder(makeURI("https", "/")).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2, "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
      assertEquals(resp.body(), body);
    }
  }

  @Test
  public void large_body_exercises_flow_control() throws Exception {
    HTTPHandler handler = (req, res) -> {
      req.getInputStream().readAllBytes();
      res.setStatus(200);
      // Body > INITIAL_WINDOW_SIZE
      byte[] big = new byte[200_000];
      Arrays.fill(big, (byte) 'a');
      res.getOutputStream().write(big);
      res.getOutputStream().close();
    };

    try (var ignored = makeServer("https", handler).start()) {
      var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(insecureSSLContext()).build();
      var resp = client.send(HttpRequest.newBuilder(makeURI("https", "/")).build(), HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2);
      assertEquals(resp.body().length, 200_000);
    }
  }

  @Test
  public void concurrent_streams_from_one_connection() throws Exception {
    var counter = new AtomicInteger();
    HTTPHandler handler = (req, res) -> {
      counter.incrementAndGet();
      res.setStatus(200);
      res.getOutputStream().write(String.valueOf(counter.get()).getBytes());
      res.getOutputStream().close();
    };

    try (var ignored = makeServer("https", handler).start()) {
      var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).sslContext(insecureSSLContext()).build();
      var futures = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
      for (int i = 0; i < 20; i++) {
        futures.add(client.sendAsync(HttpRequest.newBuilder(makeURI("https", "/" + i)).build(), HttpResponse.BodyHandlers.ofString()));
      }
      for (var f : futures) {
        var resp = f.get();
        assertEquals(resp.statusCode(), 200);
        assertEquals(resp.version(), HttpClient.Version.HTTP_2);
      }
      assertEquals(counter.get(), 20);
    }
  }
}
```

**Critical assertion:** every `HttpClient` test in this and later tasks must assert `resp.version() == HTTP_2`. The JDK client silently downgrades to HTTP/1.1 if ALPN doesn't advertise h2 — without the explicit assertion, "h2 round-trip works" tests pass against an h1.1 fallback path, hiding broken h2.
```

(`insecureSSLContext()` is a small test helper that trusts all certs — add to `BaseTest` if not already there.)

- [ ] **Step 2: Run**

Run: `latte test --test=HTTP2BasicTest`
Expected: ALL PASS.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "Add HTTP/2 basic integration tests via JDK HttpClient"
```

---

### Task 11: HTTP/2 configuration knobs on `HTTPServerConfiguration`

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java`

- [ ] **Step 1: Write tests for each knob**

```java
@Test public void http2_settings_defaults() { ... }
@Test public void with_http2_initial_window_size() { ... }
@Test public void with_http2_max_concurrent_streams() { ... }
// ... and so on for each knob in the design doc §"Configuration knobs"
```

- [ ] **Step 2: Add a single field of type `HTTP2Settings`**

```java
private HTTP2Settings http2Settings = HTTP2Settings.defaults();

private HTTP2RateLimits http2RateLimits = HTTP2RateLimits.defaults();

private Duration http2KeepAlivePingInterval;

private Duration http2SettingsAckTimeout = Duration.ofSeconds(10);
```

Add `with*` methods (matching the design doc table):

```java
public HTTPServerConfiguration withHTTP2HeaderTableSize(int size) { http2Settings = http2Settings.withHeaderTableSize(size); return this; }
public HTTPServerConfiguration withHTTP2InitialWindowSize(int size) { http2Settings = http2Settings.withInitialWindowSize(size); return this; }
public HTTPServerConfiguration withHTTP2MaxConcurrentStreams(int n) { http2Settings = http2Settings.withMaxConcurrentStreams(n); return this; }
public HTTPServerConfiguration withHTTP2MaxFrameSize(int size) { http2Settings = http2Settings.withMaxFrameSize(size); return this; }
public HTTPServerConfiguration withHTTP2MaxHeaderListSize(int size) { http2Settings = http2Settings.withMaxHeaderListSize(size); return this; }
public HTTPServerConfiguration withHTTP2KeepAlivePingInterval(Duration d) { this.http2KeepAlivePingInterval = d; return this; }
public HTTPServerConfiguration withHTTP2RateLimits(HTTP2RateLimits limits) { this.http2RateLimits = limits; return this; }
public HTTPServerConfiguration withHTTP2SettingsAckTimeout(Duration d) { this.http2SettingsAckTimeout = d; return this; }

public HTTP2Settings getHTTP2Settings() { return http2Settings; }
public HTTP2RateLimits getHTTP2RateLimits() { return http2RateLimits; }
public Duration getHTTP2KeepAlivePingInterval() { return http2KeepAlivePingInterval; }
public Duration getHTTP2SettingsAckTimeout() { return http2SettingsAckTimeout; }
```

(`HTTP2Settings` needs `with*` methods — add them as immutable copy-and-modify or convert the class to mutable with copy semantics; pick whatever fits the existing config style. Look at `HTTPServerConfiguration` for reference.)

- [ ] **Step 2: Run**

Run: `latte test --test=HTTPServerConfigurationHTTP2Test`
Expected: ALL PASS.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "Add HTTP/2 configuration knobs to HTTPServerConfiguration"
```

---

### Task 12: h2c via Upgrade/101

Wire `HTTP1Worker` to detect `Upgrade: h2c` + `HTTP2-Settings` and hand off via the 101 hook from Plan B.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP1Worker.java`

- [ ] **Step 1: Write the test**

Create `src/test/java/org/lattejava/http/tests/server/HTTP2H2cUpgradeTest.java`:

```java
@Test
public void upgrade_to_h2c_succeeds() throws Exception {
  HTTPHandler handler = (req, res) -> {
    res.setStatus(200);
    res.getOutputStream().write(req.getProtocol().getBytes());
    res.getOutputStream().close();
  };

  try (var ignored = makeServer("http", handler).start()) {
    try (var sock = makeRawSocket()) {
      sock.getOutputStream().write("""
          GET / HTTP/1.1\r
          Host: cyberdyne-systems.com\r
          Connection: Upgrade, HTTP2-Settings\r
          Upgrade: h2c\r
          HTTP2-Settings: AAMAAABkAAQCAAAAAAIAAAAA\r
          \r
          """.getBytes());
      sock.getOutputStream().flush();

      // Expect 101
      var head = new byte[256];
      int n = sock.getInputStream().read(head);
      assertTrue(new String(head, 0, n).startsWith("HTTP/1.1 101 "));

      // From here, h2 frames should flow. Simplified assertion: server should be sending its initial SETTINGS frame.
      var frameHeader = sock.getInputStream().readNBytes(9);
      assertEquals(frameHeader[3], 0x4); // SETTINGS
    }
  }
}
```

- [ ] **Step 2: Implement the branch in `HTTP1Worker`**

After `validatePreamble`, before HEAD handling:

```java
String upgrade = request.getHeader("Upgrade");
String h2settings = request.getHeader("HTTP2-Settings");
if (upgrade != null && upgrade.equalsIgnoreCase("h2c") && listener.isH2cUpgradeEnabled()) {
  if (h2settings == null) {
    closeSocketOnError(response, HTTPValues.Status.BadRequest);
    return;
  }
  byte[] settingsPayload;
  try {
    settingsPayload = java.util.Base64.getUrlDecoder().decode(h2settings);
  } catch (IllegalArgumentException e) {
    closeSocketOnError(response, HTTPValues.Status.BadRequest);
    return;
  }

  HTTP2Settings peerSettings = HTTP2Settings.defaults();
  peerSettings.applyPayload(settingsPayload);

  // Stash the request so HTTP2Connection knows stream 1 is half-closed-remote with this request.
  HTTPRequest implicitStream1 = request;

  response.switchProtocols("h2c", java.util.Map.of(), socket -> {
    new HTTP2Connection(socket, configuration, context, instrumenter, listener, throughput, /*prefaceConsumed=*/false /* client sends preface after 101 */, peerSettings, implicitStream1).run();
  });
  // The 101 emitter in HTTPWorker (from Plan B) writes the 101 and runs the handler; we exit after that.
  return;
}
```

(`HTTP2Connection` needs an additional constructor accepting `peerSettings` and `implicitStream1`. Add it.)

- [ ] **Step 3: Run**

Run: `latte test --test=HTTP2H2cUpgradeTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Implement h2c via Upgrade/101 handoff in HTTP1Worker"
```

---

### Task 13: h2c prior-knowledge integration test

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/HTTP2H2cPriorKnowledgeTest.java`

- [ ] **Step 1: Write the test**

```java
@Test
public void h2c_prior_knowledge_round_trip() throws Exception {
  HTTPHandler handler = (req, res) -> { res.setStatus(200); res.getOutputStream().close(); };
  var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);

  try (var ignored = makeServerWithListener(listener, handler).start()) {
    var client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build();
    var resp = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + actualPort + "/")).build(), HttpResponse.BodyHandlers.discarding());
    assertEquals(resp.statusCode(), 200);
    assertEquals(resp.version(), HttpClient.Version.HTTP_2);
  }
}
```

(JDK `HttpClient` over cleartext h2c uses prior-knowledge by default when `Version.HTTP_2` is set on a non-HTTPS URI.)

- [ ] **Step 2: Run**

Run: `latte test --test=HTTP2H2cPriorKnowledgeTest`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "Add h2c prior-knowledge integration test"
```

---

### Task 14: GOAWAY on graceful server shutdown

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java` (add `shutdown()` method)
- Create: `src/test/java/org/lattejava/http/tests/server/HTTP2GoawayTest.java`

- [ ] **Step 1: Add a `shutdown()` method to `HTTP2Connection`**

```java
public void shutdown() {
  // Enqueue GOAWAY(NO_ERROR) with last-stream-id; writer thread emits and then sees the sentinel and exits. In-flight streams have up to configuration.getShutdownDuration() to complete before HTTPServer forces socket close.
  int lastStreamId = streams.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
  try {
    writerQueue.put(new HTTP2Frame.GoawayFrame(lastStreamId, HTTP2ErrorCode.NO_ERROR.value, new byte[0]));
  } catch (InterruptedException e) {
    Thread.currentThread().interrupt();
  }
}
```

Wire `HTTPServer.close()` (or the existing shutdown path in `HTTPServerThread`) to call `shutdown()` on each `HTTP2Connection`, then wait up to `configuration.getShutdownDuration()` for streams to finish, then force-close the socket. The existing h1.1 shutdown path already uses `getShutdownDuration()` — reuse the same bound rather than introducing a new knob. Document the behavior in `HTTPServerConfiguration.withShutdownDuration` Javadoc: "Also bounds graceful HTTP/2 stream completion after GOAWAY."

- [ ] **Step 2: Write the test**

```java
@Test
public void goaway_on_graceful_shutdown() throws Exception {
  // Open an h2 connection; mid-flight shutdown the server; expect GOAWAY frame on the wire.
  // Use raw socket to observe.
}
```

(Implementation detail: open a raw socket with the preface + an empty SETTINGS frame, then call `server.close()` from another thread, and read frames until GOAWAY arrives. Assert frame type and `lastStreamId`.)

- [ ] **Step 3: Run, commit**

Run: `latte test --test=HTTP2GoawayTest`
Expected: PASS.

```bash
git add -A
git commit -m "Emit GOAWAY on graceful server shutdown"
```

---

### Task 15: Security tests — the six DoS classes

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/HTTP2SecurityTest.java`

- [ ] **Step 1: Write tests**

One test per DoS class. All use raw sockets. Pattern:

```java
@Test
public void rapid_reset_triggers_goaway() throws Exception {
  // Open h2c connection; send 200 HEADERS frames each followed by RST_STREAM; expect GOAWAY(ENHANCE_YOUR_CALM).
}

@Test
public void continuation_flood_triggers_close() throws Exception {
  // Send 1 HEADERS without END_HEADERS; then 20 CONTINUATION frames; expect connection close at the configured limit.
}

@Test
public void ping_flood_triggers_goaway() throws Exception {
  // Send 100 PINGs in 1 second; expect GOAWAY.
}

@Test public void settings_flood_triggers_goaway() throws Exception { ... }
@Test public void empty_data_flood_triggers_goaway() throws Exception { ... }
@Test public void window_update_flood_triggers_goaway() throws Exception { ... }
```

- [ ] **Step 2: Implement & run**

Run: `latte test --test=HTTP2SecurityTest`
Expected: ALL PASS — relies on Task 6's rate-limit class plumbed into `runFrameLoop` from Task 9.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "Add HTTP/2 DoS mitigation tests"
```

---

### Task 16: ALPN test

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/HTTP2ALPNTest.java`

- [ ] **Step 1: Write tests**

```java
@Test
public void alpn_advertises_h2_when_enabled() throws Exception {
  // TLS listener with default config (HTTP2 enabled). Client offers ["h2", "http/1.1"] → server selects "h2".
}

@Test
public void alpn_falls_back_to_http_1_1_when_disabled() throws Exception {
  // TLS listener with HTTP2 disabled. Client offers ["h2", "http/1.1"] → server selects "http/1.1".
  var listener = new HTTPListenerConfiguration(0, cert, pk).withHTTP2Enabled(false);
  // ... assert resp.version() == HTTP_1_1 ...
}

@Test
public void alpn_h2_only_client_against_disabled_h2_fails() throws Exception {
  // Client demands ["h2"] only; server with HTTP2 disabled → handshake_failure.
}
```

- [ ] **Step 2: Run, commit**

Run: `latte test --test=HTTP2ALPNTest`
Expected: PASS.

```bash
git add -A
git commit -m "Add HTTP/2 ALPN selection tests"
```

---

### Task 17: Raw frame tests

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`

- [ ] **Step 1: Write tests**

Cover what JDK HttpClient hides:
- Malformed frame size → connection close with FRAME_SIZE_ERROR
- Invalid stream-id ordering (decreasing) → PROTOCOL_ERROR
- PUSH_PROMISE inbound → PROTOCOL_ERROR
- Unknown frame type → silently ignored
- PRIORITY frame → silently ignored

- [ ] **Step 2: Run, commit**

Run: `latte test --test=HTTP2RawFrameTest`
Expected: PASS.

```bash
git add -A
git commit -m "Add HTTP/2 raw-frame conformance tests"
```

---

## Phase 6 — Cleanup and final verification

### Task 18: `HTTPRequest.isKeepAlive()` returns `true` for h2

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPRequest.java`

- [ ] **Step 1: Adjust `isKeepAlive`**

In `isKeepAlive`, before reading the `Connection` header:
```java
if ("HTTP/2.0".equals(getProtocol())) {
  return true;
}
// existing h1.1 logic...
```

- [ ] **Step 2: Compile + run full suite**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: ALL PASS.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "HTTPRequest.isKeepAlive returns true for HTTP/2"
```

---

### Task 19: Strip h1.1-only headers on h2 emission

In `HTTP2Connection`'s response-emission path (where the response headers are encoded into the HEADERS frame), filter out `Connection`, `Keep-Alive`, `Transfer-Encoding`, `Upgrade`, `Proxy-Connection`. Log at debug level.

- [ ] **Step 1: Implement the filter**

In the response-headers-to-HPACK path:

```java
private static final Set<String> H1_ONLY_HEADERS = Set.of("connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade");

private List<HPACKDynamicTable.HeaderField> responseFieldsForHeadersFrame(HTTPResponse response) {
  var out = new ArrayList<HPACKDynamicTable.HeaderField>();
  out.add(new HPACKDynamicTable.HeaderField(":status", String.valueOf(response.getStatus())));
  for (var e : response.getHeadersMap().entrySet()) {
    if (H1_ONLY_HEADERS.contains(e.getKey().toLowerCase())) {
      logger.debug("Stripping h1.1-only response header [{}] on h2 emission", e.getKey());
      continue;
    }
    for (String v : e.getValue()) {
      out.add(new HPACKDynamicTable.HeaderField(e.getKey().toLowerCase(), v));
    }
  }
  return out;
}
```

- [ ] **Step 2: Test**

Add a test in `HTTP2BasicTest`:

```java
@Test
public void h1_only_response_headers_stripped() throws Exception {
  HTTPHandler handler = (req, res) -> {
    res.setHeader("Connection", "close");
    res.setHeader("Transfer-Encoding", "chunked");
    res.setStatus(200);
    res.getOutputStream().close();
  };
  // ... send via h2 client and verify the response does not contain "connection" or "transfer-encoding" headers.
}
```

- [ ] **Step 3: Run, commit**

```bash
git add -A
git commit -m "Strip h1.1-only response headers when emitting on HTTP/2"
```

---

### Task 20: `HTTP2.md` flip

**Files:**
- Modify: `docs/specs/HTTP2.md`

- [ ] **Step 1: Walk through the spec**

For every ❌ that this plan covers, flip to ✅ and cite the test. Items expected to flip:
- All §1 transport modes
- All §2 frame types (except `PUSH_PROMISE` which stays 🚫)
- All §3 HPACK rows (covered by Plan C tests)
- All §4 stream-lifecycle rows
- All §5 flow-control rows
- All §6 pseudo-header / mapping rows
- All §7 trailer rows that depend on h2 (request and response trailers — h2)
- All §8 settings rows
- All §10 DoS-mitigation rows
- All §13 peer-comparison "❌ planned" → "✅"

- [ ] **Step 2: Commit**

```bash
git add docs/specs/HTTP2.md
git commit -m "Flip HTTP2.md spec to implemented for delivered features"
```

---

### Task 21: Final CI

- [ ] **Step 1: Full run with all guardrails**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: ALL PASS.

- [ ] **Step 2: Sanity scan**

Run: `grep -rn "TODO\|FIXME\|XXX" src/main/java/org/lattejava/http/server/internal/HTTP2*.java`
Expected: Empty (or only items consciously deferred to Plan E/F).

- [ ] **Step 3: Tag**

```bash
git tag -a http2-wire-up-complete -m "Plan D complete: real HTTP/2 traffic on all three transport modes"
```

---

## Self-review checklist

- ✅ `ClientConnection` interface introduced before second protocol arrives — keeps cleaner thread protocol-agnostic
- ✅ `ProtocolSelector` handles all three transport modes
- ✅ `HTTP2Connection` constructor variants for both prior-knowledge (preface still on wire) and post-Upgrade (preface follows 101) cases
- ✅ Each handler method (settings/ping/window-update/rst-stream/headers/data) has a corresponding integration or security test
- ✅ Configuration knobs all delivered with tests
- ✅ Integration tests use JDK `HttpClient` for h2-aware happy paths and raw sockets for the parts `HttpClient` hides
- ⚠️ Task 9 (`runFrameLoop` and handler methods) is the largest single chunk — at execution time, consider splitting into one task per handler. Don't try to write all 200 lines in one TDD cycle.
- ⚠️ The interaction between `HTTPResponse` (currently h1.1-shaped, with `HTTPOutputStream` doing transfer encoding) and h2's emit-headers-then-data shape is non-trivial. The plan assumes a small `ResponseEmitter` indirection (or direct adapter inside `HTTP2Connection`) — the cleanest decomposition will only become visible during implementation. Allow time for that.
- ⚠️ Settings retroactive window adjustment per §6.9.2 (per-stream lock + condition signal on SETTINGS-induced delta) — mentioned in design doc but no dedicated task here. Add as needed during Task 9.

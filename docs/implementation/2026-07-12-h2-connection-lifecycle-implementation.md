# HTTP/2 Connection Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `docs/design/2026-07-09-h2-connection-lifecycle-design.md` in one phase: correct H2 connection
persistence (RFC 9113 §9.1), graceful idle expiry, PING-transparent deadlines, shared liveness policy in a base class,
and deliberate connection-lifetime bounds.

**Architecture:** Liveness policy moves from `ConnectionReaperThread` into an abstract `BaseHTTPConnection` that both
protocol connections extend; the reaper becomes a dumb scheduler calling `check(now)`/`evict(reason)`. `Throughput`
gains per-phase epoch resets (same math). H2 gains a keep-alive-driven SO_TIMEOUT loop with PING-transparent idle
expiry, lifecycle-classified reader exceptions, client-GOAWAY draining, and max-requests/max-age bounds. Config knobs
`keepAliveTimeoutDuration` and `maxRequestsPerConnection` move up to `HTTPServerConfiguration`; `maxConnectionAgeDuration`
is added there.

**Tech Stack:** Java 21, Latte build (`latte`), TestNG. Zero production dependencies.

## Global Constraints

- Branch: all work on `fix/h2-connection-lifecycle` (create from `main`; never commit to `main`).
- Build/test commands: `latte clean build` (compile), `latte test` (all tests), `latte test --test=ClassName` (one
  class). Tests are TestNG, live under `src/test/java/org/lattejava/http/tests/`.
- License headers: files derived from FusionAuth java-http (`HTTPServerAcceptorThread`, `HTTP1Connection`,
  `Throughput`, `HTTPServerConfiguration`, `Configurable`) KEEP their Apache-2.0/FusionAuth headers verbatim. Brand-new
  files get the MIT header `Copyright (c) 2026 The Latte Project` + `SPDX-License-Identifier: MIT` (exact form in
  `.claude/rules/copyright.md`).
- Code style: 2-space indent, 120-char target lines, alphabetized fields/methods within visibility groups, module
  imports (`import module java.base;`), runtime values in messages wrapped in `[brackets]`, acronyms fully uppercased.
- Loggers: `System.Logger`, logger field first in the class, vararg MessageFormat-style log calls — never string
  concatenation or lambdas.
- Slow tests (sleeps > ~1 s) go in TestNG group `"timeouts"`: `@Test(groups = "timeouts")`.
- Conventional Commits (`feat:`/`fix:`/`refactor:`/`test:`), one commit per task minimum.
- No new dependencies. No changes to HPACK, frame codecs (beyond the §Task-5 frame-start marker), or flow control.

---

### Task 1: Reader-loop exception classification (design §4.1)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java` (the `catch (Throwable t)` at
  ~line 222 and surrounding frame loop)
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2LifecycleTest.java` (create; extends `BaseHTTP2RawTest`)

**Interfaces:**
- Consumes: `BaseHTTP2RawTest.openH2CConnection(int)`, `readUntilGoaway(InputStream)` (returns GOAWAY error code, or
  −1 on EOF-before-GOAWAY), `BaseTest.makeServer(String, HTTPHandler, HTTPListenerConfiguration)`.
- Produces: `HTTP2Connection`'s frame loop treats `EOFException` / `SocketException` / `SSLException` / other
  `IOException` as DEBUG-level lifecycle events with no GOAWAY, and (for now) `SocketTimeoutException` likewise —
  Task 5 replaces the timeout arm with idle-expiry logic. `catch (Throwable)` + SEVERE + `GOAWAY(INTERNAL_ERROR)`
  remains only for non-IOException throwables.

- [ ] **Step 0: Create the branch**

```bash
cd /Users/bpontarelli/dev/latte-java/http && git checkout -b fix/h2-connection-lifecycle
```

- [ ] **Step 1: Write the failing test**

Create `HTTP2LifecycleTest` (MIT header, `package org.lattejava.http.tests.server;`):

```java
public class HTTP2LifecycleTest extends BaseHTTP2RawTest {
  @Test
  public void abruptClientCloseDoesNotEmitInternalError() throws Exception {
    try (var server = makeServer("http", (req, res) -> res.setStatus(200),
        new HTTPListenerConfiguration(0, true)).start()) {
      int port = server.getListeners().getFirst().getPort();
      Socket socket = openH2CConnection(port);

      // Half-close: send FIN but keep reading. The server reader sees EOF without a GOAWAY. The old code answered
      // with SEVERE + GOAWAY(INTERNAL_ERROR); the fix must close quietly - readUntilGoaway returns -1 on plain EOF.
      socket.shutdownOutput();
      int goawayCode = readUntilGoaway(socket.getInputStream());
      assertEquals(goawayCode, -1, "Expected a quiet close on client EOF, but got GOAWAY code [" + goawayCode + "]");
      socket.close();
    }
  }
}
```

Note: check how existing raw tests construct the h2c listener (`HTTP2H2cPriorKnowledgeTest` uses an
`HTTPListenerConfiguration` with the protocol-sniffing flag) and copy that exact construction, including how they get
the bound port; adjust the two lines above to match the real helper signatures before running.

- [ ] **Step 2: Run it to verify it fails**

Run: `latte test --test=HTTP2LifecycleTest`
Expected: FAIL — `goawayCode` is `2` (INTERNAL_ERROR) because the current `catch (Throwable)` emits it on EOF.

- [ ] **Step 3: Implement the classification**

In `HTTP2Connection.run()`, wrap the frame loop's exception handling. Replace:

```java
      } catch (Throwable t) {
        logger.log(Level.ERROR, "Unhandled exception in HTTP/2 reader; emitting GOAWAY(INTERNAL_ERROR)", t);
        goAway(HTTP2ErrorCode.INTERNAL_ERROR);
      } finally {
```

with:

```java
      } catch (EOFException e) {
        // The client closed (or half-closed) without a GOAWAY. Common and clean - no GOAWAY back; the peer is gone.
        logger.log(Level.DEBUG, "HTTP/2 client closed the connection without GOAWAY: [{0}]", e.getMessage());
      } catch (SocketTimeoutException e) {
        // No bytes within SO_TIMEOUT. Task 5 turns this into keep-alive idle expiry; until then, a quiet teardown.
        logger.log(Level.DEBUG, "HTTP/2 connection timed out waiting for frames");
      } catch (SocketException | SSLException e) {
        // Local close (eviction, server shutdown) or TLS teardown - a lifecycle event, not a server bug.
        logger.log(Level.DEBUG, "HTTP/2 connection closed: [{0}]", e.getMessage());
      } catch (IOException e) {
        logger.log(Level.DEBUG, "HTTP/2 connection ended", e);
      } catch (Throwable t) {
        logger.log(Level.ERROR, "Unhandled exception in HTTP/2 reader; emitting GOAWAY(INTERNAL_ERROR)", t);
        goAway(HTTP2ErrorCode.INTERNAL_ERROR);
      } finally {
```

`SSLException` needs `import javax.net.ssl.SSLException;` (class import — it is not in `java.base`). Order matters:
the three frame-reader exceptions (`FrameSizeException` etc.) are IOException subclasses already caught *inside* the
loop at the `readFrame()` call; do not move them.

- [ ] **Step 4: Run the test to verify it passes, then run the H2 suites**

Run: `latte test --test=HTTP2LifecycleTest` → PASS.
Run: `latte test --test=HTTP2GoawayTest && latte test --test=HTTP2BasicTest && latte test --test=HTTP2SecurityTest`
Expected: PASS (no wire-behavior change for protocol errors).

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "fix: classify H2 reader lifecycle exceptions instead of GOAWAY(INTERNAL_ERROR)"
```

---

### Task 2: Configuration moves and additions (design §4.8, §5)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java`
- Modify: `src/main/java/org/lattejava/http/server/HTTP1Configuration.java` (remove two settings)
- Modify: `src/main/java/org/lattejava/http/server/Configurable.java` (add three default `with*` methods)
- Modify: `src/main/java/org/lattejava/http/server/internal/h1/HTTP1Connection.java:221,238` (call sites)
- Modify: `src/test/java/org/lattejava/http/tests/server/BaseTest.java` (`makeServer` overloads)
- Modify: any test calling `withHTTP1(h1 -> h1.withKeepAliveTimeoutDuration(...))` or
  `h1.withMaxRequestsPerConnection(...)` — find with
  `grep -rln "withKeepAliveTimeoutDuration\|withMaxRequestsPerConnection\|getKeepAliveTimeoutDuration\|getMaxRequestsPerConnection" src/`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTPServerConfigurationValidationTest.java` (add cases),
  `HTTP1ConfigurationTest.java` (remove moved cases)

**Interfaces:**
- Produces (used by Tasks 4–6):
  - `HTTPServerConfiguration.getKeepAliveTimeoutDuration()` → `Duration`, default 20 s
  - `HTTPServerConfiguration.getMaxRequestsPerConnection()` → `int`, default 100_000
  - `HTTPServerConfiguration.getMaxConnectionAgeDuration()` → `Duration`, default `null` (= unlimited)
  - Matching `withKeepAliveTimeoutDuration(Duration)`, `withMaxRequestsPerConnection(int)`,
    `withMaxConnectionAgeDuration(Duration)` on `HTTPServerConfiguration` and as `Configurable` defaults.

- [ ] **Step 1: Write failing validation tests**

In `HTTPServerConfigurationValidationTest`, following its existing assert-throws style (read the file first and copy
its idiom exactly):

```java
@Test
public void keepAliveTimeoutValidation() {
  assertThrows(IllegalArgumentException.class, () -> new HTTPServerConfiguration().withKeepAliveTimeoutDuration(Duration.ZERO));
  assertThrows(NullPointerException.class, () -> new HTTPServerConfiguration().withKeepAliveTimeoutDuration(null));
  assertEquals(new HTTPServerConfiguration().getKeepAliveTimeoutDuration(), Duration.ofSeconds(20));
}

@Test
public void maxRequestsPerConnectionValidation() {
  assertThrows(IllegalArgumentException.class, () -> new HTTPServerConfiguration().withMaxRequestsPerConnection(9));
  assertEquals(new HTTPServerConfiguration().getMaxRequestsPerConnection(), 100_000);
}

@Test
public void maxConnectionAgeValidation() {
  assertNull(new HTTPServerConfiguration().getMaxConnectionAgeDuration());
  assertThrows(IllegalArgumentException.class, () -> new HTTPServerConfiguration().withMaxConnectionAgeDuration(Duration.ZERO));
  assertEquals(new HTTPServerConfiguration().withMaxConnectionAgeDuration(Duration.ofMinutes(5)).getMaxConnectionAgeDuration(), Duration.ofMinutes(5));
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `latte test --test=HTTPServerConfigurationValidationTest`
Expected: compile error — methods do not exist.

- [ ] **Step 3: Implement the moves**

In `HTTPServerConfiguration` add fields (alphabetized among existing privates):

```java
  private Duration keepAliveTimeoutDuration = Duration.ofSeconds(20);

  private Duration maxConnectionAgeDuration; // null means unlimited

  private int maxRequestsPerConnection = 100_000; // 100,000
```

Getters (alphabetized among getters; Javadoc in the file's existing style):

```java
  /**
   * @return The idle timeout between requests on a persistent connection, shared by HTTP/1.1 keep-alive and the
   *     HTTP/2 zero-stream idle deadline. Defaults to 20 seconds.
   */
  public Duration getKeepAliveTimeoutDuration() {
    return keepAliveTimeoutDuration;
  }

  /**
   * @return The maximum age of a connection before it is gracefully closed, or null for unlimited. Defaults to null.
   */
  public Duration getMaxConnectionAgeDuration() {
    return maxConnectionAgeDuration;
  }

  /**
   * @return The maximum number of requests handled on one connection. Defaults to 100,000.
   */
  public int getMaxRequestsPerConnection() {
    return maxRequestsPerConnection;
  }
```

`with*` methods: move the bodies verbatim from `HTTP1Configuration.withKeepAliveTimeoutDuration` (including its
validation) and `withMaxRequestsPerConnection`, changing the return type to `HTTPServerConfiguration`. Add:

```java
  /**
   * Sets the maximum connection age before a graceful close. Null means unlimited.
   *
   * @param duration The duration, or null to disable.
   * @return This.
   */
  public HTTPServerConfiguration withMaxConnectionAgeDuration(Duration duration) {
    if (duration != null && (duration.isZero() || duration.isNegative())) {
      throw new IllegalArgumentException("The max connection age duration must be greater than 0");
    }

    this.maxConnectionAgeDuration = duration;
    return this;
  }
```

In `Configurable`, add three default methods following the exact pattern of `withInitialReadTimeout` (delegate through
`configuration()`), alphabetized. In `HTTP1Configuration`, delete the two fields, two getters, and two `with*` methods.

Call sites:
- `HTTP1Connection.java:221`: `configuration.getHTTP1Configuration().getMaxRequestsPerConnection()` →
  `configuration.getMaxRequestsPerConnection()`
- `HTTP1Connection.java:238`: `configuration.getHTTP1Configuration().getKeepAliveTimeoutDuration()` →
  `configuration.getKeepAliveTimeoutDuration()`
- `BaseTest.makeServer` (both overloads): delete `h1.withKeepAliveTimeoutDuration(ServerTimeout)` from the
  `withHTTP1(...)` lambda (keep the `withExpectValidator` part) and add `.withKeepAliveTimeoutDuration(ServerTimeout)`
  at the top level of the chain.
- Fix every other hit from the grep above the same way; move (do not delete) any `HTTP1ConfigurationTest` assertions
  for the two moved settings into `HTTPServerConfigurationValidationTest`.

- [ ] **Step 4: Full test run**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat!: move keepAliveTimeoutDuration and maxRequestsPerConnection to HTTPServerConfiguration; add maxConnectionAgeDuration"
```

---

### Task 3: Throughput phase epochs (design §6)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/io/Throughput.java` (keep Apache header)
- Test: `src/test/java/org/lattejava/http/tests/io/ThroughputTest.java` (create; MIT header)

**Interfaces:**
- Produces (used by Task 4's `BaseHTTPConnection`):
  - `Throughput.resetRead()` — zeroes `numberOfBytesRead`, `firstReadInstant`, `lastReadInstant` (NOT the write side)
  - `Throughput.resetWrite()` — zeroes `numberOfBytesWritten`, `firstWroteInstant`, `lastWroteInstant`
  - `lastUsed()`, `readThroughput(long)`, `writeThroughput(long)` unchanged.

- [ ] **Step 1: Write failing tests**

```java
public class ThroughputTest {
  @Test
  public void resetReadStartsAFreshEpoch() throws Exception {
    var t = new Throughput(0, 0); // zero calculation delay: rate math applies immediately
    t.read(500);
    Thread.sleep(30);
    t.read(500);              // 1000 bytes over ~30ms - a high rate
    t.wrote(100);             // written bytes disable the read-delay grace, as in production after a response

    long stale = t.readThroughput(System.currentTimeMillis());
    assertTrue(stale > 10_000, "Expected a healthy rate, got [" + stale + "]");

    t.resetRead();
    // Fresh epoch: no reads yet - the same guard as a brand-new connection (Long.MAX_VALUE).
    assertEquals(t.readThroughput(System.currentTimeMillis()), Long.MAX_VALUE);

    t.read(100);
    Thread.sleep(30);
    t.read(100);
    long fresh = t.readThroughput(System.currentTimeMillis());
    // Rate computed from the 200 bytes over ~30ms of THIS epoch, not the 1000-byte history.
    assertTrue(fresh > 1_000 && fresh < 100_000, "Expected an epoch-local rate, got [" + fresh + "]");
  }

  @Test
  public void resetWriteStartsAFreshEpoch() {
    var t = new Throughput(0, 0);
    t.wrote(1_000_000);
    t.resetWrite();
    assertEquals(t.writeThroughput(System.currentTimeMillis()), Long.MAX_VALUE);
  }

  @Test
  public void resetReadDoesNotTouchTheWriteSide() {
    var t = new Throughput(0, 0);
    t.wrote(500);
    t.resetRead();
    assertTrue(t.lastUsed() != Long.MAX_VALUE, "lastWroteInstant must survive resetRead");
  }
}
```

- [ ] **Step 2: Run to verify compile failure**

Run: `latte test --test=ThroughputTest` — expected: compile error, `resetRead` undefined.

- [ ] **Step 3: Implement**

Add to `Throughput` (alphabetized among the instance methods):

```java
  /**
   * Starts a fresh read-side measurement epoch. Called when a connection enters a read phase so the throughput sample
   * measures the current request rather than the connection's lifetime (an idle gap would otherwise poison the
   * denominator and evict a healthy client).
   */
  public synchronized void resetRead() {
    firstReadInstant = 0;
    lastReadInstant = 0;
    numberOfBytesRead = 0;
  }

  /**
   * Starts a fresh write-side measurement epoch. Called when a connection begins writing a response.
   */
  public synchronized void resetWrite() {
    firstWroteInstant = 0;
    lastWroteInstant = 0;
    numberOfBytesWritten = 0;
  }
```

Note: `readThroughput` line ~86 returns `numberOfBytesWritten` as the "always zero" value inside the
`numberOfBytesWritten == 0` branch — after `resetWrite()` that branch can be re-entered mid-connection; that is the
same code path a fresh connection takes and needs no change.

- [ ] **Step 4: Run** `latte test --test=ThroughputTest` → PASS.

- [ ] **Step 5: Commit** `git add -A && git commit -m "feat: Throughput phase-epoch resets"`

---

### Task 4: HTTPConnection contract, BaseHTTPConnection, reaper, H1, dispatcher (design §4.3, §4.4)

> **As-built deviation:** this task's `check()` code block below performs epoch resets on the reaper's rising-edge
> observation. That was found to break slow-client eviction (a late `resetWrite()` wipes an in-flight blocked write
> phase, pinning `writeThroughput` at MAX_VALUE). The implementation follows the design doc (§4.4/§6) instead:
> `check()` is gate-only, and resets happen at real phase-entry points on the I/O threads — H1 in
> `enterReadPhase()`/`enterWritePhase()`, H2 on the reader thread (reset-before-blocking-read while the read gate is
> closed; rising-edge `resetWrite()` after dispatch).

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/EvictionReason.java` (MIT header)
- Create: `src/main/java/org/lattejava/http/server/internal/BaseHTTPConnection.java` (MIT header)
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPConnection.java` (remove `state()` + `State`)
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPServerAcceptorThread.java` (reaper body; keep Apache header)
- Modify: `src/main/java/org/lattejava/http/server/internal/h1/HTTP1Connection.java` (extends base; private `State`)
- Modify: `src/main/java/org/lattejava/http/server/internal/ConnectionDispatcher.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java` (interim `check`/`evict`)
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP1LifecycleTest.java` (create)

**Interfaces:**
- Produces:

```java
public enum EvictionReason { MaxAge, ProcessingTimeout, SlowRead, SlowWrite }

public interface HTTPConnection extends Runnable {
  EvictionReason check(long now);   // null = healthy
  void evict(EvictionReason reason); // non-blocking, idempotent
  long getHandledRequests();
  Socket getSocket();
  long getStartInstant();
  void shutdown();
}
```

```java
public abstract class BaseHTTPConnection implements HTTPConnection {
  protected abstract boolean processing();
  protected abstract boolean readingRequest();
  protected abstract boolean writingResponse();
  protected abstract long lastProgressInstant();
  public final EvictionReason check(long now);  // implemented here, see below
}
```

- Consumes: Task 2 getters, Task 3 resets.

- [ ] **Step 1: Write the failing H1 idle-gap regression test** (design §8 test 12)

Create `HTTP1LifecycleTest` extending `BaseTest`. Build a server with a *hostile-to-the-old-code* config: 10 s+ gap,
low floor, short calculation delay, default 2 s reaper cadence:

```java
public class HTTP1LifecycleTest extends BaseTest {
  @Test(groups = "timeouts")
  public void keepAliveIdleGapDoesNotPoisonTheNextRequest() throws Exception {
    try (var server = new HTTPServer().withHandler((req, res) -> res.setStatus(200))
                                      .withKeepAliveTimeoutDuration(Duration.ofSeconds(60))
                                      .withInitialReadTimeout(Duration.ofSeconds(30))
                                      .withMinimumReadThroughput(1024)
                                      .withReadThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                                      .withListener(new HTTPListenerConfiguration(0)).start()) {
      int port = server.getListeners().getFirst().getPort();
      try (var socket = new Socket("127.0.0.1", port)) {
        var out = socket.getOutputStream();
        var in = socket.getInputStream();

        out.write("GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".getBytes());
        out.flush();
        readFullResponse(in); // helper below

        Thread.sleep(10_000); // idle keep-alive gap - poisons the old lifetime denominator

        // Request 2 with a slowly-dribbled body: ~2 KB/s for ~6 s, above the 1 KB/s floor. The un-reset lifetime
        // average reads (tiny bytes / 10+ s) and the old reaper evicts mid-body; the phase epoch must survive.
        byte[] body = new byte[12_000];
        out.write(("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + body.length + "\r\n\r\n").getBytes());
        for (int i = 0; i < body.length; i += 1_000) {
          out.write(body, i, 1_000);
          out.flush();
          Thread.sleep(500);
        }
        String response = readFullResponse(in);
        assertTrue(response.startsWith("HTTP/1.1 200"), "Second request after the idle gap was evicted: [" + response + "]");
      }
    }
  }

  private String readFullResponse(InputStream in) throws IOException {
    var buf = new StringBuilder();
    byte[] chunk = new byte[4096];
    // Responses here are small and Connection: keep-alive; read headers + empty body by draining until the header
    // terminator and honoring Content-Length: 0.
    while (!buf.toString().contains("\r\n\r\n")) {
      int n = in.read(chunk);
      if (n < 0) {
        break;
      }
      buf.append(new String(chunk, 0, n));
    }
    return buf.toString();
  }
}
```

The handler must drain the request body before responding — check how existing socket tests (`HTTP11SocketTest`)
structure the handler and response reads and mirror them; adjust the handler to
`(req, res) -> { req.getInputStream().readAllBytes(); res.setStatus(200); }`.

- [ ] **Step 2: Run to verify it fails** — `latte test --test=HTTP1LifecycleTest` (expected: request 2's socket dies
  mid-body — assertion failure or IOException — because the reaper evicts on the poisoned lifetime average).

- [ ] **Step 3: Implement the contract**

`EvictionReason.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

/**
 * Why the reaper evicted a connection. Returned by {@link HTTPConnection#check(long)} and passed to
 * {@link HTTPConnection#evict(EvictionReason)}.
 */
public enum EvictionReason {
  MaxAge,
  ProcessingTimeout,
  SlowRead,
  SlowWrite
}
```

`HTTPConnection.java`: delete `state()` and the whole `State` enum; add (with Javadoc):

```java
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
```

`BaseHTTPConnection.java` — the shared policy (design §4.4). Epoch resets are edge-detected here: on the first pass
that observes a gate open (after a pass that observed it closed), reset that direction's counters and skip that
direction's check for the pass; a phase is then always sampled against its own epoch. Constructor takes what both
subclasses already hold:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

/**
 * Shared liveness policy for protocol connections. {@link #check(long)} is the reaper's former per-state policy with
 * the single state scalar replaced by protocol-supplied gates, so an HTTP/2 connection - which can be reading,
 * writing, and processing simultaneously - answers each question independently. The Throughput math, floors, and
 * graces are unchanged; on the rising edge of a gate the corresponding counters reset so every sample measures the
 * current phase (see the design doc, 2026-07-09-h2-connection-lifecycle-design.md section 4.4).
 */
public abstract class BaseHTTPConnection implements HTTPConnection {
  protected final HTTPServerConfiguration configuration;

  protected final Socket socket;

  protected final long startInstant;

  protected final Throughput throughput;

  private boolean readGateWasOpen;

  private boolean writeGateWasOpen;

  protected BaseHTTPConnection(HTTPServerConfiguration configuration, Socket socket, Throughput throughput) {
    this.configuration = configuration;
    this.socket = socket;
    this.throughput = throughput;
    this.startInstant = System.currentTimeMillis();
  }

  @Override
  public final EvictionReason check(long now) {
    boolean reading = readingRequest();
    if (reading && !readGateWasOpen) {
      throughput.resetRead(); // rising edge: new read phase, fresh epoch; skip this pass's sample
    } else if (reading && throughput.readThroughput(now) < configuration.getMinimumReadThroughput()) {
      return EvictionReason.SlowRead;
    }
    readGateWasOpen = reading;

    boolean writing = writingResponse();
    if (writing && !writeGateWasOpen) {
      throughput.resetWrite();
    } else if (writing && throughput.writeThroughput(now) < configuration.getMinimumWriteThroughput()) {
      return EvictionReason.SlowWrite;
    }
    writeGateWasOpen = writing;

    if (processing() && now - lastProgressInstant() > configuration.getProcessingTimeoutDuration().toMillis()) {
      return EvictionReason.ProcessingTimeout;
    }

    Duration maxAge = configuration.getMaxConnectionAgeDuration();
    if (maxAge != null && now - startInstant > maxAge.toMillis()) {
      return EvictionReason.MaxAge;
    }

    return null;
  }

  @Override
  public Socket getSocket() {
    return socket;
  }

  @Override
  public long getStartInstant() {
    return startInstant;
  }

  /**
   * The instant of the last observable progress (bytes moved, work advanced) used by the processing-timeout check.
   */
  protected abstract long lastProgressInstant();

  /**
   * True while work is in flight (a request is being processed).
   */
  protected abstract boolean processing();

  /**
   * True while the peer owes us request bytes - the slow-reader floor applies only then.
   */
  protected abstract boolean readingRequest();

  /**
   * True while a response is being written - the slow-writer floor applies only then.
   */
  protected abstract boolean writingResponse();
}
```

`check` is called only from the single reaper thread, so the two gate-history fields need no synchronization — note
this in a comment on the fields.

`HTTP1Connection`: `extends BaseHTTPConnection` (still `implements` nothing extra — the base implements
`HTTPConnection`). Constructor calls `super(configuration, socket, throughput)`; delete the now-inherited `socket`,
`startInstant`, `throughput` fields and `getSocket`/`getStartInstant` methods (keep `configuration` if other code uses
it, otherwise use the inherited protected field). Move the `State` enum INTO `HTTP1Connection` as a private nested
enum with values `KeepAlive, Process, Read, Write` (drop `Negotiating` — it was only for the dispatcher), and change
every `HTTPConnection.State.X` reference in the file to `State.X`. Delete the `state()` override. Add:

```java
  @Override
  public void evict(EvictionReason reason) {
    // The reaper's historical behavior: a plain close. The parked worker thread surfaces a SocketException, which the
    // run() loop already classifies as an expected close.
    try {
      socket.close();
    } catch (IOException ignore) {
    }
  }

  @Override
  protected long lastProgressInstant() {
    return throughput.lastUsed();
  }

  @Override
  protected boolean processing() {
    return state == State.Process;
  }

  @Override
  protected boolean readingRequest() {
    return state == State.Read;
  }

  @Override
  protected boolean writingResponse() {
    return state == State.Write;
  }
```

`ConnectionDispatcher`: replace `state()` with:

```java
  @Override
  public EvictionReason check(long now) {
    // Until protocol selection resolves, negotiation is bounded by the socket's initial-read SO_TIMEOUT - never
    // reaper-evicted (the old Negotiating exemption).
    return delegate != null ? delegate.check(now) : null;
  }

  @Override
  public void evict(EvictionReason reason) {
    if (delegate != null) {
      delegate.evict(reason);
    }
  }
```

`HTTP2Connection` (interim, replaced in Task 5): delete `state()` and the `state` field writes; add
`public EvictionReason check(long now) { return null; }` and an `evict(EvictionReason)` that closes the socket the
same way `HTTP1Connection.evict` does. Compiles and keeps H2 un-reaped until Task 5 restores real policy.

`HTTPServerAcceptorThread` reaper: replace the entire state-switch body (the `HTTPConnection.State state = ...`
declaration down through the `socket.close()` block) with:

```java
          long now = System.currentTimeMillis();
          HTTPConnection worker = client.connection();
          EvictionReason reason = worker.check(now);
          if (reason == null) {
            logger.log(Level.TRACE, "[{0}] Worker is healthy", threadId);
            continue;
          }

          logger.log(Level.DEBUG, "[{0}] Evicting connection [{1}]. Reason [{2}]. Requests handled [{3}].",
              threadId, worker.getSocket().getRemoteSocketAddress(), reason, worker.getHandledRequests());
          iterator.remove();
          removedClientCount++;
          worker.evict(reason);

          if (instrumenter != null) {
            instrumenter.connectionClosed();
          }
```

Keep the dead-thread removal above it and the TRACE thread-dump block if desired (it referenced the old message
variables — delete the `badClientReason`/`message` string assembly entirely). Delete the now-unused
`minimumReadThroughput`/`minimumWriteThroughput` fields and the `Throughput throughput = client.throughput();` usage
(the `ClientConnection` record keeps its `throughput` component — it is still passed to connections at accept).

- [ ] **Step 4: Run the new test and the full suite**

Run: `latte test --test=HTTP1LifecycleTest` → PASS (survives the gap).
Run: `latte clean int --excludePerformance` (include timeouts group — the H1 keep-alive timeout tests in `CoreTest`
exercise the reaper) → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "refactor!: move liveness policy from the reaper into BaseHTTPConnection gates with phase epochs"
```

---

### Task 5: H2 lifecycle — idle loop, gates, graceful evict (design §4.2, §4.5, §4.6)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2FrameReader.java` (frame-start marker)
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2StreamRegistry.java` (gate queries + idle instant)
- Modify: `src/main/java/org/lattejava/http/server/io/Throughput.java` (add `lastWroteInstant()` accessor)
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2LifecycleTest.java` (extend)

**Interfaces:**
- Produces:
  - `HTTP2FrameReader.frameStartInstant()` → `long` (0 = between frames; volatile, stamped at a frame's first byte,
    cleared when the raw frame completes)
  - `HTTP2StreamRegistry.anyRequestSideOpen()` → `boolean` (any live stream in `OPEN` or `HALF_CLOSED_LOCAL`)
  - `HTTP2StreamRegistry.anyResponsePending()` → `boolean` (any live stream in `HALF_CLOSED_REMOTE`)
  - `HTTP2StreamRegistry.emptySince()` → `long` (instant the roster last became/was empty; 0 while non-empty)
  - `Throughput.lastWroteInstant()` → `long` (synchronized accessor)
  - `HTTP2Connection.check/evict` real implementations; reader loop with keep-alive SO_TIMEOUT budget.
- Consumes: Task 2 `getKeepAliveTimeoutDuration()`, Task 4 base class.

- [ ] **Step 1: Write the failing lifecycle tests** (design §8 tests 1–5)

Add to `HTTP2LifecycleTest`. For each, build the server inline (not `makeServer`) so the timeouts are explicit; copy
the h2c listener construction from the Task 1 test. GOAWAY code for `NO_ERROR` is `0`.

```java
  @Test(groups = "timeouts")
  public void idleConnectionSurvivesInitialReadTimeoutAndReaper() throws Exception {
    // initialReadTimeout=2s, keepAlive=30s: the connection must idle far past 2s and still serve a request.
    // Old behavior: SO_TIMEOUT stayed at 2s and the connection died with GOAWAY(INTERNAL_ERROR).
    try (var server = h2cServer(cfg -> cfg.withInitialReadTimeout(Duration.ofSeconds(2))
                                          .withKeepAliveTimeoutDuration(Duration.ofSeconds(30)))) {
      Socket socket = openH2CConnection(port(server));
      Thread.sleep(6_000);
      writeSimpleGet(socket, 1); // HPACK-encoded GET on stream 1 - copy the canned bytes from HTTP2RawFrameTest
      assertTrue(readUntilResponseHeaders(socket.getInputStream()) == 1, "Idle connection was killed");
      socket.close();
    }
  }

  @Test(groups = "timeouts")
  public void idleExpiryIsGracefulGoawayNoError() throws Exception {
    try (var server = h2cServer(cfg -> cfg.withKeepAliveTimeoutDuration(Duration.ofSeconds(1)))) {
      Socket socket = openH2CConnection(port(server));
      long start = System.currentTimeMillis();
      int code = readUntilGoaway(socket.getInputStream());
      long elapsed = System.currentTimeMillis() - start;
      assertEquals(code, 0, "Idle expiry must be GOAWAY(NO_ERROR)");
      assertTrue(elapsed >= 800 && elapsed < 5_000, "Idle expiry landed at [" + elapsed + "] ms");
      socket.close();
    }
  }

  @Test(groups = "timeouts")
  public void pingsDoNotExtendTheIdleDeadline() throws Exception {
    try (var server = h2cServer(cfg -> cfg.withKeepAliveTimeoutDuration(Duration.ofSeconds(2)))) {
      Socket socket = openH2CConnection(port(server));
      var out = socket.getOutputStream();
      var in = socket.getInputStream();
      long start = System.currentTimeMillis();
      int code = -2;
      // PING every 300ms; the server must ACK them AND still expire at ~2s (not 2x, not never).
      while (System.currentTimeMillis() - start < 10_000) {
        writeFrameHeader(out, 8, 0x6, 0, 0);
        out.write(new byte[8]);
        out.flush();
        socket.setSoTimeout(300);
        try {
          int[] header = readFrameHeader(in);
          in.readNBytes(header[3]);
          if (header[0] == 0x7) {
            code = 0; // saw GOAWAY
            break;
          }
        } catch (SocketTimeoutException ignore) {
        } catch (EOFException | AssertionError e) {
          break; // server closed
        }
      }
      long elapsed = System.currentTimeMillis() - start;
      assertEquals(code, 0, "Expected GOAWAY despite PING keep-alives");
      assertTrue(elapsed >= 1_500 && elapsed < 4_500, "Deadline drifted: [" + elapsed + "] ms");
      socket.close();
    }
  }

  @Test(groups = "timeouts")
  public void midFrameTrickleIsEvicted() throws Exception {
    try (var server = h2cServer(cfg -> cfg.withKeepAliveTimeoutDuration(Duration.ofSeconds(1)))) {
      Socket socket = openH2CConnection(port(server));
      var out = socket.getOutputStream();
      // 3 bytes of a frame header, then silence: mid-frame with zero live streams. The shrinking idle budget fires
      // and the connection must close within a few seconds instead of hanging forever.
      out.write(new byte[]{0, 0, 8});
      out.flush();
      socket.setSoTimeout(8_000);
      assertEquals(socket.getInputStream().read(new byte[64]) >= 0 ? keepDraining(socket) : -1, -1,
          "Connection must be closed after a mid-frame stall");
      socket.close();
    }
  }
```

Add the small private helpers in the test class: `h2cServer(Consumer<HTTPServer>)` (handler `(req, res) ->
{ req.getInputStream().readAllBytes(); res.setStatus(200); }`, port-0 h2c listener, started), `port(server)`,
`writeSimpleGet(Socket, int streamId)` (copy the canned HPACK GET request bytes from `HTTP2RawFrameTest` — find them
with `grep -n "HEADERS" src/test/java/org/lattejava/http/tests/server/HTTP2RawFrameTest.java`), and
`keepDraining(Socket)` (read until EOF, return −1). Exact helper shapes are the implementer's choice; assertions above
are the contract.

- [ ] **Step 2: Run to verify failures** — `latte test --test=HTTP2LifecycleTest` (new tests fail: idle survival dies
  at 2 s; expiry/PING tests see EOF with no GOAWAY or INTERNAL_ERROR instead of NO_ERROR).

- [ ] **Step 3: Implement the frame-start marker**

In `HTTP2FrameReader.readRawFrame()`, replace the header read:

```java
    if (in.readNBytes(buffer, 0, 9) != 9) {
      throw new EOFException("Connection closed before frame header");
    }
```

with:

```java
    int first = in.read();
    if (first == -1) {
      throw new EOFException("Connection closed before frame header");
    }
    frameStartInstant = System.currentTimeMillis();
    buffer[0] = (byte) first;
    if (in.readNBytes(buffer, 1, 8) != 8) {
      throw new EOFException("Connection closed before frame header");
    }
```

Add the field (volatile — read by the reaper thread), an accessor, and clear it at the end of `readRawFrame` right
before the `return switch (type) {` by assigning inside a try/finally around the switch... simplest correct form:
compute the frame in a local variable via the existing switch, then:

```java
    HTTP2Frame frame = switch (type) {
      // ... existing cases unchanged ...
    };
    frameStartInstant = 0;
    return frame;
```

```java
  private volatile long frameStartInstant;

  /**
   * @return The instant the frame currently being read started arriving, or 0 between frames. Read by the reaper
   *     thread for the mid-frame half of the slow-reader gate.
   */
  public long frameStartInstant() {
    return frameStartInstant;
  }
```

Note the exception paths (`FrameSizeException` etc.) intentionally leave the marker set — the connection is dying
anyway.

- [ ] **Step 4: Implement the registry queries**

In `HTTP2StreamRegistry` add a volatile `emptySince` initialized in the constructor
(`this.emptySince = System.currentTimeMillis();`), updated at the end of `open()` (`emptySince = 0;` after the `put`),
and at the end of `close()` and `remove()`
(`emptySince = openStreams.isEmpty() ? System.currentTimeMillis() : emptySince == 0 ? 0 : emptySince;` — simpler:
`if (openStreams.isEmpty()) { emptySince = System.currentTimeMillis(); }` in both, plus `emptySince = 0` in `open`;
a benign race with the reaper is acceptable, note it). Add:

```java
  /**
   * @return True when any live stream's request side is still open (the client has not sent END_STREAM or been
   *     reset) - the client owes us bytes.
   */
  public boolean anyRequestSideOpen() {
    for (HTTP2Stream stream : openStreams.values()) {
      HTTP2Stream.State s = stream.state();
      if (s == HTTP2Stream.State.OPEN || s == HTTP2Stream.State.HALF_CLOSED_LOCAL) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return True when any live stream has a completed request awaiting or receiving its response.
   */
  public boolean anyResponsePending() {
    for (HTTP2Stream stream : openStreams.values()) {
      if (stream.state() == HTTP2Stream.State.HALF_CLOSED_REMOTE) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return The instant the roster last became empty (or the registry's construction), or 0 while streams are live.
   *     Approximate under concurrent close - the readers are advisory (idle budget, reaper).
   */
  public long emptySince() {
    return emptySince;
  }
```

Add to `Throughput` (Apache file, alphabetized):

```java
  /**
   * @return The instant of the last write, or 0 if nothing has been written this epoch.
   */
  public synchronized long lastWroteInstant() {
    return lastWroteInstant;
  }
```

- [ ] **Step 5: Implement the HTTP2Connection lifecycle**

`HTTP2Connection` extends `BaseHTTPConnection` (constructor: `super(configuration, socket, throughput);` — delete the
now-duplicated fields/getters; NOTE the class also stores `configuration` and `throughput` for other uses — use the
inherited protected fields). New fields:

```java
  // Instant of the last stream-addressed inbound frame (streamId != 0). Stream-0 traffic (PING, SETTINGS,
  // WINDOW_UPDATE) intentionally does NOT advance this: it proves the transport works but never extends a lifetime
  // clock, so drip-fed pings cannot hold a wedged connection as a zombie.
  private volatile long lastStreamFrameInstant;

  private volatile boolean evicting;

  private volatile HTTP2FrameReader reader; // promoted from a local so check() can see the mid-frame marker
```

Reader loop changes, in order inside `while (true)` (after the `writer.isClosed()` break):

```java
          // Keep-alive management (design section 4.2). With live streams the timeout is flat; with zero streams it
          // is the REMAINING idle budget, recomputed every iteration, so no cadence of stream-0 frames (which reset
          // the kernel timer byte-by-byte) can postpone the deadline.
          long keepAliveMillis = configuration.getKeepAliveTimeoutDuration().toMillis();
          long emptySince = registry.emptySince();
          if (emptySince == 0) {
            socket.setSoTimeout((int) Math.min(keepAliveMillis, Integer.MAX_VALUE));
          } else {
            long remaining = keepAliveMillis - (System.currentTimeMillis() - emptySince);
            if (remaining <= 0) {
              idleExpire();
              return;
            }
            socket.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
          }

          HTTP2Frame frame;
          try {
            frame = reader.readFrame();
          } catch (SocketTimeoutException e) {
            if (reader.frameStartInstant() != 0) {
              // Mid-frame silence for the whole budget: partial bytes are consumed, the stream cannot be resumed.
              logger.log(Level.DEBUG, "Closing connection: mid-frame stall");
              goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
              return;
            }
            if (registry.emptySince() != 0) {
              idleExpire();
              return;
            }
            continue; // Live streams, clean frame boundary: a quiet client awaiting responses (long-poll). Keep reading.
          } catch (HTTP2FrameReader.FrameSizeException e) {
          ...existing three catches unchanged...
          }

          if (frame.streamId() != 0) {
            lastStreamFrameInstant = System.currentTimeMillis();
          }
```

The SETTINGS negotiation happens before this loop under the accept-time 2 s SO_TIMEOUT — leave that; the first loop
iteration replaces it. `registry` construction already precedes the loop; `emptySince` starts ticking there.

`idleExpire()` and the real `check`/`evict` (replace Task 4's stubs):

```java
  private void idleExpire() {
    logger.log(Level.DEBUG, "Idle keep-alive expired; closing with GOAWAY(NO_ERROR)");
    goAway(HTTP2ErrorCode.NO_ERROR);
  }

  @Override
  public void evict(EvictionReason reason) {
    if (evicting) {
      return;
    }
    evicting = true;

    // Never block the reaper: 0ms enqueue attempt, then a short-lived virtual thread flushes and closes. The parked
    // reader wakes with a SocketException, which run() classifies as a DEBUG lifecycle event.
    HTTP2WriterThread w = writer;
    HTTP2ErrorCode code = reason == EvictionReason.MaxAge ? HTTP2ErrorCode.NO_ERROR : HTTP2ErrorCode.ENHANCE_YOUR_CALM;
    Thread.ofVirtual().name("h2-evict").start(() -> {
      if (w != null) {
        HTTP2StreamRegistry r = registry;
        int lastStreamId = r != null ? r.highestSeenStreamId() : 0;
        try {
          w.tryEnqueue(new HTTP2Frame.GoawayFrame(lastStreamId, code.value, new byte[0]), 0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
        }
        w.requestStop();
        w.join(Duration.ofSeconds(1));
      }
      try {
        socket.close();
      } catch (IOException ignore) {
      }
    });
  }

  @Override
  protected long lastProgressInstant() {
    return Math.max(lastStreamFrameInstant, throughput.lastWroteInstant());
  }

  @Override
  protected boolean processing() {
    HTTP2StreamRegistry r = registry;
    return r != null && r.emptySince() == 0;
  }

  @Override
  protected boolean readingRequest() {
    HTTP2FrameReader fr = reader;
    HTTP2StreamRegistry r = registry;
    return (fr != null && fr.frameStartInstant() != 0) || (r != null && r.anyRequestSideOpen());
  }

  @Override
  protected boolean writingResponse() {
    HTTP2StreamRegistry r = registry;
    return r != null && r.anyResponsePending();
  }
```

`goAway(NO_ERROR)` requires no change — `goAway` already takes the code. `lastProgressInstant` guard: when both are 0
(no progress yet, streams just opened), `processing()` only turns true once a stream exists, and the HEADERS frame
that opened it set `lastStreamFrameInstant` — no division-by-history problem.

Also delete the Task 1 comment on the `SocketTimeoutException` arm of the outer catch — timeouts are now consumed
inside the loop; keep the outer arm as a safety net.

- [ ] **Step 6: Run the tests**

`latte test --test=HTTP2LifecycleTest` → all PASS.
`latte test --test=HTTP2BasicTest && latte test --test=HTTP2GoawayTest && latte test --test=HTTP2FlowControlTest &&
latte test --test=HTTP2SecurityTest && latte test --test=HTTP2RateLimitsTest` → PASS.
Then the h2spec batches: `latte test --test=HTTP2H2SpecBatch3Test` (and 4, 5) → PASS.

- [ ] **Step 7: Commit**

```bash
git add -A && git commit -m "feat: H2 keep-alive idle expiry, PING-transparent deadline, and gate-based liveness"
```

---

### Task 6: Client GOAWAY drain + maxRequestsPerConnection (design §4.7, §4.8)

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2HeaderFrameHandler.java`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2LifecycleTest.java` (extend)

**Interfaces:**
- Produces: `HTTP2HeaderFrameHandler` constructor gains a trailing `BooleanSupplier draining` parameter;
  `handleNewStream` refuses new streams with `REFUSED_STREAM` while it returns true (after the HPACK decode, mirroring
  the concurrency-cap refusal so the dynamic table stays synchronized).
- Consumes: Task 2 `getMaxRequestsPerConnection()`.

- [ ] **Step 1: Write the failing tests** (design §8 tests 7, 9)

```java
  @Test(groups = "timeouts")
  public void clientGoawayDrainsInFlightStreams() throws Exception {
    try (var server = h2cServer(cfg -> cfg.withHandler((req, res) -> {
      try { Thread.sleep(500); } catch (InterruptedException e) { throw new RuntimeException(e); }
      res.setStatus(200);
    }))) {
      Socket socket = openH2CConnection(port(server));
      writeSimpleGet(socket, 1);
      // Immediately GOAWAY (lastStreamId=1, NO_ERROR). Old behavior: teardown interrupts the handler and the
      // response never arrives. New behavior: stream 1 completes, then the connection closes.
      var out = socket.getOutputStream();
      writeFrameHeader(out, 8, 0x7, 0, 0);
      out.write(new byte[]{0, 0, 0, 1, 0, 0, 0, 0});
      out.flush();
      assertEquals(readUntilResponseHeaders(socket.getInputStream()), 1, "In-flight stream must complete after client GOAWAY");
      socket.close();
    }
  }

  @Test
  public void maxRequestsPerConnectionClosesGracefully() throws Exception {
    var connections = new AtomicInteger();
    Instrumenter instrumenter = countingInstrumenter(connections); // acceptedConnection() increments
    try (var server = h2cServer(cfg -> cfg.withMaxRequestsPerConnection(10).withInstrumenter(instrumenter))) {
      // JDK client over TLS/ALPN or h2c - reuse how HTTP2BasicTest drives multi-request tests; 11 requests must all
      // succeed and the client must have been forced onto a second connection by GOAWAY(NO_ERROR).
      ...copy the multi-request pattern from HTTP2BasicTest...
      assertEquals(connections.get(), 2, "Expected a second connection after maxRequestsPerConnection GOAWAY");
    }
  }
```

For the second test read `HTTP2BasicTest` first and reuse its client/scheme pattern verbatim (TLS + ALPN via
`makeClient`); the `Instrumenter` interface — see `BaseTest` for an existing counting instrumenter or create a minimal
anonymous implementation overriding `acceptedConnection()`.

- [ ] **Step 2: Run to verify failures** — drain test: response never arrives (EOF); maxRequests test: 11th request
  fails or connections != 2.

- [ ] **Step 3: Implement**

`HTTP2Connection`: add `private volatile boolean draining;`. Pass `() -> draining` as the new last constructor arg of
`HTTP2HeaderFrameHandler`. In the dispatch loop:

- Replace the `case HTTP2Result.Shutdown` arm:

```java
            case HTTP2Result.Shutdown ignored -> {
              if (registry.liveStreams().isEmpty()) {
                return; // Peer GOAWAY with nothing in flight - exit now.
              }
              draining = true; // Serve what is open; refuse new streams; exit when the roster empties.
            }
```

- After the `switch (result)` block, add:

```java
          // Drain completion (client GOAWAY) and the request-count bound (design section 4.8). The Nth request is
          // served; the GOAWAY announces no more will be accepted; draining mode refuses stragglers.
          if (draining && registry.liveStreams().isEmpty()) {
            return;
          }
          if (!draining && handledRequests.get() >= configuration.getMaxRequestsPerConnection()) {
            logger.log(Level.DEBUG, "Reached maxRequestsPerConnection [{0}]; draining with GOAWAY(NO_ERROR)",
                configuration.getMaxRequestsPerConnection());
            goAway(HTTP2ErrorCode.NO_ERROR);
            draining = true;
          }
```

Note `goAway` sets `goawaySent`, so a later protocol error on the draining connection will not send a second GOAWAY —
acceptable and already the class's invariant. The `finally` teardown still interrupts handler threads, but it is now
reached only after the roster empties (drain) or on real errors.

`HTTP2HeaderFrameHandler`: add the field + constructor param `BooleanSupplier draining` (update the construction site
in `HTTP2Connection`). In `handleNewStream`, right after the HPACK decode succeeds and BEFORE the `if (!opened)`
check is insufficient — the stream must not be registered at all while draining, so instead insert BEFORE
`stream.open()` a decode-preserving refusal mirroring the cap path:

```java
  public HTTP2Result handleNewStream(HTTP2Stream stream, HTTP2Frame.HeadersFrame f) {
    if (draining.getAsBoolean()) {
      // RFC 9113 section 4.3 requires decoding every header block to keep the HPACK dynamic table synchronized,
      // even for a refused stream.
      try {
        decoder.decode(f.data());
      } catch (IOException e) {
        return new HTTP2Result.ConnectionError(HTTP2ErrorCode.COMPRESSION_ERROR);
      }
      logger.log(Level.DEBUG, "Refusing stream [{0}] - connection is draining", stream.streamId());
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.REFUSED_STREAM);
    }

    boolean opened = stream.open();
    ...rest unchanged...
```

- [ ] **Step 4: Run** — `latte test --test=HTTP2LifecycleTest` → PASS; full H2 suites + h2spec batches → PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: drain in-flight H2 streams on client GOAWAY; enforce maxRequestsPerConnection with graceful GOAWAY"
```

---

### Task 7: Full verification

- [ ] **Step 1:** `latte clean int` (everything, including timeouts groups; performance may be excluded:
  `latte clean int --excludePerformance`) → all PASS.
- [ ] **Step 2:** Re-read `docs/design/2026-07-09-h2-connection-lifecycle-design.md` §8 and confirm each of tests 1–12
  maps to an implemented test (1→Task 5 idle survival; 2→Task 4 H1 gap covers the H2 variant — if no H2 second-request
  gap test exists, add one to `HTTP2LifecycleTest`: request → 3 s idle → request on the same raw connection → 200;
  3→idle expiry; 4→PING transparency; 5→mid-frame trickle (drip-PING-with-open-request variant: add if missing);
  7→GOAWAY drain; 8→Task 1 quiet close; 9→maxRequests; 10→maxAge: add a test with
  `withMaxConnectionAgeDuration(Duration.ofSeconds(1))` + steady PINGs asserting GOAWAY(NO_ERROR) ~1 s; 11→suite
  green; 12→Task 4). Add any missing ones now, same patterns as Task 5.
- [ ] **Step 3:** `git log --oneline main..HEAD` — verify Conventional Commits; then report done (squash-merge is the
  repo's merge policy — leave the merge to the user).

## Self-review notes (already applied)

- SlowWrite (design §4.4 check 2) is exercised indirectly; a dedicated slow-writer wire test (design §8 test 6) needs
  a client that opens a stream, completes the request, and withholds WINDOW_UPDATEs against a response larger than
  65,535 bytes with a low `minimumWriteThroughput` — include it in Task 7 step 2 if time-boxed effort allows; the
  base-class unit behavior is already covered by `ThroughputTest` + the H1 path.
- The `State` enum's `Negotiating` value is deleted with the dispatcher's null-delegate `check()` replacing it; grep
  for `State.Negotiating` before deleting (only `ConnectionDispatcher.state()` references it today).
- `HTTP2Connection` had `state = HTTPConnection.State.Read` at lines 82/186 — both deleted in Task 4.

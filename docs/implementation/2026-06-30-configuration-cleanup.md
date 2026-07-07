# Configuration Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Split server configuration into shared options on `Configurable` plus two protocol sub-configurations (`HTTP1Configuration`, `HTTP2Configuration`) reached through `Consumer` lambdas, and drop HTTP/2 knobs that duplicate shared config or are unwired.

**Architecture:** `HTTPServerConfiguration` holds a default-populated `HTTP1Configuration` and `HTTP2Configuration` as `final` fields, exposed via `getHTTP1Configuration()`/`getHTTP2Configuration()` getters and `withHTTP1(Consumer)`/`withHTTP2(Consumer)` mutators (also surfaced on the `Configurable` interface). The HTTP/2 wire object `HTTP2Settings` stays internal and is derived from `HTTP2Configuration` plus the shared `maxRequestHeaderSize`. `HTTP2RateLimits` moves from an internal record to a public builder class.

**Tech Stack:** Java 21, Latte build tool, TestNG, zero production dependencies.

**Design doc:** `docs/design/2026-06-30-configuration-cleanup-design.md`

## Global Constraints

- Java 21; build with `latte build`, test with `latte test --test=<ClassName>`, full CI build with `latte clean int --excludePerformance --excludeTimeouts`.
- Indent 2 spaces, continuation 4 spaces; target line length 120, do not wrap before 120.
- Acronyms fully uppercase in identifiers (`HTTP2Configuration`, not `Http2Configuration`); lowercase the whole acronym when it leads a field/method name.
- Alphabetize fields and methods within their visibility/kind group; prefer `import module ...` over single-class imports; one blank line between members.
- Error/exception/log messages wrap runtime values in `[brackets]`, never quotes.
- Copyright headers: **new** files (`HTTP1Configuration`, `HTTP2Configuration`) use the MIT `The Latte Project` header. `HTTP2RateLimits` keeps its existing MIT header and `@author Daniel DeGroff`. `HTTPServerConfiguration` and `Configurable` are FusionAuth Apache-2.0 files — **preserve** those headers, do not convert to MIT.
- This is a clean breaking change: no deprecated shims remain in the final state.
- Preserve existing public Javadoc when moving methods.
- Never commit to `main`; we are on branch `http2/configuration`. Conventional Commit messages.

---

### Task 1: `HTTP1Configuration` class

Pure additive class with builder + validation. Nothing consumes it yet, so the build stays green on its own.

**Files:**
- Create: `src/main/java/org/lattejava/http/server/HTTP1Configuration.java`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP1ConfigurationTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `HTTP1Configuration` with getters `getChunkedBufferSize():int`, `getExpectValidator():ExpectValidator`, `getKeepAliveTimeoutDuration():Duration`, `getMaxRequestChunkSize():int`, `getMaxRequestsPerConnection():int`, `getMaxResponseChunkSize():int`, and matching `with*` setters returning `HTTP1Configuration`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/HTTP1ConfigurationTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTP1ConfigurationTest {
  @Test
  public void builder_round_trips() {
    var c = new HTTP1Configuration()
        .withChunkedBufferSize(8 * 1024)
        .withKeepAliveTimeoutDuration(Duration.ofSeconds(30))
        .withMaxRequestChunkSize(2 * 1024 * 1024)
        .withMaxRequestsPerConnection(50_000)
        .withMaxResponseChunkSize(32 * 1024);
    assertEquals(c.getChunkedBufferSize(), 8 * 1024);
    assertEquals(c.getKeepAliveTimeoutDuration(), Duration.ofSeconds(30));
    assertEquals(c.getMaxRequestChunkSize(), 2 * 1024 * 1024);
    assertEquals(c.getMaxRequestsPerConnection(), 50_000);
    assertEquals(c.getMaxResponseChunkSize(), 32 * 1024);
  }

  @Test
  public void defaults_are_populated() {
    var c = new HTTP1Configuration();
    assertEquals(c.getChunkedBufferSize(), 4 * 1024);
    assertEquals(c.getKeepAliveTimeoutDuration(), Duration.ofSeconds(20));
    assertEquals(c.getMaxRequestChunkSize(), 1024 * 1024);
    assertEquals(c.getMaxRequestsPerConnection(), 100_000);
    assertEquals(c.getMaxResponseChunkSize(), 16 * 1024);
    assertNotNull(c.getExpectValidator());
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void rejects_small_max_requests_per_connection() {
    new HTTP1Configuration().withMaxRequestsPerConnection(9);
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void rejects_null_expect_validator() {
    new HTTP1Configuration().withExpectValidator(null);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=HTTP1ConfigurationTest`
Expected: compile failure — `HTTP1Configuration` does not exist.

- [ ] **Step 3: Create the implementation**

Create `src/main/java/org/lattejava/http/server/HTTP1Configuration.java`. Validation strings are copied verbatim from the current `HTTPServerConfiguration` setters (including the existing "grater" spelling) so behavior and any message-asserting tests are preserved:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server;

import module java.base;
import module org.lattejava.http;

/**
 * HTTP/1.x-specific server configuration: chunked transfer-encoding buffers and limits, keep-alive behavior, and the
 * {@code Expect: 100-continue} validator. Instantiated with defaults by {@link HTTPServerConfiguration} and mutated
 * through {@link HTTPServerConfiguration#withHTTP1(java.util.function.Consumer)}.
 */
@SuppressWarnings("UnusedReturnValue")
public class HTTP1Configuration {
  private int chunkedBufferSize = 4 * 1024; // 4 Kilobytes

  private ExpectValidator expectValidator = new AlwaysContinueExpectValidator();

  private Duration keepAliveTimeoutDuration = Duration.ofSeconds(20);

  private int maxRequestChunkSize = 1024 * 1024; // 1 Megabyte

  private int maxRequestsPerConnection = 100_000; // 100,000

  private int maxResponseChunkSize = 16 * 1024; // 16 Kilobytes

  /**
   * @return The buffer size used to decode a request body sent with {@code chunked} transfer-encoding. Defaults to 4
   *     Kilobytes.
   */
  public int getChunkedBufferSize() {
    return chunkedBufferSize;
  }

  /**
   * @return The validator invoked when a client sends {@code Expect: 100-continue}. Never null.
   */
  public ExpectValidator getExpectValidator() {
    return expectValidator;
  }

  /**
   * @return The idle timeout between keep-alive requests. Defaults to 20 seconds.
   */
  public Duration getKeepAliveTimeoutDuration() {
    return keepAliveTimeoutDuration;
  }

  /**
   * @return The maximum size of a single chunk in a {@code chunked} request body. Defaults to 1 Megabyte.
   */
  public int getMaxRequestChunkSize() {
    return maxRequestChunkSize;
  }

  /**
   * @return The maximum number of requests handled on one keep-alive connection. Defaults to 100,000.
   */
  public int getMaxRequestsPerConnection() {
    return maxRequestsPerConnection;
  }

  /**
   * @return The maximum chunk size used when writing a {@code chunked} response. Defaults to 16 Kilobytes.
   */
  public int getMaxResponseChunkSize() {
    return maxResponseChunkSize;
  }

  /**
   * Sets the buffer size used to decode a {@code chunked} request body.
   *
   * @param chunkedBufferSize The buffer size in bytes.
   * @return This.
   */
  public HTTP1Configuration withChunkedBufferSize(int chunkedBufferSize) {
    if (chunkedBufferSize <= 1024) {
      throw new IllegalArgumentException("The chunked buffer size must be greater than or equal to 1024 bytes");
    }

    this.chunkedBufferSize = chunkedBufferSize;
    return this;
  }

  /**
   * Sets the {@code Expect: 100-continue} validator. Must not be null.
   *
   * @param validator The validator.
   * @return This.
   */
  public HTTP1Configuration withExpectValidator(ExpectValidator validator) {
    Objects.requireNonNull(validator, "You cannot set the expect validator to null");
    this.expectValidator = validator;
    return this;
  }

  /**
   * Sets the idle timeout between keep-alive requests.
   *
   * @param duration The duration.
   * @return This.
   */
  public HTTP1Configuration withKeepAliveTimeoutDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the keep-alive timeout duration to null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The keep-alive timeout duration must be grater than 0");
    }

    this.keepAliveTimeoutDuration = duration;
    return this;
  }

  /**
   * Sets the maximum size of a single chunk in a {@code chunked} request body.
   *
   * @param maxRequestChunkSize The maximum per-chunk size in bytes.
   * @return This.
   */
  public HTTP1Configuration withMaxRequestChunkSize(int maxRequestChunkSize) {
    if (maxRequestChunkSize < 1 || maxRequestChunkSize > 0x0FFFFFFF) {
      throw new IllegalArgumentException("The maximum request chunk size must be between 1 and [" + 0x0FFFFFFF + "] (~256 Megabytes).");
    }

    this.maxRequestChunkSize = maxRequestChunkSize;
    return this;
  }

  /**
   * Sets the maximum number of requests handled on one keep-alive connection.
   *
   * @param maxRequestsPerConnection The maximum request count.
   * @return This.
   */
  public HTTP1Configuration withMaxRequestsPerConnection(int maxRequestsPerConnection) {
    if (maxRequestsPerConnection < 10) {
      throw new IllegalArgumentException("The maximum number of requests per connection must be greater than or equal to 10");
    }

    this.maxRequestsPerConnection = maxRequestsPerConnection;
    return this;
  }

  /**
   * Sets the maximum chunk size used when writing a {@code chunked} response.
   *
   * @param maxResponseChunkSize The size in bytes.
   * @return This.
   */
  public HTTP1Configuration withMaxResponseChunkSize(int maxResponseChunkSize) {
    if (maxResponseChunkSize < 128) {
      throw new IllegalArgumentException("The maximum chunk size must be greater than or equal to 128.");
    }

    this.maxResponseChunkSize = maxResponseChunkSize;
    return this;
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `latte test --test=HTTP1ConfigurationTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/HTTP1Configuration.java src/test/java/org/lattejava/http/tests/server/HTTP1ConfigurationTest.java
git commit -m "feat: add HTTP1Configuration sub-config class"
```

---

### Task 2: Migrate HTTP/1 config into `HTTP1Configuration`

Move the six HTTP/1 knobs off `HTTPServerConfiguration`/`Configurable`, repoint the three production consumers, and update all test call sites. The build is red mid-task and verified green at the end.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java`
- Modify: `src/main/java/org/lattejava/http/server/Configurable.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h1/HTTP1Connection.java` (lines 220, 237, 365)
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPBuffers.java` (lines 66, 78)
- Modify: `src/main/java/org/lattejava/http/server/io/HTTPInputStream.java` (lines 75, 76)
- Modify tests: `BaseTest.java`, `CoreTest.java`, `AcceptorDispatchTest.java`, `BaseSocketTest.java`, `FormDataTest.java`, `HeadTest.java`, `MultipartTest.java`

**Interfaces:**
- Consumes: `HTTP1Configuration` (Task 1).
- Produces: `HTTPServerConfiguration.getHTTP1Configuration():HTTP1Configuration`, `HTTPServerConfiguration.withHTTP1(Consumer<HTTP1Configuration>):HTTPServerConfiguration`, and `Configurable.withHTTP1(Consumer<HTTP1Configuration>):T`.

- [ ] **Step 1: Add the sub-config field, getter, and Consumer mutator to `HTTPServerConfiguration`**

Add the field with the other sub-config fields (alphabetical — `http1` sorts before `instrumenter`):

```java
  private final HTTP1Configuration http1 = new HTTP1Configuration();
```

Add the getter (alphabetical among getters):

```java
  /**
   * @return The HTTP/1.x-specific configuration. Never null.
   */
  public HTTP1Configuration getHTTP1Configuration() {
    return http1;
  }
```

Add the `Override` mutator (alphabetical among `with*`):

```java
  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withHTTP1(Consumer<HTTP1Configuration> consumer) {
    Objects.requireNonNull(consumer, "You cannot pass a null HTTP/1 configuration consumer");
    consumer.accept(http1);
    return this;
  }
```

- [ ] **Step 2: Remove the six migrated fields, getters, and setters from `HTTPServerConfiguration`**

Delete these fields: `chunkedBufferSize`, `expectValidator`, `keepAliveTimeoutDuration`, `maxRequestChunkSize`, `maxRequestsPerConnection`, `maxResponseChunkSize`. Delete their getters (`getChunkedBufferSize`, `getExpectValidator`, `getKeepAliveTimeoutDuration`, `getMaxRequestChunkSize`, `getMaxRequestsPerConnection`, `getMaxResponseChunkSize`) and their `with*` setters (`withChunkedBufferSize`, `withExpectValidator`, `withKeepAliveTimeoutDuration`, `withMaxRequestChunkSize`, `withMaxRequestsPerConnection`, `withMaxResponseChunkSize`).

- [ ] **Step 3: Update the `Configurable` interface**

Remove the six default methods `withChunkedBufferSize`, `withExpectValidator`, `withKeepAliveTimeoutDuration`, `withMaxRequestChunkSize`, `withMaxRequestsPerConnection`, `withMaxResponseChunkSize`. Add the new default method (alphabetical position among `with*`):

```java
  /**
   * Configures the HTTP/1.x-specific options.
   *
   * @param consumer A consumer that receives the always-present {@link HTTP1Configuration} to mutate.
   * @return This.
   */
  default T withHTTP1(Consumer<HTTP1Configuration> consumer) {
    configuration().withHTTP1(consumer);
    return (T) this;
  }
```

- [ ] **Step 4: Repoint the three production consumers**

`HTTP1Connection.java`:
- Line 220: `if (handledRequests >= configuration.getMaxRequestsPerConnection()) {` → `if (handledRequests >= configuration.getHTTP1Configuration().getMaxRequestsPerConnection()) {`
- Line 237: `int soTimeout = (int) configuration.getKeepAliveTimeoutDuration().toMillis();` → `int soTimeout = (int) configuration.getHTTP1Configuration().getKeepAliveTimeoutDuration().toMillis();`
- Line 365: `configuration.getExpectValidator().validate(request, expectResponse);` → `configuration.getHTTP1Configuration().getExpectValidator().validate(request, expectResponse);`

`HTTPBuffers.java`:
- Line 66: `chunkOutputStream = new FastByteArrayOutputStream(configuration.getMaxResponseChunkSize() + 64, 64);` → `chunkOutputStream = new FastByteArrayOutputStream(configuration.getHTTP1Configuration().getMaxResponseChunkSize() + 64, 64);`
- Line 78: `chunkBuffer = new byte[configuration.getMaxResponseChunkSize()];` → `chunkBuffer = new byte[configuration.getHTTP1Configuration().getMaxResponseChunkSize()];`
- Also update the two `{@link HTTPServerConfiguration#getMaxResponseChunkSize()}` Javadoc references (lines 61, 74) to `{@link HTTP1Configuration#getMaxResponseChunkSize()}`.

`HTTPInputStream.java`:
- Line 75: `this.chunkedBufferSize = configuration.getChunkedBufferSize();` → `this.chunkedBufferSize = configuration.getHTTP1Configuration().getChunkedBufferSize();`
- Line 76: `this.maxRequestChunkSize = configuration.getMaxRequestChunkSize();` → `this.maxRequestChunkSize = configuration.getHTTP1Configuration().getMaxRequestChunkSize();`

- [ ] **Step 5: Update test call sites**

Find every call: `grep -rn "withChunkedBufferSize\|withMaxRequestChunkSize\|withMaxResponseChunkSize\|withKeepAliveTimeoutDuration\|withMaxRequestsPerConnection\|withExpectValidator" src/test/`

Rewrite each to the `withHTTP1` form. The mechanical rule: a chain `X.withKeepAliveTimeoutDuration(v)` on a configuration/server becomes `X.withHTTP1(h1 -> h1.withKeepAliveTimeoutDuration(v))`; collapse adjacent migrated calls into one lambda. Examples (apply the same shape to each occurrence in `BaseTest` (4), `CoreTest` (5), `AcceptorDispatchTest` (1), `BaseSocketTest` (1), `FormDataTest` (1), `HeadTest` (1), `MultipartTest` (1)):

```java
// before
new HTTPServerConfiguration().withMaxRequestsPerConnection(1)

// after
new HTTPServerConfiguration().withHTTP1(h1 -> h1.withMaxRequestsPerConnection(1))
```

```java
// before — two migrated calls in one chain
.withKeepAliveTimeoutDuration(Duration.ofSeconds(1))
.withMaxResponseChunkSize(256)

// after — collapsed into one lambda
.withHTTP1(h1 -> h1.withKeepAliveTimeoutDuration(Duration.ofSeconds(1))
                   .withMaxResponseChunkSize(256))
```

- [ ] **Step 6: Verify the build and the affected suites are green**

Run: `latte build`
Expected: compiles clean.

Run: `latte test --test=CoreTest` then `latte test --test=FormDataTest` then `latte test --test=MultipartTest` then `latte test --test=HeadTest` then `latte test --test=AcceptorDispatchTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move HTTP/1 config to HTTP1Configuration sub-config"
```

---

### Task 3: `HTTP2RateLimits` record → public builder class

Relocate `HTTP2RateLimits` to the public `org.lattejava.http.server` package as a builder class with `get*`/`with*` accessors, make `HTTP2RateLimitsTracker`'s constructor public, drop `newTracker()`, and update the tracker, `HTTPServerConfiguration`, `HTTP2Connection`, and `HTTP2RateLimitsTest`. `HTTPServerConfiguration.getHTTP2RateLimits()` still exists after this task (removed in Task 4), so the build stays green.

**Files:**
- Delete: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2RateLimits.java`
- Create: `src/main/java/org/lattejava/http/server/HTTP2RateLimits.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2RateLimitsTracker.java`
- Modify: `src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java:104`
- Modify: `src/test/java/org/lattejava/http/tests/server/HTTP2RateLimitsTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: public `org.lattejava.http.server.HTTP2RateLimits` with no-arg default constructor; getters `getEmptyDataMax():int`, `getEmptyDataWindowMs():long`, `getPingMax():int`, `getPingWindowMs():long`, `getRstStreamMax():int`, `getRstStreamWindowMs():long`, `getSettingsMax():int`, `getSettingsWindowMs():long`, `getWindowUpdateMax():int`, `getWindowUpdateWindowMs():long`; matching `with*` setters returning `HTTP2RateLimits`. Public constructor `HTTP2RateLimitsTracker(HTTP2RateLimits config)`.

- [ ] **Step 1: Create the public builder class**

Create `src/main/java/org/lattejava/http/server/HTTP2RateLimits.java` (defaults from `docs/design/2026-05-05-HTTP2.md` §10, identical to the former record's `defaults()`):

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server;

/**
 * Configuration for the five DoS-class HTTP/2 rate limits (RST_STREAM, PING, SETTINGS, empty DATA, WINDOW_UPDATE).
 * Holds per-frame-type thresholds and sliding-window durations. Instantiated with defaults by
 * {@link HTTP2Configuration} and mutated through {@link HTTP2Configuration#withRateLimits(java.util.function.Consumer)}.
 *
 * @author Daniel DeGroff
 */
@SuppressWarnings("UnusedReturnValue")
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

  public int getEmptyDataMax() {
    return emptyDataMax;
  }

  public long getEmptyDataWindowMs() {
    return emptyDataWindowMs;
  }

  public int getPingMax() {
    return pingMax;
  }

  public long getPingWindowMs() {
    return pingWindowMs;
  }

  public int getRstStreamMax() {
    return rstStreamMax;
  }

  public long getRstStreamWindowMs() {
    return rstStreamWindowMs;
  }

  public int getSettingsMax() {
    return settingsMax;
  }

  public long getSettingsWindowMs() {
    return settingsWindowMs;
  }

  public int getWindowUpdateMax() {
    return windowUpdateMax;
  }

  public long getWindowUpdateWindowMs() {
    return windowUpdateWindowMs;
  }

  public HTTP2RateLimits withEmptyDataMax(int emptyDataMax) {
    this.emptyDataMax = emptyDataMax;
    return this;
  }

  public HTTP2RateLimits withEmptyDataWindowMs(long emptyDataWindowMs) {
    this.emptyDataWindowMs = emptyDataWindowMs;
    return this;
  }

  public HTTP2RateLimits withPingMax(int pingMax) {
    this.pingMax = pingMax;
    return this;
  }

  public HTTP2RateLimits withPingWindowMs(long pingWindowMs) {
    this.pingWindowMs = pingWindowMs;
    return this;
  }

  public HTTP2RateLimits withRstStreamMax(int rstStreamMax) {
    this.rstStreamMax = rstStreamMax;
    return this;
  }

  public HTTP2RateLimits withRstStreamWindowMs(long rstStreamWindowMs) {
    this.rstStreamWindowMs = rstStreamWindowMs;
    return this;
  }

  public HTTP2RateLimits withSettingsMax(int settingsMax) {
    this.settingsMax = settingsMax;
    return this;
  }

  public HTTP2RateLimits withSettingsWindowMs(long settingsWindowMs) {
    this.settingsWindowMs = settingsWindowMs;
    return this;
  }

  public HTTP2RateLimits withWindowUpdateMax(int windowUpdateMax) {
    this.windowUpdateMax = windowUpdateMax;
    return this;
  }

  public HTTP2RateLimits withWindowUpdateWindowMs(long windowUpdateWindowMs) {
    this.windowUpdateWindowMs = windowUpdateWindowMs;
    return this;
  }
}
```

Then delete `src/main/java/org/lattejava/http/server/internal/h2/HTTP2RateLimits.java`.

- [ ] **Step 2: Update `HTTP2RateLimitsTracker`**

Make the constructor public, import the relocated type, switch the accessor calls to `get*`, and drop the `newTracker()` reference in the class Javadoc.

- Add `import org.lattejava.http.server.HTTP2RateLimits;` (it is no longer in the same package).
- Change `HTTP2RateLimitsTracker(HTTP2RateLimits config) {` to `public HTTP2RateLimitsTracker(HTTP2RateLimits config) {`.
- Replace the five `record*` bodies' accessor calls:
  - `config.emptyDataMax(), config.emptyDataWindowMs()` → `config.getEmptyDataMax(), config.getEmptyDataWindowMs()`
  - `config.pingMax(), config.pingWindowMs()` → `config.getPingMax(), config.getPingWindowMs()`
  - `config.rstStreamMax(), config.rstStreamWindowMs()` → `config.getRstStreamMax(), config.getRstStreamWindowMs()`
  - `config.settingsMax(), config.settingsWindowMs()` → `config.getSettingsMax(), config.getSettingsWindowMs()`
  - `config.windowUpdateMax(), config.windowUpdateWindowMs()` → `config.getWindowUpdateMax(), config.getWindowUpdateWindowMs()`
- In the class Javadoc, change "Always obtain trackers via {@link HTTP2RateLimits#newTracker()}." to "Each accepted connection constructs its own tracker."

- [ ] **Step 3: Update `HTTPServerConfiguration` field + import**

- Change the import `import org.lattejava.http.server.internal.h2.HTTP2RateLimits;` to remove it (the type is now in this same package — no import needed).
- Change the field initializer `private HTTP2RateLimits http2RateLimits = HTTP2RateLimits.defaults();` to `private HTTP2RateLimits http2RateLimits = new HTTP2RateLimits();`.
- `getHTTP2RateLimits()` stays as-is (returns the field); it is removed in Task 4.

- [ ] **Step 4: Update `HTTP2Connection.java:104`**

Change `this.rateLimits = configuration.getHTTP2RateLimits().newTracker();` to:

```java
    this.rateLimits = new HTTP2RateLimitsTracker(configuration.getHTTP2RateLimits());
```

`HTTP2RateLimitsTracker` is in the same package (`internal.h2`) so no import is needed; `HTTP2RateLimits` resolves via `HTTP2Connection`'s existing imports — add `import org.lattejava.http.server.HTTP2RateLimits;` if the file does not already import it through a module import.

- [ ] **Step 5: Update `HTTP2RateLimitsTest`**

Rewrite to the new API: replace the internal import, `defaults()` → `new HTTP2RateLimits()`, `newTracker()` → `new HTTP2RateLimitsTracker(config)`, and the positional record constructor → builder calls.

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.h2.HTTP2RateLimitsTracker;

import static org.testng.Assert.*;

public class HTTP2RateLimitsTest {
  @Test
  public void newTracker_returns_isolated_counters() {
    // Each HTTP/2 connection must get its own tracker. The configuration is a shared template; constructing a tracker
    // per accept gives the connection an independent ArrayDeque so concurrent connections don't race on the same
    // non-thread-safe collection (and one noisy connection cannot trip the rate limit for everyone else).
    var config = new HTTP2RateLimits();
    var t1 = new HTTP2RateLimitsTracker(config);
    var t2 = new HTTP2RateLimitsTracker(config);

    for (int i = 0; i < 100; i++) {
      t1.recordRstStream();
    }
    assertTrue(t1.recordRstStream(), "t1 should have crossed threshold");
    assertFalse(t2.recordRstStream(), "t2 must not be affected by t1");
  }

  @Test
  public void over_threshold_returns_true() {
    var tracker = new HTTP2RateLimitsTracker(new HTTP2RateLimits());
    for (int i = 0; i < 100; i++) {
      tracker.recordRstStream();
    }
    assertTrue(tracker.recordRstStream(), "the 101st call within window should return true");
  }

  @Test
  public void under_threshold_returns_false() {
    var tracker = new HTTP2RateLimitsTracker(new HTTP2RateLimits());
    for (int i = 0; i < 100; i++) {
      assertFalse(tracker.recordRstStream(), "under-threshold call " + i + " should return false");
    }
  }

  @Test
  public void window_expires_old_events() throws Exception {
    var config = new HTTP2RateLimits().withRstStreamMax(3).withRstStreamWindowMs(100);
    var tracker = new HTTP2RateLimitsTracker(config);
    tracker.recordRstStream();
    tracker.recordRstStream();
    tracker.recordRstStream();
    Thread.sleep(150); // exceed window
    assertFalse(tracker.recordRstStream(), "old events should have expired");
  }
}
```

- [ ] **Step 6: Verify build and tests**

Run: `latte build`
Expected: compiles clean.

Run: `latte test --test=HTTP2RateLimitsTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: convert HTTP2RateLimits to public builder class"
```

---

### Task 4: `HTTP2Configuration`, derive `HTTP2Settings`, drop dead HTTP/2 config

Create `HTTP2Configuration`, derive the wire `HTTP2Settings` from it plus the shared `maxRequestHeaderSize`, wire it into `HTTPServerConfiguration`, remove the nine `withHTTP2*` setters and the five HTTP/2 getters/fields (including the two unwired timeouts and `maxHeaderListSize`), and update `HTTP2Connection` and the HTTP/2 tests.

**Files:**
- Create: `src/main/java/org/lattejava/http/server/HTTP2Configuration.java`
- Test: `src/test/java/org/lattejava/http/tests/server/HTTP2ConfigurationTest.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Settings.java` (add `fromConfiguration`)
- Modify: `src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java`
- Modify: `src/main/java/org/lattejava/http/server/Configurable.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Connection.java` (lines 103, 104, 590)
- Rewrite: `src/test/java/org/lattejava/http/tests/server/HTTPServerConfigurationHTTP2Test.java`
- Modify: `HTTP2BasicTest.java:384`, `HTTP2SecurityTest.java:101`, `HTTP2H2SpecBatch3Test.java:211`

**Interfaces:**
- Consumes: `HTTP2RateLimits` (Task 3).
- Produces: `HTTP2Configuration` with getters `getHandlerReadTimeout():Duration`, `getHeaderTableSize():int`, `getInitialWindowSize():int`, `getMaxConcurrentStreams():int`, `getMaxFrameSize():int`, `getRateLimits():HTTP2RateLimits`; setters `withHandlerReadTimeout`, `withHeaderTableSize`, `withInitialWindowSize`, `withMaxConcurrentStreams`, `withMaxFrameSize`, `withRateLimits(Consumer<HTTP2RateLimits>)`. `HTTP2Settings.fromConfiguration(HTTP2Configuration config, int maxRequestHeaderSize):HTTP2Settings`. `HTTPServerConfiguration.getHTTP2Configuration()`, `withHTTP2(Consumer)`; `Configurable.withHTTP2(Consumer)`.

- [ ] **Step 1: Write the failing test for `HTTP2Configuration`**

Create `src/test/java/org/lattejava/http/tests/server/HTTP2ConfigurationTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTP2ConfigurationTest {
  @Test
  public void builder_round_trips() {
    var c = new HTTP2Configuration()
        .withHandlerReadTimeout(Duration.ofSeconds(2))
        .withHeaderTableSize(8192)
        .withInitialWindowSize(1048576)
        .withMaxConcurrentStreams(50)
        .withMaxFrameSize(32768)
        .withRateLimits(rl -> rl.withPingMax(20));
    assertEquals(c.getHandlerReadTimeout(), Duration.ofSeconds(2));
    assertEquals(c.getHeaderTableSize(), 8192);
    assertEquals(c.getInitialWindowSize(), 1048576);
    assertEquals(c.getMaxConcurrentStreams(), 50);
    assertEquals(c.getMaxFrameSize(), 32768);
    assertEquals(c.getRateLimits().getPingMax(), 20);
  }

  @Test
  public void defaults_match_rfc() {
    var c = new HTTP2Configuration();
    assertEquals(c.getHeaderTableSize(), 4096);
    assertEquals(c.getInitialWindowSize(), 65535);
    assertEquals(c.getMaxConcurrentStreams(), 100);
    assertEquals(c.getMaxFrameSize(), 16384);
    assertEquals(c.getHandlerReadTimeout(), Duration.ofSeconds(10));
    assertNotNull(c.getRateLimits());
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void rejects_out_of_range_max_frame_size() {
    new HTTP2Configuration().withMaxFrameSize(1024);
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=HTTP2ConfigurationTest`
Expected: compile failure — `HTTP2Configuration` does not exist.

- [ ] **Step 3: Create `HTTP2Configuration`**

Create `src/main/java/org/lattejava/http/server/HTTP2Configuration.java`. `maxFrameSize` gains the range validation that the old `withHTTP2MaxFrameSize` Javadoc promised but never enforced:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server;

import module java.base;

/**
 * HTTP/2-specific server configuration: HPACK table size, flow-control window, stream concurrency, frame size, the
 * per-stream handler read timeout, and the frame-flood rate limits. Instantiated with defaults by
 * {@link HTTPServerConfiguration} and mutated through {@link HTTPServerConfiguration#withHTTP2(Consumer)}.
 * <p>
 * The maximum header-list size is not configured here; HTTP/2 derives it from the shared
 * {@link HTTPServerConfiguration#getMaxRequestHeaderSize()}.
 */
@SuppressWarnings("UnusedReturnValue")
public class HTTP2Configuration {
  private final HTTP2RateLimits rateLimits = new HTTP2RateLimits();

  private Duration handlerReadTimeout = Duration.ofSeconds(10);

  private int headerTableSize = 4096;

  private int initialWindowSize = 65535;

  private int maxConcurrentStreams = 100;

  private int maxFrameSize = 16384;

  /**
   * @return The duration the reader waits for a handler to drain a DATA frame from its per-stream pipe before
   *     cancelling the stream with RST_STREAM(CANCEL). Defaults to 10 seconds.
   */
  public Duration getHandlerReadTimeout() {
    return handlerReadTimeout;
  }

  /**
   * @return The HPACK header-table size advertised in the initial SETTINGS frame. Defaults to 4096.
   */
  public int getHeaderTableSize() {
    return headerTableSize;
  }

  /**
   * @return The initial stream-level flow-control window advertised to the client. Defaults to 65535.
   */
  public int getInitialWindowSize() {
    return initialWindowSize;
  }

  /**
   * @return The maximum number of concurrent streams allowed per connection. Defaults to 100.
   */
  public int getMaxConcurrentStreams() {
    return maxConcurrentStreams;
  }

  /**
   * @return The maximum frame payload the server will receive. Defaults to 16384.
   */
  public int getMaxFrameSize() {
    return maxFrameSize;
  }

  /**
   * @return The frame-flood rate limits. Never null.
   */
  public HTTP2RateLimits getRateLimits() {
    return rateLimits;
  }

  /**
   * Sets the per-stream handler read timeout. Cannot be null.
   *
   * @param d The duration.
   * @return This.
   */
  public HTTP2Configuration withHandlerReadTimeout(Duration d) {
    Objects.requireNonNull(d, "You cannot set the HTTP/2 handler read timeout to null");
    this.handlerReadTimeout = d;
    return this;
  }

  /**
   * Sets the HPACK header-table size advertised in the initial SETTINGS frame.
   *
   * @param size The header table size in bytes.
   * @return This.
   */
  public HTTP2Configuration withHeaderTableSize(int size) {
    if (size < 0) {
      throw new IllegalArgumentException("The HTTP/2 header table size must not be negative. Got [" + size + "]");
    }

    this.headerTableSize = size;
    return this;
  }

  /**
   * Sets the initial stream-level flow-control window advertised to the client.
   *
   * @param size The initial window size in bytes.
   * @return This.
   */
  public HTTP2Configuration withInitialWindowSize(int size) {
    if (size < 0) {
      throw new IllegalArgumentException("The HTTP/2 initial window size must not be negative. Got [" + size + "]");
    }

    this.initialWindowSize = size;
    return this;
  }

  /**
   * Sets the maximum number of concurrent streams allowed per connection.
   *
   * @param n The maximum number of concurrent streams.
   * @return This.
   */
  public HTTP2Configuration withMaxConcurrentStreams(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("The HTTP/2 max concurrent streams must not be negative. Got [" + n + "]");
    }

    this.maxConcurrentStreams = n;
    return this;
  }

  /**
   * Sets the maximum frame payload the server will receive. Must be in the range [16384, 16777215] per RFC 9113
   * §6.5.2.
   *
   * @param size The maximum frame size in bytes.
   * @return This.
   */
  public HTTP2Configuration withMaxFrameSize(int size) {
    if (size < 16384 || size > 16777215) {
      throw new IllegalArgumentException("The HTTP/2 max frame size [" + size + "] must be in the range [16384, 16777215].");
    }

    this.maxFrameSize = size;
    return this;
  }

  /**
   * Configures the frame-flood rate limits.
   *
   * @param consumer A consumer that receives the always-present {@link HTTP2RateLimits} to mutate.
   * @return This.
   */
  public HTTP2Configuration withRateLimits(Consumer<HTTP2RateLimits> consumer) {
    Objects.requireNonNull(consumer, "You cannot pass a null HTTP/2 rate limits consumer");
    consumer.accept(rateLimits);
    return this;
  }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `latte test --test=HTTP2ConfigurationTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Add `HTTP2Settings.fromConfiguration`**

In `src/main/java/org/lattejava/http/server/internal/h2/HTTP2Settings.java`, add `import org.lattejava.http.server.HTTP2Configuration;` and a static factory next to `defaults()`. A shared `maxRequestHeaderSize` of `-1` (disabled) maps to `Integer.MAX_VALUE` on the wire:

```java
  /**
   * Builds the server's local settings from its {@link HTTP2Configuration}, deriving SETTINGS_MAX_HEADER_LIST_SIZE
   * from the shared {@code maxRequestHeaderSize} ({@code -1} disabled → {@link Integer#MAX_VALUE}).
   *
   * @param config               The HTTP/2 configuration.
   * @param maxRequestHeaderSize The shared maximum request header size in bytes, or {@code -1} if disabled.
   * @return A fresh local settings instance.
   */
  public static HTTP2Settings fromConfiguration(HTTP2Configuration config, int maxRequestHeaderSize) {
    int maxHeaderListSize = maxRequestHeaderSize == -1 ? Integer.MAX_VALUE : maxRequestHeaderSize;
    return defaults()
        .withHeaderTableSize(config.getHeaderTableSize())
        .withInitialWindowSize(config.getInitialWindowSize())
        .withMaxConcurrentStreams(config.getMaxConcurrentStreams())
        .withMaxFrameSize(config.getMaxFrameSize())
        .withMaxHeaderListSize(maxHeaderListSize);
  }
```

- [ ] **Step 6: Wire `HTTP2Configuration` into `HTTPServerConfiguration` and remove the old HTTP/2 surface**

In `src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java`:

- Remove the import `import org.lattejava.http.server.internal.h2.HTTP2Settings;` (no longer referenced here).
- Remove these fields: `http2HandlerReadTimeout`, `http2KeepAlivePingInterval`, `http2RateLimits`, `http2Settings`, `http2SettingsAckTimeout`.
- Add the field (alphabetical — `http2` sorts after `http1`):

```java
  private final HTTP2Configuration http2 = new HTTP2Configuration();
```

- Remove these getters: `getHTTP2HandlerReadTimeout`, `getHTTP2KeepAlivePingInterval`, `getHTTP2RateLimits`, `getHTTP2Settings`, `getHTTP2SettingsAckTimeout`.
- Add the getter:

```java
  /**
   * @return The HTTP/2-specific configuration. Never null.
   */
  public HTTP2Configuration getHTTP2Configuration() {
    return http2;
  }
```

- Remove these setters: `withHTTP2HandlerReadTimeout`, `withHTTP2HeaderTableSize`, `withHTTP2InitialWindowSize`, `withHTTP2KeepAlivePingInterval`, `withHTTP2MaxConcurrentStreams`, `withHTTP2MaxFrameSize`, `withHTTP2MaxHeaderListSize`, `withHTTP2SettingsAckTimeout`.
- Add the mutator:

```java
  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withHTTP2(Consumer<HTTP2Configuration> consumer) {
    Objects.requireNonNull(consumer, "You cannot pass a null HTTP/2 configuration consumer");
    consumer.accept(http2);
    return this;
  }
```

- [ ] **Step 7: Add `withHTTP2` to the `Configurable` interface**

In `Configurable.java`, add (alphabetical, right after `withHTTP1`):

```java
  /**
   * Configures the HTTP/2-specific options.
   *
   * @param consumer A consumer that receives the always-present {@link HTTP2Configuration} to mutate.
   * @return This.
   */
  default T withHTTP2(Consumer<HTTP2Configuration> consumer) {
    configuration().withHTTP2(consumer);
    return (T) this;
  }
```

- [ ] **Step 8: Repoint `HTTP2Connection`**

- Line 103: `this.localSettings = configuration.getHTTP2Settings();` → `this.localSettings = HTTP2Settings.fromConfiguration(configuration.getHTTP2Configuration(), configuration.getMaxRequestHeaderSize());`
- Line 104: `this.rateLimits = new HTTP2RateLimitsTracker(configuration.getHTTP2RateLimits());` → `this.rateLimits = new HTTP2RateLimitsTracker(configuration.getHTTP2Configuration().getRateLimits());`
- Line 590: `long timeoutMs = configuration.getHTTP2HandlerReadTimeout().toMillis();` → `long timeoutMs = configuration.getHTTP2Configuration().getHandlerReadTimeout().toMillis();`

- [ ] **Step 9: Rewrite `HTTPServerConfigurationHTTP2Test`**

Replace the whole file body's tests (the `maxHeaderListSize`, `settingsAckTimeout`, and `keepAlivePingInterval` knobs no longer exist):

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.h2.HTTP2Settings;

import static org.testng.Assert.*;

public class HTTPServerConfigurationHTTP2Test {
  @Test
  public void defaults_match_rfc() {
    var h2 = new HTTPServerConfiguration().getHTTP2Configuration();
    assertEquals(h2.getHeaderTableSize(), 4096);
    assertEquals(h2.getInitialWindowSize(), 65535);
    assertEquals(h2.getMaxFrameSize(), 16384);
  }

  @Test
  public void derived_settings_use_shared_max_request_header_size() {
    var c = new HTTPServerConfiguration().withMaxRequestHeaderSize(16384);
    var s = HTTP2Settings.fromConfiguration(c.getHTTP2Configuration(), c.getMaxRequestHeaderSize());
    assertEquals(s.maxHeaderListSize(), 16384);
  }

  @Test
  public void disabled_max_request_header_size_maps_to_unlimited() {
    var c = new HTTPServerConfiguration().withMaxRequestHeaderSize(-1);
    var s = HTTP2Settings.fromConfiguration(c.getHTTP2Configuration(), c.getMaxRequestHeaderSize());
    assertEquals(s.maxHeaderListSize(), Integer.MAX_VALUE);
  }

  @Test
  public void with_http2_header_table_size() {
    var c = new HTTPServerConfiguration().withHTTP2(h2 -> h2.withHeaderTableSize(8192));
    assertEquals(c.getHTTP2Configuration().getHeaderTableSize(), 8192);
  }

  @Test
  public void with_http2_initial_window_size() {
    var c = new HTTPServerConfiguration().withHTTP2(h2 -> h2.withInitialWindowSize(1048576));
    assertEquals(c.getHTTP2Configuration().getInitialWindowSize(), 1048576);
  }

  @Test
  public void with_http2_max_concurrent_streams() {
    var c = new HTTPServerConfiguration().withHTTP2(h2 -> h2.withMaxConcurrentStreams(50));
    assertEquals(c.getHTTP2Configuration().getMaxConcurrentStreams(), 50);
  }

  @Test
  public void with_http2_max_frame_size() {
    var c = new HTTPServerConfiguration().withHTTP2(h2 -> h2.withMaxFrameSize(32768));
    assertEquals(c.getHTTP2Configuration().getMaxFrameSize(), 32768);
  }

  @Test
  public void with_http2_rate_limits() {
    var c = new HTTPServerConfiguration().withHTTP2(h2 -> h2.withRateLimits(rl -> rl.withPingMax(20)));
    assertEquals(c.getHTTP2Configuration().getRateLimits().getPingMax(), 20);
  }
}
```

- [ ] **Step 10: Update the three remaining HTTP/2 server-config call sites**

- `HTTP2BasicTest.java:384`: `server.configuration().withHTTP2HandlerReadTimeout(Duration.ofSeconds(2));` → `server.configuration().withHTTP2(h2 -> h2.withHandlerReadTimeout(Duration.ofSeconds(2)));`
- `HTTP2H2SpecBatch3Test.java:211`: `server.configuration().withHTTP2MaxConcurrentStreams(2);` → `server.configuration().withHTTP2(h2 -> h2.withMaxConcurrentStreams(2));`
- `HTTP2SecurityTest.java:101`: `server.configuration().withHTTP2MaxHeaderListSize(2048);` → `server.configuration().withMaxRequestHeaderSize(2048);` (the HTTP/2 header-list cap now derives from the shared header-size limit — same enforcement at `HTTP2Connection` lines 261/543/645).

- [ ] **Step 11: Verify build and HTTP/2 suites**

Run: `latte build`
Expected: compiles clean.

Run: `latte test --test=HTTP2ConfigurationTest` then `latte test --test=HTTPServerConfigurationHTTP2Test` then `latte test --test=HTTP2BasicTest` then `latte test --test=HTTP2SecurityTest` then `latte test --test=HTTP2H2SpecBatch3Test`
Expected: PASS. (`HTTP2SecurityTest` must still reject the oversized header list, now via `maxRequestHeaderSize`.)

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "refactor: move HTTP/2 config to HTTP2Configuration and drop unwired knobs"
```

---

### Task 5: Remove the deprecated `multipartBufferSize` server-level knob

The server-level `withMultipartBufferSize`/`getMultipartBufferSize` on `Configurable`/`HTTPServerConfiguration` are already `@Deprecated` and have no production consumer (the live multipart buffer size lives on `MultipartConfiguration`, which is untouched). Remove them for a clean break.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/Configurable.java`
- Modify: `src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java`

**Interfaces:**
- Consumes: nothing.
- Produces: removal only — `MultipartConfiguration.getMultipartBufferSize()`/`withMultipartBufferSize()` and the `HTTPRequest` deprecated constructor are **unchanged**.

- [ ] **Step 1: Confirm there are no other consumers**

Run: `grep -rn "configuration().getMultipartBufferSize\|\.getMultipartBufferSize()\|withMultipartBufferSize" src/ | grep -v "MultipartConfiguration\|HTTPRequest"`
Expected: only the definitions in `Configurable.java` and `HTTPServerConfiguration.java` (the `HTTPServerConfiguration`-level field, getter, and setter). If anything else appears, stop and reassess.

- [ ] **Step 2: Remove from `HTTPServerConfiguration`**

Delete the `multipartBufferSize` field, the `@Deprecated getMultipartBufferSize()` getter, and the `@Deprecated @SuppressWarnings("deprecation") withMultipartBufferSize(int)` setter.

- [ ] **Step 3: Remove from `Configurable`**

Delete the `@Deprecated default T withMultipartBufferSize(int)` method.

- [ ] **Step 4: Verify build**

Run: `latte build`
Expected: compiles clean.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove deprecated server-level multipartBufferSize knob"
```

---

### Task 6: Full verification and design-doc status

**Files:**
- Modify: `docs/design/2026-06-30-configuration-cleanup-design.md`

- [ ] **Step 1: Full CI build**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Confirm no stale references remain**

Run: `grep -rn "getHTTP2Settings\|getHTTP2RateLimits\|withHTTP2MaxHeaderListSize\|withHTTP2KeepAlivePingInterval\|withHTTP2SettingsAckTimeout\|withHTTP2HandlerReadTimeout\|withHTTP2HeaderTableSize\|withHTTP2InitialWindowSize\|withHTTP2MaxConcurrentStreams\|withHTTP2MaxFrameSize\|getChunkedBufferSize\|getKeepAliveTimeoutDuration\|getExpectValidator\|\.newTracker()\|HTTP2RateLimits.defaults" src/`
Expected: no matches.

- [ ] **Step 3: Flip the design-doc status**

In `docs/design/2026-06-30-configuration-cleanup-design.md`, change `- **Status:** Draft — awaiting review` to `- **Status:** Implemented`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "docs: mark configuration cleanup design as implemented"
```

---

## Self-Review

**Spec coverage:**
- §3.1 Shared options stay put — verified: nothing in Tasks 1–5 touches the shared knobs except the new `withHTTP1`/`withHTTP2` additions.
- §3.2 HTTP/1 options → `HTTP1Configuration` — Tasks 1–2.
- §3.3 HTTP/2 options → `HTTP2Configuration` — Task 4 (`headerTableSize`, `initialWindowSize`, `maxConcurrentStreams`, `maxFrameSize`, `handlerReadTimeout`) and Task 3 (`rateLimits`).
- §3.4 Removed: `maxHeaderListSize` (Task 4 Step 6, derived in Step 5), `http2KeepAlivePingInterval`/`http2SettingsAckTimeout` (Task 4 Step 6), `multipartBufferSize` (Task 5). ✅
- §4.1 narrowed `Configurable` — Tasks 2 & 4 remove HTTP/1 methods and add `withHTTP1`/`withHTTP2`. ✅
- §4.2 `HTTPServerConfiguration` holds final default sub-configs + Consumer mutators — Tasks 2 & 4. ✅
- §4.3 sub-config builder shape — Tasks 1 & 4. ✅
- §4.4 `HTTP2RateLimits` record→builder, tracker built directly — Task 3. ✅
- §4.5 `HTTP2Settings` stays internal, derived via `fromConfiguration`, `enablePush` forced 0 — Task 4 Step 5 (`defaults()` sets `enablePush=0`). ✅
- §5.1 `maxHeaderListSize` ← `maxRequestHeaderSize` with `-1`→`MAX_VALUE` — Task 4 Step 5 + tests in Step 9. ✅
- §8 module-info: `HTTP2RateLimits` moves to the already-exported `org.lattejava.http.server`, so **no `module-info.java` change is needed** (verified: `exports org.lattejava.http.server;` already present). Copyright headers honored per Global Constraints.

**Placeholder scan:** No TBD/TODO; every code step shows complete source or exact before→after edits.

**Type consistency:** Getter/setter names are identical across the class definitions (Tasks 1, 3, 4) and their consumers (Tasks 2, 4 Steps 8/10) and tests. `HTTP2RateLimitsTracker(HTTP2RateLimits)` constructor signature is consistent between Task 3 (definition) and Task 4 Step 8 (use). `HTTP2Settings.fromConfiguration(HTTP2Configuration, int)` is consistent between Task 4 Step 5 (definition) and Step 8 (use).

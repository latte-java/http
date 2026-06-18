# Body-Size Configuration Consolidation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `HTTPServerConfiguration.maxRequestBodySize` the single total-body cap for all request bodies (including multipart) on both HTTP/1.1 and HTTP/2, narrow `MultipartConfiguration` to file-shape concerns (`fileUploadPolicy`, `maxFileSize`, `maxFileCount`), and close two preexisting HTTP/2 gaps (no body-size enforcement; no application of server multipart config).

**Architecture:** Three precursor fixes land first so the consolidation does not regress security. (1) Fix the `Integer.MIN_VALUE` overflow in `HTTPInputStream.read()` that today forces `HTTP2Connection` to pass `-1` (unlimited). (2) Apply the server `MultipartConfiguration` to every HTTP/2 request's `MultipartStreamProcessor` (today h2 multipart uses bare defaults regardless of server config). (3) Wire `maxRequestBodySize` into `HTTP2Connection`'s `HTTPInputStream` construction. Then the additive change: introduce `maxFileCount`. Then the breaking change: remove `MultipartConfiguration.maxRequestSize` and its `MultipartStream.reload()` enforcement, since `HTTPInputStream` now covers the same ground on both transports. Cross-validation at `HTTPServer.start()` rejects `maxFileSize > effective maxRequestBodySize` for `multipart/form-data` so misconfiguration fails loudly rather than silently capping at the smaller value.

**Tech Stack:** Java 21, Latte build tool, TestNG. Zero production dependencies; tests use jackson5, restify, testng.

---

## File Structure

**Modify:**
- `src/main/java/org/lattejava/http/server/io/HTTPInputStream.java` — fix int overflow in `read(byte[], int, int)` (lines 173, 180, 240–242) by using `long` arithmetic for the boundary math.
- `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java` — at the `spawnRequestHandler` site (currently passes `-1` near line 528): apply `configuration.getMultipartConfiguration()` to `request.getMultiPartStreamProcessor()` and resolve `maxRequestBodySize` via `HTTPTools.getMaxRequestBodySize`, then pass that into `HTTPInputStream`.
- `src/main/java/org/lattejava/http/io/MultipartConfiguration.java` — add `maxFileCount` (int, default 20) with setter/getter/Javadoc; update copy ctor, `equals`, `hashCode`; **remove** `maxRequestSize` field, getter, and `withMaxRequestSize` setter.
- `src/main/java/org/lattejava/http/io/MultipartStream.java` — drop the `bytesRead > maxRequestSize` check in `reload()` (lines 367–372); add a `files.size() >= maxFileCount` check in `processPart` (around line 327, after `files.add`).
- `src/main/java/org/lattejava/http/server/HTTPServer.java` — call a new private `validateConfiguration()` method at the top of `start()` (before listener startup) that asserts cross-config invariants.
- `src/main/java/org/lattejava/http/server/HTTPServerConfiguration.java` — no API changes, but a small helper `effectiveMaxRequestBodySizeFor(String contentType)` may be useful for the validator.
- `docs/specs/HTTP2.md` — note that `maxRequestBodySize` and `MultipartConfiguration` now apply to HTTP/2 requests identically to HTTP/1.
- `src/test/java/org/lattejava/http/tests/server/MultipartTest.java` — remove `.withMaxRequestSize(...)` calls (3 sites); reshape `post_server_configuration_requestTooBig` to use `withMaxRequestBodySize`; keep `post_server_configuration_requestTooBig_maxBodySize` as the canonical request-too-big test.

**Create:**
- `src/test/java/org/lattejava/http/tests/server/io/HTTPInputStreamOverflowTest.java` — TestNG test for the int-overflow fix.
- `src/test/java/org/lattejava/http/tests/server/HTTPServerConfigurationValidationTest.java` — TestNG test that `HTTPServer.start()` rejects invalid cross-config (e.g., `maxFileSize > maxRequestBodySize for multipart/form-data`).

**Test-only updates:**
- `src/test/java/org/lattejava/http/tests/io/MultipartStreamTest.java` — add `maxFileCount` enforcement test; remove any `maxRequestSize` references (none today, but verify).

---

## Task 1: Fix `HTTPInputStream` Integer Overflow

**Why first:** `HTTP2Connection.java:528` hard-codes `-1` (unlimited) with a comment explaining that `Integer.MAX_VALUE` triggers `Integer.MIN_VALUE` from the boundary math. We cannot wire `maxRequestBodySize` into the h2 path until this is safe.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/io/HTTPInputStream.java`
- Create: `src/test/java/org/lattejava/http/tests/server/io/HTTPInputStreamOverflowTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/io/HTTPInputStreamOverflowTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server.io;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.server.HTTPRequest;
import org.lattejava.http.server.HTTPServerConfiguration;
import org.lattejava.http.server.io.HTTPInputStream;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;

public class HTTPInputStreamOverflowTest {
  @Test
  public void read_does_not_overflow_at_integer_max_value() throws IOException {
    byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
    HTTPServerConfiguration configuration = new HTTPServerConfiguration();
    HTTPRequest request = new HTTPRequest();
    request.setHeader("Content-Length", String.valueOf(payload.length));

    PushbackInputStream pushback = new PushbackInputStream(new ByteArrayInputStream(payload), 1);
    HTTPInputStream stream = new HTTPInputStream(configuration, request, pushback, Integer.MAX_VALUE);

    byte[] buf = new byte[8];
    int read = stream.read(buf, 0, buf.length);

    assertEquals(read, payload.length);
    assertEquals(new String(buf, 0, read, StandardCharsets.UTF_8), "hello");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=HTTPInputStreamOverflowTest`
Expected: FAIL — either `IndexOutOfBoundsException` from `delegate.read(b, off, Integer.MIN_VALUE)` or a negative return.

- [ ] **Step 3: Fix the overflow with long arithmetic**

Edit `src/main/java/org/lattejava/http/server/io/HTTPInputStream.java`:

Replace the line at 173:

```java
    int maxReadLen = maximumContentLength == -1 ? len : Math.min(len, maximumContentLength - bytesRead + 1);
```

with:

```java
    // Use long arithmetic so a maximumContentLength of Integer.MAX_VALUE does not overflow when added to + 1.
    // We still cap at one byte past the maximum so the streaming check below can trip with a single boundary read.
    int maxReadLen = maximumContentLength == -1
        ? len
        : (int) Math.min((long) len, (long) maximumContentLength - bytesRead + 1L);
```

No other changes — the streaming check at lines 180 and 240 use `int` comparisons that are safe (they compare `bytesRead` to `maximumContentLength` directly).

- [ ] **Step 4: Run the test to verify it passes**

Run: `latte test --test=HTTPInputStreamOverflowTest`
Expected: PASS.

- [ ] **Step 5: Run the rest of the suite to verify no regression**

Run: `latte test --excludePerformance --excludeTimeouts`
Expected: existing tests still pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/server/io/HTTPInputStream.java src/test/java/org/lattejava/http/tests/server/io/HTTPInputStreamOverflowTest.java
git commit -m "fix(http): use long arithmetic in HTTPInputStream boundary math to avoid Integer.MAX_VALUE overflow"
```

---

## Task 2: Apply Server `MultipartConfiguration` on HTTP/2

**Why:** Today HTTP/2 multipart requests use the bare-default `MultipartConfiguration` (file policy = `Reject`, 1 MB file, 10 MB request) regardless of server configuration. `HTTP1Worker.java:120` applies the server config; `HTTP2Connection` does not. Fixing this is also a prerequisite for the consolidation — otherwise removing the multipart `maxRequestSize` field would silently change behavior on h2.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java`
- Test: `src/test/java/org/lattejava/http/tests/server/MultipartTest.java` (existing tests already parametrize on `scheme` via `@DataProvider("schemes")`; they will exercise h2 once wiring is in place)

- [ ] **Step 1: Confirm the failing case**

Run: `latte test --test=MultipartTest`
Expected: existing h2 multipart tests currently pass *only because* the bare-default `Reject` policy happens to align with one test case; tests that configure `Allow` and exercise file uploads should fail or produce wrong results on h2. Document which tests pass/fail before the change. (If `MultipartTest`'s `scheme` data provider does not include `"https"` for h2 yet, that is fine — the next steps add an explicit test.)

- [ ] **Step 2: Write a new failing test in `MultipartTest`**

Add this test method to `src/test/java/org/lattejava/http/tests/server/MultipartTest.java`:

```java
  @Test(dataProvider = "schemes")
  public void post_server_configuration_h2_applies_multipart_config(String scheme) throws Exception {
    // The server's MultipartConfiguration must be applied on both HTTP/1 and HTTP/2.
    withScheme(scheme)
        .withFileCount(2)
        .withFileSize(512)
        .withConfiguration(config -> config.withMultipartConfiguration(
            new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow))
        )
        .expectedFileCount(2)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: application/json\r
            content-length: 16\r
            \r
            {"version":"42"}""")
        .expectNoExceptionOnWrite();
  }
```

- [ ] **Step 3: Run the test to verify it fails on h2**

Run: `latte test --test=MultipartTest`
Expected: passes for HTTP/1 scheme, fails for HTTP/2 scheme with a 422 (Reject) response or empty files list.

- [ ] **Step 4: Apply the multipart config in `HTTP2Connection`**

In `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java`, locate the block in `spawnRequestHandler` (around line 528) where the `HTTPInputStream` is constructed. Immediately before constructing the `HTTPInputStream` (whether the END_STREAM branch or the body branch), add:

```java
    // Apply the server's MultipartConfiguration as a deep copy so the handler may mutate it per-request without
    // affecting the shared server-level config. Matches HTTP1Worker.java behavior.
    request.getMultiPartStreamProcessor().setMultipartConfiguration(new MultipartConfiguration(configuration.getMultipartConfiguration()));
```

Place it directly above the `if ((flags & HTTP2Frame.FLAG_END_STREAM) != 0) { ... } else { ... }` block (lines 517–530) so it applies regardless of whether a body follows. (A bodyless multipart request is malformed, but the cost of always copying is one allocation per request — see existing `HTTP1Worker` precedent.)

- [ ] **Step 5: Run the new test**

Run: `latte test --test=MultipartTest#post_server_configuration_h2_applies_multipart_config`
Expected: PASS for both `http` and `https` schemes.

- [ ] **Step 6: Run the whole multipart suite**

Run: `latte test --test=MultipartTest`
Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java src/test/java/org/lattejava/http/tests/server/MultipartTest.java
git commit -m "fix(http2): apply server MultipartConfiguration to per-request processor"
```

---

## Task 3: Wire `maxRequestBodySize` Into HTTP/2

**Why:** `HTTP2Connection.java:528` passes `-1` (unlimited) to `HTTPInputStream`, so `maxRequestBodySize` is unenforced on HTTP/2. Now that Task 1 removed the overflow constraint, we can pass the real per-content-type cap.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java`
- Test: `src/test/java/org/lattejava/http/tests/server/MultipartTest.java` — `post_server_configuration_requestTooBig_maxBodySize` already exercises this. Confirm it covers h2 via the `schemes` data provider; if not, add a second parametrized test.

- [ ] **Step 1: Write the failing test**

Add this test in `src/test/java/org/lattejava/http/tests/server/MultipartTest.java`:

```java
  @Test(dataProvider = "schemes")
  public void post_server_configuration_h2_enforces_maxRequestBodySize(String scheme) throws Exception {
    // Verify maxRequestBodySize is enforced over HTTP/2, not just HTTP/1.
    withScheme(scheme)
        .withFileSize(1024 * 1024)
        .withFileCount(15)
        .withConfiguration(config -> config.withMultipartConfiguration(
                                               new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow))
                                           .withMaxRequestBodySize(Map.of("*", 3 * 1024 * 1024))
        )
        .expectResponse("""
            HTTP/1.1 413 \r
            connection: close\r
            content-length: 0\r
            \r
            """)
        .assertOptionalExceptionOnWrite(SocketException.class);
  }
```

- [ ] **Step 2: Run the test**

Run: `latte test --test=MultipartTest#post_server_configuration_h2_enforces_maxRequestBodySize`
Expected: PASS for HTTP/1 (already enforced), FAIL for HTTP/2 (cap not wired).

- [ ] **Step 3: Resolve the content-type cap and pass it to `HTTPInputStream`**

In `src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java`, at the `spawnRequestHandler` body-branch (currently passes `-1` near line 529), replace:

```java
      request.setInputStream(new HTTPInputStream(configuration, request,
          new PushbackInputStream(inputStream, instrumenter), -1));
```

with:

```java
      int maximumContentLength = HTTPTools.getMaxRequestBodySize(request.getContentType(), configuration.getMaxRequestBodySize());
      request.setInputStream(new HTTPInputStream(configuration, request,
          new PushbackInputStream(inputStream, instrumenter), maximumContentLength));
```

Also remove the now-obsolete comment about `Integer.MIN_VALUE` overflow (lines 526–527).

- [ ] **Step 4: Run the test**

Run: `latte test --test=MultipartTest#post_server_configuration_h2_enforces_maxRequestBodySize`
Expected: PASS for both schemes.

- [ ] **Step 5: Full multipart and core suite**

Run: `latte test --test=MultipartTest && latte test --test=CoreTest`
Expected: all pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Connection.java src/test/java/org/lattejava/http/tests/server/MultipartTest.java
git commit -m "fix(http2): enforce maxRequestBodySize on HTTP/2 requests"
```

---

## Task 4: Add `maxFileCount` to `MultipartConfiguration`

**Why:** New functionality — bound the number of files in a single multipart upload. Default chosen to be permissive but bounded: **20 files** (rationale: tighter than the previous unlimited; lets typical forms work; one-line override).

**Files:**
- Modify: `src/main/java/org/lattejava/http/io/MultipartConfiguration.java`
- Modify: `src/main/java/org/lattejava/http/io/MultipartStream.java`
- Modify: `src/test/java/org/lattejava/http/tests/io/MultipartStreamTest.java`

- [ ] **Step 1: Write the failing test**

Add this test to `src/test/java/org/lattejava/http/tests/io/MultipartStreamTest.java`:

```java
  @Test
  public void parse_throws_when_maxFileCount_exceeded() throws IOException {
    String boundary = "----WebKitFormBoundaryTWfMVJErBoLURJIe";
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < 3; i++) {
      body.append("--").append(boundary).append("\r\n")
          .append("content-disposition: form-data; name=\"file\"; filename=\"f").append(i).append(".txt\"\r\n")
          .append("content-type: text/plain\r\n\r\n")
          .append("hello").append("\r\n");
    }
    body.append("--").append(boundary).append("--\r\n");

    DefaultMultipartFileManager fileManager = new DefaultMultipartFileManager("latte-http", "test", Path.of(System.getProperty("java.io.tmpdir")));
    MultipartStream stream = new MultipartStream(
        new ByteArrayInputStream(body.toString().getBytes()),
        boundary.getBytes(),
        fileManager,
        new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow).withMaxFileCount(2));

    assertThrows(ContentTooLargeException.class, () -> stream.process(new HashMap<>(), new ArrayList<>()));
  }
```

(Adjust imports as needed; `MultipartStreamTest` likely already imports `assertThrows`, `ContentTooLargeException`, `Path`, `ByteArrayInputStream`, `HashMap`, `ArrayList`.)

- [ ] **Step 2: Run the test**

Run: `latte test --test=MultipartStreamTest#parse_throws_when_maxFileCount_exceeded`
Expected: FAIL — `withMaxFileCount` does not yet exist (compile error).

- [ ] **Step 3: Add the `maxFileCount` field to `MultipartConfiguration`**

In `src/main/java/org/lattejava/http/io/MultipartConfiguration.java`:

a. Add a new instance field (alphabetical placement, after `fileUploadPolicy`):

```java
  private int maxFileCount = 20;
```

b. Add to the copy constructor:

```java
    this.maxFileCount = other.maxFileCount;
```

c. Add to `equals`:

```java
        maxFileCount == that.maxFileCount &&
```

d. Add to `hashCode`:

```java
        maxFileCount,
```

e. Add a getter (alphabetical, after `getFileUploadPolicy`):

```java
  public int getMaxFileCount() {
    return maxFileCount;
  }
```

f. Add a setter (alphabetical, after `withFileUploadPolicy`):

```java
  /**
   * The maximum number of files allowed in a single multipart request.
   *
   * @param maxFileCount the maximum file count. Must be greater than 0, or -1 to disable the limit.
   * @return This.
   */
  public MultipartConfiguration withMaxFileCount(int maxFileCount) {
    if (maxFileCount != -1 && maxFileCount <= 0) {
      throw new IllegalArgumentException("The maximum file count must be greater than 0. Set to [-1] to disable this limitation.");
    }

    this.maxFileCount = maxFileCount;
    return this;
  }
```

- [ ] **Step 4: Enforce `maxFileCount` in `MultipartStream`**

In `src/main/java/org/lattejava/http/io/MultipartStream.java`, in `processPart` immediately after `files.add(processor.toFileInfo())` (around line 328):

```java
        if (isFile) {
          if (multipartConfiguration.getFileUploadPolicy() == MultipartFileUploadPolicy.Allow) {
            files.add(processor.toFileInfo());
            int maxFileCount = multipartConfiguration.getMaxFileCount();
            if (maxFileCount != -1 && files.size() > maxFileCount) {
              throw new ContentTooLargeException(maxFileCount, "The maximum number of files in a multipart stream has been exceeded. The maximum file count is [" + maxFileCount + "].");
            }
          }
        }
```

(Replace the existing `if (isFile) { if (multipartConfiguration.getFileUploadPolicy() == MultipartFileUploadPolicy.Allow) { files.add(processor.toFileInfo()); } }` block.)

- [ ] **Step 5: Run the new test**

Run: `latte test --test=MultipartStreamTest#parse_throws_when_maxFileCount_exceeded`
Expected: PASS.

- [ ] **Step 6: Run the multipart suites**

Run: `latte test --test=MultipartStreamTest && latte test --test=MultipartTest`
Expected: all pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/lattejava/http/io/MultipartConfiguration.java src/main/java/org/lattejava/http/io/MultipartStream.java src/test/java/org/lattejava/http/tests/io/MultipartStreamTest.java
git commit -m "feat(multipart): add maxFileCount limit to MultipartConfiguration"
```

---

## Task 5: Add Cross-Configuration Validation at Server Start

**Why:** Once total-body and per-file caps are independent, a user can configure `maxFileSize > maxRequestBodySize` and the smaller wins silently. The validator makes the misconfiguration fail loudly at `start()`.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPServer.java`
- Create: `src/test/java/org/lattejava/http/tests/server/HTTPServerConfigurationValidationTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/HTTPServerConfigurationValidationTest.java`:

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.io.MultipartConfiguration;
import org.lattejava.http.io.MultipartFileUploadPolicy;
import org.lattejava.http.server.HTTPListenerConfiguration;
import org.lattejava.http.server.HTTPServer;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

public class HTTPServerConfigurationValidationTest {
  @Test
  public void start_throws_when_maxFileSize_exceeds_effective_maxRequestBodySize() {
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("multipart/form-data", 1 * 1024 * 1024))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
            .withMaxFileSize(5 * 1024 * 1024));

    IllegalStateException ex = expectThrows(IllegalStateException.class, server::start);
    // Message should reference both numbers in the error.
    String message = ex.getMessage();
    assert message.contains("maxFileSize") : "Expected message to mention maxFileSize, got: " + message;
    assert message.contains("multipart/form-data") : "Expected message to mention multipart/form-data, got: " + message;
  }

  @Test
  public void start_succeeds_when_maxFileSize_within_effective_maxRequestBodySize() throws Exception {
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("multipart/form-data", 10 * 1024 * 1024))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
            .withMaxFileSize(5 * 1024 * 1024));

    try (HTTPServer ignored = server.start()) {
      // Successful start is all we need.
    }
  }

  @Test
  public void start_uses_wildcard_when_no_exact_multipart_match() {
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("*", 1 * 1024 * 1024))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
            .withMaxFileSize(5 * 1024 * 1024));

    IllegalStateException ex = expectThrows(IllegalStateException.class, server::start);
    assert ex.getMessage().contains("maxFileSize");
  }

  @Test
  public void start_skips_validation_when_maxFileSize_is_unlimited() throws Exception {
    // -1 (unlimited) on maxRequestBodySize means no cross-check needed.
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("*", -1))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
            .withMaxFileSize(5L * 1024 * 1024 * 1024));

    try (HTTPServer ignored = server.start()) {
      // Successful start.
    }
  }

  @Test
  public void start_skips_validation_when_file_uploads_rejected() throws Exception {
    // Reject policy means files are never uploaded; maxFileSize is irrelevant.
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("*", 1 * 1024 * 1024))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Reject)
            .withMaxFileSize(5 * 1024 * 1024));

    try (HTTPServer ignored = server.start()) {
      // Successful start.
    }
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=HTTPServerConfigurationValidationTest`
Expected: FAIL — no validation method exists yet, so `expectThrows` does not see an exception.

- [ ] **Step 3: Add the validator to `HTTPServer.start()`**

In `src/main/java/org/lattejava/http/server/HTTPServer.java`, add a private method (alphabetized within instance methods):

```java
  private void validateConfiguration() {
    MultipartConfiguration multipart = configuration.getMultipartConfiguration();

    // No file uploads → maxFileSize is irrelevant.
    if (multipart.getFileUploadPolicy() != MultipartFileUploadPolicy.Allow) {
      return;
    }

    long maxFileSize = multipart.getMaxFileSize();
    int effectiveCap = HTTPTools.getMaxRequestBodySize("multipart/form-data", configuration.getMaxRequestBodySize());

    // -1 means unlimited.
    if (effectiveCap == -1) {
      return;
    }

    if (maxFileSize > effectiveCap) {
      throw new IllegalStateException("The MultipartConfiguration maxFileSize [" + maxFileSize + "] must not exceed the maxRequestBodySize for [multipart/form-data], which resolves to [" + effectiveCap + "]. Either lower maxFileSize or raise maxRequestBodySize for [multipart/form-data] (or its wildcard parent).");
    }
  }
```

Then call it at the very top of `start()`, before the `context != null` short-circuit:

```java
  public HTTPServer start() {
    if (context != null) {
      return this;
    }

    validateConfiguration();

    // ... rest of method unchanged
```

(Add necessary imports for `MultipartConfiguration`, `MultipartFileUploadPolicy`, and `HTTPTools`.)

- [ ] **Step 4: Run the validation tests**

Run: `latte test --test=HTTPServerConfigurationValidationTest`
Expected: PASS for all five cases.

- [ ] **Step 5: Run the integration suite to confirm no other test trips the validator**

Run: `latte test --excludePerformance --excludeTimeouts`
Expected: all pass. (If `MultipartTest`'s default-config paths trip the validator, address by either lowering their `maxFileSize` or raising `maxRequestBodySize` in the test — but with default `MultipartConfiguration` `maxFileSize` of 1 MB and default `maxRequestBodySize["*"]` of 128 MB, the defaults are fine.)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/server/HTTPServer.java src/test/java/org/lattejava/http/tests/server/HTTPServerConfigurationValidationTest.java
git commit -m "feat(server): validate maxFileSize against effective maxRequestBodySize at start"
```

---

## Task 6: Remove `MultipartConfiguration.maxRequestSize` (Breaking Change)

**Why:** Now that both transports enforce `maxRequestBodySize` in `HTTPInputStream`, the multipart `maxRequestSize` is fully redundant. This is the breaking change that motivates the major version bump.

**Files:**
- Modify: `src/main/java/org/lattejava/http/io/MultipartConfiguration.java`
- Modify: `src/main/java/org/lattejava/http/io/MultipartStream.java`
- Modify: `src/test/java/org/lattejava/http/tests/server/MultipartTest.java`

- [ ] **Step 1: Update existing tests to drop `withMaxRequestSize` (will compile after step 2)**

Edit `src/test/java/org/lattejava/http/tests/server/MultipartTest.java`:

a. In `post_server_configuration_fileTooBig` (lines 97–115), remove the `.withMaxRequestSize(15 * 1024 * 1024)` call. The test still verifies per-file enforcement.

b. In `post_server_configuration_requestTooBig` (lines 177–199), replace the entire `withConfiguration(...)` block with one that uses `withMaxRequestBodySize` instead of multipart's removed `withMaxRequestSize`:

```java
        .withConfiguration(config -> config.withMultipartConfiguration(
                                               new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
                                                                           .withMaxFileSize(2 * 1024 * 1024))
                                           .withMaxRequestBodySize(Map.of("*", 3 * 1024 * 1024))
        )
```

c. In `post_server_configuration_requestTooBig_maxBodySize` (lines 201–227), drop the `.withMaxFileSize(...)` and `.withMaxRequestSize(...)` lines (they were the 2 GB / 5 GB values that demonstrated the multipart config being overridden by the smaller body cap). Keep `withMaxRequestBodySize(Map.of("*", 3 * 1024 * 1024))` — that is now the canonical limit. The multipart config keeps only `withFileUploadPolicy(Allow)`.

- [ ] **Step 2: Remove `maxRequestSize` from `MultipartConfiguration`**

In `src/main/java/org/lattejava/http/io/MultipartConfiguration.java`:

a. Remove the field declaration:

```java
  private long maxRequestSize = 10 * 1024 * 1024; // 10 Megabytes
```

b. Remove from copy constructor:

```java
    this.maxRequestSize = other.maxRequestSize;
```

c. Remove from `equals`:

```java
        maxRequestSize == that.maxRequestSize &&
```

d. Remove from `hashCode`:

```java
        maxRequestSize,
```

e. Remove the getter `getMaxRequestSize()`.

f. Remove the setter `withMaxRequestSize(long)`.

- [ ] **Step 3: Remove enforcement from `MultipartStream.reload()`**

In `src/main/java/org/lattejava/http/io/MultipartStream.java`, in `reload()` (around lines 367–372), remove the `bytesRead`-based request-size check:

```java
      // Keep track of all bytes read for this multipart stream. Fail if the length has been exceeded. ...
      bytesRead += read;
      long maximumRequestSize = multipartConfiguration.getMaxRequestSize();
      if (bytesRead > maximumRequestSize) {
        String detailedMessage = "The maximum request size of multipart stream has been exceeded. The maximum request size is [" + maximumRequestSize + "] bytes.";
        throw new ContentTooLargeException(maximumRequestSize, detailedMessage);
      }
```

The `bytesRead` field itself becomes dead unless it is used for instrumentation elsewhere — verify with `grep -n "bytesRead" src/main/java/org/lattejava/http/io/MultipartStream.java` and remove the field if it has no other reader. (Likely only the increment remains; remove both the field and the increment for cleanliness.)

The comment about the prior `start += end` quadratic bug should be preserved as a security note — relocate it as a block comment above the `start += read; end += read;` block in `reload()` if it still applies, otherwise remove with the field.

- [ ] **Step 4: Compile**

Run: `latte clean build`
Expected: SUCCESS. (If any non-test caller used `withMaxRequestSize`/`getMaxRequestSize`, the compiler will surface it now.)

- [ ] **Step 5: Run the full integration suite**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: all tests pass. The `post_server_configuration_requestTooBig` and `post_server_configuration_requestTooBig_maxBodySize` tests both now exercise `maxRequestBodySize` and produce the same 413 response shape.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/io/MultipartConfiguration.java src/main/java/org/lattejava/http/io/MultipartStream.java src/test/java/org/lattejava/http/tests/server/MultipartTest.java
git commit -m "feat(multipart)!: remove MultipartConfiguration.maxRequestSize (use HTTPServerConfiguration.maxRequestBodySize instead)"
```

---

## Task 7: Update Documentation

**Files:**
- Modify: `docs/specs/HTTP2.md`
- Modify: `src/main/java/org/lattejava/http/server/Configurable.java` (Javadoc on `withMaxRequestBodySize` and `withMultipartConfiguration`)

- [ ] **Step 1: Update `Configurable.withMaxRequestBodySize` Javadoc**

In `src/main/java/org/lattejava/http/server/Configurable.java`, on the `withMaxRequestBodySize` method (around line 200), add a paragraph clarifying that this is the single total-body cap and applies to all content types including `multipart/form-data`:

```
   * <p>
   * This is the single total-body size cap for all request bodies, including multipart uploads. The
   * {@link MultipartConfiguration} bounds per-file size ({@code maxFileSize}) and file count ({@code maxFileCount})
   * within that envelope. At server start, the configuration is rejected if {@code maxFileSize} exceeds the
   * effective {@code maxRequestBodySize} for {@code multipart/form-data}.
```

- [ ] **Step 2: Update `Configurable.withMultipartConfiguration` Javadoc**

In `src/main/java/org/lattejava/http/server/Configurable.java`, on `withMultipartConfiguration` (around line 340), note that total-body enforcement now lives on `maxRequestBodySize`:

```
   * <p>
   * The {@link MultipartConfiguration} controls only file-shape concerns: the upload policy, per-file size
   * limit, and file-count limit. The total multipart request body is bounded by
   * {@link #withMaxRequestBodySize}, which applies uniformly to all content types.
```

- [ ] **Step 3: Update `docs/specs/HTTP2.md`**

Append a new subsection (placement: under the existing "Body handling" or "Security limits" section — pick the closest existing heading). Add this paragraph:

```markdown
**Body-size limits.** `HTTPServerConfiguration.maxRequestBodySize` (a per-Content-Type map with wildcard support) is enforced on HTTP/2 requests via the same `HTTPInputStream` boundary check used on HTTP/1.1. The cap is resolved per request via `HTTPTools.getMaxRequestBodySize` against `request.getContentType()` and passed to the `HTTPInputStream` constructor in `HTTP2Connection.spawnRequestHandler`. The `MultipartConfiguration` (file upload policy, `maxFileSize`, `maxFileCount`) is applied to each request's `MultipartStreamProcessor` immediately before the `HTTPInputStream` is constructed, matching `HTTP1Worker` behavior.
```

- [ ] **Step 4: Verify the build and tests are still green**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add docs/specs/HTTP2.md src/main/java/org/lattejava/http/server/Configurable.java
git commit -m "docs: document maxRequestBodySize as single body cap; multipart config covers file shape"
```

---

## Self-Review Notes

- **Spec coverage check:** Every part of the user's design ("max body size in one place; multipart config = file policy + max file size + max file count; cross-validation") maps to a task. HTTP/2 enforcement gaps (multipart config + body cap) covered in Tasks 2 and 3. Overflow fix unblocking #3 covered in Task 1. Validation covered in Task 5. Removal of the redundant config covered in Task 6. Docs covered in Task 7.
- **Test coverage check:** Each functional change has a paired test. Cross-validation has five test cases (over-limit, within-limit, wildcard fallback, unlimited cap, Reject policy short-circuit). Overflow fix has a unit test that fails before the fix. `maxFileCount` has a unit test. HTTP/2 multipart config + body-cap enforcement each get a parametrized integration test exercising both schemes.
- **API break placement:** The removal (Task 6) is intentionally last, so the precursor fixes (Tasks 1–3) and the additive change (Task 4) and cross-validation (Task 5) all land first as independently-good commits. Each task ends with a green test suite, which makes review and rollback granular.
- **Type consistency:** `maxFileSize` stays `long` (file sizes can exceed 2 GB). `maxFileCount` is `int` (counts will not exceed 2 G). `maxRequestBodySize` stays `Integer` per current API — widening would be a separate scope.
- **Branch behavior:** This branch is pre-PR for HTTP/2 work; the consolidation is part of the same major version bump.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-05-22-body-size-config-consolidation.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?

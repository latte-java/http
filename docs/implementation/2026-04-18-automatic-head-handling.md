# Automatic HEAD Request Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the server automatically handle HTTP HEAD requests by dispatching them through the handler's GET logic while dropping the body bytes, so any GET-aware handler is transparently HEAD-compliant and specialized handlers can optimize via `isHeadRequest()`.

**Architecture:** Capture the original method on `HTTPRequest` before the server rewrites it to `GET`, flag the `HTTPOutputStream` to suppress body output, and wire both in `HTTPWorker`. The `HTTPOutputStream` continues to write the identical preamble a GET would produce (Steps 1 + 2 of `commit()`), then short-circuits the body-delegate wrapping (Steps 3 + 4) when `suppressBody` is true.

**Tech Stack:** Java 25, Latte build tool, TestNG 7.x, JDK `java.net.http.HttpClient`, raw `java.net.Socket` for low-level wire assertions.

**Spec:** [`docs/superpowers/specs/2026-04-18-automatic-head-handling-design.md`](../specs/2026-04-18-automatic-head-handling-design.md)

---

## File Map

**Production source (modify):**
- `src/main/java/org/lattejava/http/server/HTTPRequest.java` — add `originalMethod` field; `setMethod` captures `originalMethod` on first call; add `isHeadRequest()`.
- `src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java` — add `suppressBody` field + setter; short-circuit `commit()` after preamble; make `write(...)` a no-op after commit when suppressed.
- `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java` — after `validatePreamble`, detect HEAD, call `outputStream.setSuppressBody(true)` and `request.setMethod(HTTPMethod.GET)`.

**Test source (modify):**
- `src/test/java/org/lattejava/http/tests/server/BaseSocketTest.java` — `Builder` gains `withHandler(HTTPHandler)`; `assertResponse(...)` uses it when present.

**Test source (create):**
- `src/test/java/org/lattejava/http/tests/server/HeadTest.java` — raw-socket tests that assert byte-exact responses.
- `src/test/java/org/lattejava/http/tests/server/HeadRequestContractTest.java` — JDK `HttpClient` test for the request-side contract.

---

## Conventions

- **Run tests:** `latte test --test=<TestClassName>` from the repo root. This is defined in `CLAUDE.md`.
- **Full build:** `latte clean int --excludePerformance --excludeTimeouts` before the final commit.
- **Commit style:** Short subject, wrapped body explaining the "why". Use the style of recent commits on the branch.
- **Test style:** TestNG (`@Test`), raw sockets for HEAD wire-level tests, JDK `HttpClient` only where wire-level verification isn't needed.
- **Never amend a pushed commit. Prefer new commits.**

---

## Task 1: Add `isHeadRequest()` and capture `originalMethod` on `HTTPRequest`

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPRequest.java:87` (field block), `:456-462` (getter/setter)
- Test: `src/test/java/org/lattejava/http/tests/server/HeadRequestContractTest.java` (create, but only the unit-level piece for now)

We start with the request-side contract because it's pure data with no I/O — perfect place for TDD. The JDK-client integration test for this contract comes in Task 7; here we add a focused TestNG unit test that drives the field additions.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/HeadRequestContractTest.java`:

```java
/*
 * Copyright (c) 2026, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.lattejava.http.tests.server;

import org.lattejava.http.HTTPMethod;
import org.lattejava.http.server.HTTPRequest;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

/**
 * Tests the public contract for automatic HEAD handling on HTTPRequest and via a full HTTP request.
 *
 * @author Brian Pontarelli
 */
public class HeadRequestContractTest extends BaseTest {
  @Test
  public void isHeadRequest_falseByDefault() {
    HTTPRequest request = new HTTPRequest();
    assertFalse(request.isHeadRequest(), "A freshly created HTTPRequest must not report itself as HEAD.");
  }

  @Test
  public void isHeadRequest_trueAfterHEADThenGETRewrite() {
    HTTPRequest request = new HTTPRequest();
    request.setMethod(HTTPMethod.HEAD);
    request.setMethod(HTTPMethod.GET);

    assertTrue(request.isHeadRequest(), "After a HEAD->GET rewrite isHeadRequest() must remain true.");
    assertEquals(request.getMethod(), HTTPMethod.GET, "getMethod() must return the effective method (GET).");
  }

  @Test
  public void isHeadRequest_falseForPlainGET() {
    HTTPRequest request = new HTTPRequest();
    request.setMethod(HTTPMethod.GET);
    assertFalse(request.isHeadRequest(), "A plain GET must never report as HEAD.");
  }

  @Test
  public void isHeadRequest_falseForOtherMethodsRewrittenToGET() {
    HTTPRequest request = new HTTPRequest();
    request.setMethod(HTTPMethod.POST);
    request.setMethod(HTTPMethod.GET);
    assertFalse(request.isHeadRequest(), "A non-HEAD method cannot become HEAD through a later setMethod call.");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=HeadRequestContractTest`
Expected: Compilation failure — `HTTPRequest.isHeadRequest()` is not defined.

- [ ] **Step 3: Add `originalMethod` field and update `setMethod`/add `isHeadRequest`**

Edit `src/main/java/org/lattejava/http/server/HTTPRequest.java`.

Below the existing `private HTTPMethod method;` at line 87, add the `originalMethod` field:

```java
  private HTTPMethod method;

  private HTTPMethod originalMethod;
```

Replace the `setMethod` body (currently lines 460-462):

```java
  public void setMethod(HTTPMethod method) {
    if (this.originalMethod == null) {
      this.originalMethod = method;
    }
    this.method = method;
  }
```

Add a new `isHeadRequest()` method immediately after `setMethod`:

```java
  /**
   * @return True if the original wire method of this request was HEAD, regardless of any later {@link #setMethod(HTTPMethod)} call (e.g., the
   *     server's HEAD-to-GET rewrite). Handlers can use this to short-circuit body generation while still writing correct response headers.
   */
  public boolean isHeadRequest() {
    return originalMethod != null && originalMethod.is(HTTPMethod.HEAD);
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `latte test --test=HeadRequestContractTest`
Expected: All four tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/HTTPRequest.java \
        src/test/java/org/lattejava/http/tests/server/HeadRequestContractTest.java
git commit -m "$(cat <<'EOF'
Capture original method on HTTPRequest for HEAD awareness

Adds a private originalMethod field that is set on the first call to
setMethod and never overwritten afterward. Introduces isHeadRequest()
so handlers can detect HEAD requests even after the server rewrites
the effective method to GET.
EOF
)"
```

---

## Task 2: Add `suppressBody` flag and wire it through `HTTPOutputStream.commit()`

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java`

No test in this task — the behavior is exercised end-to-end by the socket tests in later tasks. We verify manually by compilation + existing test suite staying green.

- [ ] **Step 1: Add the `suppressBody` field + setter**

Edit `src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java`.

Add a new private field next to the existing fields (near `private boolean compress;`):

```java
  private boolean suppressBody;
```

Add a setter below `setCompress(...)`:

```java
  /**
   * Enables or disables body-byte suppression for this response. When enabled, the preamble is still written normally (identical to a GET
   * response), but any subsequent {@link #write} calls become no-ops and the chunked/gzip/deflate delegates are not installed. Intended for
   * HEAD request handling.
   *
   * @param suppressBody true to drop all body output.
   */
  public void setSuppressBody(boolean suppressBody) {
    this.suppressBody = suppressBody;
  }
```

- [ ] **Step 2: Short-circuit `commit()` after the preamble when `suppressBody` is set**

In `commit(boolean closing)`, immediately after the preamble is written (currently line `HTTPTools.writeResponsePreamble(response, delegate);`), add a body-suppression bail-out before the existing `if (closing || twoOhFour) return;` guard:

```java
    // Step 2: Write the preamble. This must be first without any other output stream interference.
    HTTPTools.writeResponsePreamble(response, delegate);

    // HEAD request: preamble matches what GET would send; drop body entirely. No chunked/gzip/deflate wrapping.
    if (suppressBody) {
      return;
    }

    // Step 3: Bail if there is no content.
    if (closing || twoOhFour) {
      return;
    }
```

- [ ] **Step 3: Make `write(...)` a no-op once body suppression is active**

Currently the three `write` overloads call `commit(false)` first and then forward bytes. After suppression is active post-commit, we must not forward or instrument. Change `write(byte[], int, int)` and `write(int)` to:

```java
  @Override
  public void write(byte[] buffer, int offset, int length) throws IOException {
    commit(false);
    if (suppressBody) {
      return;
    }

    delegate.write(buffer, offset, length);

    if (instrumenter != null) {
      instrumenter.wroteToClient(length);
    }
  }

  @Override
  public void write(int b) throws IOException {
    commit(false);
    if (suppressBody) {
      return;
    }

    delegate.write(b);

    if (instrumenter != null) {
      instrumenter.wroteToClient(1);
    }
  }
```

The `write(byte[] b)` overload delegates to `write(byte[], int, int)` so it inherits the behavior — no change needed.

- [ ] **Step 4: Run full test suite to verify nothing regressed**

Run: `latte test --excludePerformance --excludeTimeouts`
Expected: All existing tests pass. No new failures.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java
git commit -m "$(cat <<'EOF'
Add body suppression mode to HTTPOutputStream

Adds a suppressBody flag (set via setSuppressBody) that short-circuits
commit() after the preamble is written and makes subsequent write
calls no-ops. Header logic (Content-Length, Transfer-Encoding,
Content-Encoding, Vary) is untouched, so the preamble is byte-identical
to what a GET would send. Groundwork for automatic HEAD handling.
EOF
)"
```

---

## Task 3: Wire HEAD detection in `HTTPWorker`

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java` (around lines 156-163)

- [ ] **Step 1: Detect HEAD and rewrite to GET after preamble validation**

Open `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java`. Find the block:

```java
        // Ensure the preamble is valid
        Integer status = validatePreamble(request);
        if (status != null) {
          closeSocketOnError(response, status);
          return;
        }

        // Handle the Expect: 100-continue request header.
```

Insert the HEAD-handling block between the closing `}` of the `validatePreamble` check and the `Expect` comment:

```java
        // Ensure the preamble is valid
        Integer status = validatePreamble(request);
        if (status != null) {
          closeSocketOnError(response, status);
          return;
        }

        // Automatic HEAD handling: dispatch through GET logic but suppress body output. The HTTPRequest captured HEAD as the originalMethod on
        // the first setMethod call during preamble parsing, so isHeadRequest() remains true even after this rewrite.
        if (request.getMethod().is(HTTPMethod.HEAD)) {
          outputStream.setSuppressBody(true);
          request.setMethod(HTTPMethod.GET);
        }

        // Handle the Expect: 100-continue request header.
```

Add the `HTTPMethod` import. The existing imports list is alphabetized — insert `org.lattejava.http.HTTPMethod;` in the right slot:

```java
import org.lattejava.http.HTTPMethod;
import org.lattejava.http.HTTPProcessingException;
import org.lattejava.http.HTTPValues;
```

- [ ] **Step 2: Run the full test suite to verify nothing regressed**

Run: `latte test --excludePerformance --excludeTimeouts`
Expected: All existing tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTPWorker.java
git commit -m "$(cat <<'EOF'
Wire automatic HEAD handling in HTTPWorker

After preamble validation, detect HEAD requests: flag the
HTTPOutputStream to suppress body output and rewrite the effective
method to GET. Handlers that branch on getMethod() transparently serve
HEAD; handlers that want to optimize use isHeadRequest().
EOF
)"
```

---

## Task 4: Extend `BaseSocketTest.Builder` with a pluggable handler

**Files:**
- Modify: `src/test/java/org/lattejava/http/tests/server/BaseSocketTest.java`

The existing `Builder` hardcodes a canned handler inside `assertResponse`. HEAD tests need custom handlers. Extend the `Builder` so existing callers still work unchanged.

- [ ] **Step 1: Add `withHandler` and thread it through `assertResponse`**

Open `src/test/java/org/lattejava/http/tests/server/BaseSocketTest.java`. Replace the current contents from the `private void assertResponse(...)` declaration through the end of the `Builder` class. The revised file looks like this (lines 1-32 kept identical, only `assertResponse` signature, the handler selection, and the `Builder` class below change):

```java
  private void assertResponse(String request, String chunkedExtension, int maxRequestHeaderSize, HTTPHandler handler, String response)
      throws Exception {
    HTTPHandler effectiveHandler = handler != null ? handler : (req, res) -> {
      // Read the request body
      req.getInputStream().readAllBytes();
      res.setStatus(200);
    };

    var server = makeServer("http", effectiveHandler)
        .withReadThroughputCalculationDelayDuration(Duration.ofMinutes(2))
        .withWriteThroughputCalculationDelayDuration(Duration.ofMinutes(2))

        // Using various timeouts to make it easier to debug which one we are hitting.
        .withKeepAliveTimeoutDuration(Duration.ofSeconds(23))
        .withInitialReadTimeout(Duration.ofSeconds(19))
        .withProcessingTimeoutDuration(Duration.ofSeconds(27))

        // Default is 8k, reduce this 512 to ensure we overflow this and have to read from the input stream again
        .withRequestBufferSize(512);

    if (maxRequestHeaderSize > 0) {
      server.withMaxRequestHeaderSize(maxRequestHeaderSize);
    }

    try (HTTPServer ignore = server.start();
         Socket socket = makeClientSocket("http")) {

      socket.setSoTimeout((int) Duration.ofSeconds(30).toMillis());

      var bodyString = "These pretzels are making me thirsty. ";
      // Ensure this is larger than the default configured size for the request buffer.
      // - This body is added to each request to ensure we correctly drain the InputStream before we can write the HTTP response.
      // - This should ensure that the body is the length of the (BodyString x 2) larger than the configured request buffer. This ensures
      //   that there are bytes remaining in the InputStream after we have parsed the preamble.
      var requestBufferSize = ignore.configuration().getRequestBufferSize();
      var body = bodyString.repeat(((requestBufferSize / bodyString.length())) * 2);

      if (request.contains("Transfer-Encoding: chunked")) {
        // Chunk in 100 byte increments. Using a smaller chunk size to ensure we don't end up with a single chunk.
        body = new String(chunkEncode(body.getBytes(StandardCharsets.UTF_8), 100, chunkedExtension));
      }

      request = request.replace("{body}", body);
      var contentLength = body.getBytes(StandardCharsets.UTF_8).length;
      request = request.replace("{contentLength}", contentLength + "");

      // Ensure the caller didn't add an extra line return to the request.
      int bodyStart = request.indexOf("\r\n\r\n") + 4;
      String payload = request.substring(bodyStart);
      assertEquals(contentLength, payload.getBytes(StandardCharsets.UTF_8).length, "Check the value you provided for 'withRequest' it looks like you may have a trailing line return or something.\n");

      var os = socket.getOutputStream();
      os.write(request.getBytes(StandardCharsets.UTF_8));

      assertHTTPResponseEquals(socket, response);
    }
  }

  protected class Builder {
    public String chunkedExtension;

    public HTTPHandler handler;

    public int maxRequestHeaderSize = -1;

    public String request;

    public Builder(String request) {
      this.request = request;
    }

    public void expectResponse(String response) throws Exception {
      assertResponse(request, chunkedExtension, maxRequestHeaderSize, handler, response);
    }

    public Builder withChunkedExtension(String extension) {
      chunkedExtension = extension;
      return this;
    }

    public Builder withHandler(HTTPHandler handler) {
      this.handler = handler;
      return this;
    }

    public Builder withMaxRequestHeaderSize(int maxRequestHeaderSize) {
      this.maxRequestHeaderSize = maxRequestHeaderSize;
      return this;
    }
  }
```

- [ ] **Step 2: Run the existing socket tests to verify no regression**

Run: `latte test --test=HTTP11SocketTest`
Expected: All existing tests pass (they still use the canned handler).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/lattejava/http/tests/server/BaseSocketTest.java
git commit -m "$(cat <<'EOF'
Let BaseSocketTest.Builder accept a custom handler

Adds Builder.withHandler(HTTPHandler) and threads it through
assertResponse so tests that need to exercise specific handler
behavior can plug one in. Existing callers that do not call
withHandler fall back to the canned read-and-200 handler.
EOF
)"
```

---

## Task 5: Add the raw-socket `HeadTest` with all body-presence assertions

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/HeadTest.java`

This task is TDD. We write the failing tests, then Tasks 6 and 7 check that everything is green. The feature is already implemented by Tasks 1-3, so these tests should pass the moment the file compiles. The point is to *lock* the behavior with wire-level tests.

Important implementation details for the tests:
- The `BaseSocketTest` request infrastructure uses a `{body}` token and injects a body large enough to force InputStream drain. HEAD requests should not have a body per RFC, so our HEAD tests will send empty-body requests that don't use `{body}`.
- That also means we cannot use `{body}`/`{contentLength}` substitution in HEAD request templates. We hand-write the complete request string.
- Response assertions are byte-exact. The server always emits the headers in order: `connection`, then whatever the handler set (via `addHeader`/`setHeader`), then `content-length` or `transfer-encoding` computed at commit time. Verify this order against an actual GET response if anything looks off while writing the test.

- [ ] **Step 1: Write the test file with all raw-socket HEAD tests**

Create `src/test/java/org/lattejava/http/tests/server/HeadTest.java`:

```java
/*
 * Copyright (c) 2026, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.lattejava.http.tests.server;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.lattejava.http.HTTPValues.Headers;
import org.lattejava.http.HTTPValues.Status;
import org.lattejava.http.server.HTTPHandler;
import org.lattejava.http.server.HTTPServer;
import org.testng.annotations.Test;

/**
 * Tests automatic HEAD request handling at the wire level. Uses raw sockets because the JDK HttpClient will not read body bytes for HEAD
 * responses (per RFC), making it impossible to verify that the server did not write any.
 *
 * @author Brian Pontarelli
 */
public class HeadTest extends BaseSocketTest {
  @Test
  public void head_getOnlyHandler_writesNoBody() throws Exception {
    HTTPHandler handler = (req, res) -> {
      byte[] body = "Hello World".getBytes(StandardCharsets.UTF_8);
      res.setStatus(200);
      res.setContentLength(body.length);
      res.setContentType("text/plain");
      res.getOutputStream().write(body);
    };

    withRequest("""
            HEAD / HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            \r
            """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: text/plain\r
            content-length: 11\r
            \r
            """);
  }

  @Test
  public void head_handlerSetsContentLength_andWritesBody_bodyDropped() throws Exception {
    HTTPHandler handler = (req, res) -> {
      byte[] body = "abcdefgh".getBytes(StandardCharsets.UTF_8);
      res.setStatus(200);
      res.setContentLength(body.length);
      res.getOutputStream().write(body);
    };

    withRequest("""
            HEAD /page HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            \r
            """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-length: 8\r
            \r
            """);
  }

  @Test
  public void head_handlerWritesWithoutContentLength_chunkedHeaderPresent_noChunksOnWire() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.getOutputStream().write("abcdefgh".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
            HEAD / HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            \r
            """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            transfer-encoding: chunked\r
            \r
            """);
  }

  @Test
  public void head_handlerWritesNothing_contentLengthZero() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);

    withRequest("""
            HEAD / HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            \r
            """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-length: 0\r
            \r
            """);
  }

  @Test
  public void head_compressionEnabled_headersPresent_noBody() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setCompress(true);
      res.setContentType("text/plain");
      res.getOutputStream().write("some content to compress".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
            HEAD / HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            Accept-Encoding: gzip\r
            \r
            """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: text/plain\r
            content-encoding: gzip\r
            vary: Accept-Encoding\r
            transfer-encoding: chunked\r
            \r
            """);
  }

  @Test
  public void head_cdnEscapeHatch_contentLengthSetNoBytesWritten() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setContentType("application/octet-stream");
      if (req.isHeadRequest()) {
        res.setContentLength(104857600L); // 100 MiB
        return;
      }
      // Full body generation would go here for GET; not exercised in this test.
    };

    withRequest("""
            HEAD /asset.bin HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            \r
            """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: application/octet-stream\r
            content-length: 104857600\r
            \r
            """);
  }

  @Test
  public void head_statusNoContent_noContentLengthNoTransferEncoding() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(Status.NoContent);

    withRequest("""
            HEAD /empty HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            \r
            """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 204 \r
            connection: keep-alive\r
            \r
            """);
  }

  @Test
  public void head_statusNotModified_noContentLengthNoTransferEncoding() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(Status.NotModified);

    withRequest("""
            HEAD /etag HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            \r
            """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 304 \r
            connection: keep-alive\r
            content-length: 0\r
            \r
            """);
  }

  @Test
  public void head_redirect_locationSent_noBody() throws Exception {
    HTTPHandler handler = (req, res) -> res.sendRedirect("https://example.com/new");

    withRequest("""
            HEAD /old HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            \r
            """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 302 \r
            connection: keep-alive\r
            location: https://example.com/new\r
            content-length: 0\r
            \r
            """);
  }

  @Test
  public void head_thenGet_onSameConnection_bothSucceed() throws Exception {
    HTTPHandler handler = (req, res) -> {
      byte[] body = "Hello".getBytes(StandardCharsets.UTF_8);
      res.setStatus(200);
      res.setContentLength(body.length);
      res.getOutputStream().write(body);
    };

    var server = makeServer("http", handler)
        .withReadThroughputCalculationDelayDuration(Duration.ofMinutes(2))
        .withWriteThroughputCalculationDelayDuration(Duration.ofMinutes(2))
        .withKeepAliveTimeoutDuration(Duration.ofSeconds(23))
        .withInitialReadTimeout(Duration.ofSeconds(19))
        .withProcessingTimeoutDuration(Duration.ofSeconds(27));

    try (HTTPServer ignore = server.start();
         Socket socket = makeClientSocket("http")) {

      socket.setSoTimeout((int) Duration.ofSeconds(30).toMillis());
      OutputStream os = socket.getOutputStream();

      // First: HEAD
      os.write("""
          HEAD / HTTP/1.1\r
          Host: cyberdyne-systems.com\r
          \r
          """.getBytes(StandardCharsets.UTF_8));

      assertHTTPResponseEquals(socket, """
          HTTP/1.1 200 \r
          connection: keep-alive\r
          content-length: 5\r
          \r
          """);

      // Second: GET on the same connection
      os.write("""
          GET / HTTP/1.1\r
          Host: cyberdyne-systems.com\r
          \r
          """.getBytes(StandardCharsets.UTF_8));

      assertHTTPResponseEquals(socket, """
          HTTP/1.1 200 \r
          connection: keep-alive\r
          content-length: 5\r
          \r
          Hello""");
    }
  }
}
```

- [ ] **Step 2: Run the HEAD tests**

Run: `latte test --test=HeadTest`
Expected: All 10 tests pass on the first run because the feature was already implemented in Tasks 1-3.

If any assertion fails on header order, inspect the server's actual output (from the assertion error) and adjust the expected string. Header order is deterministic because `HTTPResponse` uses a `LinkedHashMap` and `HTTPWorker` sets `Connection` first, so the order should be stable; if you have to change it, it is because the test expected the wrong order, not a server bug.

- [ ] **Step 3: Run the full test suite**

Run: `latte test --excludePerformance --excludeTimeouts`
Expected: All tests pass, including the new `HeadTest` and the existing `HTTP11SocketTest`, `CoreTest`, etc.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/org/lattejava/http/tests/server/HeadTest.java
git commit -m "$(cat <<'EOF'
Add raw-socket tests for automatic HEAD handling

Covers GET-only handlers, handler-supplied Content-Length, chunked
fallthrough, empty body, compression parity, the CDN escape hatch via
isHeadRequest(), 204/304, redirects, and keep-alive reuse across
HEAD then GET on the same socket. Uses raw sockets because the JDK
HttpClient does not read body bytes for HEAD responses.
EOF
)"
```

---

## Task 6: Extend `HeadRequestContractTest` with an end-to-end HTTPClient test

**Files:**
- Modify: `src/test/java/org/lattejava/http/tests/server/HeadRequestContractTest.java`

Add an end-to-end test that confirms the worker actually rewrites the method and the handler sees `isHeadRequest() == true` + `getMethod() == GET` when a real HEAD arrives over the wire. This is the piece we deferred from Task 1.

- [ ] **Step 1: Write the failing test**

Open `src/test/java/org/lattejava/http/tests/server/HeadRequestContractTest.java` and append a new test method. First, make sure the class has the imports required below:

```java
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.lattejava.http.server.HTTPHandler;
import org.lattejava.http.server.HTTPServer;
```

Then add the method inside the class:

```java
  @Test
  public void endToEnd_handlerSeesGETMethodButIsHeadRequestTrue() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setHeader("X-Effective-Method", req.getMethod().name());
      res.setHeader("X-Was-Head", Boolean.toString(req.isHeadRequest()));
    };

    try (HTTPServer ignore = makeServer("http", handler).start()) {
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(ClientTimeout)
          .build();

      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create("http://localhost:4242/"))
          .timeout(ClientTimeout)
          .method("HEAD", HttpRequest.BodyPublishers.noBody())
          .build();

      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Effective-Method").orElseThrow(), "GET",
          "Handler must observe getMethod() == GET because the worker rewrote HEAD to GET.");
      assertEquals(response.headers().firstValue("X-Was-Head").orElseThrow(), "true",
          "Handler must observe isHeadRequest() == true because originalMethod captured HEAD.");
    }
  }
```

Note on the URL: `makeServer` binds to port 4242 by default — confirm this by grepping for `makeServer` / `withPort` in `BaseTest.java` before running. If the default differs, use the correct port constant from `BaseTest`.

- [ ] **Step 2: Run the test**

Run: `latte test --test=HeadRequestContractTest`
Expected: All five tests pass (the four unit-level from Task 1 plus this end-to-end test).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/lattejava/http/tests/server/HeadRequestContractTest.java
git commit -m "$(cat <<'EOF'
Verify HEAD-to-GET rewrite end-to-end via HttpClient

Sends an actual HEAD request through the JDK HttpClient and checks
that the handler sees getMethod() == GET while isHeadRequest()
returns true. Round-trips the worker's HEAD detection and method
rewrite.
EOF
)"
```

---

## Task 7: Full integration pass and wrap-up

**Files:** none modified; this task runs the full build and wraps up the branch.

- [ ] **Step 1: Run the full integration build**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: clean compile, all tests pass.

- [ ] **Step 2: Confirm the branch log**

Run: `git log --oneline main..HEAD`
Expected (in chronological order):
```
<sha> Tidy imports and modernize HTTPRequest
<sha> Add design spec for automatic HEAD request handling
<sha> Capture original method on HTTPRequest for HEAD awareness
<sha> Add body suppression mode to HTTPOutputStream
<sha> Wire automatic HEAD handling in HTTPWorker
<sha> Let BaseSocketTest.Builder accept a custom handler
<sha> Add raw-socket tests for automatic HEAD handling
<sha> Verify HEAD-to-GET rewrite end-to-end via HttpClient
```

- [ ] **Step 3: Push to origin (only if user asks)**

Do not push without explicit confirmation. If asked:

```bash
git push -u origin features/head
```

Then stop. A pull request is a separate, explicit step.

---

## Self-Review Notes

- **Spec coverage:** Every semantic rule and file listed in the spec is implemented in Tasks 1-3; every test in the spec's test plan is implemented in Tasks 5-6; `BaseSocketTest` Builder extension is Task 4.
- **Type consistency:** `setSuppressBody(boolean)`, `isHeadRequest()`, `originalMethod`, and `Builder.withHandler(HTTPHandler)` names are used identically across tasks.
- **Placeholders:** None. All code blocks are complete.
- **Open loose ends:** Task 6 Step 1 includes a note to verify the server's default port — this is a verification step, not a placeholder.

# HTTP/2 Response Set-Cookie Emission Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit `HTTPResponse` cookies as `set-cookie` fields in the HTTP/2 response HEADERS frame, and share the h1.1 response-splitting validation choke point with the h2 emission path.

**Architecture:** Extract the validation block from `HTTPTools.writeResponsePreamble` into a new `HTTPTools.validateResponse(HTTPResponse)`, called by both the h1.1 preamble writer and `HTTP2OutputProtocol.commitHeaders`. Then append one `set-cookie` header field per `response.getCookies()` entry when building the h2 HEADERS frame. Design doc: `docs/design/2026-07-08-h2-response-cookies-design.md`.

**Tech Stack:** Java 21+ (Latte build tool, not Maven/Gradle), TestNG, zero production dependencies.

## Global Constraints

- Build/test commands: `latte clean build`, `latte test --test=<ClassName>`. There is no Maven or Gradle.
- Branch: all work happens on the existing branch `fix/h2-response-cookies` (never commit to `main`).
- Commit messages must be Conventional Commits (`<type>: <Description>`); a `commit-msg` hook validates them. End commit bodies with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- License headers: `HTTPTools.java` and `CookieTest.java` carry Apache-2.0/FusionAuth headers — NEVER change or convert them. `HTTP2OutputProtocol.java` and `ResponseSplittingTest.java` carry the MIT `The Latte Project` header — keep as-is. All files already say 2026; no year bumps.
- Indentation: 2 spaces, continuation indent 4. Target line length 120 (do not wrap earlier).
- Runtime values in exception/log messages are wrapped in square brackets: `[value]`.
- Class member ordering is alphabetical within visibility groups (see `.claude/rules/code-conventions.md`); the insertion points given per task already respect this.
- HTTPS tests require this `/etc/hosts` entry (assume present; if a TLS test fails on DNS, report rather than editing `/etc/hosts`): `127.0.0.1 local.lattejava.org`.
- Do not add dependencies, new modules, or new exported packages.

---

### Task 1: Extract `HTTPTools.validateResponse` (shared choke point)

**Files:**
- Modify: `src/main/java/org/lattejava/http/util/HTTPTools.java` (validation block currently inlined at the top of `writeResponsePreamble`, ~lines 612–657)
- Test: `src/test/java/org/lattejava/http/tests/server/ResponseSplittingTest.java`

**Interfaces:**
- Consumes: existing `HTTPTools.validateResponseFieldValue(String, String)`, `validateResponseHeaderName(String)`, `validateResponseCookieAttribute(String, String)`, `HTTPResponse.getStatusMessage()/getHeadersMap()/getCookies()`.
- Produces: `public static void validateResponse(HTTPResponse response)` in `HTTPTools` — throws `IllegalArgumentException` on the first invalid character; Tasks 3 depends on this exact signature.

- [ ] **Step 1: Write the failing tests**

Add these two test methods to `ResponseSplittingTest.java`. Alphabetical placement: after `settersAcceptBadValuesSilently` and before `validatorBenignInputsDoNotThrow`.

```java
@Test
public void validateResponseAcceptsBenignResponse() {
  var response = new HTTPResponse();
  response.setStatus(200);
  response.setStatusMessage("OK");
  response.setHeader("Content-Type", "text/plain");
  response.addCookie(new Cookie("session", "abc123"));

  // Must not throw.
  HTTPTools.validateResponse(response);
}

@Test
public void validateResponseRejectsBadCookieValue() {
  var response = new HTTPResponse();
  response.addCookie(new Cookie("session", "abc\r\nset-cookie: admin=true"));
  try {
    HTTPTools.validateResponse(response);
    fail("Expected IllegalArgumentException for CRLF in cookie value.");
  } catch (IllegalArgumentException expected) {
    assertTrue(expected.getMessage().contains("value of cookie [session]"));
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `latte test --test=ResponseSplittingTest`
Expected: compilation FAILURE — `cannot find symbol: method validateResponse` (the method does not exist yet).

- [ ] **Step 3: Implement the extraction**

In `HTTPTools.java`, add the new method. Alphabetical placement: immediately BEFORE the Javadoc of `validateResponseCookieAttribute` (currently at line ~527). The body is moved verbatim from the top of `writeResponsePreamble`:

```java
/**
 * Validates every attacker-influenceable surface of a response head — status message, header names and values, and
 * cookie names, values, domains, and paths. This is the single choke point for the HTTP response-splitting defense
 * (see docs/design/2026-04-20-audit.md Vuln 4), shared by the HTTP/1.1 preamble writer
 * ({@link #writeResponsePreamble}) and the HTTP/2 HEADERS emission
 * ({@code HTTP2OutputProtocol.commitHeaders}). Validation is concentrated here rather than in the setters so that
 * direct mutation of the internal header map (via {@code getHeadersMap()}) or a Cookie's public fields cannot bypass
 * it. On HTTP/2 the status message is validated even though h2 never emits a reason phrase — one shared method,
 * fail-fast, identical handler-visible behavior across protocols.
 *
 * @param response The response to validate.
 * @throws IllegalArgumentException If any field contains a character that would enable header injection (CR, LF,
 *                                  NUL, and additionally {@code ;} in cookie attributes).
 */
public static void validateResponse(HTTPResponse response) {
  validateResponseFieldValue(response.getStatusMessage(), "status message");
  for (var entry : response.getHeadersMap().entrySet()) {
    String name = entry.getKey();
    validateResponseHeaderName(name);
    for (String value : entry.getValue()) {
      validateResponseFieldValue(value, "value of response header [" + name + "]");
    }
  }

  for (var cookie : response.getCookies()) {
    validateResponseHeaderName(cookie.name);
    validateResponseCookieAttribute(cookie.value, "value of cookie [" + cookie.name + "]");
    validateResponseCookieAttribute(cookie.domain, "domain of cookie [" + cookie.name + "]");
    validateResponseCookieAttribute(cookie.path, "path of cookie [" + cookie.name + "]");
  }
}
```

Then replace the top of `writeResponsePreamble` (everything from the `// Single choke point...` comment through the cookie-validation loop, i.e. the lines ending with `validateResponseCookieAttribute(cookie.path, ...)` and the closing `}` of that loop) with a single call. The method becomes:

```java
public static void writeResponsePreamble(HTTPResponse response, OutputStream outputStream) throws IOException {
  // Response-splitting choke point — validate before any byte is written. If a bad value is found, the IAE
  // propagates to the worker's catch-all, which resets the response and returns a clean 500, rather than flushing a
  // partially-written (and splittable) preamble.
  validateResponse(response);

  writeStatusLine(response, outputStream);
  ...
```

The `writeStatusLine` call and everything after it are unchanged, EXCEPT the cookie write loop near the end: the extracted block owned the `var cookies = response.getCookies();` local, so change the write loop from `for (var cookie : cookies) {` to:

```java
for (var cookie : response.getCookies()) {
```

Do not change `writeResponsePreamble`'s existing Javadoc. Do not touch the Apache license header.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `latte test --test=ResponseSplittingTest`
Expected: PASS — the 2 new tests plus all pre-existing tests (the existing `preambleRejects*` tests exercise the same code through `writeResponsePreamble`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/util/HTTPTools.java src/test/java/org/lattejava/http/tests/server/ResponseSplittingTest.java
git commit -m "refactor: Extract HTTPTools.validateResponse as the shared response-splitting choke point

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Emit `set-cookie` fields in the h2 response HEADERS frame

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2OutputProtocol.java:53-66` (`commitHeaders`)
- Test: `src/test/java/org/lattejava/http/tests/server/CookieTest.java`

**Interfaces:**
- Consumes: `HTTPResponse.getCookies()` (returns `List<Cookie>`), `Cookie.toResponseHeader()` (returns the full `Set-Cookie` value string, e.g. `"response=response-value; SameSite=Lax"`), `HPACKDynamicTable.HeaderField(String, String)`.
- Produces: h2 responses carry one `set-cookie` field per cookie added via `HTTPResponse.addCookie`. No new API.

- [ ] **Step 1: Write the failing tests**

Add these two test methods to `CookieTest.java` (Apache header — do not touch it). Alphabetical placement: `roundTripMultipleHTTP2` goes immediately after `roundTripMultiple`; `roundTripSingleHTTP2` immediately after `roundTripSingle`. They mirror the h1.1 round-trip tests but drive HTTP/2 over TLS-ALPN with the JDK client (same pattern as `HTTP2CompressionTest`).

```java
@Test
public void roundTripMultipleHTTP2() throws Exception {
  HTTPHandler handler = (req, res) -> {
    assertEquals(req.getCookie("request").value, "request-value");

    res.addCookie(new Cookie("response", "response-value").with(c -> c.setSameSite(Cookie.SameSite.Lax)));
    res.addCookie(new Cookie("response-2", "response-value-2").with(c -> c.setMaxAge(42L)));
    res.setStatus(200);
  };

  var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
  var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());
  try (HTTPServer server = makeServer("https", handler, listener).start()) {
    URI uri = URI.create("https://local.lattejava.org:" + server.getActualPort() + "/");
    CookieManager cookieHandler = new CookieManager();
    var sslContext = SecurityTools.clientContext(rootCertificate);
    try (var client = HttpClient.newBuilder().sslContext(sslContext).cookieHandler(cookieHandler).version(HttpClient.Version.HTTP_2).build()) {
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(HTTPValues.Headers.Cookie, "request=request-value").GET().build(),
          HttpResponse.BodyHandlers.discarding());

      assertEquals(response.statusCode(), 200);
      assertEquals(response.version(), HttpClient.Version.HTTP_2);
      assertTrue(response.headers().allValues("set-cookie").contains("response=response-value; SameSite=Lax"));
      assertTrue(response.headers().allValues("set-cookie").contains("response-2=response-value-2; Max-Age=42"));

      List<HttpCookie> cookies = cookieHandler.getCookieStore().get(uri);
      assertEquals(cookies.size(), 2);
      assertEquals(cookies.stream().filter(c -> c.getName().equals("response")).findFirst().orElseThrow().getValue(), "response-value");
      assertEquals(cookies.stream().filter(c -> c.getName().equals("response-2")).findFirst().orElseThrow().getValue(), "response-value-2");
    }
  }
}
```

```java
@Test
public void roundTripSingleHTTP2() throws Exception {
  HTTPHandler handler = (req, res) -> {
    assertEquals(req.getCookie("request").value, "request-value");

    res.addCookie(new Cookie("response", "response-value").with(c -> {
      c.setHttpOnly(true);
      c.setMaxAge(3600L);
      c.setPath("/");
      c.setSameSite(Cookie.SameSite.Lax);
      c.setSecure(true);
    }));
    res.setStatus(200);
  };

  var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
  var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());
  try (HTTPServer server = makeServer("https", handler, listener).start()) {
    URI uri = URI.create("https://local.lattejava.org:" + server.getActualPort() + "/");
    CookieManager cookieHandler = new CookieManager();
    var sslContext = SecurityTools.clientContext(rootCertificate);
    try (var client = HttpClient.newBuilder().sslContext(sslContext).cookieHandler(cookieHandler).version(HttpClient.Version.HTTP_2).build()) {
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(HTTPValues.Headers.Cookie, "request=request-value").GET().build(),
          HttpResponse.BodyHandlers.discarding());

      assertEquals(response.statusCode(), 200);
      assertEquals(response.version(), HttpClient.Version.HTTP_2);
      // Attribute rendering order is fixed by Cookie.toResponseHeader(): Domain, Expires, HttpOnly, Max-Age, Path, SameSite, Secure.
      assertEquals(response.headers().firstValue("set-cookie").orElse(null),
          "response=response-value; HttpOnly; Max-Age=3600; Path=/; SameSite=Lax; Secure");

      List<HttpCookie> cookies = cookieHandler.getCookieStore().get(uri);
      assertEquals(cookies.size(), 1);
      assertEquals(cookies.getFirst().getName(), "response");
      assertEquals(cookies.getFirst().getValue(), "response-value");
    }
  }
}
```

Notes for the implementer:
- `certificate`, `intermediateCertificate`, `keyPair`, `rootCertificate` are protected fields on `BaseTest`. `SecurityTools` comes from the already-present `import module org.lattejava.http;`. `CookieManager`/`HttpCookie` come from `import module java.base;`, and the `HttpClient`/`HttpRequest`/`HttpResponse` types from the already-present `import module java.net.http;`. No new imports are needed.
- `java.security.cert.Certificate` is written fully-qualified because `import module java.base` makes the simple name `Certificate` ambiguous (it collides with the deprecated `java.security.Certificate`). `HTTP2CompressionTest.startServer` uses the same workaround.
- The listener uses port `0` (OS-assigned) + `server.getActualPort()`, not the fixed port 4242 that `makeURI` assumes — hence the inline `URI.create`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `latte test --test=CookieTest`
Expected: the 2 new `*HTTP2` tests FAIL on the `set-cookie` assertions (no `set-cookie` header arrives; `cookies.size()` is 0). All pre-existing CookieTest tests still PASS.

- [ ] **Step 3: Implement the cookie emission**

In `HTTP2OutputProtocol.java` `commitHeaders`, after the `getHeadersMap()` loop (the `for (var entry : ...)` block ending at line 66) and before the `// HPACKEncoder mutates a shared dynamic table...` comment, insert:

```java
    // Cookies are appended after regular headers, mirroring the h1.1 preamble order. HPACKEncoder classifies
    // set-cookie as sensitive and emits it as a never-indexed literal (RFC 7541 §7.1.3).
    for (var cookie : response.getCookies()) {
      respFields.add(new HPACKDynamicTable.HeaderField("set-cookie", cookie.toResponseHeader()));
    }
```

No import changes: `HTTPTools`/`HTTPValues` and friends are covered by the existing `import module org.lattejava.http;`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `latte test --test=CookieTest`
Expected: PASS (all tests, including the 2 new ones).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/h2/HTTP2OutputProtocol.java src/test/java/org/lattejava/http/tests/server/CookieTest.java
git commit -m "fix: Emit Set-Cookie headers in the HTTP/2 response HEADERS frame

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Apply `validateResponse` to h2 HEADERS emission

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/h2/HTTP2OutputProtocol.java` (`commitHeaders`, top of method)
- Test: `src/test/java/org/lattejava/http/tests/server/ResponseSplittingTest.java` (becomes a `BaseHTTP2RawTest` subclass; gains 2 raw-frame wire tests)
- Modify: `docs/design/2026-05-05-HTTP2.md` (Response-splitting defense row, line ~208)

**Interfaces:**
- Consumes: `HTTPTools.validateResponse(HTTPResponse)` from Task 1; `BaseHTTP2RawTest.openH2CConnection(int)`, `writeFrameHeader(OutputStream, int, int, int, int)`; `BaseTest.makeServer(String, HTTPHandler, HTTPListenerConfiguration)`; `HTTPListenerConfiguration(int).withSniffProtocolVersion(true)` for h2c prior knowledge.
- Produces: h2 responses with an invalid header/cookie value are never emitted — the stream is reset with `RST_STREAM(INTERNAL_ERROR)` (error code `0x2`) before any HEADERS frame.

- [ ] **Step 1: Write the failing tests**

The new wire tests must discriminate at the frame level (a strict client like the JDK `HttpClient` may reject a CR/LF header value itself, which would mask whether the server validated). Use the raw h2c harness: before the fix the server's first stream frame is HEADERS (type `0x1`) carrying the injected bytes; after the fix it is RST_STREAM (type `0x3`) with INTERNAL_ERROR (`0x2`).

Make these changes to `ResponseSplittingTest.java`:

1. Change the class declaration from:

```java
@Test
public class ResponseSplittingTest {
```

to:

```java
public class ResponseSplittingTest extends BaseHTTP2RawTest {
```

The class-level `@Test` must be REMOVED: with class-level `@Test`, TestNG treats inherited public helpers from `BaseTest`/`BaseHTTP2RawTest` (e.g. `makeServer`) as test methods. Every existing method already carries a method-level `@Test`, so nothing is lost. Sibling raw-frame suites (`HTTP2RawFrameTest`, etc.) use the same shape.

2. Replace the class Javadoc's first paragraph with one that names the shared choke point:

```java
/**
 * Verifies that {@link HTTPTools#validateResponse} rejects CR, LF, and NUL in every attacker-influenceable response
 * surface, which would otherwise allow HTTP response splitting (see docs/design/2026-04-20-audit.md Vuln 4). The
 * choke point is shared by both protocols: {@link HTTPTools#writeResponsePreamble} calls it before writing the
 * HTTP/1.1 preamble, and {@code HTTP2OutputProtocol.commitHeaders} calls it before HPACK-encoding the response
 * HEADERS frame (RFC 9113 §8.2.1 forbids these octets in field values). Validation is concentrated at the
 * emission choke point rather than the setters so that direct mutation of the internal header map (via
 * {@code getHeadersMap()}) or a Cookie's public fields cannot bypass it.
 *
 * @author Brian Pontarelli
 */
```

3. Add a static field for the request header block (placed before the `badHeaderNames` data provider; copied from `HTTP2RawFrameTest` — that copy is private to its class):

```java
/**
 * Minimal HPACK block for a valid GET / request: {@code 0x82} (:method: GET), {@code 0x84} (:path: /), {@code 0x86}
 * (:scheme: https), then a literal {@code :authority: localhost} (RFC 7541 §6.1/§6.2).
 */
private static final byte[] MINIMAL_HPACK_GET = {
    (byte) 0x82,                          // :method: GET
    (byte) 0x84,                          // :path: /
    (byte) 0x86,                          // :scheme: https
    // :authority: localhost (literal with indexing, name from static table index 1)
    (byte) 0x41, 0x09,
    'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
};
```

4. Add the two wire tests. Alphabetical placement: before `preambleAcceptsBenignResponse` (the first existing instance method, since `h` < `p`):

```java
@Test
public void h2RejectsBadCookieValue() throws Exception {
  HTTPHandler handler = (_, res) -> {
    res.addCookie(new Cookie("session", "abc\r\nset-cookie: admin=true"));
    res.setStatus(200);
  };

  assertH2StreamResetBeforeHeaders(handler);
}

@Test
public void h2RejectsBadHeaderValue() throws Exception {
  HTTPHandler handler = (_, res) -> {
    res.setHeader("X-Bad", "value\r\nInjected: yes");
    res.setStatus(200);
  };

  assertH2StreamResetBeforeHeaders(handler);
}
```

5. Add the shared assertion helper. Placement: private instance methods go after the public ones, so it lands at the end of the class (after `validatorCookieAttributeRejectsSemicolon`):

```java
/**
 * Runs one h2c request against the handler and asserts that the server resets the stream with
 * RST_STREAM(INTERNAL_ERROR) before emitting any response HEADERS frame — i.e. validation fired before anything was
 * HPACK-encoded and nothing splittable reached the wire.
 */
private void assertH2StreamResetBeforeHeaders(HTTPHandler handler) throws Exception {
  var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
  try (HTTPServer server = makeServer("http", handler, listener).start();
       Socket sock = openH2CConnection(server.getActualPort())) {
    var out = sock.getOutputStream();
    writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1); // HEADERS, END_HEADERS | END_STREAM
    out.write(MINIMAL_HPACK_GET);
    out.flush();

    sock.setSoTimeout(5000);
    var in = sock.getInputStream();
    // Drain frames until the first HEADERS (0x1) or RST_STREAM (0x3): whichever arrives first decides the test.
    while (true) {
      int b0 = in.read();
      assertNotEquals(b0, -1, "Connection closed before HEADERS or RST_STREAM arrived");
      byte[] rest = new byte[8];
      assertEquals(in.readNBytes(rest, 0, 8), 8, "EOF while reading a frame header");
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      byte[] payload = in.readNBytes(length);
      if (type == 0x1) {
        fail("Server emitted a response HEADERS frame — the invalid value was not rejected before emission.");
      }
      if (type == 0x3) {
        int code = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
        assertEquals(code, 0x2, "Expected RST_STREAM error code INTERNAL_ERROR (0x2); got: [" + code + "]");
        return;
      }
    }
  }
}
```

No import changes are needed: `Socket` comes from `import module java.base;`, and `HTTPHandler`/`HTTPServer`/`HTTPListenerConfiguration` from `import module org.lattejava.http;` (both already present).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `latte test --test=ResponseSplittingTest`
Expected: `h2RejectsBadCookieValue` and `h2RejectsBadHeaderValue` FAIL with "Server emitted a response HEADERS frame..." (no validation exists on the h2 path yet, so the injected bytes are HPACK-encoded and emitted). All pre-existing tests PASS.

- [ ] **Step 3: Implement the h2 validation call**

In `HTTP2OutputProtocol.java`, at the very top of `commitHeaders` (before the `// Build response HEADERS...` comment), insert:

```java
    // Shared response-splitting choke point with h1.1 (see HTTPTools.validateResponse) — reject CR/LF/NUL before
    // anything is HPACK-encoded. RFC 9113 §8.2.1 forbids these octets in field values, and an unvalidated value
    // could be smuggled through an h2→h1 translating intermediary. The IAE propagates before any frame is enqueued,
    // so HTTP2HandlerDelegate's catch-all resets the stream with RST_STREAM(INTERNAL_ERROR) and nothing splittable
    // reaches the wire.
    HTTPTools.validateResponse(response);
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `latte test --test=ResponseSplittingTest`
Expected: PASS (all tests).

Then run: `latte test --test=CookieTest`
Expected: PASS — proves the benign h2 cookie round-trips from Task 2 survive the new validation.

- [ ] **Step 5: Update the stale design-doc claim**

In `docs/design/2026-05-05-HTTP2.md` (~line 208), replace the row:

```markdown
| Response-splitting defense | ✅ | Reuses choke point at `HTTPResponse.setHeader/addHeader/sendRedirect/Cookie` (audit Vuln 4 fix). Implicit via existing h1.1 defense. |
```

with:

```markdown
| Response-splitting defense | ✅ | Shared choke point `HTTPTools.validateResponse`, called by `writeResponsePreamble` (h1.1) and `HTTP2OutputProtocol.commitHeaders` (h2) before anything reaches the wire. — `ResponseSplittingTest.h2RejectsBadCookieValue` / `h2RejectsBadHeaderValue` |
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/h2/HTTP2OutputProtocol.java src/test/java/org/lattejava/http/tests/server/ResponseSplittingTest.java docs/design/2026-05-05-HTTP2.md
git commit -m "fix: Apply the response-splitting choke point to HTTP/2 HEADERS emission

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Full-suite verification

**Files:**
- None modified — verification only.

**Interfaces:**
- Consumes: everything above.
- Produces: a green CI-equivalent build on `fix/h2-response-cookies`.

- [ ] **Step 1: Run the CI-equivalent build**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: BUILD SUCCESSFUL, zero test failures. This is the repo's CI configuration (skips slow perf/timeout suites).

- [ ] **Step 2: If anything fails**

Do not commit fixes blindly. Diagnose whether the failure is caused by these changes (most likely candidates: a test that asserts h2 responses have exactly N headers, or an existing handler that sets a header value the new h2 validation rejects). Report findings; fix only regressions caused by this work, then re-run.

- [ ] **Step 3: Confirm branch state**

Run: `git log --oneline main..fix/h2-response-cookies`
Expected: the design-doc commit, the plan commit, plus the three task commits — nothing stray. Working tree clean per `git status`.

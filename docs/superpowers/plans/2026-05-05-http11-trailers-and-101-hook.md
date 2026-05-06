# HTTP/1.1 Trailers + 101 Switching Protocols Hook Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add request and response trailer support to HTTP/1.1 (closes existing ❌ items in `HTTP1.1.md` §3) and add a generic `HTTPResponse.switchProtocols(...)` hook for 101 Switching Protocols (prerequisite for h2c-Upgrade in Plan D, also reusable for future WebSockets).

**Architecture:** Trailer state is stored on `HTTPRequest` (populated by `ChunkedInputStream` after the terminator chunk) and on `HTTPResponse` (set by handlers, emitted by `HTTPOutputStream` after the chunked body). `TE: trailers` from the request gates response trailer emission. The 101 hook writes a status line + `Upgrade`/`Connection` headers + any caller-supplied headers, flushes, and invokes a handler with the bare `Socket`. The HTTP/1.1 worker exits its keep-alive loop after the handler returns.

**Tech Stack:** Java 21, TestNG, JDK `HttpClient` for round-trip tests, raw socket for protocol-switch verification.

**Reference spec:** `docs/superpowers/specs/2026-05-05-http2-design.md` §"Trailers API", §"101 Switching Protocols hook"

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `src/main/java/org/lattejava/http/HTTPValues.java` | Modify | Add `ForbiddenTrailers` set; add `TE` and `Trailer` header constants if absent |
| `src/main/java/org/lattejava/http/server/ProtocolSwitchHandler.java` | Create | Public functional interface — receives the `Socket` after 101 |
| `src/main/java/org/lattejava/http/server/HTTPRequest.java` | Modify | Trailer accessors and internal setters; `getTE()` helper for `TE: trailers` lookup |
| `src/main/java/org/lattejava/http/server/HTTPResponse.java` | Modify | Trailer accessors with deny-list enforcement; `switchProtocols(...)` |
| `src/main/java/org/lattejava/http/io/ChunkedInputStream.java` | Modify | Capture trailer text between final `0\r\n` and the terminator; expose parsed map |
| `src/main/java/org/lattejava/http/io/ChunkedOutputStream.java` | Modify | Accept a trailer supplier; emit trailer-fields after the `0\r\n` chunk |
| `src/main/java/org/lattejava/http/server/io/HTTPInputStream.java` | Modify | After EOF, copy trailers from `ChunkedInputStream` onto `HTTPRequest` |
| `src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java` | Modify | When response trailers set: force chunked, auto-set `Trailer:` header, only emit if `TE: trailers` was signaled |
| `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java` | Modify | After `switchProtocols`, exit keep-alive loop |
| `src/main/java/module-info.java` | Modify | (No new exports — `ProtocolSwitchHandler` lives in already-exported `server` package) |
| `src/test/java/org/lattejava/http/tests/server/RequestTrailersTest.java` | Create | Round-trip request trailers via `HttpClient` |
| `src/test/java/org/lattejava/http/tests/server/ResponseTrailersTest.java` | Create | Round-trip response trailers, deny-list, `TE: trailers` gating |
| `src/test/java/org/lattejava/http/tests/server/ProtocolSwitchTest.java` | Create | Raw-socket: server writes 101, handler runs, socket reused |

---

## Task 1: Add `ForbiddenTrailers` constant and `TE`/`Trailer` header names

**Files:**
- Modify: `src/main/java/org/lattejava/http/HTTPValues.java`

- [ ] **Step 1: Verify `TE` and `Trailer` header constants**

Run: `grep -n "\"TE\"\|\"Trailer\"" src/main/java/org/lattejava/http/HTTPValues.java`
If absent in the `Headers` inner class, add them alphabetically.

- [ ] **Step 2: Add `ForbiddenTrailers` constant**

Add a new inner class after `Status` (alphabetical ordering inside `HTTPValues`):

```java
public static final class ForbiddenTrailers {
  /**
   * RFC 9110 §6.5.2 forbids any trailer field that affects message framing, routing, authentication, request modifiers, response control, caching, payload processing, or connection management. Lowercased; lookups must lowercase the candidate name.
   */
  public static final Set<String> Names = Set.of(
      // Framing
      "content-encoding", "content-length", "content-range", "content-type", "transfer-encoding",
      // Routing / pseudo-headers (h2)
      ":authority", ":method", ":path", ":scheme", ":status", "host",
      // Request modifiers
      "cache-control", "expect", "max-forwards", "pragma", "range", "te",
      // Authentication / cookies
      "authorization", "cookie", "proxy-authenticate", "proxy-authorization", "set-cookie", "www-authenticate",
      // Response control
      "age", "date", "expires", "location", "retry-after", "vary", "warning",
      // Connection management
      "connection", "keep-alive", "proxy-connection", "trailer", "upgrade"
  );

  private ForbiddenTrailers() {
  }
}
```

- [ ] **Step 3: Compile**

Run: `latte clean build`
Expected: SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/lattejava/http/HTTPValues.java
git commit -m "Add HTTPValues.ForbiddenTrailers deny-list per RFC 9110 §6.5.2"
```

---

## Task 2: Add `ProtocolSwitchHandler` functional interface

**Files:**
- Create: `src/main/java/org/lattejava/http/server/ProtocolSwitchHandler.java`

- [ ] **Step 1: Create the file**

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.server;

import java.io.IOException;
import java.net.Socket;

/**
 * Invoked by the worker after a successful 101 Switching Protocols response has been written and flushed. The handler
 * owns the underlying socket from this point — the worker will exit its keep-alive loop after the handler returns. h2c
 * Upgrade is the first consumer; future WebSockets work will be the second.
 *
 * @author Daniel DeGroff
 */
@FunctionalInterface
public interface ProtocolSwitchHandler {
  void handle(Socket socket) throws IOException;
}
```

- [ ] **Step 2: Compile**

Run: `latte clean build`
Expected: SUCCESS — `ProtocolSwitchHandler` is exported automatically since `org.lattejava.http.server` is in `module-info.java`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/http/server/ProtocolSwitchHandler.java
git commit -m "Add ProtocolSwitchHandler functional interface for 101 hook"
```

---

## Task 3: Add `getTE()` helper to `HTTPRequest`

`TE: trailers` is a comma-separated token list per RFC 9110 §10.1.4. We expose a small helper that returns whether any token equals `trailers` (case-insensitive). The existing `getHeader` lookup is enough but the helper makes the intent self-documenting at every call site.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPRequest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/HTTPRequestTETest.java`:

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTPRequestTETest extends BaseTest {
  @Test
  public void te_trailers_present() {
    HTTPRequest req = new HTTPRequest();
    req.addHeader("TE", "trailers");
    assertTrue(req.acceptsTrailers());
  }

  @Test
  public void te_trailers_in_token_list() {
    HTTPRequest req = new HTTPRequest();
    req.addHeader("TE", "deflate, trailers");
    assertTrue(req.acceptsTrailers());
  }

  @Test
  public void te_absent() {
    HTTPRequest req = new HTTPRequest();
    assertFalse(req.acceptsTrailers());
  }

  @Test
  public void te_other_token_only() {
    HTTPRequest req = new HTTPRequest();
    req.addHeader("TE", "deflate");
    assertFalse(req.acceptsTrailers());
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HTTPRequestTETest`
Expected: COMPILATION FAILURE — `acceptsTrailers()` doesn't exist.

- [ ] **Step 3: Add the method to `HTTPRequest`**

Add alphabetically among the public methods (after `acceptsCompression` if it exists, otherwise where it sorts):

```java
/**
 * @return true if the client signaled `TE: trailers` per RFC 9110 §10.1.4 — trailer fields will be honored on the response.
 */
public boolean acceptsTrailers() {
  String te = getHeader(HTTPValues.Headers.TE);
  if (te == null) {
    return false;
  }

  for (String token : te.split(",")) {
    if (token.trim().equalsIgnoreCase("trailers")) {
      return true;
    }
  }

  return false;
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HTTPRequestTETest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/HTTPRequest.java src/test/java/org/lattejava/http/tests/server/HTTPRequestTETest.java
git commit -m "Add HTTPRequest.acceptsTrailers() helper for TE: trailers token-list"
```

---

## Task 4: Add request-side trailer accessors to `HTTPRequest`

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPRequest.java`

- [ ] **Step 1: Write the failing test**

Add to `HTTPRequestTETest.java` (rename to `HTTPRequestTrailersTest` or add a new file — your choice; here we add a new file):

Create `src/test/java/org/lattejava/http/tests/server/HTTPRequestTrailersAPITest.java`:

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTPRequestTrailersAPITest extends BaseTest {
  @Test
  public void no_trailers_initially() {
    HTTPRequest req = new HTTPRequest();
    assertFalse(req.hasTrailers());
    assertNull(req.getTrailer("X-Anything"));
    assertEquals(req.getTrailers("X-Anything"), List.of());
    assertTrue(req.getTrailerMap().isEmpty());
  }

  @Test
  public void trailer_added_visible() {
    HTTPRequest req = new HTTPRequest();
    req.addTrailer("X-Checksum", "abc123");
    assertTrue(req.hasTrailers());
    assertEquals(req.getTrailer("X-Checksum"), "abc123");
    assertEquals(req.getTrailers("x-checksum"), List.of("abc123")); // case-insensitive
  }

  @Test
  public void multiple_values_for_same_trailer() {
    HTTPRequest req = new HTTPRequest();
    req.addTrailer("X-Stat", "1");
    req.addTrailer("X-Stat", "2");
    assertEquals(req.getTrailers("X-Stat"), List.of("1", "2"));
    assertEquals(req.getTrailer("X-Stat"), "1"); // first wins for getTrailer
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HTTPRequestTrailersAPITest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Add the trailer field and methods**

In `HTTPRequest.java`, alphabetical with other private fields:

```java
private Map<String, List<String>> trailers;
```

(Lazy init avoids allocation when the request has no trailers — matches the GC-reduction direction.)

Add public methods (alphabetized with the rest of the public surface):

```java
public void addTrailer(String name, String value) {
  if (trailers == null) {
    trailers = new HashMap<>();
  }
  trailers.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(value);
}

public String getTrailer(String name) {
  if (trailers == null) {
    return null;
  }
  List<String> values = trailers.get(name.toLowerCase());
  return (values == null || values.isEmpty()) ? null : values.getFirst();
}

public List<String> getTrailers(String name) {
  if (trailers == null) {
    return List.of();
  }
  return trailers.getOrDefault(name.toLowerCase(), List.of());
}

public Map<String, List<String>> getTrailerMap() {
  return trailers == null ? Map.of() : trailers;
}

public boolean hasTrailers() {
  return trailers != null && !trailers.isEmpty();
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HTTPRequestTrailersAPITest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/HTTPRequest.java src/test/java/org/lattejava/http/tests/server/HTTPRequestTrailersAPITest.java
git commit -m "Add HTTPRequest trailer accessors (lazy-init, case-insensitive)"
```

---

## Task 5: Add response-side trailer accessors to `HTTPResponse` with deny-list enforcement

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPResponse.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/HTTPResponseTrailersAPITest.java`:

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTPResponseTrailersAPITest extends BaseTest {
  @Test
  public void no_trailers_initially() {
    HTTPResponse res = new HTTPResponse();
    assertTrue(res.getTrailers().isEmpty());
  }

  @Test
  public void set_then_get() {
    HTTPResponse res = new HTTPResponse();
    res.setTrailer("X-Checksum", "abc");
    assertEquals(res.getTrailers().get("x-checksum"), List.of("abc"));
  }

  @Test
  public void add_appends() {
    HTTPResponse res = new HTTPResponse();
    res.addTrailer("X-Stat", "1");
    res.addTrailer("X-Stat", "2");
    assertEquals(res.getTrailers().get("x-stat"), List.of("1", "2"));
  }

  @Test
  public void set_replaces() {
    HTTPResponse res = new HTTPResponse();
    res.addTrailer("X-Stat", "1");
    res.setTrailer("X-Stat", "2");
    assertEquals(res.getTrailers().get("x-stat"), List.of("2"));
  }

  @DataProvider
  public Object[][] forbiddenNames() {
    return new Object[][]{
        {"Content-Length"},
        {"Transfer-Encoding"},
        {"Host"},
        {"Authorization"},
        {"Set-Cookie"},
        {"Trailer"},
        {"TE"}
    };
  }

  @Test(dataProvider = "forbiddenNames")
  public void forbidden_name_throws(String name) {
    HTTPResponse res = new HTTPResponse();
    expectThrows(IllegalArgumentException.class, () -> res.setTrailer(name, "x"));
    expectThrows(IllegalArgumentException.class, () -> res.addTrailer(name, "x"));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HTTPResponseTrailersAPITest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Add the trailer field and methods to `HTTPResponse`**

Field (alphabetical with other private fields):
```java
private Map<String, List<String>> trailers;
```

Public methods (alphabetized):
```java
public void addTrailer(String name, String value) {
  rejectIfForbiddenTrailer(name);
  if (trailers == null) {
    trailers = new HashMap<>();
  }
  trailers.computeIfAbsent(name.toLowerCase(), k -> new ArrayList<>()).add(value);
}

public Map<String, List<String>> getTrailers() {
  return trailers == null ? Map.of() : trailers;
}

public void setTrailer(String name, String value) {
  rejectIfForbiddenTrailer(name);
  if (trailers == null) {
    trailers = new HashMap<>();
  }
  List<String> list = new ArrayList<>(1);
  list.add(value);
  trailers.put(name.toLowerCase(), list);
}

private void rejectIfForbiddenTrailer(String name) {
  if (HTTPValues.ForbiddenTrailers.Names.contains(name.toLowerCase())) {
    throw new IllegalArgumentException("Header name [" + name + "] is forbidden as a trailer per RFC 9110 §6.5.2");
  }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HTTPResponseTrailersAPITest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/HTTPResponse.java src/test/java/org/lattejava/http/tests/server/HTTPResponseTrailersAPITest.java
git commit -m "Add HTTPResponse trailer accessors with RFC 9110 deny-list"
```

---

## Task 6: Capture trailer text in `ChunkedInputStream` and expose parsed map

After the `0\r\n` chunk-size line, RFC 9112 §7.1.2 says trailer-fields are formatted exactly like request headers — `name: value\r\n` lines until a bare `\r\n`. Rather than instrument the existing `Trailer`/`TrailerCR`/`TrailerLF` state machine to capture bytes as they fly past (fragile under future state-machine refactors), drain the trailer section directly via a small line-based reader once the zero-length chunk is observed. The state machine retains its job of bounding the chunked body; trailer parsing becomes a separate, isolated routine.

**Files:**
- Modify: `src/main/java/org/lattejava/http/io/ChunkedInputStream.java`

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/org/lattejava/http/tests/io/ChunkedInputStreamTrailersTest.java`:

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.tests.io;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class ChunkedInputStreamTrailersTest {
  @Test
  public void trailer_after_zero_chunk_captured() throws Exception {
    String wire = "5\r\nhello\r\n0\r\nX-Checksum: abc123\r\nX-Other: 42\r\n\r\n";
    var pushback = new PushbackInputStream(new ByteArrayInputStream(wire.getBytes()), null);
    var chunked = new ChunkedInputStream(pushback, 1024, 1_000_000);

    byte[] body = chunked.readAllBytes();
    assertEquals(new String(body), "hello");

    Map<String, List<String>> trailers = chunked.getTrailers();
    assertEquals(trailers.get("x-checksum"), List.of("abc123"));
    assertEquals(trailers.get("x-other"), List.of("42"));
  }

  @Test
  public void no_trailers_returns_empty_map() throws Exception {
    String wire = "5\r\nhello\r\n0\r\n\r\n";
    var pushback = new PushbackInputStream(new ByteArrayInputStream(wire.getBytes()), null);
    var chunked = new ChunkedInputStream(pushback, 1024, 1_000_000);

    chunked.readAllBytes();
    assertTrue(chunked.getTrailers().isEmpty());
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=ChunkedInputStreamTrailersTest`
Expected: COMPILATION FAILURE — `getTrailers()` doesn't exist.

- [ ] **Step 3: Modify `ChunkedInputStream`**

Add a private trailers field and accessor:

```java
private Map<String, List<String>> trailers;

public Map<String, List<String>> getTrailers() {
  return trailers == null ? Map.of() : trailers;
}
```

In the `read` method, after the chunk-size loop reads `0` (the terminator chunk size) and just before transitioning the state machine into the trailer-handling states, instead invoke a dedicated parser and short-circuit to `Complete`. Concretely, find the existing branch that handles `chunkSize == 0`:

```java
if (chunkSize == 0) {
  state = nextState;
  continue;
}
```

Replace it with:

```java
if (chunkSize == 0) {
  // Push back the byte we just consumed so the trailer parser sees the full byte stream from the start of the trailer section.
  delegate.push(buffer, bufferIndex, bufferLength - bufferIndex);
  bufferIndex = bufferLength;
  parseTrailers(delegate);
  state = ChunkedBodyState.Complete;
  break;
}
```

Add the parser as a private method on `ChunkedInputStream`:

```java
private void parseTrailers(PushbackInputStream in) throws IOException {
  // RFC 9112 §7.1.2: trailer-fields use the same syntax as header-fields. After the 0-chunk we have either:
  //   "\r\n"                            (no trailers — bare terminator)
  //   "Name: Value\r\n...\r\n\r\n"      (one or more trailers, ending in bare \r\n)
  // Read line-by-line with a small line buffer until a bare CRLF.
  ByteArrayOutputStream line = new ByteArrayOutputStream(64);
  int b;
  while ((b = in.read()) != -1) {
    if (b == '\r') {
      int next = in.read();
      if (next != '\n') {
        throw new ParseException("Expected LF after CR in trailer section; got [" + next + "]");
      }
      if (line.size() == 0) {
        return; // bare CRLF — end of trailer section
      }
      addTrailerLine(line.toString(java.nio.charset.StandardCharsets.US_ASCII));
      line.reset();
    } else {
      line.write(b);
    }
  }
}

private void addTrailerLine(String raw) {
  int colon = raw.indexOf(':');
  if (colon < 0) {
    return; // malformed; skip rather than crash the request
  }
  String name = raw.substring(0, colon).trim().toLowerCase();
  if (name.isEmpty() || HTTPValues.ForbiddenTrailers.Names.contains(name)) {
    // RFC 9110 §6.5.2 forbidden trailers are silently dropped.
    return;
  }
  String value = raw.substring(colon + 1).trim();
  if (trailers == null) {
    trailers = new HashMap<>();
  }
  trailers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
}
```

This isolates trailer parsing from the chunk-body state machine entirely. Any future refactor of the state machine cannot break trailer capture. ASCII-only is correct for HTTP field values — RFC 9110 §5.5 requires field values to be ASCII unless explicitly extended.

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=ChunkedInputStreamTrailersTest`
Expected: ALL PASS.

- [ ] **Step 5: Run existing chunked tests for regressions**

Run: `latte test --test=ChunkedTest`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/io/ChunkedInputStream.java src/test/java/org/lattejava/http/tests/io/ChunkedInputStreamTrailersTest.java
git commit -m "Capture and parse chunked trailer fields with deny-list filtering"
```

---

## Task 7: Surface request trailers from `HTTPInputStream` to `HTTPRequest`

`HTTPInputStream` wraps `ChunkedInputStream` for chunked bodies. After EOF, it must populate `request.addTrailer(...)` from the chunked stream's parsed map.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/io/HTTPInputStream.java`

- [ ] **Step 1: Read the file to find the EOF point**

Run: `grep -n "ChunkedInputStream\|return -1\|EOF\|end of stream" src/main/java/org/lattejava/http/server/io/HTTPInputStream.java`
Read 30 lines around each hit. The goal is to find where `read()` returns `-1` for the chunked path.

- [ ] **Step 2: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/RequestTrailersTest.java`:

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class RequestTrailersTest extends BaseSocketTest {
  @Test
  public void chunked_request_trailers_visible_to_handler() throws Exception {
    AtomicReference<Map<String, List<String>>> seen = new AtomicReference<>();
    HTTPHandler handler = (req, res) -> {
      // Drain the body first so trailers are populated.
      req.getInputStream().readAllBytes();
      seen.set(req.getTrailerMap());
      res.setStatus(200);
    };

    try (var ignored = makeServer("http", handler).start()) {
      withRequest("""
          POST /trailers HTTP/1.1\r
          Host: cyberdyne-systems.com\r
          Transfer-Encoding: chunked\r
          \r
          5\r
          hello\r
          0\r
          X-Checksum: abc123\r
          \r
          """
      ).expectResponseSubstring("HTTP/1.1 200 ");
    }

    Map<String, List<String>> trailers = seen.get();
    assertNotNull(trailers);
    assertEquals(trailers.get("x-checksum"), List.of("abc123"));
  }
}
```

- [ ] **Step 3: Run to verify failure**

Run: `latte test --test=RequestTrailersTest`
Expected: FAIL — trailer map is empty (HTTPInputStream isn't copying trailers).

- [ ] **Step 4: Modify `HTTPInputStream`**

After the chunked-stream EOF detection (where `read()` returns `-1` from the chunked delegate), copy the trailers onto the request:

```java
if (delegate instanceof ChunkedInputStream chunked) {
  for (var e : chunked.getTrailers().entrySet()) {
    for (String v : e.getValue()) {
      request.addTrailer(e.getKey(), v);
    }
  }
}
```

(Place this once, on first EOF — guard with a boolean `trailersCopied` flag if `read()` may be called repeatedly post-EOF.)

- [ ] **Step 5: Run to verify pass**

Run: `latte test --test=RequestTrailersTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/server/io/HTTPInputStream.java src/test/java/org/lattejava/http/tests/server/RequestTrailersTest.java
git commit -m "Populate HTTPRequest trailer map from ChunkedInputStream on body EOF"
```

---

## Task 8: Extend `ChunkedOutputStream` to emit trailers after the terminator chunk

**Files:**
- Modify: `src/main/java/org/lattejava/http/io/ChunkedOutputStream.java`

- [ ] **Step 1: Write the failing unit test**

Create `src/test/java/org/lattejava/http/tests/io/ChunkedOutputStreamTrailersTest.java`:

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.tests.io;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class ChunkedOutputStreamTrailersTest {
  @Test
  public void emits_trailer_fields_after_terminator() throws Exception {
    var sink = new ByteArrayOutputStream();
    var chunked = new ChunkedOutputStream(sink, new byte[16], new FastByteArrayOutputStream());
    chunked.write("hello".getBytes());
    chunked.setTrailers(Map.of("x-checksum", List.of("abc")));
    chunked.close();

    String wire = sink.toString();
    assertTrue(wire.contains("0\r\nx-checksum: abc\r\n\r\n"), "Expected trailer-fields after 0-chunk; got: " + wire);
  }

  @Test
  public void no_trailers_emits_bare_terminator() throws Exception {
    var sink = new ByteArrayOutputStream();
    var chunked = new ChunkedOutputStream(sink, new byte[16], new FastByteArrayOutputStream());
    chunked.write("hello".getBytes());
    chunked.close();

    String wire = sink.toString();
    assertTrue(wire.endsWith("0\r\n\r\n"), "Expected bare 0-chunk terminator; got: " + wire);
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=ChunkedOutputStreamTrailersTest`
Expected: COMPILATION FAILURE — `setTrailers` doesn't exist.

- [ ] **Step 3: Modify `ChunkedOutputStream`**

Add a field and setter:
```java
private Map<String, List<String>> trailers;

public void setTrailers(Map<String, List<String>> trailers) {
  this.trailers = trailers;
}
```

Modify `close()` to emit trailers between the `0\r\n` and the final `\r\n`:

```java
@Override
public void close() throws IOException {
  if (!closed) {
    flush();
    if (trailers == null || trailers.isEmpty()) {
      delegate.write(HTTPValues.ControlBytes.ChunkedTerminator);  // existing path: "0\r\n\r\n"
    } else {
      delegate.write(new byte[]{'0', '\r', '\n'});
      for (var e : trailers.entrySet()) {
        for (String v : e.getValue()) {
          String line = e.getKey() + ": " + v + "\r\n";
          delegate.write(line.getBytes());
        }
      }
      delegate.write(HTTPValues.ControlBytes.CRLF);
    }
    delegate.flush();
    delegate.close();
  }
  closed = true;
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=ChunkedOutputStreamTrailersTest`
Expected: ALL PASS. Also run `ChunkedTest` for regression coverage.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/io/ChunkedOutputStream.java src/test/java/org/lattejava/http/tests/io/ChunkedOutputStreamTrailersTest.java
git commit -m "Emit trailer-fields from ChunkedOutputStream when set"
```

---

## Task 9: Wire response trailers through `HTTPOutputStream`

When the handler set response trailers: force chunked framing, auto-set the `Trailer:` response header, and (only if `request.acceptsTrailers()` was true) hand the trailer map down to the underlying `ChunkedOutputStream`. If the request did not signal `TE: trailers`, drop the trailers silently per RFC 9110 §6.5.

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/ResponseTrailersTest.java`:

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class ResponseTrailersTest extends BaseSocketTest {
  @Test
  public void trailers_emitted_when_te_signaled() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setTrailer("X-Checksum", "abc");
      var os = res.getOutputStream();
      os.write("hello".getBytes());
      os.close();
    };

    try (var ignored = makeServer("http", handler).start()) {
      withRequest("""
          GET / HTTP/1.1\r
          Host: cyberdyne-systems.com\r
          TE: trailers\r
          \r
          """
      ).expectResponseSubstring("Trailer: x-checksum")
       .expectResponseSubstring("0\r\nx-checksum: abc\r\n\r\n");
    }
  }

  @Test
  public void trailers_dropped_without_te_trailers() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setTrailer("X-Checksum", "abc");
      var os = res.getOutputStream();
      os.write("hello".getBytes());
      os.close();
    };

    try (var ignored = makeServer("http", handler).start()) {
      withRequest("""
          GET / HTTP/1.1\r
          Host: cyberdyne-systems.com\r
          \r
          """
      ).expectResponseDoesNotContain("x-checksum")
       .expectResponseSubstring("0\r\n\r\n");
    }
  }
}
```

(Note: `expectResponseDoesNotContain` may need to be added to `BaseSocketTest.Builder` — if so, do it as a one-line addition in this task.)

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=ResponseTrailersTest`
Expected: BOTH FAIL — `Trailer:` header not auto-set; trailer-fields not emitted.

- [ ] **Step 3: Modify `HTTPOutputStream`**

In the existing flow that decides chunked framing (around line 259–265), add a branch for trailers. After the existing branches:

```java
// If the handler set response trailers, force chunked framing (only chunked supports trailers in h1.1).
if (response.hasTrailers()) {
  if (response.getContentLength() != null) {
    response.removeHeader(HTTPValues.Headers.ContentLength);
  }
  response.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
  chunked = true;
}
```

(Add `HTTPResponse.hasTrailers()` accessor in the same task — one-liner: `return trailers != null && !trailers.isEmpty();`.)

After the preamble write, when wrapping `delegate` in `ChunkedOutputStream`:

```java
if (chunked) {
  ChunkedOutputStream cos = new ChunkedOutputStream(delegate, buffers.chunkBuffer(), buffers.chuckedOutputStream());
  if (response.hasTrailers() && request != null && request.acceptsTrailers()) {
    // Auto-populate the Trailer response header listing the trailer field names per RFC 9110 §6.5.
    String list = String.join(", ", response.getTrailers().keySet());
    response.setHeader(HTTPValues.Headers.Trailer, list);
    cos.setTrailers(response.getTrailers());
  }
  delegate = cos;
  if (instrumenter != null) {
    instrumenter.chunkedResponse();
  }
}
```

This requires `HTTPOutputStream` to know about the request. Add `HTTPRequest request` as a constructor parameter — correctness-by-construction. A setter would silently drop trailers if a future caller forgets to invoke it; the constructor parameter makes that mistake impossible.

- [ ] **Step 4: Add `HTTPRequest` to the `HTTPOutputStream` constructor**

Run: `grep -n "public HTTPOutputStream" src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java`
Read the existing constructor signature and the single call site in `HTTPWorker.run()`.

Add `HTTPRequest request` as a parameter. Update the field list (alphabetized with existing fields):

```java
private final HTTPRequest request;
```

Update the constructor body to assign it. Update the call site in `HTTPWorker.run()` to pass `request`:

```java
HTTPOutputStream outputStream = new HTTPOutputStream(configuration, request, request.getAcceptEncodings(), response, throughputOutputStream, buffers, () -> state = State.Write);
```

(The exact parameter order should match the existing convention — put `request` next to `response` since they're conceptually paired.)

- [ ] **Step 5: Run to verify pass**

Run: `latte test --test=ResponseTrailersTest`
Expected: BOTH PASS.

- [ ] **Step 6: Run full suite**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: ALL PASS.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Emit response trailers via chunked path; auto-set Trailer header; gate on TE: trailers"
```

---

## Task 10: Implement `HTTPResponse.switchProtocols(...)`

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/HTTPResponse.java`
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java` (to honor the switch)

**Pre-task verification — socket ownership.** Before writing any switchProtocols code, verify that `HTTPWorker.run()` does not auto-close the socket out from under the protocol-switch handler:

- [ ] **Step 0: Audit existing socket lifecycle**

Run: `grep -n "socket.close\|try.*socket\|closeSocketOnly" src/main/java/org/lattejava/http/server/internal/HTTPWorker.java`
Read each hit. Confirm:
- The socket is **not** in a `try-with-resources` block in `run()` (verified by inspection — should be a plain field).
- `closeSocketOnly` is the only path that closes the socket inside `run()` — and it's called explicitly on error paths or after the keep-alive loop ends.

If both are true, `return;` from `run()` after invoking the protocol-switch handler is safe — the socket stays open for the handler's caller to manage. **If either is false, stop and restructure the worker before proceeding** (the switch handler must own the socket; double-close is the failure mode).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/ProtocolSwitchTest.java`:

```java
/*
 * Copyright (c) 2026, The Latte Project
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class ProtocolSwitchTest extends BaseSocketTest {
  @Test
  public void switch_protocols_writes_101_then_invokes_handler() throws Exception {
    AtomicBoolean handlerInvoked = new AtomicBoolean(false);
    HTTPHandler handler = (req, res) -> {
      res.switchProtocols("test-proto", Map.of("X-Custom", "yes"), socket -> {
        handlerInvoked.set(true);
        // Echo a single byte after the switch — proves the socket is still live and writable post-101.
        socket.getOutputStream().write('K');
        socket.getOutputStream().flush();
      });
    };

    try (var ignored = makeServer("http", handler).start()) {
      try (var sock = makeRawSocket()) {
        sock.getOutputStream().write("""
            GET / HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            Upgrade: test-proto\r
            Connection: Upgrade\r
            \r
            """.getBytes());
        sock.getOutputStream().flush();

        // Read the 101 preamble.
        byte[] readBuf = new byte[256];
        int n = sock.getInputStream().read(readBuf);
        String head = new String(readBuf, 0, n);
        assertTrue(head.startsWith("HTTP/1.1 101 "), "Got: " + head);
        assertTrue(head.contains("Upgrade: test-proto"));
        assertTrue(head.contains("X-Custom: yes"));

        // The handler should have been invoked and written 'K'.
        // (Allow a small delay for the handler to run; in practice it's instantaneous.)
        int post = sock.getInputStream().read();
        assertEquals(post, 'K');
      }
    }

    assertTrue(handlerInvoked.get());
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=ProtocolSwitchTest`
Expected: COMPILATION FAILURE — `switchProtocols` doesn't exist.

- [ ] **Step 3: Add `switchProtocols` to `HTTPResponse`**

Add a private field to record the switch request:
```java
private String switchProtocolsTarget;
private Map<String, String> switchProtocolsHeaders;
private ProtocolSwitchHandler switchProtocolsHandler;

public boolean isProtocolSwitchPending() {
  return switchProtocolsHandler != null;
}

public String getSwitchProtocolsTarget() {
  return switchProtocolsTarget;
}

public Map<String, String> getSwitchProtocolsHeaders() {
  return switchProtocolsHeaders == null ? Map.of() : switchProtocolsHeaders;
}

public ProtocolSwitchHandler getSwitchProtocolsHandler() {
  return switchProtocolsHandler;
}

public void switchProtocols(String protocol, Map<String, String> additionalHeaders, ProtocolSwitchHandler handler) {
  if (protocol == null || protocol.isEmpty()) {
    throw new IllegalArgumentException("Protocol name must not be empty");
  }
  if (handler == null) {
    throw new IllegalArgumentException("Handler must not be null");
  }
  this.switchProtocolsTarget = protocol;
  this.switchProtocolsHeaders = additionalHeaders;
  this.switchProtocolsHandler = handler;
}
```

The actual writing happens in `HTTPWorker` — `switchProtocols` only records intent. (Doing it inside `HTTPResponse` would couple the response to the socket.)

- [ ] **Step 4: Honor the switch in `HTTPWorker.run()`**

After `configuration.getHandler().handle(request, response);` and before `response.close();`, add:

```java
if (response.isProtocolSwitchPending()) {
  // Manually emit the 101 preamble — bypass the normal HTTPOutputStream path.
  String target = response.getSwitchProtocolsTarget();
  StringBuilder sb = new StringBuilder();
  sb.append("HTTP/1.1 101 Switching Protocols\r\n");
  sb.append("Connection: Upgrade\r\n");
  sb.append("Upgrade: ").append(target).append("\r\n");
  for (var e : response.getSwitchProtocolsHeaders().entrySet()) {
    sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
  }
  sb.append("\r\n");
  socket.getOutputStream().write(sb.toString().getBytes(StandardCharsets.US_ASCII));
  socket.getOutputStream().flush();

  // Hand the socket to the handler.
  response.getSwitchProtocolsHandler().handle(socket);

  // Exit keep-alive; the new protocol owns the socket now.
  return;
}
```

Verify that `response.close()` is *not* called for the switch path — the worker exits before normal response writing.

- [ ] **Step 5: Run to verify pass**

Run: `latte test --test=ProtocolSwitchTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add HTTPResponse.switchProtocols(...) with 101 preamble + handler invocation"
```

---

## Task 11: Update `HTTP1.1.md` for closed items

**Files:**
- Modify: `docs/specs/HTTP1.1.md`

- [ ] **Step 1: Flip the three items closed by this plan**

Find and update these rows (specific text in the spec):

- §3 "Response trailers (sending)" → ✅, cite `ResponseTrailersTest`
- §3 "TE: trailers request signaling" → ✅, cite `ResponseTrailersTest.trailers_dropped_without_te_trailers`
- §4 "Upgrade / 101 Switching Protocols" → ✅, cite `ProtocolSwitchTest`

- [ ] **Step 2: Add a new row for request trailers if not already present**

If the spec doesn't list "Request trailers (receiving)" → add it as ✅ with citation `RequestTrailersTest`.

- [ ] **Step 3: Commit**

```bash
git add docs/specs/HTTP1.1.md
git commit -m "Flip HTTP1.1.md: trailers and 101 hook implemented"
```

---

## Task 12: Final verification

- [ ] **Step 1: Full build**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: ALL PASS.

- [ ] **Step 2: Verify public API surface**

Run: `grep -rn "switchProtocols\|setTrailer\|hasTrailers\|getTrailerMap\|acceptsTrailers" src/main/java/org/lattejava/http/server/ | head -20`
Expected: Methods present on `HTTPRequest`, `HTTPResponse`, and the `ProtocolSwitchHandler` interface visible.

---

## Self-review checklist

- ✅ Each task has TDD steps: failing test → implementation → green
- ✅ All public API changes have unit tests covering happy and edge paths
- ✅ Forbidden trailer names enforced and tested
- ✅ `TE: trailers` gating tested both ways (signaled / not signaled)
- ✅ 101 hook is generic — no h2c knowledge in the public API
- ✅ Lazy-init of trailer maps respects GC-reduction direction
- ✅ `HTTP1.1.md` updated at the end

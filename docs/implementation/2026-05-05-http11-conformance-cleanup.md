# HTTP/1.1 Conformance Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the ⚠️ ("partial — needs test") and one ❌ ("Reject other Expect values") items in `docs/specs/HTTP1.1.md` so the spec accurately reflects code behavior.

**Architecture:** Sibling cleanup running parallel to the HTTP/2 work. Mostly verification tests against the existing `RequestPreambleState` parser (already correct after the security audit) plus one real code change in `HTTPWorker` to respond 417 to non-`100-continue` `Expect` values. No new public API.

**Tech Stack:** Java 21, TestNG, `BaseSocketTest` raw-socket pattern (already in use for `BareLineFeedHeaderTest`, `TransferEncodingSmugglingTest`). Latte build tool.

**Reference spec:** `docs/superpowers/specs/2026-05-05-http11-conformance-cleanup-design.md`

---

## File Structure

| File | Responsibility |
|---|---|
| `src/main/java/org/lattejava/http/HTTPValues.java` | Add `Status.ExpectationFailed = 417` constant |
| `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java` | Branch on `Expect`: `100-continue` (existing path), null (skip), else 417 + close |
| `src/test/java/org/lattejava/http/tests/server/ExpectTest.java` | New test method: `expect_other_value_returns_417` |
| `src/test/java/org/lattejava/http/tests/server/RequestPreambleConformanceTest.java` | New raw-socket file: bare-CR / ws-before-colon / obs-fold / chunk-extensions / OPTIONS * / empty-Host |
| `docs/specs/HTTP1.1.md` | Flip the seven items to ✅ |

---

## Task 1: Add HTTP 417 status constant

**Files:**
- Modify: `src/main/java/org/lattejava/http/HTTPValues.java` (the `Status` inner class around lines 326–337)

- [ ] **Step 1: Add the constant**

Edit `HTTPValues.java`. Inside the `Status` inner class, add `ExpectationFailed` alphabetically between `ContinueRequest` and `MovedPermanently`:

```java
public static final class Status {
  public static final String ContinueRequest = "100-continue";

  public static final int ExpectationFailed = 417;

  public static final int MovedPermanently = 301;

  public static final int MovedTemporarily = 302;

  public static final int NotModified = 304;

  private Status() {
  }
}
```

- [ ] **Step 2: Compile to verify**

Run: `latte clean build`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/http/HTTPValues.java
git commit -m "Add HTTPValues.Status.ExpectationFailed (417) constant"
```

---

## Task 2: Add failing test for `Expect ≠ 100-continue` → 417

**Files:**
- Modify: `src/test/java/org/lattejava/http/tests/server/ExpectTest.java`

- [ ] **Step 1: Write the failing test**

Add this method at the end of `ExpectTest` (just inside the closing brace). Reuses the existing `schemes` data provider already used by `expect()`.

```java
@Test(dataProvider = "schemes")
public void expect_other_value_returns_417(String scheme) throws Exception {
  AtomicBoolean handlerCalled = new AtomicBoolean(false);
  HTTPHandler handler = (req, res) -> {
    handlerCalled.set(true);
    res.setStatus(200);
  };

  try (var ignored = makeServer(scheme, handler).start()) {
    var client = makeClient(scheme, null);
    var response = client.send(
        HttpRequest.newBuilder()
                   .uri(makeURI(scheme, ""))
                   .header("Expect", "200-ok")  // RFC 9110 §10.1.1: any non-100-continue is unsupported
                   .POST(HttpRequest.BodyPublishers.ofString("body"))
                   .build(),
        HttpResponse.BodyHandlers.ofString());

    assertEquals(response.statusCode(), 417);
    assertFalse(handlerCalled.get(), "Handler should not run when Expect is unsupported");
  }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `latte test --test=ExpectTest`
Expected: `expect_other_value_returns_417` FAILS — currently the worker silently ignores the unknown Expect, runs the handler, and returns 200.

- [ ] **Step 3: Commit (red)**

```bash
git add src/test/java/org/lattejava/http/tests/server/ExpectTest.java
git commit -m "Add failing test for Expect ≠ 100-continue returning 417"
```

---

## Task 3: Implement the 417 path in `HTTPWorker`

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java` (around line 146–160)

- [ ] **Step 1: Replace the Expect-handling block**

Current block (lines 146–160):
```java
// Handle the Expect: 100-continue request header.
String expect = request.getHeader(HTTPValues.Headers.Expect);
if (expect != null && expect.equalsIgnoreCase(HTTPValues.Status.ContinueRequest)) {
  state = State.Write;

  boolean doContinue = handleExpectContinue(request);
  if (!doContinue) {
    // Note that the expectContinue code already wrote to the OutputStream, all we need to do is close the socket.
    closeSocketOnly(CloseSocketReason.Expected);
    return;
  }

  // Otherwise, transition the state to Read
  state = State.Read;
}
```

Replace with:
```java
// Handle the Expect request header. RFC 9110 §10.1.1 — server MUST respond 417 to any expectation it does not support; we only support 100-continue.
String expect = request.getHeader(HTTPValues.Headers.Expect);
if (expect != null) {
  if (expect.equalsIgnoreCase(HTTPValues.Status.ContinueRequest)) {
    state = State.Write;

    boolean doContinue = handleExpectContinue(request);
    if (!doContinue) {
      // Note that the expectContinue code already wrote to the OutputStream, all we need to do is close the socket.
      closeSocketOnly(CloseSocketReason.Expected);
      return;
    }

    // Otherwise, transition the state to Read
    state = State.Read;
  } else {
    closeSocketOnError(response, HTTPValues.Status.ExpectationFailed);
    return;
  }
}
```

- [ ] **Step 2: Run the test to verify it passes**

Run: `latte test --test=ExpectTest`
Expected: PASS for both `expect()` (regression check) and `expect_other_value_returns_417`.

- [ ] **Step 3: Run the full test suite to catch regressions**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: ALL PASS.

- [ ] **Step 4: Commit (green)**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTPWorker.java
git commit -m "Reject unsupported Expect values with 417 per RFC 9110 §10.1.1"
```

---

## Task 4: Add raw-socket conformance tests for already-correct parser behavior

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/RequestPreambleConformanceTest.java`

These tests verify that `RequestPreambleState` already rejects bare CR, whitespace before colon, obs-fold, and that chunk-extensions and `OPTIONS *` work. They should pass on the first run — they exist to flip the spec's ⚠️ entries to ✅ and to lock the behavior in.

- [ ] **Step 1: Create the file with all six tests**

```java
/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import org.testng.annotations.*;

/**
 * Raw-socket conformance tests for `RequestPreambleState`. Covers items that HTTP1.1.md §6 lists as ⚠️
 * "needs test" — the parser already rejects these per the security audit (Vuln 3 et al.); this file
 * locks that behavior in.
 *
 * @author Daniel DeGroff
 */
public class RequestPreambleConformanceTest extends BaseSocketTest {
  @Test
  public void bare_cr_in_header_value_rejected() throws Exception {
    // RFC 9112 §5: bare CR (CR not followed by LF) inside a header value MUST be rejected. HeaderValue → HeaderCR; HeaderCR only accepts \n.
    withRequest("GET / HTTP/1.1\r\n" +
                "Host: cyberdyne-systems.com\r\n" +
                "X: bad\rmore\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n"
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void whitespace_before_colon_rejected() throws Exception {
    // RFC 9112 §5.1: no whitespace allowed between the field-name and the colon. HeaderName accepts only token chars or ':'.
    withRequest("""
        GET / HTTP/1.1\r
        Host : cyberdyne-systems.com\r
        Content-Length: 0\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void obs_fold_rejected() throws Exception {
    // RFC 9112 §5.2: obs-fold (line continuation via leading SP/HTAB) is forbidden. HeaderLF requires CR or token char at line start.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        X-Folded: line1\r
         line2\r
        Content-Length: 0\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void chunk_extensions_parsed_and_discarded() throws Exception {
    // RFC 9112 §7.1.1: chunk-ext is allowed and ignored. Verifies a request with chunk-ext succeeds.
    withRequest("""
        POST /echo HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Transfer-Encoding: chunked\r
        \r
        5;name=value\r
        hello\r
        0\r
        \r
        """
    ).expectResponseSubstring("HTTP/1.1 200 ");
  }

  @Test
  public void options_asterisk_form_accepted() throws Exception {
    // RFC 9110 §9.3.7: OPTIONS * is the asterisk-form for server-wide capability queries.
    withRequest("""
        OPTIONS * HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponseSubstring("HTTP/1.1 200 ");
  }

  @Test
  public void empty_host_value_rejected() throws Exception {
    // RFC 9112 §3.2.3 is silent on empty Host, but common practice is to reject as 400. Lock current behavior in;
    // if this fails we add validation in Task 5.
    withRequest("""
        GET / HTTP/1.1\r
        Host: \r
        Content-Length: 0\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }
}
```

- [ ] **Step 2: Run the file**

Run: `latte test --test=RequestPreambleConformanceTest`
Expected:
- `bare_cr_in_header_value_rejected` PASS
- `whitespace_before_colon_rejected` PASS
- `obs_fold_rejected` PASS
- `chunk_extensions_parsed_and_discarded` PASS
- `options_asterisk_form_accepted` PASS
- `empty_host_value_rejected` may FAIL — current behavior is unspecified. If it fails, proceed to Task 5; if it passes, mark Task 5 N/A.

- [ ] **Step 3: Commit whatever passes**

```bash
git add src/test/java/org/lattejava/http/tests/server/RequestPreambleConformanceTest.java
git commit -m "Add HTTP/1.1 preamble conformance tests for already-correct parser behavior"
```

---

## Task 5: Add empty-Host validation if Task 4's last test failed

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java` (the `validatePreamble` method)

If Task 4's `empty_host_value_rejected` test passed, skip this whole task.

- [ ] **Step 1: Find `validatePreamble`**

Run: `grep -n "validatePreamble" src/main/java/org/lattejava/http/server/internal/HTTPWorker.java`
Expected: a method definition somewhere after `run()`.

- [ ] **Step 2: Read existing validations**

Run: `grep -n "Host\|getHost" src/main/java/org/lattejava/http/server/internal/HTTPWorker.java`
Expected: shows where Host is read or validated. Read 30 lines around the hit to understand the pattern.

- [ ] **Step 3: Add the empty check**

In `validatePreamble`, alongside any existing Host check, add:

```java
String host = request.getHeader(HTTPValues.Headers.Host);
if (host != null && host.isEmpty()) {
  return Status.BadRequest;
}
```

(Adjust to match the local convention — if the method already pulls the host into a variable, reuse it.)

- [ ] **Step 4: Run the test**

Run: `latte test --test=RequestPreambleConformanceTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTPWorker.java
git commit -m "Reject empty Host header value with 400"
```

---

## Task 6: Update `HTTP1.1.md` to reflect closed items

**Files:**
- Modify: `docs/specs/HTTP1.1.md`

- [ ] **Step 1: Open the file and locate the seven entries**

The entries to flip live in §1, §3, §6, and §9 of `HTTP1.1.md`. Refer to the design doc (`docs/superpowers/specs/2026-05-05-http11-conformance-cleanup-design.md`) for the exact rows.

- [ ] **Step 2: Flip each ⚠️ to ✅ and the §9 ❌ to ✅**

For each row, change the status emoji and update the "Notes" column to cite the new test class. Example for "Reject bare CR":

```markdown
| §6 "Reject bare CR" | ✅ | `RequestPreambleConformanceTest.bare_cr_in_header_value_rejected` |
```

Apply the same pattern to:
- §6 "Reject bare CR" → ✅, cite `bare_cr_in_header_value_rejected`
- §6 "Reject whitespace before `:`" → ✅, cite `whitespace_before_colon_rejected`
- §6 "Reject obs-fold" → ✅, cite `obs_fold_rejected`
- §6 "Empty Host value" → ✅, cite `empty_host_value_rejected`
- §3 "Chunk extensions" → ✅, cite `chunk_extensions_parsed_and_discarded`
- §1 "OPTIONS asterisk-form" → ✅, cite `options_asterisk_form_accepted`
- §9 "Reject other Expect values" → ✅, cite `ExpectTest.expect_other_value_returns_417`

- [ ] **Step 3: Commit**

```bash
git add docs/specs/HTTP1.1.md
git commit -m "Flip HTTP1.1.md conformance items to implemented"
```

---

## Task 7: Final verification

- [ ] **Step 1: Full CI build**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: ALL PASS.

- [ ] **Step 2: Verify no leftover ⚠️/❌ in scope**

Run: `grep -E "⚠️|❌" docs/specs/HTTP1.1.md | head -20`
Expected: No row matches any of the seven items closed by this plan. Other ⚠️/❌ rows remain (e.g. trailers, 101 hook — those close in Plan B).

- [ ] **Step 3: Final commit if anything changed**

If steps above produced no new diff, this task is a no-op. Otherwise:

```bash
git add -A
git commit -m "Tidy HTTP/1.1 conformance cleanup"
```

---

## Self-review checklist

- ✅ Each step has actual code/commands
- ✅ TDD: failing test before implementation (Task 2 → 3)
- ✅ Verification tests against existing-correct behavior committed in one shot (Task 4)
- ✅ `HTTP1.1.md` flipped at the end so the spec matches code
- ✅ No new public API

# Automatic HEAD Request Handling

**Date:** 2026-04-18
**Branch:** `features/head`

## Motivation

The server currently makes no distinction between HEAD and GET requests. A HEAD request is dispatched to the handler exactly like any other method; if the handler writes a body (which it will, if it only implements GET), those body bytes go to the client — a protocol violation. Most handlers only know how to respond to GET; they should not have to implement HEAD separately just to be spec-compliant.

The goal is automatic, transparent HEAD handling: a handler that works for GET also works for HEAD with no changes, and specialized handlers that want to short-circuit body generation (static-file servers, CDN connectors) can do so.

## Semantic Rule

> A HEAD response produces the exact same preamble a GET would — only the body bytes are dropped.

Everything flows from this rule:
- Header output (status line, `Content-Length`, `Transfer-Encoding`, `Content-Encoding`, `Vary`, custom headers, cookies) is byte-for-byte identical to what the server would emit for a GET against the same handler.
- No bytes follow the preamble. No chunk framing, no compressed stream, nothing.
- Handlers can optimize (skip expensive body generation) without being required to.

## Request-Side API

Two methods participate: the existing `HTTPRequest.getMethod()` / `setMethod()` and one new method.

**`HTTPRequest` changes:**
- Add a private `originalMethod` field.
- On the first call to `setMethod(...)`, capture the argument into `originalMethod` in addition to setting the effective `method` field. Subsequent calls only update `method`.
- Add public `boolean isHeadRequest()` — returns `originalMethod != null && originalMethod.is(HTTPMethod.HEAD)`.
- `getMethod()` is unchanged. It returns the effective method.

**Rewrite at the worker:** when the worker detects `HTTPMethod.HEAD` after preamble parsing, it calls `request.setMethod(HTTPMethod.GET)`. Because `originalMethod` was captured on the first `setMethod` call (during preamble parsing), this rewrite preserves HEAD-awareness for `isHeadRequest()` while making `getMethod()` return `GET` from that point forward.

Handlers that branch on `getMethod() == GET` transparently serve HEAD requests. Handlers that want to optimize check `isHeadRequest()`.

## Response-Side Body Suppression

The body suppression lives entirely in `HTTPOutputStream`.

**New field:** `boolean suppressBody` with a setter `setSuppressBody(boolean)`. The worker sets it to `true` when the request is HEAD, before invoking the handler.

**Framing rules (applied in `commit()` before writing the preamble):**

1. **Status 204 / 304** (`noBodyStatus`) — strip any handler-set `Content-Length` and `Transfer-Encoding`; emit preamble only; suppress any body bytes the handler tries to write.
2. **`Transfer-Encoding` wins over `Content-Length`** (RFC 7230 §3.3.3) — if the handler set `Transfer-Encoding`, strip any handler-set `Content-Length` before the preamble is written.
3. **HEAD** (`suppressBody == true`) — preamble matches what GET would produce; body bytes are discarded. Handler framing (`Content-Length` or `Transfer-Encoding`) is preserved even on a no-write close (CDN escape hatch).
4. **GET defensive override on no-write close** — if the handler wrote nothing, force `Content-Length: 0` and strip any handler-set `Transfer-Encoding`. Prevents clients from hanging on mismatched framing.
5. **Handler-set `Transfer-Encoding: chunked` on GET with writes** — server wraps the output delegate in `ChunkedOutputStream` so the bytes are actually chunk-framed on the wire.

**Behavior changes to the output stream:**
- `commit(boolean closing)` — writes headers per the rules above, then the preamble. If body should be suppressed (HEAD, or 204/304), `commit()` returns without installing the chunked/gzip/deflate delegates.
- `write(byte[], int, int)` / `write(int)` / `write(byte[])` — no-op after commit when body is suppressed: no byte delegation, no `instrumenter.wroteToClient(...)` callback.
- `close()` continues to call `commit(true)`.
- `flush()` / `forceFlush()` are unchanged.

**Truth table:**

For each combination of status, handler framing choice (what the handler set on the response before committing), and whether the handler wrote any bytes, the following preamble and body bytes are produced on the wire. Handler-close-stream vs leave-open is omitted because `HTTPWorker` always calls `response.close()` after the handler returns and `commit()` is idempotent via the `committed` flag.

### Status 200

| Handler framing set | Handler wrote bytes? | GET preamble / body                                               | HEAD preamble / body                                          |
|---------------------|----------------------|-------------------------------------------------------------------|---------------------------------------------------------------|
| neither             | yes                  | `Transfer-Encoding: chunked`, body = chunk-framed bytes           | `Transfer-Encoding: chunked`, body = empty                    |
| neither             | no                   | `Content-Length: 0`, body = empty                                 | `Content-Length: 0`, body = empty                             |
| `Content-Length: N` | yes                  | `Content-Length: N`, body = N bytes                               | `Content-Length: N`, body = empty                             |
| `Content-Length: N` | no                   | `Content-Length: 0` *(defensive override)*, body = empty          | `Content-Length: N` *(CDN escape hatch)*, body = empty        |
| `Transfer-Encoding: chunked` | yes         | `Transfer-Encoding: chunked`, body = chunk-framed bytes           | `Transfer-Encoding: chunked`, body = empty                    |
| `Transfer-Encoding: chunked` | no          | `Content-Length: 0` *(defensive: strip `TE`)*, body = empty       | `Transfer-Encoding: chunked` *(framing preserved)*, body = empty |
| both `CL` and `TE`  | yes                  | `Transfer-Encoding: chunked` *(CL stripped, TE wins)*, body = chunks | `Transfer-Encoding: chunked` *(CL stripped)*, body = empty |
| both `CL` and `TE`  | no                   | `Content-Length: 0` *(defensive: strip `TE`, CL already stripped by TE-wins)*, body = empty | `Transfer-Encoding: chunked` *(CL stripped, framing preserved)*, body = empty |

### Status 204 — MUST NOT carry representation metadata or body (RFC 9110 §15.3.5, RFC 7230 §3.3.2)

All eight combinations of handler framing and writes collapse to the same outcome:

| Handler framing set | Handler wrote bytes? | GET preamble / body                                       | HEAD preamble / body                                      |
|---------------------|----------------------|-----------------------------------------------------------|-----------------------------------------------------------|
| any                 | any                  | no `Content-Length`, no `Transfer-Encoding`, body = empty | no `Content-Length`, no `Transfer-Encoding`, body = empty |

### Status 304 — SHOULD NOT carry representation metadata (RFC 9110 §15.4.5); body is never allowed

Same as 204:

| Handler framing set | Handler wrote bytes? | GET preamble / body                                       | HEAD preamble / body                                      |
|---------------------|----------------------|-----------------------------------------------------------|-----------------------------------------------------------|
| any                 | any                  | no `Content-Length`, no `Transfer-Encoding`, body = empty | no `Content-Length`, no `Transfer-Encoding`, body = empty |

### Compression

Compression (`res.setCompress(true)`) is orthogonal to the framing choice above. When compression is active and a body is written, the server strips `Content-Length`, sets `Content-Encoding: gzip` (or `deflate`), `Vary: Accept-Encoding`, and `Transfer-Encoding: chunked`. For HEAD the headers are the same; the body is suppressed.

### Error paths

When an exception in the handler triggers `closeSocketOnError`, `response.reset()` clears the headers and `outputStream.reset()` clears `committed`/`compress`/delegate state. `suppressBody` is intentionally preserved across `reset()` so HEAD error responses still suppress their body. After `reset()`, the error response follows the same rules above based on its (new) status.

## Worker Wiring

In `HTTPWorker.run()`, after `parseRequestPreamble(...)` and after `validatePreamble(...)` returns success, before the `Expect: 100-continue` block:

```java
if (request.getMethod().is(HTTPMethod.HEAD)) {
  outputStream.setSuppressBody(true);
  request.setMethod(HTTPMethod.GET);
}
```

That is the entire wiring change. The remainder of the worker (expect handling, handler dispatch, response close, keep-alive, drain, error paths) is untouched.

**Error paths:** `closeSocketOnError(...)` calls `response.reset()` and writes a fresh preamble with `Content-Length: 0`. The `suppressBody` flag lives on `HTTPOutputStream`, not on `HTTPResponse`, so it is not cleared by `response.reset()`. HEAD error responses correctly emit only a preamble.

**Expect: 100-continue:** the rewrite to `GET` happens before the expect validator runs, so the validator sees `method = GET`. A HEAD with `Expect: 100-continue` is unusual but not forbidden; this behavior is the same as any other method.

## Testing

All body-presence assertions go through raw-socket tests because the JDK `HttpClient` cannot verify "no body bytes on the wire" — it knows HEAD has no body by RFC and will not read past the preamble, so a server that incorrectly wrote body bytes or chunks would be invisible to the client.

### Enhancing `BaseSocketTest.Builder`

The existing `Builder` uses a canned handler. Extend it so HEAD tests (and any future tests) can plug in their own:
- Add `withHandler(HTTPHandler handler)` to `Builder`.
- `assertResponse(...)` uses the configured handler when one is set; otherwise falls back to the existing canned handler for backward compatibility.
- Existing callers require no changes.

### Test file: `HeadTest.java` (raw-socket)

1. **GET-only handler serves HEAD.** Handler writes a fixed body with `Content-Length`. Assert HEAD response preamble matches exactly and has no body bytes.
2. **Handler sets `Content-Length`, writes body.** Assert HEAD preamble has `Content-Length: N` and zero body bytes.
3. **Handler writes body without `Content-Length`.** Assert HEAD preamble has `Transfer-Encoding: chunked` and zero body bytes (no chunk framing).
4. **Handler writes nothing.** Assert HEAD preamble has `Content-Length: 0`.
5. **Compression enabled, handler writes body.** Assert HEAD preamble has `Content-Encoding: gzip`, `Vary: Accept-Encoding`, `Transfer-Encoding: chunked`, and zero body bytes.
6. **CDN escape hatch.** Handler checks `isHeadRequest()`, sets `Content-Length` to a synthetic value, writes nothing. Assert HEAD preamble has that `Content-Length` and zero body bytes.
7. **HEAD against 204 / 304 handlers.** Assert preamble has no `Content-Length` and no `Transfer-Encoding`, zero body bytes.
8. **HEAD with `sendRedirect(...)`.** Assert preamble has status 302 and `Location` header, zero body bytes.
9. **Keep-alive reuse.** On a single socket, send HEAD then GET. Assert HEAD response matches exactly, GET response follows with its body intact.

### Test file: `HeadRequestContractTest.java` (JDK `HttpClient` is fine)

10. **Request-side contract.** HEAD request to a handler that stashes `request.isHeadRequest()` and `request.getMethod().name()` into response headers. Assert `isHeadRequest()` == `true` and `getMethod()` == `GET`.

## Out of Scope

- No configuration flag to disable automatic HEAD handling. The behavior is always on; it is the spec-compliant behavior. Handlers that need custom HEAD logic use `isHeadRequest()`.
- No changes to `HTTPResponse`. The body-suppression contract is entirely inside `HTTPOutputStream`.
- No changes to client-facing methods on `HTTPRequest` beyond adding `isHeadRequest()`. `getMethod()` semantics shift (returns GET for HEAD), but this is the intended behavior, not a breaking change from the handler's perspective — handlers written for GET continue to work.

## Files Touched

- `src/main/java/org/lattejava/http/server/HTTPRequest.java` — add `originalMethod`, `isHeadRequest()`, adjust `setMethod(...)`.
- `src/main/java/org/lattejava/http/server/io/HTTPOutputStream.java` — add `suppressBody` flag and setter; short-circuit `commit()` Steps 3/4 and `write(...)` paths.
- `src/main/java/org/lattejava/http/server/internal/HTTPWorker.java` — detect HEAD, rewrite method, set `suppressBody`.
- `src/test/java/org/lattejava/http/tests/server/BaseSocketTest.java` — add `withHandler(...)` to `Builder`, use configured handler in `assertResponse(...)`.
- `src/test/java/org/lattejava/http/tests/server/HeadTest.java` — new socket-based tests (items 1–9).
- `src/test/java/org/lattejava/http/tests/server/HeadRequestContractTest.java` — new client-based test (item 10).

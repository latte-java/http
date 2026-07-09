# HTTP/2 response Set-Cookie emission and unified response head validation

## Problem

Cookies added to an `HTTPResponse` via `addCookie(Cookie)` are never sent to the client on HTTP/2. The HTTP/1.1 path
writes them in `HTTPTools.writeResponsePreamble` (one `Set-Cookie` header per cookie, rendered by
`Cookie.toResponseHeader()`), but the HTTP/2 path builds the response HEADERS frame in
`HTTP2OutputProtocol.commitHeaders` from `:status` plus `response.getHeadersMap()` only — the cookie map is silently
dropped. Any handler that uses `addCookie` works on h1.1 and loses its cookies on h2.

A second, related gap: the h1.1 preamble writer is the single choke point for the response-splitting defense (audit
Vuln 4) — it validates the status message, header names/values, and cookie name/value/domain/path before any byte is
written. The h2 emission path performs none of this validation. `docs/design/2026-05-05-HTTP2.md` (Response-splitting
defense row) claims h2 inherits the h1.1 defense, but the choke point lives in `writeResponsePreamble`, which h2 never
calls — the claim is stale. A CR/LF/NUL in a header or cookie value would be HPACK-encoded verbatim, violating
RFC 9113 §8.2.1 (a field value must not contain NUL, CR, or LF) and enabling header injection through an h2→h1
translating intermediary.

## Design

Two changes, both small:

### 1. Extract a shared validator: `HTTPTools.validateResponse(HTTPResponse)`

Move the validation block currently inlined at the top of `writeResponsePreamble` (status message, header
names/values, cookie name/value/domain/path) into a new public static method `validateResponse(HTTPResponse)` in
`HTTPTools`. It throws `IllegalArgumentException` on the first invalid character, exactly as today.

- `writeResponsePreamble` (h1.1) calls it first, preserving current behavior byte-for-byte.
- `HTTP2OutputProtocol.commitHeaders` (h2) calls it before building the header field list.

The status message is validated on h2 even though h2 never emits a reason phrase (`:status` is numeric only). This is
deliberate: one shared method, fail-fast, identical handler-visible behavior across protocols.

### 2. Emit cookies in the h2 response HEADERS frame

In `HTTP2OutputProtocol.commitHeaders`, after the regular header loop, append one field per cookie:

```java
for (var cookie : response.getCookies()) {
  respFields.add(new HPACKDynamicTable.HeaderField("set-cookie", cookie.toResponseHeader()));
}
```

- Cookies are appended after regular headers, mirroring the h1.1 preamble order.
- No HPACK changes needed: HPACKEncoder already classifies set-cookie as sensitive and encodes it as a literal
  without indexing (RFC 7541 §6.2.2). §7.1.3 recommends the never-indexed form (§6.2.3) for Set-Cookie; flipping
  the sensitive-field mask is a noted follow-up.
- `commitTrailers` is untouched — cookies are response-head fields, not trailers.

## Failure modes

On validation failure the `IllegalArgumentException` propagates out of `commitHeaders` before any frame is enqueued
(nothing has been sent on the stream), so no partial or splittable output ever reaches the wire:

- h1.1: unchanged — the worker's catch-all resets the response and returns a clean 500.
- h2: the exception reaches `HTTP2HandlerDelegate`'s catch-all, which sends `RST_STREAM(INTERNAL_ERROR)`. The JDK
  client surfaces this as an `IOException`. Converting this to a clean 500 HEADERS frame (as the
  `HTTPProcessingException` path does) is a possible future refinement, but out of scope here — validation failures
  behave like any other handler exception on h2.

## Out of scope

- Set-time validation in `HTTPResponse.addHeader`/`addCookie`. The audit design deliberately validates at write time
  in a single choke point; this change keeps that model and just shares the choke point across protocols.
- h2 trailer validation (`commitTrailers`). Trailers come from `response.getTrailers()`; validating them is a separate
  concern on both protocols.
- The 500-vs-RST_STREAM refinement noted above.

## Testing

Module-level tests (server + wire via the JDK `HttpClient`), TDD:

1. **Cookie emission on h2** — extend `CookieTest` with HTTP/2 variants using the h2-over-TLS client pattern from
   `HTTP2CompressionTest` (`HttpClient.Version.HTTP_2`, assert `resp.version()` is `HTTP_2`):
   - Single cookie via `addCookie` → response contains the `set-cookie` header with the exact
     `Cookie.toResponseHeader()` rendering (attributes: path, domain, max-age, secure, httponly, samesite).
   - Multiple cookies → multiple distinct `set-cookie` headers.
2. **Validation on h2** — extend `ResponseSplittingTest` with h2 cases:
   - Cookie value containing CR/LF → request fails (stream reset), injected header never reaches the client.
   - Header value containing CR/LF → same.
   - Matching h1.1 cases already exist and must keep passing against the extracted method.

## Documentation

Update the Response-splitting defense row in `docs/design/2026-05-05-HTTP2.md` to reference the real choke point
(`HTTPTools.validateResponse`, called from both `writeResponsePreamble` and `HTTP2OutputProtocol.commitHeaders`).

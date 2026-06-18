# Design: HTTPRequest.getReconstructedURL()

Date: 2026-05-15

## Goal

Add a method to `HTTPRequest` that returns the complete URL as the end user's
browser used it — scheme, host, port, path, and query string — reconstructed
from the request and proxy headers.

## Signature

```java
public String getReconstructedURL()
```

Named with the `get*` getter prefix to match the existing accessor pattern on
`HTTPRequest` (`getBaseURL`, `getPath`, `getQueryString`, etc.). Returns
`String`, consistent with the sibling `getBaseURL()`.

## Behavior

The method composes three existing accessors:

```
getBaseURL() + getPath() + ("?" + getQueryString()  when query present)
```

- **Base** (`scheme://host[:port]`) is delegated to the existing
  `getBaseURL()`. This keeps the method proxy-aware (honors
  `X-Forwarded-Proto`, `X-Forwarded-Host`, `X-Forwarded-Port`) and reuses the
  established port-omission rules: the port is omitted for `http` on 80 and
  `https` on 443, and included otherwise. No base-URL logic is duplicated.
- **Path** comes from `getPath()` — the request-line path exactly as received
  (the server does not re-decode it), so the value round-trips faithfully.
  `getPath()` defaults to `/`, so the result always has at least a root path.
- **Query** is appended as `"?" + getQueryString()` only when
  `getQueryString()` is non-null and non-empty. When the query is absent or
  empty, no trailing `?` is emitted (matches typical browser address-bar
  behavior).
- **Fragments** (`#...`) are never reconstructable because they are not
  transmitted to the server. The Javadoc states this explicitly.

## Error Handling

The method introduces no new error paths. It inherits `getBaseURL()`'s
behavior: when the (possibly proxy-supplied) scheme is neither `http` nor
`https`, `getBaseURL()` throws `IllegalArgumentException`. This is documented
on `getReconstructedURL()` with an `@throws` clause.

## Placement & Conventions

- Instance method, placed in alphabetical order among the public instance
  methods per the project's "Order inside classes" convention.
- The file (`HTTPRequest.java`) carries an upstream FusionAuth Apache-2.0
  header. Per the copyright rule, the existing header and `@author` are
  preserved unchanged; no MIT/SPDX header is introduced.
- Javadoc written in American-English sentence form per the Javadoc
  convention, including the `@return` and `@throws` tags.

## Testing

TestNG cases added to the existing `HTTPRequest` test coverage:

- Path only, no query → `https://host/path`
- Path + query → `https://host/path?a=1&b=2`
- Null or empty query string → no trailing `?`
- `http` on port 80 → port omitted
- `https` on port 443 → port omitted
- Non-default port → port included
- Behind a proxy (`X-Forwarded-Proto/Host/Port`) → reflects forwarded values
- Invalid scheme → `IllegalArgumentException`

## Scope

Additive only. No existing method's behavior changes.

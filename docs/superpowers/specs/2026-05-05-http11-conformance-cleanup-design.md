# HTTP/1.1 Conformance Cleanup — Design

**Date:** 2026-05-05
**Branch:** TBD (sibling to `robotdan/http2`)
**Status:** Brainstormed

## Motivation

While reviewing `docs/specs/HTTP1.1.md` during the HTTP/2 design pass, several ⚠️ ("Partial — needs test") items were verified against current code and found to be already correct in implementation but lacking explicit tests, plus one item that is genuinely open. This doc collects those into a small focused cleanup that runs in parallel to the HTTP/2 work — nothing about HTTP/2 forces this to ship first or together.

The HTTP/2 design (`2026-05-05-http2-design.md`) handles the three ❌ items in HTTP1.1.md that flip *because of* HTTP/2 work (response trailers, `TE: trailers` signaling, 101 Switching Protocols hook). This doc handles everything else.

## Items in scope

### Genuinely open work (one item)

| HTTP1.1.md ref | Issue | Fix |
|---|---|---|
| §9 "Reject other Expect values" | `HTTPWorker.java:148` silently ignores any non-`100-continue` Expect. RFC 9110 §10.1.1 says the server MUST respond `417 Expectation Failed` for unsupported expectations. | Branch on the Expect header: `100-continue` → existing path; null/absent → skip; anything else → write 417 and close. |

### Verification-only (tests to add; code already correct)

The state machine in `RequestPreambleState.java` was tightened during the security audit (Vuln 3, bare-LF rejection) and adjacent state transitions also reject the items below — but the spec lists them as ⚠️ "needs test." Read-through during the HTTP/2 brainstorm confirmed correctness. Adding the tests flips each entry to ✅.

| HTTP1.1.md ref | Verified mechanism |
|---|---|
| §6 "Reject bare CR" | `HeaderValue` state transitions on `\r` only to `HeaderCR`, which only accepts `\n`. Any byte other than `\n` after `\r` → `ParseException`. |
| §6 "Reject whitespace before `:`" | `HeaderName` state accepts only token chars or `:`. SP between name and colon → `ParseException`. |
| §6 "Reject obs-fold" | `HeaderLF` state requires `\r` (start of preamble-end) or token char (next header). SP/HTAB at line-start → `ParseException`. |
| §3 "Chunk extensions" | `ChunkedInputStream` parses-and-discards extensions per existing code. |
| §1 "OPTIONS asterisk-form" | Parser accepts; just no test. |

### Behavior-unclear (test, then add validation if needed)

| HTTP1.1.md ref | Action |
|---|---|
| §6 "Empty Host value" | Write a test that sends `Host:` (empty value) vs `Host: example.com` vs missing. Determine current behavior; tighten if `Host:` (empty) is treated identically to a populated Host. RFC 9112 §3.2.3 is silent; common practice is to reject as 400. |

## Out of scope

- **Range requests / 206 / `Accept-Ranges` / `If-Range`** — these are RFC 9110 §14 MAY-support items; not required for compliance. Defer to a future feature spec.
- **HTTP pipelining** — RFC says SHOULD support; we currently process one request at a time per connection. Modern clients abandoned pipelining; low practical value.
- **Auto suppression for 101/103 1xx responses** — handler hook for 1xx isn't built; if a handler returns 101 today, no auto-suppression. The new 101 hook on `HTTPResponse` (delivered with HTTP/2) addresses 101 specifically; 103 stays handler-responsibility.

## Files touched

```
src/main/java/org/lattejava/http/server/internal/HTTPWorker.java          // Expect ≠ 100-continue → 417
src/test/java/org/lattejava/http/tests/server/ExpectTest.java              // 417 cases
src/test/java/org/lattejava/http/tests/server/CoreTest.java (or new)       // bare CR / ws-before-colon / obs-fold / empty-Host / chunk-ext / OPTIONS *
docs/specs/HTTP1.1.md                                                      // flip ⚠️ entries to ✅; close §9 open item
```

## Test plan

One test per row in the tables above. Mostly raw-socket tests via the existing `BaseSocketTest.Builder` since several violations would be silently corrected by `java.net.http.HttpClient`. Tests assert: 400 (for genuine bad-request items), 417 (for unknown Expect), correct parse (for chunk-extensions, OPTIONS *).

## Sequencing

Independent of HTTP/2. Can land before, alongside, or after. Likely a single small PR.

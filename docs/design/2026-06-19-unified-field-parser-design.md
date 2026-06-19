# Design: Unified HTTP field-block parser

Date: 2026-06-19

## Goal

Parse the three HTTP/1.1 "field block" surfaces — request headers, chunked
trailers, and multipart part headers — through a single shared parser, without
regressing the performance of any of them. In particular the request-header path
is the hottest parse in the server and must stay neutral op-for-op; the other two
should get no slower (and in practice get faster).

HTTP/2 header fields are explicitly **out of scope**: they arrive HPACK-encoded
(binary) and share no grammar with the text field block. This design covers only
the HTTP/1.1 text surfaces.

## Background: the three parsers today

All three parse the same grammar — a sequence of `field-name ":" OWS field-value
CRLF` lines terminated by a bare CRLF — but each does so with its own drive loop
and its own accumulation strategy:

- **Request preamble** — `HTTPTools.parseRequestPreamble` + `RequestPreambleState`.
  An enum FSM driven over a `byte[]` read buffer in a tight inner loop,
  accumulating into a manual `byte[] valueBuffer` + `int valueLen`. This shape is
  the result of a deliberate optimization: a previous `ByteArrayOutputStream` was
  removed because `BAOS.write(int)` (synchronized, per-byte dispatch) measured at
  ~12% of `parseRequestPreamble` CPU under JFR. The FSM mixes request-line states
  (`RequestMethod` … `RequestLF`) and header states (`HeaderName` … `Complete`) in
  one machine.
- **Multipart part headers** — `MultipartStream.readHeaders`. Already reuses
  `RequestPreambleState`, entering at `HeaderName`, but drives it **one byte at a
  time** via `readByte()` and accumulates into a `StringBuilder`, then runs
  `HTTPTools.parseHeaderValue`.
- **Chunked trailers** — `ChunkedInputStream.parseTrailers` + `addTrailerLine`. A
  separate, ad-hoc line parser: **single-byte** `in.read()` calls into a
  `ByteArrayOutputStream`, a manual `indexOf(':')` split, `.trim()` /
  `.toLowerCase()`, and forbidden-name filtering. It does not use the FSM at all —
  the `Trailer*` states in `ChunkedBodyState` only detect section-end for the body
  machine.

The shared element is the field-block **grammar**. The divergence is in buffer
management and accumulation, not in what is being parsed. Request headers
additionally carry a request-line prefix; the other two do not.

## Approach

Introduce a stateful, resumable, **push/feed** field-block engine that owns the
field-block FSM and the `byte[]` accumulation, and emits each parsed
`(name, value)` pair through a consumer callback. Each caller feeds the engine
slices from its own buffer and is told how many bytes were consumed and whether
the block is complete.

Push/feed is chosen over a pull-based `ByteSource` abstraction because two of the
three callers cannot cede ownership of the read loop:

- Multipart co-uses its buffer for boundary scanning **immediately after** the
  header block, so it needs "you consumed N bytes; I will resume my own scan at
  `current + N`."
- The preamble must push leftover over-read bytes back to a
  `PushbackInputStream` once the block ends.

Both want to hand the parser a slice and get control back — that is push, not
pull.

### Components

**`HTTPFieldParser`** (new, `org.lattejava.http.util`)

The shared engine. Holds:

- A private `FieldState` FSM — the header states extracted from
  `RequestPreambleState`:
  `Start → Name → Colon → Value → FieldCR → (emit; back to Start)`, plus
  `Start → BlockCR → Complete` for the terminating bare CRLF. The
  character-class rules (`isTokenCharacter`, `isValueCharacter`) and the
  reject-on-bad-byte behavior are identical to the current header states.
- Two growable `byte[]` accumulators (name, value) with `len` counters, grown by
  doubling on overflow — the exact pattern of today's `valueBuffer`.
- A running consumed-byte counter, exposed for size-limit enforcement.

API:

```java
public int feed(byte[] buf, int off, int len, FieldConsumer consumer);
public boolean isComplete();
public long bytesConsumed();
```

`feed` drives the FSM over `[off, off+len)`, calls `consumer.field(name, value)`
once per complete field, returns the number of bytes consumed, and stops at
`Complete` or at the end of the slice — whichever comes first. State and the
partial accumulators persist across `feed` calls, so a field (or the whole block)
may span any number of feeds. This resumability is what lets every caller stay
**buffer-at-a-time** instead of byte-at-a-time.

**`FieldConsumer`** (functional interface)

```java
void field(String name, String value);
```

This is where the three callers' only real differences live. The engine itself
stays policy-neutral (it does not lowercase, trim, or filter):

- **Preamble** → `request::addHeader` with the raw name, byte-for-byte as today.
- **Multipart** → lowercase the name with `HTTPTools.asciiLowerCase` and run
  `HTTPTools.parseHeaderValue(value)` into the part's header map.
- **Trailers** → `HTTPTools.asciiLowerCase` the name, trim, and drop
  `HTTPValues.ForbiddenTrailers`.

All name lowercasing goes through `HTTPTools.asciiLowerCase` — see the
Lowercasing section.

**Request-line FSM** (`RequestPreambleState`, reduced)

`RequestPreambleState` shrinks to the request-line states only
(`RequestMethod … RequestLF`). When the preamble driver reaches `RequestLF` (the
request-line CRLF has been consumed), it stops driving the request-line machine
and feeds all remaining bytes to an `HTTPFieldParser` seeded at `Start`. The
preamble becomes "request-line FSM, then shared field parser."

### Data flow per caller

- **Preamble** (`parseRequestPreamble`): read buffer → drive the request-line FSM
  to `RequestLF` → `feed` the remainder of the buffer to the field parser → loop
  read+feed until `isComplete` → push the leftover bytes back. `maxRequestHeaderSize`
  is enforced from `bytesConsumed()` plus the request-line byte count, preserving
  the current cumulative-preamble-length check.
- **Multipart** (`readHeaders`): `feed(buffer, current, end - current, consumer)`
  → advance `current` by the return value → if not complete, `reload()` and feed
  again → resume the boundary scan at `current`. Replaces the `StringBuilder` and
  the byte-at-a-time `readByte()` drive.
- **Trailers** (`ChunkedInputStream`): after the terminating 0-chunk, `feed` from
  the stream's own buffer, reloading as needed. Replaces `parseTrailers` /
  `addTrailerLine` and the single-byte `in.read()` loop entirely.

## Performance

Performance neutrality on the request-header path is the hard constraint. It holds
because the unified engine **generalizes the shape that path already has** rather
than replacing it:

- **Per-byte work is unchanged.** Today the hot inner loop does, per byte: one enum
  virtual call (`state.next(ch)`), one `store()` branch, one array write. The
  unified engine does exactly that: one `FieldState` virtual call, one store
  branch, one write into `nameBuf`/`valueBuf`. No new per-byte indirection is
  introduced.
- **`feed` is amortized.** It is called once per buffer read — over hundreds or
  thousands of bytes — not per byte. The consumer callback fires once per field.
- **Accumulation stays `byte[] + len` with doubling** — the exact representation
  that the `BAOS` removal optimization produced. No `StringBuilder`, no
  `ByteArrayOutputStream`.
- **String materialization is once per field, UTF-8** — identical to today.
- The only structural change to the hot path is the request-line → field-block
  handoff, which happens **once per request**.

The other two paths get strictly **faster**: multipart sheds its `StringBuilder`
and its byte-at-a-time `readByte()` drive; trailers shed the `ByteArrayOutputStream`
and the single-byte `in.read()` calls. Both move to the same buffer-at-a-time
`byte[]` accumulation as the preamble.

## Behavior change: trailers become strict

Today's trailer parser is lenient — it splits on CRLF framing and accepts almost
any other byte in names and values. Routing trailers through the shared FSM brings
them under the same validation as request headers: field names must be RFC 9110
tchar tokens, and field values must satisfy `isValueCharacter` (bare CR/LF, NUL,
and other controls rejected). This is a deliberate, approved change — a small
hardening and a consistency win.

Inputs that the lenient parser accepted and the strict FSM rejects (e.g. a
non-token byte in a trailer name, or a control byte in a trailer value) will now
raise a `ParseException` instead of being stored or silently dropped. The newly
rejected inputs will be documented in the trailer tests.

## Lowercasing

All ASCII-token lowercasing touched by this work uses `HTTPTools.asciiLowerCase`
rather than `String.toLowerCase(Locale.ROOT)`. `asciiLowerCase` scans once and
returns the input unchanged when it is already lowercase ASCII (the common case
for header, trailer, and parameter names), falling back to
`toLowerCase(Locale.ROOT)` only when an uppercase or non-ASCII character is
present. Observable behavior is therefore identical to today for every input,
well-formed or malformed — the only difference is that the already-lowercase fast
path allocates nothing. This is a pure allocation win, in keeping with a parser
whose reason to exist is performance.

Conversion sites, all behavior-preserving:

- **Multipart header name** — `MultipartStream.java:244`
  (`build.toString().toLowerCase(Locale.ROOT)`), which becomes the multipart
  consumer's name handling.
- **Trailer name** — `ChunkedInputStream.java:199`
  (`...trim().toLowerCase(Locale.ROOT)`), which becomes the trailer consumer.
- **Header-value parameter names** — `HTTPTools.parseHeaderParameter`
  (`HTTPTools.java:565,568`), reached from `parseHeaderValue` on the multipart
  path. These are ASCII parameter tokens (e.g. `name`, `filename`, `charset`) and
  switch to `asciiLowerCase` for the same reason.

Out of scope: the content-type lowercasing in `HTTPTools.getMaxRequestBodySize`
(`HTTPTools.java:65`) is request-body-size negotiation, not field-block parsing,
and is left as-is to keep this change focused on the parser.

## Conventions & placement

- `HTTPFieldParser` and `FieldConsumer` are new files and take the MIT/SPDX header
  (`Copyright (c) 2026 The Latte Project`). `RequestPreambleState`, `HTTPTools`,
  `MultipartStream`, and `ChunkedInputStream` are inherited FusionAuth files and
  keep their Apache-2.0 headers; their year ranges are bumped on substantive edits.
- `FieldState` lives nested-private inside `HTTPFieldParser` — it is an
  implementation detail with no external consumers, unlike the public
  `RequestPreambleState`.
- Acronym casing (`HTTPFieldParser`), import grouping, alphabetized members, and
  bracketed runtime values in exception messages all follow the project
  conventions in `.claude/rules/`.

## Verification

**Correctness.** The following must stay green, unchanged except for the trailer
strictness cases: `RequestPreambleConformanceTest`, `MultipartStreamTest`,
`ChunkedInputStreamTest`, `ChunkedInputStreamTrailersTest`,
`HTTPRequestTrailersAPITest`, `HTTPResponseTrailersAPITest`, `HTTPToolsTest`. New
tests cover: a field block fed across several small `feed` slices (resumability),
size-limit enforcement via `bytesConsumed()`, and the trailer inputs newly
rejected by the strict FSM.

**Performance.** The existing `performance` / `performanceNoKeepAlive` tests are
coarse end-to-end round-trip timers with no threshold assertions — full-network
cost dominates, so they cannot detect a few-percent parser delta. The design adds
a focused in-process micro-benchmark (warm-up + timed loop reporting `ns/op`)
that parses a representative preamble, a multipart header block, and a trailer
block, run before and after the change to prove neutrality on the preamble and
improvement on the other two. The harness is hand-rolled and tagged
`groups = "performance"`, matching the project's zero-dependency style and the
existing perf-test convention, rather than adding JMH as a test dependency.

## Open questions

None blocking. Two choices were settled during design:

- **Micro-benchmark harness:** hand-rolled in-process loop (no new dependency)
  rather than JMH.
- **`FieldState` placement:** nested-private inside `HTTPFieldParser` rather than a
  standalone public enum.

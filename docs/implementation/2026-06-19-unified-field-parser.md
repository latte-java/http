# Unified HTTP Field-Block Parser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse HTTP/1.1 request headers, chunked trailers, and multipart part headers through one shared, resumable field-block parser without regressing the hot request-header path.

**Architecture:** Introduce a push/feed engine, `HTTPFieldParser`, that owns the field-block FSM and `byte[]` accumulation and emits each `(name, value)` through a `FieldConsumer` callback. Each of the three callers feeds it slices from its own buffer. The request line keeps its own (reduced) `RequestPreambleState` FSM and hands the header block to the field parser.

**Tech Stack:** Java 21, TestNG, the Latte build tool (`latte`). Zero production dependencies.

**Design doc:** `docs/design/2026-06-19-unified-field-parser-design.md`

## Global Constraints

- Java 21; build with `latte`, not Maven/Gradle. Tests are TestNG.
- Run a single test class with `latte test --test=ClassName`. Run the focused subset for a task with the commands given in that task.
- New files use the MIT/SPDX header `Copyright (c) 2026 The Latte Project` + `SPDX-License-Identifier: MIT`. Inherited FusionAuth files (`HTTPTools`, `RequestPreambleState`, `MultipartStream`, `ChunkedInputStream`) keep their Apache-2.0 header; bump the year range to `2022-2026` on substantive edits.
- Acronyms are fully uppercase in identifiers (`HTTPFieldParser`). Alphabetize members within a visibility group. Prefer `import module …` over class imports. No blank lines between fields.
- Runtime values in exception/log messages are wrapped in `[brackets]`, never quotes.
- All ASCII-token lowercasing introduced or touched uses `HTTPTools.asciiLowerCase`, never `String.toLowerCase(Locale.ROOT)`.
- `HTTPFieldParser` and `FieldConsumer` live in the exported `org.lattejava.http.util` package; `org.lattejava.http.io` and `org.lattejava.http.util` files reach them via the existing `import module org.lattejava.http` (no class import needed).
- `ParseException` is an unchecked `RuntimeException`. `feed` may throw it from the FSM; callers do not need a `throws` clause for it.

---

## Task 1: Field-parsing micro-benchmark + baseline

Establish a repeatable measurement of the three parse paths **against the current code**, so later tasks can prove no regression. This is the perf gate referenced by every migration task. The request-preamble measurement is the sensitive one (parseRequestPreamble is almost entirely field-block parsing); the multipart and trailer measurements are coarser end-to-end sanity checks.

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/perf/FieldParsingBenchmarkTest.java`

**Interfaces:**
- Consumes: `HTTPTools.parseRequestPreamble`, `MultipartStream.process`, `ChunkedInputStream.readAllBytes` (all existing public APIs).
- Produces: a `performance`-group TestNG test printing `ns/op` for `preamble`, `multipart-headers`, and `trailers`.

- [ ] **Step 1: Write the benchmark**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.perf;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.io.PushbackInputStream;

/**
 * Hand-rolled relative micro-benchmark for the three HTTP/1.1 field-block parse paths. Not a pass/fail gate — it prints
 * {@code ns/op} so a human can compare a run before a change against a run after it. Tagged {@code performance} so the
 * normal CI build (which passes {@code --excludePerformance}) skips it.
 */
public class FieldParsingBenchmarkTest {
  private static final int Iterations = 2_000_000;
  private static final int Warmup = 200_000;

  @Test(groups = "performance")
  public void benchmark() throws Exception {
    benchPreamble();
    benchMultipartHeaders();
    benchTrailers();
  }

  private void benchPreamble() throws Exception {
    byte[] preamble = ("GET /some/representative/path?with=query HTTP/1.1\r\n" +
        "Host: example.org\r\n" +
        "User-Agent: latte-bench/1.0\r\n" +
        "Accept: text/html,application/xhtml+xml\r\n" +
        "Accept-Encoding: gzip, deflate, br\r\n" +
        "Accept-Language: en-US,en;q=0.9\r\n" +
        "Connection: keep-alive\r\n" +
        "Cache-Control: no-cache\r\n" +
        "Cookie: session=abcdef0123456789; theme=dark\r\n" +
        "\r\n").getBytes(StandardCharsets.US_ASCII);
    byte[] requestBuffer = new byte[4096];
    long sink = 0;

    for (int i = 0; i < Warmup; i++) {
      sink += parseOnce(preamble, requestBuffer);
    }

    long start = System.nanoTime();
    for (int i = 0; i < Iterations; i++) {
      sink += parseOnce(preamble, requestBuffer);
    }
    long elapsed = System.nanoTime() - start;
    report("preamble", elapsed, sink);
  }

  private long parseOnce(byte[] preamble, byte[] requestBuffer) throws Exception {
    var in = new PushbackInputStream(new ByteArrayInputStream(preamble), null);
    var request = new HTTPRequest();
    HTTPTools.parseRequestPreamble(in, -1, request, requestBuffer, () -> {
    });
    String ua = request.getHeader("user-agent");
    return request.getPath().length() + (ua == null ? 0 : ua.length());
  }

  private void benchMultipartHeaders() throws Exception {
    String boundary = "----LatteBenchBoundary";
    StringBuilder body = new StringBuilder();
    for (int p = 0; p < 8; p++) {
      body.append("--").append(boundary).append("\r\n")
          .append("Content-Disposition: form-data; name=\"field").append(p).append("\"\r\n")
          .append("Content-Type: text/plain; charset=UTF-8\r\n")
          .append("X-Custom-Header: some-value-").append(p).append("\r\n")
          .append("\r\n")
          .append("value-").append(p).append("\r\n");
    }
    body.append("--").append(boundary).append("--\r\n");
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    Path tempDir = Files.createTempDirectory("latte-bench");
    var config = new MultipartConfiguration();
    var fileManager = new DefaultMultipartFileManager(tempDir, config.getTemporaryFilenamePrefix(), config.getTemporaryFilenameSuffix());
    long sink = 0;

    for (int i = 0; i < Warmup; i++) {
      sink += multipartOnce(bytes, boundary, fileManager, config);
    }

    long start = System.nanoTime();
    for (int i = 0; i < Iterations; i++) {
      sink += multipartOnce(bytes, boundary, fileManager, config);
    }
    long elapsed = System.nanoTime() - start;
    report("multipart-headers", elapsed, sink);
  }

  private long multipartOnce(byte[] bytes, String boundary, MultipartFileManager fileManager, MultipartConfiguration config) throws Exception {
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new ArrayList<>();
    new MultipartStream(new ByteArrayInputStream(bytes), boundary.getBytes(StandardCharsets.UTF_8), fileManager, config)
        .process(parameters, files);
    return parameters.size();
  }

  private void benchTrailers() throws Exception {
    byte[] wire = ("5\r\nhello\r\n" +
        "0\r\n" +
        "X-Checksum: abc123def456\r\n" +
        "X-Trace-Id: 0123456789abcdef\r\n" +
        "X-Span-Id: fedcba9876543210\r\n" +
        "\r\n").getBytes(StandardCharsets.US_ASCII);
    long sink = 0;

    for (int i = 0; i < Warmup; i++) {
      sink += trailersOnce(wire);
    }

    long start = System.nanoTime();
    for (int i = 0; i < Iterations; i++) {
      sink += trailersOnce(wire);
    }
    long elapsed = System.nanoTime() - start;
    report("trailers", elapsed, sink);
  }

  private long trailersOnce(byte[] wire) throws Exception {
    var pushback = new PushbackInputStream(new ByteArrayInputStream(wire), null);
    var chunked = new ChunkedInputStream(pushback, 1024, 1_000_000);
    chunked.readAllBytes();
    return chunked.getTrailers().size();
  }

  private void report(String label, long elapsedNanos, long sink) {
    double nsPerOp = elapsedNanos / (double) Iterations;
    System.out.printf("[bench] %-18s %8.2f ns/op  (sink=%d)%n", label, nsPerOp, sink);
  }
}
```

- [ ] **Step 2: Run the benchmark and confirm it prints three lines**

Run: `latte test --test=FieldParsingBenchmarkTest`
Expected: PASS, with three `[bench] …` lines in the output (`preamble`, `multipart-headers`, `trailers`), each showing a `ns/op` figure.

- [ ] **Step 3: Record the baseline**

Copy the three `ns/op` figures into this task's notes (and the commit message below). These are the **pre-refactor baseline** that Tasks 3, 4, 5, and 8 compare against. Run it 2–3 times and keep the median; a hand-rolled benchmark is noisy, so treat anything within ±5% as "neutral."

- [ ] **Step 4: Commit**

```bash
git add src/test/java/org/lattejava/http/tests/perf/FieldParsingBenchmarkTest.java
git commit -m "test(perf): add field-block parsing micro-benchmark

Baseline (median ns/op, current implementation):
  preamble          <value>
  multipart-headers <value>
  trailers          <value>

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `FieldConsumer` + `HTTPFieldParser` engine

Build the shared engine and its callback as new files, fully unit-tested, touching no existing code. The FSM duplicates the header-state grammar from `RequestPreambleState` exactly (request-header states only); `RequestPreambleState` is reduced to use it later, in Task 7.

**Files:**
- Create: `src/main/java/org/lattejava/http/util/FieldConsumer.java`
- Create: `src/main/java/org/lattejava/http/util/HTTPFieldParser.java`
- Test: `src/test/java/org/lattejava/http/tests/util/HTTPFieldParserTest.java`

**Interfaces:**
- Produces:
  - `interface FieldConsumer { void field(String name, String value); }`
  - `final class HTTPFieldParser` with `int feed(byte[] buffer, int offset, int length, FieldConsumer consumer)`, `boolean isComplete()`, `long bytesConsumed()`.
  - Semantics: parses `field-name ":" OWS field-value CRLF` lines; a bare CRLF completes the block. Emits `(name, value)` once per **non-empty-value** field, at the value→CR transition (empty values are dropped, matching the current request-preamble parser). Resumable across `feed` calls.

- [ ] **Step 1: Write the failing test**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.util;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.util.FieldConsumer;
import org.lattejava.http.util.HTTPFieldParser;

import static org.testng.Assert.*;

public class HTTPFieldParserTest {
  private List<String> collect(byte[] bytes, int chunk) {
    var out = new ArrayList<String>();
    FieldConsumer consumer = (name, value) -> out.add(name + "=" + value);
    var parser = new HTTPFieldParser();
    int offset = 0;
    while (!parser.isComplete() && offset < bytes.length) {
      int len = Math.min(chunk, bytes.length - offset);
      offset += parser.feed(bytes, offset, len, consumer);
      if (len == 0) {
        break;
      }
    }
    return out;
  }

  @Test
  public void parsesFieldsInOneFeed() {
    byte[] bytes = "Host: example.org\r\nX-A: 1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of("Host=example.org", "X-A=1"));
  }

  @Test
  public void isCompleteAfterBlankLine() {
    byte[] bytes = "Host: example.org\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    var parser = new HTTPFieldParser();
    int consumed = parser.feed(bytes, 0, bytes.length, (n, v) -> {
    });
    assertTrue(parser.isComplete());
    assertEquals(consumed, bytes.length);
    assertEquals(parser.bytesConsumed(), bytes.length);
  }

  @Test
  public void resumesAcrossTinyFeeds() {
    byte[] bytes = "Host: example.org\r\nX-A: 1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, 1), List.of("Host=example.org", "X-A=1"));
  }

  @Test
  public void dropsEmptyValues() {
    // Matches the request-preamble parser: a value-less field is not emitted.
    byte[] bytes = "X-Empty:\r\nX-Full: y\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of("X-Full=y"));
  }

  @Test
  public void skipsOptionalLeadingSpaceButKeepsTrailing() {
    byte[] bytes = "X-A:    spaced   \r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of("X-A=spaced   "));
  }

  @Test
  public void emptyBlockIsImmediatelyComplete() {
    byte[] bytes = "\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of());
  }

  @Test(expectedExceptions = ParseException.class)
  public void rejectsNonTokenInName() {
    byte[] bytes = "Bad Name: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    new HTTPFieldParser().feed(bytes, 0, bytes.length, (n, v) -> {
    });
  }

  @Test(expectedExceptions = ParseException.class)
  public void rejectsControlByteInValue() {
    byte[] bytes = "X-A: a\u0007b\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    new HTTPFieldParser().feed(bytes, 0, bytes.length, (n, v) -> {
    });
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `latte test --test=HTTPFieldParserTest`
Expected: FAIL to compile — `HTTPFieldParser` and `FieldConsumer` do not exist yet.

- [ ] **Step 3: Create `FieldConsumer`**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.util;

/**
 * Receives one parsed HTTP field (header, trailer, or multipart part header) from {@link HTTPFieldParser}. The parser
 * stays policy-neutral; each caller's lowercasing, trimming, and filtering live in its consumer.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface FieldConsumer {
  /**
   * Accepts a completed field.
   *
   * @param name  The field name, exactly as received (not lowercased).
   * @param value The field value, with optional leading whitespace after the colon already stripped.
   */
  void field(String name, String value);
}
```

- [ ] **Step 4: Create `HTTPFieldParser`**

```java
/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.util;

import module java.base;

/**
 * Resumable finite-state-machine parser for an HTTP/1.1 field block — a sequence of
 * {@code field-name ":" OWS field-value CRLF} lines terminated by a bare CRLF. Shared by the request-header,
 * chunked-trailer, and multipart part-header parse paths.
 *
 * <p>The grammar and character classes match the header states of {@link RequestPreambleState} exactly. A field is
 * emitted to the {@link FieldConsumer} once per non-empty value, at the value-to-CR transition; a field with an empty
 * value never enters the {@code Value} state and is therefore dropped, mirroring the request-preamble parser.
 *
 * <p>The parser is fed buffer slices via {@link #feed}; its state and partial name/value accumulators persist across
 * calls, so a field — or the whole block — may span any number of feeds. The caller owns the buffer and the read loop.
 *
 * @author Brian Pontarelli
 */
public final class HTTPFieldParser {
  private byte[] nameBuffer = new byte[64];
  private int nameLength;
  private FieldState state = FieldState.Start;
  private byte[] valueBuffer = new byte[256];
  private int valueLength;
  private long bytesConsumed;

  /**
   * @return The total number of bytes consumed across all {@link #feed} calls. Used by the request-preamble path to
   *     enforce the maximum header size.
   */
  public long bytesConsumed() {
    return bytesConsumed;
  }

  /**
   * Drives the FSM over {@code buffer[offset, offset + length)}, emitting each completed field to {@code consumer}.
   * Stops at the terminating bare CRLF ({@link #isComplete()} becomes {@code true}) or at the end of the slice.
   *
   * @param buffer   The source buffer.
   * @param offset   The start index to read from.
   * @param length   The number of bytes available from {@code offset}.
   * @param consumer The sink for completed fields.
   * @return The number of bytes consumed from the slice.
   */
  public int feed(byte[] buffer, int offset, int length, FieldConsumer consumer) {
    int index = offset;
    int end = offset + length;
    FieldState current = state;
    for (; index < end && current != FieldState.Complete; index++) {
      byte ch = buffer[index];
      FieldState next = current.next(ch);
      if (next != current) {
        // The only transition out of Value is Value -> FieldCR, so this fires exactly once per non-empty field.
        if (current == FieldState.Value) {
          consumer.field(new String(nameBuffer, 0, nameLength, StandardCharsets.UTF_8),
              new String(valueBuffer, 0, valueLength, StandardCharsets.UTF_8));
        }
        // Seed the destination buffer when entering a storing state. The buffers are never zero-length, so the first
        // write after the reset needs no bounds check.
        if (next == FieldState.Name) {
          nameLength = 0;
          nameBuffer[nameLength++] = ch;
        } else if (next == FieldState.Value) {
          valueLength = 0;
          valueBuffer[valueLength++] = ch;
        }
      } else if (current == FieldState.Name) {
        if (nameLength == nameBuffer.length) {
          nameBuffer = Arrays.copyOf(nameBuffer, nameBuffer.length * 2);
        }
        nameBuffer[nameLength++] = ch;
      } else if (current == FieldState.Value) {
        if (valueLength == valueBuffer.length) {
          valueBuffer = Arrays.copyOf(valueBuffer, valueBuffer.length * 2);
        }
        valueBuffer[valueLength++] = ch;
      }
      current = next;
    }
    state = current;
    int consumed = index - offset;
    bytesConsumed += consumed;
    return consumed;
  }

  /**
   * @return {@code true} once the terminating bare CRLF has been consumed.
   */
  public boolean isComplete() {
    return state == FieldState.Complete;
  }

  private enum FieldState {
    Start {
      @Override
      FieldState next(byte ch) {
        if (ch == '\r') {
          return BlockCR;
        } else if (HTTPTools.isTokenCharacter(ch)) {
          return Name;
        }
        throw HTTPTools.makeParseException(ch, this);
      }
    },
    Name {
      @Override
      FieldState next(byte ch) {
        if (HTTPTools.isTokenCharacter(ch)) {
          return Name;
        } else if (ch == ':') {
          return Colon;
        }
        throw HTTPTools.makeParseException(ch, this);
      }
    },
    Colon {
      @Override
      FieldState next(byte ch) {
        // Only SP is optional whitespace here, matching RequestPreambleState.HeaderColon; a leading HTAB is a value char
        // and begins the value.
        if (ch == ' ') {
          return Colon;
        } else if (ch == '\r') {
          return FieldCR;
        } else if (HTTPTools.isValueCharacter(ch)) {
          return Value;
        }
        throw HTTPTools.makeParseException(ch, this);
      }
    },
    Value {
      @Override
      FieldState next(byte ch) {
        if (ch == '\r') {
          return FieldCR;
        } else if (HTTPTools.isValueCharacter(ch)) {
          return Value;
        }
        throw HTTPTools.makeParseException(ch, this);
      }
    },
    FieldCR {
      @Override
      FieldState next(byte ch) {
        if (ch == '\n') {
          return Start;
        }
        throw HTTPTools.makeParseException(ch, this);
      }
    },
    BlockCR {
      @Override
      FieldState next(byte ch) {
        if (ch == '\n') {
          return Complete;
        }
        throw HTTPTools.makeParseException(ch, this);
      }
    },
    Complete {
      @Override
      FieldState next(byte ch) {
        return Complete;
      }
    };

    abstract FieldState next(byte ch);
  }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `latte test --test=HTTPFieldParserTest`
Expected: PASS (all eight test methods).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/util/FieldConsumer.java src/main/java/org/lattejava/http/util/HTTPFieldParser.java src/test/java/org/lattejava/http/tests/util/HTTPFieldParserTest.java
git commit -m "feat(util): add resumable HTTPFieldParser and FieldConsumer

Shared field-block FSM with byte[] accumulation, fed buffer slices and
emitting (name, value) per non-empty field. Mirrors the RequestPreambleState
header grammar exactly; not yet wired to any caller.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Migrate the request preamble onto `HTTPFieldParser`

Rewire `HTTPTools.parseRequestPreamble` to drive `RequestPreambleState` only through the request line, then feed the rest of the preamble to `HTTPFieldParser`. This is a behavior-preserving refactor of the hot path; the existing preamble tests are the safety net.

**Files:**
- Modify: `src/main/java/org/lattejava/http/util/HTTPTools.java:329-402` (`parseRequestPreamble`)
- Bump header year range to `2022-2026`.

**Interfaces:**
- Consumes: `HTTPFieldParser.feed/isComplete/bytesConsumed`, `FieldConsumer`, `request::addHeader`.
- Produces: unchanged `parseRequestPreamble` signature.

- [ ] **Step 1: Establish the green baseline**

Run: `latte test --test=RequestPreambleConformanceTest --test=CoreTest`
Expected: PASS. (If `CoreTest` is slow, `latte test --test=RequestPreambleConformanceTest` alone is the focused safety net; run `CoreTest` once before and once after.)

- [ ] **Step 2: Replace the body of `parseRequestPreamble`**

Replace the method (currently `HTTPTools.java:329-402`) with:

```java
  public static void parseRequestPreamble(PushbackInputStream inputStream, int maxRequestHeaderSize, HTTPRequest request,
                                          byte[] requestBuffer, Runnable readObserver)
      throws IOException {
    RequestPreambleState state = RequestPreambleState.RequestMethod;
    // Local byte[]+int for the request-line tokens (method, path, protocol). The header block is accumulated separately
    // by the HTTPFieldParser below.
    byte[] valueBuffer = new byte[512];
    int valueLen = 0;

    HTTPFieldParser fieldParser = null;
    FieldConsumer consumer = request::addHeader;

    int read = 0;
    int index = 0;
    int preambleLength = 0;

    while (fieldParser == null || !fieldParser.isComplete()) {
      long start = System.currentTimeMillis();
      read = inputStream.read(requestBuffer);

      // We have not yet reached the end of the preamble. If there are no more bytes to read, the connection must have been closed by the client.
      if (read < 0) {
        long waited = System.currentTimeMillis() - start;
        throw new ConnectionClosedException(String.format("Read returned [%d] after waiting [%d] ms", read, waited));
      }

      logger.trace("Read [{}] from client for preamble.", read);

      // Tell the callback that we've read at least one byte
      if (preambleLength == 0) {
        readObserver.run();
      }

      index = 0;

      // Phase 1: the request line. Drive RequestPreambleState until the request-line CRLF has been consumed
      // (state == RequestLF), storing the method, path, and protocol as each token ends.
      if (fieldParser == null) {
        for (; index < read && state != RequestPreambleState.RequestLF; index++) {
          byte ch = requestBuffer[index];
          RequestPreambleState nextState = state.next(ch);
          if (nextState != state) {
            switch (state) {
              case RequestMethod ->
                  request.setMethod(HTTPMethod.of(new String(valueBuffer, 0, valueLen, StandardCharsets.UTF_8)));
              case RequestPath -> request.setPath(new String(valueBuffer, 0, valueLen, StandardCharsets.UTF_8));
              case RequestProtocol -> request.setProtocol(new String(valueBuffer, 0, valueLen, StandardCharsets.UTF_8));
              default -> {
                // Non-storing request-line states (the SP and CR states) have nothing to flush.
              }
            }

            if (nextState.store()) {
              valueLen = 0;
              valueBuffer[valueLen++] = ch;
            }
          } else if (state.store()) {
            if (valueLen == valueBuffer.length) {
              valueBuffer = Arrays.copyOf(valueBuffer, valueBuffer.length * 2);
            }
            valueBuffer[valueLen++] = ch;
          }

          state = nextState;
        }

        // The request line is done once we reach RequestLF; hand the header block to the shared field parser.
        if (state == RequestPreambleState.RequestLF) {
          fieldParser = new HTTPFieldParser();
        }
      }

      // Phase 2: the header field block.
      if (fieldParser != null) {
        index += fieldParser.feed(requestBuffer, index, read - index, consumer);
      }

      // index is the number of bytes we processed as part of the preamble this iteration.
      preambleLength += index;
      if (maxRequestHeaderSize != -1 && preambleLength > maxRequestHeaderSize) {
        throw new RequestHeadersTooLargeException(maxRequestHeaderSize, "The maximum size of the request header has been exceeded. The maximum size is [" + maxRequestHeaderSize + "] bytes.");
      }
    }

    // Push back the leftover bytes (the start of the body, or the next pipelined request).
    if (index < read) {
      inputStream.push(requestBuffer, index, read - index);
    }
  }
```

- [ ] **Step 3: Bump the copyright year range**

In `HTTPTools.java`, change the header line `Copyright (c) 2022-2025, FusionAuth, All Rights Reserved` to `Copyright (c) 2022-2026, FusionAuth, All Rights Reserved`. Leave the Apache-2.0 body unchanged.

- [ ] **Step 4: Run the preamble safety net**

Run: `latte test --test=RequestPreambleConformanceTest --test=CoreTest`
Expected: PASS, unchanged from Step 1.

- [ ] **Step 5: Re-run the benchmark and compare the preamble figure**

Run: `latte test --test=FieldParsingBenchmarkTest`
Expected: the `preamble` `ns/op` is within ±5% of the Task 1 baseline (neutral). If it has regressed materially, stop and investigate before committing — this is the hard constraint.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/util/HTTPTools.java
git commit -m "refactor(util): parse request headers via HTTPFieldParser

parseRequestPreamble now drives RequestPreambleState only through the
request line, then feeds the header block to the shared HTTPFieldParser.
Behavior-preserving; preamble conformance and core tests unchanged,
preamble benchmark neutral.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Migrate multipart part headers onto `HTTPFieldParser`

Replace `MultipartStream.readHeaders`' byte-at-a-time `RequestPreambleState` drive and `StringBuilder` with `HTTPFieldParser.feed` over the multipart buffer. The multipart consumer lowercases the name via `asciiLowerCase` and runs `parseHeaderValue`.

**Files:**
- Modify: `src/main/java/org/lattejava/http/io/MultipartStream.java:227-260` (`readHeaders`)
- Bump header year range to `2022-2026`.

**Interfaces:**
- Consumes: `HTTPFieldParser`, `FieldConsumer`, `HTTPTools.asciiLowerCase`, `HTTPTools.parseHeaderValue`, the existing `reload(int)`, `buffer`, `current`, `end`.
- Produces: unchanged `readHeaders(Map<String, HTTPTools.HeaderValue>)` contract.

- [ ] **Step 1: Establish the green baseline**

Run: `latte test --test=MultipartStreamTest --test=FormDataTest`
Expected: PASS.

- [ ] **Step 2: Replace `readHeaders`**

Replace the method (currently `MultipartStream.java:227-260`) with:

```java
  /**
   * Processes the part headers using the shared {@link HTTPFieldParser}, feeding it directly from the multipart buffer
   * and reloading as needed. Header names are lower-cased with {@link HTTPTools#asciiLowerCase}; values are parsed into
   * {@link HTTPTools.HeaderValue} via {@link HTTPTools#parseHeaderValue}.
   *
   * @param headers The map to populate with the part's headers.
   * @throws IOException    If any I/O operation failed.
   * @throws ParseException If the input is not a proper multipart body and could not be processed.
   */
  private void readHeaders(Map<String, HTTPTools.HeaderValue> headers) throws IOException, ParseException {
    HTTPFieldParser parser = new HTTPFieldParser();
    FieldConsumer consumer = (name, value) -> headers.put(HTTPTools.asciiLowerCase(name), HTTPTools.parseHeaderValue(value));

    while (!parser.isComplete()) {
      if (current == end) {
        if (!reload(1)) {
          throw new ParseException("Invalid multipart body. Ran out of data while processing.");
        }
      }

      current += parser.feed(buffer, current, end - current, consumer);
    }
  }
```

- [ ] **Step 3: Bump the copyright year range**

In `MultipartStream.java`, change `Copyright (c) 2022-2025, FusionAuth, All Rights Reserved` to `Copyright (c) 2022-2026, FusionAuth, All Rights Reserved`.

- [ ] **Step 4: Run the multipart safety net**

Run: `latte test --test=MultipartStreamTest --test=FormDataTest`
Expected: PASS. Note one intentional nuance: a part with **zero** headers no longer throws an "Unexpected character" `ParseException` inside header parsing — it parses as an empty header block and then fails in `readPart` with the existing "missing a [Content-Disposition] header" `ParseException`. Both are `ParseException`; if any test asserts the *old* message for a header-less part, update its expected message to the Content-Disposition one. If no test exercises that case, nothing changes.

- [ ] **Step 5: Re-run the benchmark and compare the multipart figure**

Run: `latte test --test=FieldParsingBenchmarkTest`
Expected: `multipart-headers` `ns/op` is ≤ the Task 1 baseline (it should improve — `StringBuilder` and byte-at-a-time reads are gone). It must not regress.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/lattejava/http/io/MultipartStream.java
git commit -m "refactor(io): parse multipart part headers via HTTPFieldParser

readHeaders now feeds the shared parser from the multipart buffer instead
of driving RequestPreambleState a byte at a time into a StringBuilder.
Names lower-cased with asciiLowerCase. Multipart tests green, benchmark
improved.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Migrate chunked trailers onto `HTTPFieldParser` (strict)

Replace `ChunkedInputStream.parseTrailers` / `addTrailerLine` and their single-byte reads with `HTTPFieldParser.feed` over the stream's own buffer. This brings trailers under the same strict FSM as request headers — a deliberate, approved behavior change: malformed trailer names/values that the lenient line parser accepted now raise `ParseException`.

**Files:**
- Modify: `src/main/java/org/lattejava/http/io/ChunkedInputStream.java` — the `chunkSize == 0` block (`:134-142`), and replace `parseTrailers`/`addTrailerLine` (`:193-237`).
- Bump header year range to `2022-2026`.
- Test: `src/test/java/org/lattejava/http/tests/io/ChunkedInputStreamTrailersTest.java` (add strict cases).

**Interfaces:**
- Consumes: `HTTPFieldParser`, `FieldConsumer`, `HTTPTools.asciiLowerCase`, `HTTPValues.ForbiddenTrailers.Names`, the existing `buffer`, `bufferIndex`, `bufferLength`, `pushBackOverReadBytes()`.
- Produces: unchanged `getTrailers()` contract for well-formed input.

- [ ] **Step 1: Write the failing strict-validation tests**

Append these methods to `ChunkedInputStreamTrailersTest`:

```java
  @Test(expectedExceptions = ParseException.class)
  public void strict_rejects_non_token_trailer_name() throws Exception {
    // A space in the trailer name is not a tchar; the strict FSM rejects it.
    String wire = "5\r\nhello\r\n0\r\nBad Name: x\r\n\r\n";
    var pushback = new PushbackInputStream(new ByteArrayInputStream(wire.getBytes()), null);
    new ChunkedInputStream(pushback, 1024, 1_000_000).readAllBytes();
  }

  @Test(expectedExceptions = ParseException.class)
  public void strict_rejects_control_byte_in_trailer_value() throws Exception {
    String wire = "5\r\nhello\r\n0\r\nX-A: a\u0007b\r\n\r\n";
    var pushback = new PushbackInputStream(new ByteArrayInputStream(wire.getBytes()), null);
    new ChunkedInputStream(pushback, 1024, 1_000_000).readAllBytes();
  }

  @Test
  public void trailers_split_across_buffer_reloads() throws Exception {
    // A 1-byte buffer forces the trailer block to be fed in many tiny slices; resumability must still work.
    String wire = "5\r\nhello\r\n0\r\nX-Checksum: abc123\r\nX-Other: 42\r\n\r\n";
    var pushback = new PushbackInputStream(new ByteArrayInputStream(wire.getBytes()), null);
    var chunked = new ChunkedInputStream(pushback, 1, 1_000_000);
    assertEquals(new String(chunked.readAllBytes()), "hello");
    assertEquals(chunked.getTrailers().get("x-checksum"), List.of("abc123"));
    assertEquals(chunked.getTrailers().get("x-other"), List.of("42"));
  }
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `latte test --test=ChunkedInputStreamTrailersTest`
Expected: the three new methods FAIL (the lenient parser accepts the malformed input and does not throw; the 1-byte-buffer case may also misbehave), while the three original methods still PASS.

- [ ] **Step 3: Replace the `chunkSize == 0` trailer handoff**

In `read(...)`, replace the current block (`ChunkedInputStream.java:134-142`):

```java
          // A chunk size of 0 indicates this is the terminating chunk. Push back any over-read bytes so parseTrailers
          // sees the full byte stream from the start of the trailer section (or the bare CRLF if there are none).
          if (chunkSize == 0) {
            if (bufferIndex < bufferLength) {
              delegate.push(buffer, bufferIndex, bufferLength - bufferIndex);
              bufferIndex = bufferLength;
            }
            parseTrailers(delegate);
            state = ChunkedBodyState.Complete;
            break;
          }
```

with:

```java
          // A chunk size of 0 indicates this is the terminating chunk. Parse the trailer section directly from our own
          // buffer (feeding the shared field parser and reloading from the delegate as needed).
          if (chunkSize == 0) {
            parseTrailers();
            state = ChunkedBodyState.Complete;
            break;
          }
```

- [ ] **Step 4: Replace `parseTrailers` and `addTrailerLine`**

Replace both methods (`ChunkedInputStream.java:193-237`) with:

```java
  private void addTrailer(String name, String value) {
    // The FSM guarantees a non-empty token name; case-fold for lookup and storage.
    String lower = HTTPTools.asciiLowerCase(name);
    if (HTTPValues.ForbiddenTrailers.Names.contains(lower)) {
      // RFC 9110 §6.5.2 forbidden trailers are silently dropped.
      return;
    }

    if (trailers == null) {
      trailers = new HashMap<>();
    }

    trailers.computeIfAbsent(lower, k -> new ArrayList<>()).add(value.trim());
  }

  private void parseTrailers() throws IOException {
    // RFC 9112 §7.1.2: trailer-fields use the same syntax as header-fields. Feed the shared parser from our own buffer,
    // reloading from the delegate when it drains, until the bare CRLF that ends the trailer section.
    HTTPFieldParser parser = new HTTPFieldParser();
    FieldConsumer consumer = this::addTrailer;

    while (!parser.isComplete()) {
      if (bufferIndex >= bufferLength) {
        bufferIndex = 0;
        bufferLength = delegate.read(buffer);
        if (bufferLength < 0) {
          throw new ParseException("Unexpected end of stream while reading chunked trailers.");
        }
      }

      bufferIndex += parser.feed(buffer, bufferIndex, bufferLength - bufferIndex, consumer);
    }

    // Anything past the trailer section's terminating CRLF belongs to the next message; push it back to the delegate.
    pushBackOverReadBytes();
  }
```

Note: `value.trim()` preserves the existing trailer behavior of trimming surrounding whitespace (the FSM already strips a single optional leading space; `trim` handles any trailing whitespace and a leading HTAB).

- [ ] **Step 5: Run the trailer tests**

Run: `latte test --test=ChunkedInputStreamTrailersTest --test=ChunkedInputStreamTest`
Expected: PASS — all original methods plus the three new strict/resumability methods.

- [ ] **Step 6: Bump the copyright year range**

In `ChunkedInputStream.java`, change `Copyright (c) 2022-2025, FusionAuth, All Rights Reserved` to `Copyright (c) 2022-2026, FusionAuth, All Rights Reserved`.

- [ ] **Step 7: Re-run the benchmark and compare the trailers figure**

Run: `latte test --test=FieldParsingBenchmarkTest`
Expected: `trailers` `ns/op` ≤ the Task 1 baseline (single-byte reads and the `ByteArrayOutputStream` are gone). Must not regress.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/lattejava/http/io/ChunkedInputStream.java src/test/java/org/lattejava/http/tests/io/ChunkedInputStreamTrailersTest.java
git commit -m "refactor(io): parse chunked trailers via HTTPFieldParser (strict)

Replaces the ad-hoc single-byte trailer line parser with the shared
HTTPFieldParser fed from the stream buffer. Trailers are now validated by
the same token/value-character rules as request headers; malformed names
and values raise ParseException. Forbidden-trailer dropping and value
trimming preserved.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Route `parseHeaderParameter` lowercasing through `asciiLowerCase`

The last in-scope lowercasing site. `HTTPTools.parseHeaderParameter` lower-cases parameter names with `toLowerCase(Locale.ROOT)`; switch both occurrences to `asciiLowerCase` (behavior-identical, allocation-free on the common already-lowercase path). Reached from `parseHeaderValue` on the multipart path.

**Files:**
- Modify: `src/main/java/org/lattejava/http/util/HTTPTools.java:565,568`

**Interfaces:**
- Consumes: `HTTPTools.asciiLowerCase`.
- Produces: unchanged `parseHeaderValue`/`parseHeaderParameter` contract.

- [ ] **Step 1: Establish the green baseline**

Run: `latte test --test=HTTPToolsTest`
Expected: PASS.

- [ ] **Step 2: Switch both lowercasing calls**

In `parseHeaderParameter` (`HTTPTools.java:565` and `:568`), replace:

```java
        name = new String(chars, start, i - start).toLowerCase(Locale.ROOT);
```

with (both occurrences):

```java
        name = HTTPTools.asciiLowerCase(new String(chars, start, i - start));
```

(The `HTTPTools.java:65` content-type lowercasing in `getMaxRequestBodySize` is out of scope — leave it.)

- [ ] **Step 3: Run the test**

Run: `latte test --test=HTTPToolsTest --test=MultipartStreamTest`
Expected: PASS (behavior is identical).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/lattejava/http/util/HTTPTools.java
git commit -m "perf(util): lower-case header parameter names via asciiLowerCase

Behavior-identical, allocation-free on the common already-lowercase path.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Reduce `RequestPreambleState` to request-line states

With the preamble (Task 3) and multipart (Task 4) paths off the header states, `RequestPreambleState`'s header states (`HeaderName`, `HeaderColon`, `HeaderValue`, `HeaderCR`, `HeaderLF`, `PreambleCR`, `Complete`) are dead. Remove them so the enum names only what it still does, and make `RequestLF` the terminal handoff marker.

**Files:**
- Modify: `src/main/java/org/lattejava/http/util/RequestPreambleState.java`
- Bump header year range to `2022-2026`.

**Interfaces:**
- Produces: `RequestPreambleState` with states `RequestMethod`, `RequestMethodSP`, `RequestPath`, `RequestPathSP`, `RequestProtocol`, `RequestCR`, `RequestLF` only.

- [ ] **Step 1: Verify nothing references the header states**

Run: `grep -rn 'RequestPreambleState\.\(HeaderName\|HeaderColon\|HeaderValue\|HeaderCR\|HeaderLF\|PreambleCR\|Complete\)' src '--include=*.java'`
Expected: no matches (the only remaining `RequestPreambleState` code references are `RequestMethod` and `RequestLF` in `parseRequestPreamble`). If any match appears, stop — a caller still depends on a header state and this task is premature.

- [ ] **Step 2: Edit `RequestLF` to be terminal and delete the header states**

In `RequestLF`, replace its `next` body with a terminal implementation (it is never invoked — `parseRequestPreamble` stops driving the FSM at `RequestLF`):

```java
  RequestLF {
    @Override
    public RequestPreambleState next(byte ch) {
      // Terminal: the request line is complete. parseRequestPreamble hands the header block to HTTPFieldParser and never
      // calls next() on this state.
      return null;
    }

    @Override
    public boolean store() {
      return false;
    }
  };
```

Note the `;` terminating the enum constant list — delete everything from the old `RequestLF`'s original branches through the end of the `Complete` constant (i.e. remove the `HeaderName`, `HeaderColon`, `HeaderValue`, `HeaderCR`, `HeaderLF`, `PreambleCR`, and `Complete` constants entirely). Keep the trailing abstract method declarations:

```java
  public abstract RequestPreambleState next(byte ch);

  public abstract boolean store();
```

- [ ] **Step 3: Bump the copyright year range**

Change `Copyright (c) 2022-2025, FusionAuth, All Rights Reserved` to `Copyright (c) 2022-2026, FusionAuth, All Rights Reserved`.

- [ ] **Step 4: Build and run the preamble + multipart safety nets**

Run: `latte clean build`
Expected: compiles cleanly (no references to the deleted constants).

Run: `latte test --test=RequestPreambleConformanceTest --test=MultipartStreamTest --test=CoreTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/util/RequestPreambleState.java
git commit -m "refactor(util): trim RequestPreambleState to request-line states

The header states are now owned by HTTPFieldParser; remove them and make
RequestLF the terminal request-line marker.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Full verification

Confirm the whole suite is green and the benchmark shows no regression on the hot path.

**Files:** none (verification only).

- [ ] **Step 1: Run the full build and test suite (minus slow groups)**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: BUILD SUCCESS, all tests green.

- [ ] **Step 2: Run the full benchmark and compare against the Task 1 baseline**

Run: `latte test --test=FieldParsingBenchmarkTest`
Expected (median of 2–3 runs):
- `preamble` within ±5% of baseline (neutral — the hard constraint).
- `multipart-headers` ≤ baseline.
- `trailers` ≤ baseline.

Record the final figures. If `preamble` has regressed beyond noise, investigate before considering the work complete.

- [ ] **Step 3: Final commit (if the prior steps produced no changes, skip)**

No code change is expected here; this task is a gate. If investigation in Step 2 required a fix, commit it with a descriptive message.

---

## Self-Review

**Spec coverage** (against `docs/design/2026-06-19-unified-field-parser-design.md`):
- Shared push/feed `HTTPFieldParser` + `FieldConsumer` → Task 2.
- `FieldState` nested-private, header-grammar-identical → Task 2.
- Preamble migration, request-line FSM handoff, `bytesConsumed` size enforcement, pushback → Task 3.
- Multipart migration, `asciiLowerCase` name + `parseHeaderValue` → Task 4.
- Trailer migration, strict FSM behavior change, `asciiLowerCase` + forbidden drop + trim → Task 5.
- `parseHeaderParameter` lowercasing; content-type site explicitly out of scope → Task 6.
- `RequestPreambleState` reduced to request-line states → Task 7.
- Hand-rolled `performance`-group micro-benchmark, no new dependency, preamble as the sensitive gate → Tasks 1 & 8.
- Correctness suites named in the spec are each run by a task (RequestPreamble/Core in 3 & 7, Multipart/FormData in 4, ChunkedTrailers/Chunked in 5, HTTPTools in 6, full suite in 8).

**Placeholder scan:** none — every code step contains complete code; every run step has an exact command and expected result.

**Type consistency:** `feed(byte[], int, int, FieldConsumer) → int`, `isComplete() → boolean`, `bytesConsumed() → long`, and `FieldConsumer.field(String, String)` are used identically in Tasks 2, 3, 4, and 5. `addTrailer(String, String)` matches the `FieldConsumer` shape. `request::addHeader` matches `field(String, String)`.

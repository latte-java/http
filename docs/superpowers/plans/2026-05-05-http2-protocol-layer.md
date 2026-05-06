# HTTP/2 Protocol Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the HTTP/2 protocol-layer primitives — frame codec, HPACK encoder/decoder, stream state machine, and flow-control window primitives — entirely in isolation, with full unit-test coverage. **No socket code, no threading, no public API changes.** This plan produces a library of building blocks that Plan D wires into a working server.

**Architecture:** Each component is a single class with a tight responsibility, all in `org.lattejava.http.server.internal`. Frames are typed records produced/consumed by `HTTP2FrameReader/Writer`. HPACK encodes/decodes (name, value) pairs against a static + dynamic table with Huffman support. `HTTP2Stream` owns the per-stream state machine and window counters. All components are deterministic and pure — no I/O dependencies beyond `InputStream`/`OutputStream` parameters.

**Tech Stack:** Java 21 (records, pattern matching), TestNG, RFC 7541 Appendix C test vectors for HPACK, RFC 9113 §5.1 transitions for state machine.

**Reference spec:** `docs/superpowers/specs/2026-05-05-http2-design.md` (architecture, threading model, settings, error codes, security)

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `src/main/java/org/lattejava/http/server/internal/HTTP2ErrorCode.java` | Create | Enum of RFC 9113 §7 error codes with their numeric values |
| `src/main/java/org/lattejava/http/server/internal/HTTP2Settings.java` | Create | Mutable settings holder + `applyFrame(byte[])` |
| `src/main/java/org/lattejava/http/server/internal/HTTP2Frame.java` | Create | Sealed interface + permitted record subtypes (one per frame type) |
| `src/main/java/org/lattejava/http/server/internal/HTTP2FrameReader.java` | Create | `readFrame(InputStream)` returns a typed `HTTP2Frame`; uses a reusable buffer |
| `src/main/java/org/lattejava/http/server/internal/HTTP2FrameWriter.java` | Create | `writeFrame(OutputStream, HTTP2Frame)` |
| `src/main/java/org/lattejava/http/server/internal/HPACKHuffman.java` | Create | Static code table; `encode(byte[]) -> byte[]`, `decode(byte[]) -> byte[]` |
| `src/main/java/org/lattejava/http/server/internal/HPACKDynamicTable.java` | Create | Ring-buffer-style table sized by `HEADER_TABLE_SIZE` |
| `src/main/java/org/lattejava/http/server/internal/HPACKDecoder.java` | Create | Decodes a HEADERS+CONTINUATION block to an ordered `List<HeaderField>` |
| `src/main/java/org/lattejava/http/server/internal/HPACKEncoder.java` | Create | Encodes an ordered list of (name, value) pairs |
| `src/main/java/org/lattejava/http/server/internal/HTTP2Stream.java` | Create | Per-stream state machine (RFC 9113 §5.1), send/receive window counters |
| `src/main/java/org/lattejava/http/server/internal/HTTPBuffers.java` | Modify | Add `frameReadBuffer`, `frameWriteBuffer`, `headerAccumulationBuffer` |
| `src/test/java/org/lattejava/http/tests/server/HTTP2FrameCodecTest.java` | Create | Round-trip every frame type; malformed inputs |
| `src/test/java/org/lattejava/http/tests/server/HPACKHuffmanTest.java` | Create | Encode/decode RFC 7541 Appendix C string examples |
| `src/test/java/org/lattejava/http/tests/server/HPACKDecoderTest.java` | Create | RFC 7541 Appendix C.2/C.3/C.4 vectors |
| `src/test/java/org/lattejava/http/tests/server/HPACKEncoderTest.java` | Create | Round-trip via decoder; static-table indexing |
| `src/test/java/org/lattejava/http/tests/server/HTTP2StreamStateMachineTest.java` | Create | Every transition in RFC 9113 §5.1 |
| `src/test/java/org/lattejava/http/tests/server/HTTP2FlowControlTest.java` | Create | Window decrement/increment/exhaustion |

---

## Task 1: `HTTP2ErrorCode` enum

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2ErrorCode.java`

- [ ] **Step 1: Write the file**

```java
/*
 * Copyright (c) 2026, Daniel DeGroff, All Rights Reserved
 */
package org.lattejava.http.server.internal;

/**
 * RFC 9113 §7 error codes.
 *
 * @author Daniel DeGroff
 */
public enum HTTP2ErrorCode {
  CANCEL(0x8),
  COMPRESSION_ERROR(0x9),
  CONNECT_ERROR(0xa),
  ENHANCE_YOUR_CALM(0xb),
  FLOW_CONTROL_ERROR(0x3),
  FRAME_SIZE_ERROR(0x6),
  HTTP_1_1_REQUIRED(0xd),
  INADEQUATE_SECURITY(0xc),
  INTERNAL_ERROR(0x2),
  NO_ERROR(0x0),
  PROTOCOL_ERROR(0x1),
  REFUSED_STREAM(0x7),
  SETTINGS_TIMEOUT(0x4),
  STREAM_CLOSED(0x5);

  public final int value;

  HTTP2ErrorCode(int value) {
    this.value = value;
  }

  public static HTTP2ErrorCode of(int value) {
    for (HTTP2ErrorCode code : values()) {
      if (code.value == value) {
        return code;
      }
    }
    return INTERNAL_ERROR;
  }
}
```

- [ ] **Step 2: Compile**

Run: `latte clean build`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2ErrorCode.java
git commit -m "Add HTTP2ErrorCode enum (RFC 9113 §7)"
```

---

## Task 2: `HTTP2Settings` record + applyFrame

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2Settings.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/HTTP2SettingsTest.java`:

```java
/*
 * Copyright (c) 2026, Daniel DeGroff, All Rights Reserved
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2Settings;

import static org.testng.Assert.*;

public class HTTP2SettingsTest {
  @Test
  public void defaults_match_rfc() {
    HTTP2Settings s = HTTP2Settings.defaults();
    assertEquals(s.headerTableSize(), 4096);
    assertEquals(s.enablePush(), 0);
    assertEquals(s.maxConcurrentStreams(), Integer.MAX_VALUE); // RFC default = unlimited
    assertEquals(s.initialWindowSize(), 65535);
    assertEquals(s.maxFrameSize(), 16384);
    assertEquals(s.maxHeaderListSize(), Integer.MAX_VALUE);
  }

  @Test
  public void apply_payload_with_two_settings() {
    // SETTINGS_HEADER_TABLE_SIZE (1) = 8192; SETTINGS_INITIAL_WINDOW_SIZE (4) = 1048576
    byte[] payload = {
        0, 1, 0, 0, 0x20, 0,          // id=1, value=8192
        0, 4, 0, 0x10, 0, 0           // id=4, value=1048576
    };
    HTTP2Settings s = HTTP2Settings.defaults();
    s.applyPayload(payload);
    assertEquals(s.headerTableSize(), 8192);
    assertEquals(s.initialWindowSize(), 1048576);
  }

  @Test
  public void apply_payload_unknown_id_ignored() {
    byte[] payload = {0, 99, 0, 0, 0, 0}; // unknown setting id 99
    HTTP2Settings s = HTTP2Settings.defaults();
    s.applyPayload(payload); // should not throw
  }

  @Test
  public void apply_payload_invalid_initial_window_size() {
    // INITIAL_WINDOW_SIZE > 2^31 - 1 → FLOW_CONTROL_ERROR per RFC §6.5.2
    byte[] payload = {0, 4, (byte) 0x80, 0, 0, 0}; // value = 2^31
    HTTP2Settings s = HTTP2Settings.defaults();
    expectThrows(HTTP2SettingsException.class, () -> s.applyPayload(payload));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HTTP2SettingsTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement `HTTP2Settings`**

```java
/*
 * Copyright (c) 2026, Daniel DeGroff, All Rights Reserved
 */
package org.lattejava.http.server.internal;

/**
 * Per-connection HTTP/2 settings (RFC 9113 §6.5.2). Mutable so a single instance can be reused as the peer changes its settings mid-connection.
 *
 * @author Daniel DeGroff
 */
public class HTTP2Settings {
  public static final int SETTINGS_ENABLE_PUSH = 0x2;
  public static final int SETTINGS_HEADER_TABLE_SIZE = 0x1;
  public static final int SETTINGS_INITIAL_WINDOW_SIZE = 0x4;
  public static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x3;
  public static final int SETTINGS_MAX_FRAME_SIZE = 0x5;
  public static final int SETTINGS_MAX_HEADER_LIST_SIZE = 0x6;

  private int enablePush = 1;
  private int headerTableSize = 4096;
  private int initialWindowSize = 65535;
  private int maxConcurrentStreams = Integer.MAX_VALUE;
  private int maxFrameSize = 16384;
  private int maxHeaderListSize = Integer.MAX_VALUE;

  public static HTTP2Settings defaults() {
    HTTP2Settings s = new HTTP2Settings();
    s.enablePush = 0; // server default = no push
    return s;
  }

  public void applyPayload(byte[] payload) {
    if (payload.length % 6 != 0) {
      throw new HTTP2SettingsException("SETTINGS payload length [" + payload.length + "] is not a multiple of 6");
    }
    for (int i = 0; i < payload.length; i += 6) {
      int id = ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
      int value = ((payload[i + 2] & 0xFF) << 24) | ((payload[i + 3] & 0xFF) << 16)
                | ((payload[i + 4] & 0xFF) << 8)  |  (payload[i + 5] & 0xFF);

      switch (id) {
        case SETTINGS_HEADER_TABLE_SIZE -> headerTableSize = value;
        case SETTINGS_ENABLE_PUSH -> {
          if (value != 0 && value != 1) {
            throw new HTTP2SettingsException("ENABLE_PUSH must be 0 or 1; got [" + value + "]");
          }
          enablePush = value;
        }
        case SETTINGS_MAX_CONCURRENT_STREAMS -> maxConcurrentStreams = value;
        case SETTINGS_INITIAL_WINDOW_SIZE -> {
          if (value < 0) {
            throw new HTTP2SettingsException("INITIAL_WINDOW_SIZE exceeds 2^31-1");
          }
          initialWindowSize = value;
        }
        case SETTINGS_MAX_FRAME_SIZE -> {
          if (value < 16384 || value > 16777215) {
            throw new HTTP2SettingsException("MAX_FRAME_SIZE [" + value + "] out of range [16384, 16777215]");
          }
          maxFrameSize = value;
        }
        case SETTINGS_MAX_HEADER_LIST_SIZE -> maxHeaderListSize = value;
        default -> {} // unknown settings silently ignored per §6.5.2
      }
    }
  }

  public int enablePush() { return enablePush; }
  public int headerTableSize() { return headerTableSize; }
  public int initialWindowSize() { return initialWindowSize; }
  public int maxConcurrentStreams() { return maxConcurrentStreams; }
  public int maxFrameSize() { return maxFrameSize; }
  public int maxHeaderListSize() { return maxHeaderListSize; }

  public static class HTTP2SettingsException extends RuntimeException {
    public HTTP2SettingsException(String message) { super(message); }
  }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HTTP2SettingsTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Settings.java src/test/java/org/lattejava/http/tests/server/HTTP2SettingsTest.java
git commit -m "Add HTTP2Settings holder with applyPayload(byte[])"
```

---

## Task 3: `HTTP2Frame` sealed interface and record subtypes

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2Frame.java`

A sealed interface lets the reader return a typed record and the writer pattern-match on it.

- [ ] **Step 1: Write the file**

```java
/*
 * Copyright (c) 2026, Daniel DeGroff, All Rights Reserved
 */
package org.lattejava.http.server.internal;

/**
 * RFC 9113 §6 frame types. Each variant carries the fields specific to that frame type plus the common stream-id and flags.
 *
 * @author Daniel DeGroff
 */
public sealed interface HTTP2Frame {
  int FRAME_TYPE_DATA = 0x0;
  int FRAME_TYPE_HEADERS = 0x1;
  int FRAME_TYPE_PRIORITY = 0x2;
  int FRAME_TYPE_RST_STREAM = 0x3;
  int FRAME_TYPE_SETTINGS = 0x4;
  int FRAME_TYPE_PUSH_PROMISE = 0x5;
  int FRAME_TYPE_PING = 0x6;
  int FRAME_TYPE_GOAWAY = 0x7;
  int FRAME_TYPE_WINDOW_UPDATE = 0x8;
  int FRAME_TYPE_CONTINUATION = 0x9;

  int FLAG_END_STREAM = 0x1;
  int FLAG_END_HEADERS = 0x4;
  int FLAG_PADDED = 0x8;
  int FLAG_PRIORITY = 0x20;
  int FLAG_ACK = 0x1; // SETTINGS / PING

  int streamId();
  int flags();

  record DataFrame(int streamId, int flags, byte[] payload) implements HTTP2Frame {}
  record HeadersFrame(int streamId, int flags, byte[] headerBlockFragment) implements HTTP2Frame {}
  record PriorityFrame(int streamId) implements HTTP2Frame { public int flags() { return 0; } }
  record RstStreamFrame(int streamId, int errorCode) implements HTTP2Frame { public int flags() { return 0; } }
  record SettingsFrame(int flags, byte[] payload) implements HTTP2Frame { public int streamId() { return 0; } }
  record PushPromiseFrame(int streamId, int flags, int promisedStreamId, byte[] headerBlockFragment) implements HTTP2Frame {}
  record PingFrame(int flags, byte[] opaqueData) implements HTTP2Frame { public int streamId() { return 0; } }
  record GoawayFrame(int lastStreamId, int errorCode, byte[] debugData) implements HTTP2Frame {
    public int streamId() { return 0; }
    public int flags() { return 0; }
  }
  record WindowUpdateFrame(int streamId, int windowSizeIncrement) implements HTTP2Frame { public int flags() { return 0; } }
  record ContinuationFrame(int streamId, int flags, byte[] headerBlockFragment) implements HTTP2Frame {}
  record UnknownFrame(int streamId, int flags, int type, byte[] payload) implements HTTP2Frame {}
}
```

- [ ] **Step 2: Compile**

Run: `latte clean build`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTP2Frame.java
git commit -m "Add HTTP2Frame sealed interface with per-type record variants"
```

---

## Task 4: Add frame buffers to `HTTPBuffers`

**Files:**
- Modify: `src/main/java/org/lattejava/http/server/internal/HTTPBuffers.java`

- [ ] **Step 1: Add three new lazily-initialized buffer accessors**

Add fields (alphabetical, after existing `chunkBuffer`):

```java
private byte[] frameReadBuffer;

private byte[] frameWriteBuffer;

private FastByteArrayOutputStream headerAccumulationBuffer;
```

Add accessors:

```java
public byte[] frameReadBuffer() {
  if (frameReadBuffer == null) {
    frameReadBuffer = new byte[16777215]; // max possible MAX_FRAME_SIZE; sized once per connection
  }
  return frameReadBuffer;
}

public byte[] frameWriteBuffer() {
  if (frameWriteBuffer == null) {
    frameWriteBuffer = new byte[9 + 16777215];
  }
  return frameWriteBuffer;
}

public FastByteArrayOutputStream headerAccumulationBuffer() {
  if (headerAccumulationBuffer == null) {
    headerAccumulationBuffer = new FastByteArrayOutputStream(8192, 8192);
  }
  return headerAccumulationBuffer;
}
```

**Note on size:** sizing `frameReadBuffer` to 16 MB up-front would balloon per-connection memory. Practical refinement: take the negotiated `MAX_FRAME_SIZE` as a constructor parameter and size the buffer to that. **Do this:** add a setter `setMaxFrameSize(int)` that grows the buffer if needed; default size = 16384. This trades simplicity for memory.

```java
public byte[] frameReadBuffer() {
  if (frameReadBuffer == null) {
    frameReadBuffer = new byte[16384];
  }
  return frameReadBuffer;
}

public void ensureFrameReadCapacity(int size) {
  if (frameReadBuffer == null || frameReadBuffer.length < size) {
    frameReadBuffer = new byte[size];
  }
}
```

(Same shape for `frameWriteBuffer`.)

- [ ] **Step 2: Compile**

Run: `latte clean build`
Expected: SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/lattejava/http/server/internal/HTTPBuffers.java
git commit -m "Add frame and header-accumulation buffers to HTTPBuffers"
```

---

## Task 5: `HTTP2FrameReader` — read 9-byte header + payload

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2FrameReader.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/HTTP2FrameReaderTest.java`:

```java
/*
 * Copyright (c) 2026, Daniel DeGroff, All Rights Reserved
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2Frame;
import org.lattejava.http.server.internal.HTTP2FrameReader;

import static org.testng.Assert.*;

public class HTTP2FrameReaderTest {
  private byte[] header(int length, int type, int flags, int streamId) {
    return new byte[]{
        (byte)((length >> 16) & 0xFF), (byte)((length >> 8) & 0xFF), (byte)(length & 0xFF),
        (byte) type, (byte) flags,
        (byte)((streamId >> 24) & 0x7F), (byte)((streamId >> 16) & 0xFF), (byte)((streamId >> 8) & 0xFF), (byte)(streamId & 0xFF)
    };
  }

  @Test
  public void reads_data_frame() throws Exception {
    byte[] payload = "hello".getBytes();
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(payload.length, 0x0, 0x1, 7));
    bytes.write(payload);

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(bytes.toByteArray()), new byte[16384]);
    HTTP2Frame frame = reader.readFrame();

    assertTrue(frame instanceof HTTP2Frame.DataFrame);
    var data = (HTTP2Frame.DataFrame) frame;
    assertEquals(data.streamId(), 7);
    assertEquals(data.flags(), 0x1);
    assertEquals(data.payload(), payload);
  }

  @Test
  public void reads_settings_ack_with_empty_payload() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(0, 0x4, 0x1, 0));

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(bytes.toByteArray()), new byte[16384]);
    HTTP2Frame frame = reader.readFrame();

    assertTrue(frame instanceof HTTP2Frame.SettingsFrame);
    assertEquals(frame.flags(), 0x1);
  }

  @Test
  public void reads_window_update() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(4, 0x8, 0, 3));
    bytes.write(new byte[]{0, 0, 0, 100});

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(bytes.toByteArray()), new byte[16384]);
    HTTP2Frame frame = reader.readFrame();

    var wu = (HTTP2Frame.WindowUpdateFrame) frame;
    assertEquals(wu.streamId(), 3);
    assertEquals(wu.windowSizeIncrement(), 100);
  }

  @Test
  public void reads_unknown_frame_type() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(2, 0xFE, 0, 5));
    bytes.write(new byte[]{1, 2});

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(bytes.toByteArray()), new byte[16384]);
    HTTP2Frame frame = reader.readFrame();

    assertTrue(frame instanceof HTTP2Frame.UnknownFrame);
    var unk = (HTTP2Frame.UnknownFrame) frame;
    assertEquals(unk.type(), 0xFE);
  }

  @Test
  public void rst_stream_with_wrong_payload_length_throws() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(3, 0x3, 0, 1)); // RST_STREAM payload must be exactly 4
    bytes.write(new byte[]{1, 2, 3});

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(bytes.toByteArray()), new byte[16384]);
    expectThrows(HTTP2FrameReader.FrameSizeException.class, reader::readFrame);
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HTTP2FrameReaderTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement `HTTP2FrameReader`**

```java
/*
 * Copyright (c) 2026, Daniel DeGroff, All Rights Reserved
 */
package org.lattejava.http.server.internal;

import module java.base;

import static org.lattejava.http.server.internal.HTTP2Frame.*;

/**
 * Reads HTTP/2 frames from an InputStream. Owns the frame-read buffer (passed in by the caller, sized to MAX_FRAME_SIZE). Single-threaded — instance must not be shared across threads.
 *
 * @author Daniel DeGroff
 */
public class HTTP2FrameReader {
  private final byte[] buffer;

  private final InputStream in;

  public HTTP2FrameReader(InputStream in, byte[] buffer) {
    this.in = in;
    this.buffer = buffer;
  }

  public HTTP2Frame readFrame() throws IOException {
    // Read the 9-byte common header
    if (in.readNBytes(buffer, 0, 9) != 9) {
      throw new EOFException("Connection closed before frame header");
    }

    int length = ((buffer[0] & 0xFF) << 16) | ((buffer[1] & 0xFF) << 8) | (buffer[2] & 0xFF);
    int type = buffer[3] & 0xFF;
    int flags = buffer[4] & 0xFF;
    int streamId = ((buffer[5] & 0x7F) << 24) | ((buffer[6] & 0xFF) << 16) | ((buffer[7] & 0xFF) << 8) | (buffer[8] & 0xFF);

    if (length > buffer.length) {
      throw new FrameSizeException("Frame length [" + length + "] exceeds buffer capacity [" + buffer.length + "]");
    }

    if (in.readNBytes(buffer, 0, length) != length) {
      throw new EOFException("Connection closed mid-frame; expected [" + length + "] bytes");
    }

    return switch (type) {
      case FRAME_TYPE_DATA -> new DataFrame(streamId, flags, copyOf(buffer, length));
      case FRAME_TYPE_HEADERS -> new HeadersFrame(streamId, flags, copyOf(buffer, length));
      case FRAME_TYPE_PRIORITY -> {
        if (length != 5) throw new FrameSizeException("PRIORITY payload must be 5; got [" + length + "]");
        yield new PriorityFrame(streamId);
      }
      case FRAME_TYPE_RST_STREAM -> {
        if (length != 4) throw new FrameSizeException("RST_STREAM payload must be 4; got [" + length + "]");
        int code = ((buffer[0] & 0xFF) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        yield new RstStreamFrame(streamId, code);
      }
      case FRAME_TYPE_SETTINGS -> {
        if ((flags & FLAG_ACK) != 0 && length != 0) throw new FrameSizeException("SETTINGS ACK must have empty payload");
        if (length % 6 != 0) throw new FrameSizeException("SETTINGS payload length [" + length + "] not multiple of 6");
        yield new SettingsFrame(flags, copyOf(buffer, length));
      }
      case FRAME_TYPE_PUSH_PROMISE -> {
        // We never advertise push but parse for completeness.
        int promised = ((buffer[0] & 0x7F) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        yield new PushPromiseFrame(streamId, flags, promised, copyOfRange(buffer, 4, length));
      }
      case FRAME_TYPE_PING -> {
        if (length != 8) throw new FrameSizeException("PING payload must be 8; got [" + length + "]");
        yield new PingFrame(flags, copyOf(buffer, 8));
      }
      case FRAME_TYPE_GOAWAY -> {
        if (length < 8) throw new FrameSizeException("GOAWAY payload must be ≥ 8; got [" + length + "]");
        int last = ((buffer[0] & 0x7F) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        int code = ((buffer[4] & 0xFF) << 24) | ((buffer[5] & 0xFF) << 16) | ((buffer[6] & 0xFF) << 8) | (buffer[7] & 0xFF);
        yield new GoawayFrame(last, code, copyOfRange(buffer, 8, length));
      }
      case FRAME_TYPE_WINDOW_UPDATE -> {
        if (length != 4) throw new FrameSizeException("WINDOW_UPDATE payload must be 4");
        int inc = ((buffer[0] & 0x7F) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        yield new WindowUpdateFrame(streamId, inc);
      }
      case FRAME_TYPE_CONTINUATION -> new ContinuationFrame(streamId, flags, copyOf(buffer, length));
      default -> new UnknownFrame(streamId, flags, type, copyOf(buffer, length));
    };
  }

  private static byte[] copyOf(byte[] src, int len) {
    byte[] dst = new byte[len];
    System.arraycopy(src, 0, dst, 0, len);
    return dst;
  }

  private static byte[] copyOfRange(byte[] src, int from, int to) {
    byte[] dst = new byte[to - from];
    System.arraycopy(src, from, dst, 0, to - from);
    return dst;
  }

  public static class FrameSizeException extends IOException {
    public FrameSizeException(String message) { super(message); }
  }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HTTP2FrameReaderTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add HTTP2FrameReader with per-type validation and unknown-frame fallthrough"
```

---

## Task 6: `HTTP2FrameWriter` — serialize frames

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2FrameWriter.java`

- [ ] **Step 1: Write the round-trip test**

Add to `HTTP2FrameReaderTest.java` (or a new `HTTP2FrameRoundTripTest`):

```java
@Test
public void round_trip_data_frame() throws Exception {
  var sink = new ByteArrayOutputStream();
  var writer = new HTTP2FrameWriter(sink, new byte[16384 + 9]);
  writer.writeFrame(new HTTP2Frame.DataFrame(7, 0x1, "hello".getBytes()));

  var reader = new HTTP2FrameReader(new ByteArrayInputStream(sink.toByteArray()), new byte[16384]);
  var frame = (HTTP2Frame.DataFrame) reader.readFrame();
  assertEquals(frame.streamId(), 7);
  assertEquals(frame.flags(), 0x1);
  assertEquals(frame.payload(), "hello".getBytes());
}

@Test
public void round_trip_settings_with_payload() throws Exception {
  byte[] payload = {0, 1, 0, 0, 0x10, 0}; // HEADER_TABLE_SIZE = 4096
  var sink = new ByteArrayOutputStream();
  var writer = new HTTP2FrameWriter(sink, new byte[16384 + 9]);
  writer.writeFrame(new HTTP2Frame.SettingsFrame(0, payload));

  var reader = new HTTP2FrameReader(new ByteArrayInputStream(sink.toByteArray()), new byte[16384]);
  var frame = (HTTP2Frame.SettingsFrame) reader.readFrame();
  assertEquals(frame.payload(), payload);
}

@Test
public void round_trip_goaway() throws Exception {
  var sink = new ByteArrayOutputStream();
  var writer = new HTTP2FrameWriter(sink, new byte[16384 + 9]);
  writer.writeFrame(new HTTP2Frame.GoawayFrame(13, 0x1, new byte[0]));

  var reader = new HTTP2FrameReader(new ByteArrayInputStream(sink.toByteArray()), new byte[16384]);
  var frame = (HTTP2Frame.GoawayFrame) reader.readFrame();
  assertEquals(frame.lastStreamId(), 13);
  assertEquals(frame.errorCode(), 0x1);
}
```

- [ ] **Step 2: Verify failure**

Run: `latte test --test=HTTP2FrameReaderTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement `HTTP2FrameWriter`**

```java
/*
 * Copyright (c) 2026, Daniel DeGroff, All Rights Reserved
 */
package org.lattejava.http.server.internal;

import module java.base;

import static org.lattejava.http.server.internal.HTTP2Frame.*;

public class HTTP2FrameWriter {
  private final byte[] buffer;

  private final OutputStream out;

  public HTTP2FrameWriter(OutputStream out, byte[] buffer) {
    this.out = out;
    this.buffer = buffer;
  }

  public void writeFrame(HTTP2Frame frame) throws IOException {
    switch (frame) {
      case DataFrame f -> writeWithPayload(FRAME_TYPE_DATA, f.flags(), f.streamId(), f.payload());
      case HeadersFrame f -> writeWithPayload(FRAME_TYPE_HEADERS, f.flags(), f.streamId(), f.headerBlockFragment());
      case PriorityFrame f -> writeWithPayload(FRAME_TYPE_PRIORITY, 0, f.streamId(), new byte[5]);
      case RstStreamFrame f -> writeWithPayload(FRAME_TYPE_RST_STREAM, 0, f.streamId(), int32(f.errorCode()));
      case SettingsFrame f -> writeWithPayload(FRAME_TYPE_SETTINGS, f.flags(), 0, f.payload());
      case PushPromiseFrame f -> {
        byte[] payload = new byte[4 + f.headerBlockFragment().length];
        writeInt32(payload, 0, f.promisedStreamId() & 0x7FFFFFFF);
        System.arraycopy(f.headerBlockFragment(), 0, payload, 4, f.headerBlockFragment().length);
        writeWithPayload(FRAME_TYPE_PUSH_PROMISE, f.flags(), f.streamId(), payload);
      }
      case PingFrame f -> writeWithPayload(FRAME_TYPE_PING, f.flags(), 0, f.opaqueData());
      case GoawayFrame f -> {
        byte[] payload = new byte[8 + f.debugData().length];
        writeInt32(payload, 0, f.lastStreamId() & 0x7FFFFFFF);
        writeInt32(payload, 4, f.errorCode());
        System.arraycopy(f.debugData(), 0, payload, 8, f.debugData().length);
        writeWithPayload(FRAME_TYPE_GOAWAY, 0, 0, payload);
      }
      case WindowUpdateFrame f -> writeWithPayload(FRAME_TYPE_WINDOW_UPDATE, 0, f.streamId(), int32(f.windowSizeIncrement() & 0x7FFFFFFF));
      case ContinuationFrame f -> writeWithPayload(FRAME_TYPE_CONTINUATION, f.flags(), f.streamId(), f.headerBlockFragment());
      case UnknownFrame f -> writeWithPayload(f.type(), f.flags(), f.streamId(), f.payload());
    }
  }

  private void writeWithPayload(int type, int flags, int streamId, byte[] payload) throws IOException {
    int length = payload.length;
    buffer[0] = (byte) ((length >> 16) & 0xFF);
    buffer[1] = (byte) ((length >> 8) & 0xFF);
    buffer[2] = (byte) (length & 0xFF);
    buffer[3] = (byte) type;
    buffer[4] = (byte) flags;
    writeInt32(buffer, 5, streamId & 0x7FFFFFFF);
    System.arraycopy(payload, 0, buffer, 9, length);
    out.write(buffer, 0, 9 + length);
  }

  private static byte[] int32(int v) {
    byte[] b = new byte[4];
    writeInt32(b, 0, v);
    return b;
  }

  private static void writeInt32(byte[] dst, int off, int v) {
    dst[off] = (byte) ((v >> 24) & 0xFF);
    dst[off + 1] = (byte) ((v >> 16) & 0xFF);
    dst[off + 2] = (byte) ((v >> 8) & 0xFF);
    dst[off + 3] = (byte) (v & 0xFF);
  }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HTTP2FrameReaderTest`
Expected: ALL PASS (round-trips and prior reader-only tests).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add HTTP2FrameWriter; round-trip tests pass"
```

---

## Task 7: `HPACKHuffman` static code

The Huffman code table is fixed (RFC 7541 Appendix B, 257 entries). Hand-typed once.

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HPACKHuffman.java`

- [ ] **Step 1: Write failing tests against RFC 7541 Appendix C.4 examples**

Create `src/test/java/org/lattejava/http/tests/server/HPACKHuffmanTest.java`:

```java
/*
 * Copyright (c) 2026, Daniel DeGroff, All Rights Reserved
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HPACKHuffman;

import static org.testng.Assert.*;

public class HPACKHuffmanTest {
  // RFC 7541 Appendix C.4.1: "www.example.com" Huffman-encoded
  @Test
  public void encode_decode_www_example_com() {
    String s = "www.example.com";
    byte[] encoded = HPACKHuffman.encode(s.getBytes());
    // Expected hex per RFC: f1e3 c2e5 f23a 6ba0 ab90 f4ff
    byte[] expected = hex("f1e3c2e5f23a6ba0ab90f4ff");
    assertEquals(encoded, expected);
    byte[] decoded = HPACKHuffman.decode(encoded);
    assertEquals(new String(decoded), s);
  }

  // RFC 7541 Appendix C.4.3: "custom-key"
  @Test
  public void encode_decode_custom_key() {
    String s = "custom-key";
    byte[] encoded = HPACKHuffman.encode(s.getBytes());
    byte[] expected = hex("25a849e95ba97d7f"); // per RFC
    assertEquals(encoded, expected);
    assertEquals(new String(HPACKHuffman.decode(encoded)), s);
  }

  @Test
  public void empty_round_trip() {
    byte[] encoded = HPACKHuffman.encode(new byte[0]);
    assertEquals(encoded.length, 0);
    assertEquals(HPACKHuffman.decode(encoded).length, 0);
  }

  private static byte[] hex(String h) {
    byte[] out = new byte[h.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HPACKHuffmanTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement `HPACKHuffman`**

The static code table has 257 entries. Hand-type from RFC 7541 Appendix B as `static final int[] CODES = {...}` (the bit pattern, right-aligned) and `static final int[] LENGTHS = {...}` (number of bits).

**For the engineer:** the full table is at https://datatracker.ietf.org/doc/html/rfc7541#appendix-B. Each row maps a symbol (0–256) to a (hex, length) pair. The 257th symbol is the EOS code. The implementation is roughly:

```java
public class HPACKHuffman {
  private static final int[] CODES;
  private static final int[] LENGTHS;

  static {
    CODES = new int[257];
    LENGTHS = new int[257];
    // Populate from RFC 7541 Appendix B.
    // Example first three entries — the engineer types out all 257:
    CODES[0] = 0x1ff8;        LENGTHS[0] = 13;
    CODES[1] = 0x7fffd8;      LENGTHS[1] = 23;
    CODES[2] = 0xfffffe2;     LENGTHS[2] = 28;
    // ... continue for all 257 symbols ...
  }

  public static byte[] encode(byte[] input) {
    long acc = 0;
    int bits = 0;
    var out = new java.io.ByteArrayOutputStream();
    for (byte b : input) {
      int sym = b & 0xFF;
      acc = (acc << LENGTHS[sym]) | CODES[sym];
      bits += LENGTHS[sym];
      while (bits >= 8) {
        bits -= 8;
        out.write((int)((acc >> bits) & 0xFF));
      }
    }
    if (bits > 0) {
      // Pad with EOS prefix (1-bits)
      acc = (acc << (8 - bits)) | ((1 << (8 - bits)) - 1);
      out.write((int)(acc & 0xFF));
    }
    return out.toByteArray();
  }

  public static byte[] decode(byte[] input) {
    // Decode by walking a pre-built tree. Build the tree once at static init from the CODES/LENGTHS arrays.
    // ... see RFC 7541 Appendix B for a reference algorithm; full implementation here ...
  }
}
```

**Implementation note:** the decode side requires building a Huffman tree (or equivalent table-lookup scheme) from `CODES`/`LENGTHS`. Use a 256-entry decode table indexed by 8-bit prefixes; multi-table for codes > 8 bits. Reference implementation: Netty's `HpackHuffmanDecoder`. **Spend the time to type the table out carefully — a single bit-pattern error is undetectable until the round-trip test fails on a specific input.**

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HPACKHuffmanTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add HPACKHuffman with RFC 7541 Appendix B static code table"
```

---

## Task 8: `HPACKDynamicTable` — bounded ring buffer

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HPACKDynamicTable.java`

Per RFC 7541 §4.1, dynamic-table size is the sum of `name.length + value.length + 32` over all entries; entries evicted from the head when adding would exceed `maxSize`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/lattejava/http/tests/server/HPACKDynamicTableTest.java`:

```java
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HPACKDynamicTable;

import static org.testng.Assert.*;

public class HPACKDynamicTableTest {
  @Test
  public void empty_table_has_size_zero() {
    var t = new HPACKDynamicTable(4096);
    assertEquals(t.size(), 0);
    assertEquals(t.entryCount(), 0);
  }

  @Test
  public void add_one_entry() {
    var t = new HPACKDynamicTable(4096);
    t.add(":status", "200");
    assertEquals(t.entryCount(), 1);
    // size = name(7) + value(3) + 32 = 42
    assertEquals(t.size(), 42);
    assertEquals(t.get(0).name(), ":status");
    assertEquals(t.get(0).value(), "200");
  }

  @Test
  public void evicts_when_over_capacity() {
    var t = new HPACKDynamicTable(80); // tight
    t.add("a", "1");  // 1+1+32 = 34
    t.add("b", "2");  // 1+1+32 = 34, total 68
    t.add("c", "3");  // 1+1+32 = 34, total 102 — must evict oldest entries
    assertEquals(t.entryCount(), 2);
    assertEquals(t.get(0).name(), "c");
    assertEquals(t.get(1).name(), "b");
  }

  @Test
  public void resize_evicts() {
    var t = new HPACKDynamicTable(4096);
    t.add("a", "1");
    t.add("b", "2");
    t.setMaxSize(0);
    assertEquals(t.entryCount(), 0);
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HPACKDynamicTableTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement `HPACKDynamicTable`**

```java
package org.lattejava.http.server.internal;

import module java.base;

public class HPACKDynamicTable {
  private final ArrayDeque<HeaderField> entries = new ArrayDeque<>();
  private int maxSize;
  private int size;

  public HPACKDynamicTable(int maxSize) {
    this.maxSize = maxSize;
  }

  public void add(String name, String value) {
    int entrySize = name.length() + value.length() + 32;
    while (size + entrySize > maxSize && !entries.isEmpty()) {
      var evicted = entries.removeLast();
      size -= evicted.name().length() + evicted.value().length() + 32;
    }
    if (entrySize <= maxSize) {
      entries.addFirst(new HeaderField(name, value));
      size += entrySize;
    }
  }

  public int entryCount() { return entries.size(); }

  public HeaderField get(int index) {
    int i = 0;
    for (HeaderField e : entries) {
      if (i++ == index) return e;
    }
    throw new IndexOutOfBoundsException("Index [" + index + "] out of range; size [" + entries.size() + "]");
  }

  public int maxSize() { return maxSize; }

  public void setMaxSize(int newMax) {
    this.maxSize = newMax;
    while (size > maxSize && !entries.isEmpty()) {
      var evicted = entries.removeLast();
      size -= evicted.name().length() + evicted.value().length() + 32;
    }
  }

  public int size() { return size; }

  public record HeaderField(String name, String value) {}
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HPACKDynamicTableTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add HPACKDynamicTable with eviction on add and resize"
```

---

## Task 9: `HPACKDecoder` — decode header block to (name, value) pairs

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HPACKDecoder.java`

The decoder handles RFC 7541's six representation forms: indexed, literal-with-incremental-indexing, literal-without-indexing, literal-never-indexed, dynamic-table-size-update, plus the static table (Appendix A, 61 entries).

- [ ] **Step 1: Write tests against RFC 7541 Appendix C.2 vectors**

Create `src/test/java/org/lattejava/http/tests/server/HPACKDecoderTest.java`:

```java
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HPACKDecoder;
import org.lattejava.http.server.internal.HPACKDynamicTable;

import static org.testng.Assert.*;

public class HPACKDecoderTest {
  // RFC 7541 Appendix C.2.1: literal header field with indexing — "custom-key: custom-header"
  @Test
  public void literal_with_indexing() throws Exception {
    byte[] block = hex("400a637573746f6d2d6b65790d637573746f6d2d686561646572");
    var table = new HPACKDynamicTable(4096);
    var decoder = new HPACKDecoder(table);
    var fields = decoder.decode(block);
    assertEquals(fields.size(), 1);
    assertEquals(fields.get(0).name(), "custom-key");
    assertEquals(fields.get(0).value(), "custom-header");
    // Side-effect: dynamic table now has the entry
    assertEquals(table.entryCount(), 1);
  }

  // RFC 7541 Appendix C.2.4: indexed header field — :method GET (static index 2)
  @Test
  public void indexed_static() throws Exception {
    byte[] block = {(byte) 0x82};
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    var fields = decoder.decode(block);
    assertEquals(fields.size(), 1);
    assertEquals(fields.get(0).name(), ":method");
    assertEquals(fields.get(0).value(), "GET");
  }

  // RFC 7541 Appendix C.3.1: full GET request with multiple headers
  @Test
  public void appendix_c3_1_request_no_huffman() throws Exception {
    byte[] block = hex("828684410f7777772e6578616d706c652e636f6d");
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    var fields = decoder.decode(block);
    assertEquals(fields.size(), 4);
    assertEquals(fields.get(0).name(), ":method");    assertEquals(fields.get(0).value(), "GET");
    assertEquals(fields.get(1).name(), ":scheme");    assertEquals(fields.get(1).value(), "http");
    assertEquals(fields.get(2).name(), ":path");      assertEquals(fields.get(2).value(), "/");
    assertEquals(fields.get(3).name(), ":authority"); assertEquals(fields.get(3).value(), "www.example.com");
  }

  // RFC 7541 Appendix C.4.1: same request, Huffman-encoded
  @Test
  public void appendix_c4_1_request_with_huffman() throws Exception {
    byte[] block = hex("828684418cf1e3c2e5f23a6ba0ab90f4ff");
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    var fields = decoder.decode(block);
    assertEquals(fields.size(), 4);
    assertEquals(fields.get(3).name(), ":authority");
    assertEquals(fields.get(3).value(), "www.example.com");
  }

  private static byte[] hex(String h) {
    h = h.replace(" ", "");
    byte[] out = new byte[h.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HPACKDecoderTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement `HPACKDecoder`**

```java
package org.lattejava.http.server.internal;

import module java.base;

public class HPACKDecoder {
  // RFC 7541 Appendix A — 61 entries, indexed 1..61.
  private static final HPACKDynamicTable.HeaderField[] STATIC_TABLE = new HPACKDynamicTable.HeaderField[]{
      null, // 1-based
      new HPACKDynamicTable.HeaderField(":authority", ""),
      new HPACKDynamicTable.HeaderField(":method", "GET"),
      new HPACKDynamicTable.HeaderField(":method", "POST"),
      new HPACKDynamicTable.HeaderField(":path", "/"),
      new HPACKDynamicTable.HeaderField(":path", "/index.html"),
      new HPACKDynamicTable.HeaderField(":scheme", "http"),
      new HPACKDynamicTable.HeaderField(":scheme", "https"),
      new HPACKDynamicTable.HeaderField(":status", "200"),
      // ... continue through index 61 from RFC 7541 Appendix A ...
  };

  private final HPACKDynamicTable dynamicTable;

  public HPACKDecoder(HPACKDynamicTable dynamicTable) {
    this.dynamicTable = dynamicTable;
  }

  public List<HPACKDynamicTable.HeaderField> decode(byte[] block) throws IOException {
    var fields = new ArrayList<HPACKDynamicTable.HeaderField>();
    int i = 0;
    while (i < block.length) {
      int b = block[i] & 0xFF;
      if ((b & 0x80) != 0) {
        // Indexed header field — §6.1
        int[] r = decodeInt(block, i, 7);
        fields.add(lookup(r[0]));
        i = r[1];
      } else if ((b & 0x40) != 0) {
        // Literal with incremental indexing — §6.2.1
        int[] r = decodeInt(block, i, 6);
        var pair = readNameValue(block, r[1], r[0]);
        fields.add(pair.field());
        dynamicTable.add(pair.field().name(), pair.field().value());
        i = pair.nextIndex();
      } else if ((b & 0x20) != 0) {
        // Dynamic table size update — §6.3
        int[] r = decodeInt(block, i, 5);
        dynamicTable.setMaxSize(r[0]);
        i = r[1];
      } else {
        // Literal without indexing — §6.2.2 — or never indexed §6.2.3
        // (We treat both the same on the receive path.)
        int[] r = decodeInt(block, i, 4);
        var pair = readNameValue(block, r[1], r[0]);
        fields.add(pair.field());
        i = pair.nextIndex();
      }
    }
    return fields;
  }

  // Decodes an N-prefix integer per RFC 7541 §5.1; returns [value, nextIndex].
  static int[] decodeInt(byte[] block, int i, int prefixBits) {
    int max = (1 << prefixBits) - 1;
    int v = block[i] & max;
    i++;
    if (v < max) return new int[]{v, i};
    int m = 0;
    int b;
    do {
      b = block[i++] & 0xFF;
      v += (b & 0x7F) << m;
      m += 7;
    } while ((b & 0x80) != 0);
    return new int[]{v, i};
  }

  private HPACKDynamicTable.HeaderField lookup(int index) {
    if (index == 0) {
      throw new IllegalStateException("HPACK index 0 is invalid");
    }
    if (index <= 61) {
      return STATIC_TABLE[index];
    }
    return dynamicTable.get(index - 62);
  }

  private record NameValuePair(HPACKDynamicTable.HeaderField field, int nextIndex) {}

  private NameValuePair readNameValue(byte[] block, int start, int nameIndex) throws IOException {
    String name;
    int i = start;
    if (nameIndex == 0) {
      // Literal name follows; read string
      var s = readString(block, i);
      name = s.value();
      i = s.nextIndex();
    } else {
      name = lookup(nameIndex).name();
    }
    var v = readString(block, i);
    return new NameValuePair(new HPACKDynamicTable.HeaderField(name, v.value()), v.nextIndex());
  }

  private record StringResult(String value, int nextIndex) {}

  private StringResult readString(byte[] block, int i) throws IOException {
    boolean huffman = (block[i] & 0x80) != 0;
    int[] r = decodeInt(block, i, 7);
    int len = r[0];
    int start = r[1];
    byte[] raw = new byte[len];
    System.arraycopy(block, start, raw, 0, len);
    String s = huffman ? new String(HPACKHuffman.decode(raw)) : new String(raw);
    return new StringResult(s, start + len);
  }
}
```

- [ ] **Step 4: Type out the rest of `STATIC_TABLE`**

The full RFC 7541 Appendix A table — all 61 entries — must be present. Reference: https://datatracker.ietf.org/doc/html/rfc7541#appendix-A.

- [ ] **Step 5: Run to verify pass**

Run: `latte test --test=HPACKDecoderTest`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add HPACKDecoder with full static table and 6 representation forms"
```

---

## Task 10: `HPACKEncoder`

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HPACKEncoder.java`

Encoder strategy: literal-with-incremental-indexing for stable headers (Server, Content-Type, etc.), literal-without-indexing for sensitive (`Set-Cookie`, `Authorization`). For now, use a simple heuristic — never-indexed only for sensitive — and let tuning come later.

- [ ] **Step 1: Write the round-trip test**

Create `src/test/java/org/lattejava/http/tests/server/HPACKEncoderTest.java`:

```java
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HPACKDecoder;
import org.lattejava.http.server.internal.HPACKDynamicTable;
import org.lattejava.http.server.internal.HPACKEncoder;

import static org.testng.Assert.*;

public class HPACKEncoderTest {
  @Test
  public void round_trip_via_decoder() throws Exception {
    var encTable = new HPACKDynamicTable(4096);
    var decTable = new HPACKDynamicTable(4096);
    var encoder = new HPACKEncoder(encTable);
    var decoder = new HPACKDecoder(decTable);

    List<HPACKDynamicTable.HeaderField> input = List.of(
        new HPACKDynamicTable.HeaderField(":method", "GET"),
        new HPACKDynamicTable.HeaderField(":scheme", "https"),
        new HPACKDynamicTable.HeaderField(":path", "/"),
        new HPACKDynamicTable.HeaderField(":authority", "example.com"),
        new HPACKDynamicTable.HeaderField("custom", "value")
    );

    byte[] block = encoder.encode(input);
    var output = decoder.decode(block);
    assertEquals(output, input);
  }

  @Test
  public void uses_static_table_for_method_get() throws Exception {
    var encoder = new HPACKEncoder(new HPACKDynamicTable(4096));
    byte[] block = encoder.encode(List.of(new HPACKDynamicTable.HeaderField(":method", "GET")));
    // RFC 7541 Appendix A index 2 → 0x82 (1-bit indexed prefix + 7-bit value=2)
    assertEquals(block, new byte[]{(byte) 0x82});
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HPACKEncoderTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement `HPACKEncoder`**

Strategy:
1. For each (name, value), search the static table for an exact (name, value) match → emit indexed.
2. Otherwise search the dynamic table → emit indexed.
3. Otherwise search either table for name-only match → emit literal-with-indexing using that name index.
4. Otherwise emit literal-with-indexing with full name + value.
5. For sensitive names, force literal-without-indexing.

```java
package org.lattejava.http.server.internal;

import module java.base;

public class HPACKEncoder {
  private static final Set<String> SENSITIVE = Set.of("set-cookie", "authorization");

  private final HPACKDynamicTable dynamicTable;

  public HPACKEncoder(HPACKDynamicTable dynamicTable) {
    this.dynamicTable = dynamicTable;
  }

  public byte[] encode(List<HPACKDynamicTable.HeaderField> fields) {
    var out = new ByteArrayOutputStream();
    for (var f : fields) {
      String lcName = f.name().toLowerCase();
      // 1. Exact match in static table
      int staticExact = staticIndexFullMatch(f.name(), f.value());
      if (staticExact != -1) {
        encodeInt(out, staticExact, 7, 0x80);
        continue;
      }
      // 2. Sensitive: literal-without-indexing, name from static if possible
      int nameIdx = staticIndexNameOnly(f.name());
      if (SENSITIVE.contains(lcName)) {
        encodeInt(out, nameIdx == -1 ? 0 : nameIdx, 4, 0x00);
        if (nameIdx == -1) writeString(out, f.name());
        writeString(out, f.value());
        continue;
      }
      // 3. Otherwise literal-with-indexing
      encodeInt(out, nameIdx == -1 ? 0 : nameIdx, 6, 0x40);
      if (nameIdx == -1) writeString(out, f.name());
      writeString(out, f.value());
      dynamicTable.add(f.name(), f.value());
    }
    return out.toByteArray();
  }

  private static void encodeInt(ByteArrayOutputStream out, int value, int prefixBits, int firstByteMask) {
    int max = (1 << prefixBits) - 1;
    if (value < max) {
      out.write(firstByteMask | value);
      return;
    }
    out.write(firstByteMask | max);
    value -= max;
    while (value >= 128) {
      out.write((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    out.write(value);
  }

  private static void writeString(ByteArrayOutputStream out, String s) {
    // For simplicity and determinism, encode without Huffman in v1. Plan F can add Huffman after benchmarking.
    byte[] bytes = s.getBytes();
    encodeInt(out, bytes.length, 7, 0x00);
    out.write(bytes, 0, bytes.length);
  }

  private static int staticIndexFullMatch(String name, String value) {
    // Search HPACKDecoder.STATIC_TABLE for an exact match. Returning -1 if none.
    // (Implementation note: HPACKDecoder.STATIC_TABLE is private; either expose a package-private accessor or duplicate the table here. Simplest: move STATIC_TABLE to a shared HPACKStaticTable class.)
    return HPACKStaticTable.indexFullMatch(name, value);
  }

  private static int staticIndexNameOnly(String name) {
    return HPACKStaticTable.indexNameOnly(name);
  }
}
```

- [ ] **Step 4: Extract `HPACKStaticTable` to its own class**

Move the 61-entry table from `HPACKDecoder` into a new `HPACKStaticTable` class with `lookup(int)`, `indexFullMatch(name, value)`, and `indexNameOnly(name)` methods. Update `HPACKDecoder` to call `HPACKStaticTable.lookup(index)`. Add to `File Structure` section above (you'll need this file).

- [ ] **Step 5: Run to verify pass**

Run: `latte test --test=HPACKEncoderTest --test=HPACKDecoderTest`
Expected: ALL PASS.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add HPACKEncoder with static-table indexing and sensitive-header literal mode"
```

---

## Task 11: `HTTP2Stream` state machine

**Files:**
- Create: `src/main/java/org/lattejava/http/server/internal/HTTP2Stream.java`

States: `IDLE`, `OPEN`, `HALF_CLOSED_LOCAL`, `HALF_CLOSED_REMOTE`, `CLOSED`. Events drive transitions. Window counters live alongside.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/lattejava/http/tests/server/HTTP2StreamStateMachineTest.java`:

```java
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2Stream;
import org.lattejava.http.server.internal.HTTP2Stream.State;
import org.lattejava.http.server.internal.HTTP2Stream.Event;

import static org.testng.Assert.*;

public class HTTP2StreamStateMachineTest {
  @Test
  public void idle_to_open_on_recv_headers() {
    var s = new HTTP2Stream(1, 65535, 65535);
    assertEquals(s.state(), State.IDLE);
    s.applyEvent(Event.RECV_HEADERS_NO_END_STREAM);
    assertEquals(s.state(), State.OPEN);
  }

  @Test
  public void idle_to_half_closed_remote_on_recv_headers_with_end_stream() {
    var s = new HTTP2Stream(1, 65535, 65535);
    s.applyEvent(Event.RECV_HEADERS_END_STREAM);
    assertEquals(s.state(), State.HALF_CLOSED_REMOTE);
  }

  @Test
  public void open_to_half_closed_remote_on_recv_data_with_end_stream() {
    var s = new HTTP2Stream(1, 65535, 65535);
    s.applyEvent(Event.RECV_HEADERS_NO_END_STREAM);
    s.applyEvent(Event.RECV_DATA_END_STREAM);
    assertEquals(s.state(), State.HALF_CLOSED_REMOTE);
  }

  @Test
  public void half_closed_remote_to_closed_on_send_data_with_end_stream() {
    var s = new HTTP2Stream(1, 65535, 65535);
    s.applyEvent(Event.RECV_HEADERS_END_STREAM);
    s.applyEvent(Event.SEND_HEADERS_NO_END_STREAM);
    s.applyEvent(Event.SEND_DATA_END_STREAM);
    assertEquals(s.state(), State.CLOSED);
  }

  @Test
  public void rst_stream_from_any_state_closes() {
    var s = new HTTP2Stream(1, 65535, 65535);
    s.applyEvent(Event.RECV_HEADERS_NO_END_STREAM);
    s.applyEvent(Event.RECV_RST_STREAM);
    assertEquals(s.state(), State.CLOSED);
  }

  @Test
  public void illegal_event_throws() {
    var s = new HTTP2Stream(1, 65535, 65535);
    expectThrows(IllegalStateException.class, () -> s.applyEvent(Event.RECV_DATA_END_STREAM));
  }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `latte test --test=HTTP2StreamStateMachineTest`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement `HTTP2Stream`**

```java
package org.lattejava.http.server.internal;

public class HTTP2Stream {
  public enum State { IDLE, OPEN, HALF_CLOSED_LOCAL, HALF_CLOSED_REMOTE, CLOSED }

  public enum Event {
    RECV_HEADERS_NO_END_STREAM,
    RECV_HEADERS_END_STREAM,
    RECV_DATA_NO_END_STREAM,
    RECV_DATA_END_STREAM,
    SEND_HEADERS_NO_END_STREAM,
    SEND_HEADERS_END_STREAM,
    SEND_DATA_NO_END_STREAM,
    SEND_DATA_END_STREAM,
    RECV_RST_STREAM,
    SEND_RST_STREAM
  }

  private final int streamId;
  private long receiveWindow;
  private long sendWindow;
  private State state = State.IDLE;

  public HTTP2Stream(int streamId, int initialReceiveWindow, int initialSendWindow) {
    this.streamId = streamId;
    this.receiveWindow = initialReceiveWindow;
    this.sendWindow = initialSendWindow;
  }

  public synchronized void applyEvent(Event event) {
    state = transition(state, event);
  }

  public synchronized void consumeReceiveWindow(int bytes) {
    if (bytes > receiveWindow) {
      throw new IllegalStateException("Stream [" + streamId + "] receive-window underflow: needed [" + bytes + "], have [" + receiveWindow + "]");
    }
    receiveWindow -= bytes;
  }

  public synchronized void consumeSendWindow(int bytes) {
    if (bytes > sendWindow) {
      throw new IllegalStateException("Stream [" + streamId + "] send-window underflow: needed [" + bytes + "], have [" + sendWindow + "]");
    }
    sendWindow -= bytes;
  }

  public synchronized void incrementReceiveWindow(int delta) {
    receiveWindow += delta;
  }

  public synchronized void incrementSendWindow(int delta) {
    long next = sendWindow + delta;
    if (next > Integer.MAX_VALUE) {
      throw new IllegalStateException("Stream [" + streamId + "] send-window overflow past 2^31-1");
    }
    sendWindow = next;
  }

  public long receiveWindow() { return receiveWindow; }
  public long sendWindow() { return sendWindow; }
  public State state() { return state; }
  public int streamId() { return streamId; }

  private static State transition(State s, Event e) {
    return switch (s) {
      case IDLE -> switch (e) {
        case RECV_HEADERS_NO_END_STREAM -> State.OPEN;
        case RECV_HEADERS_END_STREAM -> State.HALF_CLOSED_REMOTE;
        case SEND_HEADERS_NO_END_STREAM -> State.OPEN;
        case SEND_HEADERS_END_STREAM -> State.HALF_CLOSED_LOCAL;
        case SEND_RST_STREAM, RECV_RST_STREAM -> State.CLOSED;
        default -> throw new IllegalStateException("Event [" + e + "] illegal in state [IDLE]");
      };
      case OPEN -> switch (e) {
        case RECV_DATA_NO_END_STREAM, SEND_DATA_NO_END_STREAM, RECV_HEADERS_NO_END_STREAM, SEND_HEADERS_NO_END_STREAM -> State.OPEN;
        case RECV_DATA_END_STREAM, RECV_HEADERS_END_STREAM -> State.HALF_CLOSED_REMOTE;
        case SEND_DATA_END_STREAM, SEND_HEADERS_END_STREAM -> State.HALF_CLOSED_LOCAL;
        case RECV_RST_STREAM, SEND_RST_STREAM -> State.CLOSED;
      };
      case HALF_CLOSED_LOCAL -> switch (e) {
        case RECV_DATA_NO_END_STREAM, RECV_HEADERS_NO_END_STREAM -> State.HALF_CLOSED_LOCAL;
        case RECV_DATA_END_STREAM, RECV_HEADERS_END_STREAM -> State.CLOSED;
        case RECV_RST_STREAM, SEND_RST_STREAM -> State.CLOSED;
        default -> throw new IllegalStateException("Event [" + e + "] illegal in state [HALF_CLOSED_LOCAL]");
      };
      case HALF_CLOSED_REMOTE -> switch (e) {
        case SEND_DATA_NO_END_STREAM, SEND_HEADERS_NO_END_STREAM -> State.HALF_CLOSED_REMOTE;
        case SEND_DATA_END_STREAM, SEND_HEADERS_END_STREAM -> State.CLOSED;
        case RECV_RST_STREAM, SEND_RST_STREAM -> State.CLOSED;
        default -> throw new IllegalStateException("Event [" + e + "] illegal in state [HALF_CLOSED_REMOTE]");
      };
      case CLOSED -> throw new IllegalStateException("Event [" + e + "] illegal in state [CLOSED]");
    };
  }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `latte test --test=HTTP2StreamStateMachineTest`
Expected: ALL PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add HTTP2Stream state machine and window counters"
```

---

## Task 12: Flow-control accounting tests

The window primitives are already implemented in Task 11. This task adds dedicated tests covering edge cases.

**Files:**
- Create: `src/test/java/org/lattejava/http/tests/server/HTTP2FlowControlTest.java`

- [ ] **Step 1: Write the tests**

```java
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2Stream;

import static org.testng.Assert.*;

public class HTTP2FlowControlTest {
  @Test
  public void send_window_decrements_and_replenishes() {
    var s = new HTTP2Stream(1, 65535, 1000);
    s.consumeSendWindow(400);
    assertEquals(s.sendWindow(), 600);
    s.incrementSendWindow(200);
    assertEquals(s.sendWindow(), 800);
  }

  @Test
  public void send_window_underflow_throws() {
    var s = new HTTP2Stream(1, 65535, 100);
    expectThrows(IllegalStateException.class, () -> s.consumeSendWindow(101));
  }

  @Test
  public void window_overflow_past_signed_int_max_throws() {
    var s = new HTTP2Stream(1, 65535, 1);
    expectThrows(IllegalStateException.class, () -> s.incrementSendWindow(Integer.MAX_VALUE));
  }

  @Test
  public void receive_window_replenishes() {
    var s = new HTTP2Stream(1, 1000, 65535);
    s.consumeReceiveWindow(400);
    assertEquals(s.receiveWindow(), 600);
    s.incrementReceiveWindow(400);
    assertEquals(s.receiveWindow(), 1000);
  }
}
```

- [ ] **Step 2: Run**

Run: `latte test --test=HTTP2FlowControlTest`
Expected: ALL PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/lattejava/http/tests/server/HTTP2FlowControlTest.java
git commit -m "Add flow-control accounting tests for HTTP2Stream"
```

---

## Task 13: Final verification

- [ ] **Step 1: Full build with all new tests**

Run: `latte clean int --excludePerformance --excludeTimeouts`
Expected: ALL PASS.

- [ ] **Step 2: Verify no public-API surface changes**

Run: `git diff main -- src/main/java/org/lattejava/http/server/HTTPRequest.java src/main/java/org/lattejava/http/server/HTTPResponse.java src/main/java/org/lattejava/http/server/HTTPHandler.java`
Expected: NO DIFF — Plan C touches only `internal/` package.

- [ ] **Step 3: Tag**

(Optional) tag the commit so Plan D has a clear baseline:
```bash
git tag -a http2-protocol-layer-complete -m "Plan C complete: codec + HPACK + state machine isolated"
```

---

## Self-review checklist

- ✅ Each task has a failing test → implementation → green pattern
- ✅ All test vectors are RFC 7541 Appendix C examples (HPACK) and RFC 9113 §5.1 (state machine)
- ✅ Reusable per-connection buffers added to `HTTPBuffers` per the GC-reduction direction
- ✅ No socket code, no threading — pure protocol-layer
- ✅ Frame codec dispatched via sealed-interface pattern matching
- ✅ State machine uses `switch` expressions for exhaustive state×event coverage
- ⚠️ `HPACKHuffman` has the table sketched but the engineer must hand-type all 257 entries — there's no shortcut
- ⚠️ `HPACKDecoder.STATIC_TABLE` likewise — moved to `HPACKStaticTable` in Task 10

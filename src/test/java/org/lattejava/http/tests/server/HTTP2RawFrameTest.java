/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Raw frame conformance tests for HTTP/2. Each test sends specific frame sequences over a raw h2c socket and asserts
 * RFC 9113 conformance at the connection level: correct GOAWAY error codes or correct transparent-ignore behavior.
 *
 * <p>Frame helpers are inlined for self-containment (rather than relying on a shared base class that does not yet
 * expose these utilities).
 *
 * @author Daniel DeGroff
 */
public class HTTP2RawFrameTest extends BaseTest {
  /**
   * Minimal HPACK block representing a valid GET / request over HTTP/2. Uses only indexed header field
   * representations from the static table (RFC 7541 §6.1):
   * <ul>
   *   <li>{@code 0x82} — index 2: {@code :method: GET}</li>
   *   <li>{@code 0x84} — index 4: {@code :path: /}</li>
   *   <li>{@code 0x86} — index 6: {@code :scheme: https}</li>
   *   <li>{@code 0x41, 0x0f, ...} — literal {@code :authority: localhost}</li>
   * </ul>
   * Passing this as the HEADERS payload gives the server a decodable header block, which is required for tests
   * that need to exercise post-HEADERS logic (e.g. stream-id ordering).
   */
  private static final byte[] MINIMAL_HPACK_GET = {
      (byte) 0x82,                          // :method: GET
      (byte) 0x84,                          // :path: /
      (byte) 0x86,                          // :scheme: https → server accepts either scheme; using literal below instead
      // :authority: localhost (literal with indexing, name from static table index 1)
      (byte) 0x41, 0x09,
      'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
  };

  /**
   * Open an h2c prior-knowledge connection and return the socket after the handshake is complete (server
   * SETTINGS and SETTINGS ACK have been drained).
   */
  private Socket openH2cConnection(int port) throws Exception {
    var sock = new Socket("127.0.0.1", port);
    var out = sock.getOutputStream();
    out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
    out.write(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0});  // empty SETTINGS
    out.flush();

    var in = sock.getInputStream();
    // Drain server SETTINGS.
    byte[] header = in.readNBytes(9);
    int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
    in.readNBytes(length);
    // Drain SETTINGS ACK.
    in.readNBytes(9);
    return sock;
  }

  /**
   * Write a 9-byte frame header.
   */
  private void writeFrameHeader(OutputStream out, int length, int type, int flags, int streamId) throws Exception {
    out.write(new byte[]{
        (byte) ((length >> 16) & 0xFF), (byte) ((length >> 8) & 0xFF), (byte) (length & 0xFF),
        (byte) type, (byte) flags,
        (byte) ((streamId >> 24) & 0x7F), (byte) ((streamId >> 16) & 0xFF),
        (byte) ((streamId >> 8) & 0xFF), (byte) (streamId & 0xFF)
    });
  }

  /**
   * Drain inbound frames until GOAWAY (type {@code 0x7}) arrives or the connection closes. Returns the GOAWAY
   * error code, or {@code -1} if EOF arrived first.
   */
  private int readUntilGoaway(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      int read = in.readNBytes(rest, 0, 8);
      if (read != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      byte[] payload = in.readNBytes(length);
      if (type == 0x7) {  // GOAWAY
        if (payload.length < 8) {
          return -1;
        }
        return ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16) | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
      }
    }
  }

  /**
   * Read and discard frames until a HEADERS frame (type {@code 0x1}) arrives. Returns the stream-id of the
   * response HEADERS frame, or {@code -1} on EOF.
   */
  private int readUntilResponseHeaders(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      int read = in.readNBytes(rest, 0, 8);
      if (read != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      int streamId = ((rest[4] & 0x7F) << 24) | ((rest[5] & 0xFF) << 16) | ((rest[6] & 0xFF) << 8) | (rest[7] & 0xFF);
      in.readNBytes(length);
      if (type == 0x1) {  // HEADERS
        return streamId;
      }
    }
  }

  /**
   * RFC 9113 §8.4 — clients MUST NOT send PUSH_PROMISE frames. The server must respond with
   * {@code GOAWAY(PROTOCOL_ERROR)} (error code {@code 0x1}).
   */
  @Test
  public void push_promise_inbound_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // PUSH_PROMISE: type=0x5, stream-id=1, promised-stream-id=2 (4-byte prefix) + empty header block.
        // Total payload = 4 bytes (promised stream-id only, no header block).
        writeFrameHeader(out, 4, 0x5, 0x4 /* END_HEADERS */, 1);
        out.write(new byte[]{0, 0, 0, 2});  // promised stream id = 2
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for inbound PUSH_PROMISE; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §5.5 — unknown frame types MUST be ignored. Send an unknown frame (type {@code 0xFE}), then a normal
   * HEADERS request. The server should respond successfully, proving the unknown frame was silently discarded.
   */
  @Test
  public void unknown_frame_type_silently_ignored() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Unknown frame type 0xFE with 4-byte payload on stream 0.
        writeFrameHeader(out, 4, 0xFE, 0, 0);
        out.write(new byte[]{0x1, 0x2, 0x3, 0x4});

        // Follow with a valid HEADERS request on stream 1.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int streamId = readUntilResponseHeaders(sock.getInputStream());
        assertEquals(streamId, 1, "Expected response HEADERS on stream 1 after unknown frame was silently ignored");
      }
    }
  }

  /**
   * RFC 9113 §5.3.2 — PRIORITY frames are valid but advisory only; the server MUST parse and discard them.
   * Send a PRIORITY frame, then a valid HEADERS request. The server should respond successfully.
   */
  @Test
  public void priority_frame_silently_ignored() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // PRIORITY: type=0x2, payload=5 bytes (stream dependency 4 bytes + weight 1 byte), stream-id=1.
        writeFrameHeader(out, 5, 0x2, 0, 1);
        out.write(new byte[]{0, 0, 0, 0, 0});  // exclusive=0, dependency=0, weight=0

        // Follow with a valid HEADERS request on stream 1.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int streamId = readUntilResponseHeaders(sock.getInputStream());
        assertEquals(streamId, 1, "Expected response HEADERS on stream 1 after PRIORITY was silently ignored");
      }
    }
  }

  /**
   * RFC 9113 §5.1 — a DATA frame on a recently-closed stream (one the client just RST'd) must produce
   * {@code GOAWAY(STREAM_CLOSED)} (error code {@code 0x5}).
   */
  @Test
  public void data_on_recently_closed_stream_triggers_stream_closed() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Open stream 1 with END_STREAM (no body — handler completes immediately).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        // RST stream 1 — server marks stream 1 as recently closed.
        writeFrameHeader(out, 4, 0x3 /* RST_STREAM */, 0, 1);
        out.write(new byte[]{0, 0, 0, 0x8}); // error code = CANCEL (0x8)
        // Send DATA on stream 1 — must produce GOAWAY(STREAM_CLOSED=0x5).
        writeFrameHeader(out, 5, 0x0 /* DATA */, 0x1 /* END_STREAM */, 1);
        out.write(new byte[]{1, 2, 3, 4, 5});
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x5, "Expected GOAWAY(STREAM_CLOSED=0x5) for DATA on recently-closed stream; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §5.1.1 — stream IDs MUST be strictly monotonically increasing. Sending a HEADERS on a stream
   * whose ID is lower than a previously seen stream ID must result in {@code GOAWAY(PROTOCOL_ERROR)} (error code
   * {@code 0x1}).
   */
  @Test
  public void decreasing_stream_id_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // First HEADERS on stream 5.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 5);
        out.write(MINIMAL_HPACK_GET);
        // Second HEADERS on stream 3 — lower than 5, a protocol error.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 3);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for decreasing stream ID; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §5.1 — a HEADERS frame on a recently-closed stream (one the client just RST'd) must produce
   * {@code GOAWAY(STREAM_CLOSED)} (error code {@code 0x5}).
   */
  @Test
  public void headers_on_recently_closed_stream_triggers_stream_closed() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Open stream 1 with END_STREAM (no body — handler completes immediately).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        // RST stream 1 — server marks stream 1 as recently closed.
        writeFrameHeader(out, 4, 0x3 /* RST_STREAM */, 0, 1);
        out.write(new byte[]{0, 0, 0, 0x8}); // error code = CANCEL (0x8)
        // Send HEADERS on stream 1 again — must produce GOAWAY(STREAM_CLOSED=0x5).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x5, "Expected GOAWAY(STREAM_CLOSED=0x5) for HEADERS on recently-closed stream; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.10 — once a HEADERS frame without END_HEADERS is sent, the next frame on the connection MUST be
   * a CONTINUATION on the same stream. Any other frame type or stream ID triggers {@code GOAWAY(PROTOCOL_ERROR)}
   * (error code {@code 0x1}).
   */
  @Test
  public void interleaved_frame_during_headers_continuation_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Send HEADERS on stream 1 WITHOUT END_HEADERS — this starts a header block.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x0 /* no END_HEADERS, no END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        // Now send a DATA frame on stream 3 instead of CONTINUATION on stream 1 — protocol error.
        writeFrameHeader(out, 0, 0x0, 0x1 /* END_STREAM */, 3);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for interleaved frame mid-header-block; got: " + errorCode);
      }
    }
  }
}

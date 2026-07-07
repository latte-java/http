/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Integration tests covering h2spec batch-5 failure categories:
 * <ul>
 *   <li>http2 §3.5/2 — invalid connection preface: connection must terminate with a clean FIN, not a TCP RST</li>
 *   <li>http2 §5.1/11 — DATA on a stream closed by normal completion must raise STREAM_CLOSED</li>
 *   <li>http2 §5.3.1/1 — HEADERS whose priority declares a dependency on its own stream must raise PROTOCOL_ERROR</li>
 *   <li>http2 §5.3.1/2 — PRIORITY that depends on its own stream must raise PROTOCOL_ERROR</li>
 *   <li>http2 §8.1/1 and §8.1.2.1/3 — a second HEADERS block without END_STREAM must raise PROTOCOL_ERROR</li>
 *   <li>hpack §4.2/1 — a dynamic table size update after the first header field must raise COMPRESSION_ERROR</li>
 * </ul>
 *
 * @author Daniel DeGroff
 */
public class HTTP2H2SpecBatch5Test extends BaseHTTP2RawTest {
  /**
   * Minimal HPACK block for a GET / request (static-table indexed only).
   */
  private static final byte[] MINIMAL_HPACK_GET = {
      (byte) 0x82,                          // :method: GET
      (byte) 0x84,                          // :path: /
      (byte) 0x86,                          // :scheme: https
      (byte) 0x41, 0x09,
      'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
  };

  /**
   * Regression guard for the §5.1/11 fix — a server that responds before the client finishes its request leaves the
   * client half of the stream open. DATA still in flight after that early response must be ignored (RFC 9113 §6.1),
   * not treated as a connection error, and the connection must remain usable for new streams.
   */
  @Test
  public void data_after_early_response_on_open_request_is_ignored() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try (var os = res.getOutputStream()) {
        os.write("ok".getBytes());
      }
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        sock.setSoTimeout(5000);
        var out = sock.getOutputStream();
        var in = sock.getInputStream();

        // Open stream 1 without END_STREAM — the handler responds without reading the body.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();
        readThroughResponse(in);

        // The request half of stream 1 is still open; this DATA must be ignored.
        writeFrameHeader(out, 4, 0x0, 0x0, 1);
        out.write("late".getBytes());

        // The connection must still serve a fresh request.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 3);
        out.write(MINIMAL_HPACK_GET);
        out.flush();
        int streamId = readUntilResponseHeaders(in);
        assertEquals(streamId, 3, "Expected a response on stream 3 after ignored DATA; got stream: [" + streamId + "]");
      }
    }
  }

  /**
   * RFC 9113 §5.1 (h2spec http2/5.1/11) — a stream closed by normal completion (client END_STREAM, full server
   * response) that then receives DATA must raise a connection error of type STREAM_CLOSED.
   */
  @Test
  public void data_on_completed_stream_triggers_stream_closed() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try (var os = res.getOutputStream()) {
        os.write("ok".getBytes());
      }
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        sock.setSoTimeout(5000);
        var out = sock.getOutputStream();
        var in = sock.getInputStream();

        // Complete request; stream 1 fully closes once the response has been sent.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();
        readThroughResponse(in);

        // DATA on the closed stream — §5.1 requires a STREAM_CLOSED connection error.
        writeFrameHeader(out, 1, 0x0, 0x0, 1);
        out.write('x');
        out.flush();

        int errorCode = readUntilGoaway(in);
        assertEquals(errorCode, 0x5, "Expected GOAWAY(STREAM_CLOSED=0x5) for DATA on a completed stream; got: [" + errorCode + "]");
      }
    }
  }

  /**
   * RFC 9113 §5.3.1 (h2spec http2/5.3.1/1) — a HEADERS frame with the PRIORITY flag whose stream dependency names its
   * own stream must raise a stream error of type PROTOCOL_ERROR, and the request must not be dispatched.
   */
  @Test
  public void headers_with_self_dependency_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        sock.setSoTimeout(5000);
        var out = sock.getOutputStream();

        // HEADERS with END_STREAM | END_HEADERS | PRIORITY; priority fields declare stream 1 depends on stream 1.
        byte[] priorityFields = {0x00, 0x00, 0x00, 0x01, 0x0F};
        writeFrameHeader(out, priorityFields.length + MINIMAL_HPACK_GET.length, 0x1, 0x1 | 0x4 | 0x20, 1);
        out.write(priorityFields);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for a self-dependent HEADERS; got: [" + errorCode + "]");
      }
    }
  }

  /**
   * RFC 7541 §4.2 (h2spec hpack/4.2/1) — a dynamic table size update after the first header field of a block is a
   * decoding error; the connection must fail with COMPRESSION_ERROR.
   */
  @Test
  public void hpack_size_update_after_header_field_triggers_compression_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        sock.setSoTimeout(5000);
        var out = sock.getOutputStream();

        // Valid header fields followed by a trailing dynamic-table-size update (0x20 — resize to 0).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length + 1, 0x1, 0x1 | 0x4, 1);
        out.write(MINIMAL_HPACK_GET);
        out.write(0x20);
        out.flush();

        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x9, "Expected GOAWAY(COMPRESSION_ERROR=0x9) for a late table-size update; got: [" + errorCode + "]");
      }
    }
  }

  /**
   * RFC 9113 §3.4 (h2spec http2/3.5/2) — an invalid connection preface on a dual-protocol (h2c prior-knowledge)
   * listener must terminate the connection cleanly and silently: the client's protocol is unknown, so an HTTP/1.1
   * error response would be gibberish to an HTTP/2 client. The client must observe a bare FIN (EOF with no payload),
   * never a TCP RST ("connection reset by peer").
   */
  @Test
  public void invalid_preface_on_h2c_listener_closes_cleanly_and_silently() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = new Socket("127.0.0.1", server.getActualPort())) {
        sock.setSoTimeout(5000);
        sock.getOutputStream().write("INVALID CONNECTION PREFACE\r\n\r\n".getBytes());
        sock.getOutputStream().flush();

        // readAllBytes throws SocketException("Connection reset") if the server closes with unread input pending.
        byte[] response = sock.getInputStream().readAllBytes();
        assertEquals(response.length, 0, "Expected a silent close for an invalid preface; got: [" + new String(response) + "]");
      }
    }
  }

  /**
   * Companion to the invalid-preface case — on a plain HTTP/1.1 listener the same garbage is just a malformed
   * request: the client must receive the 400 and then a clean EOF, never a TCP RST that destroys the response.
   */
  @Test
  public void malformed_request_on_h1_listener_gets_400_then_clean_close() throws Exception {
    var listener = new HTTPListenerConfiguration(0);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = new Socket("127.0.0.1", server.getActualPort())) {
        sock.setSoTimeout(5000);
        sock.getOutputStream().write("INVALID CONNECTION PREFACE\r\n\r\n".getBytes());
        sock.getOutputStream().flush();

        String response = new String(sock.getInputStream().readAllBytes());
        assertTrue(response.startsWith("HTTP/1.1 400"), "Expected a 400 then EOF for a malformed request; got: [" + response + "]");
      }
    }
  }

  /**
   * RFC 9113 §5.3.1 (h2spec http2/5.3.1/2) — a PRIORITY frame whose stream dependency names its own stream must raise
   * a stream error of type PROTOCOL_ERROR.
   */
  @Test
  public void priority_with_self_dependency_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        sock.setSoTimeout(5000);
        var out = sock.getOutputStream();

        // PRIORITY on stream 1 depending on stream 1.
        writeFrameHeader(out, 5, 0x2, 0x0, 1);
        out.write(new byte[]{0x00, 0x00, 0x00, 0x01, 0x0F});
        out.flush();

        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for a self-dependent PRIORITY; got: [" + errorCode + "]");
      }
    }
  }

  /**
   * RFC 9113 §8.1 (h2spec http2/8.1/1, http2/8.1.2.1/3) — a second HEADERS block on a stream is only valid as
   * trailers, and trailers must carry END_STREAM. A second HEADERS without END_STREAM must raise a stream error of
   * type PROTOCOL_ERROR — not STREAM_CLOSED. The handler sleeps briefly so the reader deterministically sees the
   * second HEADERS while the stream is still open.
   */
  @Test
  public void second_headers_without_end_stream_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> {
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      res.setStatus(200);
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        sock.setSoTimeout(5000);
        var out = sock.getOutputStream();

        // First HEADERS without END_STREAM opens the stream; the request body is still expected.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4, 1);
        out.write(MINIMAL_HPACK_GET);

        // Second HEADERS without END_STREAM — not a legal trailers block. Literal header field: x-t: 1.
        byte[] trailerBlock = {0x00, 0x03, 'x', '-', 't', 0x01, '1'};
        writeFrameHeader(out, trailerBlock.length, 0x1, 0x4, 1);
        out.write(trailerBlock);
        out.flush();

        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for a second HEADERS without END_STREAM; got: [" + errorCode + "]");
      }
    }
  }

  /**
   * Reads and discards frames until the response DATA frame carrying END_STREAM has been consumed.
   */
  private void readThroughResponse(InputStream in) throws Exception {
    while (true) {
      int[] frame = readFrameHeader(in);
      in.readNBytes(frame[3]);
      if (frame[0] == 0x0 && (frame[1] & 0x1) != 0) {
        return;
      }
    }
  }
}

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
 * DoS-class security tests for HTTP/2 rate limiting. Each test exercises one per-frame-type rate-limit check and
 * asserts that the server responds with {@code GOAWAY(ENHANCE_YOUR_CALM)} (error code {@code 0xb}) when the
 * threshold is exceeded.
 *
 * <p>All tests use raw sockets over h2c prior-knowledge. The {@code openH2cConnection} helper establishes a
 * compliant handshake, and the {@code readUntilGoaway} helper drains frames until GOAWAY arrives.
 *
 * <p>Empty-DATA flood: intentionally omitted. A meaningful DATA flood test requires an open, half-open stream to
 * send DATA on, which substantially complicates test setup (requires a valid HEADERS frame with HPACK encoding first).
 * The empty-DATA rate-limit code path in {@link org.lattejava.http.server.internal.HTTP2Connection} is exercised by
 * the unit tests in {@link HTTP2RateLimitsTest}. A future plan (Plan F) should add the integration-level coverage.
 *
 * @author Daniel DeGroff
 */
public class HTTP2SecurityTest extends BaseTest {
  /**
   * Open an h2c prior-knowledge connection. Returns the socket after the handshake is complete (server SETTINGS and
   * SETTINGS ACK have been drained).
   */
  private Socket openH2cConnection(int port) throws Exception {
    var sock = new Socket("127.0.0.1", port);
    var out = sock.getOutputStream();
    // Connection preface
    out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
    // Empty SETTINGS (length=0, type=0x4, flags=0, stream=0)
    out.write(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0});
    out.flush();

    var in = sock.getInputStream();
    // Drain server SETTINGS frame.
    byte[] header = in.readNBytes(9);
    int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
    in.readNBytes(length);
    // Drain SETTINGS ACK (9 bytes, zero payload).
    in.readNBytes(9);
    return sock;
  }

  /**
   * Write a 9-byte frame header (big-endian length + type + flags + stream-id).
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
   * Drain inbound frames until a GOAWAY (type {@code 0x7}) is seen or the connection is closed. Returns the
   * GOAWAY error code, or {@code -1} if EOF arrived first.
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
      if (type == 0x7) {
        // Payload: [last-stream-id (4)] [error-code (4)] [debug-data (variable)]
        if (payload.length < 8) {
          return -1;
        }
        return ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16) | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
      }
    }
  }

  @Test
  public void continuation_flood_triggers_goaway() throws Exception {
    // CVE-2024-27316: many CONTINUATION frames whose cumulative size exceeds MAX_HEADER_LIST_SIZE.
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    var server = makeServer("http", handler, listener);
    // Set a small MAX_HEADER_LIST_SIZE so the test triggers quickly with modest data.
    server.configuration().withHTTP2MaxHeaderListSize(2048);
    try (var ignored = server.start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // HEADERS with no END_HEADERS — 1024 bytes of header block fragment, no flags.
        writeFrameHeader(out, 1024, 0x1, 0x0, 1);
        out.write(new byte[1024]);
        // CONTINUATION frames: each 1024 bytes, no END_HEADERS — cumulative total quickly exceeds 2048.
        for (int i = 0; i < 5; i++) {
          writeFrameHeader(out, 1024, 0x9, 0, 1);
          out.write(new byte[1024]);
        }
        out.flush();
        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0xb, "Expected GOAWAY(ENHANCE_YOUR_CALM=0xb), got: " + errorCode);
      }
    }
  }

  @Test
  public void ping_flood_triggers_goaway() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // PING flood: send 15 (default threshold is 10/s).
        for (int i = 0; i < 15; i++) {
          writeFrameHeader(out, 8, 0x6, 0, 0);  // PING, length 8, no flags, stream 0
          out.write(new byte[8]);                // 8 bytes opaque data
        }
        out.flush();
        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0xb, "Expected GOAWAY(ENHANCE_YOUR_CALM=0xb), got: " + errorCode);
      }
    }
  }

  @Test
  public void rapid_reset_triggers_goaway() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Rapid Reset (CVE-2023-44487): send RST_STREAM frames in excess of the 100/30s threshold.
        // RST_STREAM on streams that were never opened is still counted by the rate limiter.
        // Odd stream IDs per RFC 9113 §5.1.1 (client-initiated).
        // 105 iterations × step 2 = stream IDs 1, 3, 5, ..., 209 → 105 RST_STREAM frames (> threshold of 100).
        for (int i = 1; i <= 210; i += 2) {
          writeFrameHeader(out, 4, 0x3, 0, i);   // RST_STREAM, length 4
          out.write(new byte[]{0, 0, 0, 0x8});   // CANCEL (0x8)
        }
        out.flush();
        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0xb, "Expected GOAWAY(ENHANCE_YOUR_CALM=0xb), got: " + errorCode);
      }
    }
  }

  @Test
  public void settings_flood_triggers_goaway() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // SETTINGS flood: 15 within 1s (default threshold is 10/s).
        for (int i = 0; i < 15; i++) {
          writeFrameHeader(out, 0, 0x4, 0, 0);   // SETTINGS, empty payload
        }
        out.flush();
        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0xb, "Expected GOAWAY(ENHANCE_YOUR_CALM=0xb), got: " + errorCode);
      }
    }
  }

  @Test
  public void window_update_flood_triggers_goaway() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // WINDOW_UPDATE flood: 110 within 1s (default threshold is 100/s).
        for (int i = 0; i < 110; i++) {
          writeFrameHeader(out, 4, 0x8, 0, 0);   // WINDOW_UPDATE on stream 0, length 4
          out.write(new byte[]{0, 0, 0, 0x1});   // increment 1
        }
        out.flush();
        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0xb, "Expected GOAWAY(ENHANCE_YOUR_CALM=0xb), got: " + errorCode);
      }
    }
  }
}

/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.h2.*;

import static org.testng.Assert.*;

/**
 * DoS-class security tests for HTTP/2 rate limiting. Each test exercises one per-frame-type rate-limit check and
 * asserts that the server responds with {@code GOAWAY(ENHANCE_YOUR_CALM)} (error code {@code 0xb}) when the threshold
 * is exceeded.
 *
 * <p>All tests use raw sockets over h2c prior-knowledge. The {@code openH2CConnection} helper establishes a
 * compliant handshake, and the {@code readUntilGoaway} helper drains frames until GOAWAY arrives.
 *
 * <p>Empty-DATA flood: intentionally omitted. A meaningful DATA flood test requires an open, half-open stream to
 * send DATA on, which substantially complicates test setup (requires a valid HEADERS frame with HPACK encoding first).
 * The empty-DATA rate-limit code path in {@link HTTP2Connection} is exercised by the
 * unit tests in {@link HTTP2RateLimitsTest}. A future plan (Plan F) should add the integration-level coverage.
 *
 * @author Daniel DeGroff
 */
public class HTTP2SecurityTest extends BaseHTTP2RawTest {
  @Test
  public void continuation_flood_triggers_goaway() throws Exception {
    // CVE-2024-27316: many CONTINUATION frames whose cumulative size exceeds MAX_HEADER_LIST_SIZE.
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    var server = makeServer("http", handler, listener);
    // Set a small MAX_HEADER_LIST_SIZE so the test triggers quickly with modest data.
    server.configuration().withMaxRequestHeaderSize(2048);
    try (var ignored = server.start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
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

  /**
   * RFC 7541 §2.1 — HPACK index 0 is invalid. RFC 9113 §4.3 — HPACK malformations are connection errors with code
   * COMPRESSION_ERROR. Locks in the specific error-code mapping; the Task 3 "any GOAWAY" safety net stays in place as a
   * backstop for genuinely unhandled exceptions.
   */
  @Test
  public void hpack_index_zero_yields_goaway_compression_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // HEADERS payload = 0x80 (indexed header field, index 0 — invalid per RFC 7541 §2.1).
        writeFrameHeader(out, 1, 0x1 /* HEADERS */, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(new byte[]{(byte) 0x80});
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x9, "Expected GOAWAY(COMPRESSION_ERROR=0x9); got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §8.1.2.6 — a malformed content-length (unparseable or negative) is a stream error of type PROTOCOL_ERROR.
   * nghttp2, Caddy, and Apache Traffic Server all treat this consistently. Previously was silently ignored, letting the
   * handler run with {@code declaredContentLength == -1} which disabled DATA-frame overflow protection.
   */
  @Test
  public void malformed_content_length_yields_rst_stream_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // POST with content-length: "abc". HPACK encoding:
        //   :method POST       (static idx 3)
        //   :path /            (static idx 4)
        //   :scheme http       (static idx 6)
        //   :authority localhost (literal-with-indexing, name idx 1)
        //   content-length abc (literal-with-indexing, name idx 28 [content-length], value "abc")
        byte[] headers = new byte[]{
            (byte) 0x83,
            (byte) 0x84,
            (byte) 0x86,
            (byte) 0x41, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't',
            (byte) 0x5c, 0x03, 'a', 'b', 'c'
        };
        writeFrameHeader(out, headers.length, 0x1, 0x4, 1); // HEADERS, END_HEADERS, no END_STREAM
        out.write(headers);
        out.flush();

        sock.setSoTimeout(5000);
        var in = sock.getInputStream();
        int rstStreamErrorCode = -1;
        while (rstStreamErrorCode == -1) {
          byte[] hdr = in.readNBytes(9);
          if (hdr.length < 9) {
            break;
          }
          int len = ((hdr[0] & 0xFF) << 16) | ((hdr[1] & 0xFF) << 8) | (hdr[2] & 0xFF);
          int type = hdr[3] & 0xFF;
          byte[] payload = in.readNBytes(len);
          if (payload.length < len) {
            break;
          }
          if (type == 0x3 && payload.length >= 4) {
            // RST_STREAM: 4-byte error code.
            rstStreamErrorCode = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
          }
        }
        assertEquals(rstStreamErrorCode, HTTP2ErrorCode.PROTOCOL_ERROR.value,
            "Expected RST_STREAM(PROTOCOL_ERROR=" + HTTP2ErrorCode.PROTOCOL_ERROR.value + "); got [" + rstStreamErrorCode + "]");
      }
    }
  }

  @Test
  public void ping_flood_triggers_goaway() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
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
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Rapid Reset (CVE-2023-44487): send HEADERS immediately followed by RST_STREAM, repeating with
        // monotonically increasing stream IDs. This models the real attack pattern where each RST targets
        // a previously-opened stream (not an idle stream). 105 iterations × step 2 = stream IDs 1..209,
        // producing 105 RST_STREAM frames — exceeding the 100/30s threshold.
        // Minimal HPACK block: :method=GET, :path=/, :scheme=http, :authority=localhost
        byte[] minimalHeaders = {
            (byte) 0x82,                                    // :method: GET
            (byte) 0x84,                                    // :path: /
            (byte) 0x86,                                    // :scheme: https
            (byte) 0x41, 0x09,                              // :authority: localhost (literal)
            'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
        };
        for (int i = 1; i <= 210; i += 2) {
          // HEADERS frame with END_HEADERS | END_STREAM so no body is expected.
          writeFrameHeader(out, minimalHeaders.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, i);
          out.write(minimalHeaders);
          // RST_STREAM on the same stream ID (Rapid Reset attack pattern).
          writeFrameHeader(out, 4, 0x3, 0, i);
          out.write(new byte[]{0, 0, 0, 0x8}); // CANCEL (0x8)
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
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
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
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
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

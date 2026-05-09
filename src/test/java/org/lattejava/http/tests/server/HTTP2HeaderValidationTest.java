/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HPACKDynamicTable;
import org.lattejava.http.server.internal.HPACKEncoder;

import static org.testng.Assert.*;

/**
 * Unit tests for HPACK pseudo-header / connection-specific-header validation per RFC 9113 §8.1.2.*
 * Each test sends a hand-crafted HEADERS frame over a raw h2c prior-knowledge socket and asserts that
 * the server responds with RST_STREAM(PROTOCOL_ERROR) for violations, or with a 200 response for the
 * content-length sanity case.
 *
 * @author Daniel DeGroff
 */
public class HTTP2HeaderValidationTest extends BaseTest {
  // ─── helpers ────────────────────────────────────────────────────────────────────────────────────

  /**
   * Builds a valid HPACK block for a GET / request with the supplied extra headers appended.
   */
  private byte[] hpackWith(List<HPACKDynamicTable.HeaderField> extraHeaders) {
    var table = new HPACKDynamicTable(4096);
    var encoder = new HPACKEncoder(table);
    var fields = new ArrayList<HPACKDynamicTable.HeaderField>();
    fields.add(new HPACKDynamicTable.HeaderField(":method", "GET"));
    fields.add(new HPACKDynamicTable.HeaderField(":path", "/"));
    fields.add(new HPACKDynamicTable.HeaderField(":scheme", "http"));
    fields.add(new HPACKDynamicTable.HeaderField(":authority", "localhost"));
    fields.addAll(extraHeaders);
    return encoder.encode(fields);
  }

  /**
   * Builds a HPACK block from exactly the supplied fields (no default request pseudo-headers added).
   */
  private byte[] hpackExact(List<HPACKDynamicTable.HeaderField> fields) {
    var table = new HPACKDynamicTable(4096);
    var encoder = new HPACKEncoder(table);
    return encoder.encode(fields);
  }

  /**
   * Open an h2c prior-knowledge connection and drain the initial handshake.
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
   * Drain inbound frames until RST_STREAM (type {@code 0x3}) arrives.
   * Returns the error code, or {@code -1} if GOAWAY or EOF arrived first.
   */
  private int readUntilRstStream(InputStream in) throws Exception {
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
      if (type == 0x3) { // RST_STREAM
        if (payload.length < 4) {
          return -1;
        }
        return ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
      }
      if (type == 0x7) { // GOAWAY — connection error, not stream error
        return -1;
      }
    }
  }

  /**
   * Drain inbound frames until a HEADERS response frame arrives. Returns the stream-id, or {@code -1} on EOF.
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
      if (type == 0x1) { // HEADERS
        return streamId;
      }
    }
  }

  // ─── §8.1.2/1: uppercase header name ────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §8.1.2/1 — header names MUST be lowercase. An uppercase header name must trigger
   * RST_STREAM(PROTOCOL_ERROR).
   */
  @Test
  public void uppercase_header_name_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Encode a HEADERS block that includes an uppercase header name (violates RFC 9113 §8.1.2/1).
        byte[] block = hpackWith(List.of(new HPACKDynamicTable.HeaderField("Content-Type", "text/plain")));
        writeFrameHeader(out, block.length, 0x1, 0x4 | 0x1 /* END_HEADERS|END_STREAM */, 1);
        out.write(block);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for uppercase header; got: " + errorCode);
      }
    }
  }

  // ─── §8.1.2.1: pseudo-header rules ──────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §8.1.2.1 — unknown pseudo-headers (e.g. {@code :foo}) MUST trigger RST_STREAM(PROTOCOL_ERROR).
   */
  @Test
  public void unknown_pseudo_header_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        byte[] block = hpackExact(List.of(
            new HPACKDynamicTable.HeaderField(":method", "GET"),
            new HPACKDynamicTable.HeaderField(":path", "/"),
            new HPACKDynamicTable.HeaderField(":scheme", "http"),
            new HPACKDynamicTable.HeaderField(":authority", "localhost"),
            new HPACKDynamicTable.HeaderField(":foo", "bar")  // unknown pseudo-header
        ));
        writeFrameHeader(out, block.length, 0x1, 0x4 | 0x1, 1);
        out.write(block);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for unknown pseudo-header :foo; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §8.1.2.1 — response pseudo-header {@code :status} in a client request
   * MUST trigger RST_STREAM(PROTOCOL_ERROR).
   */
  @Test
  public void response_pseudo_header_in_request_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        byte[] block = hpackExact(List.of(
            new HPACKDynamicTable.HeaderField(":method", "GET"),
            new HPACKDynamicTable.HeaderField(":path", "/"),
            new HPACKDynamicTable.HeaderField(":scheme", "http"),
            new HPACKDynamicTable.HeaderField(":authority", "localhost"),
            new HPACKDynamicTable.HeaderField(":status", "200")  // response-only pseudo-header
        ));
        writeFrameHeader(out, block.length, 0x1, 0x4 | 0x1, 1);
        out.write(block);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for :status in request; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §8.1.2.1 — a pseudo-header appearing after a regular header MUST trigger
   * RST_STREAM(PROTOCOL_ERROR).
   */
  @Test
  public void pseudo_header_after_regular_header_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        byte[] block = hpackExact(List.of(
            new HPACKDynamicTable.HeaderField(":method", "GET"),
            new HPACKDynamicTable.HeaderField(":path", "/"),
            new HPACKDynamicTable.HeaderField(":scheme", "http"),
            new HPACKDynamicTable.HeaderField("x-custom", "value"),    // regular header first
            new HPACKDynamicTable.HeaderField(":authority", "localhost")  // pseudo after regular
        ));
        writeFrameHeader(out, block.length, 0x1, 0x4 | 0x1, 1);
        out.write(block);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for pseudo after regular; got: " + errorCode);
      }
    }
  }

  // ─── §8.1.2.2: connection-specific headers ──────────────────────────────────────────────────────

  /**
   * RFC 9113 §8.1.2.2 — {@code Connection} is a connection-specific header forbidden in HTTP/2.
   * Must trigger RST_STREAM(PROTOCOL_ERROR).
   */
  @Test
  public void connection_header_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        byte[] block = hpackWith(List.of(new HPACKDynamicTable.HeaderField("connection", "close")));
        writeFrameHeader(out, block.length, 0x1, 0x4 | 0x1, 1);
        out.write(block);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for Connection header; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §8.1.2.2 — {@code TE} with any value other than {@code trailers} is forbidden.
   * Must trigger RST_STREAM(PROTOCOL_ERROR).
   */
  @Test
  public void te_gzip_header_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        byte[] block = hpackWith(List.of(new HPACKDynamicTable.HeaderField("te", "gzip")));
        writeFrameHeader(out, block.length, 0x1, 0x4 | 0x1, 1);
        out.write(block);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for TE: gzip; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §8.1.2.2 — {@code TE: trailers} is the only allowed TE value in HTTP/2. A valid
   * request with {@code TE: trailers} MUST be accepted and return a 200 response.
   */
  @Test
  public void te_trailers_is_allowed() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        byte[] block = hpackWith(List.of(new HPACKDynamicTable.HeaderField("te", "trailers")));
        writeFrameHeader(out, block.length, 0x1, 0x4 | 0x1, 1);
        out.write(block);
        out.flush();

        sock.setSoTimeout(5000);
        int streamId = readUntilResponseHeaders(sock.getInputStream());
        assertEquals(streamId, 1, "Expected 200 response headers on stream 1 for TE: trailers; got stream: " + streamId);
      }
    }
  }

  // ─── §8.1.2.3: missing required pseudo-headers ──────────────────────────────────────────────────

  /**
   * RFC 9113 §8.1.2.3 — missing {@code :method} MUST trigger RST_STREAM(PROTOCOL_ERROR).
   */
  @Test
  public void missing_method_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        byte[] block = hpackExact(List.of(
            // :method intentionally absent
            new HPACKDynamicTable.HeaderField(":path", "/"),
            new HPACKDynamicTable.HeaderField(":scheme", "http"),
            new HPACKDynamicTable.HeaderField(":authority", "localhost")
        ));
        writeFrameHeader(out, block.length, 0x1, 0x4 | 0x1, 1);
        out.write(block);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for missing :method; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §8.1.2.3 — empty {@code :path} MUST trigger RST_STREAM(PROTOCOL_ERROR).
   */
  @Test
  public void empty_path_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        byte[] block = hpackExact(List.of(
            new HPACKDynamicTable.HeaderField(":method", "GET"),
            new HPACKDynamicTable.HeaderField(":path", ""),  // empty :path — violation
            new HPACKDynamicTable.HeaderField(":scheme", "http"),
            new HPACKDynamicTable.HeaderField(":authority", "localhost")
        ));
        writeFrameHeader(out, block.length, 0x1, 0x4 | 0x1, 1);
        out.write(block);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for empty :path; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §8.1.2.3 — duplicated {@code :method} MUST trigger RST_STREAM(PROTOCOL_ERROR).
   */
  @Test
  public void duplicated_method_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        byte[] block = hpackExact(List.of(
            new HPACKDynamicTable.HeaderField(":method", "GET"),
            new HPACKDynamicTable.HeaderField(":method", "POST"),  // duplicate
            new HPACKDynamicTable.HeaderField(":path", "/"),
            new HPACKDynamicTable.HeaderField(":scheme", "http"),
            new HPACKDynamicTable.HeaderField(":authority", "localhost")
        ));
        writeFrameHeader(out, block.length, 0x1, 0x4 | 0x1, 1);
        out.write(block);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for duplicated :method; got: " + errorCode);
      }
    }
  }

  // ─── §8.1.2.6: content-length mismatch ──────────────────────────────────────────────────────────

  /**
   * RFC 9113 §8.1.2.6 — content-length declared as 5 but DATA payload is 3 bytes: under-delivery.
   * RST_STREAM(PROTOCOL_ERROR) must be sent when END_STREAM arrives.
   */
  @Test
  public void content_length_under_delivery_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> {
      try {
        req.getInputStream().readAllBytes();
      } catch (Exception ignore) {
      }
      res.setStatus(200);
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();

        // HEADERS for a POST with content-length: 5, no END_STREAM.
        byte[] block = hpackExact(List.of(
            new HPACKDynamicTable.HeaderField(":method", "POST"),
            new HPACKDynamicTable.HeaderField(":path", "/"),
            new HPACKDynamicTable.HeaderField(":scheme", "http"),
            new HPACKDynamicTable.HeaderField(":authority", "localhost"),
            new HPACKDynamicTable.HeaderField("content-length", "5")
        ));
        writeFrameHeader(out, block.length, 0x1, 0x4 /* END_HEADERS only */, 1);
        out.write(block);

        // DATA: only 3 bytes, but END_STREAM — content-length mismatch.
        byte[] data = {1, 2, 3};
        writeFrameHeader(out, data.length, 0x0, 0x1 /* END_STREAM */, 1);
        out.write(data);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for content-length under-delivery; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §8.1.2.6 — content-length declared as 3 but DATA payload is 5 bytes: over-delivery.
   * RST_STREAM(PROTOCOL_ERROR) must be sent immediately when the DATA frame exceeds the declared length.
   */
  @Test
  public void content_length_over_delivery_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();

        // HEADERS for a POST with content-length: 3, no END_STREAM.
        byte[] block = hpackExact(List.of(
            new HPACKDynamicTable.HeaderField(":method", "POST"),
            new HPACKDynamicTable.HeaderField(":path", "/"),
            new HPACKDynamicTable.HeaderField(":scheme", "http"),
            new HPACKDynamicTable.HeaderField(":authority", "localhost"),
            new HPACKDynamicTable.HeaderField("content-length", "3")
        ));
        writeFrameHeader(out, block.length, 0x1, 0x4 /* END_HEADERS only */, 1);
        out.write(block);

        // DATA: 5 bytes — exceeds declared content-length of 3.
        byte[] data = {1, 2, 3, 4, 5};
        writeFrameHeader(out, data.length, 0x0, 0x1 /* END_STREAM */, 1);
        out.write(data);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for content-length over-delivery; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §8.1.2.6 sanity check — content-length declared as 5 with exactly 5-byte DATA
   * payload MUST succeed and return a 200 response.
   */
  @Test
  public void content_length_exact_match_succeeds() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> {
      try {
        req.getInputStream().readAllBytes();
      } catch (Exception ignore) {
      }
      res.setStatus(200);
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();

        byte[] block = hpackExact(List.of(
            new HPACKDynamicTable.HeaderField(":method", "POST"),
            new HPACKDynamicTable.HeaderField(":path", "/"),
            new HPACKDynamicTable.HeaderField(":scheme", "http"),
            new HPACKDynamicTable.HeaderField(":authority", "localhost"),
            new HPACKDynamicTable.HeaderField("content-length", "5")
        ));
        writeFrameHeader(out, block.length, 0x1, 0x4 /* END_HEADERS only */, 1);
        out.write(block);

        // DATA: exactly 5 bytes with END_STREAM.
        byte[] data = {1, 2, 3, 4, 5};
        writeFrameHeader(out, data.length, 0x0, 0x1 /* END_STREAM */, 1);
        out.write(data);
        out.flush();

        sock.setSoTimeout(5000);
        int streamId = readUntilResponseHeaders(sock.getInputStream());
        assertEquals(streamId, 1, "Expected 200 response on stream 1 for exact content-length match; got stream: " + streamId);
      }
    }
  }
}

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
 * Integration tests covering h2spec batch-3 failure categories:
 * <ul>
 *   <li>§8.1 — second HEADERS after END_STREAM must produce RST_STREAM, not GOAWAY</li>
 *   <li>§6.4 — RST_STREAM on an idle stream must produce GOAWAY(PROTOCOL_ERROR)</li>
 *   <li>§7 — unknown error codes in RST_STREAM are accepted</li>
 *   <li>§5.1.2 — HEADERS exceeding MAX_CONCURRENT_STREAMS produces RST_STREAM(REFUSED_STREAM)</li>
 * </ul>
 *
 * <p>The invalid-preface GOAWAY fix (§3.5) is covered by
 * {@link HTTP2ConnectionPrefaceTest#invalid_preface_emits_goaway_before_close()}, which exercises
 * the TLS/ALPN path where HTTP2Connection reads the preface directly.
 *
 * @author Daniel DeGroff
 */
public class HTTP2H2SpecBatch3Test extends BaseTest {
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

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // §8.1 — second HEADERS on the same stream (HALF_CLOSED_REMOTE)
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §8.1 — a second HEADERS frame received after END_STREAM (i.e. the stream is
   * HALF_CLOSED_REMOTE from the server's perspective) must produce {@code RST_STREAM(STREAM_CLOSED)}.
   * This is a stream error (§5.4.2), not a connection error — the connection must remain open.
   *
   * <p>The handler holds the stream open briefly so the second HEADERS is processed before stream removal.
   */
  @Test
  public void second_headers_after_end_stream_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    // Small delay ensures the stream is still registered when the second HEADERS arrives.
    HTTPHandler handler = (req, res) -> {
      try { Thread.sleep(100); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
      res.setStatus(200);
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        sock.setSoTimeout(5000);

        // Open stream 1 with END_HEADERS | END_STREAM.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
        out.write(MINIMAL_HPACK_GET);

        // Send a second HEADERS on stream 1 immediately — state violation (HALF_CLOSED_REMOTE).
        // The 100 ms handler delay guarantees the stream is still in the registry when the reader
        // processes this frame.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        // Expect RST_STREAM(STREAM_CLOSED=0x5) on stream 1, not GOAWAY.
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x5, "Expected RST_STREAM(STREAM_CLOSED=0x5); got: " + errorCode);
      }
    }
  }

  /**
   * Connection must remain open after RST_STREAM for a stream violation. A subsequent valid
   * HEADERS request on a new stream ID must be served normally.
   */
  @Test
  public void connection_stays_open_after_stream_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> {
      try { Thread.sleep(100); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
      res.setStatus(200);
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        sock.setSoTimeout(5000);

        // Open stream 1, then violate it with a second HEADERS.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
        out.write(MINIMAL_HPACK_GET);
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
        out.write(MINIMAL_HPACK_GET);

        // Send a valid HEADERS on stream 3 — connection must still be alive.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 3);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        var in = sock.getInputStream();
        // Drain frames until we see a response HEADERS on stream 3. Stream 1's response HEADERS may arrive
        // first (the handler runs concurrently) — skip any HEADERS on other streams.
        int responseStreamId = readUntilResponseHeadersOnStream(in, 3);
        assertEquals(responseStreamId, 3, "Expected response HEADERS on stream 3 after stream-level error on stream 1");
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // §6.4 — RST_STREAM on idle stream
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §6.4 — RST_STREAM on an idle stream (an ID that was never opened, not recently closed)
   * must produce {@code GOAWAY(PROTOCOL_ERROR)}.
   */
  @Test
  public void rst_stream_on_idle_stream_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        sock.setSoTimeout(5000);

        // RST_STREAM on stream 1 — stream 1 was never opened (idle state).
        writeFrameHeader(out, 4, 0x3, 0, 1);
        out.write(new byte[]{0, 0, 0, 0x8}); // CANCEL
        out.flush();

        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for RST_STREAM on idle stream; got: " + errorCode);
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // §7 — unknown error codes in GOAWAY / RST_STREAM are accepted
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §7 — an endpoint receiving an unknown error code in an RST_STREAM MUST NOT treat it
   * as a connection error. The stream is cancelled; the connection stays open.
   */
  @Test
  public void rst_stream_unknown_error_code_accepted() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        sock.setSoTimeout(5000);

        // Open stream 1, then RST it with an unknown error code (0xFF).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
        out.write(MINIMAL_HPACK_GET);
        writeFrameHeader(out, 4, 0x3, 0, 1);
        out.write(new byte[]{0, 0, 0, (byte) 0xFF}); // Unknown error code 0xFF.
        // Follow with a valid request on stream 3 — connection must still be alive.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 3);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        // Expect a response on stream 3 (not GOAWAY). Stream 1 may also respond first — skip it.
        int streamId = readUntilResponseHeadersOnStream(sock.getInputStream(), 3);
        assertEquals(streamId, 3, "Connection must stay open after RST_STREAM with unknown error code");
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // §5.1.2 — concurrent-stream cap: RST_STREAM(REFUSED_STREAM), connection stays open
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §5.1.2 — when a HEADERS frame would exceed MAX_CONCURRENT_STREAMS the server MUST
   * respond with {@code RST_STREAM(REFUSED_STREAM)} (error code {@code 0x7}). The connection
   * stays open and subsequent requests on new streams are served normally.
   */
  @Test
  public void headers_exceeding_concurrent_stream_cap_triggers_refused_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    // Handler that blocks so streams stay open long enough for the concurrent check to trigger.
    HTTPHandler handler = (req, res) -> {
      try {
        Thread.sleep(500);
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
      res.setStatus(200);
    };
    var server = makeServer("http", handler, listener);
    // Set a very small concurrent stream cap so the test triggers quickly.
    server.configuration().withHTTP2MaxConcurrentStreams(2);
    try (var ignored = server.start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        sock.setSoTimeout(5000);

        // Open the maximum number of streams without END_STREAM (keep them open/half-closed-remote).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
        out.write(MINIMAL_HPACK_GET);
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 3);
        out.write(MINIMAL_HPACK_GET);

        // This HEADERS exceeds the cap of 2 — must get RST_STREAM(REFUSED_STREAM=0x7).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 5);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x7, "Expected RST_STREAM(REFUSED_STREAM=0x7) for over-cap HEADERS; got: " + errorCode);
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Opens an h2c prior-knowledge connection and drains the server's initial SETTINGS + SETTINGS ACK.
   */
  private Socket openH2cConnection(int port) throws Exception {
    var sock = new Socket("127.0.0.1", port);
    var out = sock.getOutputStream();
    out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
    out.write(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0}); // empty SETTINGS
    out.flush();

    var in = sock.getInputStream();
    byte[] header = in.readNBytes(9);
    int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
    in.readNBytes(length);
    in.readNBytes(9); // SETTINGS ACK
    return sock;
  }

  /**
   * Writes a 9-byte HTTP/2 frame header.
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
   * Drains inbound frames until GOAWAY (type {@code 0x7}) arrives or EOF. Returns the GOAWAY
   * error code, or {@code -1} on EOF.
   */
  private int readUntilGoaway(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      if (in.readNBytes(rest, 0, 8) != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      byte[] payload = in.readNBytes(length);
      if (type == 0x7) {
        if (payload.length < 8) {
          return -1;
        }
        return ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16) | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
      }
    }
  }

  /**
   * Drains inbound frames until RST_STREAM (type {@code 0x3}) arrives or EOF/GOAWAY. Returns the
   * RST_STREAM error code, or {@code -1} if EOF or GOAWAY arrived first.
   */
  private int readUntilRstStream(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      if (in.readNBytes(rest, 0, 8) != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      byte[] payload = in.readNBytes(length);
      if (type == 0x3) {
        if (payload.length < 4) {
          return -1;
        }
        return ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
      }
      if (type == 0x7) { // GOAWAY — connection error, not a stream error
        return -1;
      }
    }
  }

  /**
   * Drains inbound frames until a HEADERS frame (type {@code 0x1}) on the specified {@code targetStreamId}
   * arrives. Returns {@code targetStreamId} on match, or {@code -1} on EOF.
   */
  private int readUntilResponseHeadersOnStream(InputStream in, int targetStreamId) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      if (in.readNBytes(rest, 0, 8) != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      int streamId = ((rest[4] & 0x7F) << 24) | ((rest[5] & 0xFF) << 16) | ((rest[6] & 0xFF) << 8) | (rest[7] & 0xFF);
      in.readNBytes(length);
      if (type == 0x1 && streamId == targetStreamId) {
        return streamId;
      }
    }
  }

  /**
   * Drains inbound frames until a HEADERS frame (type {@code 0x1}) arrives. Returns the stream ID
   * of the response HEADERS frame, or {@code -1} on EOF.
   */
  private int readUntilResponseHeaders(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      if (in.readNBytes(rest, 0, 8) != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      int streamId = ((rest[4] & 0x7F) << 24) | ((rest[5] & 0xFF) << 16) | ((rest[6] & 0xFF) << 8) | (rest[7] & 0xFF);
      in.readNBytes(length);
      if (type == 0x1) {
        return streamId;
      }
    }
  }
}

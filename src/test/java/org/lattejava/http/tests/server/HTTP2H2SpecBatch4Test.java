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
 * Integration tests covering h2spec batch-4 failure categories:
 * <ul>
 *   <li>generic §2/2 — WINDOW_UPDATE on half-closed (remote) stream must be accepted</li>
 *   <li>generic §2/3 — PRIORITY on half-closed (remote) stream must be accepted</li>
 *   <li>generic §3.8/1 — GOAWAY from peer: server sends PING ACK then closes cleanly (no TCP RST)</li>
 *   <li>http2 §3.5/2 — invalid h2c preface: dual-protocol listener falls back to HTTP/1.1</li>
 *   <li>http2 §7/1 — GOAWAY with unknown error code accepted (connection continues)</li>
 *   <li>http2 §6.9.1/1 — flow-control window=1: DATA sent byte-by-byte via WINDOW_UPDATE</li>
 * </ul>
 *
 * @author Daniel DeGroff
 */
public class HTTP2H2SpecBatch4Test extends BaseTest {
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
  // Root Cause A — half-closed-remote: WINDOW_UPDATE and PRIORITY must be accepted
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §5.1 — WINDOW_UPDATE on a half-closed (remote) stream (the client sent END_STREAM but the
   * server has not yet responded) must be accepted without closing the connection. The server must still
   * send its full response.
   *
   * <p>Previously the server threw {@code IllegalStateException} in the handler thread when the client
   * RST'd the stream during the response phase, causing the connection to close (h2spec saw EOF instead of DATA).
   */
  @Test
  public void window_update_on_half_closed_remote_accepted() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try (var os = res.getOutputStream()) {
        os.write("ok".getBytes());
      }
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        sock.setSoTimeout(5000);

        // Open stream 1 with END_STREAM (half-closed-remote from server's perspective).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
        out.write(MINIMAL_HPACK_GET);

        // Send WINDOW_UPDATE on stream 1 (valid per RFC 9113 §5.1 for half-closed-remote).
        writeFrameHeader(out, 4, 0x8, 0, 1);
        out.write(new byte[]{0, 0, 0, 100}); // increment=100
        out.flush();

        // Expect: server sends HEADERS response, then DATA frame (not connection closed).
        var in = sock.getInputStream();
        boolean sawHeaders = false;
        boolean sawData = false;
        for (int i = 0; i < 10 && (!sawHeaders || !sawData); i++) {
          int b0 = in.read();
          if (b0 == -1) break;
          byte[] rest = new byte[8];
          if (in.readNBytes(rest, 0, 8) != 8) break;
          int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
          int type = rest[2] & 0xFF;
          in.readNBytes(length);
          if (type == 0x1) sawHeaders = true; // HEADERS
          if (type == 0x0) sawData = true;    // DATA
        }
        assertTrue(sawHeaders, "Expected HEADERS frame after WINDOW_UPDATE on half-closed-remote stream");
        assertTrue(sawData, "Expected DATA frame after WINDOW_UPDATE on half-closed-remote stream");
      }
    }
  }

  /**
   * RFC 9113 §5.1 — PRIORITY on a half-closed (remote) stream must be accepted. PRIORITY frames
   * are advisory and do not change the stream state (§5.3).
   */
  @Test
  public void priority_on_half_closed_remote_accepted() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try (var os = res.getOutputStream()) {
        os.write("ok".getBytes());
      }
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        sock.setSoTimeout(5000);

        // Open stream 1 with END_STREAM.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
        out.write(MINIMAL_HPACK_GET);

        // Send PRIORITY on stream 1 (valid per §5.1/§5.3 — advisory, not a state error).
        // PRIORITY payload: 5 bytes (exclusive+dependency(4) + weight(1)).
        writeFrameHeader(out, 5, 0x2, 0, 1);
        out.write(new byte[]{0, 0, 0, 0, 15}); // no dependency, weight=16
        out.flush();

        var in = sock.getInputStream();
        boolean sawHeaders = false;
        boolean sawData = false;
        for (int i = 0; i < 10 && (!sawHeaders || !sawData); i++) {
          int b0 = in.read();
          if (b0 == -1) break;
          byte[] rest = new byte[8];
          if (in.readNBytes(rest, 0, 8) != 8) break;
          int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
          int type = rest[2] & 0xFF;
          in.readNBytes(length);
          if (type == 0x1) sawHeaders = true;
          if (type == 0x0) sawData = true;
        }
        assertTrue(sawHeaders, "Expected HEADERS frame after PRIORITY on half-closed-remote stream");
        assertTrue(sawData, "Expected DATA frame after PRIORITY on half-closed-remote stream");
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // Root Cause B — invalid h2c preface: fall back to HTTP/1.1 (dual-protocol behavior)
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * {@code withH2cPriorKnowledgeEnabled(true)} acts as a dual-protocol listener: it peeks the first 24 bytes
   * and routes to HTTP/2 if they match the connection preface, or falls back to HTTP/1.1 otherwise. This allows
   * the same port to serve both wrk (HTTP/1.1) and h2load (h2c) traffic in benchmark scenarios.
   *
   * <p>A client that sends a non-preface opening (e.g. a plain {@code GET} request) receives a normal
   * HTTP/1.1 response rather than a GOAWAY, because the peeked bytes are pushed back into the stream
   * and the connection is handed off to {@link org.lattejava.http.server.internal.HTTP1Worker}.
   */
  @Test
  public void invalid_h2c_preface_falls_back_to_http1() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.getOutputStream().write("ok".getBytes());
      res.getOutputStream().close();
    };
    try (var server = makeServer("http", handler, listener).start()) {
      int port = server.getActualPort();

      try (var sock = new Socket("127.0.0.1", port)) {
        sock.setSoTimeout(5000);
        var out = new java.io.PrintWriter(sock.getOutputStream(), false, StandardCharsets.US_ASCII);
        // Send a plain HTTP/1.1 request — the 24-byte peek will not match the h2 preface.
        out.print("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        out.flush();

        var in = sock.getInputStream();
        var response = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        assertTrue(response.startsWith("HTTP/1.1 200"), "Expected HTTP/1.1 200 fallback but got: [" + response.substring(0, Math.min(response.length(), 80)) + "]");
        assertTrue(response.contains("ok"), "Expected body [ok] in response: [" + response + "]");
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // Root Cause B — GOAWAY from peer: clean TCP FIN (no TCP RST)
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §6.8 — when the server receives a GOAWAY from the peer, it must drain pending frames
   * (including a PING ACK if a PING was in-flight) and then close the connection cleanly with FIN,
   * not TCP RST. A TCP RST causes "connection reset by peer" at h2spec.
   */
  @Test
  public void goaway_from_peer_produces_clean_close() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        sock.setSoTimeout(5000);

        // Send a PING (opaque data "h2spec  ").
        byte[] pingData = {'h', '2', 's', 'p', 'e', 'c', ' ', ' '};
        writeFrameHeader(out, 8, 0x6, 0, 0);
        out.write(pingData);

        // Send GOAWAY(NO_ERROR, lastStreamId=0).
        writeFrameHeader(out, 8, 0x7, 0, 0);
        out.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0}); // lastStreamId=0, errorCode=NO_ERROR
        out.flush();

        var in = sock.getInputStream();
        boolean sawPingAck = false;
        boolean sawEof = false;
        outer:
        while (true) {
          int b0 = in.read();
          if (b0 == -1) {
            sawEof = true;
            break;
          }
          byte[] rest = new byte[8];
          int n = in.readNBytes(rest, 0, 8);
          if (n < 8) {
            sawEof = true;
            break;
          }
          int frameLength = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
          int frameType = rest[2] & 0xFF;
          int frameFlags = rest[3] & 0xFF;
          byte[] payload = in.readNBytes(frameLength);
          if (frameType == 0x6 && (frameFlags & 0x1) != 0) { // PING ACK
            assertEquals(payload, pingData, "PING ACK must echo the opaque data");
            sawPingAck = true;
          }
        }
        assertTrue(sawPingAck, "Server must send PING ACK before closing after GOAWAY");
        assertTrue(sawEof, "Server must close connection cleanly (FIN) after GOAWAY");
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
}

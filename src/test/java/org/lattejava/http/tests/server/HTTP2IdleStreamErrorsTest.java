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
 * Regression tests for idle-stream and invalid-preface protocol violations:
 * <ul>
 *   <li>§5.1/1 — DATA on an idle stream must produce {@code GOAWAY(PROTOCOL_ERROR)}</li>
 *   <li>§5.1/3 — WINDOW_UPDATE on an idle stream must produce {@code GOAWAY(PROTOCOL_ERROR)}</li>
 *   <li>§3.5/2 — An invalid connection preface must produce SETTINGS + GOAWAY followed by a clean TCP FIN,
 *       not an RST</li>
 * </ul>
 *
 * <p>These were deterministic h2spec failures pre-existing the writer-thread coalescing branch;
 * the investigation surfaced them but did not cause them.
 *
 * @author Daniel DeGroff
 */
public class HTTP2IdleStreamErrorsTest extends BaseHTTP2RawTest {
  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // §5.1/1 — DATA on idle stream
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §5.1 — a stream in the idle state can only receive HEADERS or PRIORITY. A DATA frame on an idle
   * (never-opened) client-initiated stream is a connection-level {@code PROTOCOL_ERROR}.
   *
   * <p>h2spec §5.1/1: server must emit {@code GOAWAY(PROTOCOL_ERROR)} when it receives DATA on a stream that has never
   * been opened.
   */
  @Test
  public void data_on_idle_stream_emits_goaway_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        sock.setSoTimeout(5000);

        // Send a DATA frame on stream 5 — a client-initiated stream that was never opened (idle).
        writeFrameHeader(out, 4, 0x0, 0x1 /* END_STREAM */, 5);
        out.write(new byte[]{0x01, 0x02, 0x03, 0x04});
        out.flush();

        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for DATA on idle stream [5]; got: " + errorCode);
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // §5.1/3 — WINDOW_UPDATE on idle stream
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §5.1 — WINDOW_UPDATE on an idle (never-opened) client-initiated stream is a connection-level
   * {@code PROTOCOL_ERROR}.
   *
   * <p>h2spec §5.1/3: server must emit {@code GOAWAY(PROTOCOL_ERROR)} when it receives WINDOW_UPDATE on a stream that
   * has never been opened.
   */
  @Test
  public void window_update_on_idle_stream_emits_goaway_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        sock.setSoTimeout(5000);

        // Send a WINDOW_UPDATE frame (type 0x8, length 4, increment 1) on stream 7 — idle, never opened.
        writeFrameHeader(out, 4, 0x8, 0, 7);
        out.write(new byte[]{0, 0, 0, 1}); // window size increment = 1
        out.flush();

        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for WINDOW_UPDATE on idle stream [7]; got: " + errorCode);
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // §3.5/2 — Invalid preface ends with GOAWAY + FIN, not RST
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Smoke test for the invalid-preface response path. Verifies the server delivers a GOAWAY frame and closes the
   * connection cleanly (no SocketException on read).
   *
   * <p>RFC 9113 §3.5 — when the client sends the correct connection preface but then sends a frame that is not a
   * SETTINGS frame, the server must respond with {@code GOAWAY(PROTOCOL_ERROR)} and then close the connection cleanly
   * with a TCP FIN, not a RST.
   *
   * <p>h2spec §3.5/2: the server must emit GOAWAY and the client must be able to read it before the connection closes.
   * A TCP RST prevents the client from reading the GOAWAY frame. In Java, RST manifests as a
   * {@code SocketException("Connection reset")} on read; a clean FIN manifests as {@code -1} from
   * {@code InputStream.read()}.
   *
   * <p>The h2c prior-knowledge path is used: {@code ProtocolSelector} validates and consumes the preface, then
   * {@code HTTP2Connection} reads the peer's first frame. RFC 9113 §3.5 requires that first frame to be a SETTINGS
   * frame. Sending anything else triggers the {@code GOAWAY(PROTOCOL_ERROR)} path.
   *
   * <p>NOTE: this test does NOT deterministically detect the RST-vs-FIN regression that motivated the
   * {@code socket.shutdownOutput()} fix in commit fe691cf. On loopback the kernel-side race window is too narrow to
   * reliably trigger RST during a Java {@code Socket.read()}. The test passes with or without the fix in this
   * environment. The {@code shutdownOutput()} fix is mechanically correct (RFC 9113 expects GOAWAY+FIN, not RST, and
   * {@code shutdownOutput()} sends FIN immediately); the regression manifestation requires a slow link or specific
   * kernel tuning to reproduce.
   */
  @Test
  public void invalid_preface_response_completes_cleanly_with_goaway_not_rst() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = new Socket("127.0.0.1", server.getActualPort())) {
        sock.setSoTimeout(5000);
        var out = sock.getOutputStream();

        // Send the valid connection preface — ProtocolSelector will match it and dispatch to HTTP2Connection.
        out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
        // Instead of the required SETTINGS frame, send a PING frame (type 0x6, length 8, stream 0).
        // RFC 9113 §3.5: the first frame after the preface MUST be SETTINGS — anything else is PROTOCOL_ERROR.
        writeFrameHeader(out, 8, 0x6, 0, 0);
        out.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0}); // 8 bytes of PING opaque data
        // Keep writing to simulate h2spec behaviour — it continues sending bytes after the bad frame.
        // This is the race condition: if the server does not half-close before the finally-drain, the OS
        // may emit RST when it sees data arrive after close() on a connection with a non-empty receive buffer.
        for (int i = 0; i < 10; i++) {
          out.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0});
        }
        out.flush();

        // Read all frames until EOF, recording whether we saw a GOAWAY and that no SocketException was thrown.
        var in = sock.getInputStream();
        boolean sawGoaway = false;
        int goawayErrorCode = -1;
        boolean socketReset = false;
        try {
          while (true) {
            int b0 = in.read();
            if (b0 == -1) {
              break; // Clean FIN — what we want.
            }
            byte[] rest = new byte[8];
            int read = in.readNBytes(rest, 0, 8);
            if (read < 8) {
              break;
            }
            int frameLength = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
            int frameType = rest[2] & 0xFF;
            byte[] payload = in.readNBytes(frameLength);
            if (frameType == 0x7 /* GOAWAY */ && payload.length >= 8) {
              goawayErrorCode = ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16)
                  | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
              sawGoaway = true;
            }
          }
        } catch (java.net.SocketException e) {
          // "Connection reset" — OS emitted RST instead of FIN; this is the bug.
          socketReset = true;
        }

        assertFalse(socketReset, "Server sent RST instead of FIN after invalid preface — GOAWAY was not readable");
        assertTrue(sawGoaway, "Server must send GOAWAY before closing on invalid preface");
        assertEquals(goawayErrorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1); got: " + goawayErrorCode);
      }
    }
  }
}

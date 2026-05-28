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
 * </ul>
 *
 * <p>These were deterministic h2spec failures pre-existing the writer-thread coalescing branch;
 * the investigation surfaced them but did not cause them.
 *
 * @author Daniel DeGroff
 */
public class HTTP2IdleStreamErrorsTest extends BaseTest {
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
      try (var sock = openH2cConnection(server.getActualPort())) {
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
   * Drains inbound frames until GOAWAY (type {@code 0x7}) arrives or EOF. Returns the GOAWAY error code, or {@code -1}
   * on EOF.
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
}

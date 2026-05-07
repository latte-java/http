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
 * Verifies that graceful server shutdown emits a {@code GOAWAY(NO_ERROR)} frame to connected HTTP/2 clients.
 *
 * @author Daniel DeGroff
 */
public class HTTP2GoawayTest extends BaseTest {
  /**
   * Establishes a prior-knowledge h2c connection, then closes the server and asserts that a GOAWAY frame
   * (type {@code 0x7}) is received before the connection is torn down.
   */
  @Test
  public void goaway_on_graceful_shutdown() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);

    var server = makeServer("http", handler, listener).start();
    int port = server.getActualPort();

    try (var sock = new Socket("127.0.0.1", port)) {
      var out = sock.getOutputStream();
      // Send connection preface + empty SETTINGS.
      out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
      out.write(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0});
      out.flush();

      var in = sock.getInputStream();
      sock.setSoTimeout(5000);

      // Drain server's initial SETTINGS frame (header + payload).
      byte[] firstHeader = in.readNBytes(9);
      assertEquals(firstHeader.length, 9);
      int firstLength = ((firstHeader[0] & 0xFF) << 16) | ((firstHeader[1] & 0xFF) << 8) | (firstHeader[2] & 0xFF);
      in.readNBytes(firstLength);

      // Also drain SETTINGS ACK — server sends ACK in response to our empty SETTINGS.
      // We must drain all frames until we find GOAWAY, so the loop below handles this.

      // Close the server on a background thread after a short delay.
      Thread.ofVirtual().start(() -> {
        try {
          Thread.sleep(100);
          server.close();
        } catch (Exception ignore) {
        }
      });

      // Read frames until we see GOAWAY (type 0x7) or EOF.
      boolean sawGoaway = false;
      while (true) {
        byte[] header = new byte[9];
        int totalRead = 0;
        while (totalRead < 9) {
          int b = in.read();
          if (b == -1) {
            break;
          }
          header[totalRead++] = (byte) b;
        }
        if (totalRead < 9) {
          break; // EOF before a full frame header.
        }
        int frameLength = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
        int frameType = header[3] & 0xFF;
        in.readNBytes(frameLength);
        if (frameType == 0x7) {
          sawGoaway = true;
          break;
        }
      }
      assertTrue(sawGoaway, "Expected GOAWAY frame on graceful shutdown");
    }
  }
}

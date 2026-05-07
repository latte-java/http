/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTP2H2cUpgradeTest extends BaseTest {
  @Test
  public void upgrade_to_h2c_succeeds() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.getOutputStream().write(req.getProtocol().getBytes());
      res.getOutputStream().close();
    };

    var listener = new HTTPListenerConfiguration(0).withH2cUpgradeEnabled(true);
    try (var server = makeServer("http", handler, listener).start()) {
      int port = server.getActualPort();

      try (var sock = new Socket("127.0.0.1", port)) {
        // Standard h2c-Upgrade handshake. HTTP2-Settings is base64url(empty) = "" — empty payload is legal.
        sock.getOutputStream().write("""
            GET / HTTP/1.1\r
            Host: cyberdyne-systems.com\r
            Connection: Upgrade, HTTP2-Settings\r
            Upgrade: h2c\r
            HTTP2-Settings: \r
            \r
            """.getBytes());
        sock.getOutputStream().flush();

        // Expect: 101 preamble first
        var in = sock.getInputStream();
        byte[] head = new byte[256];
        int n = readHeaderEnd(in, head); // reads up to "\r\n\r\n"
        String preamble = new String(head, 0, n);
        assertTrue(preamble.startsWith("HTTP/1.1 101 "), "Got: " + preamble);
        assertTrue(preamble.contains("Upgrade: h2c") || preamble.contains("upgrade: h2c"));

        // After 101: server starts speaking h2 immediately. Reads 9-byte SETTINGS frame.
        byte[] frameHeader = in.readNBytes(9);
        assertEquals(frameHeader.length, 9);
        assertEquals(frameHeader[3], 0x4, "First post-101 frame from server should be SETTINGS");
      }
    }
  }

  // Helper: read until \r\n\r\n.
  private int readHeaderEnd(InputStream in, byte[] dst) throws Exception {
    int n = 0;
    while (n < dst.length) {
      int b = in.read();
      if (b == -1) return n;
      dst[n++] = (byte) b;
      if (n >= 4 && dst[n - 4] == '\r' && dst[n - 3] == '\n' && dst[n - 2] == '\r' && dst[n - 1] == '\n') {
        return n;
      }
    }
    return n;
  }
}

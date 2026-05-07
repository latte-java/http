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
 * Verifies that prior-knowledge h2c (cleartext HTTP/2 without the Upgrade handshake) is dispatched correctly
 * through {@link org.lattejava.http.server.internal.ProtocolSelector}.
 *
 * @author Daniel DeGroff
 */
public class HTTP2H2cPriorKnowledgeTest extends BaseTest {
  /**
   * Sends a raw HTTP/2 connection preface over a plain TCP socket and asserts that the server responds with its own
   * SETTINGS frame, proving that prior-knowledge dispatch works end-to-end.
   */
  @Test
  public void h2c_prior_knowledge_round_trip() throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getProtocol(), "HTTP/2.0");
      res.setStatus(200);
      res.getOutputStream().write("hello".getBytes());
      res.getOutputStream().close();
    };

    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    try (var server = makeServer("http", handler, listener).start();
         var sock = new Socket("127.0.0.1", server.getActualPort())) {
      var out = sock.getOutputStream();
      // Send connection preface + empty SETTINGS (9 bytes: length=0, type=0x4, flags=0, streamId=0).
      out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
      out.write(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0});
      out.flush();

      var in = sock.getInputStream();
      sock.setSoTimeout(5000);
      byte[] frameHeader = in.readNBytes(9);
      assertEquals(frameHeader.length, 9, "Expected 9-byte frame header from server");
      assertEquals(frameHeader[3], 0x4, "First server frame should be SETTINGS (type 0x4)");
    }
  }
}

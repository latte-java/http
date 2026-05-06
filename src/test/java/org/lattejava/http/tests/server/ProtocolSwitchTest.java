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
 * Tests the 101 Switching Protocols path wired through {@link HTTPResponse#switchProtocols}.
 *
 * @author Daniel DeGroff
 */
public class ProtocolSwitchTest extends BaseTest {
  @Test
  public void switch_protocols_writes_101_then_invokes_handler() throws Exception {
    AtomicBoolean handlerInvoked = new AtomicBoolean(false);
    HTTPHandler handler = (req, res) -> {
      res.switchProtocols("test-proto", Map.of("X-Custom", "yes"), socket -> {
        handlerInvoked.set(true);
        // Echo a single byte after the switch — proves the socket is still live and writable post-101.
        socket.getOutputStream().write('K');
        socket.getOutputStream().flush();
      });
    };

    try (var ignore = makeServer("http", handler).start();
         var sock = new Socket("127.0.0.1", 4242)) {
      sock.getOutputStream().write("""
          GET / HTTP/1.1\r
          Host: cyberdyne-systems.com\r
          Upgrade: test-proto\r
          Connection: Upgrade\r
          \r
          """.getBytes());
      sock.getOutputStream().flush();

      // Read the 101 preamble.
      byte[] readBuf = new byte[256];
      int n = sock.getInputStream().read(readBuf);
      String head = new String(readBuf, 0, n);
      assertTrue(head.startsWith("HTTP/1.1 101 "), "Got: " + head);
      assertTrue(head.contains("Upgrade: test-proto"), "Got: " + head);
      assertTrue(head.contains("X-Custom: yes"), "Got: " + head);

      // The handler writes 'K' immediately after the 101 preamble. It may have arrived in the same buffer
      // read or in a subsequent one — handle both cases.
      int post;
      if (head.endsWith("K")) {
        post = 'K';
      } else {
        post = sock.getInputStream().read();
      }
      assertEquals(post, 'K', "Expected handler to write 'K' after the 101 preamble");
    }

    assertTrue(handlerInvoked.get(), "ProtocolSwitchHandler must run");
  }
}

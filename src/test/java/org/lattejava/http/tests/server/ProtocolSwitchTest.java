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
 * Tests the 101 Switching Protocols path wired through {@link HTTPResponse#switchProtocols}.
 *
 * @author Daniel DeGroff
 */
public class ProtocolSwitchTest extends BaseTest {
  @Test
  public void h2c_upgrade_with_chunked_body_rejected_with_400() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cUpgradeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = new Socket("127.0.0.1", server.getActualPort())) {
        var out = sock.getOutputStream();
        String preamble =
            "POST / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: Upgrade, HTTP2-Settings\r\n"
                + "Upgrade: h2c\r\n"
                + "HTTP2-Settings: \r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "4\r\nbody\r\n0\r\n\r\n";
        out.write(preamble.getBytes());
        out.flush();

        sock.setSoTimeout(5000);
        var in = sock.getInputStream();
        byte[] buf = new byte[256];
        int n = in.read(buf);
        assertTrue(n > 0, "Expected a response from the server");
        String response = new String(buf, 0, n);
        assertTrue(response.startsWith("HTTP/1.1 400"),
            "Expected 400 Bad Request for h2c-Upgrade with chunked body; got [" + response.split("\r\n")[0] + "]");
      }
    }
  }

  @Test
  public void h2c_upgrade_with_request_body_rejected_with_400() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cUpgradeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = new Socket("127.0.0.1", server.getActualPort())) {
        var out = sock.getOutputStream();
        String preamble =
            "POST / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: Upgrade, HTTP2-Settings\r\n"
                + "Upgrade: h2c\r\n"
                + "HTTP2-Settings: \r\n"
                + "Content-Length: 4\r\n"
                + "\r\n"
                + "body";
        out.write(preamble.getBytes());
        out.flush();

        sock.setSoTimeout(5000);
        var in = sock.getInputStream();
        byte[] buf = new byte[256];
        int n = in.read(buf);
        assertTrue(n > 0, "Expected a response from the server");
        String response = new String(buf, 0, n);
        assertTrue(response.startsWith("HTTP/1.1 400"),
            "Expected 400 Bad Request for h2c-Upgrade with body; got [" + response.split("\r\n")[0] + "]");
      }
    }
  }

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

  @Test
  public void rejects_connection_header_in_additional_headers() {
    HTTPResponse res = new HTTPResponse();
    expectThrows(IllegalArgumentException.class, () ->
        res.switchProtocols("test-proto", Map.of("Connection", "keep-alive"), socket -> {
        }));
  }

  @Test
  public void rejects_upgrade_header_in_additional_headers() {
    HTTPResponse res = new HTTPResponse();
    expectThrows(IllegalArgumentException.class, () ->
        res.switchProtocols("test-proto", Map.of("Upgrade", "other-proto"), socket -> {
        }));
  }
}

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
 * Verifies the HTTP/2 connection preface handshake implemented in {@code HTTP2Connection}.
 *
 * @author Daniel DeGroff
 */
public class HTTP2ConnectionPrefaceTest extends BaseTest {
  /**
   * TLS-ALPN path: server negotiates h2, then reads the preface itself. An invalid preface must cause the server to
   * close the connection (EOF from the client's perspective).
   */
  @Test
  public void invalid_preface_closes_connection() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());
    try (var server = makeServer("https", handler, listener).start()) {
      int port = server.getActualPort();

      // Build a TLS client context that trusts our self-signed root CA.
      var ctx = SecurityTools.clientContext(rootCertificate);
      var sslSocket = (javax.net.ssl.SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", port);

      // Force ALPN to h2 so the server dispatches to HTTP2Connection (which will read the preface itself).
      var params = sslSocket.getSSLParameters();
      params.setApplicationProtocols(new String[]{"h2"});
      sslSocket.setSSLParameters(params);

      try (sslSocket) {
        sslSocket.startHandshake();

        var out = sslSocket.getOutputStream();
        // Send a corrupt preface — correct length but wrong content.
        out.write("WRONG * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
        out.flush();

        var in = sslSocket.getInputStream();
        // Server should close. Read should hit EOF.
        int firstByte = in.read();
        assertEquals(firstByte, -1, "Server should close on invalid preface");
      }
    }
  }

  /**
   * h2c prior-knowledge path: ProtocolSelector already consumed the preface, so {@code HTTP2Connection} skips the
   * preface read and goes straight to the SETTINGS exchange.
   */
  @Test
  public void valid_preface_then_settings_completes_handshake() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    try (var server = makeServer("http", handler, listener).start()) {
      int port = server.getActualPort();

      try (var sock = new Socket("127.0.0.1", port)) {
        var out = sock.getOutputStream();
        out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
        out.write(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0}); // empty SETTINGS frame (type=4, flags=0, stream=0, length=0)
        out.flush();

        var in = sock.getInputStream();
        byte[] header = in.readNBytes(9);
        assertEquals(header.length, 9);
        assertEquals(header[3], (byte) 0x4, "Frame type should be SETTINGS"); // server's initial SETTINGS
      }
    }
  }
}

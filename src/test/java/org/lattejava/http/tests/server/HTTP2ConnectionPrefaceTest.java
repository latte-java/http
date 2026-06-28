/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
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
   * TLS-ALPN path: the server negotiates h2 but the client does not send a valid connection preface. ProtocolSelector
   * reconciles the negotiated protocol (h2) against the sniffed protocol (h1, since the bytes are not the preface),
   * finds they disagree, and closes the connection without emitting any frames.
   *
   * <p>RFC 9113 §3.4 explicitly permits omitting GOAWAY here: an invalid connection preface indicates the peer is not
   * using HTTP/2, so there is no HTTP/2 peer to address a GOAWAY to. The server therefore writes nothing — the client
   * observes EOF (or a closed connection) rather than a GOAWAY frame.
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

      // Force ALPN to h2 so the server commits to HTTP/2 during negotiation.
      var params = sslSocket.getSSLParameters();
      params.setApplicationProtocols(new String[]{"h2"});
      sslSocket.setSSLParameters(params);

      try (sslSocket) {
        sslSocket.startHandshake();
        sslSocket.setSoTimeout(5000);

        var out = sslSocket.getOutputStream();
        // Send a corrupt preface — correct length but wrong content.
        out.write("WRONG * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
        out.flush();

        var in = sslSocket.getInputStream();
        // The server must close the connection without sending any frames. Reading yields EOF (-1) on a clean close,
        // or throws on a reset — both are acceptable manifestations of the server dropping a bad client.
        try {
          int first = in.read();
          assertEquals(first, -1, "Server should close the connection on an invalid preface, sending no frames");
        } catch (IOException expected) {
          // Connection reset/closed — also acceptable.
        }
      }
    }
  }

  /**
   * Smoke test: a full HTTP/2 round-trip via the JDK HttpClient over TLS with ALPN. The handler simply sets status 200
   * and writes no body. Verifies that the frame loop, HPACK dispatch, handler-thread spawn, and writer thread all
   * cooperate to produce a valid HTTP/2 response.
   */
  @Test
  public void simple_get_h2_round_trip() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());
    try (var server = makeServer("https", handler, listener).start()) {
      int port = server.getActualPort();
      var sslContext = SecurityTools.clientContext(rootCertificate);
      var client = HttpClient.newBuilder()
                             .sslContext(sslContext)
                             .version(HttpClient.Version.HTTP_2)
                             .build();
      var resp = client.send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/")).build(),
          HttpResponse.BodyHandlers.discarding());
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2);
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

/*
 * Copyright (c) 2026 Latte Java
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
   * TLS-ALPN path: server negotiates h2, then reads the preface itself. An invalid preface must cause the server to
   * send GOAWAY(PROTOCOL_ERROR) and then close the connection — not an abrupt TCP RST.
   *
   * <p>RFC 9113 §3.5 requires the server to emit SETTINGS (and optionally GOAWAY) before closing so the client
   * can observe the protocol error. This was previously a TCP close with no GOAWAY frame.
   */
  @Test
  public void invalid_preface_emits_goaway_before_close() throws Exception {
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
        sslSocket.setSoTimeout(5000);

        var out = sslSocket.getOutputStream();
        // Send a corrupt preface — correct length but wrong content.
        out.write("WRONG * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
        out.flush();

        var in = sslSocket.getInputStream();
        // Server must send GOAWAY(PROTOCOL_ERROR) before closing.
        // Drain frames until GOAWAY or EOF.
        boolean sawGoaway = false;
        int goawayErrorCode = -1;
        outer:
        while (true) {
          int b0 = in.read();
          if (b0 == -1) break;
          byte[] rest = new byte[8];
          if (in.readNBytes(rest, 0, 8) != 8) break;
          int frameLength = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
          int frameType = rest[2] & 0xFF;
          byte[] payload = in.readNBytes(frameLength);
          if (frameType == 0x7) { // GOAWAY
            if (payload.length >= 8) {
              goawayErrorCode = ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16)
                  | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
            }
            sawGoaway = true;
            break outer;
          }
        }
        assertTrue(sawGoaway, "Server must send GOAWAY before closing on invalid preface");
        assertEquals(goawayErrorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1); got: " + goawayErrorCode);
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

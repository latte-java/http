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
 * Verifies ALPN protocol selection for HTTP/2 over TLS.
 *
 * <p>The first test confirms that the server advertises {@code h2} by default and that the JDK client
 * negotiates HTTP/2. The second test confirms that disabling HTTP/2 on the listener causes the server to
 * omit {@code h2} from its ALPN list so the JDK client falls back to HTTP/1.1.
 *
 * @author Daniel DeGroff
 */
public class HTTP2ALPNTest extends BaseTest {
  @Test
  public void alpn_advertises_h2_when_enabled() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    // Default listener has http2Enabled = true.
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
      assertEquals(resp.version(), HttpClient.Version.HTTP_2,
          "Server should advertise h2 via ALPN by default; JDK client silently falls back to h1.1 on ALPN failure");
    }
  }

  @Test
  public void alpn_falls_back_to_http_1_1_when_disabled() throws Exception {
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    // Explicitly disable HTTP/2 on the listener.
    var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate()).withHTTP2Enabled(false);
    try (var server = makeServer("https", handler, listener).start()) {
      int port = server.getActualPort();
      var sslContext = SecurityTools.clientContext(rootCertificate);
      // Even though the client prefers HTTP/2, ALPN negotiation should select http/1.1 because
      // the server does not advertise h2.
      var client = HttpClient.newBuilder()
                             .sslContext(sslContext)
                             .version(HttpClient.Version.HTTP_2)
                             .build();
      var resp = client.send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/")).build(),
          HttpResponse.BodyHandlers.discarding());
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_1_1,
          "Server should fall back to http/1.1 when h2 is disabled on the listener");
    }
  }
}

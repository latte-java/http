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
 * Comprehensive HTTP/2 basic round-trip integration tests via JDK {@link HttpClient} over TLS-ALPN.
 *
 * <p>Every test explicitly asserts {@code resp.version() == HttpClient.Version.HTTP_2} to guard against
 * the JDK client's silent h1.1 fallback that fires when ALPN negotiation fails.
 *
 * @author Daniel DeGroff
 */
public class HTTP2BasicTest extends BaseTest {
  @Test
  public void concurrent_streams_from_one_connection() throws Exception {
    var counter = new AtomicInteger();
    HTTPHandler handler = (req, res) -> {
      counter.incrementAndGet();
      res.setStatus(200);
      res.getOutputStream().write(String.valueOf(counter.get()).getBytes());
      res.getOutputStream().close();
    };

    var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());
    try (var server = makeServer("https", handler, listener).start()) {
      int port = server.getActualPort();
      var sslContext = SecurityTools.clientContext(rootCertificate);
      var client = HttpClient.newBuilder()
                             .sslContext(sslContext)
                             .version(HttpClient.Version.HTTP_2)
                             .build();
      var futures = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
      for (int i = 0; i < 20; i++) {
        var uri = URI.create("https://local.lattejava.org:" + port + "/" + i);
        futures.add(client.sendAsync(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()));
      }
      for (var f : futures) {
        var resp = f.get();
        assertEquals(resp.statusCode(), 200);
        assertEquals(resp.version(), HttpClient.Version.HTTP_2,
            "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
      }
      assertEquals(counter.get(), 20);
    }
  }

  @Test
  public void get_round_trip_h2() throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getProtocol(), "HTTP/2.0");
      res.setStatus(200);
      res.getOutputStream().write("hello".getBytes());
      res.getOutputStream().close();
    };

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
          HttpResponse.BodyHandlers.ofString());
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2,
          "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
      assertEquals(resp.body(), "hello");
    }
  }

  @Test
  public void large_body_exercises_flow_control() throws Exception {
    HTTPHandler handler = (req, res) -> {
      req.getInputStream().readAllBytes();
      res.setStatus(200);
      // Body > INITIAL_WINDOW_SIZE (65535) to exercise flow-control code paths.
      byte[] big = new byte[200_000];
      Arrays.fill(big, (byte) 'a');
      res.getOutputStream().write(big);
      res.getOutputStream().close();
    };

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
          HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2,
          "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
      assertEquals(resp.body().length, 200_000);
    }
  }

  @Test
  public void post_with_body_h2() throws Exception {
    HTTPHandler handler = (req, res) -> {
      byte[] body = req.getInputStream().readAllBytes();
      res.setStatus(200);
      res.getOutputStream().write(body);
      res.getOutputStream().close();
    };

    var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());
    try (var server = makeServer("https", handler, listener).start()) {
      int port = server.getActualPort();
      var sslContext = SecurityTools.clientContext(rootCertificate);
      var client = HttpClient.newBuilder()
                             .sslContext(sslContext)
                             .version(HttpClient.Version.HTTP_2)
                             .build();
      var body = "x".repeat(100_000);
      var resp = client.send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/"))
                     .POST(HttpRequest.BodyPublishers.ofString(body))
                     .build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2,
          "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
      assertEquals(resp.body(), body);
    }
  }
}

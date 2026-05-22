/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import java.time.Duration;

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
  public void h1_only_response_headers_stripped_on_h2() throws Exception {
    HTTPHandler handler = (req, res) -> {
      // Handler ignorantly sets h1.1-only headers — the h2 emission path must strip them.
      res.setHeader("Connection", "close");
      res.setHeader("Transfer-Encoding", "chunked");
      res.setStatus(200);
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
          HttpResponse.BodyHandlers.discarding());
      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2,
          "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
      // The JDK h2 client would reject the response with a PROTOCOL_ERROR if the server actually sent these headers.
      // The fact that the response succeeds proves they were stripped.
      assertFalse(resp.headers().firstValue("connection").isPresent(), "Connection header must be absent in h2 response");
      assertFalse(resp.headers().firstValue("transfer-encoding").isPresent(), "Transfer-Encoding header must be absent in h2 response");
    }
  }

  @Test
  public void concurrent_streams_with_dynamic_headers_no_hpack_corruption() throws Exception {
    // Regression test for Fix 1: HPACKEncoder shared across concurrent handler threads.
    // Each request emits a unique X-Request-Path header value that falls outside the static table,
    // forcing a dynamic-table write on every stream. Without synchronization, concurrent encode()
    // calls corrupt the dynamic table and the client sees HPACK_COMPRESSION_ERROR.
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setHeader("X-Request-Path", req.getPath());  // Forces dynamic-table use, varies per stream.
      res.getOutputStream().write(req.getPath().getBytes());
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
      for (int i = 0; i < 30; i++) {
        var uri = URI.create("https://local.lattejava.org:" + port + "/path-" + i);
        futures.add(client.sendAsync(HttpRequest.newBuilder(uri).build(), HttpResponse.BodyHandlers.ofString()));
      }
      for (int i = 0; i < 30; i++) {
        var resp = futures.get(i).get();
        assertEquals(resp.statusCode(), 200);
        assertEquals(resp.version(), HttpClient.Version.HTTP_2,
            "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
        assertEquals(resp.body(), "/path-" + i);
        assertEquals(resp.headers().firstValue("x-request-path").orElse(""), "/path-" + i);
      }
    }
  }

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
  public void post_h2_enforces_maxRequestBodySize() throws Exception {
    HTTPHandler handler = (req, res) -> {
      byte[] body = req.getInputStream().readAllBytes();
      res.setStatus(200);
      res.getOutputStream().write(body);
      res.getOutputStream().close();
    };

    var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());

    try (var server = makeServer("https", handler, listener)
        .withMaxRequestBodySize(Map.of("*", 1024))  // 1 KB cap
        .start()) {

      int port = server.getActualPort();
      var sslContext = SecurityTools.clientContext(rootCertificate);
      var client = HttpClient.newBuilder()
                             .sslContext(sslContext)
                             .version(HttpClient.Version.HTTP_2)
                             .build();

      // 2 KB body — exceeds the 1 KB cap.
      String oversizedBody = "x".repeat(2048);
      var resp = client.send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/"))
                     .POST(HttpRequest.BodyPublishers.ofString(oversizedBody))
                     .build(),
          HttpResponse.BodyHandlers.discarding());

      assertEquals(resp.statusCode(), 413,
          "HTTP/2 must enforce maxRequestBodySize — expected 413 for 2KB body against 1KB cap");
      // The 413 is sent as a proper HTTP/2 HEADERS frame, so the response must be delivered over h2.
      assertEquals(resp.version(), HttpClient.Version.HTTP_2,
          "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
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

  /**
   * RFC 9113 §5.2 — one slow handler must not stall other streams on the same connection. Stream 1's handler blocks
   * indefinitely without reading its body, filling the 16-slot input pipe and causing the connection reader to enqueue.
   * The reader must time out on pipe.offer() and RST_STREAM(CANCEL) the offending stream rather than blocking — stream
   * 3 must complete despite stream 1 being stuck.
   */
  @Test(timeOut = 30_000)
  public void slow_handler_does_not_stall_other_streams() throws Exception {
    CountDownLatch slowHandlerStarted = new CountDownLatch(1);
    AtomicBoolean releaseHandler = new AtomicBoolean(false);

    HTTPHandler handler = (req, res) -> {
      if (req.getPath().equals("/slow")) {
        slowHandlerStarted.countDown();
        // Spin-wait without consuming body — pipe fills, reader's offer times out → RST_STREAM(CANCEL).
        while (!releaseHandler.get()) {
          try {
            Thread.sleep(50);
          } catch (InterruptedException e) {
            return;
          }
        }
      }
      res.setStatus(200);
    };

    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    var server = makeServer("http", handler, listener);
    // Override default 10s timeout to keep this test snappy.
    server.configuration().withHTTP2HandlerReadTimeout(Duration.ofSeconds(2));
    try (var ignored = server.start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();

        // Stream 1: POST /slow with 20KB body — well over 16 × 1KB. Reader will fill the pipe and then block
        // on offer for 2s, then RST_STREAM.
        byte[] slowHeaders = new byte[]{
            (byte) 0x83, // :method POST
            (byte) 0x44, 0x05, '/', 's', 'l', 'o', 'w', // :path /slow (literal w/ indexing, name idx 4)
            (byte) 0x86, // :scheme http
            (byte) 0x41, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't',
            (byte) 0x5c, 0x05, '2', '0', '4', '8', '0'  // content-length: 20480
        };
        writeFrameHeader(out, slowHeaders.length, 0x1, 0x4 /* END_HEADERS, no END_STREAM */, 1);
        out.write(slowHeaders);

        // Fire data in chunks well past the 16-slot pipe capacity.
        byte[] chunk = new byte[1024];
        for (int i = 0; i < 20; i++) {
          writeFrameHeader(out, chunk.length, 0x0, 0, 1);
          out.write(chunk);
        }
        out.flush();
        assertTrue(slowHandlerStarted.await(3, TimeUnit.SECONDS), "Slow handler must have started");

        // Stream 3: simple GET /fast with END_STREAM — must respond promptly despite stream 1 being stuck.
        byte[] fastHeaders = new byte[]{
            (byte) 0x82, // :method GET
            (byte) 0x44, 0x05, '/', 'f', 'a', 's', 't', // :path /fast
            (byte) 0x86,
            (byte) 0x41, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
        };
        writeFrameHeader(out, fastHeaders.length, 0x1, 0x4 | 0x1, 3);
        out.write(fastHeaders);
        out.flush();

        // Read frames until we see HEADERS on stream 3.
        sock.setSoTimeout(10_000);
        var in = sock.getInputStream();
        int sawResponseOnStreamId = -1;
        for (int i = 0; i < 50 && sawResponseOnStreamId == -1; i++) {
          byte[] hdr = in.readNBytes(9);
          if (hdr.length < 9) {
            break;
          }
          int len = ((hdr[0] & 0xFF) << 16) | ((hdr[1] & 0xFF) << 8) | (hdr[2] & 0xFF);
          int type = hdr[3] & 0xFF;
          int streamId = ((hdr[5] & 0x7F) << 24) | ((hdr[6] & 0xFF) << 16) | ((hdr[7] & 0xFF) << 8) | (hdr[8] & 0xFF);
          byte[] payload = in.readNBytes(len);
          if (payload.length < len) {
            break;
          }
          if (type == 0x1 && streamId == 3) {
            sawResponseOnStreamId = streamId;
          }
        }
        assertEquals(sawResponseOnStreamId, 3, "Expected response HEADERS on stream 3 despite stream 1 stall");

        releaseHandler.set(true);
      }
    }
  }

  /**
   * Opens an h2c prior-knowledge connection and drains the server's initial SETTINGS + SETTINGS ACK.
   */
  private Socket openH2cConnection(int port) throws Exception {
    var sock = new Socket("127.0.0.1", port);
    var out = sock.getOutputStream();
    out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
    out.write(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0}); // empty SETTINGS
    out.flush();

    var in = sock.getInputStream();
    byte[] header = in.readNBytes(9);
    int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
    in.readNBytes(length);
    in.readNBytes(9); // SETTINGS ACK
    return sock;
  }

  /**
   * Writes a 9-byte HTTP/2 frame header.
   */
  private void writeFrameHeader(OutputStream out, int length, int type, int flags, int streamId) throws Exception {
    out.write(new byte[]{
        (byte) ((length >> 16) & 0xFF), (byte) ((length >> 8) & 0xFF), (byte) (length & 0xFF),
        (byte) type, (byte) flags,
        (byte) ((streamId >> 24) & 0x7F), (byte) ((streamId >> 16) & 0xFF),
        (byte) ((streamId >> 8) & 0xFF), (byte) (streamId & 0xFF)
    });
  }
}

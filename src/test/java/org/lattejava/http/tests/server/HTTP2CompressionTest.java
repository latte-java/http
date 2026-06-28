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
 * Verifies HTTP/2 response compression parity with HTTP/1 (gzip + deflate), driven over TLS-ALPN with the JDK client.
 *
 * @author Brian Pontarelli
 */
public class HTTP2CompressionTest extends BaseTest {
  private HttpClient h2Client() throws GeneralSecurityException, IOException {
    var sslContext = SecurityTools.clientContext(rootCertificate);
    return HttpClient.newBuilder().sslContext(sslContext).version(HttpClient.Version.HTTP_2).build();
  }

  private HTTPServer startServer(HTTPHandler handler) {
    var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());
    return makeServer("https", handler, listener).start();
  }

  @Test
  public void deflate_compresses_h2_response() throws Exception {
    String body = "deflate me ".repeat(500);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try (OutputStream out = res.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (var server = startServer(handler)) {
      int port = server.getActualPort();
      var resp = h2Client().send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/"))
                     .header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Deflate)
                     .build(),
          HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2);
      assertEquals(resp.headers().firstValue("content-encoding").orElse(""), HTTPValues.ContentEncodings.Deflate);
      assertEquals(resp.headers().firstValue("vary").orElse(""), HTTPValues.Headers.AcceptEncoding);
      assertFalse(resp.headers().firstValue("content-length").isPresent(), "Content-Length must be removed when compressing");
      assertEquals(new String(inflate(resp.body()), StandardCharsets.UTF_8), body);
    }
  }

  @Test
  public void gzip_compresses_h2_response() throws Exception {
    String body = "Hello world! ".repeat(500);
    HTTPHandler handler = (req, res) -> {
      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setStatus(200);
      try (OutputStream out = res.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (var server = startServer(handler)) {
      int port = server.getActualPort();
      var resp = h2Client().send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/"))
                     .header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Gzip)
                     .build(),
          HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2,
          "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
      assertEquals(resp.headers().firstValue("content-encoding").orElse(""), HTTPValues.ContentEncodings.Gzip);
      assertEquals(resp.headers().firstValue("vary").orElse(""), HTTPValues.Headers.AcceptEncoding);
      assertFalse(resp.headers().firstValue("content-length").isPresent(),
          "Content-Length must be removed when compressing");
      assertEquals(new String(ungzip(resp.body()), StandardCharsets.UTF_8), body);
    }
  }

  @Test
  public void gzip_compresses_streamed_h2_response() throws Exception {
    // Multiple write+flush calls across more than one DATA frame, all gzip-compressed end to end.
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try {
        OutputStream out = res.getOutputStream();
        for (int i = 0; i < 200; i++) {
          out.write(("chunk-" + i + "\n").getBytes(StandardCharsets.UTF_8));
          out.flush();
        }
        out.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    var expected = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      expected.append("chunk-").append(i).append("\n");
    }

    try (var server = startServer(handler)) {
      int port = server.getActualPort();
      var resp = h2Client().send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/"))
                     .header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Gzip)
                     .build(),
          HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2);
      assertEquals(resp.headers().firstValue("content-encoding").orElse(""), HTTPValues.ContentEncodings.Gzip);
      assertEquals(resp.headers().firstValue("vary").orElse(""), HTTPValues.Headers.AcceptEncoding);
      assertFalse(resp.headers().firstValue("content-length").isPresent(), "Content-Length must be removed when compressing");
      assertEquals(new String(ungzip(resp.body()), StandardCharsets.UTF_8), expected.toString());
    }
  }

  @Test
  public void setCompress_false_disables_h2_compression() throws Exception {
    String body = "uncompressed ".repeat(500);
    HTTPHandler handler = (req, res) -> {
      res.setCompress(false);
      res.setStatus(200);
      try (OutputStream out = res.getOutputStream()) {
        out.write(body.getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (var server = startServer(handler)) {
      int port = server.getActualPort();
      var resp = h2Client().send(
          HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/"))
                     .header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Gzip)
                     .build(),
          HttpResponse.BodyHandlers.ofString());

      assertEquals(resp.statusCode(), 200);
      assertEquals(resp.version(), HttpClient.Version.HTTP_2);
      assertFalse(resp.headers().firstValue("content-encoding").isPresent(), "compression disabled — no Content-Encoding");
      assertEquals(resp.body(), body);
    }
  }
}

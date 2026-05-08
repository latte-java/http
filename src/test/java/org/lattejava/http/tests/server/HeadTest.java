/*
 * Copyright (c) 2026, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import java.time.Duration;

/**
 * Tests automatic HEAD request handling at the wire level. Uses raw sockets because the JDK HttpClient will not read
 * body bytes for HEAD responses (per RFC), making it impossible to verify that the server did not write any.
 *
 * @author Brian Pontarelli
 */
public class HeadTest extends BaseSocketTest {
  @Test
  public void head_cdnEscapeHatch_contentLengthSetNoBytesWritten() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setContentType("application/octet-stream");
      if (req.isHeadRequest()) {
        res.setContentLength(104857600L); // 100 MiB
      }
      // Full body generation would go here for GET; not exercised in this test.
    };

    withRequest("""
        HEAD /asset.bin HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: application/octet-stream\r
            content-length: 104857600\r
            \r
            """);
  }

  @Test
  public void head_compressionEnabled_headersPresent_noBody() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(200);
      res.setCompress(true);
      res.setContentType("text/plain");
      res.getOutputStream().write("some content to compress".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
        HEAD / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Accept-Encoding: gzip\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-type: text/plain\r
            content-encoding: gzip\r
            vary: Accept-Encoding\r
            transfer-encoding: chunked\r
            \r
            """);
  }

  @Test
  public void head_getOnlyHandler_writesNoBody() throws Exception {
    HTTPHandler handler = (_, res) -> {
      byte[] body = "Hello World".getBytes(StandardCharsets.UTF_8);
      res.setStatus(200);
      res.setContentLength(body.length);
      res.setContentType("text/plain");
      res.getOutputStream().write(body);
    };

    withRequest("""
        HEAD / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-length: 11\r
            content-type: text/plain\r
            \r
            """);
  }

  @Test
  public void head_handlerSetsBothContentLengthAndTransferEncoding_noWrite_contentLengthStripped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(200);
      res.setContentLength(10L);
      res.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
      // Writes nothing.
    };

    withRequest("""
        HEAD / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            transfer-encoding: chunked\r
            \r
            """);
  }

  @Test
  public void head_handlerSetsBothContentLengthAndTransferEncoding_writesBytes_contentLengthStripped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(200);
      res.setContentLength(10L);
      res.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
      res.getOutputStream().write("0123456789".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
        HEAD / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            transfer-encoding: chunked\r
            \r
            """);
  }

  @Test
  public void head_handlerSetsContentLength_andWritesBody_bodyDropped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      byte[] body = "abcdefgh".getBytes(StandardCharsets.UTF_8);
      res.setStatus(200);
      res.setContentLength(body.length);
      res.getOutputStream().write(body);
    };

    withRequest("""
        HEAD /page HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-length: 8\r
            \r
            """);
  }

  @Test
  public void head_handlerSetsTransferEncodingChunked_noWrite_preserved() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(200);
      res.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
      // Writes nothing — CDN-style handler signals chunked framing but lets HEAD suppress the body.
    };

    withRequest("""
        HEAD / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            transfer-encoding: chunked\r
            \r
            """);
  }

  @Test
  public void head_handlerSetsTransferEncodingChunked_writesBytes_bodyDropped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(200);
      res.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
      res.getOutputStream().write("abcdefgh".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
        HEAD / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            transfer-encoding: chunked\r
            \r
            """);
  }

  @Test
  public void head_handlerWritesNothing_contentLengthZero() throws Exception {
    HTTPHandler handler = (_, res) -> res.setStatus(200);

    withRequest("""
        HEAD / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-length: 0\r
            \r
            """);
  }

  @Test
  public void head_handlerWritesWithoutContentLength_chunkedHeaderPresent_noChunksOnWire() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(200);
      res.getOutputStream().write("abcdefgh".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
        HEAD / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            transfer-encoding: chunked\r
            \r
            """);
  }

  @Test
  public void head_redirect_locationSent_noBody() throws Exception {
    HTTPHandler handler = (_, res) -> res.sendRedirect("https://example.com/new");

    withRequest("""
        HEAD /old HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 302 \r
            connection: keep-alive\r
            location: https://example.com/new\r
            content-length: 0\r
            \r
            """);
  }

  @Test
  public void head_statusNoContent_handlerSetsContentLength_stripped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(204);
      res.setContentLength(50L);
    };

    withRequest("""
        HEAD /empty HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 204 \r
            connection: keep-alive\r
            \r
            """);
  }

  @Test
  public void head_statusNoContent_handlerSetsTransferEncoding_stripped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(204);
      res.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
    };

    withRequest("""
        HEAD /empty HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 204 \r
            connection: keep-alive\r
            \r
            """);
  }

  @Test
  public void head_statusNoContent_handlerWritesBytes_bodyDropped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(204);
      res.getOutputStream().write("hello world".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
        HEAD /empty HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 204 \r
            connection: keep-alive\r
            \r
            """);
  }

  @Test
  public void head_statusNoContent_noContentLengthNoTransferEncoding() throws Exception {
    HTTPHandler handler = (_, res) -> res.setStatus(204);

    withRequest("""
        HEAD /empty HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 204 \r
            connection: keep-alive\r
            \r
            """);
  }

  @Test
  public void head_statusNotModified_handlerWritesBytes_bodyDropped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(304);
      res.getOutputStream().write("cached content".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
        HEAD /etag HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 304 \r
            connection: keep-alive\r
            \r
            """);
  }

  @Test
  public void head_statusNotModified_noContentLengthNoTransferEncoding() throws Exception {
    HTTPHandler handler = (_, res) -> res.setStatus(HTTPValues.Status.NotModified);

    withRequest("""
        HEAD /etag HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 304 \r
            connection: keep-alive\r
            \r
            """);
  }

  @Test
  public void head_thenGet_onSameConnection_bothSucceed() throws Exception {
    HTTPHandler handler = (_, res) -> {
      byte[] body = "Hello".getBytes(StandardCharsets.UTF_8);
      res.setStatus(200);
      res.setContentLength(body.length);
      res.getOutputStream().write(body);
    };

    var server = makeServer("http", handler)
        .withReadThroughputCalculationDelayDuration(Duration.ofMinutes(2))
        .withWriteThroughputCalculationDelayDuration(Duration.ofMinutes(2))
        .withKeepAliveTimeoutDuration(Duration.ofSeconds(23))
        .withInitialReadTimeout(Duration.ofSeconds(19))
        .withProcessingTimeoutDuration(Duration.ofSeconds(27))
        // Suppress auto-Date so the byte-exact HEAD/GET response comparisons below stay deterministic.
        .withSendDateHeader(false);

    try (HTTPServer ignore = server.start();
         Socket socket = makeClientSocket("http")) {

      socket.setSoTimeout((int) Duration.ofSeconds(30).toMillis());
      OutputStream os = socket.getOutputStream();

      // First: HEAD
      os.write("""
          HEAD / HTTP/1.1\r
          Host: cyberdyne-systems.com\r
          \r
          """.getBytes(StandardCharsets.UTF_8));

      assertHTTPResponseEquals(socket, """
          HTTP/1.1 200 \r
          connection: keep-alive\r
          content-length: 5\r
          \r
          """);

      // Second: GET on the same connection
      os.write("""
          GET / HTTP/1.1\r
          Host: cyberdyne-systems.com\r
          \r
          """.getBytes(StandardCharsets.UTF_8));

      assertHTTPResponseEquals(socket, """
          HTTP/1.1 200 \r
          connection: keep-alive\r
          content-length: 5\r
          \r
          Hello""");
    }
  }
}

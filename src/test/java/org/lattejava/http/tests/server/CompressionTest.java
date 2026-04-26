/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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
import module java.net.http;
import module org.lattejava.http;
import module org.testng;
import java.nio.file.Files;

import static org.testng.Assert.*;

/**
 * Tests the HTTP server handles compression properly.
 *
 * @author Brian Pontarelli
 */
public class CompressionTest extends BaseTest {
  private final Path file = Paths.get("src/test/java/org/lattejava/http/tests/server/ChunkedTest.java");

  @DataProvider(name = "chunkedSchemes")
  public Object[][] chunkedSchemes() {
    return new Object[][]{
        {"http", true},
        {"http", false},
        {"https", true},
        {"https", false}
    };
  }

  @Test(dataProvider = "compressedChunkedSchemes")
  public void compress(String scheme, String contentEncoding, String acceptEncoding) throws Exception {
    HTTPHandler handler = (req, res) -> {

      String body = new String(req.getInputStream().readAllBytes());
      assertEquals(body, "Hello world!");

      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setStatus(200);

      // Technically, this is ignored anytime compression is used, but we are testing if folks don't set it here
      res.setContentLength(Files.size(file));

      try (InputStream is = Files.newInputStream(file)) {
        OutputStream outputStream = res.getOutputStream();
        is.transferTo(outputStream);
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    String bodyString = "Hello world!";
    byte[] bodyBytes = bodyString.getBytes(StandardCharsets.UTF_8);
    var payload = bodyBytes;

    if (!contentEncoding.isEmpty()) {
      var requestEncodings = contentEncoding.toLowerCase().trim().split(",");
      for (String part : requestEncodings) {
        String encoding = part.trim();
        if (encoding.equals(HTTPValues.ContentEncodings.Deflate)) {
          payload = deflate(payload);
        } else if (encoding.equals(HTTPValues.ContentEncodings.Gzip) || encoding.equals(HTTPValues.ContentEncodings.XGzip)) {
          payload = gzip(payload);
        }
      }

      byte[] uncompressedBody = payload;

      // Sanity check on round trip compress/decompress
      for (int i = requestEncodings.length - 1; i >= 0; i--) {
        String encoding = requestEncodings[i].trim();
        if (encoding.equals(HTTPValues.ContentEncodings.Deflate)) {
          uncompressedBody = inflate(uncompressedBody);
        } else if (encoding.equals(HTTPValues.ContentEncodings.Gzip) || encoding.equals(HTTPValues.ContentEncodings.XGzip)) {
          uncompressedBody = ungzip(uncompressedBody);
        }
      }

      assertEquals(uncompressedBody, bodyBytes);
      assertEquals(new String(bodyBytes), bodyString);
    }

    var requestPayload = payload;

    // Make the request
    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder()
                     .uri(uri)
                     .header(HTTPValues.Headers.AcceptEncoding, acceptEncoding)
                     .header(HTTPValues.Headers.ContentEncoding, contentEncoding)
                     .header(HTTPValues.Headers.ContentType, "text/plain")
                     // In general, using a BodyPublishers.ofInputStream causes the client to use chunked transfer encoding.
                     // - Manually set the header because the body is small, and it may not chunk it otherwise.
                     .header(HTTPValues.Headers.TransferEncoding, "chunked")
                     .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(requestPayload)))
                     .build(),
          _ -> HttpResponse.BodySubscribers.ofInputStream()
      );

      assertEquals(response.statusCode(), 200);
      String expectedResponseEncoding = null;
      for (String part : acceptEncoding.toLowerCase().trim().split(",")) {
        expectedResponseEncoding = part.trim();
        break;
      }

      assertNotNull(expectedResponseEncoding);

      String result = null;
      InputStream responseInputStream = response.body();

      if (expectedResponseEncoding.isEmpty()) {
        result = new String(responseInputStream.readAllBytes());
      } else {
        assertEquals(response.headers().firstValue(HTTPValues.Headers.ContentEncoding).orElse(null), expectedResponseEncoding);
        if (expectedResponseEncoding.equals(HTTPValues.ContentEncodings.Deflate)) {
          result = new String(new InflaterInputStream(responseInputStream).readAllBytes());
        } else if (expectedResponseEncoding.equals(HTTPValues.ContentEncodings.Gzip) || expectedResponseEncoding.equals(HTTPValues.ContentEncodings.XGzip)) {
          result = new String(new GZIPInputStream(responseInputStream).readAllBytes(), StandardCharsets.UTF_8);
        }
      }

      assertEquals(result, Files.readString(file));
    }
  }

  @Test
  public void compressBadContentLength() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setCompress(true);
      res.setContentLength(1_000_000); // Ignored
      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setStatus(200);

      try (InputStream is = Files.newInputStream(file)) {
        OutputStream outputStream = res.getOutputStream();
        is.transferTo(outputStream);
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    try (var client = makeClient("http", null); var ignore = makeServer("http", handler).withCompressByDefault(false).start()) {
      URI uri = makeURI("http", "");
      var response = client.send(HttpRequest.newBuilder()
                                            .header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Gzip)
                                            .uri(uri)
                                            .GET()
                                            .build(),
          _ -> HttpResponse.BodySubscribers.ofInputStream());
      assertEquals(response.headers().firstValue(HTTPValues.Headers.ContentEncoding).orElse(null), HTTPValues.ContentEncodings.Gzip);
      assertEquals(response.statusCode(), 200);
      assertEquals(new String(new GZIPInputStream(response.body()).readAllBytes(), StandardCharsets.UTF_8), Files.readString(file));
    }
  }

  @Test(groups = "performance")
  public void compressPerformance() throws Exception {
    HTTPHandler handler = (_, res) -> {
      // Use case, do not call response.setCompress(true)
      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setStatus(200);

      try (InputStream is = Files.newInputStream(file)) {
        OutputStream outputStream = res.getOutputStream();
        is.transferTo(outputStream);
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    for (boolean compress : List.of(true, false)) {
      CountingInstrumenter instrumenter = new CountingInstrumenter();
      try (var client = makeClient("http", null); var ignore = makeServer("http", handler, instrumenter).withCompressByDefault(compress).start()) {
        URI uri = makeURI("http", "");
        int counter = 25_000;
        long start = System.currentTimeMillis();
        for (int i = 0; i < counter; i++) {
          var response = compress
              ? client.send(HttpRequest.newBuilder().header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Gzip).uri(uri).GET().build(), _ -> HttpResponse.BodySubscribers.ofInputStream())
              : client.send(HttpRequest.newBuilder().header(HTTPValues.Headers.AcceptEncoding, HTTPValues.ContentEncodings.Gzip).uri(uri).GET().build(), _ -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8));

          var result = compress
              ? new String(new GZIPInputStream((InputStream) response.body()).readAllBytes(), StandardCharsets.UTF_8)
              : response.body();

          assertEquals(response.headers().firstValue(HTTPValues.Headers.ContentEncoding).orElse(null), compress ? HTTPValues.ContentEncodings.Gzip : null);
          assertEquals(response.statusCode(), 200);
          assertEquals(result, Files.readString(file));
        }

        // With small requests like this, we expect compression to be a bit slower.
        //
        // [compression: true ][25,000] requests. Total time [7,677] ms, Avg [0.30708] ms
        // [compression: false][25,000] requests. Total time [3,298] ms, Avg [0.13192] ms

        long end = System.currentTimeMillis();
        long total = end - start;
        @SuppressWarnings("BigDecimalMethodWithoutRoundingCalled")
        BigDecimal avg = new BigDecimal(total).divide(new BigDecimal(counter));
        DecimalFormat formatter = new DecimalFormat("#,###");
        @SuppressWarnings("ConstantConditions")
        String mode = compress ? compress + " " : compress + "";
        println("[compression: " + mode + "][" + formatter.format(counter) + "] requests. Total time [" + (formatter.format(total)) + "] ms, Avg [" + avg + "] ms");
      }
    }
  }

  @Test(dataProvider = "schemes")
  public void compressWithContentLength(String scheme) throws Exception {
    // Use case: Compress the request w/out using chunked transfer encoding.
    // - The JDK rest client is hard to predict sometimes, but I think the body is small enough it won't chunk it up.
    String bodyString = "Hello world!";
    byte[] bodyBytes = bodyString.getBytes(StandardCharsets.UTF_8);
    var payload = gzip(bodyBytes);

    HTTPHandler handler = (req, res) -> {

      assertFalse(req.isChunked());

      // We forced a Content-Length by telling the JDK client not to chunk, so it will be present.
      // - We can still correctly use the Content-Length because it is used by the FixedLengthInputStream which
      //   is below the decompression. See HTTPInputStream.initialize
      var contentLength = req.getHeader(HTTPValues.Headers.ContentLength);
      assertEquals(contentLength, payload.length + "");

      // Because we are compressed, don't expect the final payload to be equal to the contentLength
      byte[] readBytes = req.getInputStream().readAllBytes();
      assertNotEquals(readBytes.length, contentLength);

      String body = new String(readBytes);
      assertEquals(body, bodyString);

      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setStatus(200);
    };

    // This publisher should cause a fixed length request.
    var bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(payload);

    try (var client = makeClient(scheme, null);
         var ignore = makeServer(scheme, handler, null).start()) {

      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder()
                     .uri(uri)
                     .header(HTTPValues.Headers.ContentEncoding, "gzip")
                     .header(HTTPValues.Headers.ContentType, "text/plain")
                     .POST(bodyPublisher)
                     .build(),
          _ -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), "");
    }
  }

  @DataProvider(name = "compressedChunkedSchemes")
  public Object[][] compressedChunkedSchemes() {
    Object[][] compressOptions = new Object[][]{
        // Content-Encoding, Accept-Encoding

        // No compression on request, or response
        {"", ""},

        // Only request
        {"deflate", ""},
        {"gzip", ""},

        // Only response
        {"", "deflate"},
        {"", "gzip"},

        // Same on request and response
        {"deflate", "deflate"},
        {"gzip", "gzip"},

        // UC, and mixed case, http
        {"Deflate", "Deflate"},
        {"Gzip", "Gzip"},

        // Multiple accept values, expect to use the first, http
        {"deflate", "deflate, gzip"},
        {"gzip", "gzip, deflate"},

        // Multiple request values, this means we will use multiple passes of compression, https
        {"deflate, gzip", "deflate, gzip"},
        {"gzip, deflate", "gzip, deflate"},

        // x-gzip with mixed case alias, https
        {"x-gzip", "deflate, gzip"},
        {"X-Gzip, deflate", "gzip, deflate"},
        {"deflate, x-gzip", "gzip, deflate"},
    };

    // For each scheme
    Object[][] schemes = schemes();
    Object[][] result = new Object[compressOptions.length * 2][3];

    int index = 0;
    for (Object[] compressOption : compressOptions) {
      for (Object[] scheme : schemes) {
        result[index][0] = scheme[0];         // scheme
        result[index][1] = compressOption[0]; // Content-Encoding
        result[index][2] = compressOption[1]; // Accept-Encoding
        index++;
      }
    }

    // scheme, Content-Encoding, Accept-Encoding
    return result;
  }

  @Test(dataProvider = "chunkedSchemes")
  public void requestedButNotAccepted(String scheme, boolean chunked) throws Exception {
    // Use case: setCompress(true), but the request does not contain the 'Accept-Encoding' header.
    //     Result: no compression
    HTTPHandler handler = (_, res) -> {
      res.setCompress(true);
      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setStatus(200);

      // Technically, this is ignored anytime compression is used, but we are testing if folks don't set it here
      if (!chunked) {
        res.setContentLength(Files.size(file));
      }

      try (InputStream is = Files.newInputStream(file)) {
        OutputStream outputStream = res.getOutputStream();
        is.transferTo(outputStream);
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).GET().build(),
          _ -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertNull(response.headers().firstValue(HTTPValues.Headers.ContentEncoding).orElse(null));
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), Files.readString(file));
    }
  }

  @Test(dataProvider = "chunkedSchemes")
  public void requestedButNotAccepted_unSupportedEncoding(String scheme, boolean chunked) throws Exception {
    // Use case: setCompress(true), and 'Accept-Encoding: br' which is valid, but not yet supported
    //     Result: no compression
    HTTPHandler handler = (_, res) -> {
      res.setCompress(true);

      // Testing an indecisive user, can't make up their mind... this is allowed as long as you have not written ay bytes.
      res.setCompress(true);
      res.setCompress(false);
      res.setCompress(true);
      res.setCompress(false);
      res.setCompress(true);

      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setStatus(200);

      // Technically, this is ignored anytime compression is used, but we are testing if folks don't set it here
      if (!chunked) {
        res.setContentLength(Files.size(file));
      }

      try (InputStream is = Files.newInputStream(file)) {
        OutputStream outputStream = res.getOutputStream();
        is.transferTo(outputStream);

        // Try to call setCompress, expect an exception - we cannot change modes once we've written to the OutputStream.
        try {
          res.setCompress(false);
          fail("Expected setCompress(false) to fail hard!");
        } catch (IllegalStateException expected) {
        }

        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (var client = makeClient(scheme, null); var ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder().header(HTTPValues.Headers.AcceptEncoding, "br").uri(uri).GET().build(),
          _ -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertNull(response.headers().firstValue(HTTPValues.Headers.ContentEncoding).orElse(null));
      assertEquals(response.statusCode(), 200);
      assertEquals(response.body(), Files.readString(file));
    }
  }

  @Test(dataProvider = "schemes")
  public void unsupportedContentType(String scheme) throws Exception {
    // Use case: Tell the server we encoded the request using 'br'
    HTTPHandler handler = (_, res) -> {
      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setStatus(200);
    };

    // The body isn't actually encoded, that is ok, we will validate that 'br' is not supported when we validate the preamble.
    var bodyPublisher = HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream("Hello World".getBytes(StandardCharsets.UTF_8)));

    try (var client = makeClient(scheme, null);
         var ignore = makeServer(scheme, handler, null).start()) {

      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder()
                     .uri(uri)
                     .header(HTTPValues.Headers.ContentEncoding, "br")
                     .header(HTTPValues.Headers.ContentType, "text/plain")
                     .POST(bodyPublisher)
                     .build(),
          _ -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      // Expect a 415 w/ an empty body response
      assertEquals(response.statusCode(), 415);
      assertEquals(response.body(), "");
    }
  }
}

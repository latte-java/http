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

import java.time.Duration;

import static org.testng.Assert.*;

/**
 * Tests the HTTP server response to 'Expect: 100 Continue'.
 *
 * @author Brian Pontarelli
 */
public class ExpectTest extends BaseTest {
  public static final String ExpectedResponse = "{\"version\":\"42\"}";

  public static final String RequestBody = "{\"message\":\"Hello World\"";

  @Test(dataProvider = "schemes")
  public void expect(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      println("Handling");
      assertEquals(req.getHeader(HTTPValues.Headers.ContentType), "application/json"); // Mixed case

      try {
        println("Reading");
        byte[] body = req.getInputStream().readAllBytes();
        assertEquals(new String(body), RequestBody);
      } catch (IOException e) {
        fail("Unable to parse body", e);
      }

      println("Done");
      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setHeader("Content-Length", "16");
      res.setStatus(200);

      try {
        println("Writing");
        OutputStream outputStream = res.getOutputStream();
        outputStream.write(ExpectedResponse.getBytes());
        outputStream.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    };

    AtomicBoolean validated = new AtomicBoolean(false);
    ExpectValidator validator = (req, res) -> {
      println("Validating");
      validated.set(true);
      assertEquals(req.getContentType(), "application/json");
      assertEquals((long) req.getContentLength(), RequestBody.length());
      res.setStatus(100);
      res.setStatusMessage("Continue");
    };

    // Test w/ and w/out a custom expect validator. The default behavior should be to approve the payload.
    CountingInstrumenter instrumenter = new CountingInstrumenter();
    boolean[] validationOptions = {true, false};
    for (boolean validation : validationOptions) {
      ExpectValidator expectValidator = validation
          ? validator                             // Custom
          : new AlwaysContinueExpectValidator();  // Default

      try (HTTPServer ignore = makeServer(scheme, handler, instrumenter, expectValidator).start(); var client = makeClient(scheme, null)) {
        URI uri = makeURI(scheme, "");
        var response = client.send(
            HttpRequest.newBuilder().uri(uri).header(HTTPValues.Headers.ContentType, "application/json").expectContinue(true).POST(HttpRequest.BodyPublishers.ofString(RequestBody)).build(),
            _ -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        assertEquals(response.statusCode(), 200);
        assertEquals(response.body(), ExpectedResponse);
        assertTrue(validated.get());
      }
    }
  }

  @Test(dataProvider = "schemes")
  public void expectReject(String scheme) throws Exception {
    HTTPHandler handler = (_, _) -> fail("Should not have been called");

    ExpectValidator validator = (req, res) -> {
      println("Validating");
      assertEquals(req.getContentType(), "application/json");
      assertEquals((long) req.getContentLength(), RequestBody.length());
      res.setStatus(417);
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter, validator).start(); var client = makeClient(scheme, null)) {
      URI uri = makeURI(scheme, "");
      var response = client.send(
          HttpRequest.newBuilder().uri(uri).header(HTTPValues.Headers.ContentType, "application/json").expectContinue(true).POST(HttpRequest.BodyPublishers.ofString(RequestBody)).build(),
          _ -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
      );

      assertEquals(response.statusCode(), 417);
      assertEquals(response.body(), "");
    }
  }

  // The JDK HttpClient treats "Expect" as a restricted header (only permits expectContinue(true) which sends "100-continue"), so we
  // use a raw socket to send an arbitrary Expect value and verify the server rejects it with 417 per RFC 9110 §10.1.1.
  @Test
  public void expect_other_value_returns_417() throws Exception {
    AtomicBoolean handlerCalled = new AtomicBoolean(false);
    HTTPHandler handler = (req, res) -> {
      handlerCalled.set(true);
      res.setStatus(200);
    };

    try (HTTPServer ignored = makeServer("http", handler).withSendDateHeader(false).start();
         Socket socket = makeClientSocket("http")) {
      socket.setSoTimeout((int) Duration.ofSeconds(10).toMillis());

      var request = "POST / HTTP/1.1\r\nHost: localhost\r\nExpect: 200-ok\r\nContent-Length: 4\r\n\r\nbody";
      socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));

      assertHTTPResponseEquals(socket, "HTTP/1.1 417 \r\nconnection: close\r\ncontent-length: 0\r\n\r\n");
      assertFalse(handlerCalled.get(), "Handler should not run when Expect is unsupported");
    }
  }
}

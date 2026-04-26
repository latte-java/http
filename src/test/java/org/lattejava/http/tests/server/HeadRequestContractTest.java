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
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Tests the public contract for automatic HEAD handling on HTTPRequest and via a full HTTP request.
 *
 * @author Brian Pontarelli
 */
public class HeadRequestContractTest extends BaseTest {
  @Test
  public void endToEnd_handlerSeesGETMethodButIsHeadRequestTrue() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setHeader("X-Effective-Method", req.getMethod().name());
      res.setHeader("X-Was-Head", Boolean.toString(req.isHeadRequest()));
    };

    try (HTTPServer ignore = makeServer("http", handler).start();
         HttpClient client = HttpClient.newBuilder().connectTimeout(ClientTimeout).build()) {

      HttpRequest request = HttpRequest.newBuilder()
                                       .uri(URI.create("http://localhost:4242/"))
                                       .timeout(ClientTimeout)
                                       .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                       .build();

      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());

      assertEquals(response.statusCode(), 200);
      assertEquals(response.headers().firstValue("X-Effective-Method").orElseThrow(), "GET",
          "Handler must observe getMethod() == GET because the worker rewrote HEAD to GET.");
      assertEquals(response.headers().firstValue("X-Was-Head").orElseThrow(), "true",
          "Handler must observe isHeadRequest() == true because originalMethod captured HEAD.");
    }
  }

  @Test
  public void isHeadRequest_falseByDefault() {
    HTTPRequest request = new HTTPRequest();
    assertFalse(request.isHeadRequest(), "A freshly created HTTPRequest must not report itself as HEAD.");
  }

  @Test
  public void isHeadRequest_falseForOtherMethodsRewrittenToGET() {
    HTTPRequest request = new HTTPRequest();
    request.setMethod(HTTPMethod.POST);
    request.setMethod(HTTPMethod.GET);
    assertFalse(request.isHeadRequest(), "A non-HEAD method cannot become HEAD through a later setMethod call.");
  }

  @Test
  public void isHeadRequest_falseForPlainGET() {
    HTTPRequest request = new HTTPRequest();
    request.setMethod(HTTPMethod.GET);
    assertFalse(request.isHeadRequest(), "A plain GET must never report as HEAD.");
  }

  @Test
  public void isHeadRequest_trueAfterHEADThenGETRewrite() {
    HTTPRequest request = new HTTPRequest();
    request.setMethod(HTTPMethod.HEAD);
    request.setMethod(HTTPMethod.GET);

    assertTrue(request.isHeadRequest(), "After a HEAD->GET rewrite isHeadRequest() must remain true.");
    assertEquals(request.getMethod(), HTTPMethod.GET, "getMethod() must return the effective method (GET).");
  }
}

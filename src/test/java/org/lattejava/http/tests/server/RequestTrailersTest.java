/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class RequestTrailersTest extends BaseSocketTest {
  @Test
  public void chunked_request_trailers_visible_to_handler() throws Exception {
    AtomicReference<Map<String, List<String>>> seen = new AtomicReference<>();
    HTTPHandler handler = (req, res) -> {
      // Drain the body first so trailers are populated.
      req.getInputStream().readAllBytes();
      seen.set(req.getTrailerMap());
      res.setStatus(200);
    };

    withRequest("""
        POST /trailers HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Transfer-Encoding: chunked\r
        \r
        5\r
        hello\r
        0\r
        X-Checksum: abc123\r
        \r
        """)
        .withHandler(handler)
        .expectResponseSubstring("HTTP/1.1 200 ");

    Map<String, List<String>> trailers = seen.get();
    assertNotNull(trailers, "Handler should have run and captured trailer map");
    assertEquals(trailers.get("x-checksum"), List.of("abc123"));
  }
}

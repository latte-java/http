/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class ResponseTrailersTest extends BaseSocketTest {
  @Test
  public void trailers_emitted_when_te_signaled() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setTrailer("X-Checksum", "abc");
      var os = res.getOutputStream();
      os.write("hello".getBytes());
      os.close();
    };

    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        TE: trailers\r
        \r
        """)
        .withHandler(handler)
        .expectResponseSubstring("trailer: x-checksum")
        .expectResponseSubstring("0\r\nx-checksum: abc\r\n\r\n");
  }

  @Test
  public void trailers_dropped_without_te_trailers() throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setTrailer("X-Checksum", "abc");
      var os = res.getOutputStream();
      os.write("hello".getBytes());
      os.close();
    };

    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponseDoesNotContain("x-checksum")
        .expectResponseSubstring("0\r\n\r\n");
  }
}

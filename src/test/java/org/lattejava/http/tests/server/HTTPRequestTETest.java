/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTPRequestTETest extends BaseTest {
  @Test
  public void te_trailers_present() {
    HTTPRequest req = new HTTPRequest();
    req.addHeader("TE", "trailers");
    assertTrue(req.acceptsTrailers());
  }

  @Test
  public void te_trailers_in_token_list() {
    HTTPRequest req = new HTTPRequest();
    req.addHeader("TE", "deflate, trailers");
    assertTrue(req.acceptsTrailers());
  }

  @Test
  public void te_absent() {
    HTTPRequest req = new HTTPRequest();
    assertFalse(req.acceptsTrailers());
  }

  @Test
  public void te_other_token_only() {
    HTTPRequest req = new HTTPRequest();
    req.addHeader("TE", "deflate");
    assertFalse(req.acceptsTrailers());
  }
}

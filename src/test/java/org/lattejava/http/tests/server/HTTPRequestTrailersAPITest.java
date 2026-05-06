/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTPRequestTrailersAPITest extends BaseTest {
  @Test
  public void no_trailers_initially() {
    HTTPRequest req = new HTTPRequest();
    assertFalse(req.hasTrailers());
    assertNull(req.getTrailer("X-Anything"));
    assertEquals(req.getTrailers("X-Anything"), List.of());
    assertTrue(req.getTrailerMap().isEmpty());
  }

  @Test
  public void trailer_added_visible() {
    HTTPRequest req = new HTTPRequest();
    req.addTrailer("X-Checksum", "abc123");
    assertTrue(req.hasTrailers());
    assertEquals(req.getTrailer("X-Checksum"), "abc123");
    assertEquals(req.getTrailers("x-checksum"), List.of("abc123")); // case-insensitive
  }

  @Test
  public void multiple_values_for_same_trailer() {
    HTTPRequest req = new HTTPRequest();
    req.addTrailer("X-Stat", "1");
    req.addTrailer("X-Stat", "2");
    assertEquals(req.getTrailers("X-Stat"), List.of("1", "2"));
    assertEquals(req.getTrailer("X-Stat"), "1"); // first wins for getTrailer
  }
}

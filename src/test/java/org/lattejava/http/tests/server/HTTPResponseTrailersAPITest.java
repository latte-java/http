/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTPResponseTrailersAPITest extends BaseTest {
  @Test
  public void no_trailers_initially() {
    HTTPResponse res = new HTTPResponse();
    assertTrue(res.getTrailers().isEmpty());
  }

  @Test
  public void set_then_get() {
    HTTPResponse res = new HTTPResponse();
    res.setTrailer("X-Checksum", "abc");
    assertEquals(res.getTrailers().get("x-checksum"), List.of("abc"));
  }

  @Test
  public void add_appends() {
    HTTPResponse res = new HTTPResponse();
    res.addTrailer("X-Stat", "1");
    res.addTrailer("X-Stat", "2");
    assertEquals(res.getTrailers().get("x-stat"), List.of("1", "2"));
  }

  @Test
  public void set_replaces() {
    HTTPResponse res = new HTTPResponse();
    res.addTrailer("X-Stat", "1");
    res.setTrailer("X-Stat", "2");
    assertEquals(res.getTrailers().get("x-stat"), List.of("2"));
  }

  @DataProvider
  public Object[][] forbiddenNames() {
    return new Object[][]{
        {"Content-Length"},
        {"Transfer-Encoding"},
        {"Host"},
        {"Authorization"},
        {"Set-Cookie"},
        {"Trailer"},
        {"TE"}
    };
  }

  @Test(dataProvider = "forbiddenNames")
  public void forbidden_name_throws(String name) {
    HTTPResponse res = new HTTPResponse();
    expectThrows(IllegalArgumentException.class, () -> res.setTrailer(name, "x"));
    expectThrows(IllegalArgumentException.class, () -> res.addTrailer(name, "x"));
  }
}

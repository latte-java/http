/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTP1ConfigurationTest {
  @Test
  public void builder_round_trips() {
    var c = new HTTP1Configuration()
        .withChunkedBufferSize(8 * 1024)
        .withMaxRequestChunkSize(2 * 1024 * 1024)
        .withMaxResponseChunkSize(32 * 1024);
    assertEquals(c.getChunkedBufferSize(), 8 * 1024);
    assertEquals(c.getMaxRequestChunkSize(), 2 * 1024 * 1024);
    assertEquals(c.getMaxResponseChunkSize(), 32 * 1024);
  }

  @Test
  public void defaults_are_populated() {
    var c = new HTTP1Configuration();
    assertEquals(c.getChunkedBufferSize(), 4 * 1024);
    assertEquals(c.getMaxRequestChunkSize(), 1024 * 1024);
    assertEquals(c.getMaxResponseChunkSize(), 16 * 1024);
    assertNotNull(c.getExpectValidator());
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void rejects_null_expect_validator() {
    new HTTP1Configuration().withExpectValidator(null);
  }
}

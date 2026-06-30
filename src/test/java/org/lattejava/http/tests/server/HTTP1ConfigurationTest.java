/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import java.time.Duration;

import static org.testng.Assert.*;

public class HTTP1ConfigurationTest {
  @Test
  public void builder_round_trips() {
    var c = new HTTP1Configuration()
        .withChunkedBufferSize(8 * 1024)
        .withKeepAliveTimeoutDuration(Duration.ofSeconds(30))
        .withMaxRequestChunkSize(2 * 1024 * 1024)
        .withMaxRequestsPerConnection(50_000)
        .withMaxResponseChunkSize(32 * 1024);
    assertEquals(c.getChunkedBufferSize(), 8 * 1024);
    assertEquals(c.getKeepAliveTimeoutDuration(), Duration.ofSeconds(30));
    assertEquals(c.getMaxRequestChunkSize(), 2 * 1024 * 1024);
    assertEquals(c.getMaxRequestsPerConnection(), 50_000);
    assertEquals(c.getMaxResponseChunkSize(), 32 * 1024);
  }

  @Test
  public void defaults_are_populated() {
    var c = new HTTP1Configuration();
    assertEquals(c.getChunkedBufferSize(), 4 * 1024);
    assertEquals(c.getKeepAliveTimeoutDuration(), Duration.ofSeconds(20));
    assertEquals(c.getMaxRequestChunkSize(), 1024 * 1024);
    assertEquals(c.getMaxRequestsPerConnection(), 100_000);
    assertEquals(c.getMaxResponseChunkSize(), 16 * 1024);
    assertNotNull(c.getExpectValidator());
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void rejects_small_max_requests_per_connection() {
    new HTTP1Configuration().withMaxRequestsPerConnection(9);
  }

  @Test(expectedExceptions = NullPointerException.class)
  public void rejects_null_expect_validator() {
    new HTTP1Configuration().withExpectValidator(null);
  }
}

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

public class HTTP2ConfigurationTest {
  @Test
  public void builder_round_trips() {
    var c = new HTTP2Configuration()
        .withHandlerReadTimeout(Duration.ofSeconds(2))
        .withHeaderTableSize(8192)
        .withInitialWindowSize(1048576)
        .withMaxConcurrentStreams(50)
        .withMaxFrameSize(32768)
        .withRateLimits(rl -> rl.withPingMax(20));
    assertEquals(c.getHandlerReadTimeout(), Duration.ofSeconds(2));
    assertEquals(c.getHeaderTableSize(), 8192);
    assertEquals(c.getInitialWindowSize(), 1048576);
    assertEquals(c.getMaxConcurrentStreams(), 50);
    assertEquals(c.getMaxFrameSize(), 32768);
    assertEquals(c.getRateLimits().getPingMax(), 20);
  }

  @Test
  public void defaults_match_rfc() {
    var c = new HTTP2Configuration();
    assertEquals(c.getHeaderTableSize(), 4096);
    assertEquals(c.getInitialWindowSize(), 65535);
    assertEquals(c.getMaxConcurrentStreams(), 100);
    assertEquals(c.getMaxFrameSize(), 16384);
    assertEquals(c.getHandlerReadTimeout(), Duration.ofSeconds(10));
    assertNotNull(c.getRateLimits());
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void rejects_above_range_max_frame_size() {
    new HTTP2Configuration().withMaxFrameSize(16777216);
  }

  @Test(expectedExceptions = IllegalArgumentException.class)
  public void rejects_out_of_range_max_frame_size() {
    new HTTP2Configuration().withMaxFrameSize(1024);
  }
}

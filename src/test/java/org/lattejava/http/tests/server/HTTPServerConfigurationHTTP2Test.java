/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.h2.HTTP2Settings;

import static org.testng.Assert.*;

public class HTTPServerConfigurationHTTP2Test {
  @Test
  public void defaults_match_rfc() {
    var h2 = new HTTPServerConfiguration().getHTTP2Configuration();
    assertEquals(h2.getHeaderTableSize(), 4096);
    assertEquals(h2.getInitialWindowSize(), 65535);
    assertEquals(h2.getMaxFrameSize(), 16384);
  }

  @Test
  public void derived_settings_use_shared_max_request_header_size() {
    var c = new HTTPServerConfiguration().withMaxRequestHeaderSize(16384);
    var s = HTTP2Settings.fromConfiguration(c.getHTTP2Configuration(), c.getMaxRequestHeaderSize());
    assertEquals(s.maxHeaderListSize(), 16384);
  }

  @Test
  public void disabled_max_request_header_size_maps_to_unlimited() {
    var c = new HTTPServerConfiguration().withMaxRequestHeaderSize(-1);
    var s = HTTP2Settings.fromConfiguration(c.getHTTP2Configuration(), c.getMaxRequestHeaderSize());
    assertEquals(s.maxHeaderListSize(), Integer.MAX_VALUE);
  }

  @Test
  public void with_http2_header_table_size() {
    var c = new HTTPServerConfiguration().withHTTP2(h2 -> h2.withHeaderTableSize(8192));
    assertEquals(c.getHTTP2Configuration().getHeaderTableSize(), 8192);
  }

  @Test
  public void with_http2_initial_window_size() {
    var c = new HTTPServerConfiguration().withHTTP2(h2 -> h2.withInitialWindowSize(1048576));
    assertEquals(c.getHTTP2Configuration().getInitialWindowSize(), 1048576);
  }

  @Test
  public void with_http2_max_concurrent_streams() {
    var c = new HTTPServerConfiguration().withHTTP2(h2 -> h2.withMaxConcurrentStreams(50));
    assertEquals(c.getHTTP2Configuration().getMaxConcurrentStreams(), 50);
  }

  @Test
  public void with_http2_max_frame_size() {
    var c = new HTTPServerConfiguration().withHTTP2(h2 -> h2.withMaxFrameSize(32768));
    assertEquals(c.getHTTP2Configuration().getMaxFrameSize(), 32768);
  }

  @Test
  public void with_http2_rate_limits() {
    var c = new HTTPServerConfiguration().withHTTP2(h2 -> h2.withRateLimits(rl -> rl.withPingMax(20)));
    assertEquals(c.getHTTP2Configuration().getRateLimits().getPingMax(), 20);
  }
}

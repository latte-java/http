/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2Settings;

import static org.testng.Assert.*;

public class HTTPServerConfigurationHTTP2Test {
  @Test
  public void defaults_match_rfc() {
    var c = new HTTPServerConfiguration();
    var s = c.getHTTP2Settings();
    assertEquals(s.headerTableSize(), 4096);
    assertEquals(s.initialWindowSize(), 65535);
    assertEquals(s.maxFrameSize(), 16384);
  }

  @Test
  public void with_http2_initial_window_size() {
    var c = new HTTPServerConfiguration().withHTTP2InitialWindowSize(1048576);
    assertEquals(c.getHTTP2Settings().initialWindowSize(), 1048576);
  }

  @Test
  public void with_http2_max_concurrent_streams() {
    var c = new HTTPServerConfiguration().withHTTP2MaxConcurrentStreams(50);
    assertEquals(c.getHTTP2Settings().maxConcurrentStreams(), 50);
  }

  @Test
  public void with_http2_max_frame_size() {
    var c = new HTTPServerConfiguration().withHTTP2MaxFrameSize(32768);
    assertEquals(c.getHTTP2Settings().maxFrameSize(), 32768);
  }

  @Test
  public void with_http2_max_header_list_size() {
    var c = new HTTPServerConfiguration().withHTTP2MaxHeaderListSize(16384);
    assertEquals(c.getHTTP2Settings().maxHeaderListSize(), 16384);
  }

  @Test
  public void with_http2_header_table_size() {
    var c = new HTTPServerConfiguration().withHTTP2HeaderTableSize(8192);
    assertEquals(c.getHTTP2Settings().headerTableSize(), 8192);
  }

  @Test
  public void with_http2_settings_ack_timeout() {
    var c = new HTTPServerConfiguration().withHTTP2SettingsAckTimeout(java.time.Duration.ofSeconds(5));
    assertEquals(c.getHTTP2SettingsAckTimeout(), java.time.Duration.ofSeconds(5));
  }

  @Test
  public void with_http2_keep_alive_ping_interval() {
    var c = new HTTPServerConfiguration().withHTTP2KeepAlivePingInterval(java.time.Duration.ofSeconds(30));
    assertEquals(c.getHTTP2KeepAlivePingInterval(), java.time.Duration.ofSeconds(30));
  }

}

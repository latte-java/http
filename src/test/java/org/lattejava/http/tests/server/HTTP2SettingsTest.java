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

public class HTTP2SettingsTest {
  @Test
  public void defaults_match_rfc() {
    HTTP2Settings s = HTTP2Settings.defaults();
    assertEquals(s.headerTableSize(), 4096);
    assertEquals(s.enablePush(), 0);
    assertEquals(s.maxConcurrentStreams(), 100); // Server default = 100 (RFC says unlimited, but conservative default)
    assertEquals(s.initialWindowSize(), 65535);
    assertEquals(s.maxFrameSize(), 16384);
    assertEquals(s.maxHeaderListSize(), Integer.MAX_VALUE);
  }

  @Test
  public void apply_payload_with_two_settings() {
    // SETTINGS_HEADER_TABLE_SIZE (1) = 8192; SETTINGS_INITIAL_WINDOW_SIZE (4) = 1048576
    byte[] payload = {
        0, 1, 0, 0, 0x20, 0,          // id=1, value=8192
        0, 4, 0, 0x10, 0, 0           // id=4, value=1048576
    };
    HTTP2Settings s = HTTP2Settings.defaults();
    s.applyPayload(payload);
    assertEquals(s.headerTableSize(), 8192);
    assertEquals(s.initialWindowSize(), 1048576);
  }

  @Test
  public void apply_payload_unknown_id_ignored() {
    byte[] payload = {0, 99, 0, 0, 0, 0}; // unknown setting id 99
    HTTP2Settings s = HTTP2Settings.defaults();
    s.applyPayload(payload); // should not throw
  }

  @Test
  public void apply_payload_invalid_initial_window_size() {
    // INITIAL_WINDOW_SIZE > 2^31 - 1 → FLOW_CONTROL_ERROR per RFC §6.5.2
    byte[] payload = {0, 4, (byte) 0x80, 0, 0, 0}; // value = 2^31
    HTTP2Settings s = HTTP2Settings.defaults();
    expectThrows(HTTP2Settings.HTTP2SettingsException.class, () -> s.applyPayload(payload));
  }
}

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

  @Test
  public void to_payload_emits_exact_bytes() {
    // The server advertises exactly six settings (36 bytes), big-endian: 2-byte id, 4-byte value.
    byte[] expected = {
        0, 1, 0, 0, 0x10, 0,                               // HEADER_TABLE_SIZE = 4096
        0, 2, 0, 0, 0, 0,                                  // ENABLE_PUSH = 0 (server never pushes)
        0, 3, 0, 0, 0, 0x64,                               // MAX_CONCURRENT_STREAMS = 100
        0, 4, 0, 0, (byte) 0xFF, (byte) 0xFF,              // INITIAL_WINDOW_SIZE = 65535
        0, 5, 0, 0, 0x40, 0,                               // MAX_FRAME_SIZE = 16384
        0, 6, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF  // MAX_HEADER_LIST_SIZE = 2^31-1
    };
    assertEquals(HTTP2Settings.defaults().toPayload(), expected);
  }

  @Test
  public void to_payload_round_trips_through_apply_payload() {
    HTTP2Settings original = HTTP2Settings.defaults()
        .withHeaderTableSize(8192)
        .withMaxConcurrentStreams(250)
        .withInitialWindowSize(1048576)
        .withMaxFrameSize(32768)
        .withMaxHeaderListSize(16384);

    HTTP2Settings parsed = HTTP2Settings.defaults();
    parsed.applyPayload(original.toPayload());

    assertEquals(parsed.headerTableSize(), 8192);
    assertEquals(parsed.maxConcurrentStreams(), 250);
    assertEquals(parsed.initialWindowSize(), 1048576);
    assertEquals(parsed.maxFrameSize(), 32768);
    assertEquals(parsed.maxHeaderListSize(), 16384);
    assertEquals(parsed.enablePush(), 0); // server never pushes
  }
}

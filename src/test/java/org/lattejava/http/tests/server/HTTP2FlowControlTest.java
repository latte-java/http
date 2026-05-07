/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2Stream;

import static org.testng.Assert.*;

public class HTTP2FlowControlTest {
  @Test
  public void send_window_decrements_and_replenishes() {
    var s = new HTTP2Stream(1, 65535, 1000);
    s.consumeSendWindow(400);
    assertEquals(s.sendWindow(), 600);
    s.incrementSendWindow(200);
    assertEquals(s.sendWindow(), 800);
  }

  @Test
  public void send_window_underflow_throws() {
    var s = new HTTP2Stream(1, 65535, 100);
    expectThrows(IllegalStateException.class, () -> s.consumeSendWindow(101));
  }

  @Test
  public void window_overflow_past_signed_int_max_throws() {
    var s = new HTTP2Stream(1, 65535, 1);
    expectThrows(IllegalStateException.class, () -> s.incrementSendWindow(Integer.MAX_VALUE));
  }

  @Test
  public void receive_window_replenishes() {
    var s = new HTTP2Stream(1, 1000, 65535);
    s.consumeReceiveWindow(400);
    assertEquals(s.receiveWindow(), 600);
    s.incrementReceiveWindow(400);
    assertEquals(s.receiveWindow(), 1000);
  }

  @Test
  public void send_window_can_go_negative_after_settings_decrease() {
    // RFC 9113 §6.9.2: when peer reduces SETTINGS_INITIAL_WINDOW_SIZE mid-connection, the delta is applied
    // to all open streams' send-windows — possibly making them negative. The writer must check
    // `available >= bytesToSend` (signed comparison) and wait for WINDOW_UPDATE rather than treating negative as an error.
    var s = new HTTP2Stream(1, 65535, 65535);
    s.consumeSendWindow(50000);                // sendWindow now 15535
    s.incrementSendWindow(-30000);             // peer reduced INITIAL_WINDOW_SIZE by 30000 → window now -14465
    assertEquals(s.sendWindow(), -14465);
    // Writer attempting to send any bytes must observe the negative window and block, not throw.
    assertFalse(s.sendWindow() >= 1);          // no credits available
    s.incrementSendWindow(20000);              // peer sends WINDOW_UPDATE
    assertEquals(s.sendWindow(), 5535);        // back in the black
    assertTrue(s.sendWindow() >= 5000);        // can now send up to 5535
  }
}

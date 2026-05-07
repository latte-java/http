/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2RateLimits;

import static org.testng.Assert.*;

public class HTTP2RateLimitsTest {
  @Test
  public void under_threshold_returns_false() {
    var rl = HTTP2RateLimits.defaults();
    for (int i = 0; i < 100; i++) {
      assertFalse(rl.recordRstStream(), "under-threshold call " + i + " should return false");
    }
  }

  @Test
  public void over_threshold_returns_true() {
    var rl = HTTP2RateLimits.defaults();
    for (int i = 0; i < 100; i++) {
      rl.recordRstStream();
    }
    assertTrue(rl.recordRstStream(), "the 101st call within window should return true");
  }

  @Test
  public void window_expires_old_events() throws Exception {
    var rl = new HTTP2RateLimits(/*rstStreamMax=*/3, /*rstStreamWindowMs=*/100, /*pingMax=*/10, /*pingWindowMs=*/1000, /*settingsMax=*/10, /*settingsWindowMs=*/1000, /*emptyDataMax=*/100, /*emptyDataWindowMs=*/30000, /*windowUpdateMax=*/100, /*windowUpdateWindowMs=*/1000);
    rl.recordRstStream();
    rl.recordRstStream();
    rl.recordRstStream();
    Thread.sleep(150); // exceed window
    assertFalse(rl.recordRstStream(), "old events should have expired");
  }
}

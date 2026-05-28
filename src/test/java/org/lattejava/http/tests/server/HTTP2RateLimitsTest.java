/*
 * Copyright (c) 2026 The Latte Project
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
  public void newTracker_returns_isolated_counters() {
    // Each HTTP/2 connection must get its own tracker. The configuration record is a shared
    // immutable template; calling newTracker() per accept gives the connection an independent
    // ArrayDeque so concurrent connections don't race on the same non-thread-safe collection
    // (and one noisy connection cannot trip the rate limit for everyone else).
    var config = HTTP2RateLimits.defaults();
    var t1 = config.newTracker();
    var t2 = config.newTracker();

    for (int i = 0; i < 100; i++) {
      t1.recordRstStream();
    }
    assertTrue(t1.recordRstStream(), "t1 should have crossed threshold");
    assertFalse(t2.recordRstStream(), "t2 must not be affected by t1");
  }

  @Test
  public void over_threshold_returns_true() {
    var tracker = HTTP2RateLimits.defaults().newTracker();
    for (int i = 0; i < 100; i++) {
      tracker.recordRstStream();
    }
    assertTrue(tracker.recordRstStream(), "the 101st call within window should return true");
  }

  @Test
  public void under_threshold_returns_false() {
    var tracker = HTTP2RateLimits.defaults().newTracker();
    for (int i = 0; i < 100; i++) {
      assertFalse(tracker.recordRstStream(), "under-threshold call " + i + " should return false");
    }
  }

  @Test
  public void window_expires_old_events() throws Exception {
    var config = new HTTP2RateLimits(/*emptyDataMax=*/100, /*emptyDataWindowMs=*/30000, /*pingMax=*/10, /*pingWindowMs=*/1000, /*rstStreamMax=*/3, /*rstStreamWindowMs=*/100, /*settingsMax=*/10, /*settingsWindowMs=*/1000, /*windowUpdateMax=*/100, /*windowUpdateWindowMs=*/1000);
    var tracker = config.newTracker();
    tracker.recordRstStream();
    tracker.recordRstStream();
    tracker.recordRstStream();
    Thread.sleep(150); // exceed window
    assertFalse(tracker.recordRstStream(), "old events should have expired");
  }
}

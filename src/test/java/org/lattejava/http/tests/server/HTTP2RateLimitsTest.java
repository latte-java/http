/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.h2.HTTP2RateLimitsTracker;

import static org.testng.Assert.*;

public class HTTP2RateLimitsTest {
  @Test
  public void separate_trackers_have_isolated_counters() {
    // Each HTTP/2 connection must get its own tracker. The configuration is a shared template; constructing a tracker
    // per accept gives the connection an independent ArrayDeque so concurrent connections don't race on the same
    // non-thread-safe collection (and one noisy connection cannot trip the rate limit for everyone else).
    var config = new HTTP2RateLimits();
    var t1 = new HTTP2RateLimitsTracker(config);
    var t2 = new HTTP2RateLimitsTracker(config);

    for (int i = 0; i < 100; i++) {
      t1.recordRstStream();
    }
    assertTrue(t1.recordRstStream(), "t1 should have crossed threshold");
    assertFalse(t2.recordRstStream(), "t2 must not be affected by t1");
  }

  @Test
  public void over_threshold_returns_true() {
    var tracker = new HTTP2RateLimitsTracker(new HTTP2RateLimits());
    for (int i = 0; i < 100; i++) {
      tracker.recordRstStream();
    }
    assertTrue(tracker.recordRstStream(), "the 101st call within window should return true");
  }

  @Test
  public void under_threshold_returns_false() {
    var tracker = new HTTP2RateLimitsTracker(new HTTP2RateLimits());
    for (int i = 0; i < 100; i++) {
      assertFalse(tracker.recordRstStream(), "under-threshold call " + i + " should return false");
    }
  }

  @Test
  public void window_expires_old_events() throws Exception {
    var config = new HTTP2RateLimits().withRstStreamMax(3).withRstStreamWindowMs(100);
    var tracker = new HTTP2RateLimitsTracker(config);
    tracker.recordRstStream();
    tracker.recordRstStream();
    tracker.recordRstStream();
    Thread.sleep(150); // exceed window
    assertFalse(tracker.recordRstStream(), "old events should have expired");
  }
}

/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * Per-connection sliding-window counters for the five DoS-class attacks. Each counter records timestamps and prunes any older than the configured window. Returns true from `record*` if the threshold has been crossed — caller emits GOAWAY(ENHANCE_YOUR_CALM).
 *
 * Not thread-safe; the reader thread is the sole caller for inbound counters.
 *
 * @author Daniel DeGroff
 */
public class HTTP2RateLimits {
  private final ArrayDeque<Long> emptyData = new ArrayDeque<>();
  private final int emptyDataMax;
  private final long emptyDataWindowMs;
  private final ArrayDeque<Long> ping = new ArrayDeque<>();
  private final int pingMax;
  private final long pingWindowMs;
  private final ArrayDeque<Long> rstStream = new ArrayDeque<>();
  private final int rstStreamMax;
  private final long rstStreamWindowMs;
  private final ArrayDeque<Long> settings = new ArrayDeque<>();
  private final int settingsMax;
  private final long settingsWindowMs;
  private final ArrayDeque<Long> windowUpdate = new ArrayDeque<>();
  private final int windowUpdateMax;
  private final long windowUpdateWindowMs;

  public HTTP2RateLimits(int rstStreamMax, long rstStreamWindowMs, int pingMax, long pingWindowMs, int settingsMax, long settingsWindowMs, int emptyDataMax, long emptyDataWindowMs, int windowUpdateMax, long windowUpdateWindowMs) {
    this.rstStreamMax = rstStreamMax;
    this.rstStreamWindowMs = rstStreamWindowMs;
    this.pingMax = pingMax;
    this.pingWindowMs = pingWindowMs;
    this.settingsMax = settingsMax;
    this.settingsWindowMs = settingsWindowMs;
    this.emptyDataMax = emptyDataMax;
    this.emptyDataWindowMs = emptyDataWindowMs;
    this.windowUpdateMax = windowUpdateMax;
    this.windowUpdateWindowMs = windowUpdateWindowMs;
  }

  public static HTTP2RateLimits defaults() {
    // Defaults from docs/specs/HTTP2.md §10.
    return new HTTP2RateLimits(100, 30_000L, 10, 1_000L, 10, 1_000L, 100, 30_000L, 100, 1_000L);
  }

  public boolean recordEmptyData() { return record(emptyData, emptyDataMax, emptyDataWindowMs); }

  public boolean recordPing() { return record(ping, pingMax, pingWindowMs); }

  public boolean recordRstStream() { return record(rstStream, rstStreamMax, rstStreamWindowMs); }

  public boolean recordSettings() { return record(settings, settingsMax, settingsWindowMs); }

  public boolean recordWindowUpdate() { return record(windowUpdate, windowUpdateMax, windowUpdateWindowMs); }

  private static boolean record(ArrayDeque<Long> q, int max, long windowMs) {
    long now = System.currentTimeMillis();
    long cutoff = now - windowMs;
    while (!q.isEmpty() && q.peekFirst() < cutoff) {
      q.removeFirst();
    }
    q.addLast(now);
    return q.size() > max;
  }
}

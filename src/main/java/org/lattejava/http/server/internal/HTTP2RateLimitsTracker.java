/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * Per-connection sliding-window counters for the five DoS-class HTTP/2 attacks. Each {@code record*} call
 * appends now() to its deque, prunes entries older than the configured window, and returns {@code true} when
 * the per-window threshold has been crossed — the caller emits GOAWAY(ENHANCE_YOUR_CALM).
 *
 * <p>Not thread-safe. Each accepted connection has one reader virtual-thread which is the sole caller for that
 * connection's tracker. Sharing a tracker across connections is a correctness bug: the ArrayDeques would race
 * and the shared counters would trip the threshold prematurely (and could NPE between {@code isEmpty()} and
 * {@code peekFirst()}). Always obtain trackers via {@link HTTP2RateLimits#newTracker()}.
 *
 * @author Daniel DeGroff
 */
public class HTTP2RateLimitsTracker {
  private final HTTP2RateLimits config;
  private final ArrayDeque<Long> emptyData = new ArrayDeque<>();
  private final ArrayDeque<Long> ping = new ArrayDeque<>();
  private final ArrayDeque<Long> rstStream = new ArrayDeque<>();
  private final ArrayDeque<Long> settings = new ArrayDeque<>();
  private final ArrayDeque<Long> windowUpdate = new ArrayDeque<>();

  HTTP2RateLimitsTracker(HTTP2RateLimits config) {
    this.config = config;
  }

  public boolean recordEmptyData() { return record(emptyData, config.emptyDataMax(), config.emptyDataWindowMs()); }

  public boolean recordPing() { return record(ping, config.pingMax(), config.pingWindowMs()); }

  public boolean recordRstStream() { return record(rstStream, config.rstStreamMax(), config.rstStreamWindowMs()); }

  public boolean recordSettings() { return record(settings, config.settingsMax(), config.settingsWindowMs()); }

  public boolean recordWindowUpdate() { return record(windowUpdate, config.windowUpdateMax(), config.windowUpdateWindowMs()); }

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

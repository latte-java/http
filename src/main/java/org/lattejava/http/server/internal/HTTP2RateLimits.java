/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

/**
 * Immutable configuration for the five DoS-class HTTP/2 rate limits (RST_STREAM, PING, SETTINGS, empty DATA,
 * WINDOW_UPDATE). Holds thresholds and windows; no mutable state. Each accepted connection obtains its own
 * {@link HTTP2RateLimitsTracker} via {@link #newTracker()} so per-connection sliding-window counters cannot race —
 * sharing a single tracker across connections corrupted the ArrayDeques under burst load.
 *
 * @author Daniel DeGroff
 */
public record HTTP2RateLimits(
    int emptyDataMax, long emptyDataWindowMs,
    int pingMax, long pingWindowMs,
    int rstStreamMax, long rstStreamWindowMs,
    int settingsMax, long settingsWindowMs,
    int windowUpdateMax, long windowUpdateWindowMs) {

  public static HTTP2RateLimits defaults() {
    // Defaults from docs/design/2026-05-05-HTTP2.md §10.
    return new HTTP2RateLimits(100, 30_000L, 10, 1_000L, 100, 30_000L, 10, 1_000L, 100, 1_000L);
  }

  public HTTP2RateLimitsTracker newTracker() {
    return new HTTP2RateLimitsTracker(this);
  }
}

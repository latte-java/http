/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server;

/**
 * Configuration for the five DoS-class HTTP/2 rate limits (RST_STREAM, PING, SETTINGS, empty DATA, WINDOW_UPDATE).
 * Holds per-frame-type thresholds and sliding-window durations. Instantiated with defaults by
 * {@link HTTP2Configuration} and mutated through {@link HTTP2Configuration#withRateLimits(java.util.function.Consumer)}.
 *
 * @author Daniel DeGroff
 */
@SuppressWarnings("UnusedReturnValue")
public class HTTP2RateLimits {
  private int emptyDataMax = 100;

  private long emptyDataWindowMs = 30_000L;

  private int pingMax = 10;

  private long pingWindowMs = 1_000L;

  private int rstStreamMax = 100;

  private long rstStreamWindowMs = 30_000L;

  private int settingsMax = 10;

  private long settingsWindowMs = 1_000L;

  private int windowUpdateMax = 100;

  private long windowUpdateWindowMs = 1_000L;

  public int getEmptyDataMax() {
    return emptyDataMax;
  }

  public long getEmptyDataWindowMs() {
    return emptyDataWindowMs;
  }

  public int getPingMax() {
    return pingMax;
  }

  public long getPingWindowMs() {
    return pingWindowMs;
  }

  public int getRstStreamMax() {
    return rstStreamMax;
  }

  public long getRstStreamWindowMs() {
    return rstStreamWindowMs;
  }

  public int getSettingsMax() {
    return settingsMax;
  }

  public long getSettingsWindowMs() {
    return settingsWindowMs;
  }

  public int getWindowUpdateMax() {
    return windowUpdateMax;
  }

  public long getWindowUpdateWindowMs() {
    return windowUpdateWindowMs;
  }

  public HTTP2RateLimits withEmptyDataMax(int emptyDataMax) {
    this.emptyDataMax = emptyDataMax;
    return this;
  }

  public HTTP2RateLimits withEmptyDataWindowMs(long emptyDataWindowMs) {
    this.emptyDataWindowMs = emptyDataWindowMs;
    return this;
  }

  public HTTP2RateLimits withPingMax(int pingMax) {
    this.pingMax = pingMax;
    return this;
  }

  public HTTP2RateLimits withPingWindowMs(long pingWindowMs) {
    this.pingWindowMs = pingWindowMs;
    return this;
  }

  public HTTP2RateLimits withRstStreamMax(int rstStreamMax) {
    this.rstStreamMax = rstStreamMax;
    return this;
  }

  public HTTP2RateLimits withRstStreamWindowMs(long rstStreamWindowMs) {
    this.rstStreamWindowMs = rstStreamWindowMs;
    return this;
  }

  public HTTP2RateLimits withSettingsMax(int settingsMax) {
    this.settingsMax = settingsMax;
    return this;
  }

  public HTTP2RateLimits withSettingsWindowMs(long settingsWindowMs) {
    this.settingsWindowMs = settingsWindowMs;
    return this;
  }

  public HTTP2RateLimits withWindowUpdateMax(int windowUpdateMax) {
    this.windowUpdateMax = windowUpdateMax;
    return this;
  }

  public HTTP2RateLimits withWindowUpdateWindowMs(long windowUpdateWindowMs) {
    this.windowUpdateWindowMs = windowUpdateWindowMs;
    return this;
  }
}

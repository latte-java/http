/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

/**
 * Connection-level send flow-control window (RFC 9113 §6.9). Shared by every per-stream writer on a connection:
 * handler threads (via {@link HTTP2OutputStream}) acquire credit before enqueuing DATA, and the reader thread
 * replenishes it on a stream-0 WINDOW_UPDATE. The HTTP/2 connection window starts at the protocol default of 65535
 * octets and, unlike a stream window, is never adjusted by SETTINGS_INITIAL_WINDOW_SIZE.
 *
 * @author Daniel DeGroff
 */
public class HTTP2ConnectionWindow {
  private long window;

  public HTTP2ConnectionWindow(int initial) {
    this.window = initial;
  }

  /**
   * Atomically waits until the window is positive, then consumes {@code min(want, available)} octets and returns the
   * amount consumed. {@code timeoutMillis} bounds each wait so the caller stays responsive to interruption and
   * connection teardown.
   */
  public synchronized int acquire(int want, long timeoutMillis) throws InterruptedException {
    while (window <= 0) {
      wait(timeoutMillis);
    }
    int grant = (int) Math.min(want, window);
    window -= grant;
    return grant;
  }

  public synchronized long available() {
    return window;
  }

  /**
   * Adds {@code delta} octets of credit and wakes any writer blocked in {@link #acquire}. Called by the reader thread
   * on a stream-0 WINDOW_UPDATE.
   */
  public synchronized void increment(int delta) {
    window += delta;
    notifyAll();
  }

  /**
   * Non-blocking, all-or-nothing acquire: consumes {@code want} octets and returns {@code true} only if the full
   * amount is available; otherwise consumes nothing and returns {@code false}. Backs the single-frame fast path in
   * {@link HTTP2OutputStream}.
   */
  public synchronized boolean tryAcquire(int want) {
    if (window < want) {
      return false;
    }
    window -= want;
    return true;
  }
}

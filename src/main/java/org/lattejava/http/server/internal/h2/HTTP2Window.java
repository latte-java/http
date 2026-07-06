/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

/**
 * A flow-control window (RFC 9113 §6.9). One class serves all four windows on a connection: per-stream and
 * connection-level, send and receive. Synchronized throughout; send-side acquirers park on this object's monitor and
 * {@link #increment} wakes them (a no-op costing nanoseconds when nothing waits). Receive-side instances are
 * currently reader-thread-confined, but the synchronization is retained because nothing enforces that confinement.
 *
 * <p>The counter is a {@code long} because RFC 9113 §6.9.2 legitimately drives send windows negative (a
 * SETTINGS_INITIAL_WINDOW_SIZE decrease applies retroactively to open streams).
 */
public class HTTP2Window {
  private long window;

  public HTTP2Window(int initial) {
    this.window = initial;
  }

  /**
   * Atomically waits until the window is positive, then consumes {@code min(numberOfBytes, available)} octets and
   * returns the amount consumed — partial grants are the contract, backing the min-of-two-windows acquisition in
   * {@link HTTP2OutputStream}. {@code timeoutMillis} bounds each park, not the total wait, keeping the caller
   * responsive to interruption and connection teardown.
   */
  public synchronized int acquire(int numberOfBytes, long timeoutMillis) throws InterruptedException {
    while (window <= 0) {
      wait(timeoutMillis);
    }
    int grant = (int) Math.min(numberOfBytes, window);
    window -= grant;
    return grant;
  }

  public synchronized long available() {
    return window;
  }

  /**
   * Consumes {@code numberOfBytes} if fully available; consumes nothing and returns {@code false} otherwise.
   * Receive-side debits use this and map {@code false} to FLOW_CONTROL_ERROR at their scope (stream or connection).
   */
  public synchronized boolean decrement(int numberOfBytes) {
    return tryAcquire(numberOfBytes);
  }

  /**
   * Adds signed credit and wakes parked acquirers. Negative deltas are legal (RFC 9113 §6.9.2). Also serves as
   * "release" for returning surplus acquired credit. Throws past 2^31-1 as a defensive backstop — protocol-level
   * overflow is pre-checked by callers, which map it to the RFC error codes.
   */
  public synchronized void increment(int numberOfBytes) {
    long next = window + numberOfBytes;
    if (next > Integer.MAX_VALUE) {
      throw new IllegalStateException("Window overflow past 2^31-1: [" + window + "] + [" + numberOfBytes + "]");
    }
    window = next;
    notifyAll();
  }

  /**
   * Non-blocking, all-or-nothing acquire: consumes {@code numberOfBytes} and returns {@code true} only if the full
   * amount is available; otherwise consumes nothing and returns {@code false}. Backs the single-frame fast path in
   * {@link HTTP2OutputStream}.
   */
  public synchronized boolean tryAcquire(int numberOfBytes) {
    if (window < numberOfBytes) {
      return false;
    }
    window -= numberOfBytes;
    return true;
  }
}

/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.h2.HTTP2Stream;
import org.lattejava.http.server.internal.h2.HTTP2Window;

import static org.testng.Assert.*;

public class HTTP2FlowControlTest {
  // The connection-level send window (RFC 9113 §6.9) is all-or-nothing on tryAcquire, grants min(want, available) on
  // the blocking acquire, and is replenished by stream-0 WINDOW_UPDATE via increment.
  @Test
  public void connection_window_try_acquire_is_all_or_nothing() {
    var w = new HTTP2Window(100);
    assertFalse(w.tryAcquire(101));
    assertEquals(w.available(), 100);
    assertTrue(w.tryAcquire(60));
    assertEquals(w.available(), 40);
  }

  @Test
  public void connection_window_acquire_grants_min_and_increment_replenishes() throws Exception {
    var w = new HTTP2Window(20);
    assertEquals(w.acquire(50, 100), 20); // want 50, only 20 available
    assertEquals(w.available(), 0);
    w.increment(15);
    assertEquals(w.available(), 15);
  }

  @Test
  public void send_window_decrements_and_replenishes() {
    var s = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 1000, null, null);
    assertTrue(s.sendWindow().decrement(400));
    assertEquals(s.sendWindow().available(), 600);
    s.sendWindow().increment(200);
    assertEquals(s.sendWindow().available(), 800);
  }

  @Test
  public void send_window_underflow_returns_false_and_does_not_mutate() {
    var s = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 100, null, null);
    assertFalse(s.sendWindow().decrement(101));
    assertEquals(s.sendWindow().available(), 100);
  }

  @Test
  public void window_overflow_past_signed_int_max_throws() {
    var s = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 1, null, null);
    expectThrows(IllegalStateException.class, () -> s.sendWindow().increment(Integer.MAX_VALUE));
  }

  // tryAcquire on the send window is all-or-nothing and atomic: it consumes the full requested amount or nothing. This
  // is the single-frame fast path; making the check and consume one synchronized step closes the TOCTOU where a
  // concurrent SETTINGS-induced window decrease could land between a separate available() read and decrement() call.
  @Test
  public void try_acquire_send_window_is_all_or_nothing() {
    var s = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 100, null, null);
    assertFalse(s.sendWindow().tryAcquire(101)); // not enough credit
    assertEquals(s.sendWindow().available(), 100);        // consumed nothing
    assertTrue(s.sendWindow().tryAcquire(60));   // enough credit
    assertEquals(s.sendWindow().available(), 40);
  }

  // acquire grants min(want, available) atomically when credit is already present, never over-consuming.
  @Test
  public void acquire_send_window_grants_min_of_want_and_available() throws Exception {
    var s = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 30, null, null);
    assertEquals(s.sendWindow().acquire(100, 100), 30); // want 100, only 30 available
    assertEquals(s.sendWindow().available(), 0);
  }

  // acquire blocks while the window is non-positive and resumes when a WINDOW_UPDATE replenishes it.
  @Test
  public void acquire_send_window_blocks_until_replenished() throws Exception {
    var s = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 0, null, null);
    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(50);
        s.sendWindow().increment(25);
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
    });
    assertEquals(s.sendWindow().acquire(40, 100), 25); // blocks, then grants the replenished 25
    assertEquals(s.sendWindow().available(), 0);
  }

  // increment(positive) returns unused credit acquired but not used (e.g. when the connection window was the tighter bound).
  @Test
  public void release_send_window_returns_unused_credit() {
    var s = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 100, null, null);
    assertTrue(s.sendWindow().tryAcquire(100));
    assertEquals(s.sendWindow().available(), 0);
    s.sendWindow().increment(40);
    assertEquals(s.sendWindow().available(), 40);
  }

  @Test
  public void receive_window_replenishes() {
    var s = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 1000, 65535, null, null);
    assertTrue(s.receiveWindow().decrement(400));
    assertEquals(s.receiveWindow().available(), 600);
    s.receiveWindow().increment(400);
    assertEquals(s.receiveWindow().available(), 1000);
  }

  @Test
  public void send_window_can_go_negative_after_settings_decrease() {
    // RFC 9113 §6.9.2: when peer reduces SETTINGS_INITIAL_WINDOW_SIZE mid-connection, the delta is applied
    // to all open streams' send-windows — possibly making them negative. The writer must check
    // `available >= bytesToSend` (signed comparison) and wait for WINDOW_UPDATE rather than treating negative as an error.
    var s = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 65535, null, null);
    assertTrue(s.sendWindow().decrement(50000));         // sendWindow now 15535
    s.sendWindow().increment(-30000);                    // peer reduced INITIAL_WINDOW_SIZE by 30000 → window now -14465
    assertEquals(s.sendWindow().available(), -14465);
    // Writer attempting to send any bytes must observe the negative window and block, not throw.
    assertFalse(s.sendWindow().available() >= 1);        // no credits available
    s.sendWindow().increment(20000);                     // peer sends WINDOW_UPDATE
    assertEquals(s.sendWindow().available(), 5535);      // back in the black
    assertTrue(s.sendWindow().available() >= 5000);      // can now send up to 5535
  }
}

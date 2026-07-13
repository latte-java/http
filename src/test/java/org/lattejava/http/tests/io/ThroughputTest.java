/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.io;

import module org.testng;

import org.lattejava.http.server.io.Throughput;

import static org.testng.Assert.*;

public class ThroughputTest {
  @Test
  public void resetReadDoesNotTouchTheWriteSide() {
    var t = new Throughput(0, 0);
    t.wrote(500);
    t.resetRead();
    assertTrue(t.lastUsed() != Long.MAX_VALUE, "lastWroteInstant must survive resetRead");
  }

  @Test
  public void resetReadStartsAFreshEpoch() throws Exception {
    var t = new Throughput(0, 0); // Zero calculation delay: rate math applies immediately.
    t.read(500);
    Thread.sleep(30);
    t.read(500);              // 1000 bytes over ~30ms - a high rate.
    t.wrote(100);             // Written bytes disable the read-delay grace, as in production after a response.

    long stale = t.readThroughput(System.currentTimeMillis());
    assertTrue(stale > 10_000, "Expected a healthy rate, got [" + stale + "]");

    t.resetRead();
    // Fresh epoch: no reads yet - the same guard as a brand-new connection (Long.MAX_VALUE).
    assertEquals(t.readThroughput(System.currentTimeMillis()), Long.MAX_VALUE);

    t.read(100);
    Thread.sleep(30);
    t.read(100);
    long fresh = t.readThroughput(System.currentTimeMillis());
    // Rate computed from the 200 bytes over ~30ms of THIS epoch, not the 1000-byte history.
    assertTrue(fresh > 1_000 && fresh < 100_000, "Expected an epoch-local rate, got [" + fresh + "]");
  }

  @Test
  public void resetWriteStartsAFreshEpoch() {
    var t = new Throughput(0, 0);
    t.wrote(1_000_000);
    t.resetWrite();
    assertEquals(t.writeThroughput(System.currentTimeMillis()), Long.MAX_VALUE);
  }
}

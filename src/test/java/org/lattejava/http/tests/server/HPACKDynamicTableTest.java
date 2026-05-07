/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HPACKDynamicTable;

import static org.testng.Assert.*;

public class HPACKDynamicTableTest {
  @Test
  public void empty_table_has_size_zero() {
    var t = new HPACKDynamicTable(4096);
    assertEquals(t.size(), 0);
    assertEquals(t.entryCount(), 0);
  }

  @Test
  public void add_one_entry() {
    var t = new HPACKDynamicTable(4096);
    t.add(":status", "200");
    assertEquals(t.entryCount(), 1);
    // size = name(7) + value(3) + 32 = 42
    assertEquals(t.size(), 42);
    assertEquals(t.get(0).name(), ":status");
    assertEquals(t.get(0).value(), "200");
  }

  @Test
  public void evicts_when_over_capacity() {
    var t = new HPACKDynamicTable(80); // tight
    t.add("a", "1");  // 1+1+32 = 34
    t.add("b", "2");  // 1+1+32 = 34, total 68
    t.add("c", "3");  // 1+1+32 = 34, total 102 — must evict oldest
    assertEquals(t.entryCount(), 2);
    assertEquals(t.get(0).name(), "c");
    assertEquals(t.get(1).name(), "b");
  }

  @Test
  public void resize_evicts() {
    var t = new HPACKDynamicTable(4096);
    t.add("a", "1");
    t.add("b", "2");
    t.setMaxSize(0);
    assertEquals(t.entryCount(), 0);
  }

  @Test
  public void max_size_zero_accepts_no_entries() {
    // RFC 7541 §6.3 — peer can advertise HEADER_TABLE_SIZE=0 to disable compression. Decoder must not NPE / div-by-zero.
    var t = new HPACKDynamicTable(0);
    t.add("a", "1");
    assertEquals(t.entryCount(), 0);
    assertEquals(t.size(), 0);
  }
}

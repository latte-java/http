/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

import java.util.ArrayDeque;

/**
 * RFC 7541 §2.3.2 dynamic table. Entries indexed from most-recently-added (index 0) to oldest. Size is the sum of (name.length + value.length + 32) over all entries; entries evicted from the tail when adding would exceed maxSize.
 *
 * @author Daniel DeGroff
 */
public class HPACKDynamicTable {
  private final ArrayDeque<HeaderField> entries = new ArrayDeque<>();
  private int maxSize;
  private int size;

  public HPACKDynamicTable(int maxSize) {
    this.maxSize = maxSize;
  }

  public void add(String name, String value) {
    int entrySize = name.length() + value.length() + 32;
    while (size + entrySize > maxSize && !entries.isEmpty()) {
      var evicted = entries.removeLast();
      size -= evicted.name().length() + evicted.value().length() + 32;
    }
    if (entrySize <= maxSize) {
      entries.addFirst(new HeaderField(name, value));
      size += entrySize;
    }
  }

  public int entryCount() { return entries.size(); }

  public HeaderField get(int index) {
    int i = 0;
    for (HeaderField e : entries) {
      if (i++ == index) return e;
    }
    throw new IndexOutOfBoundsException("Index [" + index + "] out of range; size [" + entries.size() + "]");
  }

  public int maxSize() { return maxSize; }

  public void setMaxSize(int newMax) {
    this.maxSize = newMax;
    while (size > maxSize && !entries.isEmpty()) {
      var evicted = entries.removeLast();
      size -= evicted.name().length() + evicted.value().length() + 32;
    }
  }

  public int size() { return size; }

  public record HeaderField(String name, String value) {}
}

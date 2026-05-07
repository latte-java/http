/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * RFC 7541 Appendix A — 61-entry static table. Used by both HPACKEncoder and HPACKDecoder.
 *
 * @author Daniel DeGroff
 */
public final class HPACKStaticTable {
  public static final int SIZE = 61;

  private static final HPACKDynamicTable.HeaderField[] ENTRIES = new HPACKDynamicTable.HeaderField[]{
      null, // 1-based
      new HPACKDynamicTable.HeaderField(":authority", ""),
      new HPACKDynamicTable.HeaderField(":method", "GET"),
      new HPACKDynamicTable.HeaderField(":method", "POST"),
      new HPACKDynamicTable.HeaderField(":path", "/"),
      new HPACKDynamicTable.HeaderField(":path", "/index.html"),
      new HPACKDynamicTable.HeaderField(":scheme", "http"),
      new HPACKDynamicTable.HeaderField(":scheme", "https"),
      new HPACKDynamicTable.HeaderField(":status", "200"),
      new HPACKDynamicTable.HeaderField(":status", "204"),
      new HPACKDynamicTable.HeaderField(":status", "206"),
      new HPACKDynamicTable.HeaderField(":status", "304"),
      new HPACKDynamicTable.HeaderField(":status", "400"),
      new HPACKDynamicTable.HeaderField(":status", "404"),
      new HPACKDynamicTable.HeaderField(":status", "500"),
      new HPACKDynamicTable.HeaderField("accept-charset", ""),
      new HPACKDynamicTable.HeaderField("accept-encoding", "gzip, deflate"),
      new HPACKDynamicTable.HeaderField("accept-language", ""),
      new HPACKDynamicTable.HeaderField("accept-ranges", ""),
      new HPACKDynamicTable.HeaderField("accept", ""),
      new HPACKDynamicTable.HeaderField("access-control-allow-origin", ""),
      new HPACKDynamicTable.HeaderField("age", ""),
      new HPACKDynamicTable.HeaderField("allow", ""),
      new HPACKDynamicTable.HeaderField("authorization", ""),
      new HPACKDynamicTable.HeaderField("cache-control", ""),
      new HPACKDynamicTable.HeaderField("content-disposition", ""),
      new HPACKDynamicTable.HeaderField("content-encoding", ""),
      new HPACKDynamicTable.HeaderField("content-language", ""),
      new HPACKDynamicTable.HeaderField("content-length", ""),
      new HPACKDynamicTable.HeaderField("content-location", ""),
      new HPACKDynamicTable.HeaderField("content-range", ""),
      new HPACKDynamicTable.HeaderField("content-type", ""),
      new HPACKDynamicTable.HeaderField("cookie", ""),
      new HPACKDynamicTable.HeaderField("date", ""),
      new HPACKDynamicTable.HeaderField("etag", ""),
      new HPACKDynamicTable.HeaderField("expect", ""),
      new HPACKDynamicTable.HeaderField("expires", ""),
      new HPACKDynamicTable.HeaderField("from", ""),
      new HPACKDynamicTable.HeaderField("host", ""),
      new HPACKDynamicTable.HeaderField("if-match", ""),
      new HPACKDynamicTable.HeaderField("if-modified-since", ""),
      new HPACKDynamicTable.HeaderField("if-none-match", ""),
      new HPACKDynamicTable.HeaderField("if-range", ""),
      new HPACKDynamicTable.HeaderField("if-unmodified-since", ""),
      new HPACKDynamicTable.HeaderField("last-modified", ""),
      new HPACKDynamicTable.HeaderField("link", ""),
      new HPACKDynamicTable.HeaderField("location", ""),
      new HPACKDynamicTable.HeaderField("max-forwards", ""),
      new HPACKDynamicTable.HeaderField("proxy-authenticate", ""),
      new HPACKDynamicTable.HeaderField("proxy-authorization", ""),
      new HPACKDynamicTable.HeaderField("range", ""),
      new HPACKDynamicTable.HeaderField("referer", ""),
      new HPACKDynamicTable.HeaderField("refresh", ""),
      new HPACKDynamicTable.HeaderField("retry-after", ""),
      new HPACKDynamicTable.HeaderField("server", ""),
      new HPACKDynamicTable.HeaderField("set-cookie", ""),
      new HPACKDynamicTable.HeaderField("strict-transport-security", ""),
      new HPACKDynamicTable.HeaderField("transfer-encoding", ""),
      new HPACKDynamicTable.HeaderField("user-agent", ""),
      new HPACKDynamicTable.HeaderField("vary", ""),
      new HPACKDynamicTable.HeaderField("via", ""),
      new HPACKDynamicTable.HeaderField("www-authenticate", "")
  };

  private HPACKStaticTable() {}

  public static int indexFullMatch(String name, String value) {
    for (int i = 1; i <= SIZE; i++) {
      var e = ENTRIES[i];
      if (e.name().equals(name) && e.value().equals(value)) {
        return i;
      }
    }
    return -1;
  }

  public static int indexNameOnly(String name) {
    for (int i = 1; i <= SIZE; i++) {
      if (ENTRIES[i].name().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  public static HPACKDynamicTable.HeaderField lookup(int index) {
    if (index < 1 || index > SIZE) {
      throw new IndexOutOfBoundsException("Static table index [" + index + "] out of range [1, " + SIZE + "]");
    }
    return ENTRIES[index];
  }
}

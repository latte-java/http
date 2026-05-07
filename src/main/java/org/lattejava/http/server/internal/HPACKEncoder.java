/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * RFC 7541 HPACK encoder. Encodes an ordered list of (name, value) pairs to a HEADERS+CONTINUATION block fragment.
 * v1: Huffman encoding deferred (literal-only) — Plan F can revisit after benchmarking.
 *
 * @author Daniel DeGroff
 */
public class HPACKEncoder {
  private static final Set<String> SENSITIVE = Set.of("authorization", "set-cookie");

  private final HPACKDynamicTable dynamicTable;

  public HPACKEncoder(HPACKDynamicTable dynamicTable) {
    this.dynamicTable = dynamicTable;
  }

  public byte[] encode(List<HPACKDynamicTable.HeaderField> fields) {
    var out = new ByteArrayOutputStream();
    for (var f : fields) {
      String lcName = f.name().toLowerCase(Locale.ROOT);
      // 1. Exact match in static table → indexed
      int staticExact = HPACKStaticTable.indexFullMatch(f.name(), f.value());
      if (staticExact != -1) {
        encodeInt(out, staticExact, 7, 0x80);
        continue;
      }
      int nameIdx = HPACKStaticTable.indexNameOnly(f.name());
      // 2. Sensitive: literal-without-indexing
      if (SENSITIVE.contains(lcName)) {
        encodeInt(out, nameIdx == -1 ? 0 : nameIdx, 4, 0x00);
        if (nameIdx == -1) writeString(out, f.name());
        writeString(out, f.value());
        continue;
      }
      // 3. Otherwise literal-with-indexing
      encodeInt(out, nameIdx == -1 ? 0 : nameIdx, 6, 0x40);
      if (nameIdx == -1) writeString(out, f.name());
      writeString(out, f.value());
      dynamicTable.add(f.name(), f.value());
    }
    return out.toByteArray();
  }

  private static void encodeInt(ByteArrayOutputStream out, int value, int prefixBits, int firstByteMask) {
    int max = (1 << prefixBits) - 1;
    if (value < max) {
      out.write(firstByteMask | value);
      return;
    }
    out.write(firstByteMask | max);
    value -= max;
    while (value >= 128) {
      out.write((value & 0x7F) | 0x80);
      value >>>= 7;
    }
    out.write(value);
  }

  private static void writeString(ByteArrayOutputStream out, String s) {
    // v1: literal (no Huffman) for determinism. Plan F can add Huffman after benchmarking.
    byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
    encodeInt(out, bytes.length, 7, 0x00);
    out.write(bytes, 0, bytes.length);
  }
}

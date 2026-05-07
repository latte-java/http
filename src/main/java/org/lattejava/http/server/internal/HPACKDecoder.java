/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * RFC 7541 HPACK decoder. Decodes a HEADERS+CONTINUATION block fragment to an ordered list of (name, value) pairs.
 * Mutates the supplied dynamic table as side-effect of indexing operations.
 *
 * @author Daniel DeGroff
 */
public class HPACKDecoder {
  private final HPACKDynamicTable dynamicTable;

  public HPACKDecoder(HPACKDynamicTable dynamicTable) {
    this.dynamicTable = dynamicTable;
  }

  public List<HPACKDynamicTable.HeaderField> decode(byte[] block) throws IOException {
    var fields = new ArrayList<HPACKDynamicTable.HeaderField>();
    int i = 0;
    while (i < block.length) {
      int b = block[i] & 0xFF;
      if ((b & 0x80) != 0) {
        // Indexed header field — §6.1; first bit 1
        long r = decodeInt(block, i, 7);
        fields.add(lookup((int) (r >>> 32)));
        i = (int) r;
      } else if ((b & 0x40) != 0) {
        // Literal with incremental indexing — §6.2.1; first two bits 01
        long r = decodeInt(block, i, 6);
        var pair = readNameValue(block, (int) r, (int) (r >>> 32));
        fields.add(pair.field());
        dynamicTable.add(pair.field().name(), pair.field().value());
        i = pair.nextIndex();
      } else if ((b & 0x20) != 0) {
        // Dynamic table size update — §6.3; first three bits 001
        long r = decodeInt(block, i, 5);
        dynamicTable.setMaxSize((int) (r >>> 32));
        i = (int) r;
      } else {
        // Literal without indexing (§6.2.2) — first four bits 0000
        // Literal never-indexed (§6.2.3) — first four bits 0001
        // Both are stored but not added to the dynamic table.
        long r = decodeInt(block, i, 4);
        var pair = readNameValue(block, (int) r, (int) (r >>> 32));
        fields.add(pair.field());
        i = pair.nextIndex();
      }
    }
    return fields;
  }

  // Decodes an N-prefix integer per RFC 7541 §5.1.
  // Returns a packed long: high 32 bits = decoded value, low 32 bits = nextIndex.
  static long decodeInt(byte[] block, int i, int prefixBits) {
    int max = (1 << prefixBits) - 1;
    int v = block[i] & max;
    i++;
    if (v < max) {
      return ((long) v << 32) | (i & 0xFFFFFFFFL);
    }
    int m = 0;
    int b;
    do {
      b = block[i++] & 0xFF;
      v += (b & 0x7F) << m;
      m += 7;
    } while ((b & 0x80) != 0);
    return ((long) v << 32) | (i & 0xFFFFFFFFL);
  }

  private HPACKDynamicTable.HeaderField lookup(int index) {
    if (index == 0) {
      throw new IllegalStateException("HPACK index [0] is invalid per RFC 7541 §2.1");
    }
    if (index <= HPACKStaticTable.SIZE) {
      return HPACKStaticTable.lookup(index);
    }
    return dynamicTable.get(index - HPACKStaticTable.SIZE - 1);
  }

  private NameValuePair readNameValue(byte[] block, int start, int nameIndex) throws IOException {
    String name;
    int i = start;
    if (nameIndex == 0) {
      var s = readString(block, i);
      name = s.value();
      i = s.nextIndex();
    } else {
      name = lookup(nameIndex).name();
    }
    var v = readString(block, i);
    return new NameValuePair(new HPACKDynamicTable.HeaderField(name, v.value()), v.nextIndex());
  }

  private StringResult readString(byte[] block, int i) {
    boolean huffman = (block[i] & 0x80) != 0;
    long r = decodeInt(block, i, 7);
    int len = (int) (r >>> 32);
    int start = (int) r;
    byte[] raw = new byte[len];
    System.arraycopy(block, start, raw, 0, len);
    String s = huffman
        ? new String(HPACKHuffman.decode(raw), StandardCharsets.UTF_8)
        : new String(raw, StandardCharsets.UTF_8);
    return new StringResult(s, start + len);
  }

  private record NameValuePair(HPACKDynamicTable.HeaderField field, int nextIndex) {}

  private record StringResult(String value, int nextIndex) {}
}

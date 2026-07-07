/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;

/**
 * RFC 7541 HPACK decoder. Decodes a HEADERS+CONTINUATION block fragment to an ordered list of (name, value) pairs.
 * Mutates the supplied dynamic table as side-effect of indexing operations.
 *
 * @author Daniel DeGroff
 */
public class HPACKDecoder {
  private final HPACKDynamicTable dynamicTable;
  // RFC 7541 §6.3 — the largest dynamic table size a peer size-update may request, equal to the
  // SETTINGS_HEADER_TABLE_SIZE we advertised (captured here as the table's initial maximum). A size update above
  // this is a COMPRESSION_ERROR.
  private final int maxDynamicTableSize;

  public HPACKDecoder(HPACKDynamicTable dynamicTable) {
    this.dynamicTable = dynamicTable;
    this.maxDynamicTableSize = dynamicTable.maxSize();
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
        // RFC 7541 §4.2 — size updates must occur at the beginning of a header block, before any header field.
        if (!fields.isEmpty()) {
          throw new IOException("HPACK dynamic table size update after [" + fields.size() + "] header fields — only legal at the beginning of a header block");
        }
        long r = decodeInt(block, i, 5);
        int newMax = (int) (r >>> 32);
        if (newMax > maxDynamicTableSize) {
          throw new IOException("HPACK dynamic table size update [" + newMax + "] exceeds advertised SETTINGS_HEADER_TABLE_SIZE [" + maxDynamicTableSize + "]");
        }
        dynamicTable.setMaxSize(newMax);
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
  //
  // RFC 7541 §3.3 requires malformed inputs to surface as COMPRESSION_ERROR rather than a runtime crash.
  // Three attacker-controlled failure modes are bounded here:
  //   1) Truncated continuation: the input ends with the continuation bit set on the last byte.
  //   2) Overlong continuation: more continuation bytes than a 32-bit value can use. The shift is capped at 28
  //      (at most five continuation bytes).
  //   3) Value overflow: the running total is accumulated in a long and rejected once it passes Integer.MAX_VALUE,
  //      so a value near the 28-bit shift boundary can never wrap into a negative int that would escape as a bogus
  //      table index or string length.
  static long decodeInt(byte[] block, int i, int prefixBits) throws IOException {
    int max = (1 << prefixBits) - 1;
    long v = block[i] & max;
    i++;
    if (v < max) {
      return (v << 32) | (i & 0xFFFFFFFFL);
    }
    int m = 0;
    int b;
    do {
      if (i >= block.length) {
        throw new IOException("HPACK integer truncated: continuation bit set at end of header block");
      }
      if (m > 28) {
        throw new IOException("HPACK integer overflow: more than 5 continuation bytes");
      }
      b = block[i++] & 0xFF;
      v += (long) (b & 0x7F) << m;
      if (v > Integer.MAX_VALUE) {
        throw new IOException("HPACK integer overflow: value exceeds [" + Integer.MAX_VALUE + "]");
      }
      m += 7;
    } while ((b & 0x80) != 0);
    return (v << 32) | (i & 0xFFFFFFFFL);
  }

  private HPACKDynamicTable.HeaderField lookup(int index) throws IOException {
    if (index == 0) {
      throw new IOException("HPACK index [0] is invalid per RFC 7541 §2.1");
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

  private StringResult readString(byte[] block, int i) throws IOException {
    boolean huffman = (block[i] & 0x80) != 0;
    long r = decodeInt(block, i, 7);
    int len = (int) (r >>> 32);
    int start = (int) r;
    if (len < 0 || start > block.length - len) {
      throw new IOException("HPACK string length [" + len + "] exceeds remaining header block");
    }
    byte[] raw = new byte[len];
    System.arraycopy(block, start, raw, 0, len);
    String s = huffman
        ? new String(HPACKHuffman.decode(raw), StandardCharsets.UTF_8)
        : new String(raw, StandardCharsets.UTF_8);
    return new StringResult(s, start + len);
  }

  private record NameValuePair(HPACKDynamicTable.HeaderField field, int nextIndex) {
  }

  private record StringResult(String value, int nextIndex) {
  }
}

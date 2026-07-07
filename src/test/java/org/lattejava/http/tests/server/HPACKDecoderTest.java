/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.h2.HPACKDecoder;
import org.lattejava.http.server.internal.h2.HPACKDynamicTable;

import static org.testng.Assert.*;

public class HPACKDecoderTest {
  // RFC 7541 Appendix C.2.1: literal header field with indexing — "custom-key: custom-header"
  @Test
  public void literal_with_indexing() throws Exception {
    byte[] block = hex("400a637573746f6d2d6b65790d637573746f6d2d686561646572");
    var table = new HPACKDynamicTable(4096);
    var decoder = new HPACKDecoder(table);
    var fields = decoder.decode(block);
    assertEquals(fields.size(), 1);
    assertEquals(fields.get(0).name(), "custom-key");
    assertEquals(fields.get(0).value(), "custom-header");
    // Side-effect: dynamic table now has the entry
    assertEquals(table.entryCount(), 1);
  }

  // RFC 7541 Appendix C.2.4: indexed header field — :method GET (static index 2)
  @Test
  public void indexed_static() throws Exception {
    byte[] block = {(byte) 0x82};
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    var fields = decoder.decode(block);
    assertEquals(fields.size(), 1);
    assertEquals(fields.get(0).name(), ":method");
    assertEquals(fields.get(0).value(), "GET");
  }

  // RFC 7541 Appendix C.3.1: full GET request with multiple headers
  @Test
  public void appendix_c3_1_request_no_huffman() throws Exception {
    byte[] block = hex("828684410f7777772e6578616d706c652e636f6d");
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    var fields = decoder.decode(block);
    assertEquals(fields.size(), 4);
    assertEquals(fields.get(0).name(), ":method");
    assertEquals(fields.get(0).value(), "GET");
    assertEquals(fields.get(1).name(), ":scheme");
    assertEquals(fields.get(1).value(), "http");
    assertEquals(fields.get(2).name(), ":path");
    assertEquals(fields.get(2).value(), "/");
    assertEquals(fields.get(3).name(), ":authority");
    assertEquals(fields.get(3).value(), "www.example.com");
  }

  // RFC 7541 Appendix C.4.1: same request, Huffman-encoded
  @Test
  public void appendix_c4_1_request_with_huffman() throws Exception {
    byte[] block = hex("828684418cf1e3c2e5f23a6ba0ab90f4ff");
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    var fields = decoder.decode(block);
    assertEquals(fields.size(), 4);
    assertEquals(fields.get(3).name(), ":authority");
    assertEquals(fields.get(3).value(), "www.example.com");
  }

  // Dynamic table size update — §6.3
  @Test
  public void dynamic_table_size_update() throws Exception {
    // 001xxxxx with 5-bit prefix value = 0 → table size 0
    byte[] block = {(byte) 0x20};
    var table = new HPACKDynamicTable(4096);
    var decoder = new HPACKDecoder(table);
    decoder.decode(block);
    assertEquals(table.maxSize(), 0);
  }

  // RFC 7541 §2.1 — the zero index is reserved and MUST NOT be used. An indexed-header representation with index 0
  // must surface as COMPRESSION_ERROR (IOException), not an unchecked IllegalStateException that escapes to the
  // connection-level reader loop.
  @Test(expectedExceptions = IOException.class,
      expectedExceptionsMessageRegExp = ".*index.*0.*")
  public void decode_index_zero_throws_ioexception_per_rfc_7541_section_2_1() throws Exception {
    // RFC 7541 §6.1 indexed header field representation: high bit + 7-bit index. 0x80 = indexed, index 0 (invalid).
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    decoder.decode(new byte[]{(byte) 0x80});
  }

  // RFC 7541 §3.3 — malformed input must surface as COMPRESSION_ERROR (IOException), not a runtime crash.
  // Truncated continuation: indexed-header field with prefix saturated (0xFF) plus a single continuation byte
  // whose high bit is set, with no following byte to read.
  @Test
  public void decode_truncated_integer_continuation_throws_ioexception() {
    byte[] block = {(byte) 0xFF, (byte) 0x80};
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    assertThrows(IOException.class, () -> decoder.decode(block));
  }

  // Overlong integer continuation: five continuation bytes all with high bit set would overflow the int
  // accumulator. RFC 7541 §5.1 doesn't specify a max, but implementations must bound it.
  @Test
  public void decode_overlong_integer_continuation_throws_ioexception() {
    byte[] block = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x7F};
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    assertThrows(IOException.class, () -> decoder.decode(block));
  }

  // RFC 7541 §5.1 — an integer whose decoded value exceeds Integer.MAX_VALUE must surface as a COMPRESSION_ERROR
  // (IOException), not silently overflow the accumulator into a negative value that escapes as a bogus index or
  // string length. This sequence fits within the 5-continuation-byte cap, so the byte-count guard does not catch
  // it; only a value-bound check does. Prefix 0xFF saturates the 7-bit prefix; the byte at shift 28 pushes the
  // value past 2^31-1.
  @Test
  public void decode_integer_exceeding_int_max_throws_ioexception() {
    byte[] block = {(byte) 0xFF, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x7F};
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    assertThrows(IOException.class, () -> decoder.decode(block));
  }

  // RFC 7541 §6.3 — a dynamic table size update MUST NOT exceed the limit set by the protocol
  // (SETTINGS_HEADER_TABLE_SIZE, which is the decoder table's initial maximum). An over-limit update is a
  // COMPRESSION_ERROR. 0x3F,0xA1,0x3E encodes a 5-bit-prefix size update of 8000, above the advertised 4096.
  @Test
  public void dynamic_table_size_update_above_advertised_limit_throws() {
    byte[] block = {(byte) 0x3F, (byte) 0xA1, (byte) 0x3E};
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    assertThrows(IOException.class, () -> decoder.decode(block));
  }

  // A dynamic table size update at or below the advertised limit is accepted. 0x3F,0xB1,0x0F encodes 2000 (≤ 4096).
  @Test
  public void dynamic_table_size_update_within_advertised_limit_succeeds() throws Exception {
    byte[] block = {(byte) 0x3F, (byte) 0xB1, (byte) 0x0F};
    var table = new HPACKDynamicTable(4096);
    var decoder = new HPACKDecoder(table);
    decoder.decode(block);
    assertEquals(table.maxSize(), 2000);
  }

  // Literal string with length running past the end of the header block must throw, not crash with
  // ArrayIndexOutOfBoundsException.
  @Test
  public void decode_string_length_past_end_throws_ioexception() {
    // 0x40 = literal-with-incremental-indexing, name-index = 0 (literal name follows).
    // Next byte 0x0A claims a 10-byte name string, but only 2 bytes follow.
    byte[] block = {(byte) 0x40, (byte) 0x0A, (byte) 'a', (byte) 'b'};
    var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
    assertThrows(IOException.class, () -> decoder.decode(block));
  }

  private static byte[] hex(String h) {
    h = h.replace(" ", "");
    byte[] out = new byte[h.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}

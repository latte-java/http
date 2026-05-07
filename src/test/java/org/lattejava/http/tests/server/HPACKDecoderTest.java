/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HPACKDecoder;
import org.lattejava.http.server.internal.HPACKDynamicTable;

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
    assertEquals(fields.get(0).name(), ":method");    assertEquals(fields.get(0).value(), "GET");
    assertEquals(fields.get(1).name(), ":scheme");    assertEquals(fields.get(1).value(), "http");
    assertEquals(fields.get(2).name(), ":path");      assertEquals(fields.get(2).value(), "/");
    assertEquals(fields.get(3).name(), ":authority"); assertEquals(fields.get(3).value(), "www.example.com");
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

  private static byte[] hex(String h) {
    h = h.replace(" ", "");
    byte[] out = new byte[h.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
    }
    return out;
  }
}

/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HPACKHuffman;

import static org.testng.Assert.*;

public class HPACKHuffmanTest {
  // RFC 7541 Appendix C.4.1: "www.example.com" Huffman-encoded
  @Test
  public void encode_decode_www_example_com() {
    String s = "www.example.com";
    byte[] encoded = HPACKHuffman.encode(s.getBytes());
    // Expected hex per RFC: f1e3 c2e5 f23a 6ba0 ab90 f4ff
    byte[] expected = hex("f1e3c2e5f23a6ba0ab90f4ff");
    assertEquals(encoded, expected);
    byte[] decoded = HPACKHuffman.decode(encoded);
    assertEquals(new String(decoded), s);
  }

  // RFC 7541 Appendix C.4.3: "custom-key"
  @Test
  public void encode_decode_custom_key() {
    String s = "custom-key";
    byte[] encoded = HPACKHuffman.encode(s.getBytes());
    byte[] expected = hex("25a849e95ba97d7f"); // per RFC
    assertEquals(encoded, expected);
    assertEquals(new String(HPACKHuffman.decode(encoded)), s);
  }

  // RFC 7541 Appendix C.4.3: "custom-value"
  @Test
  public void encode_decode_custom_value() {
    String s = "custom-value";
    byte[] encoded = HPACKHuffman.encode(s.getBytes());
    byte[] expected = hex("25a849e95bb8e8b4bf"); // per RFC
    assertEquals(encoded, expected);
    assertEquals(new String(HPACKHuffman.decode(encoded)), s);
  }

  @Test
  public void empty_round_trip() {
    byte[] encoded = HPACKHuffman.encode(new byte[0]);
    assertEquals(encoded.length, 0);
    assertEquals(HPACKHuffman.decode(encoded).length, 0);
  }

  @Test
  public void round_trip_all_ascii_printable() {
    StringBuilder sb = new StringBuilder();
    for (int c = 32; c < 127; c++) {
      sb.append((char) c);
    }
    byte[] input = sb.toString().getBytes();
    byte[] decoded = HPACKHuffman.decode(HPACKHuffman.encode(input));
    assertEquals(new String(decoded), sb.toString(), "Round-trip should preserve all printable ASCII");
  }

  private static byte[] hex(String s) {
    int len = s.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
    }
    return data;
  }
}

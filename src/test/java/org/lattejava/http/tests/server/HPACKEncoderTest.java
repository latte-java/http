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
import org.lattejava.http.server.internal.HPACKEncoder;

import static org.testng.Assert.*;

public class HPACKEncoderTest {
  @Test
  public void round_trip_via_decoder() throws Exception {
    var encTable = new HPACKDynamicTable(4096);
    var decTable = new HPACKDynamicTable(4096);
    var encoder = new HPACKEncoder(encTable);
    var decoder = new HPACKDecoder(decTable);

    List<HPACKDynamicTable.HeaderField> input = List.of(
        new HPACKDynamicTable.HeaderField(":method", "GET"),
        new HPACKDynamicTable.HeaderField(":scheme", "https"),
        new HPACKDynamicTable.HeaderField(":path", "/"),
        new HPACKDynamicTable.HeaderField(":authority", "example.com"),
        new HPACKDynamicTable.HeaderField("custom", "value")
    );

    byte[] block = encoder.encode(input);
    var output = decoder.decode(block);
    assertEquals(output, input);
  }

  @Test
  public void uses_static_table_for_method_get() throws Exception {
    var encoder = new HPACKEncoder(new HPACKDynamicTable(4096));
    byte[] block = encoder.encode(List.of(new HPACKDynamicTable.HeaderField(":method", "GET")));
    // RFC 7541 Appendix A index 2 → 0x82 (1-bit indexed prefix + 7-bit value=2)
    assertEquals(block, new byte[]{(byte) 0x82});
  }

  @Test
  public void sensitive_header_uses_literal_without_indexing() throws Exception {
    // For now, sensitive = "set-cookie", "authorization". Both should NOT be added to dynamic table.
    var encTable = new HPACKDynamicTable(4096);
    var encoder = new HPACKEncoder(encTable);
    encoder.encode(List.of(new HPACKDynamicTable.HeaderField("authorization", "Bearer xyz")));
    assertEquals(encTable.entryCount(), 0, "Sensitive header must not be added to dynamic table");
  }

  @Test
  public void normal_header_added_to_dynamic_table() throws Exception {
    var encTable = new HPACKDynamicTable(4096);
    var encoder = new HPACKEncoder(encTable);
    encoder.encode(List.of(new HPACKDynamicTable.HeaderField("custom-header", "value")));
    assertEquals(encTable.entryCount(), 1);
  }
}

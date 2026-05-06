/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.io;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class ChunkedOutputStreamTrailersTest {
  @Test
  public void emits_trailer_fields_after_terminator() throws Exception {
    var sink = new ByteArrayOutputStream();
    var chunked = new ChunkedOutputStream(sink, new byte[16], new FastByteArrayOutputStream(256, 64));
    chunked.write("hello".getBytes());
    chunked.setTrailers(Map.of("x-checksum", List.of("abc")));
    chunked.close();

    String wire = sink.toString();
    assertTrue(wire.contains("0\r\nx-checksum: abc\r\n\r\n"), "Expected trailer-fields after 0-chunk; got: " + wire);
  }

  @Test
  public void multiple_trailer_values_emit_repeated_lines() throws Exception {
    var sink = new ByteArrayOutputStream();
    var chunked = new ChunkedOutputStream(sink, new byte[16], new FastByteArrayOutputStream(256, 64));
    chunked.write("x".getBytes());
    chunked.setTrailers(Map.of("x-stat", List.of("1", "2")));
    chunked.close();

    String wire = sink.toString();
    assertTrue(wire.contains("0\r\nx-stat: 1\r\nx-stat: 2\r\n\r\n"), "Expected two x-stat lines; got: " + wire);
  }

  @Test
  public void no_trailers_emits_bare_terminator() throws Exception {
    var sink = new ByteArrayOutputStream();
    var chunked = new ChunkedOutputStream(sink, new byte[16], new FastByteArrayOutputStream(256, 64));
    chunked.write("hello".getBytes());
    chunked.close();

    String wire = sink.toString();
    assertTrue(wire.endsWith("0\r\n\r\n"), "Expected bare 0-chunk terminator; got: " + wire);
  }
}

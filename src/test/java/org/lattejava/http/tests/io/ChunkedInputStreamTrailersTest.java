/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.io;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.io.PushbackInputStream;

import static org.testng.Assert.*;

public class ChunkedInputStreamTrailersTest {
  @Test
  public void trailer_after_zero_chunk_captured() throws Exception {
    String wire = "5\r\nhello\r\n0\r\nX-Checksum: abc123\r\nX-Other: 42\r\n\r\n";
    var pushback = new PushbackInputStream(new ByteArrayInputStream(wire.getBytes()), null);
    var chunked = new ChunkedInputStream(pushback, 1024, 1_000_000);

    byte[] body = chunked.readAllBytes();
    assertEquals(new String(body), "hello");

    Map<String, List<String>> trailers = chunked.getTrailers();
    assertEquals(trailers.get("x-checksum"), List.of("abc123"));
    assertEquals(trailers.get("x-other"), List.of("42"));
  }

  @Test
  public void no_trailers_returns_empty_map() throws Exception {
    String wire = "5\r\nhello\r\n0\r\n\r\n";
    var pushback = new PushbackInputStream(new ByteArrayInputStream(wire.getBytes()), null);
    var chunked = new ChunkedInputStream(pushback, 1024, 1_000_000);

    chunked.readAllBytes();
    assertTrue(chunked.getTrailers().isEmpty());
  }
}

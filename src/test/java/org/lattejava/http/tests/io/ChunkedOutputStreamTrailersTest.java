/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.io;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

/**
 * Unit tests for {@link ChunkedOutputStream} pure data-chunk framing. Trailer emission and the chunk terminator are
 * now the responsibility of {@code HTTP1OutputProtocol.commitTrailers()} — tested at the server-level trailers
 * integration tests — so this class only verifies data-chunk framing and the no-op close behavior.
 */
public class ChunkedOutputStreamTrailersTest {
  @Test
  public void data_chunk_framing_writes_hex_length_crlf_data_crlf() throws Exception {
    var sink = new ByteArrayOutputStream();
    var chunked = new ChunkedOutputStream(sink, new byte[16], new FastByteArrayOutputStream(256, 64));
    chunked.write("hello".getBytes());
    chunked.close();

    // close() flushes the final buffered data chunk but does NOT write any terminator.
    String wire = sink.toString(StandardCharsets.US_ASCII);
    // "hello" is 5 bytes → hex "5"
    assertTrue(wire.startsWith("5\r\nhello\r\n"), "Expected data-chunk framing; got: " + wire);
    // No terminator written by ChunkedOutputStream itself.
    assertFalse(wire.contains("0\r\n"), "ChunkedOutputStream must not write the 0-chunk terminator; got: " + wire);
  }

  @Test
  public void close_is_idempotent() throws Exception {
    var sink = new ByteArrayOutputStream();
    var chunked = new ChunkedOutputStream(sink, new byte[16], new FastByteArrayOutputStream(256, 64));
    chunked.write("x".getBytes());
    chunked.close();
    // Second close must be a no-op (no exception, no extra bytes).
    int sizeAfterFirstClose = sink.size();
    chunked.close();
    assertEquals(sink.size(), sizeAfterFirstClose, "Second close must not write additional bytes");
  }
}

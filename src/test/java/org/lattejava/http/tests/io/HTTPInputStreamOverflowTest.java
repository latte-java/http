/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.io;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.io.PushbackInputStream;

import static org.testng.Assert.assertEquals;

public class HTTPInputStreamOverflowTest {
  @Test
  public void read_does_not_overflow_at_integer_max_value() throws IOException {
    byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
    HTTPServerConfiguration configuration = new HTTPServerConfiguration();
    HTTPRequest request = new HTTPRequest();
    request.setHeader("Content-Length", String.valueOf(payload.length));

    PushbackInputStream pushback = new PushbackInputStream(new ByteArrayInputStream(payload), null);
    HTTPInputStream stream = new HTTPInputStream(configuration, request, pushback, Integer.MAX_VALUE);

    // Before the long-arithmetic fix, this read computed maxReadLen = Integer.MIN_VALUE and the underlying
    // ByteArrayInputStream.read(b, 0, Integer.MIN_VALUE) threw IndexOutOfBoundsException — that is what this test regresses against.
    byte[] buf = new byte[8];
    int read = stream.read(buf, 0, buf.length);

    assertEquals(read, payload.length);
    assertEquals(new String(buf, 0, read, StandardCharsets.UTF_8), "hello");
  }
}

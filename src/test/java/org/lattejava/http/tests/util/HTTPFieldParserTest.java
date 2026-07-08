/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.util;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.ParseException;
import org.lattejava.http.util.FieldConsumer;
import org.lattejava.http.util.HTTPFieldParser;

import static org.testng.Assert.*;

public class HTTPFieldParserTest {
  private List<String> collect(byte[] bytes, int chunk) {
    var out = new ArrayList<String>();
    FieldConsumer consumer = (name, value) -> out.add(name + "=" + value);
    var parser = new HTTPFieldParser();
    int offset = 0;
    while (!parser.isComplete() && offset < bytes.length) {
      int len = Math.min(chunk, bytes.length - offset);
      offset += parser.feed(bytes, offset, len, consumer);
      if (len == 0) {
        break;
      }
    }
    return out;
  }

  @Test
  public void parsesFieldsInOneFeed() {
    byte[] bytes = "Host: example.org\r\nX-A: 1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of("Host=example.org", "X-A=1"));
  }

  @Test
  public void isCompleteAfterBlankLine() {
    byte[] bytes = "Host: example.org\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    var parser = new HTTPFieldParser();
    int consumed = parser.feed(bytes, 0, bytes.length, (_, _) -> {
    });
    assertTrue(parser.isComplete());
    assertEquals(consumed, bytes.length);
    assertEquals(parser.bytesConsumed(), bytes.length);
  }

  @Test
  public void resumesAcrossTinyFeeds() {
    byte[] bytes = "Host: example.org\r\nX-A: 1\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, 1), List.of("Host=example.org", "X-A=1"));
  }

  @Test
  public void dropsEmptyValues() {
    // Matches the request-preamble parser: a value-less field is not emitted.
    byte[] bytes = "X-Empty:\r\nX-Full: y\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of("X-Full=y"));
  }

  @Test
  public void stripsWhitespaceAroundValue() {
    // RFC 9112 §5: field-line = field-name ":" OWS field-value OWS — the surrounding OWS is not part of the value.
    byte[] bytes = "X-A:    spaced   \r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of("X-A=spaced"));
  }

  @Test
  public void stripsLeadingAndTrailingHorizontalTabs() {
    // OWS = *( SP / HTAB ), so tabs around the value are stripped just like spaces.
    byte[] bytes = "X-A:\tvalue\t\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of("X-A=value"));
  }

  @Test
  public void preservesInternalWhitespace() {
    byte[] bytes = "X-A: a  b\tc\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of("X-A=a  b\tc"));
  }

  @Test
  public void stripsTrailingWhitespaceAcrossTinyFeeds() {
    // The trailing OWS must be stripped even when every byte arrives in its own feed call.
    byte[] bytes = "X-A: a b \t\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, 1), List.of("X-A=a b"));
  }

  @Test
  public void dropsWhitespaceOnlyValues() {
    // A value that is nothing but OWS is an empty value, and empty values are not emitted.
    byte[] bytes = "X-WS:  \t \r\nX-Full: y\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of("X-Full=y"));
  }

  @Test
  public void emptyBlockIsImmediatelyComplete() {
    byte[] bytes = "\r\n".getBytes(StandardCharsets.US_ASCII);
    assertEquals(collect(bytes, bytes.length), List.of());
  }

  @Test(expectedExceptions = ParseException.class)
  public void rejectsNonTokenInName() {
    byte[] bytes = "Bad Name: x\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    new HTTPFieldParser().feed(bytes, 0, bytes.length, (_, _) -> {
    });
  }

  @Test(expectedExceptions = ParseException.class)
  public void rejectsControlByteInValue() {
    byte[] bytes = "X-A: ab\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    new HTTPFieldParser().feed(bytes, 0, bytes.length, (_, _) -> {
    });
  }
}

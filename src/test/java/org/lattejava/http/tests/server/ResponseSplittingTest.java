/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.Cookie;

import static org.testng.Assert.*;

/**
 * Verifies that {@link HTTPTools#validateResponse} rejects CR, LF, and NUL in every attacker-influenceable response
 * surface, which would otherwise allow HTTP response splitting (see docs/design/2026-04-20-audit.md Vuln 4). The
 * choke point is shared by both protocols: {@link HTTPTools#writeResponsePreamble} calls it before writing the
 * HTTP/1.1 preamble, and {@code HTTP2OutputProtocol.commitHeaders} calls it before HPACK-encoding the response
 * HEADERS frame (RFC 9113 §8.2.1 forbids these octets in field values). Validation is concentrated at the
 * emission choke point rather than the setters so that direct mutation of the internal header map (via
 * {@code getHeadersMap()}) or a Cookie's public fields cannot bypass it.
 *
 * @author Brian Pontarelli
 */
public class ResponseSplittingTest extends BaseHTTP2RawTest {
  /**
   * Minimal HPACK block for a valid GET / request: {@code 0x82} (:method: GET), {@code 0x84} (:path: /), {@code 0x86}
   * (:scheme: http), then a literal {@code :authority: localhost} (RFC 7541 §6.1/§6.2).
   */
  private static final byte[] MINIMAL_HPACK_GET = {
      (byte) 0x82,                          // :method: GET
      (byte) 0x84,                          // :path: /
      (byte) 0x86,                          // :scheme: http
      // :authority: localhost (literal with indexing, name from static table index 1)
      (byte) 0x41, 0x09,
      'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
  };

  @DataProvider(name = "badHeaderNames")
  public static Object[][] badHeaderNames() {
    return new Object[][]{
        {"X-Evil\r\nInjected"},
        {"X-Evil: value\r\n"},
        {"has space"},
        {"has:colon"},
        {"has\ttab"},
        {"non-ascii-\u00e9"},
    };
  }

  @DataProvider(name = "splitValues")
  public static Object[][] splitValues() {
    return new Object[][]{
        {"foo\r\nSet-Cookie: evil=1"},
        {"foo\rbar"},
        {"foo\nbar"},
        {"foo\0bar"},
        {"\r"},
        {"\n"},
        {"\0"},
    };
  }

  @Test
  public void h2RejectsBadCookieValue() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.addCookie(new Cookie("session", "abc\r\nset-cookie: admin=true"));
      res.setStatus(200);
    };

    assertH2StreamResetBeforeHeaders(handler);
  }

  @Test
  public void h2RejectsBadHeaderValue() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setHeader("X-Bad", "value\r\nInjected: yes");
      res.setStatus(200);
    };

    assertH2StreamResetBeforeHeaders(handler);
  }

  @Test
  public void preambleAcceptsBenignResponse() throws Exception {
    var response = new HTTPResponse();
    response.setStatus(200);
    response.setStatusMessage("OK");
    response.setHeader("Content-Type", "text/plain");
    response.addHeader("X-Custom", "some value");
    response.addCookie(new Cookie("session", "abc123"));

    var out = new ByteArrayOutputStream();
    HTTPTools.writeResponsePreamble(response, out);

    String preamble = out.toString();
    assertTrue(preamble.startsWith("HTTP/1.1 200 OK\r\n"), "Unexpected preamble start: " + preamble);
    assertTrue(preamble.contains("content-type: text/plain\r\n"));
    assertTrue(preamble.contains("x-custom: some value\r\n"));
    assertTrue(preamble.contains("Set-Cookie: session=abc123\r\n"));
    assertTrue(preamble.endsWith("\r\n\r\n"));
  }

  @Test
  public void preambleIncludesContextInErrorMessage() throws Exception {
    var response = new HTTPResponse();
    response.setHeader("Location", "/home\r\nSet-Cookie: evil=1");
    try {
      HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
      fail("Expected IllegalArgumentException.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("response header [location]"), "Missing header context in message: " + expected.getMessage());
      assertTrue(expected.getMessage().contains("[0x0D]"), "Missing offending char in message: " + expected.getMessage());
    }
  }

  @Test(dataProvider = "splitValues", expectedExceptions = IllegalArgumentException.class)
  public void preambleRejectsAddedHeader(String value) throws Exception {
    var response = new HTTPResponse();
    response.addHeader("X-Custom", value);
    HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
  }

  @Test(dataProvider = "badHeaderNames", expectedExceptions = IllegalArgumentException.class)
  public void preambleRejectsBadHeaderName(String name) throws Exception {
    var response = new HTTPResponse();
    response.setHeader(name, "safe");
    HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
  }

  @Test
  public void preambleRejectsBadStatusMessage() throws Exception {
    var response = new HTTPResponse();
    response.setStatusMessage("Internal\r\n\r\n<html>");
    try {
      HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
      fail("Expected IllegalArgumentException for CRLF in status message.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("status message"), "Missing 'status message' in: " + expected.getMessage());
    }
  }

  @Test
  public void preambleRejectsCookieWithBadDomain() throws Exception {
    var response = new HTTPResponse();
    var cookie = new Cookie("session", "abc");
    cookie.domain = "evil.com\r\nSet-Cookie: admin=true";
    response.addCookie(cookie);
    try {
      HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
      fail("Expected IllegalArgumentException for CRLF in cookie domain.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("domain of cookie [session]"));
    }
  }

  @Test
  public void preambleRejectsCookieWithBadName() throws Exception {
    var response = new HTTPResponse();
    response.addCookie(new Cookie("bad name", "abc"));
    try {
      HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
      fail("Expected IllegalArgumentException for space in cookie name.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("response header name"));
    }
  }

  @Test
  public void preambleRejectsCookieWithBadPath() throws Exception {
    var response = new HTTPResponse();
    var cookie = new Cookie("session", "abc");
    cookie.path = "/;Secure";
    response.addCookie(cookie);
    try {
      HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
      fail("Expected IllegalArgumentException for semicolon in cookie path.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("path of cookie [session]"));
    }
  }

  @Test
  public void preambleRejectsCookieWithBadValue() throws Exception {
    var response = new HTTPResponse();
    response.addCookie(new Cookie("session", "abc\r\nSet-Cookie: admin=true"));
    try {
      HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
      fail("Expected IllegalArgumentException for CRLF in cookie value.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("value of cookie [session]"));
    }
  }

  @Test
  public void preambleRejectsDirectMapMutation() throws Exception {
    var response = new HTTPResponse();
    response.setStatus(200);
    response.getHeadersMap().put("x-custom\r\n", new java.util.ArrayList<>(java.util.List.of("ok")));
    try {
      HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
      fail("Expected IllegalArgumentException for direct-map mutation with bad header name.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("response header name"));
    }
  }

  @Test
  public void preambleRejectsEmptyHeaderName() throws Exception {
    var response = new HTTPResponse();
    response.getHeadersMap().put("", new java.util.ArrayList<>(java.util.List.of("safe")));
    try {
      HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
      fail("Expected IllegalArgumentException for empty header name.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("must not be empty"));
    }
  }

  @Test(dataProvider = "splitValues", expectedExceptions = IllegalArgumentException.class)
  public void preambleRejectsRedirect(String uri) throws Exception {
    var response = new HTTPResponse();
    response.sendRedirect("/home" + uri, 302);
    HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
  }

  @Test(dataProvider = "splitValues", expectedExceptions = IllegalArgumentException.class)
  public void preambleRejectsSetHeader(String value) throws Exception {
    var response = new HTTPResponse();
    response.setHeader("Location", value);
    HTTPTools.writeResponsePreamble(response, new ByteArrayOutputStream());
  }

  @Test
  public void preambleWritesNoBytesOnFailure() throws Exception {
    var response = new HTTPResponse();
    response.setStatus(200);
    response.setHeader("X-Bad", "value\r\nInjected: yes");

    var out = new ByteArrayOutputStream();
    try {
      HTTPTools.writeResponsePreamble(response, out);
      fail("Expected IllegalArgumentException.");
    } catch (IllegalArgumentException expected) {
      // Validation runs up-front, so no bytes should have been written.
      assertEquals(out.size(), 0, "Preamble should not emit any bytes when validation fails. Got: " + out);
    }
  }

  @Test
  public void settersAcceptBadValuesSilently() {
    // The setters intentionally do not validate — validation happens at writeResponsePreamble. This test documents that contract so a
    // future change that re-adds setter-level validation would fail it and prompt deliberate re-alignment with the single-choke-point
    // design.
    var response = new HTTPResponse();
    response.setHeader("X-Bad", "value\r\nInjected: yes");
    response.setStatusMessage("Status\r\nwith CRLF");
    response.addCookie(new Cookie("ok", "value\r\nInjected"));
    response.sendRedirect("/home\r\nLocation: evil", 302);

    assertEquals(response.getHeader("X-Bad"), "value\r\nInjected: yes");
    assertEquals(response.getStatusMessage(), "Status\r\nwith CRLF");
  }

  @Test
  public void validateResponseAcceptsBenignResponse() {
    var response = new HTTPResponse();
    response.setStatus(200);
    response.setStatusMessage("OK");
    response.setHeader("Content-Type", "text/plain");
    response.addCookie(new Cookie("session", "abc123"));

    // Must not throw.
    HTTPTools.validateResponse(response);
  }

  @Test
  public void validateResponseRejectsBadCookieValue() {
    var response = new HTTPResponse();
    response.addCookie(new Cookie("session", "abc\r\nset-cookie: admin=true"));
    try {
      HTTPTools.validateResponse(response);
      fail("Expected IllegalArgumentException for CRLF in cookie value.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("value of cookie [session]"));
    }
  }

  @Test
  public void validatorBenignInputsDoNotThrow() {
    HTTPTools.validateResponseFieldValue("Normal value with spaces and tabs\tand visible chars!~", "response header value");
    HTTPTools.validateResponseFieldValue(null, "response header value");
    HTTPTools.validateResponseFieldValue("", "response header value");

    HTTPTools.validateResponseHeaderName("X-Custom-Header");
    HTTPTools.validateResponseHeaderName("content-type");
    HTTPTools.validateResponseHeaderName(null);

    HTTPTools.validateResponseCookieAttribute("some-value_with.dashes", "cookie value");
    HTTPTools.validateResponseCookieAttribute(null, "cookie value");
    HTTPTools.validateResponseCookieAttribute("", "cookie value");
  }

  @Test
  public void validatorCookieAttributeRejectsSemicolon() {
    try {
      HTTPTools.validateResponseCookieAttribute("attacker; Secure", "cookie value");
      fail("Expected IllegalArgumentException for semicolon in cookie attribute.");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("cookie value"));
    }
  }

  /**
   * Runs one h2c request against the handler and asserts that the server resets the stream with
   * RST_STREAM(INTERNAL_ERROR) before emitting any response HEADERS frame — i.e. validation fired before anything was
   * HPACK-encoded and nothing splittable reached the wire.
   */
  private void assertH2StreamResetBeforeHeaders(HTTPHandler handler) throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    try (HTTPServer server = makeServer("http", handler, listener).start();
         Socket sock = openH2CConnection(server.getActualPort())) {
      var out = sock.getOutputStream();
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1); // HEADERS, END_HEADERS | END_STREAM
      out.write(MINIMAL_HPACK_GET);
      out.flush();

      sock.setSoTimeout(5000);
      var in = sock.getInputStream();
      // Drain frames until the first HEADERS (0x1) or RST_STREAM (0x3): whichever arrives first decides the test.
      while (true) {
        int b0 = in.read();
        assertNotEquals(b0, -1, "Connection closed before HEADERS or RST_STREAM arrived");
        byte[] rest = new byte[8];
        assertEquals(in.readNBytes(rest, 0, 8), 8, "EOF while reading a frame header");
        int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
        int type = rest[2] & 0xFF;
        byte[] payload = in.readNBytes(length);
        if (type == 0x1) {
          fail("Server emitted a response HEADERS frame — the invalid value was not rejected before emission.");
        }
        if (type == 0x3) {
          int code = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
          assertEquals(code, 0x2, "Expected RST_STREAM error code INTERNAL_ERROR (0x2); got: [" + code + "]");
          return;
        }
      }
    }
  }
}

/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.Cookie;

import static org.testng.Assert.*;

/**
 * Verifies that {@link HTTPTools#writeResponsePreamble} rejects CR, LF, and NUL in every attacker-influenceable
 * response surface, which would otherwise allow HTTP response splitting (see docs/security/audit-2026-04-20.md Vuln 4).
 * Validation is concentrated at the preamble-write choke point rather than the setters so that direct mutation of the
 * internal header map (via {@code getHeadersMap()}) or a Cookie's public fields cannot bypass it.
 *
 * @author Brian Pontarelli
 */
@Test
public class ResponseSplittingTest {
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
}

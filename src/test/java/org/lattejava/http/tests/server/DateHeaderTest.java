/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.util.DateTools;

import static org.testng.Assert.*;

/**
 * Tests for the auto-emitted {@code Date} response header.
 * <p>
 * RFC 9110 §6.6.1 — an origin server with a clock MUST generate a Date header field for any 2xx, 3xx, or 4xx response.
 * The format is RFC 1123 (the IMF-fixdate variant), e.g. {@code Sun, 06 Nov 1994 08:49:37 GMT}.
 *
 * @author Daniel DeGroff
 */
public class DateHeaderTest extends BaseTest {
  /**
   * Use case: a stock server with no Date-related configuration. RFC 9110 §6.6.1 requires it for 2xx responses, so the
   * default must be on. Without this, any client doing freshness/staleness checks based on the response Date will
   * silently fall back to {@code Date received-by-recipient} per §6.6.1, which is fine but means we are pushing extra
   * work onto every recipient. Conformance also points the other way — origin servers MUST emit Date.
   */
  @Test(dataProvider = "schemes")
  public void date_header_present_by_default(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setContentLength(0L);
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(makeURI(scheme, "")).GET().build(),
          HttpResponse.BodyHandlers.discarding());

      assertEquals(response.statusCode(), 200);
      var date = response.headers().firstValue("Date").orElse(null);
      assertNotNull(date, "Server should send a Date header by default per RFC 9110 §6.6.1.");
    }
  }

  /**
   * Use case: a client that parses the Date header (e.g. for clock-skew detection or HTTP cache validation) needs the
   * value in IMF-fixdate (RFC 1123) format. The relevant grammar lives in RFC 9110 §5.6.7. Verify our value parses
   * successfully with the JDK's RFC 1123 formatter and is within a sane window of "now" — proves it reflects a real
   * clock instead of an arbitrary string.
   */
  @Test(dataProvider = "schemes")
  public void date_header_is_RFC_1123_and_current(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setContentLength(0L);
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      var client = makeClient(scheme, null);
      var before = Instant.now();
      var response = client.send(
          HttpRequest.newBuilder().uri(makeURI(scheme, "")).GET().build(),
          HttpResponse.BodyHandlers.discarding());
      var after = Instant.now();

      var date = response.headers().firstValue("Date").orElseThrow();
      Instant parsed;
      try {
        parsed = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
      } catch (DateTimeParseException e) {
        fail("Date header [" + date + "] does not parse as RFC 1123: " + e.getMessage());
        return;
      }

      // Allow a 5s grace window to absorb the cache granularity (1s) and clock jitter between client and server.
      assertTrue(!parsed.isBefore(before.minusSeconds(5)) && !parsed.isAfter(after.plusSeconds(5)),
                 "Date header [" + parsed + "] should be within 5s of [" + before + " .. " + after + "].");
    }
  }

  /**
   * Use case: a handler that wants a deterministic Date — e.g. a static-asset response with a stable last-modified date
   * served as Date, or a test fixture asserting an exact timestamp. The handler can pre-set Date and the server must
   * not overwrite it with the current time.
   */
  @Test(dataProvider = "schemes")
  public void handler_set_date_is_preserved(String scheme) throws Exception {
    String fixedDate = "Sun, 06 Nov 1994 08:49:37 GMT";
    HTTPHandler handler = (req, res) -> {
      res.setHeader(HTTPValues.Headers.Date, fixedDate);
      res.setStatus(200);
      res.setContentLength(0L);
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(makeURI(scheme, "")).GET().build(),
          HttpResponse.BodyHandlers.discarding());

      assertEquals(response.headers().firstValue("Date").orElse(null), fixedDate,
                   "Handler-set Date must not be overwritten by the auto-Date logic.");
    }
  }

  /**
   * Use case: regression coverage for IMF-fixdate (RFC 9110 §5.6.7) day-of-month zero-padding. The JDK's
   * {@link DateTimeFormatter#RFC_1123_DATE_TIME} emits 1-2 digits for day-of-month, so days 1-9 render as
   * {@code Sun, 3 May 2026 ...} — invalid IMF-fixdate. This formats an early-month instant directly to catch any
   * future regression that swaps the formatter back.
   */
  @Test
  public void formatter_zero_pads_single_digit_day() {
    Instant instant = Instant.parse("2026-05-03T08:49:37Z");
    String formatted = DateTools.RFC_5322_DATE_TIME.format(instant.atZone(ZoneOffset.UTC));
    assertEquals(formatted, "Sun, 03 May 2026 08:49:37 GMT",
                 "RFC_5322_DATE_TIME must zero-pad day-of-month per IMF-fixdate (RFC 9110 §5.6.7).");
  }

  /**
   * Use case: a handler that wants no Date header on this specific response — e.g. a server behind a reverse proxy
   * that adds Date itself, or a test fixture that does not want any clock-derived bytes in the response. Calling
   * {@link HTTPResponse#removeHeader} during request handling must be respected.
   * <p>
   * This works because the auto-Date logic populates the header before invoking the handler; the handler then has full
   * control to override or remove it.
   */
  @Test(dataProvider = "schemes")
  public void handler_can_suppress_date_via_remove(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.removeHeader(HTTPValues.Headers.Date);
      res.setStatus(200);
      res.setContentLength(0L);
    };

    try (HTTPServer ignore = makeServer(scheme, handler).start()) {
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(makeURI(scheme, "")).GET().build(),
          HttpResponse.BodyHandlers.discarding());

      assertFalse(response.headers().firstValue("Date").isPresent(),
                  "Handler removed Date — server must not re-add it.");
    }
  }

  /**
   * Use case: an embedded environment without a reliable wall clock (RFC 9110 §6.6.1 says such servers SHOULD NOT send
   * Date), or a test environment that wants byte-deterministic responses globally. Disabling Date at the server level
   * suppresses it for every response.
   */
  @Test(dataProvider = "schemes")
  public void server_config_can_disable_date_globally(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.setContentLength(0L);
    };

    try (HTTPServer ignore = makeServer(scheme, handler).withSendDateHeader(false).start()) {
      var client = makeClient(scheme, null);
      var response = client.send(
          HttpRequest.newBuilder().uri(makeURI(scheme, "")).GET().build(),
          HttpResponse.BodyHandlers.discarding());

      assertFalse(response.headers().firstValue("Date").isPresent(),
                  "Server with sendDateHeader=false must not emit Date.");
    }
  }
}

/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module org.testng;

/**
 * Raw-socket conformance tests for {@code RequestPreambleState}. Covers items that HTTP1.1.md §6 lists as ⚠️
 * "needs test" — the parser already rejects these per the security audit (Vuln 3 et al.); this file
 * locks that behavior in.
 *
 * @author Daniel DeGroff
 */
public class RequestPreambleConformanceTest extends BaseSocketTest {
  @Test
  public void bare_cr_in_header_value_rejected() throws Exception {
    // RFC 9112 §5: bare CR (CR not followed by LF) inside a header value MUST be rejected. HeaderValue → HeaderCR; HeaderCR only accepts \n.
    withRequest("GET / HTTP/1.1\r\n" +
                "Host: cyberdyne-systems.com\r\n" +
                "X: bad\rmore\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n"
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void chunk_extensions_parsed_and_discarded() throws Exception {
    // RFC 9112 §7.1.1: chunk-ext is allowed and ignored. Verifies a request with chunk-ext succeeds.
    withRequest("""
        POST /echo HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Transfer-Encoding: chunked\r
        \r
        5;name=value\r
        hello\r
        0\r
        \r
        """
    ).expectResponseSubstring("HTTP/1.1 200 ");
  }

  @Test
  public void empty_host_value_rejected() throws Exception {
    // RFC 9112 §3.2.3 is silent on empty Host, but common practice is to reject as 400. Lock current behavior in;
    // if this fails, Task 5 adds the validation and re-enables this test.
    withRequest("""
        GET / HTTP/1.1\r
        Host: \r
        Content-Length: 0\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void obs_fold_rejected() throws Exception {
    // RFC 9112 §5.2: obs-fold (line continuation via leading SP/HTAB) is forbidden. HeaderLF requires CR or token char at line start.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        X-Folded: line1\r
         line2\r
        Content-Length: 0\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void options_asterisk_form_accepted() throws Exception {
    // RFC 9110 §9.3.7: OPTIONS * is the asterisk-form for server-wide capability queries.
    withRequest("""
        OPTIONS * HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """
    ).expectResponseSubstring("HTTP/1.1 200 ");
  }

  @Test
  public void whitespace_before_colon_rejected() throws Exception {
    // RFC 9112 §5.1: no whitespace allowed between the field-name and the colon. HeaderName accepts only token chars or ':'.
    withRequest("""
        GET / HTTP/1.1\r
        Host : cyberdyne-systems.com\r
        Content-Length: 0\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }
}

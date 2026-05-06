/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import org.testng.annotations.*;

/**
 * Socket-level tests verifying that bare CR or LF bytes embedded in a header value are rejected as 400 rather than
 * absorbed into the stored value (see docs/security/audit-2026-04-20.md Vuln 3). Accepting a bare LF would let an
 * attacker splice a second header into a field value — and a front-end proxy that splits on LF would then disagree with
 * this server about header boundaries, a smuggling primitive even when Transfer-Encoding handling is strict.
 *
 * @author Brian Pontarelli
 */
public class BareLineFeedHeaderTest extends BaseSocketTest {
  @Test
  public void bare_lf_after_colon_rejected() throws Exception {
    // "X: \nEvil-Header: smuggled" — a bare LF immediately after the colon+space. Before this fix, HeaderColon's fallthrough would
    // transition to HeaderValue without validating the byte, so the LF became the first char of the value and the parser continued.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        X: \nEvil-Header: smuggled\r
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
  public void bare_lf_in_content_length_rejected() throws Exception {
    // A bare LF inside Content-Length would otherwise produce a value like "0\nTransfer-Encoding: chunked" which disagrees with a front
    // end that splits on LF. Confirm 400.
    withRequest("""
        POST / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Length: 0
        Transfer-Encoding: chunked\r
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
  public void bare_lf_in_header_value_rejected() throws Exception {
    // "X-Custom: value\nInjected: yes" — the literal "\n" (0x0A) is embedded mid-value, bypassing any front-end proxy that splits on
    // bare LF. The parser must throw rather than store "value\nInjected: yes" as the value.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        X-Custom: value
        Injected: yes\r
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
  public void bare_lf_in_transfer_encoding_rejected() throws Exception {
    // A bare LF in the Transfer-Encoding value would have previously stored "chunked\nX: Y" as the TE value; since equalsIgnoreCase
    // against "chunked" fails, validatePreamble would mis-handle it. This belt-and-suspenders test keeps the combined Vuln 1 + Vuln 3
    // defense covered.
    withRequest("""
        POST / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Transfer-Encoding: chunked
        X: Y\r
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

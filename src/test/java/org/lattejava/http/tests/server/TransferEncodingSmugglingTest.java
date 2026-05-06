/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module org.testng;

/**
 * Socket-level tests covering HTTP request smuggling defenses around Transfer-Encoding (see
 * docs/security/audit-2026-04-20.md Vuln 1). The server accepts only a single, exactly-{@code chunked}
 * Transfer-Encoding; every other shape (unsupported codings, mixed codings, duplicate headers, or TE + Content-Length
 * coexistence) is rejected as 400 so a front-end proxy that resolves the ambiguity differently cannot desync a
 * pipelined request onto the next keep-alive turn.
 *
 * @author Brian Pontarelli
 */
public class TransferEncodingSmugglingTest extends BaseSocketTest {
  @Test
  public void chunked_identity_rejected() throws Exception {
    // RFC 9112 §6.1 allows "chunked" as the final coding in a list, but this server only implements "chunked" on its own. Accepting
    // "chunked, identity" would force us to either (a) implement identity, or (b) guess — both risk disagreeing with a front-end parser.
    withRequest("""
        POST / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Transfer-Encoding: chunked, identity\r
        Content-Length: 44\r
        \r
        0\r
        \r
        GET /admin HTTP/1.1\r
        X: X\r
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
  public void chunked_with_tab_accepted() throws Exception {
    // After trimming the tab, the TE value is exactly "chunked" — accept and process as a normal chunked request. Uses a hand-written
    // zero-chunk body so this test doesn't depend on BaseSocketTest's case-sensitive "Transfer-Encoding: chunked" substring match.
    withRequest("""
        POST / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Transfer-Encoding: chunked\t\r
        \r
        0\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: keep-alive\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void chunked_with_trailing_space_accepted() throws Exception {
    // Before this fix, "chunked " (trailing space) would bypass the exact-match check in HTTPRequest.isChunked, leaving the body unread and
    // desyncing the socket on keep-alive. The fix trims the TE value before comparison and normalizes the stored value to "chunked".
    withRequest("""
        POST / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Transfer-Encoding: chunked \r
        \r
        0\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: keep-alive\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void duplicate_transfer_encoding_rejected() throws Exception {
    withRequest("""
        POST / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Transfer-Encoding: chunked\r
        Transfer-Encoding: chunked\r
        \r
        0\r
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
  public void identity_rejected() throws Exception {
    // "identity" was removed as a transfer coding in RFC 7230 but is still sent by some legacy clients. Before this fix, the mere presence
    // of any TE header caused Content-Length to be stripped — combined with isChunked() returning false for "identity", the body was read
    // as a null stream and the declared bytes became the next pipelined request.
    withRequest("""
        POST / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Transfer-Encoding: identity\r
        Content-Length: 44\r
        \r
        0\r
        \r
        GET /admin HTTP/1.1\r
        X: X\r
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
  public void mixed_case_chunked_accepted() throws Exception {
    // Case-insensitive match, then normalized to lowercase "chunked" for downstream checks.
    withRequest("""
        POST / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Transfer-Encoding: Chunked\r
        \r
        0\r
        \r
        """
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: keep-alive\r
        content-length: 0\r
        \r
        """);
  }

  @Test
  public void xchunked_rejected() throws Exception {
    // A token that starts with "chunked" but isn't exactly "chunked" after trimming — reject rather than accept-and-hope.
    withRequest("""
        POST / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Transfer-Encoding: xchunked\r
        Content-Length: 5\r
        \r
        hello"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }
}

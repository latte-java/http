/*
 * Copyright (c) 2025, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.lattejava.http.tests.server;

import java.nio.charset.StandardCharsets;

import org.lattejava.http.HTTPValues.Headers;
import org.lattejava.http.HTTPValues.TransferEncodings;
import org.lattejava.http.server.HTTPHandler;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Tests the HTTP server by writing directly to the server and reading using sockets to test lower level semantics.
 *
 * @author Daniel DeGroff
 */
public class HTTP11SocketTest extends BaseSocketTest {
  @Test(invocationCount = 100)
  public void bad_request() throws Exception {
    // Invalid HTTP header
    withRequest("""
        cat /etc/password\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Bare line feed (\n) instead of CRLF (\r\n) in the request line.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9112#section-2.2">RFC 9112 Section 2.2</a>
   * <p>
   * The parser requires \r before \n. A bare \n in the request protocol state is rejected as an unexpected character.
   */
  @Test
  public void bare_line_feed() throws Exception {
    // Bare \n (missing \r) after the protocol version. The preamble parser only accepts \r as the transition from
    // RequestProtocol to RequestCR. A bare \n will cause a ParseException.
    withRequest("""
        GET / HTTP/1.1
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Header matching must be case-insensitive.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9110#section-5.1">RFC 9110 Section 5.1</a>
   * <p>
   * Header field names are case-insensitive. Verify that uppercase "HOST" is accepted.
   */
  @Test(invocationCount = 100)
  public void case_insensitive_header_matching() throws Exception {
    withRequest("""
        GET / HTTP/1.1\r
        HOST: cyberdyne-systems.com\r
        CONTENT-TYPE: plain/text\r
        CONTENT-LENGTH: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: keep-alive\r
        content-length: 0\r
        \r
        """);
  }

  @DataProvider(name = "chunk-extensions")
  public Object[][] chunkExtensions() {
    return new Object[][]{
        {";foo=bar"},          // Single extension
        {";foo"},              // Single extension, no value
        {";foo="},             // Single extension, no value, with =
        {";foo=bar;bar=baz"},  // Two extensions
        {";foo=bar;bar="},     // Two extensions, second no value
        {";foo=bar;bar"},      // Two extensions, second no value, with =
    };
  }

  /**
   * Connection: close on HTTP/1.1 should result in a 200 and the response should include connection: close.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9112#section-9.6">RFC 9112 Section 9.6</a>
   */
  @Test
  public void connection_close() throws Exception {
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Connection: close\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Control characters (e.g., NUL) are not allowed in header names.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9110#section-5.1">RFC 9110 Section 5.1</a>
   * <p>
   * Header field names must be token characters. Control characters are not token characters.
   */
  @Test
  public void control_characters_in_header_name() throws Exception {
    // NUL byte (0x00) in header name. The parser only accepts token characters for header names.
    withRequest("GET / HTTP/1.1\r\nHost: cyberdyne-systems.com\r\nX-Bad\0Header: value\r\nContent-Length: {contentLength}\r\n\r\n{body}"
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Duplicate Content-Length headers with different values.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9112#section-6.3">RFC 9112 Section 6.3</a>
   * <p>
   * If multiple Content-Length headers are received, the server must reject the message.
   */
  @Test
  public void duplicate_content_length_different_values() throws Exception {
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        Content-Length: 20\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(invocationCount = 100)
  public void duplicate_host_header() throws Exception {
    // Duplicate Host header
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(invocationCount = 100)
  public void duplicate_host_header_withTransferEncoding() throws Exception {
    // Duplicate Host header w/ Transfer-Encoding instead of Content-Length
    // - In this case the Transfer-Encoding is only to ensure we can correctly drain the InputStream so the client can read the response.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Transfer-Encoding: chunked\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Header exceeding the maximum request header size.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc6585#section-5">RFC 6585 Section 5</a>
   * <p>
   * When the total preamble (request line + headers) exceeds the configured maximum, the server returns 431.
   */
  @Test
  public void header_exceeding_max_header_size() throws Exception {
    // Create a header value that, combined with the rest of the preamble, exceeds the configured max.
    String longValue = "x".repeat(300);
    withRequest("GET / HTTP/1.1\r\nHost: cyberdyne-systems.com\r\nX-Large: " + longValue + "\r\nContent-Length: {contentLength}\r\n\r\n{body}"
    ).withMaxRequestHeaderSize(256)
     .expectResponse("""
         HTTP/1.1 431 \r
         connection: close\r
         content-length: 0\r
         \r
         """);
  }

  /**
   * Host header requirements. Must be provided and not duplicated.
   * </p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc7230#section-5.4">RFC 7230 Section 5.4</a>
   * </p>
   * <pre>
   *   A server MUST respond with a 400 (Bad Request) status code to any
   *    HTTP/1.1 request message that lacks a Host header field and to any
   *    request message that contains more than one Host header field or a
   *    Host header field with an invalid field-value.
   * </pre>
   */
  @Test(invocationCount = 100)
  public void host_header_required() throws Exception {
    // Host header is required, return 400 if not provided
    withRequest("""
        GET / HTTP/1.1\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(invocationCount = 100)
  public void host_header_required_with_X_Forwarded_Host() throws Exception {
    // Ensure that X-Forwarded-Host doesn't count for the Host header
    withRequest("""
        GET / HTTP/1.1\r
        X-Forwarded-Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * HTTP/1.0 with Host header should be accepted. HTTP/1.0 defaults to Connection: close.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9112#section-7.2">RFC 9112 Section 7.2</a>
   */
  @Test
  public void http_1_0_with_host() throws Exception {
    withRequest("""
        GET / HTTP/1.0\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * HTTP/1.0 without Host header.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9112#section-7.2">RFC 9112 Section 7.2</a>
   * <p>
   * Per the RFC, a server should accept HTTP/1.0 requests without a Host header. However, this server requires Host for
   * all protocol versions. This test documents the current behavior (400).
   */
  @Test
  public void http_1_0_without_host() throws Exception {
    withRequest("""
        GET / HTTP/1.0\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Content-Length header requirements for size.
   * </p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc7230#section-3.3.2">RFC 7230 Section 3.3.2</a>
   * </p>
   * <pre>
   *   Any Content-Length field value greater than or equal to zero is
   *    valid.  Since there is no predefined limit to the length of a
   *    payload, a recipient MUST anticipate potentially large decimal
   *    numerals and prevent parsing errors due to integer conversion
   *    overflows (Section 9.3).
   * </pre>
   */
  @Test(invocationCount = 100)
  public void invalid_content_length() throws Exception {
    // In this implementation the Content-Length is stored as a long, and as such we will take a Number Format Exception if the number exceeds Long.MAX_VALUE.
    // - The above-mentioned RFC does indicate we should account for this - Long.MAX_VALUE is 2^63 - 1, which seems like a reasonable limit.

    // Too large, we will take a NumberFormatException and set this value to null in the request.
    // - So as it is written, we won't return the user an error, but we will assume that a body is not present.
    withRequest(("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: 9223372036854775808\r
        \r
        {body}""")
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Negative
    withRequest(("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: -1\r
        \r
        {body}""")
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Max Long - but negative
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: -9223372036854775807\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Protocol Versioning requirements
   * </p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc7230#section-2.6">RFC 7230 Section 2.6</a>
   * </p>
   */
  @Test(invocationCount = 100)
  public void invalid_version() throws Exception {
    // Invalid: HTTP/1
    // - missing the '.' (dot) and the second digit.
    withRequest("""
        GET / HTTP/1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse(
        """
            HTTP/1.1 505 \r
            connection: close\r
            content-length: 0\r
            \r
            """);

    // Invalid: HTTP/1.
    // - missing the minor version digit
    withRequest("""
        GET / HTTP/1.\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Invalid: HTTP/1.2
    withRequest("""
        GET / HTTP/1.2\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Invalid: HTTP/9.9
    withRequest("""
        GET / HTTP/9.9\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 505 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(invocationCount = 100)
  public void keepAlive_bodyNeverRead() throws Exception {
    // Use case: Using keep alive, and the request handler doesn't read the payload.
    // - Ensure the HTTP worker is able to drain the bytes so the next request starts with an empty byte array.
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 200 \r
        connection: keep-alive\r
        content-length: 0\r
        \r
        """);
  }

  @Test(invocationCount = 100)
  public void mangled_version() throws Exception {
    // HTTP reversed does not begin with HTTP/
    // - This will fail during the preamble parsing, so we are not returning a 505 in this case. My opinion is that
    //   this is not an invalid protocol version, it is simply a malformed request.
    withRequest("""
        GET / PTTH/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);

    // Repeated allowed characters, does not begin with HTTP/
    // - This will fail during the preamble parsing, so we are not returning a 505 in this case. My opinion is that
    //   this is not an invalid protocol version, it is simply a malformed request.
    withRequest("""
        GET / HHHH/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(invocationCount = 100)
  public void missing_protocol() throws Exception {
    // - This will fail during the preamble parsing, so we are not returning a 505 in this case. My opinion is that
    //   this is not an invalid protocol version, it is simply a malformed request.
    withRequest("""
        GET /\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * NUL byte (0x00) in the request URI path.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9112#section-3">RFC 9112 Section 3</a>
   * <p>
   * NUL (0x00) is not a valid URI character. The parser requires characters in the range 0x21-0x7E.
   */
  @Test
  public void nul_bytes_in_request_line() throws Exception {
    withRequest("GET /\0path HTTP/1.1\r\nHost: cyberdyne-systems.com\r\nContent-Length: {contentLength}\r\n\r\n{body}"
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * Obsolete line folding (obs-fold) in header values.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9112#section-5.2">RFC 9112 Section 5.2</a>
   * <p>
   * Obs-fold (continuation line starting with SP or HTAB) is deprecated. After a header line's \r\n, the parser expects
   * either a token character (next header name) or \r (end of headers). A space causes a ParseException.
   */
  @Test
  public void obs_fold() throws Exception {
    // Header continuation line (obs-fold): value followed by \r\n then a space-prefixed continuation.
    // The parser does not support obs-fold and rejects the SP after the header LF.
    withRequest("GET / HTTP/1.1\r\nHost: cyberdyne-systems.com\r\nX-Custom: value\r\n continued\r\nContent-Length: {contentLength}\r\n\r\n{body}"
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  @Test(dataProvider = "chunk-extensions", invocationCount = 100)
  public void transfer_encoding_chunked_extensions(String chunkExtension) throws Exception {
    // Ensure we can properly ignore chunked extensions
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Transfer-Encoding: chunked\r
        \r
        {body}"""
    ).withChunkedExtension(chunkExtension)
     .expectResponse("""
         HTTP/1.1 200 \r
         connection: keep-alive\r
         content-length: 0\r
         \r
         """);
  }

  /**
   * A request that carries both Content-Length and Transfer-Encoding is rejected as 400. Per RFC 9112 §6.1, such a
   * message is ambiguous — different intermediaries resolve the precedence differently — and is a classic
   * request-smuggling primitive (see docs/security/audit-2026-04-20.md Vuln 1). Silently stripping Content-Length (RFC
   * 7230's older guidance) is unsafe because a front-end proxy that honored Content-Length would desync from this
   * server.
   */
  @Test(invocationCount = 250)
  public void transfer_encoding_content_length() throws Exception {
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type: plain/text\r
        Content-Length: {contentLength}\r
        Transfer-Encoding: chunked\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * URI exceeding the maximum request header size.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9110#section-15.5.15">RFC 9110 Section 15.5.15</a>
   * <p>
   * Note: the server does not have a separate URI length limit. The URI is part of the preamble, which is bounded by
   * maxRequestHeaderSize. Exceeding this limit returns 431 (Request Header Fields Too Large) rather than the
   * RFC-specified 414 (URI Too Long). This test documents the current behavior.
   */
  @Test
  public void uri_exceeding_max_header_size() throws Exception {
    String longPath = "/" + "a".repeat(300);
    withRequest("GET " + longPath + " HTTP/1.1\r\nHost: cyberdyne-systems.com\r\nContent-Length: {contentLength}\r\n\r\n{body}"
    ).withMaxRequestHeaderSize(256)
     .expectResponse("""
         HTTP/1.1 431 \r
         connection: close\r
         content-length: 0\r
         \r
         """);
  }

  /**
   * Whitespace before the header name colon.
   * <p>
   * See <a href="https://www.rfc-editor.org/rfc/rfc9112#section-5.1">RFC 9112 Section 5.1</a>
   * <p>
   * No whitespace is allowed between the header field-name and colon. The parser rejects a space in the HeaderName
   * state since it is not a token character.
   */
  @Test
  public void whitespace_before_header_colon() throws Exception {
    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        Content-Type : plain/text\r
        Content-Length: {contentLength}\r
        \r
        {body}"""
    ).expectResponse("""
        HTTP/1.1 400 \r
        connection: close\r
        content-length: 0\r
        \r
        """);
  }

  /**
   * GET handler that explicitly sets Transfer-Encoding: chunked and writes bytes. The server must wrap the output
   * delegate in ChunkedOutputStream, so the bytes are chunk-framed on the wire — not emitted raw.
   */
  @Test
  public void get_handlerSetsTransferEncodingChunked_writesBytes_serverChunksFrames() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(200);
      res.setHeader(Headers.TransferEncoding, TransferEncodings.Chunked);
      res.getOutputStream().write("abcdefgh".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            transfer-encoding: chunked\r
            \r
            8\r
            abcdefgh\r
            0\r
            \r
            """);
  }

  /**
   * GET handler that sets Transfer-Encoding: chunked but writes nothing. The server must strip the unused TE header and
   * force Content-Length: 0 (defensive override).
   */
  @Test
  public void get_handlerSetsTransferEncodingChunked_noWrite_strippedForContentLengthZero() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(200);
      res.setHeader(Headers.TransferEncoding, TransferEncodings.Chunked);
      // Writes nothing.
    };

    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-length: 0\r
            \r
            """);
  }

  /**
   * GET handler that sets both Content-Length and Transfer-Encoding: chunked, then writes bytes. TE wins: CL must be
   * stripped, and the body must be chunk-framed on the wire.
   */
  @Test
  public void get_handlerSetsBothContentLengthAndTransferEncoding_writes_contentLengthStripped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(200);
      res.setContentLength(8L);
      res.setHeader(Headers.TransferEncoding, TransferEncodings.Chunked);
      res.getOutputStream().write("abcdefgh".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            transfer-encoding: chunked\r
            \r
            8\r
            abcdefgh\r
            0\r
            \r
            """);
  }

  /**
   * GET to a 204 handler that writes bytes. The body must be suppressed, and neither content-length nor
   * transfer-encoding should appear in the preamble (RFC 9110 §15.3.5).
   */
  @Test
  public void get_status204_handlerWritesBytes_bodySuppressed() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(204);
      res.getOutputStream().write("0123456789".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 204 \r
            connection: keep-alive\r
            \r
            """);
  }

  /**
   * GET to a 304 handler that writes bytes. The body must be suppressed, and neither content-length nor
   * transfer-encoding should appear in the preamble (RFC 9110 §15.4.5).
   */
  @Test
  public void get_status304_handlerWritesBytes_bodySuppressed() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(304);
      res.getOutputStream().write("cached content".getBytes(StandardCharsets.UTF_8));
    };

    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 304 \r
            connection: keep-alive\r
            \r
            """);
  }

  /**
   * GET to a 204 handler that sets Content-Length: 50 and writes nothing. The header must be stripped.
   */
  @Test
  public void get_status204_handlerSetsContentLength_stripped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(204);
      res.setContentLength(50L);
    };

    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 204 \r
            connection: keep-alive\r
            \r
            """);
  }

  /**
   * GET to a 304 handler that sets Content-Length: 50 and writes nothing. The header must be stripped.
   */
  @Test
  public void get_status304_handlerSetsContentLength_stripped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(304);
      res.setContentLength(50L);
    };

    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 304 \r
            connection: keep-alive\r
            \r
            """);
  }

  /**
   * Defensive override: when the handler sets both Content-Length and Transfer-Encoding but writes no bytes, TE-wins
   * strips the CL first, then the GET defensive path strips the unused TE and forces Content-Length: 0 so the client
   * does not wait for either framing.
   */
  @Test
  public void get_handlerSetsBothContentLengthAndTransferEncoding_noWrite_defensiveContentLengthZero() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(200);
      res.setContentLength(50L);
      res.setHeader(Headers.TransferEncoding, TransferEncodings.Chunked);
    };

    withRequest("""
        GET / HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 200 \r
            connection: keep-alive\r
            content-length: 0\r
            \r
            """);
  }

  /**
   * Status 304 must not carry Transfer-Encoding per RFC 9110 §15.4.5. If the handler tries to set one, the server
   * strips it.
   */
  @Test
  public void get_status304_handlerSetsTransferEncoding_stripped() throws Exception {
    HTTPHandler handler = (_, res) -> {
      res.setStatus(304);
      res.setHeader(Headers.TransferEncoding, TransferEncodings.Chunked);
    };

    withRequest("""
        GET /etag HTTP/1.1\r
        Host: cyberdyne-systems.com\r
        \r
        """)
        .withHandler(handler)
        .expectResponse("""
            HTTP/1.1 304 \r
            connection: keep-alive\r
            \r
            """);
  }
}

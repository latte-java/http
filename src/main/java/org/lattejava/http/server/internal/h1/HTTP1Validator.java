/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h1;

import module org.lattejava.http;

import java.lang.System.Logger.Level;

/**
 * Validates HTTP/1.x requests.
 *
 * @author Brian Pontarelli
 */
public final class HTTP1Validator {
  private static final System.Logger logger = System.getLogger(HTTP1Validator.class.getName());

  private HTTP1Validator() {
  }

  /**
   * Validates the preamble of an HTTP/1.x request.
   *
   * @param request The request to validate.
   * @return An error status if the request is invalid, otherwise {@code null}.
   */
  static Integer validatePreamble(HTTPRequest request) {
    var debugEnabled = logger.isLoggable(Level.DEBUG);

    // Validate protocol. Protocol version is required.
    String protocol = request.getProtocol();
    if (protocol == null) {
      logger.log(Level.DEBUG, "Invalid request. Missing HTTP Protocol");
      return HTTPValues.Status.BadRequest;
    }

    // Only HTTP/ protocol is supported.
    if (!protocol.startsWith("HTTP/")) {
      if (debugEnabled) {
        logger.log(Level.DEBUG, "Invalid request. Invalid protocol [{0}]. Supported versions [{1}].", protocol, HTTPValues.Protocols.HTTTP1_1);
      }

      return HTTPValues.Status.BadRequest;
    }

    // Minor versions less than 1 are allowed per spec. For example, HTTP/1.0 should be allowed and is considered to be compatible enough.
    if (!protocol.equals("HTTP/1.0") && !protocol.equals("HTTP/1.1")) {
      if (debugEnabled) {
        logger.log(Level.DEBUG, "Invalid request. Unsupported HTTP version [{0}]. Supported versions [{1}].", protocol, HTTPValues.Protocols.HTTTP1_1);
      }

      return HTTPValues.Status.HTTPVersionNotSupported;
    }

    // Host header is required
    var host = request.getRawHost();
    if (host == null) {
      logger.log(Level.DEBUG, "Invalid request. Missing Host header.");
      return HTTPValues.Status.BadRequest;
    }

    var hostHeaders = request.getHeaders(HTTPValues.Headers.Host);
    if (hostHeaders.size() != 1) {
      if (debugEnabled) {
        logger.log(Level.DEBUG, "Invalid request. Duplicate Host headers. [{0}]", String.join(", ", hostHeaders));
      }

      return HTTPValues.Status.BadRequest;
    }

    // Validate Transfer-Encoding and Content-Length per RFC 9112 §6.1 (see docs/design/2026-04-20-audit.md Vuln 1). This server only
    // supports the "chunked" transfer coding, so anything else must be rejected rather than silently discarded — mishandling TE is the
    // classic request-smuggling primitive. Specifically we reject: multiple Transfer-Encoding headers, TE values that aren't exactly
    // "chunked" after trimming (e.g. "identity", "chunked, identity", "xchunked", "chunked " with trailing whitespace), and the CL+TE
    // coexistence that a front-end proxy might resolve differently than we do.
    var transferEncodingHeaders = request.getHeaders(HTTPValues.Headers.TransferEncoding);
    if (transferEncodingHeaders != null && !transferEncodingHeaders.isEmpty()) {
      if (transferEncodingHeaders.size() != 1) {
        if (debugEnabled) {
          logger.log(Level.DEBUG, "Invalid request. Multiple Transfer-Encoding headers. [{0}]", String.join(", ", transferEncodingHeaders));
        }

        return HTTPValues.Status.BadRequest;
      }

      String rawTransferEncoding = transferEncodingHeaders.getFirst();
      if (!HTTPValues.TransferEncodings.Chunked.equalsIgnoreCase(rawTransferEncoding.trim())) {
        if (debugEnabled) {
          logger.log(Level.DEBUG, "Invalid request. Unsupported Transfer-Encoding. [{0}]", rawTransferEncoding);
        }

        return HTTPValues.Status.BadRequest;
      }

      if (request.getHeader(HTTPValues.Headers.ContentLength) != null) {
        if (debugEnabled) {
          logger.log(Level.DEBUG, "Invalid request. Both Transfer-Encoding and Content-Length present. [{0}] [{1}]", rawTransferEncoding, request.getHeader(HTTPValues.Headers.ContentLength));
        }

        return HTTPValues.Status.BadRequest;
      }

      // Normalize the stored value so downstream code (HTTPRequest.isChunked, ChunkedInputStream routing) sees an exact match regardless
      // of incidental whitespace or case in the original header.
      if (!HTTPValues.TransferEncodings.Chunked.equals(rawTransferEncoding)) {
        request.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
      }
    } else {
      var requestedContentLengthHeaders = request.getHeaders(HTTPValues.Headers.ContentLength);
      if (requestedContentLengthHeaders != null) {
        if (requestedContentLengthHeaders.size() != 1) {
          if (debugEnabled) {
            logger.log(Level.DEBUG, "Invalid request. Duplicate Content-Length headers. [{0}]", String.join(", ", requestedContentLengthHeaders));
          }

          // If we cannot trust the Content-Length it is unlikely we can correctly drain the InputStream in order for the client to read our response.
          return HTTPValues.Status.BadRequest;
        }

        var contentLength = request.getContentLength();
        if (contentLength == null || contentLength < 0) {
          if (debugEnabled) {
            logger.log(Level.DEBUG, "Invalid request. The Content-Length must be >= 0 and <= 9,223,372,036,854,775,807. [{0}]", requestedContentLengthHeaders.getFirst());
          }

          // If we cannot trust the Content-Length it is unlikely we can correctly drain the InputStream in order for the client to read our response.
          return HTTPValues.Status.BadRequest;
        }
      }
    }

    // Validate Content-Encoding, we currently support deflate and gzip.
    // - If we see anything else we should fail, we will be unable to handle the request.
    var contentEncodings = request.getContentEncodings();
    for (var encoding : contentEncodings) {
      if (!encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Gzip) && !encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Deflate)) {
        // Note that while we do not expect multiple Content-Encoding headers, the last one will be used. For good measure,
        // use the last one in the debug message as well.
        var contentEncodingHeader = request.getHeaders(HTTPValues.Headers.ContentEncoding).getLast();
        logger.log(Level.DEBUG, "Invalid request. The Content-Type header contains an un-supported value. [{0}]", contentEncodingHeader);
        return HTTPValues.Status.UnsupportedMediaType;
      }
    }

    return null;
  }
}

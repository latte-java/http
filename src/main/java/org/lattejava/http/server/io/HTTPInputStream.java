/*
 * Copyright (c) 2024-2025, FusionAuth, All Rights Reserved
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
package org.lattejava.http.server.io;

import java.io.*;
import java.lang.System.Logger.Level;
import java.util.zip.*;

import org.lattejava.http.*;
import org.lattejava.http.HTTPValues.*;
import org.lattejava.http.io.*;
import org.lattejava.http.io.PushbackInputStream;
import org.lattejava.http.server.*;

/**
 * An InputStream intended to read the HTTP request body.
 * <p>
 * This will handle fixed length requests, chunked requests as well as decompression if necessary.
 *
 * @author Brian Pontarelli
 */
public class HTTPInputStream extends InputStream {
  private static final System.Logger logger = System.getLogger(HTTPInputStream.class.getName());

  private final byte[] b1 = new byte[1];

  private final int chunkedBufferSize;

  private final Instrumenter instrumenter;

  private final int maxRequestChunkSize;

  private final int maximumBytesToDrain;

  private final long maximumContentLength;

  private final PushbackInputStream pushbackInputStream;

  private final HTTPRequest request;

  private long bytesRead;

  private ChunkedInputStream chunkedDelegate;

  private boolean closed;

  private InputStream delegate;

  private boolean drained;

  private boolean initialized;

  private boolean trailersCopied;

  public HTTPInputStream(HTTPServerConfiguration configuration, HTTPRequest request, PushbackInputStream pushbackInputStream,
                         long maximumContentLength) {
    this.instrumenter = configuration.getInstrumenter();
    this.request = request;
    this.delegate = pushbackInputStream;
    this.pushbackInputStream = pushbackInputStream;
    this.chunkedBufferSize = configuration.getHTTP1Configuration().getChunkedBufferSize();
    this.maxRequestChunkSize = configuration.getHTTP1Configuration().getMaxRequestChunkSize();
    this.maximumBytesToDrain = configuration.getMaxBytesToDrain();
    this.maximumContentLength = maximumContentLength;
  }

  /**
   * Constructor for subclasses that represent a stream with no underlying delegate (e.g. the bodyless-request singleton
   * {@code EmptyHTTPInputStream}). All inherited fields are left null/zero; subclasses MUST override every public
   * method that would otherwise dereference them.
   */
  protected HTTPInputStream() {
    this.instrumenter = null;
    this.request = null;
    this.delegate = null;
    this.pushbackInputStream = null;
    this.chunkedBufferSize = 0;
    this.maxRequestChunkSize = 0;
    this.maximumBytesToDrain = 0;
    this.maximumContentLength = 0;
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }

    closed = true;
    if (drained) {
      return;
    }

    drain();
  }

  public int drain() throws IOException {
    if (drained) {
      return 0;
    }

    drained = true;

    // Fast-path: if the request carries no body, there is nothing to drain.
    // This covers both the case where nobody called read() yet (!initialized) and the case where
    // the handler called readAllBytes() / read() on a bodyless request (GET/HEAD) — both result in
    // an empty underlying stream, so allocating the skip buffer and looping is pointless.
    // Null guard: subclasses constructed via the no-arg constructor (EmptyHTTPInputStream) leave request null;
    // they MUST override drain() to avoid reaching here, but the guard makes the failure mode graceful if not.
    if (request == null || !request.hasBody()) {
      return 0;
    }

    int total = 0;
    byte[] skipBuffer = new byte[2048];
    while (true) {
      int skipped = read(skipBuffer);
      if (skipped < 0) {
        break;
      }

      total += skipped;

      if (total > maximumBytesToDrain) {
        throw new TooManyBytesToDrainException(total, maximumBytesToDrain);
      }
    }

    return total;
  }

  @Override
  public int read() throws IOException {
    var read = read(b1);
    if (read <= 0) {
      return read;
    }

    return b1[0] & 0xFF;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    if (len == 0) {
      return 0;
    }

    if (!initialized) {
      initialize();
    }

    // When a maximum content length has been specified, read at most one byte past the maximum so the streaming check
    // below can trip with a single boundary read. maximumContentLength is a long so the +1 cannot overflow at the int
    // boundary, but we still clamp against len via Math.min before casting back to int.
    int maxReadLen = maximumContentLength == -1
        ? len
        : (int) Math.min((long) len, maximumContentLength - bytesRead + 1L);
    int read = delegate.read(b, off, maxReadLen);
    if (read > 0) {
      bytesRead += read;
    }

    // Throw an exception once we have read past the maximum configured content length,
    if (maximumContentLength != -1 && bytesRead > maximumContentLength) {
      String detailedMessage = "The maximum request size has been exceeded. The maximum request size is [" + maximumContentLength + "] bytes.";
      throw new ContentTooLargeException(maximumContentLength, detailedMessage);
    }

    if (read == -1 && !trailersCopied && chunkedDelegate != null) {
      trailersCopied = true;
      for (var entry : chunkedDelegate.getTrailers().entrySet()) {
        for (String value : entry.getValue()) {
          request.addTrailer(entry.getKey(), value);
        }
      }
    }

    return read;
  }

  private void initialize() throws IOException {
    initialized = true;

    // hasBody means we are either using chunked transfer encoding or we have a non-zero Content-Length.
    boolean hasBody = request.hasBody();
    if (hasBody) {
      Long contentLength = request.getContentLength();
      // Transfer-Encoding always takes precedence over Content-Length. In practice if they were to both be present on
      // the request we would have removed Content-Length during validation to remove ambiguity. See HTTP1Connection.validatePreamble.
      if (request.isChunked()) {
        logger.log(Level.TRACE, "Client indicated it was sending an entity-body in the request. Handling body using chunked encoding.");
        ChunkedInputStream chunked = new ChunkedInputStream(pushbackInputStream, chunkedBufferSize, maxRequestChunkSize);
        chunkedDelegate = chunked;
        delegate = chunked;
        if (instrumenter != null) {
          instrumenter.chunkedRequest();
        }
      } else if (request.isHTTP2()) {
        // HTTP/2 request: the frame layer (HTTP2Connection.handleData) enforces content-length against DATA frame
        // payload totals, and HTTP2InputStream signals EOF only when END_STREAM arrives (on DATA or on trailers HEADERS).
        // Wrapping in FixedLengthInputStream here would EOF at content-length bytes — before request trailers can be
        // delivered, breaking RFC 9113 §8.1 trailer semantics.
        delegate = pushbackInputStream;
      } else {
        logger.log(Level.TRACE, "Client indicated it was sending an entity-body in the request. Handling body using Content-Length header [{0}].", contentLength);
        delegate = new FixedLengthInputStream(pushbackInputStream, contentLength);
      }

      // Now that we have the InputStream set up to read the body, handle decompression.
      // The request may contain more than one value, apply in reverse order.
      // - These are both using the default 512 buffer size.
      for (String contentEncoding : request.getContentEncodings().reversed()) {
        if (contentEncoding.equalsIgnoreCase(ContentEncodings.Deflate)) {
          delegate = new InflaterInputStream(delegate);
        } else if (contentEncoding.equalsIgnoreCase(ContentEncodings.Gzip)) {
          delegate = new GZIPInputStream(delegate);
        }
      }

      // If we have a fixed length request that is reporting a contentLength larger than the configured maximum, fail early.
      // - Do this last so if anyone downstream wants to read from the InputStream it would work.
      // - Note that it is possible that the body is compressed which would mean the contentLength represents the compressed value.
      //   But when we decompress the bytes the result will be larger than the reported contentLength, so we can safely throw this exception.
      if (contentLength != null && maximumContentLength != -1 && contentLength > maximumContentLength) {
        String detailedMessage = "The maximum request size has been exceeded. The reported Content-Length is [" + contentLength + "] and the maximum request size is [" + maximumContentLength + "] bytes.";
        throw new ContentTooLargeException(maximumContentLength, detailedMessage);
      }
    } else {
      // This means that we did not find Content-Length or Transfer-Encoding on the request. Do not attempt to read from the InputStream.
      // - Note that the spec indicates it is plausible for a client to send an entity body and omit these two headers and the server can optionally
      //   read bytes until the end of the InputStream is reached. This would assume Connection: close was also sent because if we do not know
      //   how to delimit the request we cannot use a persistent connection.
      // - We aren't doing any of that - if the client wants to send bytes, it needs to send a Content-Length header, or specify Transfer-Encoding: chunked.
      // - HTTP/2 streams may omit Content-Length and Transfer-Encoding (gRPC for example), so hasBody() returns false. The underlying
      //   HTTP2InputStream signals EOF at END_STREAM, so delegate through to pushbackInputStream rather than nullInputStream().
      if (request.isHTTP2()) {
        delegate = pushbackInputStream;
      } else {
        logger.log(Level.TRACE, "Client indicated it was NOT sending an entity-body in the request");
        delegate = InputStream.nullInputStream();
      }
    }
  }
}

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

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.server.internal.*;

/**
 * The primary response body output stream for the HTTP server, shared by HTTP/1.1 and HTTP/2. It owns the commit
 * lifecycle, body suppression (HEAD, 204, and 304 responses), and compression (gzip/deflate), and delegates the
 * protocol-specific concerns — emitting the response headers and framing the body — to an {@link HTTPOutputProtocol}: a
 * text preamble plus chunked framing for HTTP/1.1, and an HPACK HEADERS frame plus DATA frames for HTTP/2.
 *
 * @author Brian Pontarelli
 */
public class HTTPOutputStream extends OutputStream {
  private final List<String> acceptEncodings;
  private final Instrumenter instrumenter;
  private final HTTPOutputProtocol protocol;
  private final HTTPResponse response;
  private boolean bodySuppressed;
  private boolean closed;
  private boolean committed;
  private boolean compress;
  private OutputStream delegate;
  private boolean suppressBody;

  public HTTPOutputStream(HTTPServerConfiguration configuration, HTTPRequest request, HTTPResponse response, HTTPOutputProtocol protocol) {
    this.acceptEncodings = request.getAcceptEncodings();
    this.compress = configuration.isCompressByDefault();
    this.instrumenter = configuration.getInstrumenter();
    this.response = response;
    this.protocol = protocol;
  }

  /**
   * Commits the response if it has not been already (writing the preamble or HTTP/2 HEADERS), flushes any remaining body
   * bytes, emits trailers, and pushes everything to the client. This method is idempotent — calling it again after the
   * first call is a no-op.
   */
  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;

    commit(true);
    protocol.prepareTrailers();
    if (delegate != null) {
      delegate.close();
    }
    protocol.commitTrailers();
    protocol.forceFlush();
  }

  /**
   * Flushes already-written body bytes to the client. This method does NOT commit the response: it will not emit the
   * response preamble or HEADERS frame before the first body byte has been written, so calling it on a stream that has
   * not yet been written to is a no-op (HTTP/1 never committed on {@code flush()} either, so this contract is consistent
   * across protocols). Handlers that want to commit the response and push the status and headers to the client early
   * (for example, an HTTP/2 bidirectional-streaming handler) must call {@link HTTPResponse#flush()} instead, which
   * routes through {@link #forceFlush()} and commits the response.
   */
  @Override
  public void flush() throws IOException {
    if (delegate != null) {
      delegate.flush();
    }
  }

  /**
   * Commits the response (writing the preamble/HEADERS if not yet written) and forces all buffered bytes to the client.
   */
  public void forceFlush() throws IOException {
    commit(false);
    if (delegate != null) {
      delegate.flush();
    }
    protocol.forceFlush();
  }

  /**
   * Determines whether the response has been committed, meaning at least one byte — for HTTP/2, the response HEADERS
   * frame — has been written back to the client. Once committed, the status, headers, and compression configuration can
   * no longer be changed and {@link #reset()} will throw.
   *
   * @return {@code true} if the response has been committed, {@code false} if it has not been generated yet or is still
   *     buffered.
   */
  public boolean isCommitted() {
    return protocol.wroteToClient();
  }

  /**
   * Indicates whether compression is currently enabled for this response. This reflects the configured intent; whether
   * compression is actually applied to the body also depends on the request's accepted encodings, which
   * {@link #willCompress()} accounts for.
   *
   * @return {@code true} if compression is enabled.
   */
  public boolean isCompress() {
    return compress;
  }

  /**
   * Enables or disables compression of the response body. This may be called repeatedly, but only before the first byte
   * is written: once bytes have been written the body pipeline is fixed and this throws, because the gzip/deflate
   * streams write header bytes during construction and cannot be installed retroactively.
   *
   * @param compress {@code true} to write the body compressed when the client accepts a supported encoding.
   * @throws IllegalStateException if called after the response has been committed.
   */
  public void setCompress(boolean compress) {
    if (committed) {
      throw new IllegalStateException("The HTTPResponse compression configuration cannot be modified once bytes have been written to it.");
    }

    this.compress = compress;
  }

  /**
   * Resets this stream so the response can be regenerated, provided nothing has been written back to the client yet.
   * Clears the committed state, discards the body pipeline, and turns compression off. {@code suppressBody} (HEAD
   * handling) is intentionally preserved so HEAD error responses continue to suppress the body.
   *
   * @throws IllegalStateException if the response has already been committed (a byte has reached the client).
   */
  public void reset() {
    if (protocol.wroteToClient()) {
      throw new IllegalStateException("The HTTPOutputStream can't be reset after it has been committed, meaning at least one byte was written back to the client.");
    }

    bodySuppressed = false;
    closed = false;
    committed = false;
    compress = false;
    delegate = null;
    protocol.reset();
    // suppressBody is intentionally preserved across reset() so HEAD error responses keep suppressing the body.
  }

  /**
   * Enables or disables body-byte suppression for this response. When enabled, the preamble (or HTTP/2 HEADERS) is still
   * written normally — identical to a GET response, so the framing headers match — but any subsequent {@link #write}
   * calls become no-ops and the chunked/gzip/deflate delegates are not installed. Intended for HEAD request handling.
   *
   * @param suppressBody {@code true} to drop all body output.
   * @throws IllegalStateException if called after the response has been committed.
   */
  public void setSuppressBody(boolean suppressBody) {
    if (committed) {
      throw new IllegalStateException("The HTTPResponse body suppression cannot be modified once bytes have been written to it.");
    }

    this.suppressBody = suppressBody;
  }

  /**
   * Indicates whether the body will actually be compressed when written. Unlike {@link #isCompress()}, which reports
   * only the configured intent, this also accounts for whether the client's accepted encodings include an encoding the
   * server supports (gzip or deflate).
   *
   * @return {@code true} if compression has been requested and a supported content encoding was offered by the client.
   */
  public boolean willCompress() {
    if (compress) {
      for (String encoding : acceptEncodings) {
        if (encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Gzip) || encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Deflate)) {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public void write(byte[] buffer, int offset, int length) throws IOException {
    commit(false);

    if (bodySuppressed) {
      return;
    }

    delegate.write(buffer, offset, length);

    if (instrumenter != null) {
      instrumenter.wroteToClient(length);
    }
  }

  @Override
  public void write(int b) throws IOException {
    commit(false);

    if (bodySuppressed) {
      return;
    }

    delegate.write(b);

    if (instrumenter != null) {
      instrumenter.wroteToClient(1);
    }
  }

  @Override
  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  /**
   * Latently builds the body pipeline on the first write or on close. Applies RFC body-suppression, decides
   * compression (the only protocol-agnostic body transform), then hands header emission + framing to the protocol and
   * wraps the returned sink with gzip/deflate when compressing.
   */
  private void commit(boolean closing) throws IOException {
    if (committed) {
      return;
    }

    committed = true;

    int status = response.getStatus();
    boolean noBodyStatus = status == 204 || status == 304;
    bodySuppressed = suppressBody || noBodyStatus;

    // framingBody: headers are computed as if a body follows (true for a writing GET and for HEAD mirroring GET).
    boolean framingBody = !noBodyStatus && !closing;
    // emitBytes: real body bytes will be written, so a compression delegate is installed.
    boolean emitBytes = framingBody && !suppressBody;

    boolean gzip = false;
    boolean deflate = false;
    if (framingBody && compress) {
      for (String encoding : acceptEncodings) {
        if (encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Gzip)) {
          response.setHeader(HTTPValues.Headers.ContentEncoding, HTTPValues.ContentEncodings.Gzip);
          response.setHeader(HTTPValues.Headers.Vary, HTTPValues.Headers.AcceptEncoding);
          response.removeHeader(HTTPValues.Headers.ContentLength);
          gzip = true;
          break;
        } else if (encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Deflate)) {
          response.setHeader(HTTPValues.Headers.ContentEncoding, HTTPValues.ContentEncodings.Deflate);
          response.setHeader(HTTPValues.Headers.Vary, HTTPValues.Headers.AcceptEncoding);
          response.removeHeader(HTTPValues.Headers.ContentLength);
          deflate = true;
          break;
        }
      }
    }

    OutputStream sink = protocol.commitHeaders(closing, suppressBody);

    if (!emitBytes) {
      delegate = sink;
      return;
    }

    if (gzip) {
      try {
        delegate = new GZIPOutputStream(sink, true);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else if (deflate) {
      delegate = new DeflaterOutputStream(sink, true);
    } else {
      delegate = sink;
    }
  }
}

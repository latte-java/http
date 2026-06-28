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
 * The primary output stream for the HTTP server (currently supporting version 1.1). This handles delegating to
 * compression and chunking output streams, depending on the response headers the application set.
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

  public boolean isCommitted() {
    return protocol.wroteToClient();
  }

  public boolean isCompress() {
    return compress;
  }

  public void setCompress(boolean compress) {
    if (committed) {
      throw new IllegalStateException("The HTTPResponse compression configuration cannot be modified once bytes have been written to it.");
    }

    this.compress = compress;
  }

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

  public void setSuppressBody(boolean suppressBody) {
    if (committed) {
      throw new IllegalStateException("The HTTPResponse body suppression cannot be modified once bytes have been written to it.");
    }

    this.suppressBody = suppressBody;
  }

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

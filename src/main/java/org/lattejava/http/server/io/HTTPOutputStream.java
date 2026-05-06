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
  private final HTTPBuffers buffers;
  private final Instrumenter instrumenter;
  private final HTTPRequest request;
  private final HTTPResponse response;
  private final ServerToSocketOutputStream serverToSocket;
  private boolean bodySuppressed;
  private boolean committed;
  private boolean compress;
  private OutputStream delegate;
  private boolean suppressBody;
  private boolean wroteOneByteToClient;

  public HTTPOutputStream(HTTPServerConfiguration configuration, HTTPRequest request, List<String> acceptEncodings, HTTPResponse response, OutputStream delegate,
                          HTTPBuffers buffers, Runnable writeObserver) {
    this.acceptEncodings = acceptEncodings;
    this.buffers = buffers;
    this.compress = configuration.isCompressByDefault();
    this.instrumenter = configuration.getInstrumenter();
    this.request = request;
    this.response = response;
    this.serverToSocket = new ServerToSocketOutputStream(delegate, buffers, writeObserver);
    this.delegate = serverToSocket;
  }

  @Override
  public void close() throws IOException {
    commit(true);
    delegate.close();
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
  }

  /**
   * Calls the {@link ServerToSocketOutputStream#forceFlush()} method to write all buffered bytes to the socket. This
   * also writes the preamble to the buffer or to the socket if it hasn't been written yet.
   *
   * @throws IOException If the socket throws.
   */
  public void forceFlush() throws IOException {
    commit(false);
    delegate.flush();
    serverToSocket.forceFlush();
  }

  /**
   * @return True if at least one byte was written back to the client. False if the response has not been generated or
   *     is sitting in the response buffer.
   */
  public boolean isCommitted() {
    return wroteOneByteToClient;
  }

  public boolean isCompress() {
    return compress;
  }

  public void setCompress(boolean compress) {
    // Too late, once you write bytes, we can no longer change the OutputStream configuration.
    if (committed) {
      throw new IllegalStateException("The HTTPResponse compression configuration cannot be modified once bytes have been written to it.");
    }

    this.compress = compress;
  }

  public void reset() {
    if (wroteOneByteToClient) {
      throw new IllegalStateException("The HTTPOutputStream can't be reset after it has been committed, meaning at least one byte was written back to the client.");
    }

    bodySuppressed = false;
    serverToSocket.reset();
    committed = false;
    compress = false;
    delegate = serverToSocket;
    // suppressBody is intentionally preserved across reset() so that HEAD error responses (triggered via response.reset() in closeSocketOnError) continue to suppress the body. HTTPOutputStream is constructed fresh per connection iteration in HTTPWorker, so there is no cross-request bleed.
  }

  /**
   * Enables or disables body-byte suppression for this response. When enabled, the preamble is still written normally
   * (identical to a GET response), but any subsequent {@link #write} calls become no-ops and the chunked/gzip/deflate
   * delegates are not installed. Intended for HEAD request handling.
   *
   * @param suppressBody true to drop all body output.
   */
  public void setSuppressBody(boolean suppressBody) {
    if (committed) {
      throw new IllegalStateException("The HTTPResponse body suppression cannot be modified once bytes have been written to it.");
    }

    this.suppressBody = suppressBody;
  }

  /**
   * @return true if compression has been requested, and it appears as though we will compress because the requested
   *     content encoding is supported.
   */
  public boolean willCompress() {
    if (compress) {
      for (String encoding : acceptEncodings) {
        if (encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Gzip)) {
          return true;
        } else if (encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Deflate)) {
          return true;
        }
      }

      return false;
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
   * Initialize the actual OutputStream latent so that we can call setCompress more than once. The GZIPOutputStream
   * writes bytes to the OutputStream during construction which means we cannot build it more than once. This is why we
   * must wait until we know for certain we are going to write bytes to construct the compressing OutputStream.
   */
  private void commit(boolean closing) throws IOException {
    if (committed) {
      return;
    }

    committed = true;

    int status = response.getStatus();
    boolean noBodyStatus = status == 204 || status == 304;

    // HEAD always suppresses body bytes; 204 and 304 must never carry a body per RFC 9110.
    bodySuppressed = suppressBody || noBodyStatus;

    boolean gzip = false;
    boolean deflate = false;
    boolean chunked = false;

    if (noBodyStatus) {
      // 204/304 must not carry Content-Length, Transfer-Encoding, or a body (RFC 9110 §15.3.5 / §15.4.5 / RFC 7230 §3.3.2).
      response.removeHeader(HTTPValues.Headers.ContentLength);
      response.removeHeader(HTTPValues.Headers.TransferEncoding);
    } else {
      // RFC 7230 §3.3.3: a sender must not send Content-Length in a message that also has Transfer-Encoding. TE wins.
      boolean handlerSetTransferEncoding = response.getHeader(HTTPValues.Headers.TransferEncoding) != null;
      if (handlerSetTransferEncoding) {
        response.removeHeader(HTTPValues.Headers.ContentLength);
      }

      if (closing) {
        // Handler wrote no bytes.
        if (suppressBody) {
          // HEAD: preserve the handler's framing so CDN-style handlers can report a synthetic Content-Length or indicate chunked framing
          // without actually generating a body. Only default to Content-Length: 0 when the handler set nothing.
          if (response.getContentLength() == null && !handlerSetTransferEncoding) {
            response.setContentLength(0L);
          }
        } else {
          // GET defensive override: the handler declared framing but produced no bytes. Strip any Transfer-Encoding (no chunks were sent)
          // and force Content-Length: 0 so the client does not wait for a body.
          if (handlerSetTransferEncoding) {
            response.removeHeader(HTTPValues.Headers.TransferEncoding);
          }
          response.setContentLength(0L);
        }
      } else {
        // Handler is writing bytes.
        if (compress) {
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

        // If the handler set response trailers, force chunked framing (only chunked supports trailers in HTTP/1.1).
        if (response.hasTrailers()) {
          if (response.getContentLength() != null) {
            response.removeHeader(HTTPValues.Headers.ContentLength);
          }
          response.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
          chunked = true;
          // Auto-populate the Trailer response header listing the trailer field names per RFC 9110 §6.5.
          // Gate on TE: trailers — clients that did not signal acceptance must not receive trailers.
          if (request != null && request.acceptsTrailers()) {
            String list = String.join(", ", response.getTrailers().keySet());
            response.setHeader(HTTPValues.Headers.Trailer, list);
          }
        } else if (handlerSetTransferEncoding) {
          // Handler asked for chunked framing explicitly. Wrap the delegate so the bytes are actually chunk-framed on the wire.
          chunked = true;
        } else if (response.getContentLength() == null) {
          response.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
          chunked = true;
        }
      }
    }

    // Write the preamble to the socket.
    HTTPTools.writeResponsePreamble(response, delegate);

    // Bail if no body bytes will follow.
    if (bodySuppressed || closing) {
      return;
    }

    // Install body delegate(s).
    if (chunked) {
      ChunkedOutputStream cos = new ChunkedOutputStream(delegate, buffers.chunkBuffer(), buffers.chuckedOutputStream());
      if (response.hasTrailers() && request != null && request.acceptsTrailers()) {
        cos.setTrailers(response.getTrailers());
      }
      delegate = cos;
      if (instrumenter != null) {
        instrumenter.chunkedResponse();
      }
    }

    if (gzip) {
      try {
        delegate = new GZIPOutputStream(delegate, true);
        response.setHeader(HTTPValues.Headers.ContentEncoding, HTTPValues.ContentEncodings.Gzip);
        response.setHeader(HTTPValues.Headers.Vary, HTTPValues.Headers.AcceptEncoding);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    } else if (deflate) {
      delegate = new DeflaterOutputStream(delegate, true);
    }
  }

  /**
   * This OutputStream handles all the complexity of buffering the response, managing calls to {@link #close()}, etc.
   *
   * @author Brian Pontarelli
   */
  private class ServerToSocketOutputStream extends OutputStream {
    private final byte[] buffer;

    private final OutputStream delegate;

    private final byte[] intsAreDumb = new byte[1];

    private final Runnable writeObserver;

    private int bufferIndex;

    public ServerToSocketOutputStream(OutputStream delegate, HTTPBuffers buffers, Runnable writeObserver) {
      this.delegate = delegate;
      this.buffer = buffers.responseBuffer();
      this.bufferIndex = 0;
      this.writeObserver = writeObserver;
    }

    /**
     * Flushes the buffer but does not close the delegate.
     *
     * @throws IOException If the flush fails.
     */
    @Override
    public void close() throws IOException {
      forceFlush();
    }

    /**
     * Only flushes if the buffer is 90+% full.
     *
     * @throws IOException If the write and flush to the delegate stream throws.
     */
    @Override
    public void flush() throws IOException {
      if (buffer == null || bufferIndex >= (buffer.length * 0.90)) {
        forceFlush();
      }
    }

    public void forceFlush() throws IOException {
      if (buffer == null || bufferIndex == 0) {
        return;
      }

      wroteOneByteToClient = true;
      delegate.write(buffer, 0, bufferIndex);
      delegate.flush();
      bufferIndex = 0;
    }

    /**
     * Resets the ServerToSocketOutputStream by resetting the buffer location to 0. This only applies if the response
     * buffer is in use.
     */
    public void reset() {
      bufferIndex = 0;
    }

    @Override
    public void write(byte[] b, int offset, int length) throws IOException {
      writeObserver.run();

      if (buffer == null) {
        delegate.write(b, offset, length);
      } else {
        do {
          int remaining = buffer.length - bufferIndex;
          int toWrite = Math.min(remaining, length);
          System.arraycopy(b, offset, buffer, bufferIndex, toWrite);
          bufferIndex += toWrite;
          offset += toWrite;
          length -= toWrite;

          if (bufferIndex >= buffer.length) {
            forceFlush();
          }
        } while (length > 0);
      }
    }

    @Override
    public void write(int b) throws IOException {
      intsAreDumb[0] = (byte) b;
      write(intsAreDumb, 0, 1);
    }

    @Override
    public void write(byte[] b) throws IOException {
      write(b, 0, b.length);
    }
  }
}

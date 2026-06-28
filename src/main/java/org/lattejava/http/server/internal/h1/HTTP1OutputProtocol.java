/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h1;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.io.ChunkedOutputStream;
import org.lattejava.http.server.internal.*;

/**
 * HTTP/1.1 implementation of {@link HTTPOutputProtocol}. Writes the text response preamble, decides Content-Length vs.
 * chunked Transfer-Encoding framing, buffers body bytes to the socket, and emits the chunk terminator and any trailers.
 *
 * @author Brian Pontarelli
 */
public class HTTP1OutputProtocol implements HTTPOutputProtocol {
  private final HTTPBuffers buffers;
  private final Instrumenter instrumenter;
  private final HTTPRequest request;
  private final HTTPResponse response;
  private final ServerToSocketOutputStream serverToSocket;
  private ChunkedOutputStream chunkedSink;
  private boolean wroteToClient;

  public HTTP1OutputProtocol(HTTPRequest request, HTTPResponse response, OutputStream socketOut, HTTPBuffers buffers,
                             Instrumenter instrumenter, Runnable writeObserver) {
    this.request = request;
    this.response = response;
    this.buffers = buffers;
    this.instrumenter = instrumenter;
    this.serverToSocket = new ServerToSocketOutputStream(socketOut, buffers, writeObserver);
  }

  @Override
  public OutputStream commitHeaders(boolean closing, boolean suppressBody) throws IOException {
    int status = response.getStatus();
    boolean noBodyStatus = status == 204 || status == 304;
    // framingBody: compute Content-Length/Transfer-Encoding as if a body follows. True for a writing GET AND for HEAD
    // (which must mirror GET headers). False for 204/304 and a body-less close().
    boolean framingBody = !noBodyStatus && !closing;
    // emitBytes: real body bytes will be written, so a real chunked sink is installed. HEAD computes framing but emits
    // no bytes, so it must NOT install a ChunkedOutputStream (else commitTrailers would write a stray terminator).
    boolean emitBytes = framingBody && !suppressBody;
    boolean chunked = false;

    if (noBodyStatus) {
      // 204/304 must not carry Content-Length, Transfer-Encoding, or a body (RFC 9110 §15.3.5 / §15.4.5).
      response.removeHeader(HTTPValues.Headers.ContentLength);
      response.removeHeader(HTTPValues.Headers.TransferEncoding);
    } else {
      // RFC 7230 §3.3.3: a sender must not send Content-Length with Transfer-Encoding. TE wins.
      boolean handlerSetTransferEncoding = response.getHeader(HTTPValues.Headers.TransferEncoding) != null;
      if (handlerSetTransferEncoding) {
        response.removeHeader(HTTPValues.Headers.ContentLength);
      }

      if (!framingBody) {
        // close() with no bytes written.
        if (suppressBody) {
          // HEAD: preserve handler framing; only default to Content-Length: 0 when nothing was set.
          if (response.getContentLength() == null && !handlerSetTransferEncoding) {
            response.setContentLength(0L);
          }
        } else {
          // GET that produced no bytes: strip any declared Transfer-Encoding and force Content-Length: 0.
          if (handlerSetTransferEncoding) {
            response.removeHeader(HTTPValues.Headers.TransferEncoding);
          }
          response.setContentLength(0L);
        }
      } else {
        // Body framing (compression already applied by HTTPOutputStream: Content-Encoding/Vary set, Content-Length
        // removed when compressing).
        if (response.hasTrailers()) {
          if (response.getContentLength() != null) {
            response.removeHeader(HTTPValues.Headers.ContentLength);
          }
          response.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
          chunked = true;
          // RFC 9110 §6.5: announce trailer names, gated on the client accepting trailers (TE: trailers).
          if (request != null && request.acceptsTrailers()) {
            response.setHeader(HTTPValues.Headers.Trailer, String.join(", ", response.getTrailers().keySet()));
          }
        } else if (handlerSetTransferEncoding) {
          chunked = true;
        } else if (response.getContentLength() == null) {
          response.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
          chunked = true;
        }
      }
    }

    HTTPTools.writeResponsePreamble(response, serverToSocket);

    if (!emitBytes) {
      // HEAD / 204 / 304 / body-less close: framing headers are written; no body sink is used.
      return serverToSocket;
    }

    if (chunked) {
      chunkedSink = new ChunkedOutputStream(serverToSocket, buffers.chunkBuffer(), buffers.chuckedOutputStream());
      if (instrumenter != null) {
        instrumenter.chunkedResponse();
      }
      return chunkedSink;
    }

    return serverToSocket;
  }

  @Override
  public void commitTrailers() throws IOException {
    // Only chunked responses carry a chunk terminator. HEAD (no chunkedSink) and Content-Length responses skip this.
    if (chunkedSink == null) {
      return;
    }

    Map<String, List<String>> trailers = response.getTrailers();
    // Gate trailer emission on the client having signaled TE: trailers. Without that, write the bare terminator even
    // when trailers are present (the handler set trailers but the client cannot accept them).
    boolean emitTrailers = !trailers.isEmpty() && request != null && request.acceptsTrailers();
    if (!emitTrailers) {
      serverToSocket.write(HTTPValues.ControlBytes.ChunkedTerminator);
    } else {
      serverToSocket.write(HTTPValues.ControlBytes.EmptyChunk);
      for (var entry : trailers.entrySet()) {
        for (String value : entry.getValue()) {
          serverToSocket.write(entry.getKey().getBytes(StandardCharsets.US_ASCII));
          serverToSocket.write(HTTPValues.ControlBytes.ColonSpace);
          serverToSocket.write(value.getBytes(StandardCharsets.US_ASCII));
          serverToSocket.write(HTTPValues.ControlBytes.CRLF);
        }
      }
      serverToSocket.write(HTTPValues.ControlBytes.CRLF);
    }
  }

  @Override
  public void forceFlush() throws IOException {
    serverToSocket.forceFlush();
  }

  @Override
  public void prepareTrailers() {
    // HTTP/1.1 appends trailers after the last-chunk marker, so there is nothing to do before the body close.
  }

  @Override
  public void reset() {
    serverToSocket.reset();
    chunkedSink = null;
  }

  @Override
  public boolean wroteToClient() {
    return wroteToClient;
  }

  /**
   * Buffers body and preamble bytes and writes them to the socket. Sets {@link #wroteToClient} the first time bytes
   * actually reach the socket, which gates response reset. Moved verbatim from the former inner class of
   * {@code HTTPOutputStream}.
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

    @Override
    public void close() throws IOException {
      forceFlush();
    }

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

      wroteToClient = true;
      delegate.write(buffer, 0, bufferIndex);
      delegate.flush();
      bufferIndex = 0;
    }

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

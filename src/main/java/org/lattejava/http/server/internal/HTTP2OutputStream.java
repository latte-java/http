/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * Per-stream output. Buffers writes locally; on flush/close, fragments against the peer-negotiated MAX_FRAME_SIZE and enqueues DATA frames to the connection writer queue. Blocks on the stream's send-window when out of credits; the connection reader thread signals via the per-stream monitor on WINDOW_UPDATE.
 *
 * @author Daniel DeGroff
 */
public class HTTP2OutputStream extends OutputStream {
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
  private final int peerMaxFrameSize;
  private final HTTP2Stream stream;
  private final BlockingQueue<HTTP2Frame> writerQueue;

  private boolean closed;

  public HTTP2OutputStream(HTTP2Stream stream, BlockingQueue<HTTP2Frame> writerQueue, int peerMaxFrameSize) {
    this.stream = stream;
    this.writerQueue = writerQueue;
    this.peerMaxFrameSize = peerMaxFrameSize;
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    flushAndFragment(/*endStream=*/true);
  }

  @Override
  public void flush() throws IOException {
    flushAndFragment(/*endStream=*/false);
  }

  @Override
  public void write(int b) throws IOException {
    buffer.write(b);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    buffer.write(b, off, len);
  }

  private void flushAndFragment(boolean endStream) throws IOException {
    byte[] all = buffer.toByteArray();
    buffer.reset();
    int off = 0;
    while (off < all.length) {
      int chunk = Math.min(peerMaxFrameSize, all.length - off);
      // Block on flow-control if needed. Signed comparison: window may be negative after SETTINGS-induced decrease.
      while (stream.sendWindow() < chunk) {
        try {
          synchronized (stream) {
            stream.wait(100);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException();
        }
      }
      stream.consumeSendWindow(chunk);
      byte[] piece = new byte[chunk];
      System.arraycopy(all, off, piece, 0, chunk);
      off += chunk;
      boolean last = (off >= all.length) && endStream;
      try {
        writerQueue.put(new HTTP2Frame.DataFrame(stream.streamId(), last ? HTTP2Frame.FLAG_END_STREAM : 0, piece));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }
    }
    // If endStream and the buffer was empty, still emit a zero-length DATA frame with END_STREAM.
    if (endStream && all.length == 0) {
      try {
        writerQueue.put(new HTTP2Frame.DataFrame(stream.streamId(), HTTP2Frame.FLAG_END_STREAM, new byte[0]));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }
    }
  }
}

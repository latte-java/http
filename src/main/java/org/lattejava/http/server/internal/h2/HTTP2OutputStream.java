/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;

/**
 * Per-stream output. Buffers writes locally; on flush/close, fragments against the peer-negotiated MAX_FRAME_SIZE and
 * enqueues DATA frames to the connection writer queue. Blocks on the stream's send-window when out of credits; the
 * connection reader thread signals via the send-window's monitor on WINDOW_UPDATE.
 *
 * <p>When the response carries trailers, the caller must invoke {@link #setTrailersFollow(boolean)} with {@code true}
 * before calling {@link #close()}. This causes the final DATA frame to omit END_STREAM so that the subsequent HEADERS
 * (trailers) frame can carry it instead, as required by RFC 9113 §8.1.
 *
 * @author Daniel DeGroff
 */
public class HTTP2OutputStream extends OutputStream {
  private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

  private final HTTP2Window connectionWindow;

  private final int peerMaxFrameSize;

  private final HTTP2Stream stream;

  private final HTTP2WriterThread writer;

  private boolean closed;

  private boolean trailersFollow;

  /**
   * Test/standalone constructor with no connection-level flow control. Uses an effectively unbounded connection window,
   * so only the per-stream window throttles output. Production code must use the
   * {@link #HTTP2OutputStream(HTTP2Stream, HTTP2WriterThread, HTTP2Window, int)} overload.
   */
  public HTTP2OutputStream(HTTP2Stream stream, HTTP2WriterThread writer, int peerMaxFrameSize) {
    this(stream, writer, new HTTP2Window(Integer.MAX_VALUE), peerMaxFrameSize);
  }

  public HTTP2OutputStream(HTTP2Stream stream, HTTP2WriterThread writer, HTTP2Window connectionWindow, int peerMaxFrameSize) {
    this.stream = stream;
    this.writer = writer;
    this.connectionWindow = connectionWindow;
    this.peerMaxFrameSize = peerMaxFrameSize;
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    // When trailers follow, omit END_STREAM from the final DATA frame; the caller will send a
    // HEADERS (trailers) frame with END_STREAM instead (RFC 9113 §8.1).
    flushAndFragment(/*endStream=*/!trailersFollow);
  }

  @Override
  public void flush() throws IOException {
    flushAndFragment(/*endStream=*/false);
  }

  /**
   * Sets whether a HEADERS frame carrying trailers will follow this DATA stream. When {@code true}, the final DATA
   * frame written by {@link #close()} will not carry END_STREAM, leaving the caller responsible for sending a HEADERS
   * (trailers) frame with END_STREAM.
   *
   * @param trailersFollow {@code true} if a trailers HEADERS frame will follow.
   */
  public void setTrailersFollow(boolean trailersFollow) {
    this.trailersFollow = trailersFollow;
  }

  @Override
  public void write(int b) throws IOException {
    buffer.write(b);
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    buffer.write(b, off, len);
  }

  private void enqueue(HTTP2Frame.DataFrame frame) throws InterruptedIOException {
    writer.enqueueBlocking(frame);
  }

  private void flushAndFragment(boolean endStream) throws IOException {
    int size = buffer.size();
    // Fast path: the buffered payload fits in a single DATA frame AND the full credit is available right now in BOTH
    // the stream and connection windows. The all-or-nothing tryAcquire calls make each check+consume one atomic step,
    // so a concurrent SETTINGS-induced window decrease (RFC 9113 §6.9.2) cannot wedge between them and force a
    // spurious underflow. Avoids the byte[]-per-chunk copy in the loop below; hot for streaming handlers that
    // write+flush in chunks already sized to a single frame.
    if (size > 0 && size <= peerMaxFrameSize && stream.sendWindow().tryAcquire(size)) {
      if (connectionWindow.tryAcquire(size)) {
        byte[] piece = buffer.toByteArray();
        buffer.reset();
        if (endStream) {
          stream.applySendEndStream(HTTP2Stream.Event.SEND_DATA_END_STREAM);
        }
        enqueue(new HTTP2Frame.DataFrame(stream.streamId(), endStream ? HTTP2Frame.FLAG_END_STREAM : 0, piece));
        return;
      }

      // Connection window can't cover the whole frame right now; return the stream credit and fall to the slow path.
      stream.sendWindow().increment(size);
    }

    byte[] all = buffer.toByteArray();
    buffer.reset();
    int off = 0;
    while (off < all.length) {
      // RFC 9113 §6.9.1: outbound DATA must fit within BOTH the stream and connection send windows, so each frame is
      // capped at min(streamWindow, connectionWindow, maxFrameSize, remaining). Acquire stream credit first (waiting
      // on this stream's send-window monitor blocks no other stream), then connection credit for that grant. Each acquire is an
      // atomic wait+consume, closing the lost-wakeup (WINDOW_UPDATE notify racing an unlocked read+wait; fixed in
      // commit 2829cc4) and the consume-underflow race. If the connection window is the tighter bound, return the
      // surplus stream credit so accounting stays exact.
      int want = Math.min(peerMaxFrameSize, all.length - off);
      int streamGrant;
      try {
        streamGrant = stream.sendWindow().acquire(want, 100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }

      int chunk;
      try {
        chunk = connectionWindow.acquire(streamGrant, 100);
      } catch (InterruptedException e) {
        stream.sendWindow().increment(streamGrant);
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }

      if (chunk < streamGrant) {
        stream.sendWindow().increment(streamGrant - chunk);
      }

      byte[] piece = new byte[chunk];
      System.arraycopy(all, off, piece, 0, chunk);
      off += chunk;
      boolean last = (off >= all.length) && endStream;
      if (last) {
        stream.applySendEndStream(HTTP2Stream.Event.SEND_DATA_END_STREAM);
      }
      enqueue(new HTTP2Frame.DataFrame(stream.streamId(), last ? HTTP2Frame.FLAG_END_STREAM : 0, piece));
    }

    // If endStream and the buffer was empty, still emit a zero-length DATA frame with END_STREAM.
    if (endStream && all.length == 0) {
      stream.applySendEndStream(HTTP2Stream.Event.SEND_DATA_END_STREAM);
      enqueue(new HTTP2Frame.DataFrame(stream.streamId(), HTTP2Frame.FLAG_END_STREAM, new byte[0]));
    }
  }
}

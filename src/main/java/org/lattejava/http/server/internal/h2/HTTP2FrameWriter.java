/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;

import static org.lattejava.http.server.internal.h2.HTTP2Frame.*;

/**
 * Writes HTTP/2 frames to an OutputStream. Owns the frame-write buffer (passed in by the caller, sized to 9 +
 * MAX_FRAME_SIZE). Single-threaded — instance must not be shared across threads.
 *
 * @author Daniel DeGroff
 */
public class HTTP2FrameWriter {
  private final byte[] buffer;

  private final OutputStream out;

  public HTTP2FrameWriter(OutputStream out, byte[] buffer) {
    this.out = out;
    this.buffer = buffer;
  }

  private static void writeInt32(byte[] dst, int off, int v) {
    dst[off] = (byte) ((v >> 24) & 0xFF);
    dst[off + 1] = (byte) ((v >> 16) & 0xFF);
    dst[off + 2] = (byte) ((v >> 8) & 0xFF);
    dst[off + 3] = (byte) (v & 0xFF);
  }

  /**
   * Flushes the underlying OutputStream.
   *
   * @throws IOException If the OutputStream throws an IOException.
   */
  public void flush() throws IOException {
    out.flush();
  }

  public void writeFrame(HTTP2Frame frame) throws IOException {
    switch (frame) {
      case ContinuationFrame f ->
          writeWithPayload(FRAME_TYPE_CONTINUATION, f.flags(), f.streamId(), f.headerBlockFragment());
      case DataFrame f -> writeWithPayload(FRAME_TYPE_DATA, f.flags(), f.streamId(), f.payload());
      case GoawayFrame f -> {
        byte[] payload = new byte[8 + f.debugData().length];
        writeInt32(payload, 0, f.lastStreamId() & 0x7FFFFFFF);
        writeInt32(payload, 4, f.errorCode());
        System.arraycopy(f.debugData(), 0, payload, 8, f.debugData().length);
        writeWithPayload(FRAME_TYPE_GOAWAY, 0, 0, payload);
      }
      case HeadersFrame f -> writeHeaderBlock(FRAME_TYPE_HEADERS, f.flags(), f.streamId(), f.headerBlockFragment());
      case PingFrame f -> writeWithPayload(FRAME_TYPE_PING, f.flags(), 0, f.opaqueData());
      case PriorityFrame f -> writeWithPayload(FRAME_TYPE_PRIORITY, 0, f.streamId(), new byte[5]);
      case PushPromiseFrame f -> {
        byte[] payload = new byte[4 + f.headerBlockFragment().length];
        writeInt32(payload, 0, f.promisedStreamId() & 0x7FFFFFFF);
        System.arraycopy(f.headerBlockFragment(), 0, payload, 4, f.headerBlockFragment().length);
        writeWithPayload(FRAME_TYPE_PUSH_PROMISE, f.flags(), f.streamId(), payload);
      }
      case RSTStreamFrame f -> writeFixedFourByte(FRAME_TYPE_RST_STREAM, 0, f.streamId(), f.errorCode());
      case SettingsFrame f -> writeWithPayload(FRAME_TYPE_SETTINGS, f.flags(), 0, f.payload());
      case UnknownFrame f -> writeWithPayload(f.type(), f.flags(), f.streamId(), f.payload());
      case WindowUpdateFrame f ->
          writeFixedFourByte(FRAME_TYPE_WINDOW_UPDATE, 0, f.streamId(), f.windowSizeIncrement() & 0x7FFFFFFF);
    }
  }

  // Writes a 4-byte fixed-length frame (RST_STREAM, WINDOW_UPDATE) directly into the shared buffer
  // without allocating a payload byte[]. This is the hottest write path — every DATA frame received
  // triggers a WINDOW_UPDATE — so keeping it allocation-free matters.
  private void writeFixedFourByte(int type, int flags, int streamId, int value) throws IOException {
    buffer[0] = 0;
    buffer[1] = 0;
    buffer[2] = 4;
    buffer[3] = (byte) type;
    buffer[4] = (byte) flags;
    writeInt32(buffer, 5, streamId & 0x7FFFFFFF);
    writeInt32(buffer, 9, value);
    out.write(buffer, 0, 13);
  }

  // Writes a single wire frame using a slice of {@code src} as the payload, without an intermediate copy.
  private void writeFromBlock(int type, int flags, int streamId, byte[] src, int srcOff, int len) throws IOException {
    buffer[0] = (byte) ((len >> 16) & 0xFF);
    buffer[1] = (byte) ((len >> 8) & 0xFF);
    buffer[2] = (byte) (len & 0xFF);
    buffer[3] = (byte) type;
    buffer[4] = (byte) flags;
    writeInt32(buffer, 5, streamId & 0x7FFFFFFF);
    System.arraycopy(src, srcOff, buffer, 9, len);
    out.write(buffer, 0, 9 + len);
  }

  /**
   * Writes a header block as a single HEADERS (or PUSH_PROMISE) frame when it fits, or as one HEADERS frame followed by
   * one or more CONTINUATION frames when it exceeds the negotiated MAX_FRAME_SIZE (RFC 9113 §4.3, §6.10). END_HEADERS
   * is set on the final wire frame regardless of fragmentation. Caller flags other than END_HEADERS (e.g. END_STREAM)
   * ride on the first frame so the receiver applies them to the stream as a whole.
   */
  private void writeHeaderBlock(int firstFrameType, int callerFlags, int streamId, byte[] block) throws IOException {
    int maxPayload = buffer.length - 9;
    if (block.length <= maxPayload) {
      writeFromBlock(firstFrameType, callerFlags | FLAG_END_HEADERS, streamId, block, 0, block.length);
      return;
    }
    writeFromBlock(firstFrameType, callerFlags & ~FLAG_END_HEADERS, streamId, block, 0, maxPayload);
    int off = maxPayload;
    while (off < block.length) {
      int chunkLen = Math.min(maxPayload, block.length - off);
      int flags = (off + chunkLen >= block.length) ? FLAG_END_HEADERS : 0;
      writeFromBlock(FRAME_TYPE_CONTINUATION, flags, streamId, block, off, chunkLen);
      off += chunkLen;
    }
  }

  private void writeWithPayload(int type, int flags, int streamId, byte[] payload) throws IOException {
    writeFromBlock(type, flags, streamId, payload, 0, payload.length);
  }
}

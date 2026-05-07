/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

import static org.lattejava.http.server.internal.HTTP2Frame.*;

/**
 * Writes HTTP/2 frames to an OutputStream. Owns the frame-write buffer (passed in by the caller, sized to 9 + MAX_FRAME_SIZE). Single-threaded — instance must not be shared across threads.
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

  public void writeFrame(HTTP2Frame frame) throws IOException {
    switch (frame) {
      case ContinuationFrame f -> writeWithPayload(FRAME_TYPE_CONTINUATION, f.flags(), f.streamId(), f.headerBlockFragment());
      case DataFrame f -> writeWithPayload(FRAME_TYPE_DATA, f.flags(), f.streamId(), f.payload());
      case GoawayFrame f -> {
        byte[] payload = new byte[8 + f.debugData().length];
        writeInt32(payload, 0, f.lastStreamId() & 0x7FFFFFFF);
        writeInt32(payload, 4, f.errorCode());
        System.arraycopy(f.debugData(), 0, payload, 8, f.debugData().length);
        writeWithPayload(FRAME_TYPE_GOAWAY, 0, 0, payload);
      }
      case HeadersFrame f -> writeWithPayload(FRAME_TYPE_HEADERS, f.flags(), f.streamId(), f.headerBlockFragment());
      case PingFrame f -> writeWithPayload(FRAME_TYPE_PING, f.flags(), 0, f.opaqueData());
      case PriorityFrame f -> writeWithPayload(FRAME_TYPE_PRIORITY, 0, f.streamId(), new byte[5]);
      case PushPromiseFrame f -> {
        byte[] payload = new byte[4 + f.headerBlockFragment().length];
        writeInt32(payload, 0, f.promisedStreamId() & 0x7FFFFFFF);
        System.arraycopy(f.headerBlockFragment(), 0, payload, 4, f.headerBlockFragment().length);
        writeWithPayload(FRAME_TYPE_PUSH_PROMISE, f.flags(), f.streamId(), payload);
      }
      case RSTStreamFrame f -> writeWithPayload(FRAME_TYPE_RST_STREAM, 0, f.streamId(), int32(f.errorCode()));
      case SettingsFrame f -> writeWithPayload(FRAME_TYPE_SETTINGS, f.flags(), 0, f.payload());
      case UnknownFrame f -> writeWithPayload(f.type(), f.flags(), f.streamId(), f.payload());
      case WindowUpdateFrame f -> writeWithPayload(FRAME_TYPE_WINDOW_UPDATE, 0, f.streamId(), int32(f.windowSizeIncrement() & 0x7FFFFFFF));
    }
  }

  private static byte[] int32(int v) {
    byte[] b = new byte[4];
    writeInt32(b, 0, v);
    return b;
  }

  private static void writeInt32(byte[] dst, int off, int v) {
    dst[off] = (byte) ((v >> 24) & 0xFF);
    dst[off + 1] = (byte) ((v >> 16) & 0xFF);
    dst[off + 2] = (byte) ((v >> 8) & 0xFF);
    dst[off + 3] = (byte) (v & 0xFF);
  }

  private void writeWithPayload(int type, int flags, int streamId, byte[] payload) throws IOException {
    int length = payload.length;
    buffer[0] = (byte) ((length >> 16) & 0xFF);
    buffer[1] = (byte) ((length >> 8) & 0xFF);
    buffer[2] = (byte) (length & 0xFF);
    buffer[3] = (byte) type;
    buffer[4] = (byte) flags;
    writeInt32(buffer, 5, streamId & 0x7FFFFFFF);
    System.arraycopy(payload, 0, buffer, 9, length);
    out.write(buffer, 0, 9 + length);
  }
}

/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

import static org.lattejava.http.server.internal.HTTP2Frame.*;

/**
 * Reads HTTP/2 frames from an InputStream. Owns the frame-read buffer (passed in by the caller, sized to MAX_FRAME_SIZE). Single-threaded — instance must not be shared across threads.
 *
 * @author Daniel DeGroff
 */
public class HTTP2FrameReader {
  private final byte[] buffer;
  private final InputStream in;

  public HTTP2FrameReader(InputStream in, byte[] buffer) {
    this.in = in;
    this.buffer = buffer;
  }

  public HTTP2Frame readFrame() throws IOException {
    if (in.readNBytes(buffer, 0, 9) != 9) {
      throw new EOFException("Connection closed before frame header");
    }

    int length = ((buffer[0] & 0xFF) << 16) | ((buffer[1] & 0xFF) << 8) | (buffer[2] & 0xFF);
    int type = buffer[3] & 0xFF;
    int flags = buffer[4] & 0xFF;
    int streamId = ((buffer[5] & 0x7F) << 24) | ((buffer[6] & 0xFF) << 16) | ((buffer[7] & 0xFF) << 8) | (buffer[8] & 0xFF);

    if (length > buffer.length) {
      throw new FrameSizeException("Frame length [" + length + "] exceeds buffer capacity [" + buffer.length + "]");
    }

    if (in.readNBytes(buffer, 0, length) != length) {
      throw new EOFException("Connection closed mid-frame; expected [" + length + "] bytes");
    }

    return switch (type) {
      case FRAME_TYPE_DATA -> {
        // RFC 9113 §6.1: DATA frames may be padded.
        if ((flags & FLAG_PADDED) != 0) {
          int padLen = buffer[0] & 0xFF;
          int dataLen = length - 1 - padLen;
          // RFC 9113 §6.1: "If the length of the padding is the length of the frame payload or greater, the
          // recipient MUST treat this as a connection error (Section 5.4.1) of type PROTOCOL_ERROR."
          if (dataLen < 0) {
            throw new ProtocolException("DATA pad length [" + padLen + "] exceeds frame payload length [" + length + "]");
          }
          yield new DataFrame(streamId, flags, copyOfRange(buffer, 1, 1 + dataLen));
        }
        yield new DataFrame(streamId, flags, copyOf(buffer, length));
      }
      case FRAME_TYPE_HEADERS -> {
        // RFC 9113 §6.2: HEADERS frame may have PADDED and/or PRIORITY prefix bytes before the fragment.
        int hdrOff = 0;
        int hdrEnd = length;
        if ((flags & FLAG_PADDED) != 0) {
          int padLen = buffer[hdrOff] & 0xFF;
          hdrOff++;
          hdrEnd -= padLen;
          // RFC 9113 §6.2: invalid pad length is a connection error PROTOCOL_ERROR.
          if (hdrEnd < hdrOff) {
            throw new ProtocolException("HEADERS pad length exceeds frame payload length [" + length + "]");
          }
        }
        if ((flags & FLAG_PRIORITY) != 0) {
          hdrOff += 5; // 4 bytes stream dependency + 1 byte weight
        }
        yield new HeadersFrame(streamId, flags, copyOfRange(buffer, hdrOff, Math.max(hdrOff, hdrEnd)));
      }
      case FRAME_TYPE_PRIORITY -> {
        if (length != 5) throw new FrameSizeException("PRIORITY payload must be 5; got [" + length + "]");
        // RFC 9113 §6.3: PRIORITY must have a non-zero stream ID.
        if (streamId == 0) throw new ProtocolException("PRIORITY frame with stream ID 0");
        yield new PriorityFrame(streamId);
      }
      case FRAME_TYPE_RST_STREAM -> {
        if (length != 4) throw new FrameSizeException("RST_STREAM payload must be 4; got [" + length + "]");
        // RFC 9113 §6.4: RST_STREAM must have a non-zero stream ID.
        if (streamId == 0) throw new ProtocolException("RST_STREAM frame with stream ID 0");
        int code = ((buffer[0] & 0xFF) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        yield new RSTStreamFrame(streamId, code);
      }
      case FRAME_TYPE_SETTINGS -> {
        // RFC 9113 §6.5: SETTINGS must have stream ID 0.
        if (streamId != 0) throw new ProtocolException("SETTINGS frame with non-zero stream ID [" + streamId + "]");
        if ((flags & FLAG_ACK) != 0 && length != 0) throw new FrameSizeException("SETTINGS ACK must have empty payload");
        if (length % 6 != 0) throw new FrameSizeException("SETTINGS payload length [" + length + "] not multiple of 6");
        yield new SettingsFrame(flags, copyOf(buffer, length));
      }
      case FRAME_TYPE_PUSH_PROMISE -> {
        int promised = ((buffer[0] & 0x7F) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        yield new PushPromiseFrame(streamId, flags, promised, copyOfRange(buffer, 4, length));
      }
      case FRAME_TYPE_PING -> {
        if (length != 8) throw new FrameSizeException("PING payload must be 8; got [" + length + "]");
        // RFC 9113 §6.7: PING must have stream ID 0.
        if (streamId != 0) throw new ProtocolException("PING frame with non-zero stream ID [" + streamId + "]");
        yield new PingFrame(flags, copyOf(buffer, 8));
      }
      case FRAME_TYPE_GOAWAY -> {
        if (length < 8) throw new FrameSizeException("GOAWAY payload must be >= 8; got [" + length + "]");
        // RFC 9113 §6.8: GOAWAY must have stream ID 0.
        if (streamId != 0) throw new ProtocolException("GOAWAY frame with non-zero stream ID [" + streamId + "]");
        int last = ((buffer[0] & 0x7F) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        int code = ((buffer[4] & 0xFF) << 24) | ((buffer[5] & 0xFF) << 16) | ((buffer[6] & 0xFF) << 8) | (buffer[7] & 0xFF);
        yield new GoawayFrame(last, code, copyOfRange(buffer, 8, length));
      }
      case FRAME_TYPE_WINDOW_UPDATE -> {
        if (length != 4) throw new FrameSizeException("WINDOW_UPDATE payload must be 4");
        int inc = ((buffer[0] & 0x7F) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        yield new WindowUpdateFrame(streamId, inc);
      }
      case FRAME_TYPE_CONTINUATION -> {
        // RFC 9113 §6.10: CONTINUATION must have a non-zero stream ID.
        if (streamId == 0) throw new ProtocolException("CONTINUATION frame with stream ID 0");
        yield new ContinuationFrame(streamId, flags, copyOf(buffer, length));
      }
      default -> new UnknownFrame(streamId, flags, type, copyOf(buffer, length));
    };
  }

  private static byte[] copyOf(byte[] src, int len) {
    byte[] dst = new byte[len];
    System.arraycopy(src, 0, dst, 0, len);
    return dst;
  }

  private static byte[] copyOfRange(byte[] src, int from, int to) {
    byte[] dst = new byte[to - from];
    System.arraycopy(src, from, dst, 0, to - from);
    return dst;
  }

  /**
   * Thrown when the frame reader detects a FRAME_SIZE_ERROR condition (RFC 9113 §6, §7).
   * The connection handler must respond with GOAWAY(FRAME_SIZE_ERROR).
   */
  public static class FrameSizeException extends IOException {
    public FrameSizeException(String message) {
      super(message);
    }
  }

  /**
   * Thrown when the frame reader detects a PROTOCOL_ERROR condition (RFC 9113 §5.4.1, §7).
   * The connection handler must respond with GOAWAY(PROTOCOL_ERROR).
   */
  public static class ProtocolException extends IOException {
    public ProtocolException(String message) {
      super(message);
    }
  }
}

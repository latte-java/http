/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

/**
 * Per-connection HTTP/2 settings (RFC 9113 §6.5.2). Mutable so a single instance can be reused as the peer changes its
 * settings mid-connection.
 *
 * @author Daniel DeGroff
 */
public class HTTP2Settings {
  private static final int SETTINGS_ENABLE_PUSH = 0x2;

  private static final int SETTINGS_HEADER_TABLE_SIZE = 0x1;

  private static final int SETTINGS_INITIAL_WINDOW_SIZE = 0x4;

  private static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x3;

  private static final int SETTINGS_MAX_FRAME_SIZE = 0x5;

  private static final int SETTINGS_MAX_HEADER_LIST_SIZE = 0x6;

  private static final int SETTING_LENGTH = 6; // bytes per setting entry: 2-byte id + 4-byte value

  private int enablePush = 1;

  private int headerTableSize = 4096;

  private int initialWindowSize = 65535;

  private int maxConcurrentStreams = 100;

  private int maxFrameSize = 16384;

  private int maxHeaderListSize = Integer.MAX_VALUE;

  public static HTTP2Settings defaults() {
    HTTP2Settings s = new HTTP2Settings();
    s.enablePush = 0; // server default = no push
    return s;
  }

  /**
   * Packs one setting entry (big-endian: 2-byte id, 4-byte value) into {@code buf} at offset {@code p} and returns the
   * offset of the next entry.
   */
  private static int writeSetting(byte[] buf, int p, int id, int value) {
    buf[p] = (byte) (id >> 8);
    buf[p + 1] = (byte) id;
    buf[p + 2] = (byte) (value >> 24);
    buf[p + 3] = (byte) (value >> 16);
    buf[p + 4] = (byte) (value >> 8);
    buf[p + 5] = (byte) value;
    return p + SETTING_LENGTH;
  }

  public void applyPayload(byte[] payload) {
    if (payload.length % SETTING_LENGTH != 0) {
      throw new HTTP2SettingsException("SETTINGS payload length [" + payload.length + "] is not a multiple of 6", HTTP2ErrorCode.FRAME_SIZE_ERROR);
    }

    for (int i = 0; i < payload.length; i += SETTING_LENGTH) {
      int id = ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
      int value = ((payload[i + 2] & 0xFF) << 24) | ((payload[i + 3] & 0xFF) << 16)
          | ((payload[i + 4] & 0xFF) << 8) | (payload[i + 5] & 0xFF);

      switch (id) {
        case SETTINGS_HEADER_TABLE_SIZE -> {
          // RFC 9113 §6.5.2: HEADER_TABLE_SIZE is unsigned 32-bit. Negative when read as signed int means the
          // value exceeds 2^31-1 — treat as PROTOCOL_ERROR rather than silently clamping or disabling the table.
          if (value < 0) {
            throw new HTTP2SettingsException("HEADER_TABLE_SIZE exceeds 2^31-1 [" + (value & 0xFFFFFFFFL) + "]", HTTP2ErrorCode.PROTOCOL_ERROR);
          }
          headerTableSize = value;
        }
        case SETTINGS_ENABLE_PUSH -> {
          // RFC 9113 §6.5.2: ENABLE_PUSH must be 0 or 1; any other value is a PROTOCOL_ERROR.
          if (value != 0 && value != 1) {
            throw new HTTP2SettingsException("ENABLE_PUSH must be 0 or 1; got [" + value + "]", HTTP2ErrorCode.PROTOCOL_ERROR);
          }
          enablePush = value;
        }
        case SETTINGS_MAX_CONCURRENT_STREAMS -> {
          // RFC 9113 §6.5.2: MAX_CONCURRENT_STREAMS is unsigned 32-bit. Negative means the peer claimed a value
          // above 2^31-1; treat as PROTOCOL_ERROR rather than letting the negative int silently disable streams.
          if (value < 0) {
            throw new HTTP2SettingsException("MAX_CONCURRENT_STREAMS exceeds 2^31-1 [" + (value & 0xFFFFFFFFL) + "]", HTTP2ErrorCode.PROTOCOL_ERROR);
          }
          maxConcurrentStreams = value;
        }
        case SETTINGS_INITIAL_WINDOW_SIZE -> {
          // RFC 9113 §6.5.2: values above 2^31-1 are a FLOW_CONTROL_ERROR.
          // Java interprets the 4-byte unsigned value as signed; values > 2^31-1 appear negative.
          if (value < 0) {
            throw new HTTP2SettingsException("INITIAL_WINDOW_SIZE exceeds 2^31-1", HTTP2ErrorCode.FLOW_CONTROL_ERROR);
          }
          initialWindowSize = value;
        }
        case SETTINGS_MAX_FRAME_SIZE -> {
          // RFC 9113 §6.5.2: MAX_FRAME_SIZE must be in [2^14, 2^24-1]; values outside are PROTOCOL_ERROR.
          if (value < 16384 || value > 16777215) {
            throw new HTTP2SettingsException("MAX_FRAME_SIZE [" + value + "] out of range [16384, 16777215]", HTTP2ErrorCode.PROTOCOL_ERROR);
          }
          maxFrameSize = value;
        }
        case SETTINGS_MAX_HEADER_LIST_SIZE -> {
          // RFC 9113 §6.5.2 doesn't bound MAX_HEADER_LIST_SIZE explicitly, but it's an unsigned 32-bit count.
          // A negative signed int (> 2^31-1) would break the cumulative-bytes guard in handleHeadersFrame.
          if (value < 0) {
            throw new HTTP2SettingsException("MAX_HEADER_LIST_SIZE exceeds 2^31-1 [" + (value & 0xFFFFFFFFL) + "]", HTTP2ErrorCode.PROTOCOL_ERROR);
          }
          maxHeaderListSize = value;
        }
        default -> {
        } // unknown settings silently ignored per §6.5.2
      }
    }
  }

  public int enablePush() {
    return enablePush;
  }

  public int headerTableSize() {
    return headerTableSize;
  }

  public int initialWindowSize() {
    return initialWindowSize;
  }

  public int maxConcurrentStreams() {
    return maxConcurrentStreams;
  }

  public int maxFrameSize() {
    return maxFrameSize;
  }

  public int maxHeaderListSize() {
    return maxHeaderListSize;
  }

  /**
   * Encodes this server's settings as a SETTINGS frame payload. The server always advertises the same six parameters,
   * so the payload is a fixed 36 bytes (six entries of {@link #SETTING_LENGTH} bytes each).
   *
   * @return the 36-byte SETTINGS payload.
   */
  public byte[] toPayload() {
    byte[] payload = new byte[6 * SETTING_LENGTH];
    int p = 0;
    p = writeSetting(payload, p, SETTINGS_HEADER_TABLE_SIZE, headerTableSize);
    p = writeSetting(payload, p, SETTINGS_ENABLE_PUSH, 0); // server never pushes
    p = writeSetting(payload, p, SETTINGS_MAX_CONCURRENT_STREAMS, maxConcurrentStreams);
    p = writeSetting(payload, p, SETTINGS_INITIAL_WINDOW_SIZE, initialWindowSize);
    p = writeSetting(payload, p, SETTINGS_MAX_FRAME_SIZE, maxFrameSize);
    writeSetting(payload, p, SETTINGS_MAX_HEADER_LIST_SIZE, maxHeaderListSize);
    return payload;
  }

  public HTTP2Settings withHeaderTableSize(int size) {
    this.headerTableSize = size;
    return this;
  }

  public HTTP2Settings withInitialWindowSize(int size) {
    this.initialWindowSize = size;
    return this;
  }

  public HTTP2Settings withMaxConcurrentStreams(int n) {
    this.maxConcurrentStreams = n;
    return this;
  }

  public HTTP2Settings withMaxFrameSize(int size) {
    this.maxFrameSize = size;
    return this;
  }

  public HTTP2Settings withMaxHeaderListSize(int size) {
    this.maxHeaderListSize = size;
    return this;
  }

  public static class HTTP2SettingsException extends RuntimeException {
    public final HTTP2ErrorCode errorCode;

    public HTTP2SettingsException(String message, HTTP2ErrorCode errorCode) {
      super(message);
      this.errorCode = errorCode;
    }
  }
}

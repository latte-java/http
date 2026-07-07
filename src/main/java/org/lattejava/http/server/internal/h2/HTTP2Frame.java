/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

/**
 * RFC 9113 §6 frame types. Each variant carries the fields specific to that frame type plus the common stream-id and
 * flags.
 *
 * @author Daniel DeGroff
 */
public sealed interface HTTP2Frame {
  int FLAG_ACK = 0x1; // SETTINGS / PING

  int FLAG_END_HEADERS = 0x4;

  int FLAG_END_STREAM = 0x1;

  int FLAG_PADDED = 0x8;

  int FLAG_PRIORITY = 0x20;

  int FRAME_TYPE_CONTINUATION = 0x9;

  int FRAME_TYPE_DATA = 0x0;

  int FRAME_TYPE_GOAWAY = 0x7;

  int FRAME_TYPE_HEADERS = 0x1;

  int FRAME_TYPE_PING = 0x6;

  int FRAME_TYPE_PRIORITY = 0x2;

  int FRAME_TYPE_PUSH_PROMISE = 0x5;

  int FRAME_TYPE_RST_STREAM = 0x3;

  int FRAME_TYPE_SETTINGS = 0x4;

  int FRAME_TYPE_WINDOW_UPDATE = 0x8;

  int flags();

  int streamId();

  record ContinuationFrame(int streamId, int flags, byte[] data) implements HTTP2Frame {
  }

  record DataFrame(int streamId, int flags, byte[] data, int flowControlledLength) implements HTTP2Frame {
    /** Outbound frames: the flow-controlled length is the payload length (the server never pads). Public because
        test-package callers construct outbound frames with this form. */
    public DataFrame(int streamId, int flags, byte[] data) {
      this(streamId, flags, data, data.length);
    }
  }

  record GoawayFrame(int lastStreamId, int errorCode, byte[] debugData) implements HTTP2Frame {
    public int flags() {
      return 0;
    }

    public int streamId() {
      return 0;
    }
  }

  record HeadersFrame(int streamId, int flags, byte[] data, int priorityDependency) implements HTTP2Frame {
    /** Outbound frames and header blocks without the PRIORITY flag carry no stream dependency. */
    public HeadersFrame(int streamId, int flags, byte[] data) {
      this(streamId, flags, data, -1);
    }
  }

  record PingFrame(int flags, byte[] data) implements HTTP2Frame {
    public int streamId() {
      return 0;
    }
  }

  record PriorityFrame(int streamId, int streamDependency) implements HTTP2Frame {
    public int flags() {
      return 0;
    }
  }

  record PushPromiseFrame(int streamId, int flags, int promisedStreamId, byte[] data) implements HTTP2Frame {
  }

  record RSTStreamFrame(int streamId, int errorCode) implements HTTP2Frame {
    public int flags() {
      return 0;
    }
  }

  record SettingsFrame(int flags, byte[] data) implements HTTP2Frame {
    public int streamId() {
      return 0;
    }
  }

  record UnknownFrame(int streamId, int flags, int type, byte[] data) implements HTTP2Frame {
  }

  record WindowUpdateFrame(int streamId, int windowSizeIncrement) implements HTTP2Frame {
    public int flags() {
      return 0;
    }
  }
}

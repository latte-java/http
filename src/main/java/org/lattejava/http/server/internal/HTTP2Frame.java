/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

/**
 * RFC 9113 §6 frame types. Each variant carries the fields specific to that frame type plus the common stream-id and flags.
 *
 * @author Daniel DeGroff
 */
public sealed interface HTTP2Frame {
  int FRAME_TYPE_DATA = 0x0;
  int FRAME_TYPE_HEADERS = 0x1;
  int FRAME_TYPE_PRIORITY = 0x2;
  int FRAME_TYPE_RST_STREAM = 0x3;
  int FRAME_TYPE_SETTINGS = 0x4;
  int FRAME_TYPE_PUSH_PROMISE = 0x5;
  int FRAME_TYPE_PING = 0x6;
  int FRAME_TYPE_GOAWAY = 0x7;
  int FRAME_TYPE_WINDOW_UPDATE = 0x8;
  int FRAME_TYPE_CONTINUATION = 0x9;

  int FLAG_END_STREAM = 0x1;
  int FLAG_END_HEADERS = 0x4;
  int FLAG_PADDED = 0x8;
  int FLAG_PRIORITY = 0x20;
  int FLAG_ACK = 0x1; // SETTINGS / PING

  int streamId();
  int flags();

  record DataFrame(int streamId, int flags, byte[] payload) implements HTTP2Frame {}
  record HeadersFrame(int streamId, int flags, byte[] headerBlockFragment) implements HTTP2Frame {}
  record PriorityFrame(int streamId) implements HTTP2Frame { public int flags() { return 0; } }
  record RSTStreamFrame(int streamId, int errorCode) implements HTTP2Frame { public int flags() { return 0; } }
  record SettingsFrame(int flags, byte[] payload) implements HTTP2Frame { public int streamId() { return 0; } }
  record PushPromiseFrame(int streamId, int flags, int promisedStreamId, byte[] headerBlockFragment) implements HTTP2Frame {}
  record PingFrame(int flags, byte[] opaqueData) implements HTTP2Frame { public int streamId() { return 0; } }
  record GoawayFrame(int lastStreamId, int errorCode, byte[] debugData) implements HTTP2Frame {
    public int streamId() { return 0; }
    public int flags() { return 0; }
  }
  record WindowUpdateFrame(int streamId, int windowSizeIncrement) implements HTTP2Frame { public int flags() { return 0; } }
  record ContinuationFrame(int streamId, int flags, byte[] headerBlockFragment) implements HTTP2Frame {}
  record UnknownFrame(int streamId, int flags, int type, byte[] payload) implements HTTP2Frame {}
}

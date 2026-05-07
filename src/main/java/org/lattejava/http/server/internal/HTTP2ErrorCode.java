/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

/**
 * RFC 9113 §7 error codes.
 *
 * @author Daniel DeGroff
 */
public enum HTTP2ErrorCode {
  CANCEL(0x8),
  COMPRESSION_ERROR(0x9),
  CONNECT_ERROR(0xa),
  ENHANCE_YOUR_CALM(0xb),
  FLOW_CONTROL_ERROR(0x3),
  FRAME_SIZE_ERROR(0x6),
  HTTP_1_1_REQUIRED(0xd),
  INADEQUATE_SECURITY(0xc),
  INTERNAL_ERROR(0x2),
  NO_ERROR(0x0),
  PROTOCOL_ERROR(0x1),
  REFUSED_STREAM(0x7),
  SETTINGS_TIMEOUT(0x4),
  STREAM_CLOSED(0x5);

  public final int value;

  HTTP2ErrorCode(int value) {
    this.value = value;
  }

  public static HTTP2ErrorCode of(int value) {
    for (HTTP2ErrorCode code : values()) {
      if (code.value == value) {
        return code;
      }
    }
    return INTERNAL_ERROR;
  }
}

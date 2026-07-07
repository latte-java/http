/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

/**
 * Handles a WINDOW_UPDATE frame addressed to a specific stream. The caller is responsible for rate-limit and
 * idle/closed state checks; this handler processes only live streams in a valid state.
 */
public class HTTP2WindowUpdateFrameHandler {
  public HTTP2Result handle(HTTP2Stream stream, HTTP2Frame.WindowUpdateFrame f) {
    if (f.windowSizeIncrement() == 0) {
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR); // §6.9
    }
    if (stream.sendWindow().available() + f.windowSizeIncrement() > Integer.MAX_VALUE) {
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.FLOW_CONTROL_ERROR); // §6.9.1
    }
    stream.sendWindow().increment(f.windowSizeIncrement());
    return HTTP2Result.OK;
  }
}

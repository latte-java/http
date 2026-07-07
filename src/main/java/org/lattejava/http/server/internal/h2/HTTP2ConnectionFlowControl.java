/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

/**
 * Connection-level receive flow control (RFC 9113 §6.9.1): debits the window by each DATA frame's flow-controlled
 * length, enforces overrun as a connection error, and replenishes at half via a stream-0 WINDOW_UPDATE. Called from
 * the DATA arm of {@link HTTP2Stream#handleFrame} for every DATA frame in every stream state — flow control is
 * connection state independent of stream outcome. Reader-thread-confined.
 */
public class HTTP2ConnectionFlowControl {
  private final int connectionWindowSize;

  private final HTTP2Window window;

  private final HTTP2WriterThread writer;

  public HTTP2ConnectionFlowControl(int connectionWindowSize, HTTP2WriterThread writer) {
    this.connectionWindowSize = connectionWindowSize;
    this.window = new HTTP2Window(connectionWindowSize);
    this.writer = writer;
  }

  /**
   * Accounts for one received DATA frame. Returns {@link HTTP2Result#OK}, or a connection error of type
   * FLOW_CONTROL_ERROR when the peer overruns the advertised window.
   */
  public HTTP2Result onData(int flowControlledLength) {
    if (!window.decrement(flowControlledLength)) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.FLOW_CONTROL_ERROR);
    }
    long available = window.available();
    if (available < connectionWindowSize / 2) {
      int delta = (int) (connectionWindowSize - available);
      window.increment(delta);
      writer.enqueueOrCloseWriter(new HTTP2Frame.WindowUpdateFrame(0, delta));
    }
    return HTTP2Result.OK;
  }
}

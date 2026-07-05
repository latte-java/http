/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;

import org.lattejava.http.log.Logger;

/**
 * Handles an RST_STREAM frame addressed to a specific stream. The caller is responsible for rate-limit and idle/closed
 * state checks; this handler processes only streams in states where RST_STREAM has an effect.
 */
public class HTTP2RSTStreamFrameHandler {
  private final Logger logger;

  public HTTP2RSTStreamFrameHandler(Logger logger) {
    this.logger = logger;
  }

  public HTTP2Result handle(HTTP2Stream stream, HTTP2Frame.RSTStreamFrame f) {
    try {
      stream.applyEvent(HTTP2Stream.Event.RECV_RST_STREAM);
    } catch (IllegalStateException ignored) {
      // Stream already CLOSED — handler completed and sent END_STREAM before this RST arrived (common in
      // rapid-reset patterns). The RST is now harmless; cleanup below is still safe to run.
      logger.debug("RST_STREAM on already-closed stream [{}] — ignoring", stream.streamId());
    }
    stream.close();
    BlockingQueue<byte[]> pipe = stream.pipe();
    if (pipe != null) {
      try {
        pipe.put(HTTP2InputStream.eofSentinel());
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
    }
    return HTTP2Result.OK;
  }
}

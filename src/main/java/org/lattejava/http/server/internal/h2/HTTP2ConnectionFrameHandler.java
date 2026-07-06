/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import java.lang.System.Logger.Level;

/**
 * Handles every frame the dispatch loop routes to stream 0: SETTINGS, PING, connection-window WINDOW_UPDATE, and
 * GOAWAY. Stream-addressed frame types arriving with stream ID 0 are connection errors here.
 */
public class HTTP2ConnectionFrameHandler implements HTTP2FrameHandler {
  private static final System.Logger logger = System.getLogger(HTTP2ConnectionFrameHandler.class.getName());

  private final HTTP2Window connectionSendWindow;

  private final HTTP2Settings peerSettings;

  private final HTTP2RateLimitsTracker rateLimits;

  private final HTTP2StreamRegistry registry;

  private final HTTP2WriterThread writer;

  public HTTP2ConnectionFrameHandler(HTTP2Window connectionSendWindow, HTTP2Settings peerSettings,
                                     HTTP2RateLimitsTracker rateLimits, HTTP2StreamRegistry registry, HTTP2WriterThread writer) {
    this.connectionSendWindow = connectionSendWindow;
    this.peerSettings = peerSettings;
    this.rateLimits = rateLimits;
    this.registry = registry;
    this.writer = writer;
  }

  @Override
  public HTTP2Result handleFrame(HTTP2Frame frame) {
    return switch (frame) {
      case HTTP2Frame.SettingsFrame f -> handleSettings(f);
      case HTTP2Frame.PingFrame f -> handlePing(f);
      case HTTP2Frame.WindowUpdateFrame f -> handleWindowUpdate(f);
      case HTTP2Frame.GoawayFrame ignored -> HTTP2Result.SHUTDOWN;
      case HTTP2Frame.UnknownFrame ignored -> HTTP2Result.OK; // §5.5
      default -> new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR); // DATA/HEADERS/PUSH_PROMISE on stream 0
    };
  }

  private HTTP2Result handlePing(HTTP2Frame.PingFrame f) {
    if ((f.flags() & HTTP2Frame.FLAG_ACK) != 0) {
      return HTTP2Result.OK;
    }
    if (rateLimits.recordPing()) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    }
    writer.enqueueOrCloseWriter(new HTTP2Frame.PingFrame(HTTP2Frame.FLAG_ACK, f.data()));
    return HTTP2Result.OK;
  }

  private HTTP2Result handleSettings(HTTP2Frame.SettingsFrame f) {
    if ((f.flags() & HTTP2Frame.FLAG_ACK) != 0) {
      return HTTP2Result.OK;
    }
    if (rateLimits.recordSettings()) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    }
    int oldInitialWindow = peerSettings.initialWindowSize();
    try {
      peerSettings.applyPayload(f.data());
    } catch (HTTP2Settings.HTTP2SettingsException e) {
      logger.log(Level.DEBUG, "Invalid SETTINGS from peer: [{0}]", e.getMessage());
      return new HTTP2Result.ConnectionError(e.errorCode);
    }
    int delta = peerSettings.initialWindowSize() - oldInitialWindow;
    if (delta != 0) {
      // RFC 9113 §6.9.2 — adjust open streams' send-windows by the delta and wake blocked writers.
      for (HTTP2Stream s : registry.liveStreams()) {
        // RFC 9113 §6.9.2 — an increase that would push a stream window past 2^31-1 is a connection error.
        if (s.sendWindow().available() + delta > Integer.MAX_VALUE) {
          return new HTTP2Result.ConnectionError(HTTP2ErrorCode.FLOW_CONTROL_ERROR);
        }
        s.sendWindow().increment(delta);
      }
    }
    writer.enqueueOrCloseWriter(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
    return HTTP2Result.OK;
  }

  private HTTP2Result handleWindowUpdate(HTTP2Frame.WindowUpdateFrame f) {
    if (rateLimits.recordWindowUpdate()) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    }
    if (f.windowSizeIncrement() == 0) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR); // §6.9
    }
    if (connectionSendWindow.available() + f.windowSizeIncrement() > Integer.MAX_VALUE) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.FLOW_CONTROL_ERROR); // §6.9.1
    }
    connectionSendWindow.increment(f.windowSizeIncrement());
    return HTTP2Result.OK;
  }
}

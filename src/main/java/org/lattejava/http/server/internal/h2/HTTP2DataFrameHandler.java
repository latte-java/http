/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;

import org.lattejava.http.log.Logger;
import org.lattejava.http.server.HTTP2Configuration;

/**
 * Handles a DATA frame addressed to a specific stream. The caller is responsible for empty-DATA rate-limiting, stream-0
 * rejection, and idle/closed/half-closed-remote state checks; this handler processes only streams with a body pipe.
 */
public class HTTP2DataFrameHandler {
  private final HTTP2Configuration http2Configuration;

  private final HTTP2Settings localSettings;

  private final Logger logger;

  private final HTTP2WriterThread writer;

  public HTTP2DataFrameHandler(HTTP2Configuration http2Configuration, HTTP2Settings localSettings, Logger logger,
                               HTTP2WriterThread writer) {
    this.http2Configuration = http2Configuration;
    this.localSettings = localSettings;
    this.logger = logger;
    this.writer = writer;
  }

  public HTTP2Result handle(HTTP2Stream stream, HTTP2Frame.DataFrame frame) {
    BlockingQueue<byte[]> pipe = stream.pipe();
    if (pipe == null) {
      // DATA after END_STREAM-on-HEADERS while OPEN is impossible, but defensive: ignore per §6.1.
      return HTTP2Result.OK;
    }

    if (frame.flowControlledLength() > 0) {
      // §8.1.2.6: check that the running DATA total does not exceed the declared content-length (data bytes only;
      // padding is flow-controlled but not content).
      if (frame.data().length > 0 && !stream.incrementDataCount(frame.data().length)) {
        return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
      }

      if (!stream.receiveWindow().decrement(frame.flowControlledLength())) {
        // RFC 9113 §6.9.1 — the peer sent more than the stream window granted; stream error FLOW_CONTROL_ERROR.
        return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.FLOW_CONTROL_ERROR);
      }

      if (frame.data().length > 0) {
        try {
          long timeoutMs = http2Configuration.getHandlerReadTimeout().toMillis();
          if (!pipe.offer(frame.data(), timeoutMs, TimeUnit.MILLISECONDS)) {
            // RFC 9113 §5.2 flow control is the intended back-pressure mechanism — but if a handler is not consuming
            // its body at all (stuck or buggy), the per-stream pipe fills and blocking the reader thread would freeze
            // every other stream on this connection. Cancel the offending stream instead.
            logger.debug("h2 handler on stream [{}] did not consume body within [{}ms]; sending RST_STREAM(CANCEL)",
                stream.streamId(), timeoutMs);
            stream.deregister();
            return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.CANCEL);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return HTTP2Result.OK;
        }
      }
    }

    if ((frame.flags() & HTTP2Frame.FLAG_END_STREAM) != 0) {
      // §8.1.2.6: when END_STREAM arrives, verify total DATA matches declared content-length.
      if (!stream.dataLengthMatches()) {
        return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
      }
      try {
        pipe.put(HTTP2InputStream.eofSentinel());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      stream.applyEvent(HTTP2Stream.Event.RECV_DATA_END_STREAM);
    }

    // Replenish-when-half-empty strategy (RFC 9113 §6.9.1).
    // When the stream receive-window drops below half its initial value, send a WINDOW_UPDATE to restore it.
    // Without this, uploads larger than INITIAL_WINDOW_SIZE (65535) stall waiting for credit.
    if (frame.flowControlledLength() > 0) {
      if (stream.receiveWindow().available() < (long) localSettings.initialWindowSize() / 2) {
        int delta = localSettings.initialWindowSize() - (int) stream.receiveWindow().available();
        stream.receiveWindow().increment(delta);
        writer.enqueueOrCloseWriter(new HTTP2Frame.WindowUpdateFrame(stream.streamId(), delta));
      }
    }

    return HTTP2Result.OK;
  }
}

/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

/**
 * Per-stream state — RFC 9113 §5.1 state machine plus send/receive window counters. Synchronized for cross-thread
 * safety: the connection reader updates state via applyEvent. Window state lives in two {@link HTTP2Window} fields:
 * handler threads acquire from the send window; the reader thread debits and replenishes the receive window and
 * updates state.
 *
 * @author Daniel DeGroff
 */
public class HTTP2Stream implements HTTP2FrameHandler {
  private final HTTP2StreamFrameHandlers handlers;

  private final HTTP2Window receiveWindow;

  private final HTTP2StreamRegistry registry;

  private final boolean remembered;

  private final HTTP2Window sendWindow;

  private final int streamId;

  private long declaredContentLength = -1; // -1 means unset

  private volatile BlockingQueue<byte[]> pipe;

  private long receivedDataBytes;

  // The HTTPRequest associated with this stream. Set by the connection reader once the initial HEADERS block has been
  // decoded and the request constructed. Used by the trailers path so the reader can deliver request trailers to the
  // same HTTPRequest the handler thread is processing (RFC 9113 §8.1).
  private volatile HTTPRequest request;

  private State state;

  /**
   * Full constructor used by {@link HTTP2StreamRegistry} and the new frame-handler path (Task 5+).
   */
  public HTTP2Stream(int streamId, State initialState, boolean remembered, int initialReceiveWindow, int initialSendWindow,
                     HTTP2StreamFrameHandlers handlers, HTTP2StreamRegistry registry) {
    this.streamId = streamId;
    this.state = initialState;
    this.remembered = remembered;
    this.receiveWindow = new HTTP2Window(initialReceiveWindow);
    this.sendWindow = new HTTP2Window(initialSendWindow);
    this.handlers = handlers;
    this.registry = registry;
  }

  private static State transition(State s, Event e) {
    return switch (s) {
      case IDLE -> switch (e) {
        case RECV_HEADERS_NO_END_STREAM, SEND_HEADERS_NO_END_STREAM -> State.OPEN;
        case RECV_HEADERS_END_STREAM -> State.HALF_CLOSED_REMOTE;
        case SEND_HEADERS_END_STREAM -> State.HALF_CLOSED_LOCAL;
        case SEND_RST_STREAM, RECV_RST_STREAM -> State.CLOSED;
        default -> throw new IllegalStateException("Event [" + e + "] illegal in state [IDLE]");
      };
      case OPEN -> switch (e) {
        case RECV_DATA_NO_END_STREAM, SEND_DATA_NO_END_STREAM, RECV_HEADERS_NO_END_STREAM, SEND_HEADERS_NO_END_STREAM ->
            State.OPEN;
        case RECV_DATA_END_STREAM, RECV_HEADERS_END_STREAM -> State.HALF_CLOSED_REMOTE;
        case SEND_DATA_END_STREAM, SEND_HEADERS_END_STREAM -> State.HALF_CLOSED_LOCAL;
        case RECV_RST_STREAM, SEND_RST_STREAM -> State.CLOSED;
      };
      case HALF_CLOSED_LOCAL -> switch (e) {
        case RECV_DATA_NO_END_STREAM, RECV_HEADERS_NO_END_STREAM -> State.HALF_CLOSED_LOCAL;
        case RECV_DATA_END_STREAM, RECV_HEADERS_END_STREAM, RECV_RST_STREAM, SEND_RST_STREAM -> State.CLOSED;
        default -> throw new IllegalStateException("Event [" + e + "] illegal in state [HALF_CLOSED_LOCAL]");
      };
      case HALF_CLOSED_REMOTE -> switch (e) {
        case SEND_DATA_NO_END_STREAM, SEND_HEADERS_NO_END_STREAM -> State.HALF_CLOSED_REMOTE;
        case SEND_DATA_END_STREAM, SEND_HEADERS_END_STREAM, RECV_RST_STREAM, SEND_RST_STREAM -> State.CLOSED;
        default -> throw new IllegalStateException("Event [" + e + "] illegal in state [HALF_CLOSED_REMOTE]");
      };
      case CLOSED -> throw new IllegalStateException("Event [" + e + "] illegal in state [CLOSED]");
    };
  }

  public synchronized void applyEvent(Event event) {
    state = transition(state, event);
  }

  /**
   * Removes this stream from the roster and records closed-stream memory. Reader thread only.
   */
  public void close() {
    registry.close(streamId);
  }

  /**
   * Returns {@code true} if the total received DATA bytes match the declared content-length (or no content-length was
   * declared). Called when END_STREAM arrives to detect under-delivery.
   */
  public synchronized boolean dataLengthMatches() {
    return declaredContentLength == -1 || receivedDataBytes == declaredContentLength;
  }

  /**
   * Removes this stream from the roster without closed memory — handler-thread completion path.
   */
  public void deregister() {
    registry.remove(streamId);
  }

  /**
   * Validates {@code frame} against this stream's RFC 9113 §5.1 state (the design §5 matrix) and delegates legal frames
   * to the per-connection handlers. Frame-type rate limits run before state validation, preserving today's check
   * ordering (flood frames aimed at dead streams still count).
   */
  @Override
  public HTTP2Result handleFrame(HTTP2Frame frame) {
    return switch (frame) {
      case HTTP2Frame.HeadersFrame f -> handleHeaders(f);
      case HTTP2Frame.DataFrame f -> handleData(f);
      case HTTP2Frame.WindowUpdateFrame f -> handleWindowUpdate(f);
      case HTTP2Frame.RSTStreamFrame f -> handleRST(f);
      case HTTP2Frame.PriorityFrame ignored -> HTTP2Result.OK; // §5.3 advisory, legal in every state
      case HTTP2Frame.PushPromiseFrame ignored -> new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR);
      default -> HTTP2Result.OK; // UnknownFrame §5.5; SETTINGS/PING/GOAWAY never route here
    };
  }

  /**
   * Accumulates the number of DATA bytes received on this stream and checks against the declared content-length (RFC
   * 9113 §8.1.2.6). Returns {@code true} if the running total does not exceed the declared length; returns
   * {@code false} if the declared length has been exceeded (caller must RST_STREAM immediately).
   */
  public synchronized boolean incrementDataCount(int n) {
    receivedDataBytes += n;
    return declaredContentLength == -1 || receivedDataBytes <= declaredContentLength;
  }

  /**
   * Registers this stream with the roster; false means the MAX_CONCURRENT_STREAMS cap refused it.
   */
  public boolean open() {
    return registry.open(this);
  }

  public BlockingQueue<byte[]> pipe() {
    return pipe;
  }

  public HTTP2Window receiveWindow() {
    return receiveWindow;
  }

  public HTTPRequest request() {
    return request;
  }

  public HTTP2Window sendWindow() {
    return sendWindow;
  }

  public synchronized void setDeclaredContentLength(long value) {
    declaredContentLength = value;
  }

  public void setPipe(BlockingQueue<byte[]> pipe) {
    this.pipe = pipe;
  }

  public void setRequest(HTTPRequest request) {
    this.request = request;
  }

  public synchronized State state() {
    return state;
  }

  public int streamId() {
    return streamId;
  }

  private HTTP2Result handleData(HTTP2Frame.DataFrame frame) {
    // RFC 9113 §6.9 — connection-level flow control applies to every DATA frame regardless of stream state.
    HTTP2Result connectionResult = handlers.connectionFlowControl().onData(frame.flowControlledLength());
    if (!(connectionResult instanceof HTTP2Result.Ok)) {
      return connectionResult;
    }

    // Empty-DATA flood guard runs before state checks, as today.
    if (frame.data().length == 0 && (frame.flags() & HTTP2Frame.FLAG_END_STREAM) == 0 && handlers.rateLimits().recordEmptyData()) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    }

    return switch (state()) {
      case IDLE -> (streamId & 1) == 1
          ? new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR)  // §5.1 — DATA on an idle client stream
          : HTTP2Result.OK;                                                 // even IDs: ignore per §6.1
      case CLOSED -> remembered
          ? new HTTP2Result.ConnectionError(HTTP2ErrorCode.STREAM_CLOSED)   // §5.1 — recently closed
          : HTTP2Result.OK;                                                 // forgotten: ignore per §6.1
      case HALF_CLOSED_REMOTE -> new HTTP2Result.StreamError(streamId, HTTP2ErrorCode.STREAM_CLOSED); // §5.1
      case OPEN, HALF_CLOSED_LOCAL -> handlers.dataHandler().handle(this, frame);
    };
  }

  private HTTP2Result handleHeaders(HTTP2Frame.HeadersFrame f) {
    return switch (state()) {
      case IDLE -> (streamId & 1) == 0
          ? new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR)  // §5.1.1 — client streams are odd
          : handlers.headerHandler().handleNewStream(this, f);
      case CLOSED -> remembered
          ? new HTTP2Result.ConnectionError(HTTP2ErrorCode.STREAM_CLOSED)   // §5.1
          : new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR); // §5.1.1 monotonicity
      case OPEN, HALF_CLOSED_LOCAL -> (f.flags() & HTTP2Frame.FLAG_END_STREAM) != 0
          ? handlers.headerHandler().handleTrailers(this, f)
          : new HTTP2Result.StreamError(streamId, HTTP2ErrorCode.STREAM_CLOSED); // §8.1 — not trailers, not legal
      case HALF_CLOSED_REMOTE -> new HTTP2Result.StreamError(streamId, HTTP2ErrorCode.STREAM_CLOSED); // §5.1
    };
  }

  private HTTP2Result handleRST(HTTP2Frame.RSTStreamFrame f) {
    // Rapid-reset guard (CVE-2023-44487) runs before state checks, as today.
    if (handlers.rateLimits().recordRstStream()) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    }
    return switch (state()) {
      case IDLE -> new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR); // §6.4 — RST on an idle stream
      case CLOSED -> HTTP2Result.OK;                                               // benign either way
      default -> handlers.rstStreamHandler().handle(this, f);
    };
  }

  private HTTP2Result handleWindowUpdate(HTTP2Frame.WindowUpdateFrame f) {
    // WINDOW_UPDATE flood guard runs before state checks, as today.
    if (handlers.rateLimits().recordWindowUpdate()) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    }
    return switch (state()) {
      case IDLE -> (streamId & 1) == 1
          ? new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR)  // §5.1
          : HTTP2Result.OK;
      case CLOSED -> HTTP2Result.OK;                                        // §6.9 — tolerated after close
      default -> handlers.windowUpdateHandler().handle(this, f);
    };
  }

  public enum Event {
    RECV_DATA_END_STREAM,
    RECV_DATA_NO_END_STREAM,
    RECV_HEADERS_END_STREAM,
    RECV_HEADERS_NO_END_STREAM,
    RECV_RST_STREAM,
    SEND_DATA_END_STREAM,
    SEND_DATA_NO_END_STREAM,
    SEND_HEADERS_END_STREAM,
    SEND_HEADERS_NO_END_STREAM,
    SEND_RST_STREAM
  }

  public enum State {
    CLOSED,
    HALF_CLOSED_LOCAL,
    HALF_CLOSED_REMOTE,
    IDLE,
    OPEN
  }
}

/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

/**
 * Per-stream state — RFC 9113 §5.1 state machine plus send/receive window counters. Synchronized for cross-thread
 * safety: the connection reader updates state via applyEvent, the writer thread checks/consumes the send window, the
 * handler thread reads the receive window.
 *
 * @author Daniel DeGroff
 */
public class HTTP2Stream implements HTTP2FrameHandler {
  private final HTTP2StreamFrameHandlers handlers;

  private final HTTP2StreamRegistry registry;

  private final boolean remembered;

  private final int streamId;

  private long declaredContentLength = -1; // -1 means unset

  private volatile BlockingQueue<byte[]> pipe;

  private long receiveWindow;

  private long receivedDataBytes;

  // The HTTPRequest associated with this stream. Set by the connection reader once the initial HEADERS block has been
  // decoded and the request constructed. Used by the trailers path so the reader can deliver request trailers to the
  // same HTTPRequest the handler thread is processing (RFC 9113 §8.1).
  private volatile HTTPRequest request;

  private long sendWindow;

  private State state;

  /**
   * Full constructor used by {@link HTTP2StreamRegistry} and the new frame-handler path (Task 5+).
   */
  public HTTP2Stream(int streamId, State initialState, boolean remembered, int initialReceiveWindow, int initialSendWindow,
                     HTTP2StreamFrameHandlers handlers, HTTP2StreamRegistry registry) {
    this.streamId = streamId;
    this.state = initialState;
    this.remembered = remembered;
    this.receiveWindow = initialReceiveWindow;
    this.sendWindow = initialSendWindow;
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

  /**
   * Atomically waits until the send window is positive, then consumes {@code min(want, available)} octets and returns
   * the amount consumed. Used by the per-stream writer so the window check and the `consume` are a single synchronized
   * step: a concurrent SETTINGS-induced INITIAL_WINDOW_SIZE change cannot wedge between them and force a spurious
   * underflow. {@code timeoutMillis} bounds each wait so the caller stays responsive to interruption and teardown.
   */
  public synchronized int acquireSendWindow(int want, long timeoutMillis) throws InterruptedException {
    while (sendWindow <= 0) {
      wait(timeoutMillis);
    }
    int grant = (int) Math.min(want, sendWindow);
    sendWindow -= grant;
    return grant;
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

  public synchronized void consumeReceiveWindow(int bytes) {
    if (bytes > receiveWindow) {
      throw new IllegalStateException("Stream [" + streamId + "] receive-window underflow: needed [" + bytes + "], have [" + receiveWindow + "]");
    }
    receiveWindow -= bytes;
  }

  public synchronized void consumeSendWindow(int bytes) {
    if (bytes > sendWindow) {
      throw new IllegalStateException("Stream [" + streamId + "] send-window underflow: needed [" + bytes + "], have [" + sendWindow + "]");
    }
    sendWindow -= bytes;
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

  public synchronized void incrementReceiveWindow(int delta) {
    receiveWindow += delta;
  }

  public synchronized void incrementSendWindow(int delta) {
    long next = sendWindow + delta;
    if (next > Integer.MAX_VALUE) {
      throw new IllegalStateException("Stream [" + streamId + "] send-window overflow past 2^31-1");
    }
    sendWindow = next;
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

  public synchronized long receiveWindow() {
    return receiveWindow;
  }

  /**
   * Returns send-window credit that was acquired but not used — for example when the connection-level window was the
   * tighter constraint and granted fewer octets than this stream's window did.
   */
  public synchronized void releaseSendWindow(int bytes) {
    sendWindow += bytes;
  }

  public HTTPRequest request() {
    return request;
  }

  public synchronized long sendWindow() {
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

  /**
   * Non-blocking, all-or-nothing send-window acquire: consumes {@code want} octets and returns {@code true} only if the
   * full amount is available; otherwise consumes nothing and returns {@code false}. Backs the single-frame fast path in
   * {@link HTTP2OutputStream}.
   */
  public synchronized boolean tryAcquireSendWindow(int want) {
    if (sendWindow < want) {
      return false;
    }
    sendWindow -= want;
    return true;
  }

  private HTTP2Result handleData(HTTP2Frame.DataFrame f) {
    // Empty-DATA flood guard runs before state checks, as today.
    if (f.data().length == 0 && (f.flags() & HTTP2Frame.FLAG_END_STREAM) == 0 && handlers.rateLimits().recordEmptyData()) {
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
      case OPEN, HALF_CLOSED_LOCAL -> handlers.dataHandler().handle(this, f);
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

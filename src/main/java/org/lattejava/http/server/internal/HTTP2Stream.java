/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import org.lattejava.http.server.HTTPRequest;

/**
 * Per-stream state — RFC 9113 §5.1 state machine plus send/receive window counters. Synchronized for cross-thread
 * safety: the connection reader updates state via applyEvent, the writer thread checks/consumes the send window, the
 * handler thread reads the receive window.
 *
 * @author Daniel DeGroff
 */
public class HTTP2Stream {
  private final int streamId;
  private long declaredContentLength = -1; // -1 means unset
  private long receiveWindow;
  private long receivedDataBytes;
  // The HTTPRequest associated with this stream. Set by the connection reader once the initial HEADERS block has been
  // decoded and the request constructed. Used by the trailers path so the reader can deliver request trailers to the
  // same HTTPRequest the handler thread is processing (RFC 9113 §8.1).
  private volatile HTTPRequest request;
  private long sendWindow;
  private State state = State.IDLE;

  public HTTP2Stream(int streamId, int initialReceiveWindow, int initialSendWindow) {
    this.streamId = streamId;
    this.receiveWindow = initialReceiveWindow;
    this.sendWindow = initialSendWindow;
  }

  public HTTPRequest request() {
    return request;
  }

  public void setRequest(HTTPRequest request) {
    this.request = request;
  }

  public synchronized void applyEvent(Event event) {
    state = transition(state, event);
  }

  /**
   * Accumulates the number of DATA bytes received on this stream and checks against the declared content-length (RFC
   * 9113 §8.1.2.6). Returns {@code true} if the running total does not exceed the declared length; returns
   * {@code false} if the declared length has been exceeded (caller must RST_STREAM immediately).
   */
  public synchronized boolean appendDataBytes(int n) {
    receivedDataBytes += n;
    return declaredContentLength == -1 || receivedDataBytes <= declaredContentLength;
  }

  /**
   * Returns {@code true} if the total received DATA bytes match the declared content-length (or no content-length was
   * declared). Called when END_STREAM arrives to detect under-delivery.
   */
  public synchronized boolean dataLengthMatches() {
    return declaredContentLength == -1 || receivedDataBytes == declaredContentLength;
  }

  /**
   * Atomically waits until the send window is positive, then consumes {@code min(want, available)} octets and returns
   * the amount consumed. Used by the per-stream writer so the window check and the consume are a single synchronized
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

  public synchronized long sendWindow() {
    return sendWindow;
  }

  public synchronized void setDeclaredContentLength(long value) {
    declaredContentLength = value;
  }

  public synchronized State state() {
    return state;
  }

  public int streamId() {
    return streamId;
  }

  /**
   * Non-blocking, all-or-nothing send-window acquire: consumes {@code want} octets and returns {@code true} only if
   * the full amount is available; otherwise consumes nothing and returns {@code false}. Backs the single-frame fast
   * path in {@link HTTP2OutputStream}.
   */
  public synchronized boolean tryAcquireSendWindow(int want) {
    if (sendWindow < want) {
      return false;
    }
    sendWindow -= want;
    return true;
  }

  private static State transition(State s, Event e) {
    return switch (s) {
      case IDLE -> switch (e) {
        case RECV_HEADERS_NO_END_STREAM -> State.OPEN;
        case RECV_HEADERS_END_STREAM -> State.HALF_CLOSED_REMOTE;
        case SEND_HEADERS_NO_END_STREAM -> State.OPEN;
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
        case RECV_DATA_END_STREAM, RECV_HEADERS_END_STREAM -> State.CLOSED;
        case RECV_RST_STREAM, SEND_RST_STREAM -> State.CLOSED;
        default -> throw new IllegalStateException("Event [" + e + "] illegal in state [HALF_CLOSED_LOCAL]");
      };
      case HALF_CLOSED_REMOTE -> switch (e) {
        case SEND_DATA_NO_END_STREAM, SEND_HEADERS_NO_END_STREAM -> State.HALF_CLOSED_REMOTE;
        case SEND_DATA_END_STREAM, SEND_HEADERS_END_STREAM -> State.CLOSED;
        case RECV_RST_STREAM, SEND_RST_STREAM -> State.CLOSED;
        default -> throw new IllegalStateException("Event [" + e + "] illegal in state [HALF_CLOSED_REMOTE]");
      };
      case CLOSED -> throw new IllegalStateException("Event [" + e + "] illegal in state [CLOSED]");
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

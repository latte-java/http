/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

/**
 * Per-stream state — RFC 9113 §5.1 state machine plus send/receive window counters. Synchronized for cross-thread safety: the connection reader updates state via applyEvent, the writer thread checks/consumes the send window, the handler thread reads the receive window.
 *
 * @author Daniel DeGroff
 */
public class HTTP2Stream {
  private final int streamId;
  private long receiveWindow;
  private long sendWindow;
  private State state = State.IDLE;

  public HTTP2Stream(int streamId, int initialReceiveWindow, int initialSendWindow) {
    this.streamId = streamId;
    this.receiveWindow = initialReceiveWindow;
    this.sendWindow = initialSendWindow;
  }

  public synchronized void applyEvent(Event event) {
    state = transition(state, event);
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

  public synchronized long receiveWindow() { return receiveWindow; }

  public synchronized long sendWindow() { return sendWindow; }

  public synchronized State state() { return state; }

  public int streamId() { return streamId; }

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
        case RECV_DATA_NO_END_STREAM, SEND_DATA_NO_END_STREAM, RECV_HEADERS_NO_END_STREAM, SEND_HEADERS_NO_END_STREAM -> State.OPEN;
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

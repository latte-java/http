/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import java.net.Socket;

/**
 * Implemented by both HTTP/1.1 and HTTP/2 worker classes so the cleaner thread can monitor either uniformly.
 *
 * @author Daniel DeGroff
 */
public interface HTTPConnection extends Runnable {
  long getHandledRequests();

  Socket getSocket();

  long getStartInstant();

  /**
   * Initiates a graceful, in-band shutdown of this connection if the protocol supports one. HTTP/2 emits
   * {@code GOAWAY(NO_ERROR)}; HTTP/1.1 has no such signal and implements this as a no-op. Called by
   * {@link HTTPServerAcceptorThread} during server shutdown, before the connection's thread is interrupted.
   */
  void shutdown();

  /**
   * Aggregated state across the connection's threads. For HTTP/1.1 this is the worker's state; for HTTP/2 this is the
   * worst-case role state across reader/writer/active handlers (Read if any thread is blocked reading, Write if any is
   * blocked writing, otherwise Process).
   */
  State state();

  enum State {
    /**
     * The connection is idle between requests on a persistent HTTP/1.1 socket. The slow-reader throughput check MUST
     * NOT apply in this state — keep-alive sockets do not consume bytes, so any throughput sample taken now would
     * compare bytes read during the prior request against an elapsed time that includes the idle period and the
     * connection would be incorrectly evicted as a slow reader. Keep-alive expiry is governed instead by the
     * socket-level {@code SO_TIMEOUT} that the worker sets when it transitions into this state.
     */
    KeepAlive,
    Process,
    Read,
    Write
  }
}

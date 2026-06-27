/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import java.net.Socket;

/**
 * Implemented by {@link org.lattejava.http.server.internal.h1.HTTP1Connection},
 * {@link org.lattejava.http.server.internal.h2.HTTP2Connection}, and {@link ConnectionDispatcher} so the
 * {@code ConnectionReaperThread} can monitor any connection uniformly.
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
    /**
     * The connection is performing protocol negotiation (the TLS-ALPN handshake or the h2c preface read) on its
     * virtual thread, before any protocol handler exists. The slow-reader throughput check MUST NOT apply in this
     * state: handshake bytes flow on the raw socket, not through {@code ThroughputInputStream}, so a throughput sample
     * taken now would read ~0 bytes/s and the reaper would evict a legitimate in-progress handshake. This phase is
     * bounded instead by the socket-level {@code SO_TIMEOUT} (the initial-read timeout) — a stalled handshake read
     * throws {@code SocketTimeoutException}, which closes the socket.
     */
    Negotiating,
    Process,
    Read,
    Write
  }
}

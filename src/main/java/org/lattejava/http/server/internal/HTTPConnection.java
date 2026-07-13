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
  /**
   * Evaluates this connection's liveness. Called by the reaper on each pass. Returns null when the connection is
   * healthy, or the reason it must be evicted.
   */
  EvictionReason check(long now);

  /**
   * Tears this connection down in a protocol-appropriate way. Must not block the calling (reaper) thread and must be
   * idempotent.
   */
  void evict(EvictionReason reason);

  long getHandledRequests();

  Socket getSocket();

  long getStartInstant();

  /**
   * Initiates a graceful, in-band shutdown of this connection if the protocol supports one. HTTP/2 emits
   * {@code GOAWAY(NO_ERROR)}; HTTP/1.1 has no such signal and implements this as a no-op. Called by
   * {@link HTTPServerAcceptorThread} during server shutdown, before the connection's thread is interrupted.
   */
  void shutdown();
}

/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import java.net.Socket;

/**
 * Implemented by both HTTP/1.1 and HTTP/2 worker classes so the cleaner thread can monitor either uniformly.
 *
 * @author Daniel DeGroff
 */
public interface ClientConnection {
  long getHandledRequests();

  Socket getSocket();

  long getStartInstant();

  /**
   * Aggregated state across the connection's threads. For HTTP/1.1 this is the worker's state; for HTTP/2 this is the worst-case role state across reader/writer/active handlers (Read if any thread is blocked reading, Write if any is blocked writing, otherwise Process).
   */
  State state();

  enum State {
    Process,
    Read,
    Write
  }
}

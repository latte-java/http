/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

/**
 * Stub implementation. Real wiring lands in Task 7.
 *
 * @author Daniel DeGroff
 */
public class HTTP2Connection implements ClientConnection, Runnable {
  private final Socket socket;
  private final long startInstant = System.currentTimeMillis();

  public HTTP2Connection(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter,
                         HTTPListenerConfiguration listener, Throughput throughput, Boolean prefaceAlreadyConsumed) throws IOException {
    this.socket = socket;
  }

  @Override
  public long getHandledRequests() {
    return 0;
  }

  @Override
  public Socket getSocket() {
    return socket;
  }

  @Override
  public long getStartInstant() {
    return startInstant;
  }

  @Override
  public void run() {
    // Real implementation lands in Task 7. For now: close the socket cleanly so anyone reaching this branch sees a clear
    // shutdown rather than a hang.
    try {
      socket.close();
    } catch (IOException ignore) {
    }
  }

  @Override
  public ClientConnection.State state() {
    return ClientConnection.State.Read;
  }
}

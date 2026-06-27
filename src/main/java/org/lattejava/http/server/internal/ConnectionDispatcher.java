/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

/**
 * Runs on the per-connection virtual thread. Performs protocol selection (the TLS-ALPN handshake or the h2c preface
 * peek) off the accept thread, then delegates to the resolved {@link org.lattejava.http.server.internal.h1.HTTP1Connection}
 * or {@link org.lattejava.http.server.internal.h2.HTTP2Connection}.
 *
 * <p>It implements {@link HTTPConnection} so {@link HTTPServerAcceptorThread} can register it with the reaper the
 * instant the connection is accepted — before the blocking negotiation runs. Until the delegate exists, the dispatcher
 * reports {@link HTTPConnection.State#Negotiating} so the reaper's throughput check does not evict an in-progress
 * handshake; the handshake is bounded by the socket {@code SO_TIMEOUT}.
 *
 * @author Brian Pontarelli
 */
public class ConnectionDispatcher implements HTTPConnection {
  private final HTTPServerConfiguration configuration;
  private final HTTPContext context;
  private final Instrumenter instrumenter;
  private final HTTPListenerConfiguration listener;
  private final Logger logger;
  private final Socket socket;
  private final long startInstant;
  private final Throughput throughput;
  private volatile HTTPConnection delegate;

  public ConnectionDispatcher(Socket socket, HTTPServerConfiguration configuration, HTTPContext context,
                              Instrumenter instrumenter, HTTPListenerConfiguration listener, Throughput throughput) {
    this.socket = socket;
    this.configuration = configuration;
    this.context = context;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.throughput = throughput;
    this.logger = configuration.getLoggerFactory().getLogger(ConnectionDispatcher.class);
    this.startInstant = System.currentTimeMillis();
  }

  @Override
  public long getHandledRequests() {
    HTTPConnection d = delegate;
    return d != null ? d.getHandledRequests() : 0;
  }

  @Override
  public Socket getSocket() {
    return socket;
  }

  @Override
  public long getStartInstant() {
    HTTPConnection d = delegate;
    return d != null ? d.getStartInstant() : startInstant;
  }

  @Override
  public void run() {
    try {
      HTTPConnection selected = ProtocolSelector.select(socket, configuration, context, instrumenter, listener, throughput);
      delegate = selected;
      selected.run();
    } catch (IOException e) {
      // Protocol selection failed: TLS handshake error, h2c-preface peek error, or a slow-loris client that never
      // finished the handshake within the initial-read SO_TIMEOUT. Close the socket so the file descriptor does not
      // leak; the reaper removes this now-dead thread on its next pass.
      logger.debug("Protocol selection failed; closing socket", e);
      try {
        socket.close();
      } catch (IOException ignore) {
      }
    }
  }

  @Override
  public void shutdown() {
    HTTPConnection d = delegate;
    if (d != null) {
      d.shutdown();
    }
  }

  @Override
  public State state() {
    HTTPConnection d = delegate;
    return d != null ? d.state() : State.Negotiating;
  }
}

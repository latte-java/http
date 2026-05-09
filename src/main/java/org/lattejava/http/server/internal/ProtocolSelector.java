/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

import javax.net.ssl.SSLSocket;

import org.lattejava.http.io.PushbackInputStream;

/**
 * Dispatches an accepted connection to either {@link HTTP1Worker} or {@link HTTP2Connection} based on TLS-ALPN
 * selection (TLS path) or peek of the connection preface (h2c prior-knowledge cleartext path).
 *
 * @author Daniel DeGroff
 */
public class ProtocolSelector {
  private static final byte[] HTTP2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

  /**
   * Selects the appropriate connection handler for the given socket.
   *
   * @param socket          the accepted client socket
   * @param configuration   the server configuration
   * @param context         the server context
   * @param instrumenter    the instrumenter, may be null
   * @param listener        the listener configuration that accepted the connection
   * @param throughput      the per-connection throughput tracker
   * @return a {@link ClientConnection} (also a {@link Runnable}) ready to be started on a virtual thread
   * @throws IOException if the socket or handshake fails before dispatch
   */
  public static ClientConnection select(Socket socket, HTTPServerConfiguration configuration, HTTPContext context,
                                        Instrumenter instrumenter, HTTPListenerConfiguration listener,
                                        Throughput throughput) throws IOException {
    if (socket instanceof SSLSocket sslSocket) {
      // Force handshake so ALPN selection has happened.
      sslSocket.startHandshake();
      String proto = sslSocket.getApplicationProtocol();
      if ("h2".equals(proto)) {
        return new HTTP2Connection(socket, configuration, context, instrumenter, listener, throughput, null);
      }
      // null, "", or "http/1.1" all → HTTP/1.1
      return new HTTP1Worker(socket, configuration, context, instrumenter, listener, throughput);
    }

    // Cleartext path: check for h2c prior-knowledge.
    if (listener.isH2cPriorKnowledgeEnabled()) {
      // Wrap the socket input exactly as HTTP1Worker would so throughput accounting is consistent.
      var pushback = new PushbackInputStream(new ThroughputInputStream(socket.getInputStream(), throughput), instrumenter);
      byte[] peek = new byte[HTTP2_PREFACE.length];
      int n;
      try {
        n = pushback.readNBytes(peek, 0, peek.length);
      } catch (SocketTimeoutException timeout) {
        // Slowloris-style client never finished the preface within the initial-read timeout. Fall back to HTTP/1.1,
        // which has its own preamble parser with its own timeout. The pushback stream has no buffered bytes at this
        // point so it is safe to pass directly to the worker.
        return new HTTP1Worker(socket, configuration, context, instrumenter, listener, throughput, pushback);
      }
      if (n == HTTP2_PREFACE.length && Arrays.equals(peek, HTTP2_PREFACE)) {
        return new HTTP2Connection(socket, configuration, context, instrumenter, listener, throughput, /*prefaceConsumed=*/true);
      }
      // Preface did not match — this is an HTTP/1.1 (or other) client on an h2c prior-knowledge listener.
      // Push the peeked bytes back so the HTTP/1.1 worker sees a complete, unmodified request stream.
      pushback.push(peek, 0, n);
      return new HTTP1Worker(socket, configuration, context, instrumenter, listener, throughput, pushback);
    }

    return new HTTP1Worker(socket, configuration, context, instrumenter, listener, throughput);
  }

  private ProtocolSelector() {
  }
}

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
      // Preface did not match: h2c prior-knowledge requires the HTTP/2 preface.
      // RFC 9113 §3.5 — emit SETTINGS + GOAWAY(PROTOCOL_ERROR) before closing so the peer can observe the error.
      sendH2cInvalidPrefaceError(socket);
      return new ClosedConnection(socket);
    }

    return new HTTP1Worker(socket, configuration, context, instrumenter, listener, throughput);
  }

  /**
   * Writes a minimal HTTP/2 SETTINGS frame followed by GOAWAY(PROTOCOL_ERROR) directly to the socket output stream.
   * Used when a client connects to an h2c prior-knowledge endpoint but sends an invalid connection preface.
   * RFC 9113 §3.5 requires the server to emit a GOAWAY before closing.
   */
  private static void sendH2cInvalidPrefaceError(Socket socket) {
    try {
      OutputStream out = socket.getOutputStream();
      // Empty SETTINGS frame: length=0, type=4, flags=0, stream_id=0.
      out.write(new byte[]{0, 0, 0, 4, 0, 0, 0, 0, 0});
      // GOAWAY frame: length=8, type=7, flags=0, stream_id=0, lastStreamId=0, errorCode=PROTOCOL_ERROR(1).
      out.write(new byte[]{0, 0, 8, 7, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1});
      out.flush();
    } catch (IOException ignore) {
      // Best-effort: if the peer already closed, suppress the write error.
    }
  }

  private ProtocolSelector() {
  }

  /**
   * A no-op {@link ClientConnection} returned after the connection has already been handled (e.g., error response
   * sent synchronously in {@link ProtocolSelector#select}). Its {@code run()} method closes the socket and returns.
   */
  private record ClosedConnection(Socket socket) implements ClientConnection, Runnable {
    @Override
    public long getHandledRequests() { return 0; }

    @Override
    public Socket getSocket() { return socket; }

    @Override
    public long getStartInstant() { return System.currentTimeMillis(); }

    @Override
    public ClientConnection.State state() { return ClientConnection.State.Read; }

    @Override
    public void run() {
      // Graceful teardown: shut down the output side (FIN) then drain input to avoid TCP RST.
      // SSLSocket.shutdownOutput() is not supported — suppress all exceptions here.
      try {
        socket.shutdownOutput();
      } catch (Exception ignore) {
      }
      try {
        socket.setSoTimeout(500);
        socket.getInputStream().skip(Long.MAX_VALUE);
      } catch (IOException ignore) {
      }
      try {
        socket.close();
      } catch (IOException ignore) {
      }
    }
  }
}

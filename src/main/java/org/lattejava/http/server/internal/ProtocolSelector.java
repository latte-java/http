/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

import java.lang.System.Logger.Level;
import javax.net.ssl.SSLSocket;

import org.lattejava.http.io.PushbackInputStream;
import org.lattejava.http.server.internal.h1.*;
import org.lattejava.http.server.internal.h2.*;

/**
 * Dispatches an accepted connection to either {@link HTTP1Connection} or {@link HTTP2Connection} by reconciling two
 * independent signals:
 * <ol>
 *   <li>the protocol the connection <em>negotiated</em> — {@code h2} or {@code h1} from TLS-ALPN, or {@code unknown}
 *       on a cleartext h2c prior-knowledge listener (which has no negotiation and permits either); and</li>
 *   <li>the protocol the client actually <em>spoke</em> — {@code h2} when the first bytes are the HTTP/2 connection
 *       preface, {@code h1} otherwise.</li>
 * </ol>
 * The connection is honored only when negotiation did not pin a protocol ({@code unknown}) or pinned the one that was
 * spoken. A client that negotiates one protocol and speaks another is closed — bad clients are not honored, and a
 * client that did not send a valid HTTP/2 preface is, by definition, not using HTTP/2 (RFC 9113 §3.4 permits closing
 * without a GOAWAY in that case).
 *
 * <p>Because the preface is fully consumed here before {@link HTTP2Connection} is constructed, that class never reads
 * or validates the preface itself.
 *
 * @author Daniel DeGroff
 */
public class ProtocolSelector {
  private static final System.Logger logger = System.getLogger(ProtocolSelector.class.getName());

  /**
   * Selects the appropriate connection handler for the given socket, or closes the socket and returns {@code null} when
   * the negotiated and spoken protocols disagree.
   *
   * @param socket        the accepted client socket
   * @param configuration the server configuration
   * @param context       the server context
   * @param instrumenter  the instrumenter, may be null
   * @param listener      the listener configuration that accepted the connection
   * @param throughput    the per-connection throughput tracker
   * @return a {@link HTTPConnection} (also a {@link Runnable}) ready to be started, or {@code null} if the connection
   *     was closed because the client's negotiated and spoken protocols disagreed
   * @throws IOException if the socket or TLS handshake fails before dispatch
   */
  public static HTTPConnection select(Socket socket, HTTPServerConfiguration configuration, HTTPContext context,
                                      Instrumenter instrumenter, HTTPListenerConfiguration listener,
                                      Throughput throughput) throws IOException {
    // 1. Determine the protocol the connection committed to during negotiation.
    Version negotiated;
    if (socket instanceof SSLSocket sslSocket) {
      // Configuring ALPN and forcing the handshake both block, so they run here — on the per-connection virtual
      // thread (ConnectionDispatcher), never on the accept thread. h2 over TLS requires ALPN, so the absence of an
      // "h2" selection (null, "", or "http/1.1") pins HTTP/1.1.
      SecurityTools.configureALPN(sslSocket, listener);
      sslSocket.startHandshake();
      negotiated = "h2".equals(sslSocket.getApplicationProtocol()) ? Version.h2 : Version.h1;
    } else {
      // Cleartext has no ALPN. An h2c prior-knowledge listener permits either protocol — the preface decides — while
      // any other cleartext listener is HTTP/1.1 only.
      negotiated = listener.isH2cPriorKnowledgeEnabled() ? Version.unknown : Version.h1;
    }

    // 2. Sniff the first bytes for the HTTP/2 connection preface. Reading runs through throughput accounting (so a
    // stalled sniff is bounded by the socket SO_TIMEOUT) and pushes non-preface bytes back for the HTTP/1.1 worker.
    var pushback = new PushbackInputStream(new ThroughputInputStream(socket.getInputStream(), throughput), instrumenter);
    Version spoken = sniff(pushback);

    // 3. Honor the connection only when negotiation left the protocol open or agrees with what was spoken.
    if (negotiated == Version.unknown || negotiated == spoken) {
      if (spoken == Version.h2) {
        return new HTTP2Connection(socket, configuration, context, instrumenter, listener, throughput, pushback);
      }
      return new HTTP1Connection(socket, configuration, context, instrumenter, listener, throughput, pushback);
    }

    // Negotiated one protocol, spoke another — a broken or malicious client. Drop it.
    logger.log(Level.DEBUG, "Closing connection: client negotiated [{0}] but spoke [{1}].", negotiated, spoken);
    socket.close();
    return null;
  }

  /**
   * Reads up to the 24-byte HTTP/2 connection preface from {@code pushback}, comparing byte-for-byte as it goes.
   * Returns {@link Version#h2} only when every preface byte matches (and is therefore consumed). On the first
   * mismatching byte — or a read timeout or EOF before the preface completes — the bytes read so far are pushed back so
   * the HTTP/1.1 worker sees an unmodified stream, and {@link Version#h1} is returned. Short-circuiting on the first
   * mismatch keeps the common HTTP/1.1 case to a single small read rather than blocking for a full 24 bytes.
   */
  private static Version sniff(PushbackInputStream pushback) throws IOException {
    byte[] peek = new byte[HTTPValues.ControlBytes.HTTP2Preface.length];
    int total = 0;
    while (total < HTTPValues.ControlBytes.HTTP2Preface.length) {
      int read;
      try {
        read = pushback.read(peek, total, HTTPValues.ControlBytes.HTTP2Preface.length - total);
      } catch (SocketTimeoutException timeout) {
        // A slow or silent client never completed the preface within the initial-read timeout. Treat it as HTTP/1.1
        // and let that worker's own preamble parser apply its timeout.
        if (total > 0) {
          pushback.push(peek, 0, total);
        }
        return Version.h1;
      }

      if (read == -1) {
        // EOF before the preface completed — not HTTP/2. Hand any partial bytes to the HTTP/1.1 worker.
        if (total > 0) {
          pushback.push(peek, 0, total);
        }
        return Version.h1;
      }

      for (int i = total; i < total + read; i++) {
        if (peek[i] != HTTPValues.ControlBytes.HTTP2Preface[i]) {
          pushback.push(peek, 0, total + read);
          return Version.h1;
        }
      }
      total += read;
    }

    return Version.h2;
  }

  private ProtocolSelector() {
  }

  enum Version {
    h1,
    h2,
    unknown
  }
}

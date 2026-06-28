/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * Protocol-specific seam for {@link org.lattejava.http.server.io.HTTPOutputStream}. The shared output stream owns the
 * commit lifecycle, body suppression, and compression; it delegates header emission and body framing to a per-response
 * implementation of this interface — {@code HTTP1OutputProtocol} for HTTP/1.1 and {@code HTTP2OutputProtocol} for
 * HTTP/2.
 *
 * @author Brian Pontarelli
 */
public interface HTTPOutputProtocol {
  /**
   * Lazily emits the response headers and returns the framing sink for the body. Called once, from
   * {@code HTTPOutputStream.commit()}, after the shared code has applied the compression decision (Content-Encoding /
   * Vary set and Content-Length removed when compressing).
   *
   * @param closing     true when invoked from {@code close()} before any body byte was written.
   * @param suppressBody true for a HEAD request (framing headers mirror a GET, but no body bytes follow).
   * @return the body sink that the (possibly gzip-wrapped) body bytes are written to. Never null.
   */
  OutputStream commitHeaders(boolean closing, boolean suppressBody) throws IOException;

  /**
   * Emits any trailers after the body sink has been closed. Symmetric across protocols — each writes its own trailer
   * bytes here (HTTP/1.1: the chunk terminator plus trailer fields; HTTP/2: a HEADERS + END_STREAM frame).
   */
  void commitTrailers() throws IOException;

  /**
   * Forces buffered bytes out to the client (HTTP/1.1: flushes the socket buffer; HTTP/2: no-op — frames are already
   * enqueued to the writer).
   */
  void forceFlush() throws IOException;

  /**
   * Signals that trailers will follow, before the body sink is closed. HTTP/2 only — the END_STREAM flag rides on the
   * final DATA frame, so the decision must precede the body close. HTTP/1.1 defers everything to
   * {@link #commitTrailers()} and implements this as a no-op.
   */
  void prepareTrailers() throws IOException;

  /**
   * @return true once any byte (or, for HTTP/2, the response HEADERS frame) has been sent to the client, after which
   *     the response can no longer be reset.
   */
  boolean wroteToClient();

  /**
   * Resets pre-commit framing state for HTTP/1.1 keep-alive reuse (HTTP/2 reuses nothing and implements this as a
   * no-op).
   */
  default void reset() {
  }
}

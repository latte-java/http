/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

/**
 * The outcome of handling one HTTP/2 frame. Returned by every {@link HTTP2FrameHandler}; consumed only by the
 * {@code HTTP2Connection} dispatch loop, which is the single place error frames are emitted:
 * {@link ConnectionError} becomes a GOAWAY and ends the connection, {@link StreamError} becomes an RST_STREAM and the
 * loop continues, {@link Shutdown} exits the loop cleanly (peer GOAWAY), and {@link Ok} continues.
 */
public sealed interface HTTP2Result {
  HTTP2Result OK = new Ok();

  HTTP2Result SHUTDOWN = new Shutdown();

  record ConnectionError(HTTP2ErrorCode code) implements HTTP2Result {
  }

  record Ok() implements HTTP2Result {
  }

  record Shutdown() implements HTTP2Result {
  }

  record StreamError(int streamId, HTTP2ErrorCode code) implements HTTP2Result {
  }
}

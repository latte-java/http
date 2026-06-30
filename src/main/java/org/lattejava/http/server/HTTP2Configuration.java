/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server;

import module java.base;

/**
 * HTTP/2-specific server configuration: HPACK table size, flow-control window, stream concurrency, frame size, the
 * per-stream handler read timeout, and the frame-flood rate limits. Instantiated with defaults by
 * {@link HTTPServerConfiguration} and mutated through {@link HTTPServerConfiguration#withHTTP2(Consumer)}.
 * <p>
 * The maximum header-list size is not configured here; HTTP/2 derives it from the shared
 * {@link HTTPServerConfiguration#getMaxRequestHeaderSize()}.
 */
@SuppressWarnings("UnusedReturnValue")
public class HTTP2Configuration {
  private final HTTP2RateLimits rateLimits = new HTTP2RateLimits();

  private Duration handlerReadTimeout = Duration.ofSeconds(10);

  private int headerTableSize = 4096;

  private int initialWindowSize = 65535;

  private int maxConcurrentStreams = 100;

  private int maxFrameSize = 16384;

  /**
   * @return The duration the reader waits for a handler to drain a DATA frame from its per-stream pipe before
   *     cancelling the stream with RST_STREAM(CANCEL). Defaults to 10 seconds.
   */
  public Duration getHandlerReadTimeout() {
    return handlerReadTimeout;
  }

  /**
   * @return The HPACK header-table size advertised in the initial SETTINGS frame. Defaults to 4096.
   */
  public int getHeaderTableSize() {
    return headerTableSize;
  }

  /**
   * @return The initial stream-level flow-control window advertised to the client. Defaults to 65535.
   */
  public int getInitialWindowSize() {
    return initialWindowSize;
  }

  /**
   * @return The maximum number of concurrent streams allowed per connection. Defaults to 100.
   */
  public int getMaxConcurrentStreams() {
    return maxConcurrentStreams;
  }

  /**
   * @return The maximum frame payload the server will receive. Defaults to 16384.
   */
  public int getMaxFrameSize() {
    return maxFrameSize;
  }

  /**
   * @return The frame-flood rate limits. Never null.
   */
  public HTTP2RateLimits getRateLimits() {
    return rateLimits;
  }

  /**
   * Sets the per-stream handler read timeout. Cannot be null.
   *
   * @param d The duration.
   * @return This.
   */
  public HTTP2Configuration withHandlerReadTimeout(Duration d) {
    Objects.requireNonNull(d, "You cannot set the HTTP/2 handler read timeout to null");
    this.handlerReadTimeout = d;
    return this;
  }

  /**
   * Sets the HPACK header-table size advertised in the initial SETTINGS frame.
   *
   * @param size The header table size in bytes.
   * @return This.
   */
  public HTTP2Configuration withHeaderTableSize(int size) {
    if (size < 0) {
      throw new IllegalArgumentException("The HTTP/2 header table size must not be negative. Got [" + size + "]");
    }

    this.headerTableSize = size;
    return this;
  }

  /**
   * Sets the initial stream-level flow-control window advertised to the client.
   *
   * @param size The initial window size in bytes.
   * @return This.
   */
  public HTTP2Configuration withInitialWindowSize(int size) {
    if (size < 0) {
      throw new IllegalArgumentException("The HTTP/2 initial window size must not be negative. Got [" + size + "]");
    }

    this.initialWindowSize = size;
    return this;
  }

  /**
   * Sets the maximum number of concurrent streams allowed per connection.
   *
   * @param n The maximum number of concurrent streams.
   * @return This.
   */
  public HTTP2Configuration withMaxConcurrentStreams(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("The HTTP/2 max concurrent streams must not be negative. Got [" + n + "]");
    }

    this.maxConcurrentStreams = n;
    return this;
  }

  /**
   * Sets the maximum frame payload the server will receive. Must be in the range [16384, 16777215] per RFC 9113
   * §6.5.2.
   *
   * @param size The maximum frame size in bytes.
   * @return This.
   */
  public HTTP2Configuration withMaxFrameSize(int size) {
    if (size < 16384 || size > 16777215) {
      throw new IllegalArgumentException("The HTTP/2 max frame size [" + size + "] must be in the range [16384, 16777215].");
    }

    this.maxFrameSize = size;
    return this;
  }

  /**
   * Configures the frame-flood rate limits.
   *
   * @param consumer A consumer that receives the always-present {@link HTTP2RateLimits} to mutate.
   * @return This.
   */
  public HTTP2Configuration withRateLimits(Consumer<HTTP2RateLimits> consumer) {
    Objects.requireNonNull(consumer, "You cannot pass a null HTTP/2 rate limits consumer");
    consumer.accept(rateLimits);
    return this;
  }
}

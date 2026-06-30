/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server;

import module java.base;
import module org.lattejava.http;

/**
 * HTTP/1.x-specific server configuration: chunked transfer-encoding buffers and limits, keep-alive behavior, and the
 * {@code Expect: 100-continue} validator. Instantiated with defaults by {@link HTTPServerConfiguration} and mutated
 * through {@link HTTPServerConfiguration#withHTTP1(java.util.function.Consumer)}.
 */
@SuppressWarnings("UnusedReturnValue")
public class HTTP1Configuration {
  private int chunkedBufferSize = 4 * 1024; // 4 Kilobytes

  private ExpectValidator expectValidator = new AlwaysContinueExpectValidator();

  private Duration keepAliveTimeoutDuration = Duration.ofSeconds(20);

  private int maxRequestChunkSize = 1024 * 1024; // 1 Megabyte

  private int maxRequestsPerConnection = 100_000; // 100,000

  private int maxResponseChunkSize = 16 * 1024; // 16 Kilobytes

  /**
   * @return The buffer size used to decode a request body sent with {@code chunked} transfer-encoding. Defaults to 4
   *     Kilobytes.
   */
  public int getChunkedBufferSize() {
    return chunkedBufferSize;
  }

  /**
   * @return The validator invoked when a client sends {@code Expect: 100-continue}. Never null.
   */
  public ExpectValidator getExpectValidator() {
    return expectValidator;
  }

  /**
   * @return The idle timeout between keep-alive requests. Defaults to 20 seconds.
   */
  public Duration getKeepAliveTimeoutDuration() {
    return keepAliveTimeoutDuration;
  }

  /**
   * @return The maximum size of a single chunk in a {@code chunked} request body. Defaults to 1 Megabyte.
   */
  public int getMaxRequestChunkSize() {
    return maxRequestChunkSize;
  }

  /**
   * @return The maximum number of requests handled on one keep-alive connection. Defaults to 100,000.
   */
  public int getMaxRequestsPerConnection() {
    return maxRequestsPerConnection;
  }

  /**
   * @return The maximum chunk size used when writing a {@code chunked} response. Defaults to 16 Kilobytes.
   */
  public int getMaxResponseChunkSize() {
    return maxResponseChunkSize;
  }

  /**
   * Sets the buffer size used to decode a {@code chunked} request body.
   *
   * @param chunkedBufferSize The buffer size in bytes.
   * @return This.
   */
  public HTTP1Configuration withChunkedBufferSize(int chunkedBufferSize) {
    if (chunkedBufferSize <= 1024) {
      throw new IllegalArgumentException("The chunked buffer size must be greater than or equal to 1024 bytes");
    }

    this.chunkedBufferSize = chunkedBufferSize;
    return this;
  }

  /**
   * Sets the {@code Expect: 100-continue} validator. Must not be null.
   *
   * @param validator The validator.
   * @return This.
   */
  public HTTP1Configuration withExpectValidator(ExpectValidator validator) {
    Objects.requireNonNull(validator, "You cannot set the expect validator to null");
    this.expectValidator = validator;
    return this;
  }

  /**
   * Sets the idle timeout between keep-alive requests.
   *
   * @param duration The duration.
   * @return This.
   */
  public HTTP1Configuration withKeepAliveTimeoutDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the keep-alive timeout duration to null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The keep-alive timeout duration must be grater than 0");
    }

    this.keepAliveTimeoutDuration = duration;
    return this;
  }

  /**
   * Sets the maximum size of a single chunk in a {@code chunked} request body.
   *
   * @param maxRequestChunkSize The maximum per-chunk size in bytes.
   * @return This.
   */
  public HTTP1Configuration withMaxRequestChunkSize(int maxRequestChunkSize) {
    if (maxRequestChunkSize < 1 || maxRequestChunkSize > 0x0FFFFFFF) {
      throw new IllegalArgumentException("The maximum request chunk size must be between 1 and [" + 0x0FFFFFFF + "] (~256 Megabytes).");
    }

    this.maxRequestChunkSize = maxRequestChunkSize;
    return this;
  }

  /**
   * Sets the maximum number of requests handled on one keep-alive connection.
   *
   * @param maxRequestsPerConnection The maximum request count.
   * @return This.
   */
  public HTTP1Configuration withMaxRequestsPerConnection(int maxRequestsPerConnection) {
    if (maxRequestsPerConnection < 10) {
      throw new IllegalArgumentException("The maximum number of requests per connection must be greater than or equal to 10");
    }

    this.maxRequestsPerConnection = maxRequestsPerConnection;
    return this;
  }

  /**
   * Sets the maximum chunk size used when writing a {@code chunked} response.
   *
   * @param maxResponseChunkSize The size in bytes.
   * @return This.
   */
  public HTTP1Configuration withMaxResponseChunkSize(int maxResponseChunkSize) {
    if (maxResponseChunkSize < 128) {
      throw new IllegalArgumentException("The maximum chunk size must be greater than or equal to 128.");
    }

    this.maxResponseChunkSize = maxResponseChunkSize;
    return this;
  }
}

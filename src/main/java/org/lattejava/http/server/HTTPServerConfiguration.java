/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.lattejava.http.server;

import module java.base;
import module org.lattejava.http;

/**
 * The HTTP Server configuration.
 *
 * @author Brian Pontarelli
 */
public class HTTPServerConfiguration implements Configurable<HTTPServerConfiguration> {
  public static final Map<String, Long> DefaultMaxRequestSizes = Map.of(
      "*", 128L * 1024 * 1024,                                  // 128 Megabytes
      "application/x-www-form-urlencoded", 10L * 1024 * 1024    // 10 Megabytes
  );

  private final HTTP1Configuration http1 = new HTTP1Configuration();

  private final HTTP2Configuration http2 = new HTTP2Configuration();

  private final List<HTTPListenerConfiguration> listeners = new ArrayList<>();

  private final Map<String, Long> maxRequestBodySize = new HashMap<>(DefaultMaxRequestSizes);

  private Path baseDir = Path.of("");

  private boolean compressByDefault = true;

  private String contextPath = "";

  private HTTPHandler handler;

  private Duration initialReadTimeoutDuration = Duration.ofSeconds(2);

  private Instrumenter instrumenter;

  private Duration keepAliveTimeoutDuration = Duration.ofSeconds(20);

  private int maxBytesToDrain = 256 * 1024; // 256 Kilobytes

  private Duration maxConnectionAgeDuration; // null means unlimited

  private int maxPendingSocketConnections = 4096;

  private int maxRequestHeaderSize = 128 * 1024; // 128 Kilobytes

  private int maxRequestsPerConnection = 100_000; // 100,000

  private long minimumReadThroughput = 16 * 1024; // 16 Kilobytes/second

  private long minimumWriteThroughput = 16 * 1024; // 16 Kilobytes/second

  private MultipartConfiguration multipartStreamConfiguration = new MultipartConfiguration();

  private Duration processingTimeoutDuration = Duration.ofSeconds(10);

  private Duration readThroughputCalculationDelayDuration = Duration.ofSeconds(5);

  private int requestBufferSize = 16 * 1024; // 16 Kilobytes

  private int responseBufferSize = 64 * 1024; // 64 Kilobytes

  private boolean sendDateHeader = true;

  private Duration shutdownDuration = Duration.ofSeconds(10);

  private HTTPUnexpectedExceptionHandler unexpectedExceptionHandler = new DefaultHTTPUnexpectedExceptionHandler();

  private Duration writeThroughputCalculationDelayDuration = Duration.ofSeconds(5);

  /**
   * @return This.
   */
  @Override
  public HTTPServerConfiguration configuration() {
    return this;
  }

  /**
   * @return The base dir for the entire server. This can be used to calculate files from as needed.
   */
  public Path getBaseDir() {
    return baseDir;
  }

  /**
   * @return The context page that the entire server serves requests under or null.
   */
  public String getContextPath() {
    return contextPath;
  }

  /**
   * @return The HTTP/1.x-specific configuration. Never null.
   */
  public HTTP1Configuration getHTTP1Configuration() {
    return http1;
  }

  /**
   * @return The HTTP/2-specific configuration. Never null.
   */
  public HTTP2Configuration getHTTP2Configuration() {
    return http2;
  }

  /**
   * @return The HTTP handler for this server. Cannot be null and is required.
   */
  public HTTPHandler getHandler() {
    return handler;
  }

  /**
   * @return The timeout between a socket being accepted by the server and the first byte being read. This is distinct
   *     and separate from the timeout for subsequent reads after the connection has been "kept alive".
   */
  public Duration getInitialReadTimeoutDuration() {
    return initialReadTimeoutDuration;
  }

  /**
   * @return The instrumenter or null.
   */
  public Instrumenter getInstrumenter() {
    return instrumenter;
  }

  /**
   * @return The idle timeout between requests on a persistent connection, shared by HTTP/1.1 keep-alive and the
   *     HTTP/2 zero-stream idle deadline. Defaults to 20 seconds.
   */
  public Duration getKeepAliveTimeoutDuration() {
    return keepAliveTimeoutDuration;
  }

  /**
   * @return All configured listeners (if any) or an empty list.
   */
  public List<HTTPListenerConfiguration> getListeners() {
    return listeners;
  }

  /**
   * When using keep-alive, this configuration represents the maximum number of bytes to drain from the InputStream when
   * the request handler did not read all available bytes. This is done to drain the InputStream, attempting to reach
   * the end of the request in order to prepare for the next request.
   * <p>
   * If you are not using keep-alive, this configuration will not be utilized.
   * <p>
   * When this configured limit is reached, the socket will be closed. This would be equivalent to adding Connection:
   * close to the request. Setting this limit too low could cause connections not to be re-used, setting this limit too
   * high simply means that the server will take more time to read more bytes from the client before being able to
   * re-use the connection.
   *
   * @return The maximum number of bytes to drain from the InputStream when the request handler did not read all
   *     available bytes. Defaults to 256 Kilobytes.
   */
  public int getMaxBytesToDrain() {
    return maxBytesToDrain;
  }

  /**
   * @return The maximum age of a connection before it is gracefully closed, or null for unlimited. Defaults to null.
   */
  public Duration getMaxConnectionAgeDuration() {
    return maxConnectionAgeDuration;
  }

  /**
   * The maximum number of pending socket connections per HTTP listener.
   * <p>
   * This number represents how many pending socket connections are allowed to queue before they are rejected. Once the
   * connection is accepted by the server socket, a client socket is created and handed to an HTTP Worker. This queue
   * length only needs to be large enough to buffer the incoming requests as fast as we can accept them and hand them to
   * a worker.
   *
   * @return The maximum number of pending socket connections per HTTP listener. Defaults to 250.
   */
  public int getMaxPendingSocketConnections() {
    return maxPendingSocketConnections;
  }

  /**
   * The map that specifies the maximum size in bytes of the HTTP request body by Content-Type. This configuration
   * excludes the size of the HTTP request header.
   * <p>
   * The returned map is keyed by Content-Type, and will contain a default value identified by '*', and may optionally
   * contain a content type value with a wild card '*' as the subtype. For example, 'application/*' will match all
   * subtypes of an application/ content type.
   *
   * @return the map keyed by Content-Type indicating the maximum size in bytes of the HTTP request body.  Defaults to
   *     128 Megabytes as a default, and 10 Megabytes for application/x-www-form-urlencoded.
   */
  public Map<String, Long> getMaxRequestBodySize() {
    return maxRequestBodySize;
  }

  /**
   * @return the maximum size of the HTTP request header in bytes. This configuration does not affect the HTTP response
   *     header. Defaults to 128 Kilobytes.
   */
  public int getMaxRequestHeaderSize() {
    return maxRequestHeaderSize;
  }

  /**
   * @return The maximum number of requests handled on one connection. Defaults to 100,000.
   */
  public int getMaxRequestsPerConnection() {
    return maxRequestsPerConnection;
  }

  /**
   * This configuration is the minimum number of bytes per second that a client must send a request to the server before
   * the server closes the connection.
   * <p>
   * A value of -1 indicates the minimum read throughput limitation has been disabled.
   *
   * @return The minimum throughput for any connection with the server in bytes per second.
   */
  public long getMinimumReadThroughput() {
    return minimumReadThroughput;
  }

  /**
   * This configuration is the minimum number of bytes per second that a client must read the response from the server
   * before the server closes the connection.
   * <p>
   * A value of -1 indicates the minimum write throughput limitation has been disabled.
   *
   * @return The minimum throughput for any connection with the server in bytes per second.
   */
  public long getMinimumWriteThroughput() {
    return minimumWriteThroughput;
  }

  /**
   * @return the multipart configuration.
   */
  public MultipartConfiguration getMultipartConfiguration() {
    return multipartStreamConfiguration;
  }

  /**
   * @return The timeout between when the request has been fully read and the first byte is written. This provides the
   *     worker thread to perform work before it begins to write. This timeout should be relatively short depending on
   *     how long you want the browser/client to wait before the response comes back. Defaults to 10 seconds.
   */
  public Duration getProcessingTimeoutDuration() {
    return processingTimeoutDuration;
  }

  /**
   * @return the duration that will be used to delay the calculation and enforcement of the minimum read throughput.
   */
  public Duration getReadThroughputCalculationDelay() {
    return readThroughputCalculationDelayDuration;
  }

  /**
   * @return The size of the buffer used to read the request. Defaults to 16 Kilobytes
   */
  public int getRequestBufferSize() {
    return requestBufferSize;
  }

  /**
   * @return The size of the buffer used to store the response. This allows the server to handle exceptions and errors
   *     without writing back a 200 response that is actually an error. Defaults to 64 Kilobytes.
   */
  public int getResponseBufferSize() {
    return responseBufferSize;
  }

  /**
   * @return The duration that the server will wait while worker threads complete before forcibly shutting itself down.
   *     Defaults to 10 seconds.
   */
  public Duration getShutdownDuration() {
    return shutdownDuration;
  }

  /**
   * @return The HTTP unexpected exception handler for this server. Never null.
   */
  public HTTPUnexpectedExceptionHandler getUnexpectedExceptionHandler() {
    return unexpectedExceptionHandler;
  }

  /**
   * @return the duration that will be used to delay the calculation and enforcement of the minimum write throughput.
   */
  public Duration getWriteThroughputCalculationDelay() {
    return writeThroughputCalculationDelayDuration;
  }

  /**
   * @return Whether all responses are compressed by default. Defaults to true.
   */
  public boolean isCompressByDefault() {
    return compressByDefault;
  }

  /**
   * @return Whether the server auto-emits an RFC 1123 {@code Date} header on every response. Defaults to true per RFC
   *     9110 §6.6.1.
   */
  public boolean isSendDateHeader() {
    return sendDateHeader;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withBaseDir(Path baseDir) {
    Objects.requireNonNull(baseDir, "You cannot set the base dir to null");
    this.baseDir = baseDir;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withCompressByDefault(boolean compressByDefault) {
    this.compressByDefault = compressByDefault;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withContextPath(String contextPath) {
    this.contextPath = contextPath;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withHTTP1(Consumer<HTTP1Configuration> consumer) {
    Objects.requireNonNull(consumer, "You cannot pass a null HTTP/1 configuration consumer");
    consumer.accept(http1);
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withHTTP2(Consumer<HTTP2Configuration> consumer) {
    Objects.requireNonNull(consumer, "You cannot pass a null HTTP/2 configuration consumer");
    consumer.accept(http2);
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withHandler(HTTPHandler handler) {
    Objects.requireNonNull(handler, "You cannot set the handler to null");
    this.handler = handler;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withInitialReadTimeout(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the client read timeout duration to null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The client timeout duration must be greater than 0");
    }

    this.initialReadTimeoutDuration = duration;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withInstrumenter(Instrumenter instrumenter) {
    this.instrumenter = instrumenter;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withKeepAliveTimeoutDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the keep-alive timeout duration to null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The keep-alive timeout duration must be grater than 0");
    }

    this.keepAliveTimeoutDuration = duration;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withListener(HTTPListenerConfiguration listener) {
    Objects.requireNonNull(listener, "You cannot add a null HTTPListenerConfiguration");
    this.listeners.add(listener);
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withListeners(HTTPListenerConfiguration... listeners) {
    Objects.requireNonNull(listeners, "You cannot add a null array of HTTPListenerConfigurations");
    for (HTTPListenerConfiguration listener : listeners) {
      Objects.requireNonNull(listener, "You cannot add a null HTTPListenerConfiguration");
      this.listeners.add(listener);
    }
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMaxConnectionAgeDuration(Duration duration) {
    if (duration != null && (duration.isZero() || duration.isNegative())) {
      throw new IllegalArgumentException("The max connection age duration must be greater than 0");
    }

    this.maxConnectionAgeDuration = duration;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMaxPendingSocketConnections(int maxPendingSocketConnections) {
    if (maxPendingSocketConnections < 25) {
      throw new IllegalArgumentException("The minimum pending socket connections must be greater than or equal to 25");
    }

    this.maxPendingSocketConnections = maxPendingSocketConnections;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMaxRequestBodySize(Map<String, Long> maxRequestBodySize) {
    Objects.requireNonNull(maxRequestBodySize, "You cannot set the maximum request body size map to null");
    for (String contentType : maxRequestBodySize.keySet()) {
      Objects.requireNonNull(contentType, "You cannot specify a null value for content type");
      Long maxSize = maxRequestBodySize.get(contentType);
      Objects.requireNonNull(maxSize, "You may not specify a null value for the maximum request body size");
      if (maxSize != -1 && maxSize <= 0) {
        throw new IllegalArgumentException("The maximum request body size must be greater than 0 for [" + contentType + "]. Set to -1 to disable this limitation.");
      }
    }

    this.maxRequestBodySize.clear();
    // Add back a default to ensure we always have a fallback, can still be modified by the incoming configuration.
    this.maxRequestBodySize.put("*", DefaultMaxRequestSizes.get("*"));
    // Store lower case keys
    maxRequestBodySize.forEach((k, v) -> this.maxRequestBodySize.put(k.toLowerCase(Locale.ROOT), v));
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMaxRequestHeaderSize(int maxRequestHeaderSize) {
    if (maxRequestHeaderSize != -1 && maxRequestHeaderSize <= 0) {
      throw new IllegalArgumentException("The maximum request header size must be greater than 0. Set to -1 to disable this limitation.");
    }

    this.maxRequestHeaderSize = maxRequestHeaderSize;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMaxRequestsPerConnection(int maxRequestsPerConnection) {
    if (maxRequestsPerConnection < 10) {
      throw new IllegalArgumentException("The maximum number of requests per connection must be greater than or equal to 10");
    }

    this.maxRequestsPerConnection = maxRequestsPerConnection;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMaximumBytesToDrain(int maxBytesToDrain) {
    if (maxBytesToDrain < 1024 || maxBytesToDrain > 256 * 1024 * 1024) {
      throw new IllegalArgumentException("The maximum bytes to drain must be greater than or equal to 1024 and less than or equal to 268,435,456 (256 Megabytes)");
    }

    this.maxBytesToDrain = maxBytesToDrain;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withMinimumReadThroughput(long bytesPerSecond) {
    if (bytesPerSecond != -1 && bytesPerSecond < 512) {
      throw new IllegalArgumentException("The minimum bytes per second must be greater than 512. Note that the theoretical maximum transmission speed of a 28.8k is 28,800 bits /second, or 3,600 bytes /second. Maybe consider requiring the read throughput to be faster than a 28.8k modem. Set this to -1 to disable this check.");
    }

    this.minimumReadThroughput = bytesPerSecond;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  public HTTPServerConfiguration withMinimumWriteThroughput(long bytesPerSecond) {
    if (bytesPerSecond != -1 && bytesPerSecond < 512) {
      throw new IllegalArgumentException("The minimum bytes per second must be greater than 512. Note that the theoretical maximum transmission speed of a 28.8k is 28,800 bits /second, or 3,600 bytes /second. Maybe consider requiring the write throughput to be faster than a 28.8k modem. Set this to -1 to disable this check.");
    }

    this.minimumWriteThroughput = bytesPerSecond;
    return this;
  }

  @Override
  public HTTPServerConfiguration withMultipartConfiguration(MultipartConfiguration multipartStreamConfiguration) {
    Objects.requireNonNull(multipartStreamConfiguration, "You cannot set the multipart stream configuration to null");
    this.multipartStreamConfiguration = multipartStreamConfiguration;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withProcessingTimeoutDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the processing timeout duration to null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The processing timeout duration must be grater than 0");
    }

    this.processingTimeoutDuration = duration;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withReadThroughputCalculationDelayDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the read throughput delay duration to null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The read throughput delay duration must be grater than 0");
    }

    this.readThroughputCalculationDelayDuration = duration;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withRequestBufferSize(int requestBufferSize) {
    if (requestBufferSize <= 0) {
      throw new IllegalArgumentException("The request buffer size must be greater than 0");
    }

    this.requestBufferSize = requestBufferSize;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withResponseBufferSize(int responseBufferSize) {
    if (responseBufferSize != -1 && responseBufferSize <= 0) {
      throw new IllegalArgumentException("The response buffer size must be greater than 0. Set to -1 to disable buffering completely.");
    }

    this.responseBufferSize = responseBufferSize;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withSendDateHeader(boolean sendDateHeader) {
    this.sendDateHeader = sendDateHeader;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withShutdownDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the shutdown duration to null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The shutdown duration must be grater than 0");
    }

    this.shutdownDuration = duration;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withUnexpectedExceptionHandler(HTTPUnexpectedExceptionHandler unexpectedExceptionHandler) {
    Objects.requireNonNull(unexpectedExceptionHandler, "You cannot set the unexpected exception handler to null");
    this.unexpectedExceptionHandler = unexpectedExceptionHandler;
    return this;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public HTTPServerConfiguration withWriteThroughputCalculationDelayDuration(Duration duration) {
    Objects.requireNonNull(duration, "You cannot set the write throughput delay duration to null");
    if (duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("The write throughput delay duration must be grater than 0");
    }

    this.writeThroughputCalculationDelayDuration = duration;
    return this;
  }
}

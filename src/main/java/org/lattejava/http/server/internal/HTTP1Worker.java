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
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.ParseException;
import org.lattejava.http.io.PushbackInputStream;

/**
 * An HTTP worker that is a delegate Runnable to an {@link HTTPHandler}.
 *
 * @author Brian Pontarelli
 */
public class HTTP1Worker implements ClientConnection, Runnable {
  private final HTTPBuffers buffers;

  private final HTTPServerConfiguration configuration;

  private final HTTPContext context;

  private final PushbackInputStream inputStream;

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listener;

  private final Logger logger;

  private final Socket socket;

  private final long startInstant;

  private final Throughput throughput;

  private long handledRequests;

  private volatile WorkerState workerState;

  public HTTP1Worker(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter,
                     HTTPListenerConfiguration listener, Throughput throughput) throws IOException {
    this.socket = socket;
    this.configuration = configuration;
    this.context = context;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.throughput = throughput;
    this.buffers = new HTTPBuffers(configuration);
    this.logger = configuration.getLoggerFactory().getLogger(HTTP1Worker.class);
    this.inputStream = new PushbackInputStream(new ThroughputInputStream(socket.getInputStream(), throughput), instrumenter);
    this.workerState = WorkerState.Read;
    this.startInstant = System.currentTimeMillis();
    logger.trace("[{}] Starting HTTP worker.", Thread.currentThread().threadId());
  }

  /**
   * Alternate constructor used by {@link ProtocolSelector} when the h2c prior-knowledge peek path has already consumed
   * bytes from the socket's InputStream and pushed them back into a pre-built {@link PushbackInputStream}. Passing the
   * stream directly avoids a second wrapping and ensures no peeked bytes are lost.
   */
  public HTTP1Worker(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter,
                     HTTPListenerConfiguration listener, Throughput throughput, PushbackInputStream inputStream) throws IOException {
    this.socket = socket;
    this.configuration = configuration;
    this.context = context;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.throughput = throughput;
    this.buffers = new HTTPBuffers(configuration);
    this.logger = configuration.getLoggerFactory().getLogger(HTTP1Worker.class);
    this.inputStream = inputStream;
    this.workerState = WorkerState.Read;
    this.startInstant = System.currentTimeMillis();
    logger.trace("[{}] Starting HTTP worker.", Thread.currentThread().threadId());
  }

  public long getHandledRequests() {
    return handledRequests;
  }

  public Socket getSocket() {
    return socket;
  }

  public long getStartInstant() {
    return startInstant;
  }

  @Override
  public void run() {
    HTTPInputStream httpInputStream;
    HTTPRequest request = null;
    HTTPResponse response = null;

    try {
      if (instrumenter != null) {
        instrumenter.workerStarted();
      }

      while (true) {
        logger.trace("[{}] Running HTTP worker. Block while we wait to read the preamble", Thread.currentThread().threadId());
        request = new HTTPRequest(context, configuration.getContextPath(), listener.getCertificate() != null ? "https" : "http", listener.getPort(), socket.getInetAddress().getHostAddress());

        // Create a deep copy of the MultipartConfiguration so that the request may optionally modify the configuration on a per-request basis.
        request.getMultiPartStreamProcessor().setMultipartConfiguration(new MultipartConfiguration(configuration.getMultipartConfiguration()));

        // Set up the output stream so that if we fail we have the opportunity to write a response that contains a status code.
        var throughputOutputStream = new ThroughputOutputStream(socket.getOutputStream(), throughput);
        response = new HTTPResponse();

        HTTPOutputStream outputStream = new HTTPOutputStream(configuration, request, request.getAcceptEncodings(), response, throughputOutputStream, buffers, () -> workerState = WorkerState.Write);
        response.setOutputStream(outputStream);

        // Not this line of code will block
        // - When a client is using Keep-Alive - we will loop and block here while we wait for the client to send us bytes.
        byte[] requestBuffer = buffers.requestBuffer();
        HTTPTools.parseRequestPreamble(inputStream, configuration.getMaxRequestHeaderSize(), request, requestBuffer, () -> workerState = WorkerState.Read);
        if (logger.isTraceEnabled()) {
          int availableBufferedBytes = inputStream.getAvailableBufferedBytesRemaining();
          if (availableBufferedBytes != 0) {
            logger.trace("[{}] Preamble parser had [{}] left over bytes. These will be used in the HTTPInputStream.", availableBufferedBytes);
          }
        }

        // Once we have performed an initial read, we can count this as a handled request.
        handledRequests++;
        if (instrumenter != null) {
          instrumenter.acceptedRequest();
        }

        int maximumContentLength = HTTPTools.getMaxRequestBodySize(request.getContentType(), configuration.getMaxRequestBodySize());
        httpInputStream = new HTTPInputStream(configuration, request, inputStream, maximumContentLength);
        request.setInputStream(httpInputStream);

        // Set the Connection response header as soon as possible
        // - This needs to occur after we have parsed the pre-amble so we can read the request headers
        response.setHeader(HTTPValues.Headers.Connection, request.isKeepAlive() ? HTTPValues.Connections.KeepAlive : HTTPValues.Connections.Close);

        // Ensure the preamble is valid
        Integer status = validatePreamble(request);
        if (status != null) {
          closeSocketOnError(response, status);
          return;
        }

        // h2c via Upgrade/101 (RFC 7540 §3.2 — back-compat path; RFC 9113 deprecates this in favor of prior-knowledge).
        boolean upgradeRequested = false;
        if (listener.isH2cUpgradeEnabled()) {
          String upgrade = request.getHeader("Upgrade");
          if (upgrade != null && upgrade.equalsIgnoreCase("h2c")) {
            // RFC 9113 §3.2 requires HTTP2-Settings to be present. The preamble parser drops headers with empty values, so
            // a null here may mean "present but empty" rather than truly absent. Treat both null and empty as an empty
            // settings payload — the practical effect is identical (no peer settings overrides), and rejecting empty
            // values would break the most common h2c upgrade pattern used by curl and other tooling.
            String h2settings = request.getHeader("HTTP2-Settings");
            byte[] settingsPayload;
            if (h2settings == null || h2settings.isBlank()) {
              settingsPayload = new byte[0];
            } else {
              try {
                settingsPayload = Base64.getUrlDecoder().decode(h2settings.strip());
              } catch (IllegalArgumentException e) {
                closeSocketOnError(response, Status.BadRequest);
                return;
              }
            }
            HTTP2Settings peerSettings = HTTP2Settings.defaults();
            peerSettings.applyPayload(settingsPayload);
            // TODO Plan E: the h2c spec says the original HTTP/1.1 request becomes implicit stream 1 in the new HTTP/2
            //   connection. We punt on that here — the simpler implementation drops the original request and lets the
            //   client re-send it as a proper HTTP/2 stream after the 101. Most h2c-Upgrade clients (curl --http2) do
            //   not send a request body in the upgrade request and re-send after receiving the 101.
            response.switchProtocols("h2c", Map.of(), s -> {
              try {
                new HTTP2Connection(s, configuration, context, instrumenter, listener, throughput, /*prefaceConsumed=*/false, /*serverSendsFirst=*/true).run();
              } catch (Exception e) {
                logger.debug("h2c upgrade handler ended", e);
              }
            });
            upgradeRequested = true;
          }
        }

        // Automatic HEAD handling: dispatch through GET logic but suppress body output. The HTTPRequest captured HEAD as the originalMethod on
        // the first setMethod call during preamble parsing, so isHeadRequest() remains true even after this rewrite.
        if (request.getMethod().is(HTTPMethod.HEAD)) {
          outputStream.setSuppressBody(true);
          request.setMethod(HTTPMethod.GET);
        }

        // Handle the Expect request header. RFC 9110 §10.1.1 — server MUST respond 417 to any expectation it does not support; we only support 100-continue.
        String expect = request.getHeader(HTTPValues.Headers.Expect);
        if (expect != null) {
          if (expect.equalsIgnoreCase(HTTPValues.Status.ContinueRequest)) {
            workerState = WorkerState.Write;

            boolean doContinue = handleExpectContinue(request);
            if (!doContinue) {
              // Note that the expectContinue code already wrote to the OutputStream, all we need to do is close the socket.
              closeSocketOnly(CloseSocketReason.Expected);
              return;
            }

            // Otherwise, transition the state to Read
            workerState = WorkerState.Read;
          } else {
            closeSocketOnError(response, HTTPValues.Status.ExpectationFailed);
            return;
          }
        }

        // RFC 9110 §6.6.1 — origin servers with a clock MUST emit a Date header. We populate it before invoking the handler so
        // the handler can override (set a different value) or suppress (call removeHeader) per-response without needing extra API.
        if (configuration.isSendDateHeader()) {
          response.setHeader(HTTPValues.Headers.Date, DateTools.currentHTTPDate());
        }

        // Transition to processing
        workerState = WorkerState.Process;
        logger.trace("[{}] Set state [{}]. Call the request handler.", Thread.currentThread().threadId(), workerState);
        try {
          if (!upgradeRequested) {
            configuration.getHandler().handle(request, response);
          }
          logger.trace("[{}] Handler completed successfully", Thread.currentThread().threadId());
        } finally {
          // Clean up temporary files if instructed to do so.
          // - Note that this is using the request scoped configuration. It is possible for the request handler to disable
          //   deletion of temporary files on a request basis.
          var multiPartProcessor = request.getMultiPartStreamProcessor();
          if (multiPartProcessor.getMultiPartConfiguration().isDeleteTemporaryFiles()) {
            var fileManager = multiPartProcessor.getMultipartFileManager();
            for (var file : fileManager.getTemporaryFiles()) {
              try {
                logger.debug("Delete temporary file [{}]", file);
                Files.deleteIfExists(file);
              } catch (Exception e) {
                logger.error("Unable to delete temporary file. [" + file + "]", e);
              }
            }
          }
        }

        // If the handler requested a protocol switch, emit the 101 preamble and hand off the socket.
        // Normal response writing is bypassed — the new protocol owns the socket from this point.
        if (response.isProtocolSwitchPending()) {
          String target = response.getSwitchProtocolsTarget();
          StringBuilder sb = new StringBuilder();
          sb.append("HTTP/1.1 101 Switching Protocols\r\n");
          sb.append("Connection: Upgrade\r\n");
          sb.append("Upgrade: ").append(target).append("\r\n");
          for (var entry : response.getSwitchProtocolsHeaders().entrySet()) {
            sb.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
          }
          sb.append("\r\n");
          socket.getOutputStream().write(sb.toString().getBytes(StandardCharsets.US_ASCII));
          socket.getOutputStream().flush();
          response.getSwitchProtocolsHandler().handle(socket);
          return;
        }

        // Do this before we write the response preamble. The normal Keep-Alive check below will handle closing the socket.
        if (handledRequests >= configuration.getMaxRequestsPerConnection()) {
          logger.trace("[{}] Maximum requests per connection has been reached. Turn off Keep-Alive.", Thread.currentThread().threadId());
          response.setHeader(HTTPValues.Headers.Connection, HTTPValues.Connections.Close);
        }

        response.close();

        boolean keepSocketAlive = keepSocketAlive(request, response);
        // Close the socket.
        if (!keepSocketAlive) {
          logger.trace("[{}] Closing socket. No Keep-Alive.", Thread.currentThread().threadId());
          closeSocketOnly(CloseSocketReason.Expected);
          return;
        }

        // Transition to Keep-Alive state and reset the SO timeout
        workerState = WorkerState.KeepAlive;
        int soTimeout = (int) configuration.getKeepAliveTimeoutDuration().toMillis();
        logger.trace("[{}] Enter Keep-Alive state [{}] Reset socket timeout [{}].", Thread.currentThread().threadId(), workerState, soTimeout);
        socket.setSoTimeout(soTimeout);

        // Drain the InputStream so we can complete this request
        long startDrain = System.currentTimeMillis();
        int drained = httpInputStream.drain();
        if (drained > 0 && logger.isTraceEnabled()) {
          long drainDuration = System.currentTimeMillis() - startDrain;
          logger.trace("[{}] Drained [{}] bytes from the InputStream. Duration [{}] ms.", Thread.currentThread().threadId(), drained, drainDuration);
        }
      }
    } catch (ConnectionClosedException e) {
      // The client closed the socket. Trace log this since it is an expected case.
      logger.trace("[{}] Closing socket. Client closed the connection. Reason [{}].", Thread.currentThread().threadId(), e.getMessage());
      closeSocketOnly(CloseSocketReason.Expected);
    } catch (HTTPProcessingException e) {
      // These are expected, but are things the client may want to know about. Use closeSocketOnError so we can attempt to write a response.
      logger.debug("[{}] Closing socket with status [{}]. An unhandled [{}] exception was taken. Reason [{}].", Thread.currentThread().threadId(), e.getStatus(), e.getClass().getSimpleName(), e.getMessage());
      closeSocketOnError(response, e.getStatus());
    } catch (TooManyBytesToDrainException e) {
      // The request handler did not read the entire InputStream, we tried to drain it but there were more bytes remaining than the configured maximum.
      // - Close the connection, unless we drain it, the connection cannot be re-used.
      // - Treating this as an expected case because if we are in a keep-alive state, no big deal, the client can just re-open the request. If we
      //   are not ina keep alive state, the request does not need to be re-used anyway.
      logger.debug("[{}] Closing socket [{}]. Too many bytes remaining in the InputStream. Drained [{}] bytes. Configured maximum bytes [{}].", Thread.currentThread().threadId(), workerState, e.getDrainedBytes(), e.getMaximumDrainedBytes());
      closeSocketOnly(CloseSocketReason.Expected);
    } catch (SocketTimeoutException e) {
      // This might be a read timeout or a Keep-Alive timeout. The reason is based on the worker state.
      CloseSocketReason reason = workerState == WorkerState.KeepAlive ? CloseSocketReason.Expected : CloseSocketReason.Unexpected;
      String message = workerState == WorkerState.Read ? "Initial read timeout" : "Keep-Alive expired";
      if (reason == CloseSocketReason.Expected) {
        logger.trace("[{}] Closing socket [{}]. {}.", Thread.currentThread().threadId(), workerState, message);
      } else {
        logger.debug("[{}] Closing socket [{}]. {}.", Thread.currentThread().threadId(), workerState, message);
      }
      closeSocketOnly(reason);
    } catch (ParseException e) {
      logger.debug("[{}] Closing socket with status [{}]. Bad request, failed to parse request. Reason [{}] Parser state [{}]", Thread.currentThread().threadId(), Status.BadRequest, e.getMessage(), e.getState());
      closeSocketOnError(response, Status.BadRequest);
    } catch (SocketException e) {
      // When the HTTPServerThread shuts down, we will interrupt each client thread, so debug log it accordingly.
      // - This will cause the socket to throw a SocketException, so log it.
      if (Thread.currentThread().isInterrupted()) {
        logger.debug("[{}] Closing socket. Server is shutting down.", Thread.currentThread().threadId());
      } else {
        logger.debug("[{}] Closing socket. The socket was closed by a client, proxy or otherwise.", Thread.currentThread().threadId());
      }
      closeSocketOnly(CloseSocketReason.Expected);
    } catch (IOException e) {
      logger.debug(String.format("[%s] Closing socket with status [%d]. An IO exception was thrown during processing. These are pretty common.", Thread.currentThread().threadId(), Status.InternalServerError), e);
      closeSocketOnError(response, Status.InternalServerError);
    } catch (Throwable e) {
      ExceptionHandlerContext context = new ExceptionHandlerContext(logger, request, Status.InternalServerError, e);
      try {
        configuration.getUnexpectedExceptionHandler().handle(context);
      } catch (Throwable ignore) {
      }

      // Signal an error
      closeSocketOnError(response, context.getStatusCode());
    } finally {
      if (instrumenter != null) {
        instrumenter.workerStopped();
      }
    }
  }

  @Override
  public ClientConnection.State state() {
    return switch (workerState) {
      case Read, KeepAlive -> ClientConnection.State.Read;
      case Write -> ClientConnection.State.Write;
      case Process -> ClientConnection.State.Process;
    };
  }

  private void closeSocketOnError(HTTPResponse response, int status) {
    if (status >= 400 && status <= 499 && instrumenter != null) {
      instrumenter.badRequest();
    }

    try {
      // If the conditions are perfect, we can still write back a status code.
      // - If the response is committed, someone already wrote to the client so we can't affect the response status, headers, etc.
      if (response != null && !response.isCommitted()) {
        // Note that we are intentionally not purging the InputStream prior to writing the response. In the most ideal sense, purging
        // the input stream would allow the client to read this response more easily. However, most of the error conditions that would cause
        // this path are malformed requests or potentially malicious payloads.
        // It is still possible to read a response, the client simply needs to handle the socket reset, and reconnect to read the response.
        // - Perhaps this is not common, but it is possible.

        // Note that reset() clears the Connection response header.
        response.reset();
        response.setHeader(HTTPValues.Headers.Connection, HTTPValues.Connections.Close);
        response.setStatus(status);
        response.setContentLength(0L);
        response.close();
      }
    } catch (IOException e) {
      logger.debug(String.format("[%s] Could not close the HTTP response.", Thread.currentThread().threadId()), e);
    } finally {
      // It is plausible that calling response.close() could throw an exception. We must ensure we close the socket.
      closeSocketOnly(CloseSocketReason.Unexpected);
    }
  }

  private void closeSocketOnly(CloseSocketReason reason) {
    if (reason == CloseSocketReason.Unexpected && instrumenter != null) {
      instrumenter.connectionClosed();
    }

    try {
      socket.close();
    } catch (IOException e) {
      logger.debug(String.format("[%s] Could not close the socket.", Thread.currentThread().threadId()), e);
    }
  }

  private boolean handleExpectContinue(HTTPRequest request) throws IOException {
    var expectResponse = new HTTPResponse();
    configuration.getExpectValidator().validate(request, expectResponse);

    // Write directly to the socket because the HTTPOutputStream.close() does a lot of extra work that we don't want
    OutputStream out = socket.getOutputStream();
    HTTPTools.writeResponsePreamble(expectResponse, out);
    out.flush();

    return expectResponse.getStatus() == 100;
  }

  /**
   * Determine if we should keep the socket alive.
   * <p>
   * Note that the HTTP request handler may have modified the 'Connection' response header, so verify the current state
   * using the HTTP response header.
   * <p>
   * When the client has requested HTTP/1.0, the default behavior will be to close the connection. When the client has
   * requested HTTP/1.1, the default behavior will be to keep the connection alive.
   * <p>
   * The reason this is an important distinction is that an HTTP/1.0 client, unless it is explicitly asking to keep the
   * connection open, will not reuse the connection. So if we do not close it, we will have to wait until we reach the
   * socket timeout before closing the socket. In the meantime the HTTP/1.0 client will keep opening new connections.
   * This leads to very poor performance for these clients, and there are still HTTP/1.0 benchmark tools around.
   *
   * @param request  the http request
   * @param response the http response
   * @return true if the socket should be kept alive.
   */
  private boolean keepSocketAlive(HTTPRequest request, HTTPResponse response) {
    var connectionHeader = response.getHeader(HTTPValues.Headers.Connection);
    return request.getProtocol().equals(HTTPValues.Protocols.HTTTP1_1)
        ? !HTTPValues.Connections.Close.equalsIgnoreCase(connectionHeader)
        : HTTPValues.Connections.KeepAlive.equalsIgnoreCase(connectionHeader);
  }

  private Integer validatePreamble(HTTPRequest request) {
    var debugEnabled = logger.isDebugEnabled();

    // Validate protocol. Protocol version is required.
    String protocol = request.getProtocol();
    if (protocol == null) {
      logger.debug("Invalid request. Missing HTTP Protocol");
      return Status.BadRequest;
    }

    // Only HTTP/ protocol is supported.
    if (!protocol.startsWith("HTTP/")) {
      if (debugEnabled) {
        logger.debug("Invalid request. Invalid protocol [{}]. Supported versions [{}].", protocol, HTTPValues.Protocols.HTTTP1_1);
      }

      return Status.BadRequest;
    }

    // Minor versions less than 1 are allowed per spec. For example, HTTP/1.0 should be allowed and is considered to be compatible enough.
    if (!protocol.equals("HTTP/1.0") && !protocol.equals("HTTP/1.1")) {
      if (debugEnabled) {
        logger.debug("Invalid request. Unsupported HTTP version [{}]. Supported versions [{}].", protocol, HTTPValues.Protocols.HTTTP1_1);
      }

      return Status.HTTPVersionNotSupported;
    }

    // Host header is required
    var host = request.getRawHost();
    if (host == null) {
      logger.debug("Invalid request. Missing Host header.");
      return Status.BadRequest;
    }

    var hostHeaders = request.getHeaders(HTTPValues.Headers.Host);
    if (hostHeaders.size() != 1) {
      if (debugEnabled) {
        logger.debug("Invalid request. Duplicate Host headers. [{}]", String.join(", ", hostHeaders));
      }

      return Status.BadRequest;
    }

    // Validate Transfer-Encoding and Content-Length per RFC 9112 §6.1 (see docs/security/audit-2026-04-20.md Vuln 1). This server only
    // supports the "chunked" transfer coding, so anything else must be rejected rather than silently discarded — mishandling TE is the
    // classic request-smuggling primitive. Specifically we reject: multiple Transfer-Encoding headers, TE values that aren't exactly
    // "chunked" after trimming (e.g. "identity", "chunked, identity", "xchunked", "chunked " with trailing whitespace), and the CL+TE
    // coexistence that a front-end proxy might resolve differently than we do.
    var transferEncodingHeaders = request.getHeaders(HTTPValues.Headers.TransferEncoding);
    if (transferEncodingHeaders != null && !transferEncodingHeaders.isEmpty()) {
      if (transferEncodingHeaders.size() != 1) {
        if (debugEnabled) {
          logger.debug("Invalid request. Multiple Transfer-Encoding headers. [{}]", String.join(", ", transferEncodingHeaders));
        }

        return Status.BadRequest;
      }

      String rawTransferEncoding = transferEncodingHeaders.getFirst();
      if (!HTTPValues.TransferEncodings.Chunked.equalsIgnoreCase(rawTransferEncoding.trim())) {
        if (debugEnabled) {
          logger.debug("Invalid request. Unsupported Transfer-Encoding. [{}]", rawTransferEncoding);
        }

        return Status.BadRequest;
      }

      if (request.getHeader(HTTPValues.Headers.ContentLength) != null) {
        if (debugEnabled) {
          logger.debug("Invalid request. Both Transfer-Encoding and Content-Length present. [{}] [{}]", rawTransferEncoding, request.getHeader(HTTPValues.Headers.ContentLength));
        }

        return Status.BadRequest;
      }

      // Normalize the stored value so downstream code (HTTPRequest.isChunked, ChunkedInputStream routing) sees an exact match regardless
      // of incidental whitespace or case in the original header.
      if (!HTTPValues.TransferEncodings.Chunked.equals(rawTransferEncoding)) {
        request.setHeader(HTTPValues.Headers.TransferEncoding, HTTPValues.TransferEncodings.Chunked);
      }
    } else {
      var requestedContentLengthHeaders = request.getHeaders(HTTPValues.Headers.ContentLength);
      if (requestedContentLengthHeaders != null) {
        if (requestedContentLengthHeaders.size() != 1) {
          if (debugEnabled) {
            logger.debug("Invalid request. Duplicate Content-Length headers. [{}]", String.join(", ", requestedContentLengthHeaders));
          }

          // If we cannot trust the Content-Length it is unlikely we can correctly drain the InputStream in order for the client to read our response.
          return Status.BadRequest;
        }

        var contentLength = request.getContentLength();
        if (contentLength == null || contentLength < 0) {
          if (debugEnabled) {
            logger.debug("Invalid request. The Content-Length must be >= 0 and <= 9,223,372,036,854,775,807. [{}]", requestedContentLengthHeaders.getFirst());
          }

          // If we cannot trust the Content-Length it is unlikely we can correctly drain the InputStream in order for the client to read our response.
          return Status.BadRequest;
        }
      }
    }

    // Validate Content-Encoding, we currently support deflate and gzip.
    // - If we see anything else we should fail, we will be unable to handle the request.
    var contentEncodings = request.getContentEncodings();
    for (var encoding : contentEncodings) {
      if (!encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Gzip) && !encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.Deflate)) {
        // Note that while we do not expect multiple Content-Encoding headers, the last one will be used. For good measure,
        // use the last one in the debug message as well.
        var contentEncodingHeader = request.getHeaders(HTTPValues.Headers.ContentEncoding).getLast();
        logger.debug("Invalid request. The Content-Type header contains an un-supported value. [{}]", contentEncodingHeader);
        return Status.UnsupportedMediaType;
      }
    }

    return null;
  }

  private enum CloseSocketReason {
    Expected,
    Unexpected
  }

  private enum WorkerState {
    KeepAlive,
    Process,
    Read,
    Write
  }

  private static class Status {
    public static final int BadRequest = 400;

    public static final int HTTPVersionNotSupported = 505;

    public static final int InternalServerError = 500;

    public static final int UnsupportedMediaType = 415;
  }
}

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
package org.lattejava.http.server.internal.h1;

import module java.base;
import module org.lattejava.http;

import java.lang.System.Logger.Level;

import org.lattejava.http.ParseException;
import org.lattejava.http.io.PushbackInputStream;
import org.lattejava.http.server.internal.*;
import org.lattejava.http.server.io.EmptyHTTPInputStream;

/**
 * An HTTP worker that is a delegate Runnable to an {@link HTTPHandler}.
 *
 * @author Brian Pontarelli
 */
public class HTTP1Connection extends BaseHTTPConnection {
  private static final System.Logger logger = System.getLogger(HTTP1Connection.class.getName());

  private final HTTPBuffers buffers;

  private final HTTPContext context;

  private final PushbackInputStream inputStream;

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listener;

  private long handledRequests;

  private volatile State state;

  public HTTP1Connection(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter,
                         HTTPListenerConfiguration listener, Throughput throughput) throws IOException {
    this(socket, configuration, context, instrumenter, listener, throughput, new PushbackInputStream(new ThroughputInputStream(socket.getInputStream(), throughput), instrumenter));
  }

  /**
   * Alternate constructor used by {@link ProtocolSelector} when the h2c prior-knowledge peek path has already consumed
   * bytes from the socket's InputStream and pushed them back into a pre-built {@link PushbackInputStream}. Passing the
   * stream directly avoids a second wrapping and ensures no peeked bytes are lost.
   */
  public HTTP1Connection(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter,
                         HTTPListenerConfiguration listener, Throughput throughput, PushbackInputStream inputStream) throws IOException {
    super(configuration, socket, throughput);
    this.context = context;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.buffers = new HTTPBuffers(configuration);
    this.inputStream = inputStream;
    this.state = State.Read;
    logger.log(Level.TRACE, "[{0}] Starting HTTP worker.", Thread.currentThread().threadId());
  }

  @Override
  public void evict(EvictionReason reason) {
    // The reaper's historical behavior: a plain close. The parked worker thread surfaces a SocketException, which the
    // run() loop already classifies as an expected close.
    try {
      socket.close();
    } catch (IOException ignore) {
    }
  }

  @Override
  public long getHandledRequests() {
    return handledRequests;
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
        logger.log(Level.TRACE, "[{0}] Running HTTP worker. Block while we wait to read the preamble", Thread.currentThread().threadId());
        request = new HTTPRequest(context, configuration.getContextPath(), listener.getCertificate() != null ? "https" : "http", listener.getPort(), socket.getInetAddress().getHostAddress());

        // Create a deep copy of the MultipartConfiguration so that the request may optionally modify the configuration on a per-request basis.
        request.getMultiPartStreamProcessor().setMultipartConfiguration(new MultipartConfiguration(configuration.getMultipartConfiguration()));

        // Set up the output stream so that if we fail we have the opportunity to write a response that contains a status code.
        var throughputOutputStream = new ThroughputOutputStream(socket.getOutputStream(), throughput);
        response = new HTTPResponse();

        var protocol = new HTTP1OutputProtocol(request, response, throughputOutputStream, buffers, instrumenter, this::enterWritePhase);
        HTTPOutputStream outputStream = new HTTPOutputStream(configuration, request, response, protocol);
        response.setOutputStream(outputStream);

        // Note: this line of code will block
        // - When a client is using Keep-Alive - we will loop and block here while we wait for the client to send us bytes.
        byte[] requestBuffer = buffers.requestBuffer();
        HTTPTools.parseRequestPreamble(inputStream, configuration.getMaxRequestHeaderSize(), request, requestBuffer, this::enterReadPhase);
        if (logger.isLoggable(Level.TRACE)) {
          int availableBufferedBytes = inputStream.getAvailableBufferedBytesRemaining();
          if (availableBufferedBytes != 0) {
            logger.log(Level.TRACE, "[{0}] Preamble parser had [{1}] left over bytes. These will be used in the HTTPInputStream.", Thread.currentThread().threadId(), availableBufferedBytes);
          }
        }

        // Once we have performed an initial read, we can count this as a handled request.
        handledRequests++;
        if (instrumenter != null) {
          instrumenter.acceptedRequest();
        }

        long maximumContentLength = HTTPTools.getMaxRequestBodySize(request.getContentType(), configuration.getMaxRequestBodySize());
        if (request.hasBody()) {
          httpInputStream = new HTTPInputStream(configuration, request, inputStream, maximumContentLength);
          request.setInputStream(httpInputStream);
        } else {
          // Bodyless requests (the GET/HEAD common case): give the handler a zero-allocation empty stream so
          // readAllBytes() returns a shared empty byte[] instead of the JDK default's 16 KB-allocate-then-discard pattern.
          // The drain step below is a no-op for these requests (HTTPInputStream.drain already short-circuits when hasBody()
          // is false), so skipping the wrapper here is behaviour-preserving.
          httpInputStream = null;
          request.setInputStream(EmptyHTTPInputStream.INSTANCE);
        }

        // Set the Connection response header as soon as possible
        // - This needs to occur after we have parsed the pre-amble so we can read the request headers
        response.setHeader(HTTPValues.Headers.Connection, request.isKeepAlive() ? HTTPValues.Connections.KeepAlive : HTTPValues.Connections.Close);

        // Ensure the preamble is valid
        Integer status = HTTP1Validator.validatePreamble(request);
        if (status != null) {
          closeSocketOnError(response, status);
          return;
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
            enterWritePhase();

            boolean doContinue = handleExpectContinue(request);
            if (!doContinue) {
              // Note that the expectContinue code already wrote to the OutputStream, all we need to do is close the socket.
              closeSocketOnly(CloseSocketReason.Expected);
              return;
            }

            // Otherwise, transition the state to Read
            enterReadPhase();
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
        state = State.Process;
        logger.log(Level.TRACE, "[{0}] Set state [{1}]. Call the request handler.", Thread.currentThread().threadId(), state);
        try {
          configuration.getHandler().handle(request, response);
          logger.log(Level.TRACE, "[{0}] Handler completed successfully", Thread.currentThread().threadId());
        } finally {
          // Clean up temporary files if instructed to do so.
          // - Note that this is using the request scoped configuration. It is possible for the request handler to disable
          //   deletion of temporary files on a request basis.
          var multiPartProcessor = request.getMultiPartStreamProcessor();
          if (multiPartProcessor.getMultiPartConfiguration().isDeleteTemporaryFiles()) {
            var fileManager = multiPartProcessor.getMultipartFileManager();
            for (var file : fileManager.getTemporaryFiles()) {
              try {
                logger.log(Level.DEBUG, "Delete temporary file [{0}]", file);
                Files.deleteIfExists(file);
              } catch (Exception e) {
                logger.log(Level.ERROR, MessageFormat.format("Unable to delete temporary file. [{0}]", file), e);
              }
            }
          }
        }

        // Do this before we write the response preamble. The normal Keep-Alive check below will handle closing the socket.
        if (handledRequests >= configuration.getMaxRequestsPerConnection()) {
          logger.log(Level.TRACE, "[{0}] Maximum requests per connection has been reached. Turn off Keep-Alive.", Thread.currentThread().threadId());
          response.setHeader(HTTPValues.Headers.Connection, HTTPValues.Connections.Close);
        }

        response.close();

        boolean keepSocketAlive = keepSocketAlive(request, response);
        if (!keepSocketAlive) {
          // Close the socket.
          logger.log(Level.TRACE, "[{0}] Closing socket. No Keep-Alive.", Thread.currentThread().threadId());
          closeSocketOnly(CloseSocketReason.Expected);
          return;
        }

        // Transition to Keep-Alive state and reset the SO timeout
        state = State.KeepAlive;
        int soTimeout = (int) configuration.getKeepAliveTimeoutDuration().toMillis();
        logger.log(Level.TRACE, "[{0}] Enter Keep-Alive state [{1}] Reset socket timeout [{2}].", Thread.currentThread().threadId(), state, soTimeout);
        socket.setSoTimeout(soTimeout);

        // Drain the InputStream so we can complete this request. Null when the request was bodyless and the
        // EmptyHTTPInputStream singleton was installed above — nothing to drain in that case.
        if (httpInputStream != null) {
          long startDrain = System.currentTimeMillis();
          int drained = httpInputStream.drain();
          if (drained > 0 && logger.isLoggable(Level.TRACE)) {
            long drainDuration = System.currentTimeMillis() - startDrain;
            logger.log(Level.TRACE, "[{0}] Drained [{1}] bytes from the InputStream. Duration [{2}] ms.", Thread.currentThread().threadId(), drained, drainDuration);
          }
        }
      }
    } catch (ConnectionClosedException e) {
      // The client closed the socket. Trace log this since it is an expected case.
      logger.log(Level.TRACE, "[{0}] Closing socket. Client closed the connection. Reason [{1}].", Thread.currentThread().threadId(), e.getMessage());
      closeSocketOnly(CloseSocketReason.Expected);
    } catch (HTTPProcessingException e) {
      // These are expected, but are things the client may want to know about. Use closeSocketOnError so we can attempt to write a response.
      logger.log(Level.DEBUG, "[{0}] Closing socket with status [{1}]. An unhandled [{2}] exception was taken. Reason [{3}].", Thread.currentThread().threadId(), e.getStatus(), e.getClass().getSimpleName(), e.getMessage());
      closeSocketOnError(response, e.getStatus());
    } catch (TooManyBytesToDrainException e) {
      // The request handler did not read the entire InputStream, we tried to drain it but there were more bytes remaining than the configured maximum.
      // - Close the connection, unless we drain it, the connection cannot be re-used.
      // - Treating this as an expected case because if we are in a keep-alive state, no big deal, the client can just re-open the request. If we
      //   are not ina keep alive state, the request does not need to be re-used anyway.
      logger.log(Level.DEBUG, "[{0}] Closing socket [{1}]. Too many bytes remaining in the InputStream. Drained [{2}] bytes. Configured maximum bytes [{3}].", Thread.currentThread().threadId(), state, e.getDrainedBytes(), e.getMaximumDrainedBytes());
      closeSocketOnly(CloseSocketReason.Expected);
    } catch (SocketTimeoutException e) {
      // This might be a read timeout or a Keep-Alive timeout. The reason is based on the worker state.
      CloseSocketReason reason = state == State.KeepAlive ? CloseSocketReason.Expected : CloseSocketReason.Unexpected;
      String message = state == State.Read ? "Initial read timeout" : "Keep-Alive expired";
      Level level = reason == CloseSocketReason.Expected ? Level.TRACE : Level.DEBUG;
      logger.log(level, "[{0}] Closing socket [{1}]. {2}.", Thread.currentThread().threadId(), state, message);
      closeSocketOnly(reason);
    } catch (ParseException e) {
      // On a dual-protocol (h2c prior-knowledge) listener, first-request bytes that parse as neither an HTTP/2
      // preface nor an HTTP/1.1 preamble come from a client whose protocol is unknown — an HTTP/1.1 400 would be
      // gibberish to an HTTP/2-speaking client, so terminate silently (RFC 9113 §3.4 permits closing without a
      // GOAWAY). Once a request has been handled the client has proven it speaks HTTP/1.1 and gets the 400.
      if (listener.isSniffProtocolVersion() && handledRequests == 0) {
        logger.log(Level.DEBUG, "[{0}] Closing socket. First bytes on a dual-protocol listener were neither an HTTP/2 preface nor a parseable HTTP/1.1 request. Reason [{1}] Parser state [{2}]", Thread.currentThread().threadId(), e.getMessage(), e.getState());
        closeSocketOnly(CloseSocketReason.Unexpected);
      } else {
        logger.log(Level.DEBUG, "[{0}] Closing socket with status [{1}]. Bad request, failed to parse request. Reason [{2}] Parser state [{3}]", Thread.currentThread().threadId(), HTTPValues.Status.BadRequest, e.getMessage(), e.getState());
        closeSocketOnError(response, HTTPValues.Status.BadRequest);
      }
    } catch (SocketException e) {
      // When the HTTPServerAcceptorThread shuts down, we will interrupt each client thread, so debug log it accordingly.
      // - This will cause the socket to throw a SocketException, so log it.
      if (Thread.currentThread().isInterrupted()) {
        logger.log(Level.DEBUG, "[{0}] Closing socket. Server is shutting down.", Thread.currentThread().threadId());
      } else {
        logger.log(Level.DEBUG, "[{0}] Closing socket. The socket was closed by a client, proxy or otherwise.", Thread.currentThread().threadId());
      }
      closeSocketOnly(CloseSocketReason.Expected);
    } catch (IOException e) {
      logger.log(Level.DEBUG, MessageFormat.format("[{0}] Closing socket with status [{1}]. An IO exception was thrown during processing. These are pretty common.", Thread.currentThread().threadId(), HTTPValues.Status.InternalServerError), e);
      closeSocketOnError(response, HTTPValues.Status.InternalServerError);
    } catch (Throwable e) {
      ExceptionHandlerContext context = new ExceptionHandlerContext(request, HTTPValues.Status.InternalServerError, e);
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

  /**
   * HTTP/1.1 has no graceful in-band shutdown signal; the connection is torn down by the socket close and thread
   * interrupt that {@link HTTPServerAcceptorThread} performs at server shutdown. Intentionally a no-op.
   */
  @Override
  public void shutdown() {
  }

  @Override
  protected long lastProgressInstant() {
    return throughput.lastUsed();
  }

  @Override
  protected boolean processing() {
    return state == State.Process;
  }

  @Override
  protected boolean readingRequest() {
    return state == State.Read;
  }

  @Override
  protected boolean writingResponse() {
    return state == State.Write;
  }

  private void closeSocketOnError(HTTPResponse response, int status) {
    if (status >= 400 && status <= 499 && instrumenter != null) {
      instrumenter.badRequest();
    }

    try {
      // If the conditions are perfect, we can still write back a status code.
      // - If the response is committed, someone already wrote to the client so we can't affect the response status, headers, etc.
      if (response != null && !response.isCommitted()) {
        // Note that we are intentionally not purging the InputStream prior to writing the response — most of the error
        // conditions that reach this path are malformed requests or potentially malicious payloads, so an unbounded
        // purge here would be a DoS vector. closeSocketOnly performs a bounded drain after this response is written so
        // that the close is a clean FIN and the client can read the response before EOF.

        // Note that reset() clears the Connection response header.
        response.reset();
        response.setHeader(HTTPValues.Headers.Connection, HTTPValues.Connections.Close);
        response.setStatus(status);
        response.setContentLength(0L);
        response.close();
      }
    } catch (IOException e) {
      logger.log(Level.DEBUG, MessageFormat.format("[{0}] Could not close the HTTP response.", Thread.currentThread().threadId()), e);
    } finally {
      // It is plausible that calling response.close() could throw an exception. We must ensure we close the socket.
      closeSocketOnly(CloseSocketReason.Unexpected);
    }
  }

  private void closeSocketOnly(CloseSocketReason reason) {
    if (reason == CloseSocketReason.Unexpected && instrumenter != null) {
      instrumenter.connectionClosed();
    }

    // Error-path closes race unread request bytes: close() with data pending in the kernel receive buffer makes the
    // OS emit a TCP RST instead of a clean FIN, and the RST can also destroy an error response before the client
    // reads it. Send our FIN first, then drain a bounded amount so the close is clean. Expected closes (keep-alive
    // expiry, deliberately abandoned drains) skip this — no response is in flight, and the drain would add latency
    // or resume a drain that TooManyBytesToDrainException chose to abandon.
    if (reason == CloseSocketReason.Unexpected) {
      try {
        socket.shutdownOutput();
      } catch (Exception ignore) {
        // Already closed, or an SSLSocket (shutdownOutput unsupported) — proceed to close.
      }
      try {
        socket.setSoTimeout(50);
        InputStream in = socket.getInputStream();
        byte[] scrap = new byte[4096];
        // Iteration-bounded (not byte-bounded) so a byte-at-a-time trickler cannot hold the drain open: at most
        // 16 reads x 50 ms = 800 ms and 64 KB, whichever a hostile client hits first.
        for (int i = 0; i < 16; i++) {
          if (in.read(scrap) < 0) {
            break;
          }
        }
      } catch (IOException ignore) {
        // Timed out waiting for more bytes, or the socket is already dead — either way, close now.
      }
    }

    try {
      socket.close();
    } catch (IOException e) {
      logger.log(Level.DEBUG, MessageFormat.format("[{0}] Could not close the socket.", Thread.currentThread().threadId()), e);
    }
  }

  /**
   * Transitions the worker into its read phase and, on the transition (not on a repeat), starts a fresh read-throughput
   * epoch so the slow-reader floor measures only the current request. Invoked from the worker thread by the preamble
   * parser's read observer and the Expect/continue path, so the guarded reset happens exactly once per read phase even
   * though the observer may fire on more than one read.
   */
  private void enterReadPhase() {
    if (state != State.Read) {
      throughput.resetRead();
      state = State.Read;
    }
  }

  /**
   * Transitions the worker into its write phase and, on the transition, starts a fresh write-throughput epoch. Invoked
   * from the worker thread by the output protocol's write observer, which fires on every write, so the guard resets the
   * epoch once - at the point the first response byte is produced, before the socket buffer fills - and never discards
   * a response already in flight.
   */
  private void enterWritePhase() {
    if (state != State.Write) {
      throughput.resetWrite();
      state = State.Write;
    }
  }

  private boolean handleExpectContinue(HTTPRequest request) throws IOException {
    var expectResponse = new HTTPResponse();
    configuration.getHTTP1Configuration().getExpectValidator().validate(request, expectResponse);

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

  private enum CloseSocketReason {
    Expected,
    Unexpected
  }

  /**
   * The worker's lifecycle phase, mapped onto the {@link BaseHTTPConnection} liveness gates. {@code KeepAlive} opens no
   * gate — an idle keep-alive socket is never reaper-evicted; its expiry is governed by the socket-level
   * {@code SO_TIMEOUT} the worker sets when it enters this phase.
   */
  private enum State {
    KeepAlive,
    Process,
    Read,
    Write
  }
}

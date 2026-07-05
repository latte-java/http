/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.log.Logger;
import org.lattejava.http.server.internal.*;

/**
 * The per-request virtual-thread body for HTTP/2 handler invocations. Builds the output protocol, invokes the
 * application handler, and handles the three exit paths: clean completion, processing exception, and unexpected error.
 */
public class HTTP2HandlerDelegate implements Runnable {
  private final HTTPServerConfiguration configuration;

  private final HTTP2ConnectionWindow connectionSendWindow;

  private final HPACKEncoder encoder;

  private final Set<Thread> handlerThreads;

  private final Logger logger;

  private final HTTP2Settings peerSettings;

  private final HTTPRequest request;

  private final HTTPResponse response;

  private final HTTP2Stream stream;

  private final HTTP2WriterThread writer;

  public HTTP2HandlerDelegate(HTTPServerConfiguration configuration, HTTP2ConnectionWindow connectionSendWindow,
                              HPACKEncoder encoder, Set<Thread> handlerThreads, Logger logger, HTTP2Settings peerSettings,
                              HTTPRequest request, HTTPResponse response, HTTP2Stream stream, HTTP2WriterThread writer) {
    this.configuration = configuration;
    this.connectionSendWindow = connectionSendWindow;
    this.encoder = encoder;
    this.handlerThreads = handlerThreads;
    this.logger = logger;
    this.peerSettings = peerSettings;
    this.request = request;
    this.response = response;
    this.stream = stream;
    this.writer = writer;
  }

  @Override
  public void run() {
    Thread self = Thread.currentThread();
    handlerThreads.add(self);
    try {
      // Use a lazy-header output stream: HEADERS are emitted on the first write or flush so that the
      // handler can interleave request reads and response writes (required for bidi-streaming).
      // RFC 9113 §8.1 requires HEADERS to precede DATA frames — the HTTP2OutputProtocol enforces this.
      var protocol = new HTTP2OutputProtocol(response, stream, encoder, writer, connectionSendWindow, peerSettings, logger);
      HTTPOutputStream outputStream = new HTTPOutputStream(configuration, request, response, protocol);
      response.setOutputStream(outputStream);

      configuration.getHandler().handle(request, response);

      // Ensure the output is closed even if the handler did not call out.close() explicitly.
      response.getOutputStream().close();

      try {
        stream.applyEvent(HTTP2Stream.Event.SEND_DATA_END_STREAM);
      } catch (IllegalStateException ignored) {
        // Stream was reset by the client (RECV_RST_STREAM) between our last write and now.
        // The DATA frame is already in the writer queue; the RST_STREAM from the client implicitly
        // cancels it. Not an error — this is normal during graceful teardown or test probing.
      }

      stream.deregister();
    } catch (HTTPProcessingException e) {
      // Expected processing errors (e.g. ContentTooLargeException → 413). Send a proper HTTP error response
      // so the client receives the status code rather than a RST_STREAM(INTERNAL_ERROR).
      logger.debug("h2 handler processing exception on stream [{}]: [{}]", stream.streamId(), e.getMessage());
      try {
        response.setStatus(e.getStatus());
        var errorProtocol = new HTTP2OutputProtocol(response, stream, encoder, writer, connectionSendWindow, peerSettings, logger);
        response.setOutputStream(new HTTPOutputStream(configuration, request, response, errorProtocol));
        response.getOutputStream().close();
      } catch (Exception writeEx) {
        logger.debug("Failed to write error response for stream [{}]", stream.streamId(), writeEx);
      }
      // RFC 9113 §8.1: after a complete response, the server MAY send RST_STREAM(NO_ERROR) to ask the client to
      // stop uploading the rest of the request body. Without this, the client keeps sending DATA frames that we
      // would silently drain (or the connection-level flow window would stall). Skip when the client has already
      // reset the stream (RFC 9113 §5.4.2 forbids RST_STREAM in response to RST_STREAM).
      if (stream.state() != HTTP2Stream.State.CLOSED) {
        writer.enqueueOrCloseWriter(new HTTP2Frame.RSTStreamFrame(stream.streamId(), HTTP2ErrorCode.NO_ERROR.value));
      }
      stream.deregister();
    } catch (Exception e) {
      logger.error("h2 handler exception on stream [" + stream.streamId() + "]", e);
      // offer with short timeout — the writer may already be dead and the queue full during connection teardown.
      // We don't want this cleanup path to block, but a silently dropped RST_STREAM is worth a debug log so that
      // backed-up writer-queue scenarios are visible.
      try {
        if (!writer.tryEnqueue(new HTTP2Frame.RSTStreamFrame(stream.streamId(), HTTP2ErrorCode.INTERNAL_ERROR.value),
            100, TimeUnit.MILLISECONDS)) {
          logger.debug("Dropped RST_STREAM(INTERNAL_ERROR) for stream [{}] — writer queue full or dead", stream.streamId());
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      stream.deregister();
    } finally {
      handlerThreads.remove(self);
    }
  }
}

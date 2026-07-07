/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

import java.lang.System.Logger.Level;
import java.text.MessageFormat;

import org.lattejava.http.server.internal.*;

/**
 * The per-request virtual-thread body for HTTP/2 handler invocations. Builds the output protocol, invokes the
 * application handler, and handles the three exit paths: clean completion, processing exception, and unexpected error.
 */
public class HTTP2HandlerDelegate implements Runnable {
  private static final System.Logger logger = System.getLogger(HTTP2HandlerDelegate.class.getName());

  private final HTTPServerConfiguration configuration;

  private final HTTP2Window connectionSendWindow;

  private final HPACKEncoder encoder;

  private final Set<Thread> handlerThreads;

  private final HTTP2Settings peerSettings;

  private final HTTPRequest request;

  private final HTTPResponse response;

  private final HTTP2Stream stream;

  private final HTTP2WriterThread writer;

  public HTTP2HandlerDelegate(HTTPServerConfiguration configuration, HTTP2Window connectionSendWindow,
                              HPACKEncoder encoder, Set<Thread> handlerThreads, HTTP2Settings peerSettings,
                              HTTPRequest request, HTTPResponse response, HTTP2Stream stream, HTTP2WriterThread writer) {
    this.configuration = configuration;
    this.connectionSendWindow = connectionSendWindow;
    this.encoder = encoder;
    this.handlerThreads = handlerThreads;
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
      var protocol = new HTTP2OutputProtocol(response, stream, encoder, writer, connectionSendWindow, peerSettings);
      HTTPOutputStream outputStream = new HTTPOutputStream(configuration, request, response, protocol);
      response.setOutputStream(outputStream);

      configuration.getHandler().handle(request, response);

      // Ensure the output is closed even if the handler did not call out.close() explicitly.
      response.getOutputStream().close();

      try {
        stream.applyEvent(HTTP2Stream.Event.SEND_DATA_END_STREAM);
      } catch (IllegalStateException ignored) {
        // Already transitioned: the output path applied SEND_*_END_STREAM when it enqueued the final frame
        // (releasing the MAX_CONCURRENT_STREAMS slot at that instant — RFC 9113 §5.1.2), or the client reset the
        // stream between our last write and now. Either way this is not an error.
      }

      // Fallback roster cleanup. Normally the output path already released the slot when END_STREAM was enqueued;
      // registry.close() is idempotent so re-closing is harmless. This pass matters for the paths the output side
      // cannot see: a client half still open when we responded early (forgotten, so late client DATA is ignored)
      // and a client END_STREAM that arrived after our response closed the local half (remembered).
      if (stream.state() == HTTP2Stream.State.CLOSED) {
        stream.close();
      } else {
        stream.deregister();
      }
    } catch (HTTPProcessingException e) {
      // Expected processing errors (e.g. ContentTooLargeException → 413). Send a proper HTTP error response
      // so the client receives the status code rather than a RST_STREAM(INTERNAL_ERROR).
      logger.log(Level.DEBUG, "h2 handler processing exception on stream [{0}]: [{1}]", stream.streamId(), e.getMessage());
      try {
        response.setStatus(e.getStatus());
        var errorProtocol = new HTTP2OutputProtocol(response, stream, encoder, writer, connectionSendWindow, peerSettings);
        response.setOutputStream(new HTTPOutputStream(configuration, request, response, errorProtocol));
        response.getOutputStream().close();
      } catch (Exception writeEx) {
        logger.log(Level.DEBUG, MessageFormat.format("Failed to write error response for stream [{0}]", stream.streamId()), writeEx);
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
      logger.log(Level.ERROR, MessageFormat.format("h2 handler exception on stream [{0}]", stream.streamId()), e);
      // offer with short timeout — the writer may already be dead and the queue full during connection teardown.
      // We don't want this cleanup path to block, but a silently dropped RST_STREAM is worth a debug log so that
      // backed-up writer-queue scenarios are visible.
      try {
        if (!writer.tryEnqueue(new HTTP2Frame.RSTStreamFrame(stream.streamId(), HTTP2ErrorCode.INTERNAL_ERROR.value),
            100, TimeUnit.MILLISECONDS)) {
          logger.log(Level.DEBUG, "Dropped RST_STREAM(INTERNAL_ERROR) for stream [{0}] — writer queue full or dead", stream.streamId());
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

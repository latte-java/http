/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

import java.lang.System.Logger.Level;
import java.text.MessageFormat;

import org.lattejava.http.io.MultipartConfiguration;
import org.lattejava.http.io.PushbackInputStream;
import org.lattejava.http.server.HTTPContext;
import org.lattejava.http.server.Instrumenter;
import org.lattejava.http.util.HTTPTools;

/**
 * Handles complete header blocks (the frame reader assembles fragments — a HeadersFrame here always carries the
 * whole block). Two entry points: a new stream's request headers, and trailers on an existing stream.
 */
public class HTTP2HeaderFrameHandler {
  private static final System.Logger logger = System.getLogger(HTTP2HeaderFrameHandler.class.getName());

  private final HTTPServerConfiguration configuration;

  private final HTTP2Window connectionSendWindow;

  private final HTTPContext context;

  private final HPACKDecoder decoder;

  private final HPACKEncoder encoder;

  private final AtomicLong handledRequests;

  private final Set<Thread> handlerThreads;

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listener;

  private final HTTP2Settings peerSettings;

  private final Socket socket;

  private final HTTP2WriterThread writer;

  public HTTP2HeaderFrameHandler(HTTPServerConfiguration configuration, HTTP2Window connectionSendWindow,
                                 HTTPContext context, HPACKDecoder decoder, HPACKEncoder encoder,
                                 AtomicLong handledRequests, Set<Thread> handlerThreads, Instrumenter instrumenter,
                                 HTTPListenerConfiguration listener, HTTP2Settings peerSettings,
                                 Socket socket, HTTP2WriterThread writer) {
    this.configuration = configuration;
    this.connectionSendWindow = connectionSendWindow;
    this.context = context;
    this.decoder = decoder;
    this.encoder = encoder;
    this.handledRequests = handledRequests;
    this.handlerThreads = handlerThreads;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.peerSettings = peerSettings;
    this.socket = socket;
    this.writer = writer;
  }

  public HTTP2Result handleNewStream(HTTP2Stream stream, HTTP2Frame.HeadersFrame f) {
    boolean opened = stream.open();

    List<HPACKDynamicTable.HeaderField> fields;
    try {
      fields = decoder.decode(f.data());
    } catch (IOException e) {
      // RFC 7541 §2.1 / RFC 9113 §4.3 — HPACK decode failure is a connection error, refused stream or not.
      logger.log(Level.DEBUG, "HPACK decode failed on stream [{0}]: [{1}]", stream.streamId(), e.getMessage());
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.COMPRESSION_ERROR);
    }

    if (!opened) {
      // MAX_CONCURRENT_STREAMS refusal. The block was still HPACK-decoded above — RFC 9113 §4.3 requires processing
      // every header block to keep the shared dynamic table synchronized — but the fields are discarded.
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.REFUSED_STREAM);
    }

    // RFC 9113 §5.3.1 — a stream cannot depend on itself. Checked after the HPACK decode so the dynamic table stays
    // synchronized even though the request is rejected.
    if (f.priorityDependency() == stream.streamId()) {
      stream.deregister();
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
    }

    if (!HTTP2Tools.validateHeaders(fields, false)) {
      stream.deregister();
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
    }

    HTTPRequest request = HTTP2Tools.buildRequest(fields, context, configuration.getContextPath(),
        listener.getCertificate() != null ? "https" : "http", listener.getPort(),
        socket.getInetAddress().getHostAddress());
    stream.setRequest(request);

    // §8.1.2.6: track declared content-length for DATA frame validation.
    for (var field : fields) {
      if (field.name().equals("content-length")) {
        try {
          long cl = Long.parseLong(field.value());
          if (cl < 0) {
            // RFC 9113 §8.1.2.6 — negative content-length is malformed; stream error PROTOCOL_ERROR.
            logger.log(Level.DEBUG, "Negative content-length [{0}] on stream [{1}]", cl, stream.streamId());
            stream.deregister();
            return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
          }
          stream.setDeclaredContentLength(cl);
        } catch (NumberFormatException e) {
          // RFC 9113 §8.1.2.6 — unparseable content-length is a stream error of type PROTOCOL_ERROR.
          logger.log(Level.DEBUG, "Malformed content-length [{0}] on stream [{1}]", field.value(), stream.streamId());
          stream.deregister();
          return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
        }
        break;
      }
    }

    try {
      if ((f.flags() & HTTP2Frame.FLAG_END_STREAM) != 0) {
        stream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_END_STREAM);
      } else {
        stream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_NO_END_STREAM);
      }
    } catch (IllegalStateException e) {
      // RFC 9113 §8.1 — HEADERS received in a state where the stream cannot accept them is a stream error.
      stream.deregister();
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.STREAM_CLOSED);
    }

    // Apply the server's MultipartConfiguration as a deep copy so the handler may mutate it per-request without
    // affecting the shared server-level config. Matches HTTP1Connection.java behavior.
    request.getMultiPartStreamProcessor().setMultipartConfiguration(new MultipartConfiguration(configuration.getMultipartConfiguration()));

    if ((f.flags() & HTTP2Frame.FLAG_END_STREAM) != 0) {
      // No body will follow. Skip the per-stream pipe, HTTP2InputStream, PushbackInputStream, and HTTPInputStream
      // allocations entirely — the handler sees a zero-allocation empty stream. Any DATA frame that arrives after
      // END_STREAM-on-HEADERS is a client protocol violation; the data handler handles a missing pipe by ignoring per §6.1.
      request.setInputStream(EmptyHTTPInputStream.INSTANCE);
    } else {
      ArrayBlockingQueue<byte[]> pipe = new ArrayBlockingQueue<>(16);
      stream.setPipe(pipe);
      HTTP2InputStream inputStream = new HTTP2InputStream(pipe);
      long maximumContentLength = HTTPTools.getMaxRequestBodySize(request.getContentType(), configuration.getMaxRequestBodySize());
      request.setInputStream(new HTTPInputStream(configuration, request,
          new PushbackInputStream(inputStream, instrumenter), maximumContentLength));
    }

    HTTPResponse response = new HTTPResponse();
    Thread.ofVirtual().name("h2-handler-" + stream.streamId()).start(new HTTP2HandlerDelegate(configuration,
        connectionSendWindow, encoder, handlerThreads, peerSettings, request, response, stream, writer));
    handledRequests.incrementAndGet();
    return HTTP2Result.OK;
  }

  public HTTP2Result handleTrailers(HTTP2Stream stream, HTTP2Frame.HeadersFrame f) {
    List<HPACKDynamicTable.HeaderField> fields;
    try {
      fields = decoder.decode(f.data());
    } catch (IOException e) {
      // RFC 7541 §2.1 / RFC 9113 §4.3 — HPACK decode failure is a connection error.
      logger.log(Level.DEBUG, "HPACK decode failed on stream [{0}]: [{1}]", stream.streamId(), e.getMessage());
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.COMPRESSION_ERROR);
    }

    if (!HTTP2Tools.validateHeaders(fields, true)) {
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
    }

    // RFC 9113 §5.3.1 — a stream cannot depend on itself, trailers included.
    if (f.priorityDependency() == stream.streamId()) {
      return new HTTP2Result.StreamError(stream.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
    }

    // Deliver trailers to the same HTTPRequest the handler is processing, then signal EOF on the body pipe so the
    // handler unblocks from any pending read. Trailers MUST be set before the EOF sentinel so the handler that
    // reads-then-getTrailer sees a populated trailer map.
    HTTPRequest request = stream.request();
    if (request != null) {
      for (var field : fields) {
        request.addTrailer(field.name(), field.value());
      }
    }
    BlockingQueue<byte[]> pipe = stream.pipe();
    if (pipe != null) {
      try {
        pipe.put(HTTP2InputStream.eofSentinel());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    try {
      stream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_END_STREAM);
    } catch (IllegalStateException e) {
      // Race with concurrent RST_STREAM — stream is already closed; trailers harmless. Log so unexpected
      // state-machine transitions in future refactors are visible.
      logger.log(Level.DEBUG, MessageFormat.format("Trailers HEADERS ignored on stream [{0}] in state [{1}]", stream.streamId(), stream.state()), e);
    }
    return HTTP2Result.OK;
  }
}

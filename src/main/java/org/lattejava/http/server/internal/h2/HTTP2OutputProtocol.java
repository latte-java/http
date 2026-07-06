/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.server.internal.*;

/**
 * HTTP/2 implementation of {@link HTTPOutputProtocol}. On the first write or flush the response status and headers
 * are HPACK-encoded and enqueued as a HEADERS frame (lazy emission preserves bidi-streaming); body bytes flow into an
 * {@link HTTP2OutputStream} that fragments DATA frames under flow control. Trailers ride a trailing HEADERS frame.
 *
 * <p>RFC 9113 §8.1 — HEADERS must precede DATA. This class enforces that invariant.
 */
public class HTTP2OutputProtocol implements HTTPOutputProtocol {
  private final HTTP2Window connectionSendWindow;

  private final HPACKEncoder encoder;

  private final Logger logger;

  private final HTTP2Settings peerSettings;

  private final HTTPResponse response;

  private final HTTP2Stream stream;

  private final HTTP2WriterThread writer;

  private HTTP2OutputStream sink;

  private boolean streamReset;

  private boolean wroteToClient;

  HTTP2OutputProtocol(HTTPResponse response, HTTP2Stream stream, HPACKEncoder encoder, HTTP2WriterThread writer,
                      HTTP2Window connectionSendWindow, HTTP2Settings peerSettings, Logger logger) {
    this.response = response;
    this.stream = stream;
    this.encoder = encoder;
    this.writer = writer;
    this.connectionSendWindow = connectionSendWindow;
    this.peerSettings = peerSettings;
    this.logger = logger;
  }

  @Override
  public OutputStream commitHeaders(boolean closing, boolean suppressBody) {
    // Build response HEADERS from the response state at first write/flush.
    List<HPACKDynamicTable.HeaderField> respFields = new ArrayList<>();
    respFields.add(new HPACKDynamicTable.HeaderField(":status", String.valueOf(response.getStatus())));
    for (var entry : response.getHeadersMap().entrySet()) {
      String lowerKey = entry.getKey().toLowerCase(Locale.ROOT);
      if (HTTPValues.Headers.ConnectionSpecificHeaders.contains(lowerKey)) {
        logger.debug("Stripping h1.1-only response header [{}] on h2 emission", entry.getKey());
        continue;
      }
      for (String v : entry.getValue()) {
        respFields.add(new HPACKDynamicTable.HeaderField(lowerKey, v));
      }
    }

    // HPACKEncoder mutates a shared dynamic table and is not thread-safe across handler threads.
    byte[] headerBlock;
    synchronized (encoder) {
      headerBlock = encoder.encode(respFields);
    }

    // RFC 9113 §5.1 — take the stream monitor across the state check, transition, AND enqueue so a concurrent
    // RECV_RST_STREAM cannot interleave.
    synchronized (stream) {
      if (stream.state() == HTTP2Stream.State.CLOSED) {
        streamReset = true;
        return OutputStream.nullOutputStream();
      }
      try {
        stream.applyEvent(HTTP2Stream.Event.SEND_HEADERS_NO_END_STREAM);
      } catch (IllegalStateException ignored) {
        streamReset = true;
        return OutputStream.nullOutputStream();
      }
      if (!writer.enqueueOrCloseWriter(new HTTP2Frame.HeadersFrame(stream.streamId(), HTTP2Frame.FLAG_END_HEADERS, headerBlock))) {
        streamReset = true;
        return OutputStream.nullOutputStream();
      }
    }

    wroteToClient = true;
    sink = new HTTP2OutputStream(stream, writer, connectionSendWindow, peerSettings.maxFrameSize());
    return sink;
  }

  @Override
  public void commitTrailers() {
    if (streamReset || !response.hasTrailers()) {
      return;
    }

    List<HPACKDynamicTable.HeaderField> trailerFields = new ArrayList<>();
    for (var entry : response.getTrailers().entrySet()) {
      for (String v : entry.getValue()) {
        trailerFields.add(new HPACKDynamicTable.HeaderField(entry.getKey(), v));
      }
    }
    byte[] trailerBlock;
    synchronized (encoder) {
      trailerBlock = encoder.encode(trailerFields);
    }
    writer.enqueueOrCloseWriter(new HTTP2Frame.HeadersFrame(stream.streamId(),
        HTTP2Frame.FLAG_END_HEADERS | HTTP2Frame.FLAG_END_STREAM, trailerBlock));
  }

  @Override
  public void forceFlush() {
    // No-op: HTTP/2 frames are enqueued to the writer queue as they are produced; there is no extra buffer to push.
  }

  @Override
  public void prepareTrailers() {
    // The END_STREAM flag rides the final DATA frame, so suppress it now if trailers will follow.
    if (!streamReset && sink != null && response.hasTrailers()) {
      sink.setTrailersFollow(true);
    }
  }

  @Override
  public boolean wroteToClient() {
    return wroteToClient;
  }
}

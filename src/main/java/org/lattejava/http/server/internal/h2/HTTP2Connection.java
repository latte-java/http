/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.io.PushbackInputStream;
import org.lattejava.http.server.internal.*;

/**
 * Per-connection HTTP/2 state and lifecycle. Owns the socket I/O, frame codec, HPACK state, and stream registry.
 *
 * <p>Threading model:
 * <ul>
 *   <li>The calling thread (reader thread) runs {@link #run()}, reads all inbound frames, and dispatches
 *       to handler methods.</li>
 *   <li>A single {@link HTTP2WriterThread} serializes all outbound frames to the socket. It exits when it
 *       dequeues the writer-shutdown sentinel (a GoawayFrame with lastStreamId == -1).</li>
 *   <li>Each request spawns a handler virtual-thread that runs the application {@link HTTPHandler}, then
 *       enqueues response HEADERS and DATA frames to the writer queue.</li>
 * </ul>
 *
 * @author Daniel DeGroff
 */
public class HTTP2Connection implements HTTPConnection, Runnable {
  // Maximum number of recently-closed stream IDs to remember for §5.1 STREAM_CLOSED detection.
  private static final int MAX_RECENTLY_CLOSED = 100;

  private final HTTPBuffers buffers;

  private final HTTPServerConfiguration configuration;

  // Connection-level send window (RFC 9113 §6.9). Shared by every per-stream writer; replenished by the reader thread
  // on a stream-0 WINDOW_UPDATE. Starts at the HTTP/2 default of 65535 octets.
  private final HTTP2ConnectionWindow connectionSendWindow = new HTTP2ConnectionWindow(65535);

  private final HTTPContext context;

  // Active handler virtual-threads. Each handler adds itself on entry and removes itself in finally. The connection's
  // teardown path interrupts every thread in this set so handlers parked on the writer queue or in HTTP2OutputStream's
  // flow-control wait loop unblock and propagate InterruptedIOException out of the user handler instead of leaking.
  private final Set<Thread> handlerThreads = ConcurrentHashMap.newKeySet();

  // The connection input stream, already wrapped for throughput accounting and positioned immediately after the
  // HTTP/2 connection preface, which ProtocolSelector read and validated before constructing this connection.
  private final InputStream inputStream;

  private final Instrumenter instrumenter;

  private final HTTPListenerConfiguration listener;

  private final HTTP2Settings localSettings;

  private final Logger logger;

  private final HTTP2Settings peerSettings = HTTP2Settings.defaults();

  private final HTTP2RateLimitsTracker rateLimits;

  // Bounded deque of recently-closed stream IDs for RFC 9113 §5.1 STREAM_CLOSED error detection.
  // Access is confined to the reader thread, so no synchronization is needed.
  private final Deque<Integer> recentlyClosedStreams = new ArrayDeque<>();

  private final Socket socket;

  private final long startInstant;

  private final Map<Integer, BlockingQueue<byte[]>> streamPipes = new ConcurrentHashMap<>();

  private final Map<Integer, HTTP2Stream> streams = new ConcurrentHashMap<>();

  private final Throughput throughput;

  private volatile boolean goawaySent;

  private long handledRequests;

  private volatile int highestSeenStreamId = 0;

  // Reader thread handle, captured at the top of run() so the writer thread can interrupt it when it dies.
  private volatile Thread readerThread;

  private volatile HTTPConnection.State state = HTTPConnection.State.Read;

  // The outbound writer: owns the frame queue and the virtual thread that serializes frames to the socket. Built after
  // the SETTINGS exchange in run(); volatile because shutdown() reads it from the acceptor thread.
  private volatile HTTP2WriterThread writer;

  public HTTP2Connection(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter,
                         HTTPListenerConfiguration listener, Throughput throughput, InputStream inputStream) throws IOException {
    this.socket = socket;
    this.configuration = configuration;
    this.context = context;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.throughput = throughput;
    this.inputStream = inputStream;
    this.buffers = new HTTPBuffers(configuration);
    this.logger = configuration.getLoggerFactory().getLogger(HTTP2Connection.class);
    this.localSettings = configuration.getHTTP2Settings();
    this.rateLimits = configuration.getHTTP2RateLimits().newTracker();
    this.startInstant = System.currentTimeMillis();
  }

  @Override
  public long getHandledRequests() {
    return handledRequests;
  }

  @Override
  public Socket getSocket() {
    return socket;
  }

  @Override
  public long getStartInstant() {
    return startInstant;
  }

  @Override
  public void run() {
    readerThread = Thread.currentThread();

    try {
      // 64 KiB userspace buffer between the frame writer and the socket. Without this, every writeFrame
      // hit the socket as a separate write syscall — JFR (2026-05-19) attributed ~13% of writer-thread
      // CPU to SocketDispatcher.write0. The BufferedOutputStream coalesces the frame-header + payload
      // writes of a single writeFrame, AND coalesces multiple writeFrames between explicit flush() calls
      // (Phase 2 of this plan exploits the latter via drainTo batching).
      var out = new BufferedOutputStream(new ThroughputOutputStream(socket.getOutputStream(), throughput), 64 * 1024);

      // Pre-size buffers to our advertised SETTINGS_MAX_FRAME_SIZE so we can read inbound frames the peer
      // sends within the limit we declared and write outbound frames up to the same size. The write buffer
      // may be grown again below if peer SETTINGS advertise a larger MAX_FRAME_SIZE than our own.
      buffers.ensureFrameReadCapacity(localSettings.maxFrameSize());
      buffers.ensureFrameWriteCapacity(localSettings.maxFrameSize());

      var frameWriter = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
      var reader = new HTTP2FrameReader(inputStream, buffers.frameReadBuffer());

      // Send our initial SETTINGS frame. The client connection preface was already read and validated by
      // ProtocolSelector, so we begin directly at the SETTINGS exchange.
      frameWriter.writeFrame(new HTTP2Frame.SettingsFrame(0, localSettings.toPayload()));
      out.flush();

      // Read the peer's first SETTINGS frame.
      var firstFrame = reader.readFrame();
      if (!(firstFrame instanceof HTTP2Frame.SettingsFrame(int flags, byte[] payload)) || (flags & HTTP2Frame.FLAG_ACK) != 0) {
        logger.debug("Expected client SETTINGS frame after preface");
        // RFC 9113 §3.5 / §5.4.1: emit GOAWAY(PROTOCOL_ERROR) before closing.
        sendGoAwayDirect(frameWriter, out, HTTP2ErrorCode.PROTOCOL_ERROR);
        // Half-close immediately so the kernel sends FIN — h2spec keeps writing preface bytes;
        // bytes arriving after the 50 ms finally-drain race the close and cause OS RST instead of FIN.
        try {
          socket.shutdownOutput();
        } catch (IOException ignore) { /* best effort */ }
        return;
      }
      peerSettings.applyPayload(payload);

      // RFC 9113 §4.2: outbound DATA frames may be up to peer's SETTINGS_MAX_FRAME_SIZE. Grow the write buffer
      // if the peer accepts larger frames than we configured locally; the frame writer holds a byte[] reference, so
      // we must rebuild it to pick up the new buffer. Safe to swap here — the writer thread has not started yet.
      if (peerSettings.maxFrameSize() > localSettings.maxFrameSize()) {
        buffers.ensureFrameWriteCapacity(peerSettings.maxFrameSize());
        frameWriter = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
      }

      // Send SETTINGS ACK.
      frameWriter.writeFrame(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
      out.flush();

      // Start the writer: it drains its private queue and serializes frames to the socket, exiting on the
      // writer-shutdown sentinel (requestStop). It is stored in a field so the reader can join it before closing
      // the socket, guaranteeing GOAWAY frames are flushed before teardown, and so shutdown() can reach it.
      writer = new HTTP2WriterThread(frameWriter, out, Thread.currentThread(), logger);
      writer.start();

      // Frame-handling loop.
      HPACKDynamicTable decoderTable = new HPACKDynamicTable(localSettings.headerTableSize());
      HPACKDecoder decoder = new HPACKDecoder(decoderTable);
      HPACKDynamicTable encoderTable = new HPACKDynamicTable(peerSettings.headerTableSize());
      HPACKEncoder encoder = new HPACKEncoder(encoderTable);

      ByteArrayOutputStream headerAccum = new ByteArrayOutputStream();
      Integer headerBlockStreamId = null;

      try {
        while (true) {
          state = HTTPConnection.State.Read;
          if (writer.isClosed()) {
            logger.debug("Writer thread closed; reader exiting");
            break;
          }
          HTTP2Frame frame;
          try {
            frame = reader.readFrame();
          } catch (HTTP2FrameReader.FrameSizeException e) {
            // RFC 9113 §4.2: frame size violations are a connection error of type FRAME_SIZE_ERROR.
            goAway(HTTP2ErrorCode.FRAME_SIZE_ERROR);
            break;
          } catch (HTTP2FrameReader.ProtocolException e) {
            // RFC 9113 §5.4.1: protocol violations detected during frame parsing are PROTOCOL_ERROR.
            goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
            break;
          }

          // RFC 9113 §6.10 — once HEADERS without END_HEADERS has been received, the next frame
          // MUST be CONTINUATION on the same stream. Anything else is a connection error PROTOCOL_ERROR.
          if (headerBlockStreamId != null) {
            boolean isContinuationOnSameStream =
                frame instanceof HTTP2Frame.ContinuationFrame cont && cont.streamId() == headerBlockStreamId;
            if (!isContinuationOnSameStream) {
              goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
              break;
            }
          }

          switch (frame) {
            case HTTP2Frame.SettingsFrame f -> handleSettings(f);
            case HTTP2Frame.PingFrame f -> handlePing(f);
            case HTTP2Frame.WindowUpdateFrame f -> handleWindowUpdate(f);
            case HTTP2Frame.RSTStreamFrame f -> handleRSTStream(f);
            case HTTP2Frame.GoawayFrame ignored -> {
              return; // Peer is shutting down — drain and exit.
            }
            case HTTP2Frame.HeadersFrame f -> {
              // RFC 9113 §5.1.1: client-initiated streams must use odd stream IDs (§5.1.1).
              if (f.streamId() == 0 || (f.streamId() & 1) == 0) {
                goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
                return;
              }
              // RFC 9113 §5.1 — HEADERS on a recently-closed stream is STREAM_CLOSED, not PROTOCOL_ERROR.
              // Must be checked before the monotonicity guard (which would fire PROTOCOL_ERROR instead).
              if (isRecentlyClosed(f.streamId())) {
                goAway(HTTP2ErrorCode.STREAM_CLOSED);
                return;
              }
              // RFC 9113 §8.1 — a second HEADERS block is valid only as request trailers, which require END_STREAM
              // AND the stream still being open in the client→server direction (state OPEN or HALF_CLOSED_LOCAL —
              // i.e. the client has not yet END_STREAM'd). Anything else (no END_STREAM, or stream already in
              // HALF_CLOSED_REMOTE/CLOSED) is an illegal mid-stream HEADERS — stream error STREAM_CLOSED per §5.1.
              HTTP2Stream existing = streams.get(f.streamId());
              if (existing != null) {
                boolean hasEndStream = (f.flags() & HTTP2Frame.FLAG_END_STREAM) != 0;
                HTTP2Stream.State streamState = existing.state();
                boolean clientStillOpen = streamState == HTTP2Stream.State.OPEN || streamState == HTTP2Stream.State.HALF_CLOSED_LOCAL;
                if (!hasEndStream || !clientStillOpen) {
                  rstStream(f.streamId(), HTTP2ErrorCode.STREAM_CLOSED);
                  break;
                }
                // Trailers path — bypass the MAX_CONCURRENT_STREAMS gate (the stream already counts toward
                // the cap and we're not opening a new one). The accumulator + size guards still apply.
                headerAccum.reset();
                headerAccum.write(f.headerBlockFragment());
                if (headerAccum.size() > localSettings.maxHeaderListSize()) {
                  goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
                  return;
                }
                if ((f.flags() & HTTP2Frame.FLAG_END_HEADERS) != 0) {
                  finalizeHeaderBlock(f.streamId(), f.flags(), headerAccum, decoder, encoder);
                }
                headerBlockStreamId = (f.flags() & HTTP2Frame.FLAG_END_HEADERS) == 0 ? f.streamId() : null;
                break;
              }
              if (f.streamId() <= highestSeenStreamId) {
                goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
                return;
              }
              highestSeenStreamId = f.streamId();
              handleHeadersFrame(f, headerAccum, decoder, encoder);
              headerBlockStreamId = (f.flags() & HTTP2Frame.FLAG_END_HEADERS) == 0 ? f.streamId() : null;
            }
            case HTTP2Frame.ContinuationFrame f -> {
              // RFC 9113 §6.10: CONTINUATION with no preceding HEADERS (headerBlockStreamId == null) is
              // a PROTOCOL_ERROR regardless of whether the previous header block ended with END_HEADERS or
              // the preceding frame was not a HEADERS/CONTINUATION at all.
              // (The headerBlockStreamId != null guard above already rejects interleaved non-CONTINUATION frames,
              // but we also need to reject CONTINUATION when no header block is open at all.)
              if (headerBlockStreamId == null) {
                goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
                return;
              }
              handleContinuationFrame(f, headerAccum, decoder, encoder);
              if ((f.flags() & HTTP2Frame.FLAG_END_HEADERS) != 0) {
                headerBlockStreamId = null;
              }
            }
            case HTTP2Frame.DataFrame f -> handleData(f);
            case HTTP2Frame.PriorityFrame f -> {
              // §5.3 — PRIORITY frames are advisory; parse and discard.
              // Half-closed-remote and open streams both accept PRIORITY (not a state error).
              // Zero stream ID is already rejected by the reader (ProtocolException).
            }
            case HTTP2Frame.PushPromiseFrame ignored -> {
              goAway(HTTP2ErrorCode.PROTOCOL_ERROR); // Clients must not push.
              return;
            }
            case HTTP2Frame.UnknownFrame ignored -> {
            } // §5.5 — ignore unknown frame types
          }
          // Rate-limit handlers call goAway() but return normally (they don't propagate the exit signal
          // by returning from run()). Check here so the frame loop doesn't keep processing flood frames.
          if (goawaySent) {
            break;
          }
        }
      } catch (HTTP2Settings.HTTP2SettingsException e) {
        // SETTINGS parameter validation failures bubble up from handleSettings(); convert to GOAWAY.
        goAway(e.errorCode);
      } catch (Throwable t) {
        // RFC 9113 §5.4.1 — any unhandled error during connection processing is a connection error.
        // Emit GOAWAY(INTERNAL_ERROR) so the peer learns the connection died deliberately, not from a
        // bare TCP FIN that looks indistinguishable from a network glitch. Must run before the finally
        // enqueues the writer-shutdown sentinel, or the GOAWAY would be queued after the sentinel and
        // never written. goAway is idempotent — safe even if an inner catch already emitted a more
        // specific code.
        logger.error("Unhandled exception in HTTP/2 reader; emitting GOAWAY(INTERNAL_ERROR)", t);
        goAway(HTTP2ErrorCode.INTERNAL_ERROR);
      } finally {
        // Signal the writer thread to exit cleanly. If the writer has already closed, this is a no-op.
        writer.requestStop();
      }
    } catch (Exception e) {
      logger.debug("HTTP/2 connection ended", e);
    } finally {
      // Wait for the writer to flush its queue (including any GOAWAY) before closing the socket. join() is a no-op if
      // the writer was never started (e.g. a failure before the SETTINGS exchange).
      if (writer != null) {
        writer.join(Duration.ofSeconds(5));
      }
      // Interrupt any handler virtual-threads still parked on the writer queue or in the per-stream send-window
      // wait loop — the connection is dead and they would otherwise hang until JVM exit. Each handler propagates
      // InterruptedIOException out of its write path and exits via its own finally.
      for (Thread t : handlerThreads) {
        t.interrupt();
      }
      // Graceful TCP teardown: shut down the output side (sends FIN to peer), drain any already-buffered
      // inbound bytes, then close. Without draining, close() on a socket with unread receive-buffer data
      // causes the OS to emit a TCP RST instead of a clean FIN — the peer sees "connection reset by peer".
      // SSLSocket.shutdownOutput() is not supported (throws UnsupportedOperationException) — suppress it.
      // The 50 ms SO_TIMEOUT limits the drain to already-buffered data: if no bytes are pending, skip()
      // returns after one 50 ms poll and terminates cleanly without blocking test shutdown paths.
      try {
        socket.shutdownOutput();
      } catch (Exception ignore) {
      }
      try {
        socket.setSoTimeout(50);
        inputStream.skip(Long.MAX_VALUE);
      } catch (IOException ignore) {
      }
      try {
        socket.close();
      } catch (IOException ignore) {
      }
    }
  }

  /**
   * Initiates a graceful shutdown by enqueuing a {@code GOAWAY(NO_ERROR)} frame with the highest seen client stream-id,
   * followed by the writer-shutdown sentinel, then waits (bounded) for the writer thread to flush the GOAWAY and exit.
   * <p>
   * The wait is essential: this method is invoked from the acceptor thread, which interrupts the connection's thread
   * immediately afterward. Interrupting a virtual thread blocked in a socket read closes the socket, which would
   * otherwise race the writer and the peer would never see the GOAWAY. Blocking here until the writer has flushed and
   * exited makes the GOAWAY-before-teardown ordering deterministic.
   */
  @Override
  public void shutdown() {
    // shutdown() runs on the acceptor thread; writer is null until the SETTINGS exchange builds it. Snapshot + guard so
    // an early shutdown cannot NPE. Nothing can be flushed before the writer exists anyway.
    HTTP2WriterThread w = writer;
    if (w != null) {
      w.enqueueOrCloseWriter(new HTTP2Frame.GoawayFrame(highestSeenStreamId, HTTP2ErrorCode.NO_ERROR.value, new byte[0]));
      // Writer-shutdown sentinel so the writer flushes the GOAWAY above and then exits.
      w.requestStop();
      w.join(Duration.ofSeconds(1));
    }
  }

  @Override
  public HTTPConnection.State state() {
    return state;
  }

  private HTTPRequest buildRequestFromHeaders(List<HPACKDynamicTable.HeaderField> fields, int streamId) {
    HTTPRequest req = new HTTPRequest(context, configuration.getContextPath(),
        listener.getCertificate() != null ? "https" : "http",
        listener.getPort(),
        socket.getInetAddress().getHostAddress());
    req.setProtocol("HTTP/2.0");
    for (var field : fields) {
      String name = field.name();
      String value = field.value();
      switch (name) {
        case ":method" -> req.setMethod(HTTPMethod.of(value));
        case ":path" -> req.setPath(value); // setPath handles query-string splitting internally
        case ":scheme" -> {
        } // Scheme derived from listener.getCertificate(); pseudo-header recorded but not applied
        case ":authority" -> req.addHeader("Host", value);
        default -> req.addHeader(name, value);
      }
    }
    return req;
  }

  private void finalizeHeaderBlock(int streamId, int flags, ByteArrayOutputStream headerAccum, HPACKDecoder decoder, HPACKEncoder encoder) throws IOException {
    List<HPACKDynamicTable.HeaderField> fields;
    try {
      fields = decoder.decode(headerAccum.toByteArray());
    } catch (IOException e) {
      // RFC 7541 §2.1 / RFC 9113 §4.3 — HPACK decode failure is a connection error of type COMPRESSION_ERROR.
      logger.debug("HPACK decode failed on stream [{}]: [{}]", streamId, e.getMessage());
      goAway(HTTP2ErrorCode.COMPRESSION_ERROR);
      return;
    }

    // Trailers path — a HEADERS block decoded for a stream that already exists. RFC 9113 §8.1.
    HTTP2Stream existingStream = streams.get(streamId);
    if (existingStream != null) {
      if (!validateHeaders(fields, streamId, true)) {
        return;
      }
      // Deliver trailers to the same HTTPRequest the handler is processing, then signal EOF on the body
      // pipe so the handler unblocks from any pending read. Trailers MUST be set before the EOF sentinel
      // so the handler that reads-then-getTrailer sees a populated trailer map.
      HTTPRequest request = existingStream.request();
      if (request != null) {
        for (var field : fields) {
          request.addTrailer(field.name(), field.value());
        }
      }
      BlockingQueue<byte[]> pipe = streamPipes.get(streamId);
      if (pipe != null) {
        try {
          pipe.put(HTTP2InputStream.eofSentinel());
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      try {
        existingStream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_END_STREAM);
      } catch (IllegalStateException e) {
        // Race with concurrent RST_STREAM — stream is already closed; trailers harmless. Log so unexpected
        // state-machine transitions in future refactors are visible.
        logger.debug("Trailers HEADERS ignored on stream [{}] in state [{}]", existingStream.streamId(), existingStream.state(), e);
      }
      return;
    }

    if (!validateHeaders(fields, streamId, false)) {
      return;
    }

    HTTPRequest request = buildRequestFromHeaders(fields, streamId);
    HTTP2Stream stream = new HTTP2Stream(streamId, localSettings.initialWindowSize(), peerSettings.initialWindowSize());
    stream.setRequest(request);

    // §8.1.2.6: track declared content-length for DATA frame validation.
    for (var f : fields) {
      if (f.name().equals("content-length")) {
        try {
          long cl = Long.parseLong(f.value());
          if (cl < 0) {
            // RFC 9113 §8.1.2.6 — negative content-length is malformed; stream error PROTOCOL_ERROR.
            logger.debug("Negative content-length [{}] on stream [{}]", cl, streamId);
            rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
            return;
          }
          stream.setDeclaredContentLength(cl);
        } catch (NumberFormatException e) {
          // RFC 9113 §8.1.2.6 — unparseable content-length is a stream error of type PROTOCOL_ERROR.
          logger.debug("Malformed content-length [{}] on stream [{}]", f.value(), streamId);
          rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
          return;
        }
        break;
      }
    }

    try {
      if ((flags & HTTP2Frame.FLAG_END_STREAM) != 0) {
        stream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_END_STREAM);
      } else {
        stream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_NO_END_STREAM);
      }
    } catch (IllegalStateException e) {
      // RFC 9113 §8.1 — HEADERS received in a state where the stream cannot accept them (e.g. HALF_CLOSED_REMOTE)
      // is a stream error, not a connection error. Emit RST_STREAM(STREAM_CLOSED) and discard this stream.
      rstStream(streamId, HTTP2ErrorCode.STREAM_CLOSED);
      return;
    }
    streams.put(streamId, stream);

    // Apply the server's MultipartConfiguration as a deep copy so the handler may mutate it per-request without
    // affecting the shared server-level config. Matches HTTP1Connection.java behavior.
    request.getMultiPartStreamProcessor().setMultipartConfiguration(new MultipartConfiguration(configuration.getMultipartConfiguration()));

    if ((flags & HTTP2Frame.FLAG_END_STREAM) != 0) {
      // No body will follow. Skip the per-stream pipe, HTTP2InputStream, PushbackInputStream, and HTTPInputStream
      // allocations entirely — the handler sees a zero-allocation empty stream. Any DATA frame that arrives after
      // END_STREAM-on-HEADERS is a client protocol violation; handleData handles a missing pipe by ignoring per §6.1.
      request.setInputStream(EmptyHTTPInputStream.INSTANCE);
    } else {
      ArrayBlockingQueue<byte[]> pipe = new ArrayBlockingQueue<>(16);
      streamPipes.put(streamId, pipe);
      HTTP2InputStream inputStream = new HTTP2InputStream(pipe);
      long maximumContentLength = HTTPTools.getMaxRequestBodySize(request.getContentType(), configuration.getMaxRequestBodySize());
      request.setInputStream(new HTTPInputStream(configuration, request,
          new PushbackInputStream(inputStream, instrumenter), maximumContentLength));
    }

    HTTPResponse response = new HTTPResponse();

    spawnHandlerThread(request, response, stream, encoder);
    handledRequests++;
  }

  private void goAway(HTTP2ErrorCode code) {
    if (goawaySent) {
      return; // Idempotent — only one GOAWAY per connection.
    }
    goawaySent = true;
    // Use the highest seen client stream-id.
    // lastStreamId == -1 is reserved as the writer-shutdown sentinel and must never be used for a real GOAWAY.
    writer.enqueueOrCloseWriter(new HTTP2Frame.GoawayFrame(highestSeenStreamId, code.value, new byte[0]));
  }

  private void handleContinuationFrame(HTTP2Frame.ContinuationFrame f, ByteArrayOutputStream headerAccum, HPACKDecoder decoder, HPACKEncoder encoder) throws IOException {
    // RFC 9113 §5.1 — frames on recently-closed streams are a STREAM_CLOSED connection error.
    if (isRecentlyClosed(f.streamId())) {
      goAway(HTTP2ErrorCode.STREAM_CLOSED);
      return;
    }
    headerAccum.write(f.headerBlockFragment());
    // CVE-2024-27316: bound cumulative HEADERS+CONTINUATION accumulator to MAX_HEADER_LIST_SIZE.
    if (headerAccum.size() > localSettings.maxHeaderListSize()) {
      goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
      return;
    }
    if ((f.flags() & HTTP2Frame.FLAG_END_HEADERS) != 0) {
      finalizeHeaderBlock(f.streamId(), f.flags(), headerAccum, decoder, encoder);
    }
  }

  private void handleData(HTTP2Frame.DataFrame f) {
    // RFC 9113 §6.1: DATA on stream 0 is a connection error PROTOCOL_ERROR.
    if (f.streamId() == 0) {
      goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
      return;
    }
    // Rate limit: empty DATA without END_STREAM.
    if (f.payload().length == 0 && (f.flags() & HTTP2Frame.FLAG_END_STREAM) == 0) {
      if (rateLimits.recordEmptyData()) {
        goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
        return;
      }
    }
    HTTP2Stream stream = streams.get(f.streamId());
    BlockingQueue<byte[]> pipe = streamPipes.get(f.streamId());
    if (stream == null || pipe == null) {
      // RFC 9113 §5.1 — DATA on a recently-closed stream is a STREAM_CLOSED connection error.
      if (isRecentlyClosed(f.streamId())) {
        goAway(HTTP2ErrorCode.STREAM_CLOSED);
        return;
      }
      // RFC 9113 §5.1 — DATA on an idle (never-opened) client-initiated stream is a connection-level PROTOCOL_ERROR.
      // Client-initiated streams are odd-numbered; stream IDs beyond highestSeenStreamId have never been opened.
      if (f.streamId() > highestSeenStreamId && (f.streamId() & 1) == 1) {
        goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
        return;
      }
      // Truly unknown stream ID: ignore per §6.1.
      return;
    }
    if (f.payload().length > 0) {
      // §8.1.2.6: check that running DATA total does not exceed declared content-length.
      if (!stream.appendDataBytes(f.payload().length)) {
        rstStream(f.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
        return;
      }
      stream.consumeReceiveWindow(f.payload().length);
      try {
        long timeoutMs = configuration.getHTTP2HandlerReadTimeout().toMillis();
        if (!pipe.offer(f.payload(), timeoutMs, TimeUnit.MILLISECONDS)) {
          // RFC 9113 §5.2 flow control is the intended back-pressure mechanism — but if a handler is not consuming
          // its body at all (stuck or buggy), the per-stream pipe fills and blocking the reader thread would freeze
          // every other stream on this connection. Cancel the offending stream instead.
          logger.debug("h2 handler on stream [{}] did not consume body within [{}ms]; sending RST_STREAM(CANCEL)",
              f.streamId(), timeoutMs);
          rstStream(f.streamId(), HTTP2ErrorCode.CANCEL);
          streams.remove(f.streamId());
          streamPipes.remove(f.streamId());
          return;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
    if ((f.flags() & HTTP2Frame.FLAG_END_STREAM) != 0) {
      // §8.1.2.6: when END_STREAM arrives, verify total DATA matches declared content-length.
      if (!stream.dataLengthMatches()) {
        rstStream(f.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
        return;
      }
      try {
        pipe.put(HTTP2InputStream.eofSentinel());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      stream.applyEvent(HTTP2Stream.Event.RECV_DATA_END_STREAM);
    }

    // Replenish-when-half-empty strategy (RFC 9113 §6.9.1).
    // When the stream receive-window drops below half its initial value, send a WINDOW_UPDATE to restore it.
    // Without this, uploads larger than INITIAL_WINDOW_SIZE (65535) stall waiting for credit.
    if (f.payload().length > 0) {
      if (stream.receiveWindow() < (long) localSettings.initialWindowSize() / 2) {
        int delta = localSettings.initialWindowSize() - (int) stream.receiveWindow();
        stream.incrementReceiveWindow(delta);
        writer.enqueueOrCloseWriter(new HTTP2Frame.WindowUpdateFrame(f.streamId(), delta));
      }
      // Also replenish the connection-level window for the consumed bytes so the peer can keep sending.
      writer.enqueueOrCloseWriter(new HTTP2Frame.WindowUpdateFrame(0, f.payload().length));
    }
  }

  private void handleHeadersFrame(HTTP2Frame.HeadersFrame f, ByteArrayOutputStream headerAccum, HPACKDecoder decoder, HPACKEncoder encoder) throws IOException {
    // Enforce MAX_CONCURRENT_STREAMS before any per-stream allocation (headerAccum, ArrayBlockingQueue, etc.).
    // This ensures a HEADERS flood cannot exhaust heap even if the cap is reached.
    if (streams.size() >= localSettings.maxConcurrentStreams()) {
      writer.enqueueOrCloseWriter(new HTTP2Frame.RSTStreamFrame(f.streamId(), HTTP2ErrorCode.REFUSED_STREAM.value));
      return;
    }
    headerAccum.reset();
    headerAccum.write(f.headerBlockFragment());
    // CVE-2024-27316: bound cumulative HEADERS+CONTINUATION accumulator to MAX_HEADER_LIST_SIZE.
    if (headerAccum.size() > localSettings.maxHeaderListSize()) {
      goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
      return;
    }
    if ((f.flags() & HTTP2Frame.FLAG_END_HEADERS) != 0) {
      finalizeHeaderBlock(f.streamId(), f.flags(), headerAccum, decoder, encoder);
    }
  }

  private void handlePing(HTTP2Frame.PingFrame f) {
    if ((f.flags() & HTTP2Frame.FLAG_ACK) != 0) {
      return; // An ACK to our PING; nothing to do.
    }
    if (rateLimits.recordPing()) {
      goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
      return;
    }
    writer.enqueueOrCloseWriter(new HTTP2Frame.PingFrame(HTTP2Frame.FLAG_ACK, f.opaqueData()));
  }

  private void handleRSTStream(HTTP2Frame.RSTStreamFrame f) {
    // Rate-limit check first: the rapid-reset attack sends many RST_STREAMs in rapid succession.
    if (rateLimits.recordRstStream()) {
      goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
      return;
    }
    // RFC 9113 §6.4 — RST_STREAM on an idle stream (never opened, not recently closed) is a connection error.
    // An "idle" stream is one with an ID we have never seen (beyond highestSeenStreamId and not in any table).
    HTTP2Stream stream = streams.get(f.streamId());
    if (stream == null && !isRecentlyClosed(f.streamId()) && f.streamId() > highestSeenStreamId) {
      goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
      return;
    }
    if (stream != null) {
      try {
        stream.applyEvent(HTTP2Stream.Event.RECV_RST_STREAM);
      } catch (IllegalStateException ignored) {
        // Stream already CLOSED — handler completed and sent END_STREAM before this RST arrived (common
        // in rapid-reset patterns where the client RSTs every just-opened stream). The RST is now harmless;
        // cleanup below is still safe to run.
        logger.debug("RST_STREAM on already-closed stream [{}] — ignoring", f.streamId());
      }
      streams.remove(f.streamId());
      BlockingQueue<byte[]> pipe = streamPipes.remove(f.streamId());
      markClosed(f.streamId());
      if (pipe != null) {
        try {
          pipe.put(HTTP2InputStream.eofSentinel());
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private void handleSettings(HTTP2Frame.SettingsFrame f) {
    if ((f.flags() & HTTP2Frame.FLAG_ACK) != 0) {
      return; // ACK to our SETTINGS; nothing to do.
    }
    if (rateLimits.recordSettings()) {
      goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
      return;
    }
    // Apply peer's settings change.
    int oldInitialWindow = peerSettings.initialWindowSize();
    peerSettings.applyPayload(f.payload());
    int newInitialWindow = peerSettings.initialWindowSize();
    // RFC 9113 §6.9.2 — adjust open streams' send-windows by the delta and signal blocked writers.
    if (newInitialWindow != oldInitialWindow) {
      int delta = newInitialWindow - oldInitialWindow;
      for (HTTP2Stream s : streams.values()) {
        s.incrementSendWindow(delta);
        synchronized (s) {
          s.notifyAll();
        }
      }
    }
    // ACK the peer's SETTINGS.
    writer.enqueueOrCloseWriter(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
  }

  private void handleWindowUpdate(HTTP2Frame.WindowUpdateFrame f) {
    if (rateLimits.recordWindowUpdate()) {
      goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
      return;
    }
    if (f.streamId() == 0) {
      // RFC 9113 §6.9: zero increment on the connection window is a connection error PROTOCOL_ERROR.
      if (f.windowSizeIncrement() == 0) {
        goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
        return;
      }
      // RFC 9113 §6.9.1: connection send-window overflow is a FLOW_CONTROL_ERROR.
      if (connectionSendWindow.available() + f.windowSizeIncrement() > Integer.MAX_VALUE) {
        goAway(HTTP2ErrorCode.FLOW_CONTROL_ERROR);
        return;
      }
      connectionSendWindow.increment(f.windowSizeIncrement());
      return;
    }
    // RFC 9113 §6.9: zero increment on a stream window is a stream error PROTOCOL_ERROR.
    if (f.windowSizeIncrement() == 0) {
      rstStream(f.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
      return;
    }
    // RFC 9113 §5.1 — WINDOW_UPDATE on an idle (never-opened) client-initiated stream is a connection-level
    // PROTOCOL_ERROR. Client-initiated streams are odd-numbered; stream IDs beyond highestSeenStreamId have never
    // been opened.
    if (f.streamId() > highestSeenStreamId && (f.streamId() & 1) == 1) {
      goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
      return;
    }
    HTTP2Stream stream = streams.get(f.streamId());
    if (stream != null) {
      // RFC 9113 §6.9.1: per-stream send-window overflow is a stream error FLOW_CONTROL_ERROR.
      if ((long) stream.sendWindow() + f.windowSizeIncrement() > Integer.MAX_VALUE) {
        rstStream(f.streamId(), HTTP2ErrorCode.FLOW_CONTROL_ERROR);
        return;
      }
      stream.incrementSendWindow(f.windowSizeIncrement());
      synchronized (stream) {
        stream.notifyAll();
      }
    }
  }

  /**
   * Returns {@code true} if {@code streamId} is in the recently-closed set. Call only from the reader thread.
   */
  private boolean isRecentlyClosed(int streamId) {
    return recentlyClosedStreams.contains(streamId);
  }

  /**
   * Records {@code streamId} as recently closed. Evicts the oldest entry when the deque exceeds
   * {@link #MAX_RECENTLY_CLOSED}. Call only from the reader thread.
   */
  private void markClosed(int streamId) {
    recentlyClosedStreams.addLast(streamId);
    if (recentlyClosedStreams.size() > MAX_RECENTLY_CLOSED) {
      recentlyClosedStreams.removeFirst();
    }
  }

  /**
   * Sends a stream-level error by enqueueing an RST_STREAM frame for {@code streamId}. Use this for stream errors (RFC
   * 9113 §5.4.2), not connection errors.
   */
  private void rstStream(int streamId, HTTP2ErrorCode code) {
    writer.enqueueOrCloseWriter(new HTTP2Frame.RSTStreamFrame(streamId, code.value));
  }

  /**
   * Writes a GOAWAY frame directly to the wire — bypassing the writer queue. Used only during the connection preamble
   * phase (before the writer virtual-thread is started) to ensure the peer receives the error frame before the TCP
   * connection is closed.
   */
  private void sendGoAwayDirect(HTTP2FrameWriter writer, OutputStream out, HTTP2ErrorCode code) {
    try {
      writer.writeFrame(new HTTP2Frame.GoawayFrame(highestSeenStreamId, code.value, new byte[0]));
      out.flush();
    } catch (IOException ignore) {
      // Best-effort: if the peer already closed, suppress the write error.
    }
  }

  private void spawnHandlerThread(HTTPRequest request, HTTPResponse response, HTTP2Stream stream, HPACKEncoder encoder) {
    Thread.ofVirtual().name("h2-handler-" + stream.streamId()).start(() -> {
      Thread self = Thread.currentThread();
      handlerThreads.add(self);
      try {
        // Use a lazy-header output stream: HEADERS are emitted on the first write or flush so that the
        // handler can interleave request reads and response writes (required for bidi-streaming).
        // RFC 9113 §8.1 requires HEADERS to precede DATA frames — the HTTP2OutputProtocol enforces this.
        var protocol = new HTTP2OutputProtocol(response, stream, encoder);
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

        streams.remove(stream.streamId());
        streamPipes.remove(stream.streamId());
      } catch (HTTPProcessingException e) {
        // Expected processing errors (e.g. ContentTooLargeException → 413). Send a proper HTTP error response
        // so the client receives the status code rather than a RST_STREAM(INTERNAL_ERROR).
        logger.debug("h2 handler processing exception on stream [{}]: [{}]", stream.streamId(), e.getMessage());
        try {
          response.setStatus(e.getStatus());
          var errorProtocol = new HTTP2OutputProtocol(response, stream, encoder);
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
          rstStream(stream.streamId(), HTTP2ErrorCode.NO_ERROR);
        }
        streams.remove(stream.streamId());
        streamPipes.remove(stream.streamId());
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
        streams.remove(stream.streamId());
        streamPipes.remove(stream.streamId());
      } finally {
        handlerThreads.remove(self);
      }
    });
  }

  /**
   * Validates the decoded header list per RFC 9113 §8.1.2.*. Returns {@code true} if valid. On any violation, enqueues
   * RST_STREAM(PROTOCOL_ERROR) for {@code streamId} and returns {@code false}.
   */
  private boolean validateHeaders(List<HPACKDynamicTable.HeaderField> fields, int streamId, boolean isTrailer) {
    boolean seenRegularHeader = false;
    Set<String> seenPseudo = new HashSet<>();

    for (var f : fields) {
      String name = f.name();

      // §8.1.2/1: header names MUST be lowercase.
      for (int i = 0; i < name.length(); i++) {
        char c = name.charAt(i);
        if (c >= 'A' && c <= 'Z') {
          rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
          return false;
        }
      }

      boolean isPseudo = name.startsWith(":");
      if (isPseudo) {
        // §8.1.2.1/3: pseudo-headers are forbidden in trailers.
        if (isTrailer) {
          rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
          return false;
        }
        // §8.1.2.1/4: pseudo-header after a regular header.
        if (seenRegularHeader) {
          rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
          return false;
        }
        // §8.1.2.1/1 + §8.1.2.1/2: unknown pseudo-header or response pseudo-header in request.
        if (!HTTPValues.Headers.RequestPseudoHeaders.contains(name)) {
          rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
          return false;
        }
        // §8.1.2.3/5–7: pseudo-headers MUST NOT appear more than once.
        if (!seenPseudo.add(name)) {
          rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
          return false;
        }
      } else {
        seenRegularHeader = true;
        // §8.1.2.2/1: connection-specific headers are forbidden.
        if (HTTPValues.Headers.ConnectionSpecificHeaders.contains(name)) {
          rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
          return false;
        }
        // §8.1.2.2/2: TE header may only contain "trailers".
        if (name.equals("te") && !f.value().equalsIgnoreCase("trailers")) {
          rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
          return false;
        }
      }
    }

    if (!isTrailer) {
      // §8.1.2.3/2,3,4: required request pseudo-headers must be present.
      if (!seenPseudo.contains(":method")) {
        rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
        return false;
      }
      if (!seenPseudo.contains(":scheme")) {
        rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
        return false;
      }
      if (!seenPseudo.contains(":path")) {
        rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
        return false;
      }
      // §8.1.2.3/1: :path must not be empty.
      for (var f : fields) {
        if (f.name().equals(":path") && f.value().isEmpty()) {
          rstStream(streamId, HTTP2ErrorCode.PROTOCOL_ERROR);
          return false;
        }
      }
    }

    return true;
  }

  /**
   * HTTP/2 implementation of {@link HTTPOutputProtocol}. On the first write or flush the response status and headers
   * are HPACK-encoded and enqueued as a HEADERS frame (lazy emission preserves bidi-streaming); body bytes flow into an
   * {@link HTTP2OutputStream} that fragments DATA frames under flow control. Trailers ride a trailing HEADERS frame.
   *
   * <p>RFC 9113 §8.1 — HEADERS must precede DATA. This class enforces that invariant.
   */
  private class HTTP2OutputProtocol implements HTTPOutputProtocol {
    private final HPACKEncoder encoder;
    private final HTTPResponse response;
    private final HTTP2Stream stream;
    private HTTP2OutputStream sink;
    private boolean streamReset;
    private boolean wroteToClient;

    HTTP2OutputProtocol(HTTPResponse response, HTTP2Stream stream, HPACKEncoder encoder) {
      this.response = response;
      this.stream = stream;
      this.encoder = encoder;
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
}

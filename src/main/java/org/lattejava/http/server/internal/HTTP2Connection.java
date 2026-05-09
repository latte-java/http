/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.io.PushbackInputStream;

/**
 * Per-connection HTTP/2 state and lifecycle. Owns the socket I/O, frame codec, HPACK state, and stream registry.
 *
 * <p>Threading model:
 * <ul>
 *   <li>The calling thread (reader thread) runs {@link #run()}, reads all inbound frames, and dispatches
 *       to handler methods.</li>
 *   <li>A single writer virtual-thread serializes all outbound frames from {@link #writerQueue} to the
 *       socket. It exits when it dequeues the writer-shutdown sentinel (a GoawayFrame with lastStreamId == -1).</li>
 *   <li>Each request spawns a handler virtual-thread that runs the application {@link HTTPHandler}, then
 *       enqueues response HEADERS and DATA frames to the writer queue.</li>
 * </ul>
 *
 * @author Daniel DeGroff
 */
public class HTTP2Connection implements ClientConnection, Runnable {
  // RFC 9113 §8.1.2.2: headers that are connection-specific and forbidden in HTTP/2.
  private static final Set<String> CONNECTION_SPECIFIC_HEADERS = Set.of(
      "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade"
  );
  private static final Set<String> H1_ONLY_HEADERS = Set.of(
      "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade"
  );
  private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
  // RFC 9113 §8.1.2.1: the only pseudo-headers valid in a client request.
  private static final Set<String> REQUEST_PSEUDO_HEADERS = Set.of(":authority", ":method", ":path", ":scheme");

  // Maximum number of recently-closed stream IDs to remember for §5.1 STREAM_CLOSED detection.
  private static final int MAX_RECENTLY_CLOSED = 100;

  private final HTTPBuffers buffers;
  private final HTTPServerConfiguration configuration;
  // Lock object used to notify handler threads waiting on connection-level send-window credit.
  private final Object connectionSendWindowLock = new Object();
  private final HTTPContext context;
  private final Instrumenter instrumenter;
  private final HTTPListenerConfiguration listener;
  private final HTTP2Settings localSettings;
  private final Logger logger;
  private final HTTP2Settings peerSettings = HTTP2Settings.defaults();
  private final boolean prefaceAlreadyConsumed;
  private final HTTP2RateLimits rateLimits;
  // Bounded deque of recently-closed stream IDs for RFC 9113 §5.1 STREAM_CLOSED error detection.
  // Access is confined to the reader thread, so no synchronization is needed.
  private final Deque<Integer> recentlyClosedStreams = new ArrayDeque<>();
  // True for the h2c Upgrade/101 path: server sends its SETTINGS frame immediately after the 101, before reading the
  // client connection preface. All other paths (ALPN, prior-knowledge) leave this false.
  private final boolean serverSendsFirst;
  private final Socket socket;
  private final long startInstant;
  private final Map<Integer, HTTP2Stream> streams = new ConcurrentHashMap<>();
  private final Map<Integer, BlockingQueue<byte[]>> streamPipes = new ConcurrentHashMap<>();
  private final Throughput throughput;
  private final BlockingQueue<HTTP2Frame> writerQueue = new LinkedBlockingQueue<>(128);
  // Connection-level send window (RFC 9113 §6.9). Initial value is the HTTP/2 default (65535).
  // Tracked only for overflow detection; not yet used to throttle outbound DATA frames.
  private int connectionSendWindow = 65535;
  private volatile boolean goawaySent;
  private long handledRequests;
  private volatile int highestSeenStreamId = 0;
  private volatile ClientConnection.State state = ClientConnection.State.Read;

  public HTTP2Connection(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter,
                         HTTPListenerConfiguration listener, Throughput throughput, Boolean prefaceAlreadyConsumed) throws IOException {
    this(socket, configuration, context, instrumenter, listener, throughput, prefaceAlreadyConsumed, false);
  }

  public HTTP2Connection(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter,
                         HTTPListenerConfiguration listener, Throughput throughput, Boolean prefaceAlreadyConsumed,
                         boolean serverSendsFirst) throws IOException {
    this.socket = socket;
    this.configuration = configuration;
    this.context = context;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.throughput = throughput;
    this.buffers = new HTTPBuffers(configuration);
    this.logger = configuration.getLoggerFactory().getLogger(HTTP2Connection.class);
    this.localSettings = configuration.getHTTP2Settings();
    this.rateLimits = configuration.getHTTP2RateLimits();
    this.prefaceAlreadyConsumed = Boolean.TRUE.equals(prefaceAlreadyConsumed);
    this.serverSendsFirst = serverSendsFirst;
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
  public ClientConnection.State state() {
    return state;
  }

  /**
   * Initiates a graceful shutdown by enqueuing a {@code GOAWAY(NO_ERROR)} frame with the highest seen client stream-id.
   * The writer thread emits it and in-flight streams are given up to the server's configured shutdown duration to
   * complete before the socket is force-closed by {@link HTTPServer}.
   */
  public void shutdown() {
    try {
      writerQueue.put(new HTTP2Frame.GoawayFrame(highestSeenStreamId, HTTP2ErrorCode.NO_ERROR.value, new byte[0]));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void run() {
    Thread writerThread = null;
    InputStream socketIn = null;
    try {
      var in = new ThroughputInputStream(socket.getInputStream(), throughput);
      socketIn = in;
      var out = new ThroughputOutputStream(socket.getOutputStream(), throughput);

      var writer = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
      var reader = new HTTP2FrameReader(in, buffers.frameReadBuffer());

      if (serverSendsFirst) {
        // h2c Upgrade/101 path (RFC 7540 §3.2): server sends its connection preface (SETTINGS) immediately after the
        // 101, before reading the client preface. The client sends its preface concurrently; we read it next.
        writer.writeFrame(new HTTP2Frame.SettingsFrame(0, encodeSettings(localSettings)));
        out.flush();

        // Read and validate the client connection preface.
        byte[] received = in.readNBytes(PREFACE.length);
        if (!Arrays.equals(received, PREFACE)) {
          logger.debug("Invalid HTTP/2 connection preface after h2c upgrade");
          // RFC 9113 §3.5: server has already sent SETTINGS; emit GOAWAY(PROTOCOL_ERROR) before closing.
          sendGoAwayDirect(writer, out, HTTP2ErrorCode.PROTOCOL_ERROR);
          return;
        }
      } else {
        // ALPN (TLS) and prior-knowledge paths: read the client connection preface first (or skip if ProtocolSelector
        // already consumed it), then send our SETTINGS.
        if (!prefaceAlreadyConsumed) {
          byte[] received = in.readNBytes(PREFACE.length);
          if (!Arrays.equals(received, PREFACE)) {
            logger.debug("Invalid HTTP/2 connection preface");
            // RFC 9113 §3.5: emit SETTINGS + GOAWAY(PROTOCOL_ERROR) so peer can observe the error before TCP close.
            writer.writeFrame(new HTTP2Frame.SettingsFrame(0, encodeSettings(localSettings)));
            sendGoAwayDirect(writer, out, HTTP2ErrorCode.PROTOCOL_ERROR);
            return;
          }
        }

        // Send our initial SETTINGS frame.
        writer.writeFrame(new HTTP2Frame.SettingsFrame(0, encodeSettings(localSettings)));
        out.flush();
      }

      // Read the peer's first SETTINGS frame.
      var firstFrame = reader.readFrame();
      if (!(firstFrame instanceof HTTP2Frame.SettingsFrame settings) || (settings.flags() & HTTP2Frame.FLAG_ACK) != 0) {
        logger.debug("Expected client SETTINGS frame after preface");
        // RFC 9113 §3.5 / §5.4.1: emit GOAWAY(PROTOCOL_ERROR) before closing.
        sendGoAwayDirect(writer, out, HTTP2ErrorCode.PROTOCOL_ERROR);
        return;
      }
      peerSettings.applyPayload(settings.payload());

      // Send SETTINGS ACK.
      writer.writeFrame(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
      out.flush();

      // Spawn the writer virtual-thread. It drains writerQueue and serializes frames to the socket.
      // It exits cleanly when it dequeues the writer-shutdown sentinel:
      //   a GoawayFrame with lastStreamId == -1 (negative, never valid for a real GOAWAY).
      // The thread reference is stored so the reader thread can join it before closing the socket,
      // guaranteeing that GOAWAY frames are fully flushed before the connection is torn down.
      HTTP2FrameWriter writerForThread = writer;
      OutputStream outForThread = out;
      writerThread = Thread.ofVirtual().name("h2-writer").start(() -> {
        try {
          while (true) {
            HTTP2Frame f = writerQueue.take();
            if (f instanceof HTTP2Frame.GoawayFrame g && g.lastStreamId() == -1) {
              // Sentinel: shut down the writer thread cleanly.
              return;
            }
            writerForThread.writeFrame(f);
            outForThread.flush();
          }
        } catch (Exception e) {
          logger.debug("Writer thread ended", e);
        }
      });

      // Frame-handling loop.
      HPACKDynamicTable decoderTable = new HPACKDynamicTable(localSettings.headerTableSize());
      HPACKDecoder decoder = new HPACKDecoder(decoderTable);
      HPACKDynamicTable encoderTable = new HPACKDynamicTable(peerSettings.headerTableSize());
      HPACKEncoder encoder = new HPACKEncoder(encoderTable);

      ByteArrayOutputStream headerAccum = new ByteArrayOutputStream();
      Integer headerBlockStreamId = null;

      try {
        while (true) {
          state = ClientConnection.State.Read;
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
              // RFC 9113 §5.1 — HEADERS on an already-open stream (e.g. HALF_CLOSED_REMOTE) is a stream error
              // STREAM_CLOSED, not a connection error. Emit RST_STREAM and continue serving the connection.
              if (streams.containsKey(f.streamId())) {
                rstStream(f.streamId(), HTTP2ErrorCode.STREAM_CLOSED);
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
            case HTTP2Frame.UnknownFrame ignored -> {} // §5.5 — ignore unknown frame types
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
      } finally {
        // Signal writer thread to exit cleanly.
        try {
          writerQueue.put(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
        }
      }
    } catch (Exception e) {
      logger.debug("HTTP/2 connection ended", e);
    } finally {
      // Wait for the writer thread to finish flushing its queue (including any GOAWAY frames) before closing
      // the socket. Without this join, the socket.close() can race with the GOAWAY write and the peer sees EOF
      // instead of the GOAWAY frame.
      try {
        if (writerThread != null) {
          writerThread.join(Duration.ofSeconds(5));
        }
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
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
      if (socketIn != null) {
        try {
          socket.setSoTimeout(50);
          socketIn.skip(Long.MAX_VALUE);
        } catch (IOException ignore) {
        }
      }
      try {
        socket.close();
      } catch (IOException ignore) {
      }
    }
  }

  private void finalizeHeaderBlock(int streamId, int flags, ByteArrayOutputStream headerAccum, HPACKDecoder decoder, HPACKEncoder encoder) throws IOException {
    List<HPACKDynamicTable.HeaderField> fields = decoder.decode(headerAccum.toByteArray());

    if (!validateHeaders(fields, streamId, false)) {
      return;
    }

    HTTPRequest request = buildRequestFromHeaders(fields, streamId);
    HTTP2Stream stream = new HTTP2Stream(streamId, localSettings.initialWindowSize(), peerSettings.initialWindowSize());

    // §8.1.2.6: track declared content-length for DATA frame validation.
    for (var f : fields) {
      if (f.name().equals("content-length")) {
        try {
          stream.setDeclaredContentLength(Long.parseLong(f.value()));
        } catch (NumberFormatException ignore) {
          // Malformed content-length — let handler deal with it.
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

    ArrayBlockingQueue<byte[]> pipe = new ArrayBlockingQueue<>(16);
    streamPipes.put(streamId, pipe);
    HTTP2InputStream inputStream = new HTTP2InputStream(pipe);
    // Pass -1 for unlimited content length. Integer.MAX_VALUE would cause an integer overflow in
    // HTTPInputStream's boundary check: maximumContentLength - bytesRead + 1 overflows to Integer.MIN_VALUE.
    request.setInputStream(new HTTPInputStream(configuration, request,
        new PushbackInputStream(inputStream, instrumenter), -1));

    // For END_STREAM-on-HEADERS (no body), pre-populate the EOF sentinel so the handler's input read returns -1 immediately.
    if ((flags & HTTP2Frame.FLAG_END_STREAM) != 0) {
      try {
        pipe.put(HTTP2InputStream.eofSentinel());
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
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
    try {
      writerQueue.put(new HTTP2Frame.GoawayFrame(highestSeenStreamId, code.value, new byte[0]));
    } catch (InterruptedException ignore) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Writes a GOAWAY frame directly to the wire — bypassing the writer queue. Used only during the connection
   * preamble phase (before the writer virtual-thread is started) to ensure the peer receives the error frame
   * before the TCP connection is closed.
   */
  private void sendGoAwayDirect(HTTP2FrameWriter writer, OutputStream out, HTTP2ErrorCode code) {
    try {
      writer.writeFrame(new HTTP2Frame.GoawayFrame(highestSeenStreamId, code.value, new byte[0]));
      out.flush();
    } catch (IOException ignore) {
      // Best-effort: if the peer already closed, suppress the write error.
    }
  }

  /**
   * Sends a stream-level error by enqueueing an RST_STREAM frame for {@code streamId}.
   * Use this for stream errors (RFC 9113 §5.4.2), not connection errors.
   */
  private void rstStream(int streamId, HTTP2ErrorCode code) {
    try {
      writerQueue.put(new HTTP2Frame.RSTStreamFrame(streamId, code.value));
    } catch (InterruptedException ignore) {
      Thread.currentThread().interrupt();
    }
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
        pipe.put(f.payload());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
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
        try {
          writerQueue.put(new HTTP2Frame.WindowUpdateFrame(f.streamId(), delta));
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
        }
      }
      // Also replenish the connection-level window for the consumed bytes so the peer can keep sending.
      try {
        writerQueue.put(new HTTP2Frame.WindowUpdateFrame(0, f.payload().length));
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void handleHeadersFrame(HTTP2Frame.HeadersFrame f, ByteArrayOutputStream headerAccum, HPACKDecoder decoder, HPACKEncoder encoder) throws IOException {
    // Enforce MAX_CONCURRENT_STREAMS before any per-stream allocation (headerAccum, ArrayBlockingQueue, etc.).
    // This ensures a HEADERS flood cannot exhaust heap even if the cap is reached.
    if (streams.size() >= localSettings.maxConcurrentStreams()) {
      try {
        writerQueue.put(new HTTP2Frame.RSTStreamFrame(f.streamId(), HTTP2ErrorCode.REFUSED_STREAM.value));
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
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
    try {
      writerQueue.put(new HTTP2Frame.PingFrame(HTTP2Frame.FLAG_ACK, f.opaqueData()));
    } catch (InterruptedException ignore) {
      Thread.currentThread().interrupt();
    }
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
      stream.applyEvent(HTTP2Stream.Event.RECV_RST_STREAM);
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
    try {
      writerQueue.put(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
    } catch (InterruptedException ignore) {
      Thread.currentThread().interrupt();
    }
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
      if ((long) connectionSendWindow + f.windowSizeIncrement() > Integer.MAX_VALUE) {
        goAway(HTTP2ErrorCode.FLOW_CONTROL_ERROR);
        return;
      }
      connectionSendWindow += f.windowSizeIncrement();
      synchronized (connectionSendWindowLock) {
        connectionSendWindowLock.notifyAll();
      }
      return;
    }
    // RFC 9113 §6.9: zero increment on a stream window is a stream error PROTOCOL_ERROR.
    if (f.windowSizeIncrement() == 0) {
      rstStream(f.streamId(), HTTP2ErrorCode.PROTOCOL_ERROR);
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
   * Validates the decoded header list per RFC 9113 §8.1.2.*. Returns {@code true} if valid.
   * On any violation, enqueues RST_STREAM(PROTOCOL_ERROR) for {@code streamId} and returns {@code false}.
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
        if (!REQUEST_PSEUDO_HEADERS.contains(name)) {
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
        if (CONNECTION_SPECIFIC_HEADERS.contains(name)) {
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
        case ":scheme" -> {} // Scheme derived from listener.getCertificate(); pseudo-header recorded but not applied
        case ":authority" -> req.addHeader("Host", value);
        default -> req.addHeader(name, value);
      }
    }
    return req;
  }

  private void spawnHandlerThread(HTTPRequest request, HTTPResponse response, HTTP2Stream stream, HPACKEncoder encoder) {
    Thread.ofVirtual().name("h2-handler-" + stream.streamId()).start(() -> {
      try {
        // Use a lazy-header output stream: HEADERS are emitted on the first write or flush so that the
        // handler can interleave request reads and response writes (required for bidi-streaming).
        // RFC 9113 §8.1 requires HEADERS to precede DATA frames — the LazyHeaderOutputStream enforces this.
        var lazyOut = new LazyHeaderOutputStream(response, stream, encoder);
        response.setRawOutputStream(lazyOut);

        configuration.getHandler().handle(request, response);

        // Ensure the output is closed even if the handler did not call out.close() explicitly.
        lazyOut.close();

        try {
          stream.applyEvent(HTTP2Stream.Event.SEND_DATA_END_STREAM);
        } catch (IllegalStateException ignored) {
          // Stream was reset by the client (RECV_RST_STREAM) between our last write and now.
          // The DATA frame is already in the writer queue; the RST_STREAM from the client implicitly
          // cancels it. Not an error — this is normal during graceful teardown or test probing.
        }

        streams.remove(stream.streamId());
        streamPipes.remove(stream.streamId());
      } catch (Exception e) {
        logger.error("h2 handler exception", e);
        try {
          writerQueue.put(new HTTP2Frame.RSTStreamFrame(stream.streamId(), HTTP2ErrorCode.INTERNAL_ERROR.value));
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
        }
        streams.remove(stream.streamId());
        streamPipes.remove(stream.streamId());
      }
    });
  }

  /**
   * Wraps the per-stream {@link HTTP2OutputStream} with lazy HEADERS emission. On the first write or
   * flush the current response status and headers are encoded and enqueued as an HTTP/2 HEADERS frame;
   * subsequent writes and flushes are delegated directly to the underlying stream, enabling bidi-streaming
   * handlers to interleave request reads and response writes.
   *
   * <p>RFC 9113 §8.1 — HEADERS must precede DATA. This class enforces that invariant.
   */
  private class LazyHeaderOutputStream extends OutputStream {
    private final HTTPResponse response;
    private final HTTP2Stream stream;
    private final HPACKEncoder encoder;
    private HTTP2OutputStream delegate;
    private boolean closed;
    // Set when the client RST'd the stream before we sent headers. Subsequent writes and closes are no-ops.
    private boolean streamReset;

    LazyHeaderOutputStream(HTTPResponse response, HTTP2Stream stream, HPACKEncoder encoder) {
      this.response = response;
      this.stream = stream;
      this.encoder = encoder;
    }

    @Override
    public void write(int b) throws IOException {
      ensureHeadersSent();
      if (streamReset) return;
      delegate.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      ensureHeadersSent();
      if (streamReset) return;
      delegate.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
      ensureHeadersSent();
      if (streamReset) return;
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      ensureHeadersSent();
      if (streamReset) return;
      if (response.hasTrailers()) {
        delegate.setTrailersFollow(true);
      }
      delegate.close();
      if (response.hasTrailers()) {
        emitTrailers();
      }
    }

    private void ensureHeadersSent() throws IOException {
      if (delegate != null || streamReset) return;
      // Build response HEADERS field list from the response state at the time of first write.
      List<HPACKDynamicTable.HeaderField> respFields = new ArrayList<>();
      respFields.add(new HPACKDynamicTable.HeaderField(":status", String.valueOf(response.getStatus())));
      for (var entry : response.getHeadersMap().entrySet()) {
        String lowerKey = entry.getKey().toLowerCase(Locale.ROOT);
        if (H1_ONLY_HEADERS.contains(lowerKey)) {
          logger.debug("Stripping h1.1-only response header [{}] on h2 emission", entry.getKey());
          continue;
        }
        for (String v : entry.getValue()) {
          respFields.add(new HPACKDynamicTable.HeaderField(lowerKey, v));
        }
      }
      // Synchronize on the encoder: HPACKEncoder mutates a shared HPACKDynamicTable (ArrayDeque-backed)
      // and is not thread-safe. Multiple handler virtual-threads can call encode() concurrently on
      // the same connection-level encoder, corrupting the dynamic-table state without coordination.
      byte[] headerBlock;
      synchronized (encoder) {
        headerBlock = encoder.encode(respFields);
      }
      // If the client RST'd the stream, applyEvent(SEND_HEADERS_NO_END_STREAM) would throw because the
      // state is CLOSED. Mark the stream as reset so the handler finishes without further errors.
      // Do not enqueue the HEADERS frame — the stream was cancelled by the client.
      if (stream.state() == HTTP2Stream.State.CLOSED) {
        streamReset = true;
        return;
      }
      try {
        writerQueue.put(new HTTP2Frame.HeadersFrame(stream.streamId(), HTTP2Frame.FLAG_END_HEADERS, headerBlock));
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
      try {
        stream.applyEvent(HTTP2Stream.Event.SEND_HEADERS_NO_END_STREAM);
      } catch (IllegalStateException ignored) {
        // Race: client sent RST_STREAM between the state check above and the applyEvent call.
        // The HEADERS frame was already enqueued (writer will discard it per RFC 9113 §5.1).
        // Mark stream as reset so subsequent handler writes go to /dev/null.
        streamReset = true;
        return;
      }
      delegate = new HTTP2OutputStream(stream, writerQueue, peerSettings.maxFrameSize());
    }

    private void emitTrailers() {
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
      try {
        writerQueue.put(new HTTP2Frame.HeadersFrame(stream.streamId(),
            HTTP2Frame.FLAG_END_HEADERS | HTTP2Frame.FLAG_END_STREAM, trailerBlock));
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static byte[] encodeSettings(HTTP2Settings s) {
    var baos = new ByteArrayOutputStream();
    writeSetting(baos, HTTP2Settings.SETTINGS_HEADER_TABLE_SIZE, s.headerTableSize());
    writeSetting(baos, HTTP2Settings.SETTINGS_ENABLE_PUSH, 0); // server never pushes
    writeSetting(baos, HTTP2Settings.SETTINGS_MAX_CONCURRENT_STREAMS, s.maxConcurrentStreams());
    writeSetting(baos, HTTP2Settings.SETTINGS_INITIAL_WINDOW_SIZE, s.initialWindowSize());
    writeSetting(baos, HTTP2Settings.SETTINGS_MAX_FRAME_SIZE, s.maxFrameSize());
    writeSetting(baos, HTTP2Settings.SETTINGS_MAX_HEADER_LIST_SIZE, s.maxHeaderListSize());
    return baos.toByteArray();
  }

  private static void writeSetting(ByteArrayOutputStream out, int id, int value) {
    out.write((id >> 8) & 0xFF);
    out.write(id & 0xFF);
    out.write((value >> 24) & 0xFF);
    out.write((value >> 16) & 0xFF);
    out.write((value >> 8) & 0xFF);
    out.write(value & 0xFF);
  }
}

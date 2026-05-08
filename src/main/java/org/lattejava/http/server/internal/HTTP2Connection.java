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
  private static final Set<String> H1_ONLY_HEADERS = Set.of(
      "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade"
  );
  private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

  private final HTTPBuffers buffers;
  private final HTTPServerConfiguration configuration;
  private final HTTPContext context;
  private final Instrumenter instrumenter;
  private final HTTPListenerConfiguration listener;
  private final HTTP2Settings localSettings;
  private final Logger logger;
  private final HTTP2Settings peerSettings = HTTP2Settings.defaults();
  private final boolean prefaceAlreadyConsumed;
  private final HTTP2RateLimits rateLimits;
  // True for the h2c Upgrade/101 path: server sends its SETTINGS frame immediately after the 101, before reading the
  // client connection preface. All other paths (ALPN, prior-knowledge) leave this false.
  private final boolean serverSendsFirst;
  private final Socket socket;
  private final long startInstant;
  private final Map<Integer, HTTP2Stream> streams = new ConcurrentHashMap<>();
  private final Map<Integer, BlockingQueue<byte[]>> streamPipes = new ConcurrentHashMap<>();
  private final Throughput throughput;
  private final BlockingQueue<HTTP2Frame> writerQueue = new LinkedBlockingQueue<>(128);
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
    try {
      var in = new ThroughputInputStream(socket.getInputStream(), throughput);
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
          return;
        }
      } else {
        // ALPN (TLS) and prior-knowledge paths: read the client connection preface first (or skip if ProtocolSelector
        // already consumed it), then send our SETTINGS.
        if (!prefaceAlreadyConsumed) {
          byte[] received = in.readNBytes(PREFACE.length);
          if (!Arrays.equals(received, PREFACE)) {
            logger.debug("Invalid HTTP/2 connection preface");
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
          HTTP2Frame frame = reader.readFrame();

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
              if (f.streamId() <= highestSeenStreamId) {
                goAway(HTTP2ErrorCode.PROTOCOL_ERROR);
                return;
              }
              highestSeenStreamId = f.streamId();
              handleHeadersFrame(f, headerAccum, decoder, encoder);
              headerBlockStreamId = (f.flags() & HTTP2Frame.FLAG_END_HEADERS) == 0 ? f.streamId() : null;
            }
            case HTTP2Frame.ContinuationFrame f -> {
              handleContinuationFrame(f, headerAccum, decoder, encoder);
              if ((f.flags() & HTTP2Frame.FLAG_END_HEADERS) != 0) {
                headerBlockStreamId = null;
              }
            }
            case HTTP2Frame.DataFrame f -> handleData(f);
            case HTTP2Frame.PriorityFrame ignored -> {} // §5.3 — parse and discard
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
      try {
        socket.close();
      } catch (IOException ignore) {
      }
    }
  }

  private void finalizeHeaderBlock(int streamId, int flags, ByteArrayOutputStream headerAccum, HPACKDecoder decoder, HPACKEncoder encoder) throws IOException {
    List<HPACKDynamicTable.HeaderField> fields = decoder.decode(headerAccum.toByteArray());

    HTTPRequest request = buildRequestFromHeaders(fields, streamId);
    HTTP2Stream stream = new HTTP2Stream(streamId, localSettings.initialWindowSize(), peerSettings.initialWindowSize());
    if ((flags & HTTP2Frame.FLAG_END_STREAM) != 0) {
      stream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_END_STREAM);
    } else {
      stream.applyEvent(HTTP2Stream.Event.RECV_HEADERS_NO_END_STREAM);
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

  private void handleContinuationFrame(HTTP2Frame.ContinuationFrame f, ByteArrayOutputStream headerAccum, HPACKDecoder decoder, HPACKEncoder encoder) throws IOException {
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
      return; // Unknown stream; ignore.
    }
    if (f.payload().length > 0) {
      stream.consumeReceiveWindow(f.payload().length);
      try {
        pipe.put(f.payload());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if ((f.flags() & HTTP2Frame.FLAG_END_STREAM) != 0) {
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
    if (rateLimits.recordRstStream()) {
      goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
      return;
    }
    HTTP2Stream stream = streams.get(f.streamId());
    if (stream != null) {
      stream.applyEvent(HTTP2Stream.Event.RECV_RST_STREAM);
      streams.remove(f.streamId());
      BlockingQueue<byte[]> pipe = streamPipes.remove(f.streamId());
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
      // Connection-level window update — no per-connection window tracking yet. Plan F can refine.
      return;
    }
    HTTP2Stream stream = streams.get(f.streamId());
    if (stream != null) {
      stream.incrementSendWindow(f.windowSizeIncrement());
      synchronized (stream) {
        stream.notifyAll();
      }
    }
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
        // Stage the response body during handler execution. The handler may call res.getOutputStream().close()
        // before returning, but RFC 9113 §8.1 requires HEADERS to precede all DATA frames on a stream.
        // Buffering here ensures we can emit HEADERS first, then the staged body bytes, in the correct order.
        ByteArrayOutputStream stagingBuffer = new ByteArrayOutputStream();
        response.setRawOutputStream(stagingBuffer);

        configuration.getHandler().handle(request, response);

        // Build response HEADERS field list.
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

        // Encode and emit HEADERS frame (without END_STREAM so that the body DATA frames follow).
        // Synchronize on the encoder: HPACKEncoder mutates a shared HPACKDynamicTable (ArrayDeque-backed)
        // and is not thread-safe. Multiple handler virtual-threads can call encode() concurrently on
        // the same connection-level encoder, corrupting the dynamic-table state without coordination.
        byte[] headerBlock;
        synchronized (encoder) {
          headerBlock = encoder.encode(respFields);
        }
        try {
          writerQueue.put(new HTTP2Frame.HeadersFrame(stream.streamId(), HTTP2Frame.FLAG_END_HEADERS, headerBlock));
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
        }
        stream.applyEvent(HTTP2Stream.Event.SEND_HEADERS_NO_END_STREAM);

        // Emit the staged body bytes as DATA frames, then the trailers HEADERS frame if present.
        HTTP2OutputStream h2out = new HTTP2OutputStream(stream, writerQueue, peerSettings.maxFrameSize());
        if (response.hasTrailers()) {
          h2out.setTrailersFollow(true);
        }
        byte[] body = stagingBuffer.toByteArray();
        if (body.length > 0) {
          h2out.write(body);
        }
        h2out.close();

        if (response.hasTrailers()) {
          // Build and emit the trailers HEADERS frame with END_STREAM (RFC 9113 §8.1).
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

        stream.applyEvent(HTTP2Stream.Event.SEND_DATA_END_STREAM);

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

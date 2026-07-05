/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.server.internal.*;

/**
 * Per-connection HTTP/2 state and lifecycle. Owns the socket I/O, frame codec, HPACK context, and the frame dispatch
 * loop. After the SETTINGS exchange, constructs and wires the handler collaborators —
 * {@link HTTP2StreamRegistry}, {@link HTTP2ConnectionFrameHandler}, {@link HTTP2HeaderFrameHandler},
 * {@link HTTP2DataFrameHandler}, {@link HTTP2RSTStreamFrameHandler}, {@link HTTP2WindowUpdateFrameHandler} —
 * then enters the §4.10 dispatch loop.
 *
 * <p>Threading model:
 * <ul>
 *   <li>The calling thread (reader thread) runs {@link #run()}, reads all inbound frames via {@link HTTP2FrameReader},
 *       and dispatches to {@link HTTP2FrameHandler} implementations. HEADERS arrive pre-assembled — CONTINUATION
 *       reassembly happens inside the reader, not here.</li>
 *   <li>A single {@link HTTP2WriterThread} serializes all outbound frames to the socket. It exits when it
 *       dequeues the writer-shutdown sentinel (a GoawayFrame with lastStreamId == -1).</li>
 *   <li>Each request spawns a handler virtual-thread (via {@link HTTP2HeaderFrameHandler}) that runs the application
 *       {@link HTTPHandler}, then enqueues response HEADERS and DATA frames to the writer queue.</li>
 * </ul>
 *
 * @author Daniel DeGroff
 */
public class HTTP2Connection implements HTTPConnection, Runnable {
  private final HTTPBuffers buffers;

  private final HTTPServerConfiguration configuration;

  // Connection-level send window (RFC 9113 §6.9). Shared by every per-stream writer; replenished by the reader thread
  // on a stream-0 WINDOW_UPDATE. Starts at the HTTP/2 default of 65535 octets.
  private final HTTP2ConnectionWindow connectionSendWindow = new HTTP2ConnectionWindow(65535);

  private final HTTPContext context;

  private final AtomicLong handledRequests = new AtomicLong();

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

  private final Socket socket;

  private final long startInstant;

  private final Throughput throughput;

  private volatile boolean goawaySent;

  // Reader thread handle, captured at the top of run() so the writer thread can interrupt it when it dies.
  private volatile Thread readerThread;

  // The stream roster. Null until after the SETTINGS exchange; shutdown() and goAway() use a null-guard snapshot.
  private volatile HTTP2StreamRegistry registry;

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
    this.localSettings = HTTP2Settings.fromConfiguration(configuration.getHTTP2Configuration(), configuration.getMaxRequestHeaderSize());
    this.rateLimits = new HTTP2RateLimitsTracker(configuration.getHTTP2Configuration().getRateLimits());
    this.startInstant = System.currentTimeMillis();
  }

  @Override
  public long getHandledRequests() {
    return handledRequests.get();
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
      var reader = new HTTP2FrameReader(inputStream, buffers.frameReadBuffer(), localSettings.maxHeaderListSize());

      // Perform the SETTINGS exchange. The client connection preface was already read and validated by ProtocolSelector.
      HTTP2Result negotiation = HTTP2Tools.negotiateSettings(reader, frameWriter, out, localSettings, peerSettings, logger);
      if (negotiation instanceof HTTP2Result.ConnectionError(HTTP2ErrorCode code)) {
        // The writer thread does not exist yet, so the GOAWAY is written directly, then the output side is half-closed
        // so the kernel sends FIN — h2spec keeps writing preface bytes and a plain close would race into an OS RST.
        sendGoAwayDirect(frameWriter, out, code);
        try {
          socket.shutdownOutput();
        } catch (IOException ignore) { /* best effort */ }
        return;
      }

      // RFC 9113 §4.2: outbound DATA frames may be up to the peer's SETTINGS_MAX_FRAME_SIZE. Grow the write buffer if
      // the peer accepts larger frames than we configured locally; the frame writer holds a byte[] reference, so it is
      // rebuilt to pick up the new buffer. Safe here — the writer thread has not started yet.
      if (peerSettings.maxFrameSize() > localSettings.maxFrameSize()) {
        buffers.ensureFrameWriteCapacity(peerSettings.maxFrameSize());
        frameWriter = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
      }

      // Start the writer: it drains its private queue and serializes frames to the socket, exiting on the
      // writer-shutdown sentinel (requestStop). It is stored in a field so the reader can join it before closing
      // the socket, guaranteeing GOAWAY frames are flushed before teardown, and so shutdown() can reach it.
      writer = new HTTP2WriterThread(frameWriter, readerThread, logger);
      writer.start();

      HPACKDecoder decoder = new HPACKDecoder(new HPACKDynamicTable(localSettings.headerTableSize()));
      HPACKEncoder encoder = new HPACKEncoder(new HPACKDynamicTable(peerSettings.headerTableSize()));

      var headerHandler = new HTTP2HeaderFrameHandler(configuration, connectionSendWindow, context, decoder, encoder,
          handledRequests, handlerThreads, instrumenter, listener, logger, peerSettings, socket, writer);
      var handlers = new HTTP2StreamFrameHandlers(
          new HTTP2DataFrameHandler(configuration.getHTTP2Configuration(), localSettings, logger, writer),
          headerHandler, rateLimits, new HTTP2RSTStreamFrameHandler(logger), new HTTP2WindowUpdateFrameHandler());
      registry = new HTTP2StreamRegistry(localSettings, peerSettings, handlers);
      var connectionFrameHandler = new HTTP2ConnectionFrameHandler(connectionSendWindow, logger, peerSettings, rateLimits,
          registry, writer);

      try {
        frames:
        while (true) {
          state = HTTPConnection.State.Read;
          if (writer.isClosed()) {
            logger.debug("Writer thread closed; reader exiting");
            break;
          }

          HTTP2Frame frame;
          try {
            frame = reader.readFrame(); // HEADERS arrives as one complete, assembled header block
          } catch (HTTP2FrameReader.FrameSizeException e) {
            goAway(HTTP2ErrorCode.FRAME_SIZE_ERROR); // §4.2
            break;
          } catch (HTTP2FrameReader.HeaderListSizeException e) {
            goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM); // CVE-2024-27316
            break;
          } catch (HTTP2FrameReader.ProtocolException e) {
            goAway(HTTP2ErrorCode.PROTOCOL_ERROR); // §5.4.1
            break;
          }

          HTTP2FrameHandler handler = frame.streamId() == 0 ? connectionFrameHandler : registry.lookup(frame.streamId());
          HTTP2Result result = handler.handleFrame(frame);

          switch (result) {
            case HTTP2Result.Ok ignored -> {
            }
            case HTTP2Result.StreamError(int id, HTTP2ErrorCode code) -> rstStream(id, code);
            case HTTP2Result.ConnectionError(HTTP2ErrorCode code) -> {
              goAway(code);
              break frames;
            }
            case HTTP2Result.Shutdown ignored -> {
              return; // Peer GOAWAY — drain and exit.
            }
          }
        }
      } catch (Throwable t) {
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
      HTTP2StreamRegistry r = registry;
      int lastStreamId = r != null ? r.highestSeenStreamId() : 0;
      w.enqueueOrCloseWriter(new HTTP2Frame.GoawayFrame(lastStreamId, HTTP2ErrorCode.NO_ERROR.value, new byte[0]));
      // Writer-shutdown sentinel so the writer flushes the GOAWAY above and then exits.
      w.requestStop();
      w.join(Duration.ofSeconds(1));
    }
  }

  @Override
  public HTTPConnection.State state() {
    return state;
  }

  private void goAway(HTTP2ErrorCode code) {
    if (goawaySent) {
      return; // Idempotent — only one GOAWAY per connection.
    }
    goawaySent = true;
    // Use the highest seen client stream-id.
    // lastStreamId == -1 is reserved as the writer-shutdown sentinel and must never be used for a real GOAWAY.
    HTTP2StreamRegistry r = registry;
    int lastStreamId = r != null ? r.highestSeenStreamId() : 0;
    writer.enqueueOrCloseWriter(new HTTP2Frame.GoawayFrame(lastStreamId, code.value, new byte[0]));
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
  private void sendGoAwayDirect(HTTP2FrameWriter frameWriter, OutputStream out, HTTP2ErrorCode code) {
    try {
      // Preamble-phase only: the registry does not exist yet, so no stream was ever opened — lastStreamId is 0.
      HTTP2StreamRegistry r = registry;
      int lastStreamId = r != null ? r.highestSeenStreamId() : 0;
      frameWriter.writeFrame(new HTTP2Frame.GoawayFrame(lastStreamId, code.value, new byte[0]));
      out.flush();
    } catch (IOException ignore) {
      // Best-effort: if the peer already closed, suppress the write error.
    }
  }

}

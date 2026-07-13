/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

import java.lang.System.Logger.Level;

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
public class HTTP2Connection extends BaseHTTPConnection implements Runnable {
  private static final System.Logger logger = System.getLogger(HTTP2Connection.class.getName());

  private final HTTPBuffers buffers;

  // Connection-level send window (RFC 9113 §6.9). Shared by every per-stream writer; replenished by the reader thread
  // on a stream-0 WINDOW_UPDATE. Starts at the HTTP/2 default of 65535 octets.
  private final HTTP2Window connectionSendWindow = new HTTP2Window(65535);

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

  private final HTTP2Settings peerSettings = HTTP2Settings.defaults();

  private final HTTP2RateLimitsTracker rateLimits;

  // Set once the connection enters graceful drain: either a client GOAWAY arrived with streams still in flight, or the
  // maxRequestsPerConnection bound was reached. While true, HTTP2HeaderFrameHandler refuses new streams with
  // REFUSED_STREAM; the dispatch loop exits when the roster empties.
  private volatile boolean draining;

  private volatile boolean evicting;

  private volatile boolean goawaySent;

  // Instant of the last stream-addressed inbound frame (streamId != 0). Stream-0 traffic (PING, SETTINGS,
  // WINDOW_UPDATE) intentionally does NOT advance this: it proves the transport works but never extends a lifetime
  // clock, so drip-fed pings cannot hold a wedged connection as a zombie.
  private volatile long lastStreamFrameInstant;

  // The frame reader, promoted from a run()-local to a field so check() can observe the mid-frame marker.
  private volatile HTTP2FrameReader reader;

  // Reader thread handle, captured at the top of run() so the writer thread can interrupt it when it dies.
  private volatile Thread readerThread;

  // The stream roster. Null until after the SETTINGS exchange; shutdown() and goAway() use a null-guard snapshot.
  private volatile HTTP2StreamRegistry registry;

  // The outbound writer: owns the frame queue and the virtual thread that serializes frames to the socket. Built after
  // the SETTINGS exchange in run(); volatile because shutdown() reads it from the acceptor thread.
  private volatile HTTP2WriterThread writer;

  public HTTP2Connection(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter,
                         HTTPListenerConfiguration listener, Throughput throughput, InputStream inputStream) throws IOException {
    super(configuration, socket, throughput);
    this.context = context;
    this.instrumenter = instrumenter;
    this.listener = listener;
    this.inputStream = inputStream;
    this.buffers = new HTTPBuffers(configuration);
    this.localSettings = HTTP2Settings.fromConfiguration(configuration.getHTTP2Configuration(), configuration.getMaxRequestHeaderSize());
    this.rateLimits = new HTTP2RateLimitsTracker(configuration.getHTTP2Configuration().getRateLimits());
  }

  @Override
  public void evict(EvictionReason reason) {
    if (evicting) {
      return;
    }
    evicting = true;

    // Never block the reaper: a 0ms enqueue attempt, then a short-lived virtual thread flushes the GOAWAY and closes.
    // The parked reader wakes with a SocketException, which run() classifies as a DEBUG lifecycle event. MaxAge is a
    // graceful GOAWAY(NO_ERROR); every slow/timeout reason is GOAWAY(ENHANCE_YOUR_CALM).
    HTTP2WriterThread w = writer;
    HTTP2ErrorCode code = reason == EvictionReason.MaxAge ? HTTP2ErrorCode.NO_ERROR : HTTP2ErrorCode.ENHANCE_YOUR_CALM;
    Thread.ofVirtual().name("h2-evict").start(() -> {
      if (w != null) {
        HTTP2StreamRegistry r = registry;
        int lastStreamId = r != null ? r.highestSeenStreamId() : 0;
        try {
          w.tryEnqueue(new HTTP2Frame.GoawayFrame(lastStreamId, code.value, new byte[0]), 0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignore) {
          Thread.currentThread().interrupt();
        }
        w.requestStop();
        w.join(Duration.ofSeconds(1));
      }
      try {
        socket.close();
      } catch (IOException ignore) {
      }
    });
  }

  @Override
  public long getHandledRequests() {
    return handledRequests.get();
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
      reader = new HTTP2FrameReader(inputStream, buffers.frameReadBuffer(), localSettings.maxHeaderListSize());

      // Perform the SETTINGS exchange. The client connection preface was already read and validated by ProtocolSelector.
      // The connection-window advertisement rides the same first flight as our SETTINGS (RFC 9113 §6.9.2).
      int connectionWindowSize = configuration.getHTTP2Configuration().getConnectionWindowSize();
      HTTP2Result negotiation = HTTP2Tools.negotiateSettings(reader, frameWriter, out, localSettings, peerSettings,
          connectionWindowSize);
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
      writer = new HTTP2WriterThread(frameWriter, readerThread);
      writer.start();

      var connectionFlowControl = new HTTP2ConnectionFlowControl(connectionWindowSize, writer);

      HPACKDecoder decoder = new HPACKDecoder(new HPACKDynamicTable(localSettings.headerTableSize()));
      HPACKEncoder encoder = new HPACKEncoder(new HPACKDynamicTable(peerSettings.headerTableSize()));

      var headerHandler = new HTTP2HeaderFrameHandler(configuration, connectionSendWindow, context, decoder, encoder,
          handledRequests, handlerThreads, instrumenter, listener, peerSettings, socket, writer, () -> draining);
      var handlers = new HTTP2StreamFrameHandlers(
          connectionFlowControl,
          new HTTP2DataFrameHandler(configuration.getHTTP2Configuration(), localSettings, writer),
          headerHandler, rateLimits, new HTTP2RSTStreamFrameHandler(), new HTTP2WindowUpdateFrameHandler());
      registry = new HTTP2StreamRegistry(localSettings, peerSettings, handlers);
      var connectionFrameHandler = new HTTP2ConnectionFrameHandler(connectionSendWindow, peerSettings, rateLimits,
          registry, writer);

      try {
        frames:
        while (true) {
          if (writer.isClosed()) {
            logger.log(Level.DEBUG, "Writer thread closed; reader exiting");
            break;
          }

          // Keep-alive management (design section 4.2). With live streams the timeout is flat; with zero streams it
          // is the REMAINING idle budget, recomputed every iteration, so no cadence of stream-0 frames (which reset
          // the kernel timer byte-by-byte) can postpone the deadline.
          long keepAliveMillis = configuration.getKeepAliveTimeoutDuration().toMillis();
          long emptySince = registry.emptySince();
          if (emptySince == 0) {
            socket.setSoTimeout((int) Math.min(keepAliveMillis, Integer.MAX_VALUE));
          } else {
            long remaining = keepAliveMillis - (System.currentTimeMillis() - emptySince);
            if (remaining <= 0) {
              idleExpire();
              return;
            }
            socket.setSoTimeout((int) Math.min(remaining, Integer.MAX_VALUE));
          }

          // Read-epoch reset (design section 6, rising-edge rule): because check() is gate-only, the reader resets the
          // read epoch in real time. When the read gate is closed (no request side open — between frames the mid-frame
          // marker is necessarily clear) the next blocking read begins a fresh read phase, so its first byte lands in a
          // fresh epoch. Repeated resets across idle iterations are harmless (the counters are already empty); resetting
          // while the gate is OPEN would wipe an in-flight phase, so we do not.
          if (!registry.anyRequestSideOpen()) {
            throughput.resetRead();
          }

          HTTP2Frame frame;
          try {
            frame = reader.readFrame(); // HEADERS arrives as one complete, assembled header block
          } catch (SocketTimeoutException e) {
            if (reader.frameStartInstant() != 0) {
              // Mid-frame silence for the whole budget: partial bytes are consumed, the stream cannot be resumed.
              logger.log(Level.DEBUG, "Closing HTTP/2 connection: mid-frame stall");
              goAway(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
              return;
            }
            if (registry.emptySince() != 0) {
              idleExpire();
              return;
            }
            continue; // Live streams, clean frame boundary: a quiet client awaiting responses (long-poll). Keep reading.
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

          if (frame.streamId() != 0) {
            lastStreamFrameInstant = System.currentTimeMillis();
          }

          // Write-epoch reset (design section 6, rising-edge rule): snapshot the write gate before dispatch; if a
          // response phase just began (the gate went from closed to open — a completed request just entered
          // HALF_CLOSED_REMOTE) start a fresh write epoch. Overlapping responses share the epoch (leniency is
          // acceptable); resetting while the gate was already open would wipe an in-flight phase, so we only reset on
          // the rising edge.
          boolean writeGateWasOpen = registry.anyResponsePending();

          HTTP2FrameHandler handler = frame.streamId() == 0 ? connectionFrameHandler : registry.lookup(frame.streamId());
          HTTP2Result result = handler.handleFrame(frame);

          if (!writeGateWasOpen && registry.anyResponsePending()) {
            throughput.resetWrite();
          }

          switch (result) {
            case HTTP2Result.Ok ignored -> {
            }
            case HTTP2Result.StreamError(int id, HTTP2ErrorCode code) -> rstStream(id, code);
            case HTTP2Result.ConnectionError(HTTP2ErrorCode code) -> {
              goAway(code);
              break frames;
            }
            case HTTP2Result.Shutdown ignored -> {
              if (registry.liveStreams().isEmpty()) {
                return; // Peer GOAWAY with nothing in flight — exit now.
              }
              draining = true; // Serve what is open; refuse new streams; exit when the roster empties.
            }
          }

          // Drain completion (client GOAWAY) and the request-count bound (design section 4.8). The Nth request is
          // served; the GOAWAY announces no more will be accepted; draining mode refuses stragglers.
          if (draining && registry.liveStreams().isEmpty()) {
            return;
          }
          if (!draining && handledRequests.get() >= configuration.getMaxRequestsPerConnection()) {
            logger.log(Level.DEBUG, "Reached maxRequestsPerConnection [{0}]; draining with GOAWAY(NO_ERROR)",
                configuration.getMaxRequestsPerConnection());
            goAway(HTTP2ErrorCode.NO_ERROR);
            draining = true;
          }
        }
      } catch (EOFException e) {
        // The client closed (or half-closed) without a GOAWAY. Common and clean — no GOAWAY back; the peer is gone.
        logger.log(Level.DEBUG, "HTTP/2 client closed the connection without GOAWAY: [{0}]", e.getMessage());
      } catch (SocketTimeoutException e) {
        // Safety net only: the reader loop consumes SO_TIMEOUT itself (idle expiry, mid-frame stall). Reaching here
        // means a timeout escaped the loop's own handling — treat it as a quiet teardown.
        logger.log(Level.DEBUG, "HTTP/2 connection timed out waiting for frames");
      } catch (SocketException | SSLException e) {
        // Local close (eviction, server shutdown) or TLS teardown — a lifecycle event, not a server bug.
        logger.log(Level.DEBUG, "HTTP/2 connection closed: [{0}]", e.getMessage());
      } catch (IOException e) {
        logger.log(Level.DEBUG, "HTTP/2 connection ended", e);
      } catch (Throwable t) {
        logger.log(Level.ERROR, "Unhandled exception in HTTP/2 reader; emitting GOAWAY(INTERNAL_ERROR)", t);
        goAway(HTTP2ErrorCode.INTERNAL_ERROR);
      } finally {
        // Signal the writer thread to exit cleanly. If the writer has already closed, this is a no-op.
        writer.requestStop();
      }
    } catch (Exception e) {
      logger.log(Level.DEBUG, "HTTP/2 connection ended", e);
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
  protected long lastProgressInstant() {
    // Both scalars are 0 only before any stream exists; processing() is false then, so the guard never divides by an
    // empty history. Once a stream opens, its HEADERS set lastStreamFrameInstant.
    return Math.max(lastStreamFrameInstant, throughput.lastWroteInstant());
  }

  @Override
  protected boolean processing() {
    HTTP2StreamRegistry r = registry;
    return r != null && r.emptySince() == 0;
  }

  @Override
  protected boolean readingRequest() {
    HTTP2FrameReader fr = reader;
    HTTP2StreamRegistry r = registry;
    return (fr != null && fr.frameStartInstant() != 0) || (r != null && r.anyRequestSideOpen());
  }

  @Override
  protected boolean writingResponse() {
    HTTP2StreamRegistry r = registry;
    return r != null && r.anyResponsePending();
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

  private void idleExpire() {
    logger.log(Level.DEBUG, "Idle keep-alive expired; closing with GOAWAY(NO_ERROR)");
    goAway(HTTP2ErrorCode.NO_ERROR);
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

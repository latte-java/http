/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

/**
 * Per-connection HTTP/2 state and lifecycle. Owns the socket I/O, frame codec, HPACK state, and stream registry. Plan D
 * Task 7 implements the preface + initial SETTINGS exchange. The frame loop and stream handling land in Task 9.
 *
 * @author Daniel DeGroff
 */
public class HTTP2Connection implements ClientConnection, Runnable {
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
  private final Socket socket;
  private final long startInstant;
  private final Throughput throughput;
  private long handledRequests;
  private volatile ClientConnection.State state = ClientConnection.State.Read;

  public HTTP2Connection(Socket socket, HTTPServerConfiguration configuration, HTTPContext context, Instrumenter instrumenter,
                         HTTPListenerConfiguration listener, Throughput throughput, Boolean prefaceAlreadyConsumed) throws IOException {
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

  @Override
  public void run() {
    try {
      var in = new ThroughputInputStream(socket.getInputStream(), throughput);
      var out = new ThroughputOutputStream(socket.getOutputStream(), throughput);

      // Read and validate the connection preface unless already consumed by ProtocolSelector.
      if (!prefaceAlreadyConsumed) {
        byte[] received = in.readNBytes(PREFACE.length);
        if (!Arrays.equals(received, PREFACE)) {
          logger.debug("Invalid HTTP/2 connection preface");
          return;
        }
      }

      // Send our initial SETTINGS frame.
      var writer = new HTTP2FrameWriter(out, buffers.frameWriteBuffer());
      writer.writeFrame(new HTTP2Frame.SettingsFrame(0, encodeSettings(localSettings)));
      out.flush();

      // Read the peer's first SETTINGS frame.
      var reader = new HTTP2FrameReader(in, buffers.frameReadBuffer());
      var firstFrame = reader.readFrame();
      if (!(firstFrame instanceof HTTP2Frame.SettingsFrame settings) || (settings.flags() & HTTP2Frame.FLAG_ACK) != 0) {
        logger.debug("Expected client SETTINGS frame after preface");
        return;
      }
      peerSettings.applyPayload(settings.payload());

      // Send SETTINGS ACK.
      writer.writeFrame(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
      out.flush();

      // Frame-handling loop implemented in Task 9. For now, return here.
    } catch (Exception e) {
      logger.debug("HTTP/2 connection ended", e);
    } finally {
      try {
        socket.close();
      } catch (IOException ignore) {
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

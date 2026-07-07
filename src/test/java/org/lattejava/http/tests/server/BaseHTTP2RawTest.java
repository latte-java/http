/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;

import static org.testng.Assert.*;

/**
 * Base class for raw-frame HTTP/2 tests: opens h2c prior-knowledge connections and reads/writes frames at the byte
 * level. Hoisted from per-class copies so protocol changes (e.g. the handshake-flight composition) are edited once.
 */
public abstract class BaseHTTP2RawTest extends BaseTest {
  /**
   * Open an h2c prior-knowledge connection and return the socket after the handshake is complete (server SETTINGS and
   * SETTINGS ACK have been drained).
   */
  protected Socket openH2CConnection(int port) throws Exception {
    var sock = new Socket("127.0.0.1", port);
    var out = sock.getOutputStream();
    out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
    out.write(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0});  // empty SETTINGS
    out.flush();

    var in = sock.getInputStream();
    // Drain the server's handshake flight by frame type until the SETTINGS ACK: server SETTINGS, an optional
    // stream-0 WINDOW_UPDATE (the connection-window advertisement rides the first flight), then the ACK of our
    // SETTINGS. Type-driven rather than positional so the drain is independent of the advertisement's presence.
    while (true) {
      byte[] header = in.readNBytes(9);
      if (header.length < 9) {
        throw new EOFException("Connection closed during the h2c handshake");
      }
      int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
      in.readNBytes(length);
      if ((header[3] & 0xFF) == 0x4 && (header[4] & 0x1) != 0) {
        return sock; // SETTINGS ACK — handshake complete.
      }
    }
  }

  /** Reads one 9-byte frame header; returns {type, flags, streamId, length}. */
  protected int[] readFrameHeader(InputStream in) throws Exception {
    byte[] h = in.readNBytes(9);
    assertEquals(h.length, 9, "EOF while reading a frame header");
    int length = ((h[0] & 0xFF) << 16) | ((h[1] & 0xFF) << 8) | (h[2] & 0xFF);
    return new int[]{h[3] & 0xFF, h[4] & 0xFF,
        ((h[5] & 0x7F) << 24) | ((h[6] & 0xFF) << 16) | ((h[7] & 0xFF) << 8) | (h[8] & 0xFF), length};
  }

  /**
   * Drain inbound frames until GOAWAY (type {@code 0x7}) arrives or the connection closes. Returns the GOAWAY error
   * code, or {@code -1} if EOF arrived first.
   */
  protected int readUntilGoaway(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      int read = in.readNBytes(rest, 0, 8);
      if (read != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      byte[] payload = in.readNBytes(length);
      if (type == 0x7) {  // GOAWAY
        if (payload.length < 8) {
          return -1;
        }
        return ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16) | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
      }
    }
  }

  /**
   * Reads and discards frames until a response HEADERS frame (type {@code 0x1}) arrives. Returns the <b>stream ID</b>
   * of that frame — NOT an HTTP status; decoding the status would require HPACK. Returns {@code -1} on EOF.
   */
  protected int readUntilResponseHeaders(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      int read = in.readNBytes(rest, 0, 8);
      if (read != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      int streamId = ((rest[4] & 0x7F) << 24) | ((rest[5] & 0xFF) << 16) | ((rest[6] & 0xFF) << 8) | (rest[7] & 0xFF);
      in.readNBytes(length);
      if (type == 0x1) {  // HEADERS
        return streamId;
      }
    }
  }

  /**
   * Drain inbound frames until RST_STREAM (type {@code 0x3}) arrives or the connection closes. Returns the RST_STREAM
   * error code, or {@code -1} if EOF or GOAWAY arrived first.
   */
  protected int readUntilRstStream(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      int read = in.readNBytes(rest, 0, 8);
      if (read != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      byte[] payload = in.readNBytes(length);
      if (type == 0x3) { // RST_STREAM
        if (payload.length < 4) {
          return -1;
        }
        return ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
      }
      if (type == 0x7) { // GOAWAY — connection error instead
        return -1;
      }
    }
  }

  /**
   * Write a 9-byte frame header.
   */
  protected void writeFrameHeader(OutputStream out, int length, int type, int flags, int streamId) throws Exception {
    out.write(new byte[]{
        (byte) ((length >> 16) & 0xFF), (byte) ((length >> 8) & 0xFF), (byte) (length & 0xFF),
        (byte) type, (byte) flags,
        (byte) ((streamId >> 24) & 0x7F), (byte) ((streamId >> 16) & 0xFF),
        (byte) ((streamId >> 8) & 0xFF), (byte) (streamId & 0xFF)
    });
  }
}

/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;

import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * RFC 9113 §5.1.2 — a stream stops counting toward MAX_CONCURRENT_STREAMS the moment it transitions to CLOSED, which
 * for a request the client half-closed happens when the server <em>sends</em> the frame bearing END_STREAM. The
 * client may legitimately open a replacement stream as soon as that frame arrives; the server must not refuse it just
 * because the handler thread has not finished its post-response bookkeeping.
 */
public class HTTP2ConcurrencyCapReleaseTest extends BaseHTTP2RawTest {
  private static final byte[] MINIMAL_HPACK_GET = {
      (byte) 0x82,                          // :method: GET
      (byte) 0x84,                          // :path: /
      (byte) 0x86,                          // :scheme: http
      // :authority: localhost (literal with indexing, name from static table index 1)
      (byte) 0x41, 0x09,
      'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
  };

  /**
   * The handler completes its response (END_STREAM on the wire) and then stays busy. A new stream opened by the
   * client after receiving the full response must be accepted, not RST_STREAM(REFUSED_STREAM) — the completed
   * stream's slot is free even though its handler thread is still running.
   */
  @Test(timeOut = 15_000)
  public void completed_response_releases_concurrency_slot_before_handler_returns() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    CountDownLatch release = new CountDownLatch(1);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      res.getOutputStream().close(); // END_STREAM is enqueued for the wire here
      try {
        release.await(10, TimeUnit.SECONDS); // hold the handler thread so its cleanup cannot run
      } catch (InterruptedException ignore) {
      }
    };
    try (var server = makeServer("http", handler, listener).withHTTP2(h2 -> h2.withMaxConcurrentStreams(1)).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        var in = sock.getInputStream();
        sock.setSoTimeout(5000);

        // Stream 1: complete request. The response finishes on the wire while the handler thread stays parked.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS|END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();
        readUntilEndStream(in, 1);

        // Stream 3: the client has the complete response for stream 1, so by its (correct) accounting the cap of 1
        // has a free slot. The server must accept this stream.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 3);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        while (true) {
          int[] frame = readFrameHeader(in);
          int type = frame[0];
          int flags = frame[1];
          int streamId = frame[2];
          byte[] payload = in.readNBytes(frame[3]);
          if (type == 0x3 && streamId == 3) {
            int code = ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
            fail("Stream 3 was reset (error code [" + code + "]) but the cap slot from the completed stream 1 should have been free");
          }
          if (type == 0x7) {
            fail("Connection error (GOAWAY) while waiting for the stream 3 response");
          }
          if (type == 0x1 && streamId == 3) {
            break; // Response HEADERS for stream 3 — the slot was released correctly.
          }
          assertTrue((flags & 0x1) == 0 || streamId == 1, "Unexpected END_STREAM on stream [" + streamId + "]");
        }
      } finally {
        release.countDown();
      }
    }
  }

  /**
   * Drains frames until one bearing END_STREAM arrives on {@code streamId} — the point at which the response is
   * complete from the client's perspective.
   */
  private void readUntilEndStream(InputStream in, int streamId) throws Exception {
    while (true) {
      int[] frame = readFrameHeader(in);
      in.readNBytes(frame[3]);
      boolean endStream = (frame[0] == 0x0 || frame[0] == 0x1) && (frame[1] & 0x1) != 0;
      if (endStream && frame[2] == streamId) {
        return;
      }
    }
  }
}

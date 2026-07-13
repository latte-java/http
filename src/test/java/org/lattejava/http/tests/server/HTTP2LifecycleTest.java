/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import java.time.Duration;

import static org.testng.Assert.*;

/**
 * Connection-lifecycle tests for HTTP/2: verifies that ordinary client disconnects (EOF, half-close, socket teardown)
 * are treated as quiet lifecycle events rather than server-side {@code INTERNAL_ERROR} conditions that emit a GOAWAY,
 * and that an idle keep-alive connection survives its initial-read timeout, expires gracefully at the keep-alive
 * deadline, and cannot be held open indefinitely by stream-0 traffic (PING) or a mid-frame trickle.
 */
public class HTTP2LifecycleTest extends BaseHTTP2RawTest {
  /**
   * Minimal HPACK block representing a valid GET / over HTTP/2: {@code :method GET}, {@code :path /},
   * {@code :scheme http}, {@code :authority localhost}. Copied from {@code HTTP2RawFrameTest} so post-HEADERS logic
   * (stream open, response) can be exercised without an HPACK encoder.
   */
  private static final byte[] MINIMAL_HPACK_GET = {
      (byte) 0x82,                          // :method: GET
      (byte) 0x84,                          // :path: /
      (byte) 0x86,                          // :scheme: http
      // :authority: localhost (literal with indexing, name from static table index 1)
      (byte) 0x41, 0x09,
      'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
  };

  /**
   * A client that half-closes (sends FIN but keeps reading) makes the server reader see EOF without a GOAWAY. The old
   * code answered with SEVERE + GOAWAY(INTERNAL_ERROR); the fix must close quietly, so {@code readUntilGoaway} returns
   * {@code -1} on the plain EOF rather than a GOAWAY error code.
   */
  @Test
  public void abruptClientCloseDoesNotEmitInternalError() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (_, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2CConnection(server.getActualPort())) {
        sock.setSoTimeout(5000);

        // Half-close: send FIN but keep reading. The server reader sees EOF without a GOAWAY. The old code answered
        // with SEVERE + GOAWAY(INTERNAL_ERROR); the fix must close quietly — readUntilGoaway returns -1 on plain EOF.
        sock.shutdownOutput();
        int goawayCode = readUntilGoaway(sock.getInputStream());
        assertEquals(goawayCode, -1, "Expected a quiet close on client EOF, but got GOAWAY code [" + goawayCode + "]");
      }
    }
  }

  @Test(groups = "timeouts")
  public void clientGoawayDrainsInFlightStreams() throws Exception {
    try (var server = h2cServer(cfg -> cfg.withHandler((_, res) -> {
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
      res.setStatus(200);
    }))) {
      Socket socket = openH2CConnection(server.getActualPort());
      writeSimpleGet(socket);
      // Immediately GOAWAY (lastStreamId=1, NO_ERROR). Old behavior: teardown interrupts the handler and the response
      // never arrives. New behavior: stream 1 completes, then the connection closes.
      var out = socket.getOutputStream();
      writeFrameHeader(out, 8, 0x7, 0, 0);
      out.write(new byte[]{0, 0, 0, 1, 0, 0, 0, 0});
      out.flush();
      assertEquals(readUntilResponseHeaders(socket.getInputStream()), 1, "In-flight stream must complete after client GOAWAY");
      socket.close();
    }
  }

  /**
   * Slow-reader variant (b): the client opens a request but never sends END_STREAM, holding the
   * {@code readingRequest()} gate open, then drips PINGs. Stream-0 traffic proves the transport works but never counts
   * toward the read floor, so the read rate stays far below {@code minimumReadThroughput} and the reaper must evict
   * after the grace. A GOAWAY (ENHANCE_YOUR_CALM) or a plain close is acceptable; either way the connection must be
   * gone within the bound.
   */
  @Test(groups = "timeouts")
  public void dripPingWithOpenRequestIsEvicted() throws Exception {
    try (var server = h2cServer(cfg -> cfg.withKeepAliveTimeoutDuration(Duration.ofSeconds(30)))) {
      Socket socket = openH2CConnection(server.getActualPort());
      var out = socket.getOutputStream();
      var in = socket.getInputStream();

      // Open stream 1 with END_HEADERS but NOT END_STREAM: the request side stays incomplete, holding the read gate
      // open so the slow-reader floor applies. Then starve the stream of DATA and only drip PINGs.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4, 1);
      out.write(MINIMAL_HPACK_GET);
      out.flush();

      long start = System.currentTimeMillis();
      long lastPing = 0;
      boolean closed = false;
      while (System.currentTimeMillis() - start < 8_000) {
        long now = System.currentTimeMillis();
        if (now - lastPing >= 300) {
          try {
            writeFrameHeader(out, 8, 0x6, 0, 0);
            out.write(new byte[8]);
            out.flush();
          } catch (IOException e) {
            closed = true; // The server closed under us while we were writing a PING.
            break;
          }
          lastPing = now;
        }
        socket.setSoTimeout(300);
        try {
          int[] header = readFrameHeader(in);
          in.readNBytes(header[3]);
          if (header[0] == 0x7) {
            closed = true; // GOAWAY
            break;
          }
        } catch (SocketTimeoutException ignore) {
        } catch (EOFException | AssertionError e) {
          closed = true; // Plain close.
          break;
        }
      }
      assertTrue(closed, "Drip-PING with an open request must be evicted within the bound");
      socket.close();
    }
  }

  @Test(groups = "timeouts")
  public void idleConnectionSurvivesInitialReadTimeoutAndReaper() throws Exception {
    // initialReadTimeout=2s, keepAlive=30s: the connection must idle far past 2s and still serve a request.
    // Old behavior: SO_TIMEOUT stayed at 2s and the connection died with GOAWAY(INTERNAL_ERROR).
    try (var server = h2cServer(cfg -> cfg.withInitialReadTimeout(Duration.ofSeconds(2))
                                          .withKeepAliveTimeoutDuration(Duration.ofSeconds(30)))) {
      Socket socket = openH2CConnection(server.getActualPort());
      Thread.sleep(6_000);
      writeSimpleGet(socket); // HPACK-encoded GET on stream 1
      assertEquals(readUntilResponseHeaders(socket.getInputStream()), 1, "Idle connection was killed");
      socket.close();
    }
  }

  @Test(groups = "timeouts")
  public void idleExpiryIsGracefulGoawayNoError() throws Exception {
    try (var server = h2cServer(cfg -> cfg.withKeepAliveTimeoutDuration(Duration.ofSeconds(1)))) {
      Socket socket = openH2CConnection(server.getActualPort());
      long start = System.currentTimeMillis();
      int code = readUntilGoaway(socket.getInputStream());
      long elapsed = System.currentTimeMillis() - start;
      assertEquals(code, 0, "Idle expiry must be GOAWAY(NO_ERROR)");
      assertTrue(elapsed >= 800 && elapsed < 5_000, "Idle expiry landed at [" + elapsed + "] ms");
      socket.close();
    }
  }

  /**
   * Idle-gap regression, the H2 twin of {@link HTTP1LifecycleTest}: a request completes, the connection idles past a
   * couple of reaper passes, then a second request is dribbled on the SAME raw connection at a rate above the floor.
   * The un-reset lifetime read average (tiny bytes over a span that includes the idle gap) would evict mid-read; the
   * reader-thread {@code resetRead} on the new request's rising edge scopes the sample to the fresh phase and it
   * survives.
   */
  @Test(groups = "timeouts")
  public void idleGapDoesNotPoisonTheNextRequest() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> {
      req.getInputStream().readAllBytes();
      res.setStatus(200);
    };
    try (var server = new HTTPServer().withHandler(handler)
                                      .withKeepAliveTimeoutDuration(Duration.ofSeconds(30))
                                      .withMinimumReadThroughput(1024)
                                      .withReadThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                                      .withListener(listener)
                                      .start()) {
      Socket socket = openH2CConnection(server.getActualPort());
      var out = socket.getOutputStream();
      var in = socket.getInputStream();

      // Request 1 completes normally.
      writeSimpleGet(socket);
      assertEquals(readUntilResponseHeaders(in), 1, "First request failed");

      // Idle keep-alive gap. Under the un-reset lifetime average this poisons the read denominator for request 2.
      Thread.sleep(3_000);

      // Request 2: HEADERS without END_STREAM, then DATA dribbled at ~2 KB/s (above the 1 KB/s floor) across reaper
      // cycles. The 6 KB total sits under the 65,535-byte default window, so no WINDOW_UPDATE is needed.
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4, 3);
      out.write(MINIMAL_HPACK_GET);
      out.flush();
      byte[] chunk = new byte[512];
      for (int i = 0; i < 12; i++) {
        writeFrameHeader(out, chunk.length, 0x0, 0, 3);
        out.write(chunk);
        out.flush();
        Thread.sleep(250);
      }
      writeFrameHeader(out, 0, 0x0, 0x1, 3); // empty DATA, END_STREAM

      out.flush();
      assertEquals(readUntilResponseHeaders(in), 3, "Second request after the idle gap was evicted");
      socket.close();
    }
  }

  /**
   * {@code maxConnectionAgeDuration} backstop: a connection kept active with PINGs past its configured age is rotated
   * with a graceful {@code GOAWAY(NO_ERROR)}. The age check lives in the reaper's {@code check()} rather than a read
   * loop precisely so a PINGing client — which never surfaces from a socket read timeout — is still bounded.
   */
  @Test(groups = "timeouts")
  public void maxConnectionAgeClosesGracefully() throws Exception {
    try (var server = h2cServer(cfg -> cfg.withKeepAliveTimeoutDuration(Duration.ofSeconds(30))
                                          .withMaxConnectionAgeDuration(Duration.ofSeconds(1)))) {
      Socket socket = openH2CConnection(server.getActualPort());
      var out = socket.getOutputStream();
      var in = socket.getInputStream();

      long start = System.currentTimeMillis();
      long lastPing = 0;
      int code = -2;
      while (System.currentTimeMillis() - start < 6_000) {
        long now = System.currentTimeMillis();
        if (now - lastPing >= 300) {
          try {
            writeFrameHeader(out, 8, 0x6, 0, 0);
            out.write(new byte[8]);
            out.flush();
          } catch (IOException e) {
            break; // Socket closed after the GOAWAY; the read below has already captured the code.
          }
          lastPing = now;
        }
        socket.setSoTimeout(300);
        try {
          int[] header = readFrameHeader(in);
          byte[] payload = in.readNBytes(header[3]);
          if (header[0] == 0x7) { // GOAWAY: last 4 bytes of the 8-byte prefix are the error code.
            code = ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16) | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
            break;
          }
        } catch (SocketTimeoutException ignore) {
        } catch (EOFException | AssertionError e) {
          break;
        }
      }
      long elapsed = System.currentTimeMillis() - start;
      assertEquals(code, 0, "Max-age eviction must be GOAWAY(NO_ERROR)");
      assertTrue(elapsed >= 800 && elapsed < 5_000, "Max-age eviction landed at [" + elapsed + "] ms");
      socket.close();
    }
  }

  @Test
  public void maxRequestsPerConnectionClosesGracefully() throws Exception {
    var instrumenter = new CountingInstrumenter();
    var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());
    HTTPHandler handler = (req, res) -> {
      req.getInputStream().readAllBytes();
      res.setStatus(200);
    };
    try (var server = new HTTPServer().withHandler(handler)
                                 .withInstrumenter(instrumenter)
                                 .withMaxRequestsPerConnection(10)
                                 .withMinimumReadThroughput(200 * 1024)
                                 .withMinimumWriteThroughput(200 * 1024)
                                 .withProcessingTimeoutDuration(Duration.ofSeconds(10))
                                 .withReadThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                                 .withWriteThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                                 .withListener(listener).start();
         var client = HttpClient.newBuilder()
                                .sslContext(SecurityTools.clientContext(rootCertificate))
                                .version(HttpClient.Version.HTTP_2)
                                .build()) {
      int port = server.getActualPort();

      // 11 requests over a connection whose limit is 10: request 10 is served, the server sends GOAWAY(NO_ERROR), and
      // the client is forced onto a second connection for request 11. All 11 must succeed on h2.
      for (int i = 0; i < 11; i++) {
        var resp = client.send(
            HttpRequest.newBuilder(URI.create("https://local.lattejava.org:" + port + "/")).build(),
            HttpResponse.BodyHandlers.discarding());
        assertEquals(resp.statusCode(), 200, "Request [" + i + "] failed");
        assertEquals(resp.version(), HttpClient.Version.HTTP_2,
            "JDK HttpClient silently downgrades to h1.1 on ALPN failure — assert h2 explicitly");
      }
      assertEquals(instrumenter.getConnections(), 2, "Expected a second connection after maxRequestsPerConnection GOAWAY");
    }
  }

  @Test(groups = "timeouts")
  public void midFrameTrickleIsEvicted() throws Exception {
    try (var server = h2cServer(cfg -> cfg.withKeepAliveTimeoutDuration(Duration.ofSeconds(1)))) {
      Socket socket = openH2CConnection(server.getActualPort());
      var out = socket.getOutputStream();
      // 3 bytes of a frame header, then silence: mid-frame with zero live streams. The shrinking idle budget fires
      // and the connection must close within a few seconds instead of hanging forever.
      out.write(new byte[]{0, 0, 8});
      out.flush();
      socket.setSoTimeout(8_000);
      assertEquals(socket.getInputStream().read(new byte[64]) >= 0 ? keepDraining(socket) : -1, -1,
          "Connection must be closed after a mid-frame stall");
      socket.close();
    }
  }

  @Test(groups = "timeouts")
  public void pingsDoNotExtendTheIdleDeadline() throws Exception {
    try (var server = h2cServer(cfg -> cfg.withKeepAliveTimeoutDuration(Duration.ofSeconds(2)))) {
      Socket socket = openH2CConnection(server.getActualPort());
      var out = socket.getOutputStream();
      var in = socket.getInputStream();
      long start = System.currentTimeMillis();
      long lastPing = 0;
      int code = -2;
      // PING every 300ms (paced, not spun — the server ACKs instantly, so a tight write/read loop would flood past the
      // PING rate limit); the server must ACK them AND still expire at ~2s (not 2x, not ever).
      while (System.currentTimeMillis() - start < 10_000) {
        long now = System.currentTimeMillis();
        if (now - lastPing >= 300) {
          writeFrameHeader(out, 8, 0x6, 0, 0);
          out.write(new byte[8]);
          out.flush();
          lastPing = now;
        }
        socket.setSoTimeout(300);
        try {
          int[] header = readFrameHeader(in);
          in.readNBytes(header[3]);
          if (header[0] == 0x7) {
            code = 0; // saw GOAWAY
            break;
          }
        } catch (SocketTimeoutException ignore) {
        } catch (EOFException | AssertionError e) {
          break; // server closed
        }
      }
      long elapsed = System.currentTimeMillis() - start;
      assertEquals(code, 0, "Expected GOAWAY despite PING keep-alives");
      assertTrue(elapsed >= 1_500 && elapsed < 4_500, "Deadline drifted: [" + elapsed + "] ms");
      socket.close();
    }
  }

  /**
   * Slow-writer eviction: the client completes its request (END_STREAM) then never sends a WINDOW_UPDATE and never
   * grants the stream more credit. The handler writes a response larger than the 65,535-byte default stream window, so
   * after the first window's worth of DATA the handler parks on flow control — the {@code writingResponse()} gate stays
   * open while {@code writeThroughput} decays below {@code minimumWriteThroughput}, and the reaper must evict. The test
   * drains inbound DATA (which does NOT extend the H2 window — only a WINDOW_UPDATE would) so the server's GOAWAY is
   * not wedged behind a full TCP send buffer.
   */
  @Test(groups = "timeouts")
  public void slowWriterStarvedOfWindowUpdatesIsEvicted() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> {
      req.getInputStream().readAllBytes();
      res.setStatus(200);
      res.getOutputStream().write(new byte[200 * 1024]); // > 65,535: forces a flow-control park after one window.
      res.getOutputStream().close();
    };
    try (var server = new HTTPServer().withHandler(handler)
                                      .withKeepAliveTimeoutDuration(Duration.ofSeconds(30))
                                      .withMinimumWriteThroughput(200 * 1024)
                                      .withProcessingTimeoutDuration(Duration.ofSeconds(30))
                                      .withWriteThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                                      .withListener(listener)
                                      .start()) {
      Socket socket = openH2CConnection(server.getActualPort());
      writeSimpleGet(socket); // END_HEADERS | END_STREAM: request complete, no WINDOW_UPDATE will ever follow.
      socket.setSoTimeout(10_000);
      long start = System.currentTimeMillis();
      // Drain frames (response HEADERS, one window of DATA, then the eviction GOAWAY) until GOAWAY or EOF. Draining TCP
      // never re-opens the H2 window, so the handler stays parked and the slow-write eviction still fires.
      int code = readUntilGoaway(socket.getInputStream());
      long elapsed = System.currentTimeMillis() - start;
      assertTrue(code == 0xb || code == -1, // 0xb = ENHANCE_YOUR_CALM
          "Slow writer must be evicted with ENHANCE_YOUR_CALM or a plain close, got GOAWAY code [" + code + "]");
      assertTrue(elapsed < 8_000, "Slow-writer eviction did not land within the bound: [" + elapsed + "] ms");
      socket.close();
    }
  }

  /**
   * Builds and starts an h2c prior-knowledge server on an OS-assigned port. The handler drains the request body and
   * returns 200. The customizer applies the test's explicit timeouts (initial-read, keep-alive) before start.
   */
  private HTTPServer h2cServer(Consumer<HTTPServer> customizer) {
    var listener = new HTTPListenerConfiguration(0).withSniffProtocolVersion(true);
    HTTPHandler handler = (req, res) -> {
      req.getInputStream().readAllBytes();
      res.setStatus(200);
    };
    var server = new HTTPServer().withHandler(handler)
                                 .withMinimumReadThroughput(200 * 1024)
                                 .withMinimumWriteThroughput(200 * 1024)
                                 .withProcessingTimeoutDuration(Duration.ofSeconds(10))
                                 .withReadThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                                 .withWriteThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                                 .withListener(listener);
    customizer.accept(server);
    return server.start();
  }

  /**
   * Reads and discards inbound bytes until EOF. Always returns {@code -1} so it can be composed into a single assertion
   * against a closed connection.
   */
  private int keepDraining(Socket socket) throws Exception {
    var in = socket.getInputStream();
    byte[] buf = new byte[256];
    //noinspection StatementWithEmptyBody
    while (in.read(buf) >= 0) {
      // Drain until the server closes.
    }
    return -1;
  }

  /**
   * Writes a single END_HEADERS | END_STREAM HEADERS frame carrying the canned GET on {@code streamId}.
   */
  private void writeSimpleGet(Socket socket) throws Exception {
    var out = socket.getOutputStream();
    writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1, 1);
    out.write(MINIMAL_HPACK_GET);
    out.flush();
  }
}

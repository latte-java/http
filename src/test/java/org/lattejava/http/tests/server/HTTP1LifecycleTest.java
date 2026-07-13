/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import java.time.Duration;

import static org.testng.Assert.*;

/**
 * Verifies that an HTTP/1.1 keep-alive connection's read-throughput measurement is scoped to the current request rather
 * than the connection's lifetime. A long idle keep-alive gap must not poison the throughput denominator and cause the
 * reaper to evict a healthy, slowly-dribbled follow-up request.
 * <p>
 * The follow-up request's <em>preamble</em> is dribbled (not its body): {@code HTTP1Connection} holds
 * {@code State.Read} for the entire header parse but transitions to {@code State.Process} before the body is read, so
 * the slow-reader floor applies to the header dribble. The dribble rate (~2 KB/s) sits above the 1 KB/s floor, yet the
 * un-reset lifetime average — tiny bytes divided by the 10+ s that includes the idle gap — falls below it, so the old
 * reaper evicts mid-preamble. The phase epoch reset must let the new request survive.
 *
 * @author Brian Pontarelli
 */
public class HTTP1LifecycleTest extends BaseTest {
  @Test(groups = "timeouts")
  public void keepAliveIdleGapDoesNotPoisonTheNextRequest() throws Exception {
    try (var server = new HTTPServer().withHandler((req, res) -> {
                                        req.getInputStream().readAllBytes();
                                        res.setStatus(200);
                                      })
                                      .withKeepAliveTimeoutDuration(Duration.ofSeconds(60))
                                      .withInitialReadTimeout(Duration.ofSeconds(30))
                                      .withMinimumReadThroughput(1024)
                                      .withReadThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                                      .withListener(new HTTPListenerConfiguration(0))
                                      .start()) {
      int port = server.getActualPort();
      try (var socket = new Socket("127.0.0.1", port)) {
        var out = socket.getOutputStream();
        var in = socket.getInputStream();

        out.write("GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n".getBytes());
        out.flush();
        String first = readFullResponse(in);
        assertTrue(first.startsWith("HTTP/1.1 200"), "First request failed: [" + first + "]");

        // Idle keep-alive gap. Under the old reaper this poisons the lifetime read-throughput denominator: the read
        // epoch's firstReadInstant is still the first request's, so the second request measures against 10+ s.
        Thread.sleep(10_000);

        // Request 2 whose preamble is padded and dribbled at ~2 KB/s for ~6 s, above the 1 KB/s floor. The connection
        // sits in State.Read for the whole dribble, spanning multiple 2 s reaper cycles. The un-reset lifetime average
        // reads (tiny bytes / 10+ s) and the old reaper evicts mid-preamble; the fresh phase epoch must survive.
        var preamble = new StringBuilder("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nX-Pad: ");
        preamble.append("A".repeat(12_000));
        preamble.append("\r\n\r\n");
        byte[] bytes = preamble.toString().getBytes();
        for (int i = 0; i < bytes.length; i += 512) {
          out.write(bytes, i, Math.min(512, bytes.length - i));
          out.flush();
          Thread.sleep(250);
        }

        String response = readFullResponse(in);
        assertTrue(response.startsWith("HTTP/1.1 200"), "Second request after the idle gap was evicted: [" + response + "]");
      }
    }
  }

  private String readFullResponse(InputStream in) throws IOException {
    var buf = new StringBuilder();
    byte[] chunk = new byte[4096];
    // Responses here are small and Connection: keep-alive; read the headers by draining until the header terminator.
    // Both responses carry Content-Length: 0, so the header block is the entire response.
    while (!buf.toString().contains("\r\n\r\n")) {
      int n = in.read(chunk);
      if (n < 0) {
        break;
      }
      buf.append(new String(chunk, 0, n));
    }
    return buf.toString();
  }
}

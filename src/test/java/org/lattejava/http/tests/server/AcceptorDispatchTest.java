/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import java.security.cert.Certificate;
import java.time.Duration;

import static org.testng.Assert.*;

/**
 * Verifies that protocol selection runs on the per-connection virtual thread, not the accept thread: a client that
 * stalls the TLS handshake must not block acceptance of other connections, and a connection still negotiating must be
 * bounded by SO_TIMEOUT rather than evicted by the reaper's slow-reader throughput check.
 *
 * @author Brian Pontarelli
 */
public class AcceptorDispatchTest extends BaseTest {
  @Test(groups = "timeouts")
  public void acceptorNotBlockedByStalledHandshake() throws Exception {
    // Initial-read SO_TIMEOUT of 20s: under the old (accept-thread) behavior the stalled handshake would hold the
    // accept loop for ~20s, so the second request would take ~20s. Under the new behavior it is instant.
    HTTPServer server = startTLSServer(Duration.ofSeconds(20));
    Socket staller = null;
    try {
      // Connect TCP to the TLS port but send no ClientHello — the server-side handshake blocks on its virtual thread.
      staller = new Socket("127.0.0.1", 4242);
      assertTrue(staller.isConnected());

      // A normal HTTPS request on a second connection must complete quickly while the staller is still pending.
      HttpClient client = makeClient("https", null);
      long start = System.currentTimeMillis();
      HttpResponse<String> response = client.send(
          HttpRequest.newBuilder().uri(makeURI("https", "")).GET().build(),
          HttpResponse.BodyHandlers.ofString());
      long elapsed = System.currentTimeMillis() - start;

      assertEquals(response.statusCode(), 200);
      assertTrue(elapsed < 5000, "Second request took [" + elapsed + "] ms; the accept thread was blocked by the stalled handshake.");
    } finally {
      if (staller != null) {
        staller.close();
      }
      server.close();
    }
  }

  @Test(groups = "timeouts")
  public void negotiatingConnectionSurvivesReaperCycles() throws Exception {
    // Large SO_TIMEOUT (20s) so the handshake itself will not time out during the test window. The reaper cycles every
    // ~2s with a 200 KB/s minimum read throughput. If a negotiating connection were reported as Read, the reaper would
    // measure ~0 bytes/s and close it on the first cycle (~2-3s). With State.Negotiating it must stay open.
    HTTPServer server = startTLSServer(Duration.ofSeconds(20));
    Socket staller = null;
    try {
      staller = new Socket("127.0.0.1", 4242);
      staller.setSoTimeout(6000); // longer than two reaper cycles

      // Read one byte. If the server wrongly reaped the negotiating connection, the stream closes and read() returns
      // -1 promptly. If correct, the server holds the connection open and our own read times out after 6s.
      InputStream in = staller.getInputStream();
      try {
        int b = in.read();
        fail("Server closed the negotiating connection (read returned [" + b + "]); it was reaped before SO_TIMEOUT.");
      } catch (SocketTimeoutException expected) {
        // Correct: the connection was still open after >2 reaper cycles, bounded only by SO_TIMEOUT.
      }
    } finally {
      if (staller != null) {
        staller.close();
      }
      server.close();
    }
  }

  private HTTPServer startTLSServer(Duration initialReadTimeout) {
    var certChain = new Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(4242, certChain, keyPair.getPrivate());
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> res.setStatus(200))
        .withInitialReadTimeout(initialReadTimeout)
        .withHTTP1(h1 -> h1.withKeepAliveTimeoutDuration(ServerTimeout))
        .withProcessingTimeoutDuration(ServerTimeout)
        .withMinimumReadThroughput(200 * 1024)
        .withMinimumWriteThroughput(200 * 1024)
        .withReadThroughputCalculationDelayDuration(Duration.ofSeconds(1))
        .withWriteThroughputCalculationDelayDuration(Duration.ofSeconds(1))
        .withListener(listener);
    server.start();
    return server;
  }
}

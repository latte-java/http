/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;
import module org.lattejava.http;

/**
 * Shared liveness policy for protocol connections. {@link #check(long)} is the reaper's former per-state policy with
 * the single state scalar replaced by protocol-supplied gates, so an HTTP/2 connection - which can be reading,
 * writing, and processing simultaneously - answers each question independently. The Throughput math, floors, and
 * graces are unchanged. Per-phase counter resets (design doc 2026-07-09-h2-connection-lifecycle-design.md section 4.4)
 * are the subclass's responsibility - they land on the real phase transition in the I/O thread (H1 enters {@code Read}
 * for a new request and {@code Write} when it commits a response), not on the reaper's late first observation of a
 * gate. Resetting in the reaper would discard a response already buffered before the reaper looked and, for a writer
 * blocked on a full socket buffer, would leave the counters at zero forever so the stall is never caught.
 *
 * @author Brian Pontarelli
 */
public abstract class BaseHTTPConnection implements HTTPConnection {
  protected final HTTPServerConfiguration configuration;

  protected final Socket socket;

  protected final long startInstant;

  protected final Throughput throughput;

  protected BaseHTTPConnection(HTTPServerConfiguration configuration, Socket socket, Throughput throughput) {
    this.configuration = configuration;
    this.socket = socket;
    this.throughput = throughput;
    this.startInstant = System.currentTimeMillis();
  }

  @Override
  public final EvictionReason check(long now) {
    if (readingRequest() && throughput.readThroughput(now) < configuration.getMinimumReadThroughput()) {
      return EvictionReason.SlowRead;
    }

    if (writingResponse() && throughput.writeThroughput(now) < configuration.getMinimumWriteThroughput()) {
      return EvictionReason.SlowWrite;
    }

    if (processing() && now - lastProgressInstant() > configuration.getProcessingTimeoutDuration().toMillis()) {
      return EvictionReason.ProcessingTimeout;
    }

    Duration maxAge = configuration.getMaxConnectionAgeDuration();
    if (maxAge != null && now - startInstant > maxAge.toMillis()) {
      return EvictionReason.MaxAge;
    }

    return null;
  }

  @Override
  public Socket getSocket() {
    return socket;
  }

  @Override
  public long getStartInstant() {
    return startInstant;
  }

  /**
   * The instant of the last observable progress (bytes moved, work advanced) used by the processing-timeout check.
   */
  protected abstract long lastProgressInstant();

  /**
   * True while work is in flight (a request is being processed).
   */
  protected abstract boolean processing();

  /**
   * True while the peer owes us request bytes - the slow-reader floor applies only then.
   */
  protected abstract boolean readingRequest();

  /**
   * True while a response is being written - the slow-writer floor applies only then.
   */
  protected abstract boolean writingResponse();
}

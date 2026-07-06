/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;

/**
 * The stream roster for one connection: live streams, recently-closed memory, the highest client stream-id seen, and
 * the MAX_CONCURRENT_STREAMS bound. The registry classifies stream IDs and materializes stream objects — it never
 * interprets frames; all frame policy lives in {@link HTTP2Stream}.
 *
 * <p>Threading: {@link #lookup} and {@link #open} are reader-thread-only. {@link #remove} and {@link #close} may be
 * called from handler virtual-threads (completion cleanup), so recently-closed memory is guarded by its own lock.
 * {@link #highestSeenStreamId()} is read by the acceptor thread during shutdown.
 */
public class HTTP2StreamRegistry {
  private static final int MAX_RECENTLY_CLOSED = 100;

  private final HTTP2StreamFrameHandlers handlers;

  private final AtomicInteger highestSeenStreamId = new AtomicInteger();

  private final HTTP2Settings localSettings;

  private final Map<Integer, HTTP2Stream> openStreams = new ConcurrentHashMap<>();

  private final HTTP2Settings peerSettings;

  private final Deque<Integer> recentlyClosed = new ArrayDeque<>(MAX_RECENTLY_CLOSED + 1);

  public HTTP2StreamRegistry(HTTP2Settings localSettings, HTTP2Settings peerSettings, HTTP2StreamFrameHandlers handlers) {
    this.localSettings = localSettings;
    this.peerSettings = peerSettings;
    this.handlers = handlers;
  }

  /**
   * Removes {@code streamId} from the roster and records it in recently-closed memory (the RST_STREAM path and
   * fully-closed handler completion). Contrast with {@link #remove}, which leaves no memory.
   */
  public void close(int streamId) {
    openStreams.remove(streamId);
    synchronized (recentlyClosed) {
      recentlyClosed.addLast(streamId);
      if (recentlyClosed.size() > MAX_RECENTLY_CLOSED) {
        recentlyClosed.removeFirst();
      }
    }
  }

  public int highestSeenStreamId() {
    return highestSeenStreamId.get();
  }

  public Collection<HTTP2Stream> liveStreams() {
    return openStreams.values();
  }

  /**
   * Returns the stream for {@code streamId}: the live one, or a transient flyweight in the RFC 9113 state the ID is in
   * — CLOSED (remembered) if recently closed, CLOSED (forgotten) if at-or-below the highest seen ID, IDLE above it.
   */
  public HTTP2Stream lookup(int streamId) {
    HTTP2Stream stream = openStreams.get(streamId);
    if (stream != null) {
      return stream;
    }

    boolean rememberedClosed;
    synchronized (recentlyClosed) {
      rememberedClosed = recentlyClosed.contains(streamId);
    }
    if (rememberedClosed) {
      return materialize(streamId, HTTP2Stream.State.CLOSED, true);
    }

    if (streamId <= highestSeenStreamId.get()) {
      return materialize(streamId, HTTP2Stream.State.CLOSED, false);
    }

    return materialize(streamId, HTTP2Stream.State.IDLE, false);
  }

  /**
   * Registers an opening stream. Bumps the highest seen ID even when refusing at the MAX_CONCURRENT_STREAMS cap — the
   * ID is consumed either way, so a retry on the same ID is a monotonicity PROTOCOL_ERROR (RFC 9113 permits retrying a
   * refused request only on a new stream).
   */
  public boolean open(HTTP2Stream stream) {
    int streamId = stream.streamId();
    highestSeenStreamId.getAndUpdate(cur -> Math.max(cur, streamId));

    if (openStreams.size() >= localSettings.maxConcurrentStreams()) {
      return false;
    }
    openStreams.put(streamId, stream);

    return true;
  }

  /**
   * Removes {@code streamId} without closed-stream memory — the handler-thread completion path.
   */
  public void remove(int streamId) {
    openStreams.remove(streamId);
  }

  private HTTP2Stream materialize(int streamId, HTTP2Stream.State state, boolean remembered) {
    return new HTTP2Stream(streamId, state, remembered, localSettings.initialWindowSize(),
        peerSettings.initialWindowSize(), handlers, this);
  }
}

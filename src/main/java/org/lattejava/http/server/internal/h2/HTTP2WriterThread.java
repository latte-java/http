/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

/**
 * Owns the single virtual thread that serializes every outbound HTTP/2 frame for one connection. Producers — the reader
 * thread (PING acks, WINDOW_UPDATE, RST_STREAM, SETTINGS ack, GOAWAY) and the per-request handler threads (response
 * HEADERS, DATA, trailers) — enqueue frames through {@link #enqueueOrCloseWriter}, {@link #enqueueBlocking}, and
 * {@link #tryEnqueue}; the queue itself is private and never handed out.
 *
 * <p>{@link #run()} blocks for the next frame, opportunistically drains a batch, writes each frame through the
 * {@link HTTP2FrameWriter}, and flushes once per batch. It exits when it dequeues the writer-shutdown sentinel (a
 * {@link HTTP2Frame.GoawayFrame} with {@code lastStreamId == -1}, enqueued via {@link #requestStop()}). On exit —
 * whether the clean sentinel return or a mid-write {@link IOException} from a broken pipe / peer reset — it marks
 * itself closed and interrupts the reader thread so the connection tears down.
 *
 * @author Daniel DeGroff
 */
public class HTTP2WriterThread implements Runnable {
  // Maximum number of frames the writer drains per loop iteration. The blocking head-take is unchanged; this caps the
  // opportunistic drainTo that follows. 32 chosen so that even at peerMaxFrameSize=16384 a full batch is ~512KB, inside
  // one TCP-window worth of data on a typical link; smaller batches reduce per-frame queue contention without holding
  // many MB in userspace under sustained DATA bursts.
  private static final int BATCH_SIZE = 32;

  // The writer-shutdown sentinel: a GOAWAY whose lastStreamId is -1 (negative, never valid for a real GOAWAY). Its
  // encoding is private to this class — callers request shutdown via requestStop(), never by building this frame.
  private static final HTTP2Frame SHUTDOWN_SENTINEL = new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]);

  private final Logger logger;

  private final BlockingQueue<HTTP2Frame> queue;

  private final Thread readerThread;

  private final HTTP2FrameWriter writer;

  private volatile boolean closed;

  private volatile Thread thread;

  /**
   * Production constructor. Creates the outbound queue, which never escapes this instance.
   *
   * @param writer       the wire-frame encoder.
   * @param readerThread the connection reader thread, interrupted when this writer closes.
   * @param logger       the connection logger.
   */
  public HTTP2WriterThread(HTTP2FrameWriter writer, Thread readerThread, Logger logger) {
    this(writer, readerThread, logger, new LinkedBlockingQueue<>(128));
  }

  /**
   * Queue-injecting constructor for tests that need to pre-load frames and drive {@link #run()} directly. Production
   * code uses the four-argument constructor.
   */
  public HTTP2WriterThread(HTTP2FrameWriter writer, Thread readerThread, Logger logger, BlockingQueue<HTTP2Frame> queue) {
    this.writer = writer;
    this.readerThread = readerThread;
    this.logger = logger;
    this.queue = queue;
  }

  /**
   * Test seam — wraps a caller-supplied queue so a test can inject frames via {@link #enqueueBlocking} and drain them
   * for assertions, with no socket or thread. Only the enqueue methods are valid on the returned instance; the frame
   * writer, reader thread, and logger are {@code null}.
   *
   * @param queue the queue to expose through the enqueue methods.
   * @return a writer backed by {@code queue}.
   */
  public static HTTP2WriterThread forQueue(BlockingQueue<HTTP2Frame> queue) {
    return new HTTP2WriterThread(null, null, null, queue);
  }

  /**
   * Blocks until {@code frame} can be enqueued, surfacing an interrupt as {@link InterruptedIOException}. The block is
   * the intended queue-full back-pressure on a producing handler thread. Used for response DATA frames.
   *
   * @param frame the frame to enqueue.
   * @throws InterruptedIOException if the calling thread is interrupted while waiting.
   */
  public void enqueueBlocking(HTTP2Frame frame) throws InterruptedIOException {
    try {
      queue.put(frame);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException();
    }
  }

  /**
   * Enqueues {@code frame} with a bounded wait. Returns {@code false} (logging at debug) if the writer is already
   * closed, or if the queue stays full past the timeout — in which case the writer is declared closed. Reader-side and
   * response-header callers use this; most ignore the result on fire-and-forget paths.
   *
   * @param frame the frame to enqueue.
   * @return {@code true} if the frame was enqueued.
   */
  public boolean enqueueOrCloseWriter(HTTP2Frame frame) {
    if (closed) {
      logger.debug("Dropping frame [{}] — writer thread already closed", frame);
      return false;
    }

    try {
      if (!queue.offer(frame, 5, TimeUnit.SECONDS)) {
        logger.debug("Writer queue full for [5s]; declaring writer closed and dropping frame [{}]", frame);
        closed = true;
        return false;
      }
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Returns whether the writer has closed — set when the loop exits or when {@link #enqueueOrCloseWriter} times out.
   *
   * @return {@code true} once the writer is closed.
   */
  public boolean isClosed() {
    return closed;
  }

  /**
   * Waits up to {@code timeout} for the writer thread to finish, returning immediately if it was never started.
   * Swallows an interrupt (re-setting the calling thread's interrupt status) so teardown call sites stay terse.
   *
   * @param timeout the maximum time to wait.
   */
  public void join(Duration timeout) {
    Thread t = thread;
    if (t == null) {
      return;
    }
    try {
      t.join(timeout);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Enqueues the writer-shutdown sentinel so {@link #run()} flushes whatever is queued and then exits. A no-op if the
   * writer is already closed.
   *
   * @return {@code true} if the sentinel was enqueued.
   */
  public boolean requestStop() {
    return enqueueOrCloseWriter(SHUTDOWN_SENTINEL);
  }

  @Override
  public void run() {
    try {
      List<HTTP2Frame> batch = new ArrayList<>(BATCH_SIZE);
      while (true) {
        // Blocking head-take preserves the idle-park behavior; the non-blocking drainTo coalesces whatever concurrent
        // producers have already enqueued so the syscall and queue-lock are amortized across the batch.
        HTTP2Frame head = queue.take();
        batch.add(head);
        queue.drainTo(batch, BATCH_SIZE - 1);

        for (HTTP2Frame frame : batch) {
          if (frame instanceof HTTP2Frame.GoawayFrame goaway && goaway.lastStreamId() == -1) {
            // Sentinel: flush whatever preceded it, then exit. Frames after the sentinel in the batch are discarded.
            writer.flush();
            return;
          }

          writer.writeFrame(frame);
        }

        writer.flush();
        batch.clear();
      }
    } catch (Exception e) {
      logger.debug("Writer thread ended unexpectedly; signaling reader", e);
    } finally {
      closed = true;
      readerThread.interrupt();
    }
  }

  /**
   * Spawns the {@code h2-writer} virtual thread running this loop and captures the handle for {@link #join}.
   */
  public void start() {
    thread = Thread.ofVirtual().name("h2-writer").start(this);
  }

  /**
   * Best-effort timed enqueue that never closes the writer. Used only by the handler error-cleanup path, which must not
   * block connection teardown.
   *
   * @param frame   the frame to enqueue.
   * @param timeout the maximum time to wait.
   * @param unit    the unit of {@code timeout}.
   * @return {@code true} if the frame was accepted within the timeout.
   * @throws InterruptedException if interrupted while waiting.
   */
  public boolean tryEnqueue(HTTP2Frame frame, long timeout, TimeUnit unit) throws InterruptedException {
    return queue.offer(frame, timeout, unit);
  }
}

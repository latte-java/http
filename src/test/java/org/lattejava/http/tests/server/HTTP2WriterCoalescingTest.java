/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.log.AccumulatingLogger;
import org.lattejava.http.server.internal.h2.HTTP2Frame;
import org.lattejava.http.server.internal.h2.HTTP2FrameWriter;
import org.lattejava.http.server.internal.h2.HTTP2WriterThread;

import static org.testng.Assert.*;

/**
 * Unit tests for the writer-thread loop ({@link HTTP2WriterThread#run()}). Verifies that the loop batches frames already
 * queued at drain time into a single flush, exits cleanly on the sentinel even when other frames are batched ahead of
 * it, and — on a mid-batch write failure — marks the writer closed and interrupts the reader instead of propagating the
 * exception.
 */
public class HTTP2WriterCoalescingTest {

  /**
   * Counting OutputStream — records bytes written and counts flush() calls. Used to assert the number of flushes per
   * batch.
   */
  static final class CountingOutputStream extends OutputStream {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    int flushes;

    @Override
    public void flush() {
      flushes++;
    }

    @Override
    public void write(int b) {
      bytes.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) {
      bytes.write(b, off, len);
    }
  }

  /**
   * Throwing OutputStream — used to verify that a mid-batch IOException closes the writer. The byte threshold in the
   * test ({@code throwAfterBytes}) assumes {@link HTTP2FrameWriter} issues one {@code write(byte[], int, int)} call per
   * frame (header + payload concatenated into the shared write buffer). If that ever changes to split header and payload
   * into separate writes, the threshold in {@code io_exception_mid_batch_closes_writer_and_interrupts_reader} will need
   * to be recomputed.
   */
  static final class ThrowingOutputStream extends OutputStream {
    final int throwAfterBytes;
    int written;

    ThrowingOutputStream(int throwAfterBytes) {
      this.throwAfterBytes = throwAfterBytes;
    }

    @Override
    public void flush() {}

    @Override
    public void write(int b) throws IOException {
      maybeThrow(1);
      written++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      maybeThrow(len);
      written += len;
    }

    private void maybeThrow(int incoming) throws IOException {
      if (written + incoming > throwAfterBytes) {
        throw new IOException("simulated socket failure");
      }
    }
  }

  @Test(timeOut = 5_000)
  public void batched_frames_produce_single_flush() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>(128);
    var out = new CountingOutputStream();
    var frameWriter = new HTTP2FrameWriter(out, new byte[9 + 16384]);
    var writerThread = new HTTP2WriterThread(frameWriter, out, new Thread(), new AccumulatingLogger(), queue);

    // Pre-load 5 small DATA frames + the sentinel so drainTo grabs them all in one shot.
    for (int i = 1; i <= 5; i++) {
      queue.put(new HTTP2Frame.DataFrame(i, 0, ("frame-" + i).getBytes()));
    }
    queue.put(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));

    writerThread.run();

    // 5 frames written + sentinel observed → 1 flush, not 5.
    assertEquals(out.flushes, 1, "Expected one flush for the batch of 5 frames; got [" + out.flushes + "]");
    // Sanity: all 5 frames hit the byte stream (9-byte header + 7-byte payload each = 16 bytes per frame).
    assertEquals(out.bytes.size(), 5 * 16, "Expected 5 frames × 16 bytes; got [" + out.bytes.size() + "]");
  }

  @Test(timeOut = 5_000)
  public void sentinel_mid_batch_flushes_preceding_frames_then_exits() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>(128);
    var out = new CountingOutputStream();
    var frameWriter = new HTTP2FrameWriter(out, new byte[9 + 16384]);
    var writerThread = new HTTP2WriterThread(frameWriter, out, new Thread(), new AccumulatingLogger(), queue);

    // Queue: 3 frames, then sentinel, then 2 more frames that should NOT be written.
    queue.put(new HTTP2Frame.DataFrame(1, 0, "a".getBytes()));
    queue.put(new HTTP2Frame.DataFrame(2, 0, "b".getBytes()));
    queue.put(new HTTP2Frame.DataFrame(3, 0, "c".getBytes()));
    queue.put(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));
    queue.put(new HTTP2Frame.DataFrame(4, 0, "d".getBytes()));
    queue.put(new HTTP2Frame.DataFrame(5, 0, "e".getBytes()));

    writerThread.run();

    // 3 frames × 10 bytes (9 header + 1 payload) = 30. Frames 4 and 5 must NOT be present.
    assertEquals(out.bytes.size(), 30, "Pre-sentinel frames should hit the wire; got [" + out.bytes.size() + "] bytes");
    assertEquals(out.flushes, 1, "Expected exactly one flush before sentinel exit; got [" + out.flushes + "]");
    // Post-sentinel frames were drained into the batch by drainTo but not written — the loop exits on the sentinel
    // before reaching them. They are not in the queue (drainTo moved them) and not in the output (loop exited).
    assertTrue(queue.isEmpty(), "Queue should be empty — drainTo moved post-sentinel frames into the batch, which was discarded on sentinel exit; got queue size [" + queue.size() + "]");
  }

  @Test(timeOut = 5_000)
  public void io_exception_mid_batch_closes_writer_and_interrupts_reader() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>(128);
    // Throws on the 3rd frame. Each frame is 9 (header) + 5 (payload) = 14 bytes. 2 frames = 28 bytes ok;
    // the 3rd push past 28 bytes triggers the throw.
    var out = new ThrowingOutputStream(28);
    var frameWriter = new HTTP2FrameWriter(out, new byte[9 + 16384]);
    var readerStub = new Thread();
    var writerThread = new HTTP2WriterThread(frameWriter, out, readerStub, new AccumulatingLogger(), queue);

    for (int i = 1; i <= 5; i++) {
      queue.put(new HTTP2Frame.DataFrame(i, 0, "data!".getBytes()));
    }
    queue.put(new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]));

    // run() catches the mid-batch IOException (its production contract), so it returns normally rather than throwing.
    writerThread.run();

    // Sanity: the loop wrote frames 1 and 2 successfully before failing on frame 3.
    assertEquals(out.written, 28, "Expected 2 frames (28 bytes) before the throw; got [" + out.written + "]");
    assertTrue(writerThread.isClosed(), "Writer should mark itself closed after a mid-batch write failure");
    assertTrue(readerStub.isInterrupted(), "Reader thread should be interrupted so the connection tears down");
  }
}

/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.h2.HTTP2Frame;
import org.lattejava.http.server.internal.h2.HTTP2Window;
import org.lattejava.http.server.internal.h2.HTTP2OutputStream;
import org.lattejava.http.server.internal.h2.HTTP2Stream;
import org.lattejava.http.server.internal.h2.HTTP2WriterThread;

import static org.testng.Assert.*;

public class HTTP2OutputStreamFragmentationTest {
  /**
   * RFC 9113 §6.9.1 — outbound DATA must respect BOTH the stream and connection send windows. With the stream window
   * wide open but the connection window at 10 octets, the first frame must be capped at 10 (not maxFrameSize=16), then
   * the writer blocks until a stream-0 WINDOW_UPDATE replenishes the connection window, after which the rest flows
   * fragmented by maxFrameSize.
   */
  @Test
  public void connection_window_caps_chunk_and_blocks_when_exhausted() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>(128);
    var stream = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 65535, null, null);
    var connectionWindow = new HTTP2Window(10);
    var os = new HTTP2OutputStream(stream, HTTP2WriterThread.forQueue(queue), connectionWindow, 16);

    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(50);
        connectionWindow.increment(100);
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
    });

    os.write(new byte[30]);
    os.close();

    var f1 = (HTTP2Frame.DataFrame) queue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
    assertNotNull(f1, "First DATA frame should arrive within 2 seconds");
    assertEquals(f1.data().length, 10, "First frame capped by the 10-octet connection window");
    assertEquals(f1.flags(), 0);

    var f2 = (HTTP2Frame.DataFrame) queue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
    assertNotNull(f2, "Second DATA frame should arrive after the connection WINDOW_UPDATE");
    assertEquals(f2.data().length, 16, "Remaining bytes fragmented by maxFrameSize");

    var f3 = (HTTP2Frame.DataFrame) queue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
    assertNotNull(f3);
    assertEquals(f3.data().length, 4);
    assertEquals(f3.flags(), HTTP2Frame.FLAG_END_STREAM);
  }

  @Test
  public void empty_close_emits_zero_length_end_stream() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>();
    var stream = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 65535, null, null);
    var os = new HTTP2OutputStream(stream, HTTP2WriterThread.forQueue(queue), 16384);

    os.close();

    var f = (HTTP2Frame.DataFrame) queue.take();
    assertEquals(f.data().length, 0);
    assertEquals(f.flags(), HTTP2Frame.FLAG_END_STREAM);
  }

  @Test
  public void large_write_fragments_against_max_frame_size() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>();
    var stream = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 65535, null, null);
    var os = new HTTP2OutputStream(stream, HTTP2WriterThread.forQueue(queue), 16); // tiny max-frame-size for test

    byte[] data = new byte[40];
    for (int i = 0; i < 40; i++) data[i] = (byte) i;
    os.write(data);
    os.close();

    // Expect 3 frames: 16, 16, 8 (last with END_STREAM)
    var f1 = (HTTP2Frame.DataFrame) queue.take();
    assertEquals(f1.data().length, 16);
    assertEquals(f1.flags(), 0);

    var f2 = (HTTP2Frame.DataFrame) queue.take();
    assertEquals(f2.data().length, 16);
    assertEquals(f2.flags(), 0);

    var f3 = (HTTP2Frame.DataFrame) queue.take();
    assertEquals(f3.data().length, 8);
    assertEquals(f3.flags(), HTTP2Frame.FLAG_END_STREAM);
  }

  @Test
  public void single_write_no_fragmentation() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>();
    var stream = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 65535, null, null);
    var os = new HTTP2OutputStream(stream, HTTP2WriterThread.forQueue(queue), 16384);

    os.write("hello".getBytes());
    os.close();

    var f1 = (HTTP2Frame.DataFrame) queue.take();
    assertEquals(f1.data(), "hello".getBytes());
    assertEquals(f1.flags(), HTTP2Frame.FLAG_END_STREAM, "Final frame has END_STREAM");
  }

  /**
   * RFC 9113 §6.9.1 — when the initial send-window is 1, the server must send 1 byte at a time, blocked until the peer
   * sends WINDOW_UPDATE. Verifies that {@code HTTP2OutputStream.flushAndFragment} never waits for window >= chunk; it
   * waits only until window > 0, then sends up to min(window, maxFrameSize, remaining).
   */
  @Test
  public void flow_control_window_one_sends_byte_by_byte() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>(128);
    // Send-window starts at 1 (SETTINGS_INITIAL_WINDOW_SIZE=1 from peer).
    var stream = new HTTP2Stream(1, HTTP2Stream.State.IDLE, false, 65535, 1, null, null);
    var os = new HTTP2OutputStream(stream, HTTP2WriterThread.forQueue(queue), 16384);

    // Simulate WINDOW_UPDATE arriving from a background thread after a brief delay.
    // The flushAndFragment loop will send 1 byte (consuming the window), wait for more credit, then send the second byte.
    Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(50);
        stream.sendWindow().increment(1);
      } catch (InterruptedException ignore) {
        Thread.currentThread().interrupt();
      }
    });

    os.write("ok".getBytes());
    os.close();

    // Expect 2 frames: 1 byte then 1 byte (END_STREAM).
    var f1 = (HTTP2Frame.DataFrame) queue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
    assertNotNull(f1, "First DATA frame should arrive within 2 seconds");
    assertEquals(f1.data().length, 1, "First frame: 1 byte (window=1)");
    assertEquals(f1.flags(), 0, "First frame: no END_STREAM");
    assertEquals(f1.data()[0], (byte) 'o');

    var f2 = (HTTP2Frame.DataFrame) queue.poll(2, java.util.concurrent.TimeUnit.SECONDS);
    assertNotNull(f2, "Second DATA frame should arrive after WINDOW_UPDATE");
    assertEquals(f2.data().length, 1, "Second frame: 1 byte (remaining)");
    assertEquals(f2.flags(), HTTP2Frame.FLAG_END_STREAM, "Second frame: END_STREAM");
    assertEquals(f2.data()[0], (byte) 'k');
  }
}

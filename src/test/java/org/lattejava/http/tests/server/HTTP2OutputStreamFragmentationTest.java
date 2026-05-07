/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2Frame;
import org.lattejava.http.server.internal.HTTP2OutputStream;
import org.lattejava.http.server.internal.HTTP2Stream;

import static org.testng.Assert.*;

public class HTTP2OutputStreamFragmentationTest {
  @Test
  public void empty_close_emits_zero_length_end_stream() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>();
    var stream = new HTTP2Stream(1, 65535, 65535);
    var os = new HTTP2OutputStream(stream, queue, 16384);

    os.close();

    var f = (HTTP2Frame.DataFrame) queue.take();
    assertEquals(f.payload().length, 0);
    assertEquals(f.flags(), HTTP2Frame.FLAG_END_STREAM);
  }

  @Test
  public void large_write_fragments_against_max_frame_size() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>();
    var stream = new HTTP2Stream(1, 65535, 65535);
    var os = new HTTP2OutputStream(stream, queue, 16); // tiny max-frame-size for test

    byte[] data = new byte[40];
    for (int i = 0; i < 40; i++) data[i] = (byte) i;
    os.write(data);
    os.close();

    // Expect 3 frames: 16, 16, 8 (last with END_STREAM)
    var f1 = (HTTP2Frame.DataFrame) queue.take();
    assertEquals(f1.payload().length, 16);
    assertEquals(f1.flags(), 0);

    var f2 = (HTTP2Frame.DataFrame) queue.take();
    assertEquals(f2.payload().length, 16);
    assertEquals(f2.flags(), 0);

    var f3 = (HTTP2Frame.DataFrame) queue.take();
    assertEquals(f3.payload().length, 8);
    assertEquals(f3.flags(), HTTP2Frame.FLAG_END_STREAM);
  }

  @Test
  public void single_write_no_fragmentation() throws Exception {
    var queue = new LinkedBlockingQueue<HTTP2Frame>();
    var stream = new HTTP2Stream(1, 65535, 65535);
    var os = new HTTP2OutputStream(stream, queue, 16384);

    os.write("hello".getBytes());
    os.close();

    var f1 = (HTTP2Frame.DataFrame) queue.take();
    assertEquals(f1.payload(), "hello".getBytes());
    assertEquals(f1.flags(), HTTP2Frame.FLAG_END_STREAM, "Final frame has END_STREAM");
  }
}

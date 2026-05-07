/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * Per-stream input. Backed by an ArrayBlockingQueue<byte[]> filled by the connection reader thread. A zero-length byte[] is the EOF sentinel.
 *
 * @author Daniel DeGroff
 */
public class HTTP2InputStream extends InputStream {
  private static final byte[] EOF_SENTINEL = new byte[0];

  private final BlockingQueue<byte[]> queue;

  private byte[] current;
  private int currentPos;
  private boolean eof;

  public HTTP2InputStream(BlockingQueue<byte[]> queue) {
    this.queue = queue;
  }

  public static byte[] eofSentinel() { return EOF_SENTINEL; }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    int n = read(one, 0, 1);
    return n == -1 ? -1 : one[0] & 0xFF;
  }

  @Override
  public int read(byte[] dst, int off, int len) throws IOException {
    if (eof) return -1;
    if (current == null || currentPos >= current.length) {
      try {
        current = queue.take();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }
      currentPos = 0;
      if (current.length == 0) {
        eof = true;
        return -1;
      }
    }
    int copy = Math.min(len, current.length - currentPos);
    System.arraycopy(current, currentPos, dst, off, copy);
    currentPos += copy;
    return copy;
  }
}

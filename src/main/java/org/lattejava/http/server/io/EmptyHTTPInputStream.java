/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.io;

import module java.base;

/**
 * Zero-allocation singleton {@link InputStream} for requests known to carry no body.
 *
 * <p>Avoids the JDK default {@link InputStream#readAllBytes()} / {@link InputStream#readNBytes(int)} behaviour of
 * allocating a 16 KB scratch buffer just to discover EOF — which is pure waste for the GET, HEAD and END_STREAM-on-HEADERS
 * cases that dominate real-world HTTP traffic. Use {@link #INSTANCE} as the request's input stream when the protocol layer
 * has already determined that no body bytes will arrive.
 *
 * @author Daniel DeGroff
 */
public final class EmptyHTTPInputStream extends InputStream {
  public static final EmptyHTTPInputStream INSTANCE = new EmptyHTTPInputStream();

  private static final byte[] EMPTY = new byte[0];

  private EmptyHTTPInputStream() {
  }

  @Override
  public int available() {
    return 0;
  }

  @Override
  public void close() {
  }

  @Override
  public int read() {
    return -1;
  }

  @Override
  public int read(byte[] b, int off, int len) {
    Objects.checkFromIndexSize(off, len, b.length);
    return len == 0 ? 0 : -1;
  }

  @Override
  public byte[] readAllBytes() {
    return EMPTY;
  }

  @Override
  public int readNBytes(byte[] b, int off, int len) {
    Objects.checkFromIndexSize(off, len, b.length);
    return 0;
  }

  @Override
  public byte[] readNBytes(int len) {
    if (len < 0) {
      throw new IllegalArgumentException("len < 0");
    }
    return EMPTY;
  }

  @Override
  public long skip(long n) {
    return 0L;
  }

  @Override
  public void skipNBytes(long n) throws EOFException {
    if (n > 0) {
      throw new EOFException();
    }
  }

  @Override
  public long transferTo(OutputStream out) {
    return 0L;
  }
}

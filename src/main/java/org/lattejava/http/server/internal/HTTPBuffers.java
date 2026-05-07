/*
 * Copyright (c) 2024-2025, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.lattejava.http.server.internal;

import module org.lattejava.http;

/**
 * A class that lazily creates and caches the buffers for a single worker thread. This is only used by the worker
 * thread, so no synchronization is required.
 *
 * @author Brian Pontarelli
 */
public class HTTPBuffers {
  // RFC 9113 §6.5.2: default max frame size and absolute ceiling.
  private static final int DEFAULT_FRAME_BUFFER_SIZE = 16384;
  private static final int MAX_FRAME_BUFFER_SIZE = 16777215;

  private final HTTPServerConfiguration configuration;

  private final byte[] requestBuffer;

  private final byte[] responseBuffer;

  private byte[] chunkBuffer;

  private FastByteArrayOutputStream chunkOutputStream;

  private byte[] frameReadBuffer;

  private byte[] frameWriteBuffer;

  private FastByteArrayOutputStream headerAccumulationBuffer;

  public HTTPBuffers(HTTPServerConfiguration configuration) {
    this.configuration = configuration;
    this.requestBuffer = new byte[configuration.getRequestBufferSize()];

    int responseBufferSize = configuration.getResponseBufferSize();
    if (responseBufferSize > 0) {
      this.responseBuffer = new byte[responseBufferSize];
    } else {
      this.responseBuffer = null;
    }
  }

  /**
   * @return An output stream that can be used for chunking responses. This uses the configuration's
   *     {@link HTTPServerConfiguration#getMaxResponseChunkSize()} value plus 64 bytes of padding for the header and
   *     footer.  This is lazily created.
   */
  public FastByteArrayOutputStream chuckedOutputStream() {
    if (chunkOutputStream == null) {
      chunkOutputStream = new FastByteArrayOutputStream(configuration.getMaxResponseChunkSize() + 64, 64);
    }

    return chunkOutputStream;
  }

  /**
   * @return A byte array that can be used for chunking responses. This uses the configuration's
   *     {@link HTTPServerConfiguration#getMaxResponseChunkSize()} value for the size. This is lazily created.
   */
  public byte[] chunkBuffer() {
    if (chunkBuffer == null) {
      chunkBuffer = new byte[configuration.getMaxResponseChunkSize()];
    }

    return chunkBuffer;
  }

  /**
   * Ensures the frame read buffer has capacity for at least the given size. Grows the buffer if needed up to the
   * RFC 9113 ceiling of 16777215 bytes.
   *
   * @param size The required size in bytes.
   * @throws IllegalArgumentException if size exceeds the RFC 9113 ceiling.
   */
  public void ensureFrameReadCapacity(int size) {
    if (size > MAX_FRAME_BUFFER_SIZE) {
      throw new IllegalArgumentException("Frame size [" + size + "] exceeds RFC 9113 ceiling of [" + MAX_FRAME_BUFFER_SIZE + "]");
    }
    if (frameReadBuffer == null || frameReadBuffer.length < size) {
      frameReadBuffer = new byte[size];
    }
  }

  /**
   * Ensures the frame write buffer has capacity for the frame header plus the given payload size. Grows the buffer if
   * needed up to the RFC 9113 ceiling of 16777215 bytes (plus 9 bytes for the frame header).
   *
   * @param payloadSize The required payload size in bytes.
   * @throws IllegalArgumentException if payloadSize exceeds the RFC 9113 ceiling.
   */
  public void ensureFrameWriteCapacity(int payloadSize) {
    if (payloadSize > MAX_FRAME_BUFFER_SIZE) {
      throw new IllegalArgumentException("Frame size [" + payloadSize + "] exceeds RFC 9113 ceiling of [" + MAX_FRAME_BUFFER_SIZE + "]");
    }
    int needed = 9 + payloadSize;
    if (frameWriteBuffer == null || frameWriteBuffer.length < needed) {
      frameWriteBuffer = new byte[needed];
    }
  }

  /**
   * @return A byte array that can be used for reading HTTP/2 frames. This uses the RFC 9113 default
   *     {@link #DEFAULT_FRAME_BUFFER_SIZE} (16384) and grows on demand up to the peer-negotiated cap. This is lazily
   *     created.
   */
  public byte[] frameReadBuffer() {
    if (frameReadBuffer == null) {
      frameReadBuffer = new byte[DEFAULT_FRAME_BUFFER_SIZE];
    }
    return frameReadBuffer;
  }

  /**
   * @return A byte array that can be used for writing HTTP/2 frames. This uses the RFC 9113 default
   *     {@link #DEFAULT_FRAME_BUFFER_SIZE} (16384) plus 9 bytes for the frame header, and grows on demand up to the
   *     peer-negotiated cap. This is lazily created.
   */
  public byte[] frameWriteBuffer() {
    if (frameWriteBuffer == null) {
      frameWriteBuffer = new byte[9 + DEFAULT_FRAME_BUFFER_SIZE];
    }
    return frameWriteBuffer;
  }

  /**
   * @return An output stream that can be used for accumulating HTTP/2 headers. This uses an initial capacity of 8192
   *     bytes with a 8192-byte growth increment. This is lazily created.
   */
  public FastByteArrayOutputStream headerAccumulationBuffer() {
    if (headerAccumulationBuffer == null) {
      headerAccumulationBuffer = new FastByteArrayOutputStream(8192, 8192);
    }
    return headerAccumulationBuffer;
  }

  /**
   * @return A byte array used to read the request preamble and body. This uses the configuration's
   *     {@link HTTPServerConfiguration#getRequestBufferSize()} value for the size. It is created in the constructor
   *     since it is always needed.
   */
  public byte[] requestBuffer() {
    return requestBuffer;
  }

  /**
   * @return A byte array used to buffer the response such that the server can replace the response with an error
   *     response if an error occurs during processing, but after the preamble and body has already been partially
   *     written. May be null if the response buffer has been disabled.
   */
  public byte[] responseBuffer() {
    return responseBuffer;
  }
}

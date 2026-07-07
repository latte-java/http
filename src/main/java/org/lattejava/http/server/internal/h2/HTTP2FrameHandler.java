/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;

/**
 * A dispatch target for inbound HTTP/2 frames. Implemented by {@code HTTP2ConnectionFrameHandler} (stream-0 frames)
 * and {@code HTTP2Stream} (everything else). Implementations never emit error frames themselves — they report the
 * outcome as an {@link HTTP2Result} and the connection dispatch loop emits.
 */
public interface HTTP2FrameHandler {
  HTTP2Result handleFrame(HTTP2Frame frame) throws IOException;
}

/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

/**
 * Per-connection stream-frame collaborators, shared by every stream the registry materializes.
 */
public record HTTP2StreamFrameHandlers(
    HTTP2DataFrameHandler dataHandler,
    HTTP2HeaderFrameHandler headerHandler,
    HTTP2RateLimitsTracker rateLimits,
    HTTP2RSTStreamFrameHandler rstStreamHandler,
    HTTP2WindowUpdateFrameHandler windowUpdateHandler
) {
}

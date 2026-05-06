/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server;

import java.io.IOException;
import java.net.Socket;

/**
 * Invoked by the worker after a successful 101 Switching Protocols response has been written and flushed. The handler
 * owns the underlying socket from this point — the worker will exit its keep-alive loop after the handler returns. h2c
 * Upgrade is the first consumer; future WebSockets work will be the second.
 *
 * @author Daniel DeGroff
 */
@FunctionalInterface
public interface ProtocolSwitchHandler {
  void handle(Socket socket) throws IOException;
}

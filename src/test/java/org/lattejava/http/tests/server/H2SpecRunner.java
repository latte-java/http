/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;

/**
 * Standalone h2c server for ad-hoc h2spec runs. Boot via:
 *   latte build && java -cp build/classes/main:build/classes/test org.lattejava.http.tests.server.H2SpecRunner [port]
 * Then in another shell: build/h2spec -h 127.0.0.1 -p &lt;port&gt; --strict generic/1
 *
 * @author Daniel DeGroff
 */
public class H2SpecRunner {
  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 0;

    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try (var os = res.getOutputStream()) {
        os.write("ok".getBytes());
      }
    };

    var listener = new HTTPListenerConfiguration(port).withH2cPriorKnowledgeEnabled(true);
    var server = new HTTPServer().withHandler(handler).withListener(listener);
    server.start();

    int actualPort = server.getActualPort();
    System.out.println("h2spec runner listening on port " + actualPort);
    // Print port on a recognizable line so the shell can grep it.
    System.out.println("PORT=" + actualPort);

    // Wait for SIGINT.
    Thread.currentThread().join();
  }
}

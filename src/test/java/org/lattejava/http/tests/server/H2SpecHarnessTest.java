/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import java.nio.file.Files;

import static org.testng.Assert.*;

/**
 * Boots a minimal h2c server on a random port and runs h2spec against it. Marked with the "h2spec" group so it can be
 * excluded from the normal {@code latte test} run; included by the {@code int-h2spec} target.
 *
 * @author Daniel DeGroff
 */
public class H2SpecHarnessTest extends BaseTest {
  private static final Path H2SPEC_BIN = Path.of("build/h2spec");

  @Test(groups = "h2spec")
  public void run_h2spec() throws Exception {
    if (!Files.isExecutable(H2SPEC_BIN)) {
      throw new SkipException("h2spec not installed at [" + H2SPEC_BIN + "] — run tools/install-h2spec.sh");
    }

    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      try (var os = res.getOutputStream()) {
        os.write("ok".getBytes());
      }
    };

    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    try (var server = makeServer("http", handler, listener).start()) {
      int port = server.getActualPort();
      var pb = new ProcessBuilder(
          H2SPEC_BIN.toString(),
          "-h", "127.0.0.1",
          "-p", String.valueOf(port),
          "--strict",
          "--junit-report", "build/h2spec-report.xml"
      );
      pb.redirectErrorStream(true);
      Process p = pb.start();
      String output = new String(p.getInputStream().readAllBytes());
      int exit = p.waitFor();

      System.out.println(output);

      if (exit != 0) {
        // The JUnit report at build/h2spec-report.xml lists the specific failures.
        fail("h2spec reported failures (exit=" + exit + "). See build/h2spec-report.xml. Output above.");
      }
    }
  }
}

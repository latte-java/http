/*
 * Copyright (c) 2026 The Latte Project
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

  // Known failures as of 2026-05-21 — see docs/design/2026-05-05-HTTP2.md §"Bug ledger". Pinning the exact set so that:
  //   - Any drift (a new failure or a fixed test that newly passes) breaks this assertion and forces an
  //     intentional update to both this set AND 2026-05-05-HTTP2.md's bug ledger.
  //   - Adversarial regressions on tests we currently pass also fail loudly.
  // Each entry is the section name as it appears in h2spec's JUnit report. Pattern is "<section> / <description>".
  // Specific descriptions can be found in build/h2spec-report.xml after a real run.
  private static final Set<String> KNOWN_FAILING_SECTIONS = Set.of(
      "6.5.3", // Settings Synchronization
      "6.9.1", // The Flow-Control Window (mid-stream window=1)
      "6.9.2"  // Initial Flow-Control Window Size (mid-stream INITIAL_WINDOW_SIZE decrease)
  );

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
      Path reportPath = Path.of("build/h2spec-report.xml");
      var pb = new ProcessBuilder(
          H2SPEC_BIN.toString(),
          "-h", "127.0.0.1",
          "-p", String.valueOf(port),
          "--strict",
          "--junit-report", reportPath.toString()
      );
      pb.redirectErrorStream(true);
      Process p = pb.start();
      String output = new String(p.getInputStream().readAllBytes());
      p.waitFor();

      Files.writeString(Path.of("build/h2spec-output.txt"), output);
      System.out.println(output);

      // Extract the section identifiers (e.g. "6.5.3") for every failing test from the JUnit report.
      Set<String> actualFailingSections = parseFailingSections(reportPath);
      assertEquals(actualFailingSections, KNOWN_FAILING_SECTIONS,
          "h2spec known-failure set drifted. Update KNOWN_FAILING_SECTIONS in this test AND the bug ledger in docs/design/2026-05-05-HTTP2.md. " +
              "Expected [" + KNOWN_FAILING_SECTIONS + "], actual [" + actualFailingSections + "]. " +
              "Full report at [" + reportPath + "].");
    }
  }

  /**
   * Parses h2spec's JUnit XML and returns the set of failing section identifiers (e.g. "6.5.3"). The h2spec JUnit
   * report puts each section name in the {@code <testsuite name="...">} element and individual test cases under that
   * suite with optional {@code <failure>} children. We collect the section names that have any failing case.
   */
  private static Set<String> parseFailingSections(Path reportPath) throws IOException {
    if (!Files.exists(reportPath)) {
      return Set.of();
    }
    String xml = Files.readString(reportPath);
    Set<String> failingSections = new TreeSet<>();
    // h2spec output groups testcases under <testsuite name="..."> blocks. A failing testcase contains a <failure ...
    // element. We pair each <testsuite> with whether any subsequent testcase before the next </testsuite> has a
    // <failure>. The section identifier we record is the leading "x.y.z" prefix of the suite name.
    Matcher suiteMatcher = Pattern.compile("<testsuite\\s[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</testsuite>", Pattern.DOTALL).matcher(xml);
    while (suiteMatcher.find()) {
      String name = suiteMatcher.group(1);
      String body = suiteMatcher.group(2);
      if (!body.contains("<failure")) {
        continue;
      }
      // Extract leading section number (e.g. "6.5.3" from "6.5.3. Settings Synchronization").
      Matcher sectionMatcher = Pattern.compile("^(\\d+(?:\\.\\d+)*)").matcher(name);
      if (sectionMatcher.find()) {
        failingSections.add(sectionMatcher.group(1));
      } else {
        failingSections.add(name);
      }
    }
    return failingSections;
  }
}

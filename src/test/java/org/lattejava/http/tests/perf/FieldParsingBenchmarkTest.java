/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.perf;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import java.nio.file.Files;

import org.lattejava.http.io.PushbackInputStream;

/**
 * Hand-rolled relative micro-benchmark for the three HTTP/1.1 field-block parse paths. Not a pass/fail gate — it prints
 * {@code ns/op} so a human can compare a run before a change against a run after it. Tagged {@code performance} so the
 * normal CI build (which passes {@code --excludePerformance}) skips it.
 */
public class FieldParsingBenchmarkTest {
  private static final int Iterations = 2_000_000;
  private static final int Warmup = 200_000;

  @Test(groups = "performance")
  public void benchmark() throws Exception {
    benchPreamble();
    benchMultipartHeaders();
    benchTrailers();
  }

  private void benchPreamble() throws Exception {
    byte[] preamble = ("GET /some/representative/path?with=query HTTP/1.1\r\n" +
        "Host: example.org\r\n" +
        "User-Agent: latte-bench/1.0\r\n" +
        "Accept: text/html,application/xhtml+xml\r\n" +
        "Accept-Encoding: gzip, deflate, br\r\n" +
        "Accept-Language: en-US,en;q=0.9\r\n" +
        "Connection: keep-alive\r\n" +
        "Cache-Control: no-cache\r\n" +
        "Cookie: session=abcdef0123456789; theme=dark\r\n" +
        "\r\n").getBytes(StandardCharsets.US_ASCII);
    byte[] requestBuffer = new byte[4096];
    long sink = 0;

    for (int i = 0; i < Warmup; i++) {
      sink += parseOnce(preamble, requestBuffer);
    }

    long start = System.nanoTime();
    for (int i = 0; i < Iterations; i++) {
      sink += parseOnce(preamble, requestBuffer);
    }
    long elapsed = System.nanoTime() - start;
    report("preamble", elapsed, sink);
  }

  private long parseOnce(byte[] preamble, byte[] requestBuffer) throws Exception {
    var in = new PushbackInputStream(new ByteArrayInputStream(preamble), null);
    var request = new HTTPRequest();
    HTTPTools.parseRequestPreamble(in, -1, request, requestBuffer, () -> {
    });
    String ua = request.getHeader("user-agent");
    return request.getPath().length() + (ua == null ? 0 : ua.length());
  }

  private void benchMultipartHeaders() throws Exception {
    String boundary = "----LatteBenchBoundary";
    StringBuilder body = new StringBuilder();
    for (int p = 0; p < 8; p++) {
      body.append("--").append(boundary).append("\r\n")
          .append("Content-Disposition: form-data; name=\"field").append(p).append("\"\r\n")
          .append("Content-Type: text/plain; charset=UTF-8\r\n")
          .append("X-Custom-Header: some-value-").append(p).append("\r\n")
          .append("\r\n")
          .append("value-").append(p).append("\r\n");
    }
    body.append("--").append(boundary).append("--\r\n");
    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
    Path tempDir = Files.createTempDirectory("latte-bench");
    var config = new MultipartConfiguration();
    var fileManager = new DefaultMultipartFileManager(tempDir, config.getTemporaryFilenamePrefix(), config.getTemporaryFilenameSuffix());
    long sink = 0;

    for (int i = 0; i < Warmup; i++) {
      sink += multipartOnce(bytes, boundary, fileManager, config);
    }

    long start = System.nanoTime();
    for (int i = 0; i < Iterations; i++) {
      sink += multipartOnce(bytes, boundary, fileManager, config);
    }
    long elapsed = System.nanoTime() - start;
    report("multipart-headers", elapsed, sink);
  }

  private long multipartOnce(byte[] bytes, String boundary, MultipartFileManager fileManager, MultipartConfiguration config) throws Exception {
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new ArrayList<>();
    new MultipartStream(new ByteArrayInputStream(bytes), boundary.getBytes(StandardCharsets.UTF_8), fileManager, config)
        .process(parameters, files);
    return parameters.size();
  }

  private void benchTrailers() throws Exception {
    byte[] wire = ("5\r\nhello\r\n" +
        "0\r\n" +
        "X-Checksum: abc123def456\r\n" +
        "X-Trace-Id: 0123456789abcdef\r\n" +
        "X-Span-Id: fedcba9876543210\r\n" +
        "\r\n").getBytes(StandardCharsets.US_ASCII);
    long sink = 0;

    for (int i = 0; i < Warmup; i++) {
      sink += trailersOnce(wire);
    }

    long start = System.nanoTime();
    for (int i = 0; i < Iterations; i++) {
      sink += trailersOnce(wire);
    }
    long elapsed = System.nanoTime() - start;
    report("trailers", elapsed, sink);
  }

  private long trailersOnce(byte[] wire) throws Exception {
    var pushback = new PushbackInputStream(new ByteArrayInputStream(wire), null);
    var chunked = new ChunkedInputStream(pushback, 1024, 1_000_000);
    chunked.readAllBytes();
    return chunked.getTrailers().size();
  }

  private void report(String label, long elapsedNanos, long sink) {
    double nsPerOp = elapsedNanos / (double) Iterations;
    System.out.printf("[bench] %-18s %8.2f ns/op  (sink=%d)%n", label, nsPerOp, sink);
  }
}

/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;

import java.time.Duration;

import org.lattejava.http.io.MultipartConfiguration;
import org.lattejava.http.io.MultipartFileUploadPolicy;
import org.lattejava.http.server.HTTPListenerConfiguration;
import org.lattejava.http.server.HTTPServer;
import org.lattejava.http.server.HTTPServerConfiguration;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

public class HTTPServerConfigurationValidationTest {
  @Test
  public void keepAliveTimeout_defaults_to_20_seconds() {
    assertEquals(new HTTPServerConfiguration().getKeepAliveTimeoutDuration(), Duration.ofSeconds(20));
  }

  @Test
  public void keepAliveTimeout_rejects_null() {
    expectThrows(NullPointerException.class, () -> new HTTPServerConfiguration().withKeepAliveTimeoutDuration(null));
  }

  @Test
  public void keepAliveTimeout_rejects_zero() {
    expectThrows(IllegalArgumentException.class, () -> new HTTPServerConfiguration().withKeepAliveTimeoutDuration(Duration.ZERO));
  }

  @Test
  public void keepAliveTimeout_round_trips() {
    assertEquals(new HTTPServerConfiguration().withKeepAliveTimeoutDuration(Duration.ofSeconds(30)).getKeepAliveTimeoutDuration(), Duration.ofSeconds(30));
  }

  @Test
  public void maxConnectionAge_defaults_to_null() {
    assertNull(new HTTPServerConfiguration().getMaxConnectionAgeDuration());
  }

  @Test
  public void maxConnectionAge_rejects_zero() {
    expectThrows(IllegalArgumentException.class, () -> new HTTPServerConfiguration().withMaxConnectionAgeDuration(Duration.ZERO));
  }

  @Test
  public void maxConnectionAge_round_trips() {
    assertEquals(new HTTPServerConfiguration().withMaxConnectionAgeDuration(Duration.ofMinutes(5)).getMaxConnectionAgeDuration(), Duration.ofMinutes(5));
  }

  @Test
  public void maxRequestsPerConnection_defaults_to_100_000() {
    assertEquals(new HTTPServerConfiguration().getMaxRequestsPerConnection(), 100_000);
  }

  @Test
  public void maxRequestsPerConnection_rejects_small_value() {
    expectThrows(IllegalArgumentException.class, () -> new HTTPServerConfiguration().withMaxRequestsPerConnection(9));
  }

  @Test
  public void maxRequestsPerConnection_round_trips() {
    assertEquals(new HTTPServerConfiguration().withMaxRequestsPerConnection(50_000).getMaxRequestsPerConnection(), 50_000);
  }

  @Test
  public void start_skips_validation_when_file_uploads_rejected() throws Exception {
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("*", 1L * 1024 * 1024))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Reject)
            .withMaxFileSize(5 * 1024 * 1024));

    try (HTTPServer ignored = server.start()) {
      // Successful start.
    }
  }

  @Test
  public void start_skips_validation_when_maxFileSize_is_unlimited() throws Exception {
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("*", -1L))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
            .withMaxFileSize(5L * 1024 * 1024 * 1024));

    try (HTTPServer ignored = server.start()) {
      // Successful start.
    }
  }

  @Test
  public void start_succeeds_when_maxFileSize_within_effective_maxRequestBodySize() throws Exception {
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("multipart/form-data", 10L * 1024 * 1024))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
            .withMaxFileSize(5 * 1024 * 1024));

    try (HTTPServer ignored = server.start()) {
      // Successful start.
    }
  }

  @Test
  public void start_throws_when_maxFileSize_exceeds_effective_maxRequestBodySize() {
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("multipart/form-data", 1L * 1024 * 1024))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
            .withMaxFileSize(5 * 1024 * 1024));

    IllegalStateException ex = expectThrows(IllegalStateException.class, server::start);
    String message = ex.getMessage();
    assertTrue(message.contains("maxFileSize"), "Expected message to mention maxFileSize, got: " + message);
    assertTrue(message.contains("multipart/form-data"), "Expected message to mention multipart/form-data, got: " + message);
  }

  @Test
  public void start_uses_wildcard_when_no_exact_multipart_match() {
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("*", 1L * 1024 * 1024))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
            .withMaxFileSize(5 * 1024 * 1024));

    IllegalStateException ex = expectThrows(IllegalStateException.class, server::start);
    assertTrue(ex.getMessage().contains("maxFileSize"));
  }

  @Test
  public void start_skips_validation_when_exact_multipart_key_is_unlimited() throws Exception {
    // The exact "multipart/form-data" key is unlimited (-1); the wildcard "*" is finite.
    // Exact-key match wins over the wildcard fallback, so the validator must see -1 and skip the check
    // even though maxFileSize would exceed the wildcard cap.
    HTTPServer server = new HTTPServer()
        .withHandler((req, res) -> {})
        .withListener(new HTTPListenerConfiguration(0))
        .withMaxRequestBodySize(Map.of("multipart/form-data", -1L, "*", 1L * 1024 * 1024))
        .withMultipartConfiguration(new MultipartConfiguration()
            .withFileUploadPolicy(MultipartFileUploadPolicy.Allow)
            .withMaxFileSize(5L * 1024 * 1024));

    try (HTTPServer ignored = server.start()) {
      // Successful start — exact key overrides the more restrictive wildcard.
    }
  }
}

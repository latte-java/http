/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.util;

/**
 * Receives one parsed HTTP field (header, trailer, or multipart part header) from {@link HTTPFieldParser}. The parser
 * stays policy-neutral; each caller's lowercasing, trimming, and filtering live in its consumer.
 *
 * @author Brian Pontarelli
 */
@FunctionalInterface
public interface FieldConsumer {
  /**
   * Accepts a completed field.
   *
   * @param name  The field name, exactly as received (not lowercased).
   * @param value The field value, with optional leading whitespace after the colon already stripped.
   */
  void field(String name, String value);
}

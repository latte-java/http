/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import static org.testng.Assert.*;

public class HTTPListenerConfigurationHTTP2Test {
  @Test
  public void defaults() {
    var c = new HTTPListenerConfiguration(80);
    assertTrue(c.isHTTP2Enabled());
    assertFalse(c.isH2cPriorKnowledgeEnabled());
    assertFalse(c.isH2cUpgradeEnabled());
  }

  @Test
  public void withers_set_flags() {
    var c = new HTTPListenerConfiguration(80)
        .withHTTP2Enabled(false)
        .withH2cPriorKnowledgeEnabled(true)
        .withH2cUpgradeEnabled(false);
    assertFalse(c.isHTTP2Enabled());
    assertTrue(c.isH2cPriorKnowledgeEnabled());
    assertFalse(c.isH2cUpgradeEnabled());
  }
}

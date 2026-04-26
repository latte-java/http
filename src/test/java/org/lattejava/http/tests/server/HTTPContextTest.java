/*
 * Copyright (c) 2026, FusionAuth, All Rights Reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */
package org.lattejava.http.tests.server;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.lattejava.http.server.HTTPContext;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.*;

/**
 * Tests for HTTPContext focusing on path traversal security and resource resolution.
 * <p>
 * These tests verify that HTTPContext properly prevents path traversal attacks as described in: - CVE-2019-19781
 * (Citrix path traversal) - Blog post: https://blog.dochia.dev/blog/http_edge_cases/
 *
 * @author Dan Moore
 */
public class HTTPContextTest {
  private HTTPContext context;
  private Path tempDir;

  @BeforeMethod
  public void setup() throws IOException {
    // Create a temporary directory structure for testing
    tempDir = Files.createTempDirectory("http-context-test");

    // Create legitimate test files
    Files.writeString(tempDir.resolve("index.html"), "<html>Index</html>");

    Path cssDir = Files.createDirectory(tempDir.resolve("css"));
    Files.writeString(cssDir.resolve("style.css"), "body { color: blue; }");

    Path subDir = Files.createDirectory(tempDir.resolve("subdir"));
    Files.writeString(subDir.resolve("file.txt"), "Legitimate file");

    // Create file outside the baseDir to test traversal attempts
    Path parentDir = tempDir.getParent();
    Files.writeString(parentDir.resolve("secret.txt"), "Secret data");

    context = new HTTPContext(tempDir);
  }

  @AfterMethod
  public void teardown() throws IOException {
    // Cleanup temp files
    if (tempDir != null && Files.exists(tempDir)) {
      Files.walk(tempDir)
           .sorted(Comparator.reverseOrder()) // Delete files before directories
           .forEach(path -> {
             try {
               Files.deleteIfExists(path);
             } catch (IOException e) {
               // Ignore cleanup errors
             }
           });
    }

    // Cleanup secret file from parent
    Path secretFile = tempDir.getParent().resolve("secret.txt");
    Files.deleteIfExists(secretFile);
  }

  /**
   * Test attribute storage (not security related, but completeness).
   */
  @Test
  public void testAttributeStorage() {
    context.setAttribute("test", "value");
    assertEquals(context.getAttribute("test"), "value");

    context.setAttribute("number", 42);
    assertEquals(context.getAttribute("number"), 42);

    Object removed = context.removeAttribute("test");
    assertEquals(removed, "value");
    assertNull(context.getAttribute("test"));
  }

  /**
   * Test that legitimate file paths work correctly.
   */
  @Test
  public void testLegitimatePathsSucceed() {
    // Test root level file
    URL indexUrl = context.getResource("index.html");
    assertNotNull(indexUrl, "Should resolve index.html");
    assertTrue(indexUrl.toString().contains("index.html"));

    // Test subdirectory file
    URL cssUrl = context.getResource("css/style.css");
    assertNotNull(cssUrl, "Should resolve css/style.css");
    assertTrue(cssUrl.toString().contains("style.css"));

    // Test with leading slash (should be stripped)
    URL slashUrl = context.getResource("/css/style.css");
    assertNotNull(slashUrl, "Should resolve /css/style.css");
    assertTrue(slashUrl.toString().contains("style.css"));

    // Test nested path
    URL subdirUrl = context.getResource("subdir/file.txt");
    assertNotNull(subdirUrl, "Should resolve subdir/file.txt");
    assertTrue(subdirUrl.toString().contains("file.txt"));
  }

  /**
   * Test that non-existent files return null (not exceptions).
   */
  @Test
  public void testNonExistentFileReturnsNull() {
    URL result = context.getResource("does-not-exist.txt");
    assertNull(result);
  }

  /**
   * Test edge case: path that goes down then up but stays within baseDir. For example: "subdir/../index.html" should
   * resolve to "index.html"
   */
  @Test
  public void testNormalizedPathWithinBaseDirSucceeds() {
    // This path traverses up but stays within baseDir after normalization
    URL result = context.getResource("subdir/../index.html");
    assertNotNull(result, "Should allow subdir/../index.html as it normalizes to index.html");
    assertTrue(result.toString().contains("index.html"));

    Path resolved = context.resolve("subdir/../index.html");
    assertNotNull(resolved, "Should resolve subdir/../index.html");
    assertEquals(resolved, tempDir.resolve("index.html"));
  }

  /**
   * Test path traversal attack using ../ sequences (CVE-2019-19781 style). These attacks attempt to escape the baseDir
   * and access parent directories.
   */
  @Test
  public void testPathTraversalAttacksBlocked() {
    // Simple parent directory traversal
    URL result1 = context.getResource("../secret.txt");
    assertNull(result1, "Should block ../secret.txt");

    // Multiple parent traversals
    URL result2 = context.getResource("../../etc/passwd");
    assertNull(result2, "Should block ../../etc/passwd");

    // Traversal with valid path prefix
    URL result3 = context.getResource("css/../../secret.txt");
    assertNull(result3, "Should block css/../../secret.txt");

    // Deep traversal
    URL result4 = context.getResource("subdir/../../secret.txt");
    assertNull(result4, "Should block subdir/../../secret.txt");

    // Many parent directory references
    URL result5 = context.getResource("../../../../../../../../../etc/passwd");
    assertNull(result5, "Should block ../../../../../../../../../etc/passwd");
  }

  /**
   * Test that resolve() works correctly for legitimate paths.
   */
  @Test
  public void testResolveLegitimatePathsSucceed() {
    // Test root level file
    Path indexPath = context.resolve("index.html");
    assertNotNull(indexPath, "Should resolve index.html");
    assertEquals(indexPath, tempDir.resolve("index.html"));

    // Test subdirectory file
    Path cssPath = context.resolve("css/style.css");
    assertNotNull(cssPath, "Should resolve css/style.css");
    assertEquals(cssPath, tempDir.resolve("css/style.css"));

    // Test with leading slash
    Path slashPath = context.resolve("/css/style.css");
    assertNotNull(slashPath, "Should resolve /css/style.css");
    assertEquals(slashPath, tempDir.resolve("css/style.css"));
  }

  /**
   * Test that resolve() method also prevents path traversal.
   */
  @Test
  public void testResolvePathTraversalBlocked() {
    // Simple parent directory traversal
    Path result1 = context.resolve("../secret.txt");
    assertNull(result1, "Should block ../secret.txt in resolve()");

    // Multiple parent traversals
    Path result2 = context.resolve("../../etc/passwd");
    assertNull(result2, "Should block ../../etc/passwd in resolve()");

    // Traversal with valid path prefix
    Path result3 = context.resolve("css/../../secret.txt");
    assertNull(result3, "Should block css/../../secret.txt in resolve()");
  }

  /**
   * Test URL-encoded path traversal attacks. Attackers often URL-encode the ../ sequences to bypass naive filters.
   */
  @Test
  public void testUrlEncodedTraversalBlocked() {
    // URL-encoded ../ is %2e%2e%2f
    URL result1 = context.getResource("%2e%2e%2fsecret.txt");
    assertNull(result1, "Should block URL-encoded traversal %2e%2e%2fsecret.txt");

    URL result2 = context.getResource("%2e%2e%2f%2e%2e%2fsecret.txt");
    assertNull(result2, "Should block %2e%2e%2f%2e%2e%2fsecret.txt");

    // Mixed encoded and plain
    URL result3 = context.getResource("css/%2e%2e%2f%2e%2e%2fsecret.txt");
    assertNull(result3, "Should block css/%2e%2e%2f%2e%2e%2fsecret.txt");
  }
}

/*
 * Copyright (c) 2022-2025, FusionAuth, All Rights Reserved
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
package org.lattejava.http.tests.io;

import module java.base;
import module org.lattejava.http;
import module org.testng;
import java.nio.file.Files;

import org.lattejava.http.ParseException;

import static org.testng.Assert.*;

/**
 * @author Brian Pontarelli
 */
public class MultipartStreamTest {
  private MultipartFileManager fileManager;

  @AfterTest
  public void afterTest() {
    for (var file : fileManager.getTemporaryFiles()) {
      try {
        Files.deleteIfExists(file);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
  }

  @DataProvider(name = "badBoundary")
  public Object[][] badBoundary() {
    return new Object[][]{
        {"""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar----WebKitFormBoundaryTWfMVJErBoLURJIe--"""},
        {"""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar------WebKitFormBoundaryTWfMVJErBoLURJIe--"""}
    };
  }

  @Test(dataProvider = "badBoundary", expectedExceptions = ParseException.class, expectedExceptionsMessageRegExp = "Invalid multipart body. Ran out of data while processing.")
  public void bad_boundaryParameter(String boundary) throws IOException {
    new MultipartStream(new ByteArrayInputStream(boundary.getBytes()), "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), fileManager, new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow))
        .process(new HashMap<>(), new LinkedList<>());
  }

  @BeforeTest
  public void beforeTest() {
    var multipartConfiguration = new MultipartConfiguration();
    Path tempDir = Paths.get(multipartConfiguration.getTemporaryFileLocation());
    fileManager = new DefaultMultipartFileManager(tempDir, multipartConfiguration.getTemporaryFilenamePrefix(), multipartConfiguration.getTemporaryFilenameSuffix());
  }

  @Test
  public void boundaryInParameter() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), fileManager, new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow));
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("bar------WebKitFormBoundaryTWfMVJErBoLURJIe"));
  }

  @Test
  public void file() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"; filename="foo.jpg"\r
        \r
        filecontents\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), fileManager, new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow));
    stream.process(parameters, files);

    assertEquals(files.size(), 1);
    assertEquals(files.getFirst().contentType(), "application/octet-stream");
    assertEquals(Files.readString(files.getFirst().file()), "filecontents");
    assertEquals(files.getFirst().fileName(), "foo.jpg");
    assertEquals(files.getFirst().name(), "foo");

    Files.delete(files.getFirst().file());
  }

  @Test
  public void mixed() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="file"; filename="foo.jpg"\r
        \r
        filecontents\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), fileManager, new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow));
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("bar"));

    assertEquals(files.size(), 1);
    assertEquals(files.getFirst().contentType(), "application/octet-stream");
    assertEquals(Files.readString(files.getFirst().file()), "filecontents");
    assertEquals(files.getFirst().fileName(), "foo.jpg");
    assertEquals(files.getFirst().name(), "file");

    Files.delete(files.getFirst().file());
  }

  @Test
  public void parameter() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), fileManager, new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow));
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("bar"));
  }

  @Test
  public void partialBoundaryInParameter() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        ------WebKitFormBoundaryTWfMVJErBoLURJI\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), fileManager, new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow));
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("------WebKitFormBoundaryTWfMVJErBoLURJI"));
  }

  @DataProvider(name = "parts")
  public Object[][] parts() {
    String body = """
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="file"; filename="foo.jpg"\r
        \r
        filecontents\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""";
    Object[][] invocations = new Object[body.length() - 1][];
    for (int i = 1; i < body.length(); i++) {
      invocations[i - 1] = new Object[]{i, new Parts(new byte[][]{body.substring(0, i).getBytes(), body.substring(i).getBytes()})};
    }
    return invocations;
  }

  @Test(dataProvider = "parts")
  public void separateParts(@SuppressWarnings("unused") int index, Parts parts) throws IOException {
    PartInputStream is = new PartInputStream(parts.parts);
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), fileManager, new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow));
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("bar"));

    assertEquals(files.size(), 1);
    assertEquals(files.getFirst().contentType(), "application/octet-stream");
    assertEquals(Files.readString(files.getFirst().file()), "filecontents");
    assertEquals(files.getFirst().fileName(), "foo.jpg");
    assertEquals(files.getFirst().name(), "file");

    Files.delete(files.getFirst().file());
  }

  /**
   * Regression for docs/security/audit-2026-04-20.md Vuln 5. The previous {@code start += end} arithmetic in
   * {@code MultipartStream.reload} overshot the real write offset whenever {@code InputStream.read} returned fewer
   * bytes than requested — a routine condition under TCP segmentation or slow/TLS clients. The loop then wrote
   * subsequent chunks into the wrong buffer positions, leaving uninitialized gaps that {@code findBoundary} would scan
   * as if they were real bytes, and eventually overrunning the buffer end with an {@code IndexOutOfBoundsException}.
   * This test drips the body one byte at a time, which forces {@code reload} to iterate enough times for the bug to
   * manifest; with the {@code start += read} fix it parses normally.
   */
  @Test
  public void trickling_one_byte_at_a_time() throws IOException {
    byte[] body = """
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="foo"\r
        \r
        bar\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        Content-Disposition: form-data; name="file"; filename="foo.jpg"\r
        \r
        filecontents\r
        ------WebKitFormBoundaryTWfMVJErBoLURJIe--""".getBytes();
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(new TricklingInputStream(body), "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), fileManager, new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow));
    stream.process(parameters, files);

    assertEquals(parameters.get("foo"), List.of("bar"));
    assertEquals(files.size(), 1);
    assertEquals(Files.readString(files.getFirst().file()), "filecontents");
    assertEquals(files.getFirst().fileName(), "foo.jpg");
    assertEquals(files.getFirst().name(), "file");
    Files.delete(files.getFirst().file());
  }

  @Test
  public void truncated() throws IOException {
    ByteArrayInputStream is = new ByteArrayInputStream("""
        ------WebKitFormBoundaryTWfMVJErBoLURJIe\r
        """.getBytes());
    Map<String, List<String>> parameters = new HashMap<>();
    List<FileInfo> files = new LinkedList<>();
    MultipartStream stream = new MultipartStream(is, "----WebKitFormBoundaryTWfMVJErBoLURJIe".getBytes(), fileManager, new MultipartConfiguration().withFileUploadPolicy(MultipartFileUploadPolicy.Allow));
    try {
      stream.process(parameters, files);
      fail("Expected to fail with a ParseException");

    } catch (ParseException e) {
      assertEquals(e.getMessage(), "Invalid multipart body. Ran out of data while processing.");
    }
  }

  public static class PartInputStream extends InputStream {
    private final byte[][] parts;

    private int index;

    private int partIndex;

    public PartInputStream(byte[]... parts) {
      this.parts = parts;
    }

    public int read(byte[] buffer, int start, int count) {
      if (index > parts.length) {
        return -1;
      }

      int copied = Math.min(count, parts[index].length - partIndex);
      System.arraycopy(parts[index], partIndex, buffer, start, copied);
      partIndex += copied;

      if (partIndex >= parts[index].length) {
        partIndex = 0;
        index++;
      }

      return copied;
    }

    @Override
    public int read() {
      throw new UnsupportedOperationException();
    }
  }

  public static class Parts {
    public byte[][] parts;

    public Parts(byte[][] parts) {
      this.parts = parts;
    }

    public String toString() {
      List<String> result = new ArrayList<>();
      for (byte[] part : parts) {
        result.add("" + part.length);
      }
      return "{" + String.join(",", result) + "}";
    }
  }

  /**
   * An {@link InputStream} that returns exactly one byte per {@code read} call. Models the worst-case behavior of a
   * slow / TLS / heavily segmented client and forces {@code MultipartStream.reload} to iterate once per byte.
   */
  public static class TricklingInputStream extends InputStream {
    private final byte[] source;

    private int index;

    public TricklingInputStream(byte[] source) {
      this.source = source;
    }

    @Override
    public int read() {
      if (index >= source.length) {
        return -1;
      }

      return source[index++] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      if (index >= source.length) {
        return -1;
      }
      if (length == 0) {
        return 0;
      }

      buffer[offset] = source[index++];
      return 1;
    }
  }
}

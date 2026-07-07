/*
 * Copyright (c) 2022-2026, FusionAuth, All Rights Reserved
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
package org.lattejava.http.benchmark;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

public class LoadServlet extends HttpServlet {
  private static final Map<Integer, byte[]> Blobs = new HashMap<>();

  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse res) {
    doPost(req, res);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse res) {
    // Note that this should be mostly the same between all load tests.
    // - See benchmarks/self
    switch (req.getPathInfo()) {
      case "/" -> handleNoOp(req, res);
      case "/no-read" -> handleNoRead(req, res);
      case "/hello" -> handleHello(req, res);
      case "/file" -> handleFile(req, res);
      case "/load" -> handleLoad(req, res);
      case "/compute" -> handleCompute(req, res);
      case "/io" -> handleIO(req, res);
      case "/large-response" -> handleLargeResponse(req, res);
      case "/stream" -> handleStream(req, res);
      default -> handleFailure(req, res);
    }
  }

  private void handleCompute(HttpServletRequest req, HttpServletResponse res) {
    int rounds = 5000;
    String roundsParam = req.getParameter("rounds");
    if (roundsParam != null) {
      rounds = Integer.parseInt(roundsParam);
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = new byte[32];
      for (int i = 0; i < rounds; i++) {
        hash = md.digest(hash);
      }
      byte[] body = HexFormat.of().formatHex(hash).getBytes(StandardCharsets.UTF_8);
      res.setStatus(200);
      res.setContentType("text/plain");
      res.setContentLength(body.length);
      res.getOutputStream().write(body);
    } catch (Exception e) {
      res.setStatus(500);
    }
  }

  private void handleFailure(HttpServletRequest req, HttpServletResponse res) {
    // Path does not match handler.
    res.setStatus(400);
    byte[] response = ("Invalid path [" + req.getPathInfo() + "]. Supported paths include [/, /no-read, /hello, /file, /load, /compute, /io, /stream].").getBytes(StandardCharsets.UTF_8);
    res.setContentLength(response.length);
    res.setContentType("text/plain");
    try {
      res.getOutputStream().write(response);
    } catch (IOException e) {
      res.setStatus(500);
    }
  }

  private void handleFile(HttpServletRequest req, HttpServletResponse res) {
    try (InputStream is = req.getInputStream()) {
      is.readAllBytes();

      int size = 1024 * 1024;
      var requestedSize = req.getParameter("size");
      if (requestedSize != null) {
        size = Integer.parseInt(requestedSize);
      }

      // Ensure we only build one file
      byte[] blob = Blobs.get(size);
      if (blob == null) {
        synchronized (Blobs) {
          blob = Blobs.get(size);
          if (blob == null) {
            System.out.println("Build file with size : " + size);
            String s = "Lorem ipsum dolor sit amet";
            String body = s.repeat((size + s.length() - 1) / s.length()).substring(0, size);
            assert body.length() == size;
            Blobs.put(size, body.getBytes(StandardCharsets.UTF_8));
            blob = Blobs.get(size);
            assert blob != null;
          }
        }
      }

      res.setStatus(200);
      res.setContentType("application/octet-stream");
      res.setContentLength(blob.length);

      try (OutputStream os = res.getOutputStream()) {
        os.write(blob);
      }
    } catch (Exception e) {
      res.setStatus(500);
    }
  }

  private void handleHello(HttpServletRequest req, HttpServletResponse res) {
    try (InputStream is = req.getInputStream()) {
      // Empty the InputStream
      is.readAllBytes();

      // Hello world
      res.setStatus(200);
      res.setContentType("text/plain");
      byte[] response = "Hello world".getBytes(StandardCharsets.UTF_8);
      res.setContentLength(response.length);

      try (OutputStream os = res.getOutputStream()) {
        os.write(response);
      }
    } catch (Exception e) {
      res.setStatus(500);
    }
  }

  private void handleIO(HttpServletRequest req, HttpServletResponse res) {
    int ms = 10;
    String msParam = req.getParameter("ms");
    if (msParam != null) {
      ms = Integer.parseInt(msParam);
    }
    try {
      Thread.sleep(ms);
      byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
      res.setStatus(200);
      res.setContentType("text/plain");
      res.setContentLength(body.length);
      res.getOutputStream().write(body);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      res.setStatus(500);
    } catch (IOException e) {
      res.setStatus(500);
    }
  }

  private void handleLargeResponse(HttpServletRequest req, HttpServletResponse res) {
    // Single-shot write; contrast with /stream which does per-chunk flush.
    int size = 131072;
    String sizeParam = req.getParameter("size");
    if (sizeParam != null) {
      size = Integer.parseInt(sizeParam);
    }

    byte[] blob = Blobs.get(size);
    if (blob == null) {
      synchronized (Blobs) {
        blob = Blobs.get(size);
        if (blob == null) {
          String s = "Lorem ipsum dolor sit amet";
          String body = s.repeat((size + s.length() - 1) / s.length()).substring(0, size);
          Blobs.put(size, body.getBytes(StandardCharsets.UTF_8));
          blob = Blobs.get(size);
        }
      }
    }

    res.setStatus(200);
    res.setContentType("application/octet-stream");
    res.setContentLength(blob.length);

    try (OutputStream os = res.getOutputStream()) {
      os.write(blob);
    } catch (IOException e) {
      res.setStatus(500);
    }
  }

  private void handleLoad(HttpServletRequest req, HttpServletResponse res) {
    // Note that this should be mostly the same between all load tests.
    // - See benchmarks/self
    try (InputStream is = req.getInputStream()) {
      byte[] body = is.readAllBytes();
      byte[] result = Base64.getEncoder().encode(body);
      res.setContentLength(result.length);
      res.setContentType("text/plain");
      res.setStatus(200);
      res.getOutputStream().write(result);
    } catch (Exception e) {
      res.setStatus(500);
    }
  }

  private void handleNoOp(HttpServletRequest req, HttpServletResponse res) {
    try (InputStream is = req.getInputStream()) {
      // Just read the bytes from the InputStream and return. Do no other worker.
      is.readAllBytes();
      res.setStatus(200);
    } catch (Exception e) {
      res.setStatus(500);
    }
  }

  @SuppressWarnings("unused")
  private void handleNoRead(HttpServletRequest req, HttpServletResponse res) {
    // Note that it is intentionally that we are not reading the InputStream. This will cause the server to have to drain it.
    res.setStatus(200);
  }

  private void handleStream(HttpServletRequest req, HttpServletResponse res) {
    int size = 131072;
    String sizeParam = req.getParameter("size");
    if (sizeParam != null) {
      size = Integer.parseInt(sizeParam);
    }

    byte[] blob = Blobs.get(size);
    if (blob == null) {
      synchronized (Blobs) {
        blob = Blobs.get(size);
        if (blob == null) {
          String s = "Lorem ipsum dolor sit amet";
          String body = s.repeat((size + s.length() - 1) / s.length()).substring(0, size);
          Blobs.put(size, body.getBytes(StandardCharsets.UTF_8));
          blob = Blobs.get(size);
        }
      }
    }

    res.setStatus(200);
    res.setContentType("application/octet-stream");
    res.setContentLength(blob.length);

    try (OutputStream os = res.getOutputStream()) {
      int chunkSize = 8192;
      for (int offset = 0; offset < blob.length; offset += chunkSize) {
        int len = Math.min(chunkSize, blob.length - offset);
        os.write(blob, offset, len);
        os.flush();
      }
    } catch (IOException e) {
      res.setStatus(500);
    }
  }
}

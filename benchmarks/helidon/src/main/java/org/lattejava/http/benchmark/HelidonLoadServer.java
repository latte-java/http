/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.benchmark;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import io.helidon.common.configurable.Resource;
import io.helidon.common.tls.Tls;
import io.helidon.webserver.WebServer;
import io.helidon.webserver.http.HttpRouting;
import io.helidon.webserver.http.ServerRequest;
import io.helidon.webserver.http.ServerResponse;

public class HelidonLoadServer {
  private static final Map<Integer, byte[]> Blobs = new HashMap<>();

  public static void main(String[] args) throws Exception {
    // TLS from the fixed PKCS12 benchmark keystore. start.sh runs from build/dist, so we walk up:
    //   dist -> build -> helidon -> benchmarks -> certs
    Tls tls = Tls.builder()
        .privateKey(key -> key
            .keystore(store -> store
                .passphrase("benchmark")
                .keystore(Resource.create(Paths.get("../../../certs/keystore.p12")))))
        .privateKeyCertChain(key -> key
            .keystore(store -> store
                .passphrase("benchmark")
                .keystore(Resource.create(Paths.get("../../../certs/keystore.p12")))))
        .build();

    WebServer server = WebServer.builder()
        .port(8080)
        .host("0.0.0.0")
        .backlog(200)
        .routing(HelidonLoadServer::routing)
        .putSocket("https", socket -> socket
            .port(8443)
            .host("0.0.0.0")
            .backlog(200)
            .tls(tls)
            .routing(HelidonLoadServer::routing))
        .build()
        .start();

    System.out.println("Helidon server started on port 8080 (h1.1 + h2c) and port 8443 (TLS+ALPN h2)");

    // Keep main thread alive.
    Thread.currentThread().join();
  }

  private static void routing(HttpRouting.Builder routing) {
    routing
        .get("/", HelidonLoadServer::handleNoOp)
        .post("/", HelidonLoadServer::handleNoOp)
        .get("/no-read", HelidonLoadServer::handleNoRead)
        .post("/no-read", HelidonLoadServer::handleNoRead)
        .get("/hello", HelidonLoadServer::handleHello)
        .post("/hello", HelidonLoadServer::handleHello)
        .get("/file", HelidonLoadServer::handleFile)
        .post("/load", HelidonLoadServer::handleLoad)
        .get("/compute", HelidonLoadServer::handleCompute)
        .get("/io", HelidonLoadServer::handleIO)
        .get("/large-response", HelidonLoadServer::handleLargeResponse)
        .get("/stream", HelidonLoadServer::handleStream);
  }

  private static byte[] readRequestBody(ServerRequest req) {
    try (InputStream in = req.content().inputStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) {
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    } catch (Exception e) {
      return new byte[0];
    }
  }

  private static byte[] blobOf(int size) {
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
    return blob;
  }

  private static int queryInt(ServerRequest req, String name, int fallback) {
    return req.query().first(name).map(Integer::parseInt).orElse(fallback);
  }

  private static void handleNoOp(ServerRequest req, ServerResponse res) {
    readRequestBody(req);
    res.status(200).send();
  }

  private static void handleNoRead(ServerRequest req, ServerResponse res) {
    res.status(200).send();
  }

  private static void handleHello(ServerRequest req, ServerResponse res) {
    readRequestBody(req);
    res.headers().contentType(io.helidon.common.media.type.MediaTypes.TEXT_PLAIN);
    res.send("Hello world".getBytes(StandardCharsets.UTF_8));
  }

  private static void handleFile(ServerRequest req, ServerResponse res) {
    readRequestBody(req);
    int size = queryInt(req, "size", 1024 * 1024);
    res.headers().contentType(io.helidon.common.media.type.MediaTypes.APPLICATION_OCTET_STREAM);
    res.send(blobOf(size));
  }

  private static void handleLoad(ServerRequest req, ServerResponse res) {
    byte[] body = readRequestBody(req);
    byte[] result = Base64.getEncoder().encode(body);
    res.headers().contentType(io.helidon.common.media.type.MediaTypes.TEXT_PLAIN);
    res.send(result);
  }

  private static void handleCompute(ServerRequest req, ServerResponse res) {
    int rounds = queryInt(req, "rounds", 5000);
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = new byte[32];
      for (int i = 0; i < rounds; i++) {
        hash = md.digest(hash);
      }
      byte[] body = HexFormat.of().formatHex(hash).getBytes(StandardCharsets.UTF_8);
      res.headers().contentType(io.helidon.common.media.type.MediaTypes.TEXT_PLAIN);
      res.send(body);
    } catch (Exception e) {
      res.status(500).send();
    }
  }

  private static void handleIO(ServerRequest req, ServerResponse res) {
    int ms = queryInt(req, "ms", 10);
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    res.headers().contentType(io.helidon.common.media.type.MediaTypes.TEXT_PLAIN);
    res.send("ok".getBytes(StandardCharsets.UTF_8));
  }

  private static void handleLargeResponse(ServerRequest req, ServerResponse res) {
    int size = queryInt(req, "size", 131072);
    res.headers().contentType(io.helidon.common.media.type.MediaTypes.APPLICATION_OCTET_STREAM);
    res.send(blobOf(size));
  }

  private static void handleStream(ServerRequest req, ServerResponse res) {
    int size = queryInt(req, "size", 131072);
    byte[] blob = blobOf(size);
    res.headers().contentType(io.helidon.common.media.type.MediaTypes.APPLICATION_OCTET_STREAM);
    try (var out = res.outputStream()) {
      int chunkSize = 8192;
      for (int off = 0; off < blob.length; off += chunkSize) {
        int len = Math.min(chunkSize, blob.length - off);
        out.write(blob, off, len);
        out.flush();
      }
    } catch (Exception e) {
      // Stream already started — Helidon will close the response.
    }
  }
}

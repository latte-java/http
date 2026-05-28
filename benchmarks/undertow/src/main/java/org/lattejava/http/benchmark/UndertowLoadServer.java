/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.benchmark;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

public class UndertowLoadServer {
  private static final Map<Integer, byte[]> Blobs = new HashMap<>();

  public static void main(String[] args) throws Exception {
    // Load PKCS12 keystore. start.sh runs from build/dist: dist -> build -> undertow -> benchmarks -> certs
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (FileInputStream in = new FileInputStream("../../../certs/keystore.p12")) {
      ks.load(in, "benchmark".toCharArray());
    }
    KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(ks, "benchmark".toCharArray());

    SSLContext sslContext = SSLContext.getInstance("TLSv1.3");
    sslContext.init(kmf.getKeyManagers(), null, null);

    Undertow server = Undertow.builder()
        .addHttpListener(8080, "0.0.0.0")
        .addHttpsListener(8443, "0.0.0.0", sslContext)
        .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
        .setHandler(new LoadHandler())
        .build();

    server.start();
    System.out.println("Undertow server started on port 8080 (h1.1 + h2c) and port 8443 (TLS+ALPN h2)");
  }

  static class LoadHandler implements HttpHandler {
    @Override
    public void handleRequest(HttpServerExchange exchange) throws Exception {
      // Undertow runs the handler on an XNIO worker thread by default, which can perform blocking I/O.
      if (exchange.isInIoThread()) {
        exchange.dispatch(this);
        return;
      }
      exchange.startBlocking();
      String path = exchange.getRequestPath();
      try {
        switch (path) {
          case "/" -> handleNoOp(exchange);
          case "/no-read" -> handleNoRead(exchange);
          case "/hello" -> handleHello(exchange);
          case "/file" -> handleFile(exchange);
          case "/load" -> handleLoad(exchange);
          case "/compute" -> handleCompute(exchange);
          case "/io" -> handleIO(exchange);
          case "/large-response" -> handleLargeResponse(exchange);
          case "/stream" -> handleStream(exchange);
          default -> handleFailure(exchange, path);
        }
      } catch (Exception e) {
        exchange.setStatusCode(500);
      }
    }

    private static byte[] readRequestBody(HttpServerExchange exchange) throws Exception {
      return exchange.getInputStream().readAllBytes();
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

    private static int queryInt(HttpServerExchange exchange, String name, int fallback) {
      Deque<String> q = exchange.getQueryParameters().get(name);
      if (q == null || q.isEmpty()) return fallback;
      try { return Integer.parseInt(q.getFirst()); } catch (NumberFormatException e) { return fallback; }
    }

    private static void send(HttpServerExchange exchange, byte[] body, String contentType) {
      exchange.setStatusCode(200);
      exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
      exchange.getResponseSender().send(ByteBuffer.wrap(body));
    }

    private void handleNoOp(HttpServerExchange exchange) throws Exception {
      readRequestBody(exchange);
      exchange.setStatusCode(200);
      exchange.endExchange();
    }

    private void handleNoRead(HttpServerExchange exchange) {
      exchange.setStatusCode(200);
      exchange.endExchange();
    }

    private void handleHello(HttpServerExchange exchange) throws Exception {
      readRequestBody(exchange);
      send(exchange, "Hello world".getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private void handleFile(HttpServerExchange exchange) throws Exception {
      readRequestBody(exchange);
      int size = queryInt(exchange, "size", 1024 * 1024);
      send(exchange, blobOf(size), "application/octet-stream");
    }

    private void handleLoad(HttpServerExchange exchange) throws Exception {
      byte[] body = readRequestBody(exchange);
      byte[] result = Base64.getEncoder().encode(body);
      send(exchange, result, "text/plain");
    }

    private void handleCompute(HttpServerExchange exchange) throws Exception {
      int rounds = queryInt(exchange, "rounds", 5000);
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = new byte[32];
      for (int i = 0; i < rounds; i++) {
        hash = md.digest(hash);
      }
      send(exchange, HexFormat.of().formatHex(hash).getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private void handleIO(HttpServerExchange exchange) throws Exception {
      int ms = queryInt(exchange, "ms", 10);
      Thread.sleep(ms);
      send(exchange, "ok".getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private void handleLargeResponse(HttpServerExchange exchange) {
      int size = queryInt(exchange, "size", 131072);
      send(exchange, blobOf(size), "application/octet-stream");
    }

    private void handleStream(HttpServerExchange exchange) throws Exception {
      int size = queryInt(exchange, "size", 131072);
      byte[] blob = blobOf(size);
      exchange.setStatusCode(200);
      exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/octet-stream");
      var out = exchange.getOutputStream();
      int chunkSize = 8192;
      for (int off = 0; off < blob.length; off += chunkSize) {
        int len = Math.min(chunkSize, blob.length - off);
        out.write(blob, off, len);
        out.flush();
      }
      out.close();
    }

    private void handleFailure(HttpServerExchange exchange, String path) throws Exception {
      readRequestBody(exchange);
      byte[] body = ("Invalid path [" + path + "]. Supported paths: /, /no-read, /hello, /file, /load, /compute, /io, /large-response, /stream").getBytes(StandardCharsets.UTF_8);
      exchange.setStatusCode(400);
      exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
      exchange.getResponseSender().send(ByteBuffer.wrap(body));
    }
  }
}

/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;

import org.lattejava.http.tests.grpc.EchoGrpc;
import org.lattejava.http.tests.grpc.EchoProto.EchoRequest;
import org.lattejava.http.tests.grpc.EchoProto.EchoResponse;

import static org.testng.Assert.*;

/**
 * Hand-rolled gRPC interop tests that verify HTTP/2 framing, trailer emission, and the gRPC wire
 * format end-to-end using the grpc-java Netty client against our HTTPServer.
 *
 * <p>Each test boots a server on an OS-assigned port, executes one or two RPCs, and tears down.
 * Client-streaming and bidirectional-streaming RPCs are deferred to a future plan — the unary and
 * server-streaming pair is sufficient to prove that single-message and multi-message response paths
 * (including grpc-status trailers) both work correctly.
 *
 * @author Daniel DeGroff
 */
public class GRPCInteropTest extends BaseTest {
  // ============================================================
  // Unary RPC over h2c
  // ============================================================
  @Test
  public void unary_h2c() throws Exception {
    HTTPHandler handler = grpcUnaryAdapter(req -> EchoResponse.newBuilder().setMessage("hello, " + req.getMessage()).build());

    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    try (var server = makeServer("http", handler, listener).start()) {
      ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getActualPort())
          .usePlaintext()
          .build();
      try {
        var stub = EchoGrpc.newBlockingStub(channel);
        var resp = stub.unary(EchoRequest.newBuilder().setMessage("world").build());
        assertEquals(resp.getMessage(), "hello, world");
      } finally {
        channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
      }
    }
  }

  // ============================================================
  // Server-streaming RPC over h2c
  // ============================================================
  @Test
  public void server_stream_h2c() throws Exception {
    HTTPHandler handler = grpcServerStreamAdapter(req -> {
      List<EchoResponse> out = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        out.add(EchoResponse.newBuilder().setMessage(req.getMessage() + "-" + i).build());
      }
      return out;
    });

    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    try (var server = makeServer("http", handler, listener).start()) {
      ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", server.getActualPort())
          .usePlaintext()
          .build();
      try {
        var stub = EchoGrpc.newBlockingStub(channel);
        var iter = stub.serverStream(EchoRequest.newBuilder().setMessage("stream").build());
        List<String> received = new ArrayList<>();
        iter.forEachRemaining(r -> received.add(r.getMessage()));
        assertEquals(received, List.of("stream-0", "stream-1", "stream-2"));
      } finally {
        channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
      }
    }
  }

  // ============================================================
  // Unary RPC over TLS+h2 (ALPN)
  // ============================================================
  @Test
  public void unary_h2_tls() throws Exception {
    HTTPHandler handler = grpcUnaryAdapter(req -> EchoResponse.newBuilder().setMessage("tls-" + req.getMessage()).build());

    var certChain = new java.security.cert.Certificate[]{certificate, intermediateCertificate};
    var listener = new HTTPListenerConfiguration(0, certChain, keyPair.getPrivate());
    try (var server = makeServer("https", handler, listener).start()) {
      // Trust the full chain: root CA and intermediate. The server sends [server cert, intermediate CA]
      // in the TLS handshake; the client needs the root CA to complete the PKIX chain.
      var ssl = GrpcSslContexts.forClient()
          .trustManager(
              (java.security.cert.X509Certificate) rootCertificate,
              (java.security.cert.X509Certificate) intermediateCertificate)
          .build();
      ManagedChannel channel = NettyChannelBuilder.forAddress("local.lattejava.org", server.getActualPort())
          .sslContext(ssl)
          .build();
      try {
        var stub = EchoGrpc.newBlockingStub(channel);
        var resp = stub.unary(EchoRequest.newBuilder().setMessage("hi").build());
        assertEquals(resp.getMessage(), "tls-hi");
      } finally {
        channel.shutdown().awaitTermination(2, TimeUnit.SECONDS);
      }
    }
  }

  // ============================================================
  // Hand-rolled gRPC adapter helpers
  // ============================================================

  /**
   * Builds a unary HTTPHandler that reads one length-prefixed protobuf request, invokes the supplied
   * function, writes one length-prefixed response, and ends with a {@code grpc-status: 0} trailer.
   *
   * @param impl the function mapping an EchoRequest to an EchoResponse.
   * @return an HTTPHandler suitable for a gRPC unary method.
   */
  private static HTTPHandler grpcUnaryAdapter(java.util.function.Function<EchoRequest, EchoResponse> impl) {
    return (req, res) -> {
      var in = req.getInputStream();
      // Per gRPC HTTP/2 spec: 1-byte compression flag (0 = uncompressed), 4-byte big-endian length, then payload.
      int compressed = in.read();
      if (compressed != 0) {
        throw new IllegalStateException("Test does not exercise gRPC compression; received compressed flag [" + compressed + "]");
      }
      int len = ((in.read() & 0xFF) << 24) | ((in.read() & 0xFF) << 16) | ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
      byte[] payload = in.readNBytes(len);
      EchoRequest grpcReq = EchoRequest.parseFrom(payload);

      EchoResponse grpcResp = impl.apply(grpcReq);
      byte[] respBytes = grpcResp.toByteArray();

      res.setStatus(200);
      res.setHeader("content-type", "application/grpc");
      res.setTrailer("grpc-status", "0");

      var out = res.getOutputStream();
      out.write(0);  // not compressed
      out.write((respBytes.length >> 24) & 0xFF);
      out.write((respBytes.length >> 16) & 0xFF);
      out.write((respBytes.length >> 8) & 0xFF);
      out.write(respBytes.length & 0xFF);
      out.write(respBytes);
      out.close();
    };
  }

  /**
   * Builds a server-streaming HTTPHandler that reads one request and emits a sequence of
   * length-prefixed responses, ending with a {@code grpc-status: 0} trailer.
   *
   * @param impl the function mapping an EchoRequest to a list of EchoResponse messages.
   * @return an HTTPHandler suitable for a gRPC server-streaming method.
   */
  private static HTTPHandler grpcServerStreamAdapter(java.util.function.Function<EchoRequest, List<EchoResponse>> impl) {
    return (req, res) -> {
      var in = req.getInputStream();
      int compressed = in.read();
      if (compressed != 0) {
        throw new IllegalStateException("Test does not exercise gRPC compression; received compressed flag [" + compressed + "]");
      }
      int len = ((in.read() & 0xFF) << 24) | ((in.read() & 0xFF) << 16) | ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
      byte[] payload = in.readNBytes(len);
      EchoRequest grpcReq = EchoRequest.parseFrom(payload);

      List<EchoResponse> responses = impl.apply(grpcReq);

      res.setStatus(200);
      res.setHeader("content-type", "application/grpc");
      res.setTrailer("grpc-status", "0");

      var out = res.getOutputStream();
      for (var grpcResp : responses) {
        byte[] respBytes = grpcResp.toByteArray();
        out.write(0);
        out.write((respBytes.length >> 24) & 0xFF);
        out.write((respBytes.length >> 16) & 0xFF);
        out.write((respBytes.length >> 8) & 0xFF);
        out.write(respBytes.length & 0xFF);
        out.write(respBytes);
      }
      out.close();
    };
  }
}

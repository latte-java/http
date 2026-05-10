/*
 * Copyright (c) 2025, FusionAuth, All Rights Reserved
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

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http2.CleartextHttp2ServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;

public class NettyLoadServer {
  private static final Map<Integer, byte[]> Blobs = new HashMap<>();

  public static void main(String[] args) throws Exception {
    EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    EventLoopGroup workerGroup = new NioEventLoopGroup();

    // TLS+ALPN SslContext: load the fixed benchmark self-signed cert/key from benchmarks/certs/.
    // Path is relative to the working directory (build/dist when launched by start.sh).
    File certFile = new File("../../certs/server.crt");
    File keyFile = new File("../../certs/server.key");
    SslContext sslCtx = SslContextBuilder.forServer(certFile, keyFile)
                                         .sslProvider(SslProvider.JDK)
                                         .applicationProtocolConfig(new ApplicationProtocolConfig(
                                             Protocol.ALPN,
                                             SelectorFailureBehavior.NO_ADVERTISE,
                                             SelectedListenerFailureBehavior.ACCEPT,
                                             ApplicationProtocolNames.HTTP_2,
                                             ApplicationProtocolNames.HTTP_1_1))
                                         .build();

    try {
      // Port 8080: h2c (cleartext) + HTTP/1.1 — used by wrk and h2load h2c scenarios.
      ServerBootstrap b = new ServerBootstrap();
      b.group(bossGroup, workerGroup)
       .channel(NioServerSocketChannel.class)
       .option(ChannelOption.SO_BACKLOG, 200)
       .childHandler(new ChannelInitializer<SocketChannel>() {
         @Override
         protected void initChannel(SocketChannel ch) {
           configurePipeline(ch.pipeline());
         }
       });
      var ch = b.bind(8080).sync().channel();

      // Port 8443: TLS + ALPN h2 — used by h2load TLS scenarios.
      ServerBootstrap tlsBootstrap = new ServerBootstrap();
      tlsBootstrap.group(bossGroup, workerGroup)
                  .channel(NioServerSocketChannel.class)
                  .option(ChannelOption.SO_BACKLOG, 200)
                  .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                      configureTLSPipeline(ch.pipeline(), sslCtx);
                    }
                  });
      var tlsCh = tlsBootstrap.bind(8443).sync().channel();

      System.out.println("Netty server started on port 8080 (h2c) and port 8443 (TLS+ALPN h2)");
      tlsCh.closeFuture().sync();
      ch.closeFuture().sync();
    } finally {
      bossGroup.shutdownGracefully();
      workerGroup.shutdownGracefully();
    }
  }

  /**
   * Configures the channel pipeline to accept both HTTP/1.1 (wrk) and h2c-prior-knowledge (h2load)
   * on the same port 8080.
   *
   * <p>The {@link CleartextHttp2ServerUpgradeHandler} inspects the first bytes of each connection:
   * <ul>
   *   <li>If it sees the h2c PRI preface, it fires a {@code PriorKnowledgeUpgradeEvent} and hands off
   *       to the h2 multiplexer pipeline.</li>
   *   <li>If it sees an HTTP/1.1 Upgrade: h2c request, it performs the upgrade handshake.</li>
   *   <li>Otherwise (plain HTTP/1.1), it falls through to the HTTP/1.1 codec + handler chain.</li>
   * </ul>
   */
  private static void configurePipeline(ChannelPipeline p) {
    // h2c-prior-knowledge: Http2FrameCodec decodes frames, Http2MultiplexHandler
    // creates one child channel per stream, the stream initializer adds the
    // HTTP-object codec + aggregator + shared LoadHandler.
    var http2FrameCodec = Http2FrameCodecBuilder.forServer().build();
    var http2Multiplexer = new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
      @Override
      protected void initChannel(Http2StreamChannel streamCh) {
        streamCh.pipeline().addLast(
            new Http2StreamFrameToHttpObjectCodec(true),
            new HttpObjectAggregator(10 * 1024 * 1024),
            new LoadHandler()
        );
      }
    });

    // HTTP/1.1 side: reused as the source codec for the upgrade handler.
    // After a successful h2c upgrade or prior-knowledge detection the h1 codec is
    // removed from the pipeline by Netty automatically.
    HttpServerCodec sourceCodec = new HttpServerCodec();

    // Upgrade factory: when Upgrade: h2c header is seen on an HTTP/1.1 request,
    // install the h2 frame codec + multiplexer.
    HttpServerUpgradeHandler.UpgradeCodecFactory upgradeCodecFactory =
        protocol -> "h2c".equals(protocol.toString())
            ? new Http2ServerUpgradeCodec(http2FrameCodec, http2Multiplexer)
            : null;
    HttpServerUpgradeHandler upgradeHandler = new HttpServerUpgradeHandler(sourceCodec, upgradeCodecFactory);

    // The cleartext upgrade handler auto-detects h2c preface vs h1.1 upgrade vs plain h1.1.
    // The third argument (http2FrameCodec + http2Multiplexer) is added to the pipeline on
    // prior-knowledge detection; a PriorKnowledgeUpgradeEvent is fired so handlers downstream
    // know the protocol has been switched.
    CleartextHttp2ServerUpgradeHandler cleartextHandler = new CleartextHttp2ServerUpgradeHandler(
        sourceCodec, upgradeHandler, new ChannelInitializer<SocketChannel>() {
          @Override
          protected void initChannel(SocketChannel ch) {
            ch.pipeline().addLast(http2FrameCodec, http2Multiplexer);
          }
        }
    );

    p.addLast(cleartextHandler);
    // Fallback: plain HTTP/1.1 traffic that did not trigger any upgrade.
    p.addLast(new HttpObjectAggregator(10 * 1024 * 1024));
    p.addLast(new LoadHandler());
  }

  /**
   * Configures a TLS+ALPN pipeline on port 8443 for h2load TLS scenarios.
   *
   * <p>The pipeline is:
   * <ol>
   *   <li>TLS handshake (SslHandler from the provided SslContext)</li>
   *   <li>ALPN dispatch (ApplicationProtocolNegotiationHandler): selects h2 or http/1.1 sub-pipeline</li>
   * </ol>
   */
  private static void configureTLSPipeline(ChannelPipeline p, SslContext sslCtx) {
    p.addLast(sslCtx.newHandler(p.channel().alloc()));
    p.addLast(new ApplicationProtocolNegotiationHandler(ApplicationProtocolNames.HTTP_1_1) {
      @Override
      protected void configurePipeline(ChannelHandlerContext ctx, String protocol) {
        if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
          // h2 path: frame codec + stream multiplexer.
          var http2FrameCodec = Http2FrameCodecBuilder.forServer().build();
          var http2Multiplexer = new Http2MultiplexHandler(new ChannelInitializer<Http2StreamChannel>() {
            @Override
            protected void initChannel(Http2StreamChannel streamCh) {
              streamCh.pipeline().addLast(
                  new Http2StreamFrameToHttpObjectCodec(true),
                  new HttpObjectAggregator(10 * 1024 * 1024),
                  new LoadHandler()
              );
            }
          });
          ctx.pipeline().addLast(http2FrameCodec, http2Multiplexer);
        } else {
          // http/1.1 fallback.
          ctx.pipeline().addLast(
              new HttpServerCodec(),
              new HttpObjectAggregator(10 * 1024 * 1024),
              new LoadHandler()
          );
        }
      }
    });
  }

  static class LoadHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
      String path = request.uri();
      int queryIdx = path.indexOf('?');
      String pathOnly = queryIdx >= 0 ? path.substring(0, queryIdx) : path;

      FullHttpResponse response;
      try {
        response = switch (pathOnly) {
          case "/" -> handleNoOp(request);
          case "/no-read" -> handleNoRead();
          case "/hello" -> handleHello();
          case "/file" -> handleFile(request);
          case "/load" -> handleLoad(request);
          default -> handleFailure(pathOnly);
        };
      } catch (Exception e) {
        response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR);
      }

      boolean keepAlive = HttpUtil.isKeepAlive(request);
      response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
      if (keepAlive) {
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
      }

      var future = ctx.writeAndFlush(response);
      if (!keepAlive) {
        future.addListener(ChannelFutureListener.CLOSE);
      }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      ctx.close();
    }

    private FullHttpResponse handleFailure(String path) {
      byte[] body = ("Invalid path [" + path + "]. Supported paths include [/, /no-read, /hello, /file, /load].").getBytes(StandardCharsets.UTF_8);
      ByteBuf content = Unpooled.wrappedBuffer(body);
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, content);
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
      return response;
    }

    private FullHttpResponse handleFile(FullHttpRequest request) {
      int size = 1024 * 1024;
      QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
      var sizeParam = decoder.parameters().get("size");
      if (sizeParam != null && !sizeParam.isEmpty()) {
        size = Integer.parseInt(sizeParam.getFirst());
      }

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

      ByteBuf content = Unpooled.wrappedBuffer(blob);
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
      return response;
    }

    private FullHttpResponse handleHello() {
      byte[] body = "Hello world".getBytes(StandardCharsets.UTF_8);
      ByteBuf content = Unpooled.wrappedBuffer(body);
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
      return response;
    }

    private FullHttpResponse handleLoad(FullHttpRequest request) {
      // Note that this should be mostly the same between all load tests.
      // - See benchmarks/self
      byte[] body = new byte[request.content().readableBytes()];
      request.content().readBytes(body);
      byte[] result = Base64.getEncoder().encode(body);
      ByteBuf content = Unpooled.wrappedBuffer(result);
      FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
      response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
      return response;
    }

    private FullHttpResponse handleNoOp(FullHttpRequest request) {
      // Read the body (it's already aggregated by HttpObjectAggregator)
      return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    }

    private FullHttpResponse handleNoRead() {
      return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
    }
  }
}

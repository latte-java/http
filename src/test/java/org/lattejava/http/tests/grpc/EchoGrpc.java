package org.lattejava.http.tests.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.processing.Generated(
    value = "by gRPC proto compiler (version 1.63.2)",
    comments = "Source: echo.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class EchoGrpc {

  private EchoGrpc() {}

  public static final java.lang.String SERVICE_NAME = "latte.echo.Echo";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
      org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getUnaryMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "Unary",
      requestType = org.lattejava.http.tests.grpc.EchoProto.EchoRequest.class,
      responseType = org.lattejava.http.tests.grpc.EchoProto.EchoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
      org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getUnaryMethod() {
    io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest, org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getUnaryMethod;
    if ((getUnaryMethod = EchoGrpc.getUnaryMethod) == null) {
      synchronized (EchoGrpc.class) {
        if ((getUnaryMethod = EchoGrpc.getUnaryMethod) == null) {
          EchoGrpc.getUnaryMethod = getUnaryMethod =
              io.grpc.MethodDescriptor.<org.lattejava.http.tests.grpc.EchoProto.EchoRequest, org.lattejava.http.tests.grpc.EchoProto.EchoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "Unary"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.lattejava.http.tests.grpc.EchoProto.EchoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.lattejava.http.tests.grpc.EchoProto.EchoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EchoMethodDescriptorSupplier("Unary"))
              .build();
        }
      }
    }
    return getUnaryMethod;
  }

  private static volatile io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
      org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getServerStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ServerStream",
      requestType = org.lattejava.http.tests.grpc.EchoProto.EchoRequest.class,
      responseType = org.lattejava.http.tests.grpc.EchoProto.EchoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
      org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getServerStreamMethod() {
    io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest, org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getServerStreamMethod;
    if ((getServerStreamMethod = EchoGrpc.getServerStreamMethod) == null) {
      synchronized (EchoGrpc.class) {
        if ((getServerStreamMethod = EchoGrpc.getServerStreamMethod) == null) {
          EchoGrpc.getServerStreamMethod = getServerStreamMethod =
              io.grpc.MethodDescriptor.<org.lattejava.http.tests.grpc.EchoProto.EchoRequest, org.lattejava.http.tests.grpc.EchoProto.EchoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ServerStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.lattejava.http.tests.grpc.EchoProto.EchoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.lattejava.http.tests.grpc.EchoProto.EchoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EchoMethodDescriptorSupplier("ServerStream"))
              .build();
        }
      }
    }
    return getServerStreamMethod;
  }

  private static volatile io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
      org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getClientStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ClientStream",
      requestType = org.lattejava.http.tests.grpc.EchoProto.EchoRequest.class,
      responseType = org.lattejava.http.tests.grpc.EchoProto.EchoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
      org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getClientStreamMethod() {
    io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest, org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getClientStreamMethod;
    if ((getClientStreamMethod = EchoGrpc.getClientStreamMethod) == null) {
      synchronized (EchoGrpc.class) {
        if ((getClientStreamMethod = EchoGrpc.getClientStreamMethod) == null) {
          EchoGrpc.getClientStreamMethod = getClientStreamMethod =
              io.grpc.MethodDescriptor.<org.lattejava.http.tests.grpc.EchoProto.EchoRequest, org.lattejava.http.tests.grpc.EchoProto.EchoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ClientStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.lattejava.http.tests.grpc.EchoProto.EchoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.lattejava.http.tests.grpc.EchoProto.EchoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EchoMethodDescriptorSupplier("ClientStream"))
              .build();
        }
      }
    }
    return getClientStreamMethod;
  }

  private static volatile io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
      org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getBidiStreamMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "BidiStream",
      requestType = org.lattejava.http.tests.grpc.EchoProto.EchoRequest.class,
      responseType = org.lattejava.http.tests.grpc.EchoProto.EchoResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
  public static io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
      org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getBidiStreamMethod() {
    io.grpc.MethodDescriptor<org.lattejava.http.tests.grpc.EchoProto.EchoRequest, org.lattejava.http.tests.grpc.EchoProto.EchoResponse> getBidiStreamMethod;
    if ((getBidiStreamMethod = EchoGrpc.getBidiStreamMethod) == null) {
      synchronized (EchoGrpc.class) {
        if ((getBidiStreamMethod = EchoGrpc.getBidiStreamMethod) == null) {
          EchoGrpc.getBidiStreamMethod = getBidiStreamMethod =
              io.grpc.MethodDescriptor.<org.lattejava.http.tests.grpc.EchoProto.EchoRequest, org.lattejava.http.tests.grpc.EchoProto.EchoResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.BIDI_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "BidiStream"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.lattejava.http.tests.grpc.EchoProto.EchoRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  org.lattejava.http.tests.grpc.EchoProto.EchoResponse.getDefaultInstance()))
              .setSchemaDescriptor(new EchoMethodDescriptorSupplier("BidiStream"))
              .build();
        }
      }
    }
    return getBidiStreamMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static EchoStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EchoStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EchoStub>() {
        @java.lang.Override
        public EchoStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EchoStub(channel, callOptions);
        }
      };
    return EchoStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static EchoBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EchoBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EchoBlockingStub>() {
        @java.lang.Override
        public EchoBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EchoBlockingStub(channel, callOptions);
        }
      };
    return EchoBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static EchoFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<EchoFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<EchoFutureStub>() {
        @java.lang.Override
        public EchoFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new EchoFutureStub(channel, callOptions);
        }
      };
    return EchoFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void unary(org.lattejava.http.tests.grpc.EchoProto.EchoRequest request,
        io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getUnaryMethod(), responseObserver);
    }

    /**
     */
    default void serverStream(org.lattejava.http.tests.grpc.EchoProto.EchoRequest request,
        io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getServerStreamMethod(), responseObserver);
    }

    /**
     */
    default io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoRequest> clientStream(
        io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getClientStreamMethod(), responseObserver);
    }

    /**
     */
    default io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoRequest> bidiStream(
        io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getBidiStreamMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service Echo.
   */
  public static abstract class EchoImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return EchoGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service Echo.
   */
  public static final class EchoStub
      extends io.grpc.stub.AbstractAsyncStub<EchoStub> {
    private EchoStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EchoStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EchoStub(channel, callOptions);
    }

    /**
     */
    public void unary(org.lattejava.http.tests.grpc.EchoProto.EchoRequest request,
        io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getUnaryMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void serverStream(org.lattejava.http.tests.grpc.EchoProto.EchoRequest request,
        io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getServerStreamMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoRequest> clientStream(
        io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getClientStreamMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoRequest> bidiStream(
        io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
          getChannel().newCall(getBidiStreamMethod(), getCallOptions()), responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service Echo.
   */
  public static final class EchoBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<EchoBlockingStub> {
    private EchoBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EchoBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EchoBlockingStub(channel, callOptions);
    }

    /**
     */
    public org.lattejava.http.tests.grpc.EchoProto.EchoResponse unary(org.lattejava.http.tests.grpc.EchoProto.EchoRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getUnaryMethod(), getCallOptions(), request);
    }

    /**
     */
    public java.util.Iterator<org.lattejava.http.tests.grpc.EchoProto.EchoResponse> serverStream(
        org.lattejava.http.tests.grpc.EchoProto.EchoRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getServerStreamMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service Echo.
   */
  public static final class EchoFutureStub
      extends io.grpc.stub.AbstractFutureStub<EchoFutureStub> {
    private EchoFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected EchoFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new EchoFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<org.lattejava.http.tests.grpc.EchoProto.EchoResponse> unary(
        org.lattejava.http.tests.grpc.EchoProto.EchoRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getUnaryMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_UNARY = 0;
  private static final int METHODID_SERVER_STREAM = 1;
  private static final int METHODID_CLIENT_STREAM = 2;
  private static final int METHODID_BIDI_STREAM = 3;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_UNARY:
          serviceImpl.unary((org.lattejava.http.tests.grpc.EchoProto.EchoRequest) request,
              (io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse>) responseObserver);
          break;
        case METHODID_SERVER_STREAM:
          serviceImpl.serverStream((org.lattejava.http.tests.grpc.EchoProto.EchoRequest) request,
              (io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_CLIENT_STREAM:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.clientStream(
              (io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse>) responseObserver);
        case METHODID_BIDI_STREAM:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.bidiStream(
              (io.grpc.stub.StreamObserver<org.lattejava.http.tests.grpc.EchoProto.EchoResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getUnaryMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
              org.lattejava.http.tests.grpc.EchoProto.EchoResponse>(
                service, METHODID_UNARY)))
        .addMethod(
          getServerStreamMethod(),
          io.grpc.stub.ServerCalls.asyncServerStreamingCall(
            new MethodHandlers<
              org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
              org.lattejava.http.tests.grpc.EchoProto.EchoResponse>(
                service, METHODID_SERVER_STREAM)))
        .addMethod(
          getClientStreamMethod(),
          io.grpc.stub.ServerCalls.asyncClientStreamingCall(
            new MethodHandlers<
              org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
              org.lattejava.http.tests.grpc.EchoProto.EchoResponse>(
                service, METHODID_CLIENT_STREAM)))
        .addMethod(
          getBidiStreamMethod(),
          io.grpc.stub.ServerCalls.asyncBidiStreamingCall(
            new MethodHandlers<
              org.lattejava.http.tests.grpc.EchoProto.EchoRequest,
              org.lattejava.http.tests.grpc.EchoProto.EchoResponse>(
                service, METHODID_BIDI_STREAM)))
        .build();
  }

  private static abstract class EchoBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    EchoBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return org.lattejava.http.tests.grpc.EchoProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Echo");
    }
  }

  private static final class EchoFileDescriptorSupplier
      extends EchoBaseDescriptorSupplier {
    EchoFileDescriptorSupplier() {}
  }

  private static final class EchoMethodDescriptorSupplier
      extends EchoBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    EchoMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (EchoGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new EchoFileDescriptorSupplier())
              .addMethod(getUnaryMethod())
              .addMethod(getServerStreamMethod())
              .addMethod(getClientStreamMethod())
              .addMethod(getBidiStreamMethod())
              .build();
        }
      }
    }
    return result;
  }
}

module org.lattejava.http.tests {
  requires com.google.common;
  requires com.google.protobuf;
  requires io.grpc;
  requires io.grpc.protobuf;
  requires io.grpc.stub;
  requires jackson5;
  requires java.compiler;
  requires java.net.http;
  requires org.lattejava.http;
  requires org.testng;
  requires restify;
  opens org.lattejava.http.tests.grpc to org.testng;
  opens org.lattejava.http.tests.io to org.testng;
  opens org.lattejava.http.tests.security to org.testng;
  opens org.lattejava.http.tests.server to org.testng;
  opens org.lattejava.http.tests.util to org.testng;
}

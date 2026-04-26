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
package org.lattejava.http.tests.server;

import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

import org.lattejava.http.HTTPValues.Connections;
import org.lattejava.http.HTTPValues.ContentEncodings;
import org.lattejava.http.log.FileLogger;
import org.lattejava.http.log.FileLoggerFactory;
import org.lattejava.http.log.Level;
import org.lattejava.http.log.LoggerFactory;
import org.lattejava.http.security.SecurityTools;
import org.lattejava.http.server.*;
import org.testng.ITestListener;
import org.testng.ITestResult;
import org.testng.annotations.*;
import sun.security.util.KnownOIDs;
import sun.security.util.ObjectIdentifier;
import sun.security.x509.*;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.fail;

/**
 * Base class for tests in order to provide data providers and other reusable code.
 *
 * @author Brian Pontarelli
 */
public abstract class BaseTest {
  /**
   * This timeout is used for the HttpClient during each test. If you are in a debugger, you will need to change this
   * timeout to be much larger, otherwise, the client might truncate the request to the server.
   */
  public static final Duration ClientTimeout = Duration.ofSeconds(30);

  /**
   * This timeout is used for the HTTPServer during each test. If you are in a debugger, you will need to change this
   * timeout to be much larger, otherwise, the server will toss out the request.
   */
  public static final Duration ServerTimeout = Duration.ofSeconds(30);

  private static final ZonedDateTime TestStarted = ZonedDateTime.now();

  private static final DateTimeFormatter hh_mm_ss_SSS = DateTimeFormatter.ofPattern("hh:mm:ss.SSS");

  public static String SystemOutPrefix = "     | ";

  /*
   * Keypairs and certificates for a 3-level CA chain (root->intermediate->server).
   */
  public static Certificate certificate;

  public static Certificate intermediateCertificate;

  public static KeyPair intermediateKeyPair;

  public static KeyPair keyPair;

  public static Certificate rootCertificate;

  public static KeyPair rootKeyPair;

  static {
    System.setProperty("sun.net.http.retryPost", "false");
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "connection");
  }

  protected boolean verbose;

  /**
   * Generates keypairs and certificates for Root CA -> Intermediate -> Server Certificate.
   */
  @BeforeSuite
  public static void setupCertificates() {
    rootKeyPair = generateNewRSAKeyPair();
    intermediateKeyPair = generateNewRSAKeyPair();
    keyPair = generateNewRSAKeyPair();

    // Build root and intermediate CAs
    rootCertificate = generateRootCA(rootKeyPair.getPublic(), rootKeyPair.getPrivate());
    X509CertInfo intermediateCertInfo = generateCertInfo(intermediateKeyPair.getPublic(), "intermediate.lattejava.org");
    intermediateCertificate = signCertificate((X509Certificate) rootCertificate, rootKeyPair.getPrivate(), intermediateCertInfo, true);

    // Build server cert
    X509CertInfo serverCertInfo = generateCertInfo(keyPair.getPublic(), "local.lattejava.org");
    certificate = signCertificate((X509Certificate) intermediateCertificate, intermediateKeyPair.getPrivate(), serverCertInfo, false);
  }

  protected static X509CertInfo generateCertInfo(PublicKey publicKey, String commonName) {
    try {
      X509CertInfo certInfo = new X509CertInfo();
      CertificateX509Key certKey = new CertificateX509Key(publicKey);
      certInfo.setKey(certKey);
      // X.509 Certificate version 3 (0 based)
      certInfo.setVersion(new CertificateVersion(2));
      certInfo.setAlgorithmId(new CertificateAlgorithmId(new AlgorithmId(ObjectIdentifier.of(KnownOIDs.SHA256withRSA))));
      certInfo.setSubject(new X500Name("CN=" + commonName));
      certInfo.setValidity(new CertificateValidity(Date.from(Instant.now().minusSeconds(30)), Date.from(Instant.now().plusSeconds(10_000))));
      certInfo.setSerialNumber(new CertificateSerialNumber(new BigInteger(UUID.randomUUID().toString().replace("-", ""), 16)));

      return certInfo;
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  protected static KeyPair generateNewRSAKeyPair() {
    try {
      KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
      keyPairGenerator.initialize(4096);
      return keyPairGenerator.generateKeyPair();
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

  protected static Certificate generateRootCA(PublicKey publicKey, PrivateKey privateKey)
      throws IllegalArgumentException {
    try {
      // Generate the standard CertInfo, but set Issuer and Subject to the same value.
      X509CertInfo certInfo = generateCertInfo(publicKey, "root-ca.lattejava.org");
      certInfo.setIssuer(new X500Name("CN=root-ca.lattejava.org"));

      // Self-sign certificate
//      return signCertificate(new X509CertImpl(certInfo.getEncodedInfo()), privateKey, certInfo, true);
      return X509CertImpl.newSigned(certInfo, privateKey, "SHA256withRSA");
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  protected static X509Certificate signCertificate(X509Certificate issuer, PrivateKey issuerPrivateKey, X509CertInfo signingRequest,
                                                   boolean isCa)
      throws IllegalArgumentException {

    try {
      X509CertInfo issuerInfo = new X509CertInfo(issuer.getTBSCertificate());
      signingRequest.setIssuer(issuerInfo.getSubject());

      CertificateExtensions certExtensions = new CertificateExtensions();
      if (isCa) {
        certExtensions.setExtension(BasicConstraintsExtension.NAME, new BasicConstraintsExtension(true, true, 1));
      }

      // Set the Subject Alternate Names field to the DNS hostname.
      X500Name subject = signingRequest.getSubject();
      String hostname = subject.getCommonName();
      GeneralNames altNames = new GeneralNames();
      altNames.add(new GeneralName(new DNSName(hostname)));
      certExtensions.setExtension(SubjectAlternativeNameExtension.NAME, new SubjectAlternativeNameExtension(false, altNames));
      signingRequest.setExtensions(certExtensions);

      // Sign it
      return X509CertImpl.newSigned(signingRequest, issuerPrivateKey, "SHA256withRSA");
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
  }

  @BeforeMethod
  public void beforeMethod() {
    verbose = false;
  }

  @DataProvider
  public Object[][] connections() {
    return new Object[][]{
        {Connections.Close},
        {Connections.KeepAlive}
    };
  }

  @DataProvider(name = "contentEncoding")
  public Object[][] contentEncoding() {
    return new Object[][]{
        {""},                // No compression
        {"gzip"},            // gzip only
        {"deflate"},         // deflate only
        {"gzip, deflate"},   // gzip, then deflate
        {"deflate, gzip"}    // deflate, then gzip
    };
  }

  @AfterMethod
  public void flush() {
    FileLogger fl = (FileLogger) FileLoggerFactory.FACTORY.getLogger(BaseTest.class);
    if (fl != null) {
      fl.flush();
    }
  }

  public HttpClient makeClient(String scheme, CookieHandler cookieHandler) throws GeneralSecurityException, IOException {
    var builder = HttpClient.newBuilder();
    if (scheme.equals("https")) {
      builder.sslContext(SecurityTools.clientContext(rootCertificate));
    }

    if (cookieHandler != null) {
      builder.cookieHandler(cookieHandler);
    }

    return builder.connectTimeout(ClientTimeout).build();
  }

  public Socket makeClientSocket(String scheme) throws GeneralSecurityException, IOException {
    Socket socket;
    if (scheme.equals("https")) {
      var ctx = SecurityTools.clientContext(rootCertificate);
      socket = ctx.getSocketFactory().createSocket("127.0.0.1", 4242);
    } else {
      socket = new Socket("127.0.0.1", 4242);
    }

    return socket;
  }

  public HTTPServer makeServer(String scheme, HTTPHandler handler, Instrumenter instrumenter) {
    return makeServer(scheme, handler, instrumenter, null);
  }

  public HTTPServer makeServer(String scheme, HTTPHandler handler) {
    return makeServer(scheme, handler, null);
  }

  @SuppressWarnings("resource")
  public HTTPServer makeServer(String scheme, HTTPHandler handler, Instrumenter instrumenter, ExpectValidator expectValidator) {
    boolean tls = scheme.equals("https");
    HTTPListenerConfiguration listenerConfiguration;
    if (tls) {
      var certChain = new Certificate[]{certificate, intermediateCertificate};
      listenerConfiguration = new HTTPListenerConfiguration(4242, certChain, keyPair.getPrivate());
    } else {
      listenerConfiguration = new HTTPListenerConfiguration(4242);
    }

    LoggerFactory factory = FileLoggerFactory.FACTORY;
    return new HTTPServer().withHandler(handler)
                           .withKeepAliveTimeoutDuration(ServerTimeout)
                           .withInitialReadTimeout(ServerTimeout)
                           .withProcessingTimeoutDuration(ServerTimeout)
                           .withExpectValidator(expectValidator != null ? expectValidator : new AlwaysContinueExpectValidator())
                           .withInstrumenter(instrumenter)
                           .withLoggerFactory(factory)
                           .withMinimumReadThroughput(200 * 1024)
                           .withMinimumWriteThroughput(200 * 1024)
                           .withListener(listenerConfiguration)
                           .withReadThroughputCalculationDelayDuration(Duration.ofSeconds(1))
                           .withWriteThroughputCalculationDelayDuration(Duration.ofSeconds(1));
  }

  public URI makeURI(String scheme, String params) {
    if (scheme.equals("https")) {
      return URI.create("https://local.lattejava.org:4242/api/system/version" + params);
    }

    return URI.create("http://localhost:4242/api/system/version" + params);
  }

  /**
   * @return The possible schemes - {@code http} and {@code https}.
   */
  @DataProvider
  public Object[][] schemes() {
    return new Object[][]{
        {"http"},
        {"https"}
    };
  }

  /**
   * @return The possible schemes - {@code http} and {@code https} and chunked.
   */
  @DataProvider
  public Object[][] schemesAndChunked() {
    return new Object[][]{
        {"http", true},
        {"http", false},
        {"https", true},
        {"https", false}
    };
  }

  /**
   * @return The possible response buffer lengths and schemes.
   */
  @DataProvider
  public Object[][] schemesAndResponseBufferSizes() {
    return new Object[][]{
        {"http", 64 * 1024},
        {"https", 64 * 1024},
        {"http", 512},
        {"https", 512},
        {"http", -1},
        {"https", -1}
    };
  }

  public void sendBadRequest(String message) {
    try (Socket socket = new Socket("127.0.0.1", 4242); OutputStream os = socket.getOutputStream(); InputStream is = socket.getInputStream()) {
      os.write(message.getBytes());
      os.flush();

      // Sockets are pretty resilient, so this will be closed by the server, but we'll just see that close are zero bytes read. If we were
      // to continue writing above, then that likely would throw an exception because the pipe would be broken

      byte[] buffer = is.readAllBytes();
      assertEquals(new String(buffer), """
          HTTP/1.1 400 \r
          connection: close\r
          content-length: 0\r
          \r
          """);
    } catch (Exception e) {
      fail(e.getMessage());
    }
  }

  @AfterSuite
  public void tearDown() {
    System.out.println("\nTests began : " + hh_mm_ss_SSS.format(TestStarted));
    System.out.println("Tests ended : " + hh_mm_ss_SSS.format(ZonedDateTime.now()));
    System.out.println("Total test time in minutes : " + Duration.between(TestStarted, ZonedDateTime.now()).toMinutes());
  }

  /**
   * This is a "faster" wait to assert on an HTTP response using only the socket w/out necessarily having to wait for
   * the socket keep-alive timeout.
   * <p>
   * We start by assuming the number of bytes to read will be equal to the expected response. If that is not the case,
   * then we try to read the remaining bytes from the socket knowing that we will block until we reach the socket
   * timeout.
   * <p>
   * This way we can provide an accurate assertion on the actual response body vs the expected but as long as the test
   * passes, we do not have to wait for the socket timeout.
   *
   * @param socket           the socket used to read the HTTP response
   * @param expectedResponse the expected HTTP response
   */
  protected void assertHTTPResponseEquals(Socket socket, String expectedResponse) throws Exception {
    var is = socket.getInputStream();
    var expectedResponseLength = expectedResponse.getBytes(StandardCharsets.UTF_8).length;

    byte[] buffer = new byte[expectedResponseLength * 2];
    int read = is.read(buffer);
    var actualResponse = new String(buffer, 0, read, StandardCharsets.UTF_8);

    // Perform an initial equality check, this is fast. If it fails, it may because there are remaining bytes left to read. This is slower.
    if (!actualResponse.equals(expectedResponse)) {
      // Note this is going to block until the socket keep-alive times out.
      try {
        assertResponseEquals(is, actualResponse, expectedResponse);
      } catch (SocketException se) {
        // If the server has not read the entire request, trying to read from the InputStream will cause a SocketException due to Connection reset.
        // - Attempt to recover from this condition and read the response.
        // - Note that "normal" HTTP clients won't do this, so this isn't to show what a client would normally see, but it is to show what the server
        //   is returning regardless if the client is smart enough or cares enough to read the response.
        if (se.getMessage().equals("Connection reset")) {
          var addr = socket.getRemoteSocketAddress();
          socket.close();
          try {
            socket.connect(addr);
            assertResponseEquals(socket.getInputStream(), actualResponse, expectedResponse);
          } catch (Exception e) {
            assertEquals(actualResponse, expectedResponse, "[" + e.getClass().getSimpleName() + "] was thrown trying to read. We are going to assert on what we have.\n");
          }
        }
      }
    }
  }

  protected byte[] chunkEncode(byte[] bytes, int chunkSize, String chunkedExtension) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (var i = 0; i < bytes.length; i += chunkSize) {
      var endIndex = Math.min(i + chunkSize, bytes.length);

      var chunk = Arrays.copyOfRange(bytes, i, endIndex);
      var chunkLength = chunk.length;

      String hex = Integer.toHexString(chunkLength);
      out.write(hex.getBytes(StandardCharsets.UTF_8));

      if (chunkedExtension != null) {
        out.write(chunkedExtension.getBytes(StandardCharsets.UTF_8));
      }

      out.write("\r\n".getBytes(StandardCharsets.UTF_8));
      out.write(chunk);
      out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    out.write(("0\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
    return out.toByteArray();
  }

  protected byte[] compressUsingContentEncoding(byte[] bytes, String contentEncoding) throws Exception {
    if (!contentEncoding.isEmpty()) {
      var requestEncodings = contentEncoding.toLowerCase().trim().split(",");
      for (String part : requestEncodings) {
        String encoding = part.trim();
        if (encoding.equals(ContentEncodings.Deflate)) {
          bytes = deflate(bytes);
        } else if (encoding.equals(ContentEncodings.Gzip)) {
          bytes = gzip(bytes);
        }
      }
    }

    return bytes;
  }

  protected byte[] deflate(byte[] bytes) throws Exception {
    ByteArrayOutputStream baseOutputStream = new ByteArrayOutputStream();
    try (DeflaterOutputStream out = new DeflaterOutputStream(baseOutputStream, true)) {
      out.write(bytes);
      out.flush();
      out.finish();
      return baseOutputStream.toByteArray();
    }
  }

  protected byte[] gzip(byte[] bytes) throws Exception {
    ByteArrayOutputStream baseOutputStream = new ByteArrayOutputStream();
    try (DeflaterOutputStream out = new GZIPOutputStream(baseOutputStream, true)) {
      out.write(bytes);
      out.flush();
      out.finish();
      return baseOutputStream.toByteArray();
    }
  }

  protected byte[] inflate(byte[] bytes) throws Exception {
    ByteArrayInputStream baseInputStream = new ByteArrayInputStream(bytes);
    try (InflaterInputStream in = new InflaterInputStream(baseInputStream)) {
      return in.readAllBytes();
    }
  }

  protected void printf(String format, Object... args) {
    if (verbose) {
      System.out.printf(SystemOutPrefix + format, args);
    }
  }

  protected void println(Object o) {
    if (verbose) {
      System.out.println(o);
    }
  }

  protected void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ignore) {
    }
  }

  protected byte[] ungzip(byte[] bytes) throws Exception {
    ByteArrayInputStream baseInputStream = new ByteArrayInputStream(bytes);
    try (InflaterInputStream in = new GZIPInputStream(baseInputStream)) {
      return in.readAllBytes();
    }
  }

  /**
   * Verifies that the chain certificates can be validated up to the supplied root certificate. See
   * {@link CertPathValidator#validate(CertPath, CertPathParameters)} for details.
   */
  protected void validateCertPath(Certificate root, Certificate[] chain)
      throws CertPathValidatorException, InvalidAlgorithmParameterException {

    CertPathValidator validator;
    CertPath certPath;
    PKIXParameters pkixParameters;

    try {
      var certificateFactory = CertificateFactory.getInstance("X.509");
      certPath = certificateFactory.generateCertPath(Arrays.asList(chain));

      // Create a trustStore with only the root installed
      var trustStore = KeyStore.getInstance("JKS");
      trustStore.load(null);
      trustStore.setCertificateEntry("root-ca", root);

      pkixParameters = new PKIXParameters(trustStore);
      pkixParameters.setRevocationEnabled(false);
      validator = CertPathValidator.getInstance("PKIX");
    } catch (Exception e) {
      throw new IllegalArgumentException(e);
    }
    // validate() will throw an exception if any check fails.
    validator.validate(certPath, pkixParameters);
  }

  private void assertResponseEquals(InputStream is, String actualResponse, String expectedResponse) throws IOException {
    try {
      var remainingBytes = is.readAllBytes();
      String fullResponse = actualResponse + new String(remainingBytes, StandardCharsets.UTF_8);
      // Use assertEquals so we can get Eclipse error formatting
      assertEquals(fullResponse, expectedResponse, "An additional [" + remainingBytes.length + "] was read from the InputStream to complete the message.\nInitial expected response\n[" + expectedResponse + "]\nInitial actual response\n[" + actualResponse + "]\n");
    } catch (SocketTimeoutException e) {
      assertEquals(actualResponse, expectedResponse, "[SocketTimeoutException] was thrown trying to read. We are going to assert on what we have.\n");
    }
  }

  @SuppressWarnings("unused")
  public static class TestListener implements ITestListener {
    private int counter = 0;

    private String lastTestMethod;

    private int lastTestMethodCounter = 0;

    @Override
    public void onTestFailure(ITestResult result) {
      Throwable throwable = result.getThrowable();

      // Intentionally leaving empty lines here
      StringBuilder threadDump = new StringBuilder();
      for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
        threadDump.append(entry.getKey()).append(" ").append(entry.getKey().getState()).append("\n");
        for (StackTraceElement ste : entry.getValue()) {
          threadDump.append("\tat ").append(ste).append("\n");
        }
        threadDump.append("\n");
      }

      System.out.println("""
          
          Test failure
          -----------------
          Exception: {{exception}}
          Message: {{message}}
          
          Stack traces (client side):
          {{threadDump}}
          -----------------
          """.replace("{{exception}}", throwable != null ? throwable.getClass().getSimpleName() : "-")
             .replace("{{message}}", throwable != null ? (throwable.getMessage() != null ? throwable.getMessage() : "-") : "-")
             .replace("{{threadDump}}", threadDump));
    }

    @Override
    public void onTestStart(ITestResult result) {
      Object[] dataProvider = result.getParameters();
      String iteration = dataProvider != null && dataProvider.length > 0 ? " [" + serializeDataProviderArgs(dataProvider) + "]" : "";

      // Still missing the factory data provider, for example when we re-run tests as GraalJS or Nashorn, I don't yet have a way to show that in this output.
      // - But TestNG can do it - so we can too! Just need to figure it out.
      String testMethod = result.getTestClass().getName() + "." + result.getName();
      if (lastTestMethod != null && !lastTestMethod.equals(testMethod)) {
        lastTestMethodCounter = 0;
      }

      if (!iteration.isEmpty()) {
        iteration += " (" + ++lastTestMethodCounter + ")";
      }

      lastTestMethod = testMethod;

      // Trying to replicate the name of the test in the IJ TestNG runner.
      System.out.println("[" + (++counter) + "] " + hh_mm_ss_SSS.format(ZonedDateTime.now()) + " " + testMethod + iteration);

      // Set up the logger
      FileLogger logger = new FileLogger(Paths.get("build/test/logs/" + result.getTestClass().getName() + iteration + ".txt"));
      logger.setLevel(Level.Trace);
      FileLoggerFactory.setLogger(logger);
    }

    private String serializeDataProviderArgs(Object[] dataProvider) {
      String result = Arrays.stream(dataProvider)
                            // Escape line return and carriage return to keep everything on the same line
                            .map(o -> (o == null ? "null" : o.toString()).replace("\n", "\\n").replace("\r", "\\r"))
                            .collect(Collectors.joining(", "));

      int maxLength = 128;
      if (result.length() > maxLength) {
        if (result.charAt(maxLength) == ',') {
          maxLength -= 1;
        }

        //noinspection UnnecessaryUnicodeEscape
        result = result.substring(0, maxLength) + "\u2026";
      }

      return result;
    }
  }
}

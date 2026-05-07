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
package org.lattejava.http.server;

import module java.base;
import module org.lattejava.http;

/**
 * An HTTP request that is received by the HTTP server. This contains all the relevant information from the request,
 * including any file uploads and the InputStream that the server can read from to handle the HTTP body.
 * <p>
 * This is mutable because the server is not trying to enforce that the request is always the same as the one it
 * received. There are many cases where requests values are mutated, removed, or replaced. Rather than using a janky
 * delegate or wrapper, this is simply mutable.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class HTTPRequest implements Buildable<HTTPRequest> {
  // Cached so each Accept-Language header parse doesn't allocate a fresh Comparator. Sorts language ranges by their q-value (weight)
  // descending. Double.compare(b, a) avoids both auto-boxing and the .reversed() wrapper we'd get from Comparator.comparingDouble().
  private static final Comparator<Locale.LanguageRange> LANGUAGE_RANGE_BY_WEIGHT_DESC =
      (a, b) -> Double.compare(b.getWeight(), a.getWeight());

  private final List<String> acceptEncodings = new LinkedList<>();

  private final Map<String, Object> attributes = new HashMap<>();

  private final List<String> contentEncodings = new LinkedList<>();

  private final Map<String, Cookie> cookies = new HashMap<>();

  private final List<FileInfo> files = new LinkedList<>();

  private final Map<String, List<String>> headers = new HashMap<>();

  private final List<Locale> locales = new LinkedList<>();

  private final MultipartStreamProcessor multipartStreamProcessor = new MultipartStreamProcessor();

  private final Map<String, List<String>> urlParameters = new HashMap<>();

  private byte[] bodyBytes;

  private Map<String, List<String>> combinedParameters;

  private Long contentLength;

  private String contentType;

  private HTTPContext context;

  private String contextPath;

  private Charset encoding = StandardCharsets.UTF_8;

  private Map<String, List<String>> formData;

  private String host;

  private InputStream inputStream;

  private String ipAddress;

  private HTTPMethod method;

  private boolean multipart;

  private String multipartBoundary;

  private HTTPMethod originalMethod;

  private String path = "/";

  private int port = -1;

  private String protocol;

  private String queryString;

  private String scheme;
  private Map<String, List<String>> trailers;

  public HTTPRequest() {
    this.contextPath = "";
  }

  public HTTPRequest(String contextPath, @Deprecated int multipartBufferSize, String scheme, int port, String ipAddress) {
    Objects.requireNonNull(contextPath);
    Objects.requireNonNull(scheme);
    this.contextPath = contextPath;
    this.scheme = scheme;
    this.port = port;
    this.ipAddress = ipAddress;
    this.multipartStreamProcessor.getMultiPartConfiguration().withMultipartBufferSize(multipartBufferSize);
  }

  public HTTPRequest(HTTPContext context, String contextPath, String scheme, int port, String ipAddress) {
    Objects.requireNonNull(contextPath);
    Objects.requireNonNull(scheme);
    this.context = context;
    this.contextPath = contextPath;
    this.scheme = scheme;
    this.port = port;
    this.ipAddress = ipAddress;
  }

  /**
   * Parse an Accept-Encoding header value into the list of encodings ordered by RFC 9110 priority — q-value descending, original-position
   * ascending for ties. Walks the input with indexOf() instead of {@link String#split(String)} + {@link java.util.TreeSet} + a stream
   * pipeline. The previous implementation showed up at ~17% of CPU and was the top single-source allocator in JFR profiling. Browsers send
   * 1–4 entries with no q-values in the common case; for that path the insertion sort is O(N) over already-sorted weights.
   *
   * @param value the raw header value, e.g. {@code "gzip, deflate, br;q=0.5"}
   * @return the encodings in priority order, never null
   */
  private static List<String> parseAcceptEncoding(String value) {
    int cap = 4;
    String[] encodings = new String[cap];
    double[] weights = new double[cap];
    int count = 0;
    int len = value.length();
    int from = 0;

    while (from < len) {
      int comma = value.indexOf(',', from);
      int segmentEnd = comma < 0 ? len : comma;

      // Trim OWS (RFC 9110 §5.6.3 allows space and HTAB) from both ends of the segment.
      int start = from;
      while (start < segmentEnd && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
        start++;
      }
      int end = segmentEnd;
      while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
        end--;
      }

      if (start < end) {
        // Locate the optional ';q=...' parameter. Other parameters (rare for Accept-Encoding) are skipped over.
        int semi = value.indexOf(';', start);
        if (semi < 0 || semi >= end) {
          semi = -1;
        }

        double weight = 1.0;
        int encEnd = end;
        if (semi >= 0) {
          encEnd = semi;
          while (encEnd > start && (value.charAt(encEnd - 1) == ' ' || value.charAt(encEnd - 1) == '\t')) {
            encEnd--;
          }

          int p = semi + 1;
          while (p < end) {
            while (p < end && (value.charAt(p) == ' ' || value.charAt(p) == '\t')) {
              p++;
            }
            if (p + 1 < end && (value.charAt(p) == 'q' || value.charAt(p) == 'Q') && value.charAt(p + 1) == '=') {
              int qStart = p + 2;
              int qEnd = value.indexOf(';', qStart);
              if (qEnd < 0 || qEnd > end) {
                qEnd = end;
              }
              try {
                weight = Double.parseDouble(value.substring(qStart, qEnd).trim());
              } catch (NumberFormatException ignored) {
                // Malformed q-value — leave weight at default 1.0.
              }
              break;
            }
            int next = value.indexOf(';', p);
            if (next < 0 || next >= end) {
              break;
            }
            p = next + 1;
          }
        }

        if (count == cap) {
          cap *= 2;
          encodings = Arrays.copyOf(encodings, cap);
          weights = Arrays.copyOf(weights, cap);
        }
        encodings[count] = value.substring(start, encEnd);
        weights[count] = weight;
        count++;
      }

      if (comma < 0) {
        break;
      }
      from = comma + 1;
    }

    if (count == 0) {
      return List.of();
    }

    // Insertion sort: weight DESC, original index ASC. Stable on equal weights because we only swap on strict less-than. O(N) on the common
    // browser case where every weight is 1.0 and the input is already in priority order.
    for (int i = 1; i < count; i++) {
      String e = encodings[i];
      double w = weights[i];
      int j = i - 1;
      while (j >= 0 && weights[j] < w) {
        encodings[j + 1] = encodings[j];
        weights[j + 1] = weights[j];
        j--;
      }
      encodings[j + 1] = e;
      weights[j + 1] = w;
    }

    List<String> result = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      result.add(encodings[i]);
    }
    return result;
  }

  /**
   * @return true if the client signaled {@code TE: trailers} per RFC 9110 §10.1.4 — trailer fields will be honored on the response.
   */
  public boolean acceptsTrailers() {
    String te = getHeader(HTTPValues.Headers.TE);
    if (te == null) {
      return false;
    }

    for (String token : te.split(",")) {
      if (token.trim().equalsIgnoreCase("trailers")) {
        return true;
      }
    }

    return false;
  }

  public void addAcceptEncoding(String encoding) {
    this.acceptEncodings.add(encoding);
  }

  public void addAcceptEncodings(List<String> encodings) {
    this.acceptEncodings.addAll(encodings);
  }

  public void addContentEncoding(String encoding) {
    this.contentEncodings.add(encoding);
  }

  public void addContentEncodings(List<String> encodings) {
    this.contentEncodings.addAll(encodings);
  }

  public void addCookies(Cookie... cookies) {
    for (Cookie cookie : cookies) {
      this.cookies.put(cookie.name, cookie);
    }
  }

  public void addCookies(Collection<Cookie> cookies) {
    if (cookies == null) {
      return;
    }

    for (Cookie cookie : cookies) {
      this.cookies.put(cookie.name, cookie);
    }
  }

  public void addHeader(String name, String value) {
    name = name.toLowerCase(Locale.ROOT);
    headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    decodeHeader(name, value);
  }

  public void addHeaders(String name, String... values) {
    name = name.toLowerCase(Locale.ROOT);
    headers.computeIfAbsent(name, key -> new ArrayList<>()).addAll(List.of(values));

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  public void addHeaders(String name, Collection<String> values) {
    name = name.toLowerCase(Locale.ROOT);
    headers.computeIfAbsent(name, key -> new ArrayList<>()).addAll(values);

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  public void addHeaders(Map<String, List<String>> params) {
    params.forEach(this::addHeaders);
  }

  public void addLocales(Locale... locales) {
    this.locales.addAll(Arrays.asList(locales));
  }

  public void addLocales(Collection<Locale> locales) {
    this.locales.addAll(locales);
  }

  public void addTrailer(String name, String value) {
    if (trailers == null) {
      trailers = new HashMap<>();
    }
    trailers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(value);
  }

  public void addURLParameter(String name, String value) {
    urlParameters.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    combinedParameters = null;
  }

  public void addURLParameters(String name, String... values) {
    urlParameters.computeIfAbsent(name, key -> new ArrayList<>()).addAll(List.of(values));
    combinedParameters = null;
  }

  public void addURLParameters(String name, Collection<String> values) {
    urlParameters.computeIfAbsent(name, key -> new ArrayList<>()).addAll(values);
    combinedParameters = null;
  }

  public void addURLParameters(Map<String, List<String>> params) {
    params.forEach(this::addURLParameters);
    combinedParameters = null;
  }

  public void deleteCookie(String name) {
    cookies.remove(name);
  }

  public List<String> getAcceptEncodings() {
    return acceptEncodings;
  }

  public void setAcceptEncodings(List<String> encodings) {
    this.acceptEncodings.clear();
    this.acceptEncodings.addAll(encodings);
  }

  /**
   * Retrieves a request attribute.
   *
   * @param name The name of the attribute.
   * @return The attribute or null if it doesn't exist.
   */
  public Object getAttribute(String name) {
    return attributes.get(name);
  }

  /**
   * Retrieves all the request attributes. This returns the direct Map so changes to the Map will affect all
   * attributes.
   *
   * @return The attribute Map.
   */
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  public String getBaseURL() {
    // Setting the wrong value in the X-Forwarded-Proto header seems to be a common issue that causes an exception during URI.create.
    // Assuming request.getScheme() is not the problem, and it is related to the proxy configuration.
    String scheme = getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
      throw new IllegalArgumentException("The request scheme is invalid. Only http or https are valid schemes. The X-Forwarded-Proto header has a value of [" + getHeader(HTTPValues.Headers.XForwardedProto) + "], this is likely an issue in your proxy configuration.");
    }

    String serverName = getHost().toLowerCase(Locale.ROOT);
    int serverPort = getBaseURLServerPort();

    String uri = scheme + "://" + serverName;
    if (serverPort > 0) {
      if ((scheme.equalsIgnoreCase("http") && serverPort != 80) || (scheme.equalsIgnoreCase("https") && serverPort != 443)) {
        uri += ":" + serverPort;
      }
    }

    return uri;
  }

  public byte[] getBodyBytes() throws BodyException {
    if (bodyBytes == null) {
      if (inputStream != null) {
        try {
          bodyBytes = inputStream.readAllBytes();
        } catch (IOException e) {
          throw new BodyException("Unable to read the HTTP request body bytes", e);
        }
      } else {
        bodyBytes = new byte[0];
      }
    }

    return bodyBytes;
  }

  public Charset getCharacterEncoding() {
    return encoding;
  }

  public void setCharacterEncoding(Charset encoding) {
    this.encoding = encoding;
  }

  public List<String> getContentEncodings() {
    return contentEncodings;
  }

  public void setContentEncodings(List<String> encodings) {
    this.contentEncodings.clear();
    this.contentEncodings.addAll(encodings);
  }

  public Long getContentLength() {
    return contentLength;
  }

  public void setContentLength(Long contentLength) {
    this.contentLength = contentLength;
  }

  public String getContentType() {
    return contentType;
  }

  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  public HTTPContext getContext() {
    return context;
  }

  public String getContextPath() {
    return contextPath;
  }

  public void setContextPath(String contextPath) {
    this.contextPath = contextPath;
  }

  public Cookie getCookie(String name) {
    return cookies.get(name);
  }

  public List<Cookie> getCookies() {
    return new ArrayList<>(cookies.values());
  }

  public Instant getDateHeader(String name) {
    String header = getHeader(name);
    return header != null ? ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() : null;
  }

  /**
   * Processes the HTTP request body completely by calling {@link #getFormData()}. If the {@code Content-Type} header is
   * multipart, then the processing of the body will extract the files.
   *
   * @return The files, if any.
   */
  public List<FileInfo> getFiles() {
    getFormData();
    return files;
  }

  /**
   * Processes the HTTP request body completely if the {@code Content-Type} header is equal to
   * {@link HTTPValues.ContentTypes#Form}. If this method is called multiple times, the body is only processed the first
   * time. This is not thread-safe, so you need to ensure you protect against multiple threads calling this method
   * concurrently.
   * <p>
   * If the {@code Content-Type} is not {@link HTTPValues.ContentTypes#Form}, this will always return an empty Map.
   * <p>
   * If the InputStream is not ready or complete, this will block until all the bytes are read from the client.
   *
   * @return The Form data body.
   */
  public Map<String, List<String>> getFormData() {
    if (formData == null) {
      formData = new HashMap<>();

      String contentType = getContentType();
      if (contentType != null && contentType.equalsIgnoreCase(HTTPValues.ContentTypes.Form)) {
        byte[] body = getBodyBytes();
        HTTPTools.parseEncodedData(body, 0, body.length, getCharacterEncoding(), formData);
      } else if (isMultipart()) {
        try {
          multipartStreamProcessor.process(inputStream, formData, files, multipartBoundary.getBytes());
        } catch (IOException e) {
          throw new BodyException("Invalid multipart body.", e);
        }
      }
    }

    return formData;
  }

  public String getHeader(String name) {
    List<String> values = getHeaders(name);
    return values != null && !values.isEmpty() ? values.getFirst() : null;
  }

  public List<String> getHeaders(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }

  public Map<String, List<String>> getHeaders() {
    return headers;
  }

  public void setHeaders(Map<String, List<String>> parameters) {
    this.headers.clear();
    parameters.forEach(this::setHeaders);
  }

  public String getHost() {
    String xHost = getHeader(HTTPValues.Headers.XForwardedHost);
    if (xHost == null) {
      return host;
    }

    String[] xHosts = xHost.split(",");
    if (xHosts.length > 1) {
      xHost = xHosts[0];
    }

    int colon = xHost.indexOf(':');
    if (colon > 0) {
      return xHost.substring(0, colon);
    }

    return xHost.trim();
  }

  public void setHost(String host) {
    this.host = host;
  }

  public String getIPAddress() {
    String xIPAddress = getHeader(HTTPValues.Headers.XForwardedFor);
    if (xIPAddress == null || xIPAddress.trim().isEmpty()) {
      return ipAddress;
    }

    String[] ips = xIPAddress.split(",");
    if (ips.length < 1) {
      return xIPAddress.trim();
    }

    return ips[0].trim();
  }

  public void setIPAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  public InputStream getInputStream() {
    return inputStream;
  }

  public void setInputStream(InputStream inputStream) {
    this.inputStream = inputStream;
    combinedParameters = null;
    formData = null;
  }

  public Locale getLocale() {
    return !locales.isEmpty() ? locales.getFirst() : Locale.getDefault();
  }

  public List<Locale> getLocales() {
    return locales;
  }

  public HTTPMethod getMethod() {
    return method;
  }

  /**
   * Sets the effective HTTP method for this request. The first call also fixes the original wire method, which is
   * exposed via {@link #isHeadRequest()}. Subsequent calls update only the effective method and do not affect
   * {@link #isHeadRequest()}.
   *
   * @param method The method to set.
   */
  public void setMethod(HTTPMethod method) {
    if (this.originalMethod == null) {
      this.originalMethod = method;
    }
    this.method = method;
  }

  public MultipartStreamProcessor getMultiPartStreamProcessor() {
    return multipartStreamProcessor;
  }

  public String getMultipartBoundary() {
    return multipartBoundary;
  }

  /**
   * Calls {@link #getParameters()} to combine everything and then returns the first parameter value for the given
   * name.
   *
   * @param name The name of the parameter
   * @return The parameter values or null if the parameter doesn't exist.
   */
  public String getParameter(String name) {
    List<String> values = getParameters().get(name);
    if (values != null && !values.isEmpty()) {
      return values.getFirst();
    }

    return null;
  }

  /**
   * Combines the URL parameters and the form data that might exist in the body of the HTTP request. The Map returned is
   * not linked back to the URL parameters or form data. Changing it will not impact either of those Maps. If this
   * method is called multiple times, the merging of all the data is only done the first time and then cached. This is
   * not thread-safe, so you need to ensure you protect against multiple threads calling this method concurrently.
   *
   * @return The combined parameters.
   */
  public Map<String, List<String>> getParameters() {
    if (combinedParameters == null) {
      combinedParameters = new HashMap<>();
      getURLParameters().forEach((name, values) -> combinedParameters.put(name, new LinkedList<>(values)));
      getFormData().forEach((name, value) -> combinedParameters.merge(name, value, (first, second) -> {
        first.addAll(second);
        return first;
      }));
    }

    return combinedParameters;
  }

  /**
   * Calls {@link #getParameters()} to combine everything and then returns the parameters for the given name.
   *
   * @param name The name of the parameter
   * @return The parameter values or null if the parameter doesn't exist.
   */
  public List<String> getParameters(String name) {
    return getParameters().get(name);
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    urlParameters.clear();

    // Parse the parameters
    byte[] chars = path.getBytes(StandardCharsets.UTF_8);
    int questionMark = path.indexOf('?');
    if (questionMark > 0 && questionMark != chars.length - 1) {
      queryString = new String(chars, questionMark + 1, chars.length - questionMark - 1);
      HTTPTools.parseEncodedData(chars, questionMark + 1, chars.length, urlParameters);
    }

    // Only save the path portion and ensure we decode it properly
    this.path = questionMark > 0 ? new String(chars, 0, questionMark) : path;
  }

  public int getPort() {
    String xPort = getHeader(HTTPValues.Headers.XForwardedPort);
    return xPort == null ? port : Integer.parseInt(xPort);
  }

  public void setPort(int port) {
    this.port = port;
  }

  public String getProtocol() {
    return protocol;
  }

  public void setProtocol(String protocol) {
    this.protocol = protocol;
  }

  public String getQueryString() {
    return queryString;
  }

  public String getRawHost() {
    return host;
  }

  public String getRawIPAddress() {
    return ipAddress;
  }

  public int getRawPort() {
    return port;
  }

  public String getRawScheme() {
    return scheme;
  }

  public String getScheme() {
    String xScheme = getHeader(HTTPValues.Headers.XForwardedProto);
    return xScheme == null ? scheme : xScheme;
  }

  public void setScheme(String scheme) {
    this.scheme = scheme;
  }

  public String getTrailer(String name) {
    if (trailers == null) {
      return null;
    }
    List<String> values = trailers.get(name.toLowerCase(Locale.ROOT));
    return (values == null || values.isEmpty()) ? null : values.getFirst();
  }

  public Map<String, List<String>> getTrailerMap() {
    return trailers == null ? Map.of() : trailers;
  }

  public List<String> getTrailers(String name) {
    if (trailers == null) {
      return List.of();
    }
    return trailers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
  }

  public String getTransferEncoding() {
    return getHeader(HTTPValues.Headers.TransferEncoding);
  }

  public String getURLParameter(String name) {
    List<String> values = urlParameters.get(name);
    return (values != null && !values.isEmpty()) ? values.getFirst() : null;
  }

  public List<String> getURLParameters(String name) {
    return urlParameters.get(name);
  }

  public Map<String, List<String>> getURLParameters() {
    return urlParameters;
  }

  public void setURLParameters(Map<String, List<String>> parameters) {
    this.urlParameters.clear();
    this.urlParameters.putAll(parameters);
  }

  /**
   * @return True if the request can reasonably be assumed to have a body. This uses the fact that the request is
   *     chunked or that {@code Content-Length} header was provided.
   */
  public boolean hasBody() {
    if (isChunked()) {
      return true;
    }

    Long contentLength = getContentLength();
    return contentLength != null && contentLength > 0;
  }

  public boolean hasTrailers() {
    return trailers != null && !trailers.isEmpty();
  }

  public boolean isChunked() {
    return HTTPValues.TransferEncodings.Chunked.equalsIgnoreCase(getTransferEncoding());
  }

  /**
   * @return True if the original wire method of this request was HEAD, regardless of any later
   *     {@link #setMethod(HTTPMethod)} call (e.g., the server's HEAD-to-GET rewrite). Handlers can use this to
   *     short-circuit body generation while still writing correct response headers.
   */
  public boolean isHeadRequest() {
    return originalMethod != null && originalMethod.is(HTTPMethod.HEAD);
  }

  /**
   * Determines if the request is asking for the server to keep the connection alive. This is based on the Connection
   * header.
   * <p>
   * This method will account for HTTP 1.0 and 1.1 protocol versions. In HTTP 1.0, you must explicitly ask for a
   * persistent connection, and in HTTP 1.1 it is on by default, and you must request for it to be disabled by providing
   * the <code>Connection: close</code> request header.
   *
   * @return True if the Connection header is missing or not `Close`.
   */
  public boolean isKeepAlive() {
    if ("HTTP/2.0".equals(getProtocol())) {
      return true;
    }

    // Connection is a comma-separated token list per RFC 9110 §7.6.1, e.g. "close, upgrade". Exact equality misclassifies any
    // multi-token value, so split into tokens and check membership.
    var tokens = connectionTokens();
    if (HTTPValues.Protocols.HTTTP1_0.equals(protocol)) {
      // HTTP/1.0 requires the client to ask explicitly for keep-alive. Some load testing frameworks still use HTTP/1.0, so this path
      // matters for benchmark accuracy.
      return tokens.contains(HTTPValues.Connections.KeepAlive);
    }

    return !tokens.contains(HTTPValues.Connections.Close);
  }

  public boolean isMultipart() {
    return multipart;
  }

  /**
   * Removes a request attribute.
   *
   * @param name The name of the attribute.
   * @return The attribute if it exists.
   */
  public Object removeAttribute(String name) {
    return attributes.remove(name);
  }

  public void removeHeader(String name) {
    headers.remove(name.toLowerCase(Locale.ROOT));
  }

  public void removeHeader(String name, String... values) {
    List<String> actual = headers.get(name.toLowerCase(Locale.ROOT));
    if (actual != null) {
      actual.removeAll(List.of(values));
    }
  }

  /**
   * Sets a request attribute.
   *
   * @param name  The name to store the attribute under.
   * @param value The attribute value.
   */
  public void setAttribute(String name, Object value) {
    attributes.put(name, value);
  }

  public void setHeader(String name, String value) {
    name = name.toLowerCase(Locale.ROOT);
    this.headers.put(name, new ArrayList<>(List.of(value)));
    decodeHeader(name, value);
  }

  public void setHeaders(String name, String... values) {
    name = name.toLowerCase(Locale.ROOT);
    this.headers.put(name, new ArrayList<>(List.of(values)));

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  public void setHeaders(String name, Collection<String> values) {
    name = name.toLowerCase(Locale.ROOT);
    this.headers.put(name, new ArrayList<>(values));

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  public void setURLParameter(String name, String value) {
    setURLParameters(name, value);
  }

  public void setURLParameters(String name, String... values) {
    setURLParameters(name, new ArrayList<>(List.of(values)));
  }

  public void setURLParameters(String name, Collection<String> values) {
    List<String> list = new ArrayList<>();
    this.urlParameters.put(name, list);

    values.stream()
          .filter(Objects::nonNull)
          .forEach(list::add);

    combinedParameters = null;
  }

  private Set<String> connectionTokens() {
    var values = getHeaders(HTTPValues.Headers.Connection);
    if (values == null || values.isEmpty()) {
      return Set.of();
    }

    // Fast path: one header instance with no comma is a single token. Avoids HashSet and split() regex on the per-request hot path.
    if (values.size() == 1 && values.getFirst().indexOf(',') < 0) {
      String token = values.getFirst().trim();
      return token.isEmpty() ? Set.of() : Set.of(token.toLowerCase(Locale.ROOT));
    }

    Set<String> tokens = HashSet.newHashSet(2);
    for (String value : values) {
      for (String token : value.split(",")) {
        token = token.trim();
        if (!token.isEmpty()) {
          tokens.add(token.toLowerCase(Locale.ROOT));
        }
      }
    }
    return tokens;
  }

  private void decodeHeader(String name, String value) {
    switch (name) {
      case HTTPValues.Headers.AcceptEncodingLower:
        setAcceptEncodings(parseAcceptEncoding(value));
        break;
      case HTTPValues.Headers.AcceptLanguageLower:
        try {
          List<Locale.LanguageRange> parsed = Locale.LanguageRange.parse(value);
          if (parsed.isEmpty()) {
            break;
          }
          // Replace the prior stream() -> sorted() -> map() -> map() -> collect() pipeline with a manual sort+loop. JFR profiling showed
          // the stream pipeline plus its intermediate ReferencePipeline / SortedOps / SizedRefSortingSink objects were a meaningful
          // allocation source inside decodeHeader, dwarfed only by LanguageRange.parse itself (which is JDK code we can't easily replace).
          // LanguageRange.parse returns an unmodifiable list, so a mutable copy is required to call sort() in place.
          List<Locale.LanguageRange> ranges = new ArrayList<>(parsed);
          ranges.sort(LANGUAGE_RANGE_BY_WEIGHT_DESC);
          List<Locale> localeList = new ArrayList<>(ranges.size());
          for (Locale.LanguageRange range : ranges) {
            localeList.add(Locale.forLanguageTag(range.getRange()));
          }
          addLocales(localeList);
        } catch (Exception e) {
          // Ignore the exception and keep the value null
        }
        break;
      case HTTPValues.Headers.ContentEncodingLower:
        String[] encodings = value.split(",");
        List<String> contentEncodings = new ArrayList<>(1);
        for (String encoding : encodings) {
          encoding = encoding.trim();
          if (encoding.isEmpty()) {
            continue;
          }

          // The HTTP/1.1 standard recommends that the servers supporting gzip also recognize x-gzip as an alias for compatibility.
          if (encoding.equalsIgnoreCase(HTTPValues.ContentEncodings.XGzip)) {
            encoding = HTTPValues.ContentEncodings.Gzip;
          }

          contentEncodings.add(encoding);
        }

        setContentEncodings(contentEncodings);
        break;
      case HTTPValues.Headers.ContentTypeLower:
        this.encoding = null;
        this.multipart = false;

        HTTPTools.HeaderValue headerValue = HTTPTools.parseHeaderValue(value);
        this.contentType = headerValue.value();

        if (headerValue.value().startsWith(HTTPValues.ContentTypes.MultipartPrefix)) {
          this.multipart = true;
          this.multipartBoundary = headerValue.parameters().get(HTTPValues.ContentTypes.BoundaryParameter);
        }

        String charset = headerValue.parameters().get(HTTPValues.ContentTypes.CharsetParameter);
        if (charset != null) {
          this.encoding = Charset.forName(charset);
        }

        break;
      case HTTPValues.Headers.ContentLengthLower:
        if (value == null || value.isBlank()) {
          contentLength = null;
        } else {
          try {
            contentLength = Long.parseLong(value);
          } catch (NumberFormatException e) {
            contentLength = null;
          }
        }
        break;
      case HTTPValues.Headers.CookieLower:
        addCookies(Cookie.fromRequestHeader(value));
        break;
      case HTTPValues.Headers.HostLower:
        int colon = value.indexOf(':');
        if (colon > 0) {
          this.host = value.substring(0, colon);
          String portString = value.substring(colon + 1);
          if (!portString.isEmpty()) {
            try {
              this.port = Integer.parseInt(portString);
            } catch (NumberFormatException e) {
              // fallback, intentionally do nothing
            }
          }
        } else {
          this.host = value;
          if ("http".equalsIgnoreCase(scheme)) {
            this.port = 80;
          } else if ("https".equalsIgnoreCase(scheme)) {
            this.port = 443;
          }
        }
        break;
    }
  }

  /**
   * Try and infer the port if the X-Forwarded-Port header is not present.
   *
   * @return the server port
   */
  private int getBaseURLServerPort() {
    // Ignore port 80 for http
    int serverPort = getPort();
    if (scheme.equalsIgnoreCase("http") && serverPort == 80) {
      serverPort = -1;
    }

    // See if we can infer a better choice for the port than the current serverPort.

    // If we already have an X-Forwarded-Port header, nothing to do here.
    if (getHeader(HTTPValues.Headers.XForwardedPort) != null) {
      return serverPort;
    }

    // If we don't have a host header, nothing to do here.
    String xHost = getHeader(HTTPValues.Headers.XForwardedHost);
    if (xHost == null) {
      return serverPort;
    }

    // If we can pull the port from the X-Forwarded-Host header, let's do that.
    // - This is effectively the same as X-Forwarded-Port
    try {
      int hostPort = URI.create("https://" + xHost).getPort();
      if (hostPort != -1) {
        return hostPort;
      }
    } catch (Exception ignore) {
      // If we can't parse the hostHeader, keep the existing resolved port
      return serverPort;
    }

    // If we don't have an X-Forwarded-Proto header, or it is not https, nothing to do here.
    // - We must have the X-Forwarded-Proto: https in order to assume 443
    if (!"https".equals(getHeader(HTTPValues.Headers.XForwardedProto))) {
      return serverPort;
    }

    // If we made this far, we have met all conditions for assuming port 443.
    // - We are missing the X-Forwarded-Port header, we have an X-Forwarded-Proto header of https, and we have an X-Forwarded-Host
    //   header value that has not defined a port.
    return 443;
  }
}

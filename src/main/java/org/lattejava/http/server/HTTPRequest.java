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

  /**
   * Constructs an empty request with an empty context path. All other fields are left at their defaults and are
   * expected to be populated by the server as it parses the request line, headers, and body.
   */
  public HTTPRequest() {
    this.contextPath = "";
  }

  /**
   * Constructs a request with the connection-level information that the server knows before it parses the request
   * headers.
   *
   * @param contextPath         The context path that the server is bound to, never {@code null}.
   * @param multipartBufferSize The buffer size to use when parsing multipart bodies. This parameter is deprecated and
   *                            is applied to the request's {@link MultipartStreamProcessor} configuration.
   * @param scheme              The wire scheme of the connection, e.g. {@code "http"} or {@code "https"}, never
   *                            {@code null}.
   * @param port                The local port the connection was accepted on.
   * @param ipAddress           The remote IP address of the client.
   */
  public HTTPRequest(String contextPath, @Deprecated int multipartBufferSize, String scheme, int port, String ipAddress) {
    Objects.requireNonNull(contextPath);
    Objects.requireNonNull(scheme);
    this.contextPath = contextPath;
    this.scheme = scheme;
    this.port = port;
    this.ipAddress = ipAddress;
    this.multipartStreamProcessor.getMultiPartConfiguration().withMultipartBufferSize(multipartBufferSize);
  }

  /**
   * Constructs a request with the connection-level information that the server knows before it parses the request
   * headers, along with the {@link HTTPContext} that the request is being handled within.
   *
   * @param context     The HTTP context the request is associated with, may be {@code null}.
   * @param contextPath The context path that the server is bound to, never {@code null}.
   * @param scheme      The wire scheme of the connection, e.g. {@code "http"} or {@code "https"}, never {@code null}.
   * @param port        The local port the connection was accepted on.
   * @param ipAddress   The remote IP address of the client.
   */
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
   * Parse an Accept-Encoding header value into the list of encodings ordered by RFC 9110 priority — q-value descending,
   * original-position ascending for ties. Walks the input with indexOf() instead of {@link String#split(String)} +
   * {@link java.util.TreeSet} + a stream pipeline. The previous implementation showed up at ~17% of CPU and was the top
   * single-source allocator in JFR profiling. Browsers send 1–4 entries with no q-values in the common case; for that
   * path the insertion sort is O(N) over already-sorted weights.
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
   * Adds a single accept-encoding to the list of encodings the client will accept in the response body. These are
   * normally populated automatically from the {@code Accept-Encoding} request header.
   *
   * @param encoding The accept-encoding to add, e.g. {@code "gzip"}.
   */
  public void addAcceptEncoding(String encoding) {
    this.acceptEncodings.add(encoding);
  }

  /**
   * Adds all the given accept-encodings to the list of encodings the client will accept in the response body.
   *
   * @param encodings The accept-encodings to add.
   */
  public void addAcceptEncodings(List<String> encodings) {
    this.acceptEncodings.addAll(encodings);
  }

  /**
   * Adds a single content-encoding to the list of encodings that have been applied to the request body. These are
   * normally populated automatically from the {@code Content-Encoding} request header.
   *
   * @param encoding The content-encoding to add, e.g. {@code "gzip"}.
   */
  public void addContentEncoding(String encoding) {
    this.contentEncodings.add(encoding);
  }

  /**
   * Adds all the given content-encodings to the list of encodings that have been applied to the request body.
   *
   * @param encodings The content-encodings to add.
   */
  public void addContentEncodings(List<String> encodings) {
    this.contentEncodings.addAll(encodings);
  }

  /**
   * Adds the given cookies to the request, keyed by cookie name. An existing cookie with the same name is replaced.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * request.addCookies(new Cookie("session", "abc123"));
   * request.getCookie("session"); // => the "session" cookie
   * }</pre>
   *
   * @param cookies The cookies to add.
   */
  public void addCookies(Cookie... cookies) {
    for (Cookie cookie : cookies) {
      this.cookies.put(cookie.name, cookie);
    }
  }

  /**
   * Adds the given cookies to the request, keyed by cookie name. An existing cookie with the same name is replaced. A
   * {@code null} collection is silently ignored.
   *
   * @param cookies The cookies to add, may be {@code null}.
   */
  public void addCookies(Collection<Cookie> cookies) {
    if (cookies == null) {
      return;
    }

    for (Cookie cookie : cookies) {
      this.cookies.put(cookie.name, cookie);
    }
  }

  /**
   * Adds a single value for the named header. The header name is lower-cased before storage, so header lookups are
   * case-insensitive. Adding certain headers (e.g. {@code Content-Type}, {@code Cookie}, {@code Accept-Encoding}) also
   * decodes them into the corresponding parsed request state.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * request.addHeader("X-Request-Id", "abc-123");
   * request.getHeader("x-request-id"); // => "abc-123"
   * }</pre>
   *
   * @param name  The header name.
   * @param value The header value.
   */
  public void addHeader(String name, String value) {
    name = name.toLowerCase(Locale.ROOT);
    headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    decodeHeader(name, value);
  }

  /**
   * Adds multiple values for the named header. The header name is lower-cased before storage. Each value is also
   * decoded into the corresponding parsed request state.
   *
   * @param name   The header name.
   * @param values The header values to add.
   */
  public void addHeaders(String name, String... values) {
    name = name.toLowerCase(Locale.ROOT);
    headers.computeIfAbsent(name, key -> new ArrayList<>()).addAll(List.of(values));

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  /**
   * Adds multiple values for the named header. The header name is lower-cased before storage. Each value is also
   * decoded into the corresponding parsed request state.
   *
   * @param name   The header name.
   * @param values The header values to add.
   */
  public void addHeaders(String name, Collection<String> values) {
    name = name.toLowerCase(Locale.ROOT);
    headers.computeIfAbsent(name, key -> new ArrayList<>()).addAll(values);

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  /**
   * Adds all the headers in the given Map, where each key is a header name and the value is the list of values for that
   * header.
   *
   * @param params The headers to add.
   */
  public void addHeaders(Map<String, List<String>> params) {
    params.forEach(this::addHeaders);
  }

  /**
   * Adds the given locales to the request's accept-language preference list. These are normally populated automatically
   * from the {@code Accept-Language} request header in q-value order.
   *
   * @param locales The locales to add.
   */
  public void addLocales(Locale... locales) {
    this.locales.addAll(Arrays.asList(locales));
  }

  /**
   * Adds the given locales to the request's accept-language preference list. These are normally populated automatically
   * from the {@code Accept-Language} request header in q-value order.
   *
   * @param locales The locales to add.
   */
  public void addLocales(Collection<Locale> locales) {
    this.locales.addAll(locales);
  }

  /**
   * Adds a single value for the named URL (query string) parameter. This invalidates the cached combined parameters so
   * that the next call to {@link #getParameters()} re-merges the URL parameters and form data.
   *
   * @param name  The parameter name.
   * @param value The parameter value to add.
   */
  public void addURLParameter(String name, String value) {
    urlParameters.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
    combinedParameters = null;
  }

  /**
   * Adds multiple values for the named URL (query string) parameter. This invalidates the cached combined parameters.
   *
   * @param name   The parameter name.
   * @param values The parameter values to add.
   */
  public void addURLParameters(String name, String... values) {
    urlParameters.computeIfAbsent(name, key -> new ArrayList<>()).addAll(List.of(values));
    combinedParameters = null;
  }

  /**
   * Adds multiple values for the named URL (query string) parameter. This invalidates the cached combined parameters.
   *
   * @param name   The parameter name.
   * @param values The parameter values to add.
   */
  public void addURLParameters(String name, Collection<String> values) {
    urlParameters.computeIfAbsent(name, key -> new ArrayList<>()).addAll(values);
    combinedParameters = null;
  }

  /**
   * Adds all the URL (query string) parameters in the given Map. This invalidates the cached combined parameters.
   *
   * @param params The URL parameters to add, keyed by parameter name.
   */
  public void addURLParameters(Map<String, List<String>> params) {
    params.forEach(this::addURLParameters);
    combinedParameters = null;
  }

  /**
   * Deletes the cookie with the given name from this request, if it exists.
   *
   * @param name The name of the cookie to delete.
   */
  public void deleteCookie(String name) {
    cookies.remove(name);
  }

  /**
   * Returns the list of encodings the client will accept in the response body, ordered by priority (highest q-value
   * first). This is parsed from the {@code Accept-Encoding} request header. The returned list is the live backing
   * list.
   *
   * @return The accept-encodings, never {@code null}.
   */
  public List<String> getAcceptEncodings() {
    return acceptEncodings;
  }

  /**
   * Replaces the request's accept-encodings with the given list.
   *
   * @param encodings The accept-encodings to set.
   */
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

  /**
   * Builds the base URL for this request, consisting of the scheme, host, and (when non-standard) port. The scheme is
   * resolved via {@link #getScheme()} (honoring {@code X-Forwarded-Proto}), the host via {@link #getHost()} (honoring
   * {@code X-Forwarded-Host}), and the port via the internal proxy-aware port resolution. The standard ports (80 for
   * http, 443 for https) are omitted.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * request.getBaseURL(); // => "https://acme.com"
   * }</pre>
   *
   * @return The base URL with no trailing slash.
   * @throws IllegalArgumentException If the resolved scheme is neither {@code http} nor {@code https}, which usually
   *                                  indicates a misconfigured {@code X-Forwarded-Proto} header in the proxy.
   */
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

  /**
   * Reads and returns the entire HTTP request body as a byte array. The body is read from the input stream the first
   * time this is called and cached, subsequent calls return the same array. If there is no input stream, an empty
   * array is returned. This is not thread-safe and may block until the client has sent the full body.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * byte[] body = request.getBodyBytes();
   * String json = new String(body, request.getCharacterEncoding());
   * }</pre>
   *
   * @return The request body bytes, never {@code null}.
   * @throws BodyException If the body cannot be read from the input stream.
   */
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

  /**
   * Returns the character encoding used to decode the request body. This is derived from the {@code charset} parameter
   * of the {@code Content-Type} header, defaulting to {@link StandardCharsets#UTF_8} when not specified.
   *
   * @return The body character encoding.
   */
  public Charset getCharacterEncoding() {
    return encoding;
  }

  /**
   * Sets the character encoding used to decode the request body.
   *
   * @param encoding The body character encoding.
   */
  public void setCharacterEncoding(Charset encoding) {
    this.encoding = encoding;
  }

  /**
   * Returns the list of encodings that have been applied to the request body, parsed from the {@code Content-Encoding}
   * request header. The returned list is the live backing list.
   *
   * @return The content encodings, never {@code null}.
   */
  public List<String> getContentEncodings() {
    return contentEncodings;
  }

  /**
   * Replaces the request's content encodings with the given list.
   *
   * @param encodings The content encodings to set.
   */
  public void setContentEncodings(List<String> encodings) {
    this.contentEncodings.clear();
    this.contentEncodings.addAll(encodings);
  }

  /**
   * Returns the value of the {@code Content-Length} header, or {@code null} if it was absent or not a valid number.
   *
   * @return The content length in bytes, or {@code null}.
   */
  public Long getContentLength() {
    return contentLength;
  }

  /**
   * Sets the content length of the request body.
   *
   * @param contentLength The content length in bytes, or {@code null} if unknown.
   */
  public void setContentLength(Long contentLength) {
    this.contentLength = contentLength;
  }

  /**
   * Returns the media type portion of the {@code Content-Type} header (without any parameters such as {@code charset}),
   * or {@code null} if the header was absent.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * request.getContentType(); // => "application/json" for "application/json; charset=UTF-8"
   * }</pre>
   *
   * @return The content type, or {@code null}.
   */
  public String getContentType() {
    return contentType;
  }

  /**
   * Sets the content type of the request body.
   *
   * @param contentType The content type.
   */
  public void setContentType(String contentType) {
    this.contentType = contentType;
  }

  /**
   * Returns the {@link HTTPContext} this request is associated with, or {@code null} if none was set.
   *
   * @return The HTTP context, or {@code null}.
   */
  public HTTPContext getContext() {
    return context;
  }

  /**
   * Returns the context path the server is bound to. This is the prefix of the URL path that is not part of the
   * application's routable path.
   *
   * @return The context path, never {@code null} (an empty string when there is none).
   */
  public String getContextPath() {
    return contextPath;
  }

  /**
   * Sets the context path the server is bound to.
   *
   * @param contextPath The context path.
   */
  public void setContextPath(String contextPath) {
    this.contextPath = contextPath;
  }

  /**
   * Returns the cookie with the given name, or {@code null} if no such cookie was sent with the request.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * Cookie session = request.getCookie("session");
   * String id = session != null ? session.value : null;
   * }</pre>
   *
   * @param name The name of the cookie.
   * @return The cookie, or {@code null}.
   */
  public Cookie getCookie(String name) {
    return cookies.get(name);
  }

  /**
   * Returns a snapshot list of all the cookies sent with the request. The returned list is a copy, so modifying it does
   * not affect the request.
   *
   * @return The cookies, never {@code null}.
   */
  public List<Cookie> getCookies() {
    return new ArrayList<>(cookies.values());
  }

  /**
   * Returns the value of the named header parsed as an RFC 1123 date, or {@code null} if the header is absent.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * Instant since = request.getDateHeader("If-Modified-Since"); // => parsed Instant or null
   * }</pre>
   *
   * @param name The header name.
   * @return The parsed instant, or {@code null} if the header is absent.
   */
  public Instant getDateHeader(String name) {
    String header = getHeader(name);
    return header != null ? ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() : null;
  }

  /**
   * Processes the HTTP request body completely by calling {@link #getFormData()}. If the {@code Content-Type} header is
   * multipart, then the processing of the body will extract the files. This may block until the full body has been read
   * from the client.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * for (FileInfo file : request.getFiles()) {
   *   Files.copy(file.file, target.resolve(file.name));
   * }
   * }</pre>
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
   * <pre>{@code
   * HTTPRequest request = ...;
   * Map<String, List<String>> form = request.getFormData();
   * String email = form.getOrDefault("email", List.of()).stream().findFirst().orElse(null);
   * }</pre>
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

  /**
   * Returns the first value of the named header, or {@code null} if the header is absent. Header lookups are
   * case-insensitive.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * request.getHeader("Content-Type"); // => "application/json"
   * }</pre>
   *
   * @param name The header name.
   * @return The first header value, or {@code null}.
   */
  public String getHeader(String name) {
    List<String> values = getHeaders(name);
    return values != null && !values.isEmpty() ? values.getFirst() : null;
  }

  /**
   * Returns all values of the named header, or {@code null} if the header is absent. Header lookups are
   * case-insensitive.
   *
   * @param name The header name.
   * @return The header values, or {@code null}.
   */
  public List<String> getHeaders(String name) {
    return headers.get(name.toLowerCase(Locale.ROOT));
  }

  /**
   * Returns the live Map of all request headers, keyed by lower-cased header name. Modifying this Map modifies the
   * request's headers directly.
   *
   * @return The headers Map, never {@code null}.
   */
  public Map<String, List<String>> getHeaders() {
    return headers;
  }

  /**
   * Replaces all the request's headers with the entries in the given Map. Each value is also decoded into the
   * corresponding parsed request state.
   *
   * @param parameters The headers to set, keyed by header name.
   */
  public void setHeaders(Map<String, List<String>> parameters) {
    this.headers.clear();
    parameters.forEach(this::setHeaders);
  }

  /**
   * Returns the host name for this request without the port. If an {@code X-Forwarded-Host} header is present, its
   * first comma-separated entry is used (with any {@code :port} suffix stripped); otherwise the host parsed from the
   * {@code Host} header is returned.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * // X-Forwarded-Host: acme.com:8443, internal
   * request.getHost(); // => "acme.com"
   * }</pre>
   *
   * @return The host name, or {@code null} if none is known.
   */
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

  /**
   * Sets the raw host name for this request. This is the value returned by {@link #getRawHost()}; note that
   * {@link #getHost()} still prefers the {@code X-Forwarded-Host} header when present.
   *
   * @param host The host name.
   */
  public void setHost(String host) {
    this.host = host;
  }

  /**
   * Returns the client IP address for this request. If an {@code X-Forwarded-For} header is present and non-blank, its
   * first comma-separated entry (the original client) is returned; otherwise the raw connection IP address is
   * returned.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * // X-Forwarded-For: 203.0.113.7, 10.0.0.1
   * request.getIPAddress(); // => "203.0.113.7"
   * }</pre>
   *
   * @return The client IP address, or {@code null} if none is known.
   */
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

  /**
   * Sets the raw connection IP address for this request. This is the value returned by {@link #getRawIPAddress()}; note
   * that {@link #getIPAddress()} still prefers the {@code X-Forwarded-For} header when present.
   *
   * @param ipAddress The client IP address.
   */
  public void setIPAddress(String ipAddress) {
    this.ipAddress = ipAddress;
  }

  /**
   * Returns the raw input stream for the request body. Reading from this stream directly is mutually exclusive with the
   * buffering accessors such as {@link #getBodyBytes()} and {@link #getFormData()}.
   *
   * @return The body input stream, or {@code null} if there is no body.
   */
  public InputStream getInputStream() {
    return inputStream;
  }

  /**
   * Sets the input stream for the request body. This also clears the cached combined parameters and form data so they
   * are recomputed from the new stream on the next access.
   *
   * @param inputStream The body input stream.
   */
  public void setInputStream(InputStream inputStream) {
    this.inputStream = inputStream;
    combinedParameters = null;
    formData = null;
  }

  /**
   * Returns the client's preferred locale. This is the highest-priority locale parsed from the {@code Accept-Language}
   * header, or {@link Locale#getDefault()} if none was sent.
   *
   * @return The preferred locale, never {@code null}.
   */
  public Locale getLocale() {
    return !locales.isEmpty() ? locales.getFirst() : Locale.getDefault();
  }

  /**
   * Returns the client's accepted locales in preference order (highest q-value first), as parsed from the
   * {@code Accept-Language} header. The returned list is the live backing list.
   *
   * @return The accepted locales, never {@code null}.
   */
  public List<Locale> getLocales() {
    return locales;
  }

  /**
   * Returns the effective HTTP method for this request. This may differ from the original wire method if it was
   * rewritten via {@link #setMethod(HTTPMethod)} (e.g. the server's HEAD-to-GET rewrite).
   *
   * @return The HTTP method, or {@code null} if it has not been set.
   */
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

  /**
   * Returns the {@link MultipartStreamProcessor} used to parse multipart request bodies. This can be used to adjust the
   * multipart configuration (e.g. buffer size or file size limits) before the body is processed.
   *
   * @return The multipart stream processor, never {@code null}.
   */
  public MultipartStreamProcessor getMultiPartStreamProcessor() {
    return multipartStreamProcessor;
  }

  /**
   * Returns the multipart boundary string parsed from the {@code Content-Type} header, or {@code null} if the request
   * is not multipart.
   *
   * @return The multipart boundary, or {@code null}.
   */
  public String getMultipartBoundary() {
    return multipartBoundary;
  }

  /**
   * Calls {@link #getParameters()} to combine everything and then returns the first parameter value for the given
   * name.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * // /search?q=latte
   * request.getParameter("q"); // => "latte"
   * }</pre>
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
   * <pre>{@code
   * HTTPRequest request = ...;
   * // POST /save?id=7 with form body "name=latte"
   * Map<String, List<String>> params = request.getParameters();
   * params.get("id");   // => ["7"]
   * params.get("name"); // => ["latte"]
   * }</pre>
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
   * <pre>{@code
   * HTTPRequest request = ...;
   * // /filter?tag=a&tag=b
   * request.getParameters("tag"); // => ["a", "b"]
   * }</pre>
   *
   * @param name The name of the parameter
   * @return The parameter values or null if the parameter doesn't exist.
   */
  public List<String> getParameters(String name) {
    return getParameters().get(name);
  }

  /**
   * Returns the decoded request path, without the query string. Defaults to {@code "/"} until {@link #setPath(String)}
   * is called.
   *
   * @return The request path, never {@code null}.
   */
  public String getPath() {
    return path;
  }

  /**
   * Sets the request path. If the given value contains a query string ({@code ?...}), it is split off: the query string
   * is stored (see {@link #getQueryString()}) and parsed into the URL parameters, while only the path portion is
   * retained. This clears any previously set URL parameters.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * request.setPath("/users?id=7");
   * request.getPath();             // => "/users"
   * request.getQueryString();      // => "id=7"
   * request.getURLParameter("id"); // => "7"
   * }</pre>
   *
   * @param path The raw request path, optionally including a query string.
   */
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

  /**
   * Returns the port for this request. If an {@code X-Forwarded-Port} header is present, its value is parsed and
   * returned; otherwise the raw connection port is returned.
   *
   * @return The port.
   */
  public int getPort() {
    String xPort = getHeader(HTTPValues.Headers.XForwardedPort);
    return xPort == null ? port : Integer.parseInt(xPort);
  }

  /**
   * Sets the raw connection port for this request. This is the value returned by {@link #getRawPort()}; note that
   * {@link #getPort()} still prefers the {@code X-Forwarded-Port} header when present.
   *
   * @param port The port.
   */
  public void setPort(int port) {
    this.port = port;
  }

  /**
   * Returns the HTTP protocol version of the request, e.g. {@code "HTTP/1.1"}.
   *
   * @return The protocol, or {@code null} if it has not been set.
   */
  public String getProtocol() {
    return protocol;
  }

  /**
   * Sets the HTTP protocol version of the request.
   *
   * @param protocol The protocol, e.g. {@code "HTTP/1.1"}.
   */
  public void setProtocol(String protocol) {
    this.protocol = protocol;
  }

  /**
   * Returns the raw query string from the request path (the portion after {@code ?}), or {@code null} if there was
   * none. This is set by {@link #setPath(String)}.
   *
   * @return The query string, or {@code null}.
   */
  public String getQueryString() {
    return queryString;
  }

  /**
   * Returns the raw host name set on this request, ignoring any {@code X-Forwarded-Host} header. Use {@link #getHost()}
   * for the proxy-aware host.
   *
   * @return The raw host, or {@code null}.
   */
  public String getRawHost() {
    return host;
  }

  /**
   * Returns the raw connection IP address, ignoring any {@code X-Forwarded-For} header. Use {@link #getIPAddress()} for
   * the proxy-aware client IP.
   *
   * @return The raw IP address, or {@code null}.
   */
  public String getRawIPAddress() {
    return ipAddress;
  }

  /**
   * Returns the raw connection port, ignoring any {@code X-Forwarded-Port} header. Use {@link #getPort()} for the
   * proxy-aware port.
   *
   * @return The raw port.
   */
  public int getRawPort() {
    return port;
  }

  /**
   * Returns the raw connection scheme, ignoring any {@code X-Forwarded-Proto} header. Use {@link #getScheme()} for the
   * proxy-aware scheme.
   *
   * @return The raw scheme, e.g. {@code "http"} or {@code "https"}.
   */
  public String getRawScheme() {
    return scheme;
  }

  /**
   * Reconstructs the full URL as the end user's browser used it, including the scheme, host, optional port, path, and
   * query string. The scheme, host, and port are resolved via {@link #getBaseURL()}, so any {@code X-Forwarded-Proto},
   * {@code X-Forwarded-Host}, and {@code X-Forwarded-Port} proxy headers are honored and the standard ports (80 for
   * http, 443 for https) are omitted. The query string is appended only when one is present. The URL fragment
   * ({@code #...}) is never included because browsers do not transmit it to the server.
   *
   * @return The reconstructed request URL.
   * @throws IllegalArgumentException If the request scheme is neither http nor https, propagated from
   *                                  {@link #getBaseURL()}.
   */
  public String getReconstructedURL() {
    String url = getBaseURL() + getPath();
    String queryString = getQueryString();
    if (queryString != null && !queryString.isEmpty()) {
      url += "?" + queryString;
    }

    return url;
  }

  /**
   * Returns the scheme for this request. If an {@code X-Forwarded-Proto} header is present, its value is returned;
   * otherwise the raw connection scheme is returned.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * // X-Forwarded-Proto: https (TLS terminated at the proxy)
   * request.getScheme(); // => "https"
   * }</pre>
   *
   * @return The scheme, typically {@code "http"} or {@code "https"}.
   */
  public String getScheme() {
    String xScheme = getHeader(HTTPValues.Headers.XForwardedProto);
    return xScheme == null ? scheme : xScheme;
  }

  /**
   * Sets the raw connection scheme for this request. This is the value returned by {@link #getRawScheme()}; note that
   * {@link #getScheme()} still prefers the {@code X-Forwarded-Proto} header when present.
   *
   * @param scheme The scheme, typically {@code "http"} or {@code "https"}.
   */
  public void setScheme(String scheme) {
    this.scheme = scheme;
  }

  /**
   * Returns the value of the {@code Transfer-Encoding} header, or {@code null} if it is absent.
   *
   * @return The transfer encoding, or {@code null}.
   */
  public String getTransferEncoding() {
    return getHeader(HTTPValues.Headers.TransferEncoding);
  }

  /**
   * Returns the first value of the named URL (query string) parameter, or {@code null} if it does not exist. Unlike
   * {@link #getParameter(String)}, this does not consider form data and does not trigger body processing.
   *
   * <pre>{@code
   * HTTPRequest request = ...;
   * // /search?q=latte
   * request.getURLParameter("q"); // => "latte"
   * }</pre>
   *
   * @param name The parameter name.
   * @return The first parameter value, or {@code null}.
   */
  public String getURLParameter(String name) {
    List<String> values = urlParameters.get(name);
    return (values != null && !values.isEmpty()) ? values.getFirst() : null;
  }

  /**
   * Returns all values of the named URL (query string) parameter, or {@code null} if it does not exist. This does not
   * consider form data.
   *
   * @param name The parameter name.
   * @return The parameter values, or {@code null}.
   */
  public List<String> getURLParameters(String name) {
    return urlParameters.get(name);
  }

  /**
   * Returns the live Map of all URL (query string) parameters, keyed by parameter name. Modifying this Map modifies the
   * request's URL parameters directly.
   *
   * @return The URL parameters Map, never {@code null}.
   */
  public Map<String, List<String>> getURLParameters() {
    return urlParameters;
  }

  /**
   * Replaces all of the request's URL (query string) parameters with the entries in the given Map.
   *
   * @param parameters The URL parameters to set, keyed by parameter name.
   */
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

  /**
   * Returns whether the request body uses chunked transfer encoding, based on the {@code Transfer-Encoding} header.
   *
   * @return {@code true} if the request is chunked.
   */
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

  /**
   * Returns whether the request body is a multipart body, based on the {@code Content-Type} header having a
   * {@code multipart/} media type.
   *
   * @return {@code true} if the request is multipart.
   */
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

  /**
   * Removes the named header and all of its values. The header name is matched case-insensitively.
   *
   * @param name The header name.
   */
  public void removeHeader(String name) {
    headers.remove(name.toLowerCase(Locale.ROOT));
  }

  /**
   * Removes the given values from the named header, leaving any other values for that header in place. The header name
   * is matched case-insensitively. Does nothing if the header is absent.
   *
   * @param name   The header name.
   * @param values The specific values to remove.
   */
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

  /**
   * Sets the named header to a single value, replacing any existing values. The header name is lower-cased before
   * storage, and the value is decoded into the corresponding parsed request state.
   *
   * @param name  The header name.
   * @param value The header value.
   */
  public void setHeader(String name, String value) {
    name = name.toLowerCase(Locale.ROOT);
    this.headers.put(name, new ArrayList<>(List.of(value)));
    decodeHeader(name, value);
  }

  /**
   * Sets the named header to the given values, replacing any existing values. The header name is lower-cased before
   * storage, and each value is decoded into the corresponding parsed request state.
   *
   * @param name   The header name.
   * @param values The header values.
   */
  public void setHeaders(String name, String... values) {
    name = name.toLowerCase(Locale.ROOT);
    this.headers.put(name, new ArrayList<>(List.of(values)));

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  /**
   * Sets the named header to the given values, replacing any existing values. The header name is lower-cased before
   * storage, and each value is decoded into the corresponding parsed request state.
   *
   * @param name   The header name.
   * @param values The header values.
   */
  public void setHeaders(String name, Collection<String> values) {
    name = name.toLowerCase(Locale.ROOT);
    this.headers.put(name, new ArrayList<>(values));

    for (String value : values) {
      decodeHeader(name, value);
    }
  }

  /**
   * Sets the named URL (query string) parameter to a single value, replacing any existing values.
   *
   * @param name  The parameter name.
   * @param value The parameter value.
   */
  public void setURLParameter(String name, String value) {
    setURLParameters(name, value);
  }

  /**
   * Sets the named URL (query string) parameter to the given values, replacing any existing values.
   *
   * @param name   The parameter name.
   * @param values The parameter values.
   */
  public void setURLParameters(String name, String... values) {
    setURLParameters(name, new ArrayList<>(List.of(values)));
  }

  /**
   * Sets the named URL (query string) parameter to the given values, replacing any existing values. Any {@code null}
   * values in the collection are filtered out. This invalidates the cached combined parameters.
   *
   * @param name   The parameter name.
   * @param values The parameter values; {@code null} entries are ignored.
   */
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

/*
 * Copyright (c) 2021-2025, FusionAuth, All Rights Reserved
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
 * An HTTP response that the server sends back to a client. The handler that processes the HTTP request can fill out
 * this object and the HTTP server will send it back to the client.
 *
 * @author Brian Pontarelli
 */
@SuppressWarnings("unused")
public class HTTPResponse {
  private final Map<String, Map<String, Cookie>> cookies = new HashMap<>(); // <Path, <Name, Cookie>>

  private final Map<String, List<String>> headers = new LinkedHashMap<>();

  private Throwable exception;

  private HTTPOutputStream outputStream;

  private int status = 200;

  private String statusMessage;

  private Writer writer;

  /**
   * Adds a {@link Cookie} to this response. The cookie is stored keyed by its path (defaulting to {@code /} when the
   * cookie's path is {@code null}) and then by its name, so adding another cookie with the same path and name replaces
   * the previous one. Each stored cookie is later written as a {@code Set-Cookie} response header.
   * <p>
   * The cookie must be added before the response is committed (before the first byte of the body is written).
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * Cookie cookie = new Cookie("session", "abc123");
   * cookie.path = "/";
   * response.addCookie(cookie);
   * }</pre>
   *
   * @param cookie The cookie to add to the response.
   */
  public void addCookie(Cookie cookie) {
    String path = cookie.path != null ? cookie.path : "/";
    cookies.computeIfAbsent(path, key -> new LinkedHashMap<>()).put(cookie.name, cookie);
  }

  /**
   * Add a response header. Calling this method multiple times with the same name will result in multiple headers being
   * written to the HTTP response.
   * <p>
   * Optionally call {@link #setHeader(String, String)} if you wish to only write a single header by name, overwriting
   * any previously written headers.
   * <p>
   * If either parameter is null, the header will not be added to the response.
   * <p>
   * Header names are stored lower-cased internally, so lookups via {@link #getHeader(String)} are case-insensitive.
   * This must be called before the response is committed.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.addHeader("Set-Cookie", "a=1");
   * response.addHeader("Set-Cookie", "b=2");
   * // => two Set-Cookie headers are written to the response
   * }</pre>
   *
   * @param name  The header name.
   * @param value The header value.
   */
  public void addHeader(String name, String value) {
    if (name == null || value == null) {
      return;
    }

    headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(value);
  }

  /**
   * Removes all response headers that have been added so far. This does not affect cookies, the status code, or any
   * bytes already written. Call this before the response is committed if you want the change to take effect.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.clearHeaders();
   * response.setContentType("application/json");
   * }</pre>
   */
  public void clearHeaders() {
    headers.clear();
  }

  /**
   * Closes the HTTP response to ensure that the client is notified that the server is finished responding. This closes
   * the Writer or the OutputStream if they are available. The Writer is preferred if it exists so that it is properly
   * flushed.
   * <p>
   * After this method returns, no further body content can be written. The server normally closes the response, so
   * handlers typically only call this when they need to signal completion early.
   *
   * @throws IOException If closing the underlying {@link Writer} or {@code OutputStream} throws.
   */
  public void close() throws IOException {
    if (writer != null) {
      writer.close();
    } else {
      outputStream.close();
    }
  }

  /**
   * Determines whether the response currently has at least one value for the named header. The lookup is
   * case-insensitive because header names are stored lower-cased.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setContentType("text/html");
   * boolean has = response.containsHeader("Content-Type"); // => true
   * }</pre>
   *
   * @param name The header name to check.
   * @return {@code true} if the header exists and has at least one value, {@code false} otherwise.
   */
  public boolean containsHeader(String name) {
    String key = name.toLowerCase(Locale.ROOT);
    return headers.containsKey(key) && !headers.get(key).isEmpty();
  }

  /**
   * Indicates whether the current status code represents a failure, meaning it is outside the {@code 2xx} success
   * range. A status less than {@code 200} or greater than {@code 299} is considered a failure.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setStatus(404);
   * boolean failed = response.failure(); // => true
   * }</pre>
   *
   * @return {@code true} if the status code is not in the {@code 200}-{@code 299} range, {@code false} otherwise.
   */
  public boolean failure() {
    return status < 200 || status > 299;
  }

  /**
   * Flushes any buffered response (including the preamble) to the client. This is a force flush operation.
   * <p>
   * Because the preamble (status line and headers) is flushed, calling this commits the response. After this point the
   * status, headers, and cookies can no longer be changed.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.getWriter().write("partial");
   * response.flush(); // => status line and headers are sent; response is now committed
   * }</pre>
   *
   * @throws IOException If the socket throws.
   */
  public void flush() throws IOException {
    outputStream.forceFlush();
  }

  /**
   * Determines the character set by parsing the {@code Content-Type} header (if it exists) to pull out the
   * {@code charset} parameter.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setContentType("text/html; charset=ISO-8859-1");
   * Charset cs = response.getCharset(); // => ISO-8859-1
   * }</pre>
   *
   * @return The Charset or UTF-8 if it wasn't specified in the {@code Content-Type} header.
   */
  public Charset getCharset() {
    Charset charset = StandardCharsets.UTF_8;
    String contentType = getContentType();
    if (contentType != null) {
      HTTPTools.HeaderValue headerValue = HTTPTools.parseHeaderValue(contentType);
      String charsetName = headerValue.parameters().get(HTTPValues.ContentTypes.CharsetParameter);
      if (charsetName != null) {
        charset = Charset.forName(charsetName);
      }
    }

    return charset;
  }

  /**
   * Returns the value of the {@code Content-Length} response header parsed as a {@code Long}, or {@code null} if the
   * header has not been set.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setContentLength(1024);
   * Long len = response.getContentLength(); // => 1024
   * }</pre>
   *
   * @return The content length, or {@code null} if the {@code Content-Length} header is not present.
   */
  public Long getContentLength() {
    if (containsHeader(HTTPValues.Headers.ContentLength)) {
      return Long.parseLong(getHeader(HTTPValues.Headers.ContentLength));
    }

    return null;
  }

  /**
   * Sets the {@code Content-Length} response header, replacing any previously set value. When a content length is set
   * the server can send a fixed-length body rather than using chunked transfer encoding. This must be called before the
   * response is committed.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * byte[] body = ...;
   * response.setContentLength(body.length);
   * }</pre>
   *
   * @param length The number of bytes that will be written as the response body.
   */
  public void setContentLength(long length) {
    setHeader(HTTPValues.Headers.ContentLength, Long.toString(length));
  }

  /**
   * Returns the value of the {@code Content-Type} response header, or {@code null} if it has not been set.
   *
   * @return The {@code Content-Type} header value, or {@code null} if it is not present.
   */
  public String getContentType() {
    return getHeader(HTTPValues.Headers.ContentType);
  }

  /**
   * Sets the {@code Content-Type} response header, replacing any previously set value. This must be called before the
   * response is committed. The value may include a {@code charset} parameter, which {@link #getCharset()} and
   * {@link #getWriter()} use to encode text output.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setContentType("application/json; charset=UTF-8");
   * }</pre>
   *
   * @param contentType The media type to use for the response body.
   */
  public void setContentType(String contentType) {
    setHeader(HTTPValues.Headers.ContentType, contentType);
  }

  /**
   * Returns a flattened, immutable-style snapshot list of all cookies that have been added to this response across all
   * paths. The returned list is a new {@link List} and modifying it does not affect the response.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.addCookie(new Cookie("session", "abc"));
   * List<Cookie> cookies = response.getCookies(); // => [session=abc]
   * }</pre>
   *
   * @return A list of every {@link Cookie} that has been added to the response.
   */
  public List<Cookie> getCookies() {
    return cookies.values()
                  .stream()
                  .flatMap(map -> map.values().stream())
                  .collect(Collectors.toList());
  }

  /**
   * Returns the {@link Throwable} that was associated with this response, if the handler or server recorded one while
   * processing the request. This is informational and does not by itself change the status code.
   *
   * @return The recorded exception, or {@code null} if none was set.
   */
  public Throwable getException() {
    return exception;
  }

  /**
   * Records a {@link Throwable} that occurred while processing the request. This is used for diagnostics and logging;
   * setting it does not automatically change the response status.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * try {
   *   // handle request
   * } catch (Exception e) {
   *   response.setException(e);
   *   response.setStatus(500);
   * }
   * }</pre>
   *
   * @param exception The exception to associate with this response.
   */
  public void setException(Throwable exception) {
    this.exception = exception;
  }

  /**
   * Returns the first value of the named response header, or {@code null} if the header has not been set. The lookup is
   * case-insensitive because header names are stored lower-cased.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setContentType("text/plain");
   * String type = response.getHeader("content-type"); // => "text/plain"
   * }</pre>
   *
   * @param name The header name.
   * @return The first value for the header, or {@code null} if it is not present.
   */
  public String getHeader(String name) {
    String key = name.toLowerCase(Locale.ROOT);
    return headers.containsKey(key) && !headers.get(key).isEmpty() ? headers.get(key).getFirst() : null;
  }

  /**
   * Returns every value for the named response header, or {@code null} if the header has not been set. The lookup is
   * case-insensitive. The returned list is the live backing list; callers should not modify it directly.
   *
   * @param key The header name.
   * @return The list of values for the header, or {@code null} if it is not present.
   */
  public List<String> getHeaders(String key) {
    return headers.get(key.toLowerCase(Locale.ROOT));
  }

  /**
   * Returns the live map of all response headers, keyed by lower-cased header name. Mutating this map directly mutates
   * the response headers; prefer {@link #addHeader(String, String)} and {@link #setHeader(String, String)} instead.
   *
   * @return The backing map of header names to their list of values.
   */
  public Map<String, List<String>> getHeadersMap() {
    return headers;
  }

  /**
   * Returns the {@link OutputStream} used to write the response body. Writing the first byte to this stream commits the
   * response, after which the status, headers, and cookies can no longer be changed. Compression and chunking settings
   * must be configured before the first write.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setContentType("application/octet-stream");
   * response.getOutputStream().write(bytes);
   * }</pre>
   *
   * @return The response body output stream.
   */
  public OutputStream getOutputStream() {
    return outputStream;
  }

  /**
   * Sets the {@link HTTPOutputStream} that backs this response's body. This is called by the server when wiring up the
   * response and is not typically invoked by request handlers.
   *
   * @param outputStream The output stream to use for the response body.
   */
  public void setOutputStream(HTTPOutputStream outputStream) {
    this.outputStream = outputStream;
  }

  /**
   * Returns the value of the {@code Location} response header, which is the redirect target set by
   * {@link #sendRedirect(String)}, or {@code null} if no redirect has been set.
   *
   * @return The redirect URI from the {@code Location} header, or {@code null} if it is not present.
   */
  public String getRedirect() {
    return getHeader(HTTPValues.Headers.Location);
  }

  /**
   * Returns the HTTP status code for this response. The status defaults to {@code 200} until it is changed.
   *
   * @return The response status code.
   */
  public int getStatus() {
    return status;
  }

  /**
   * Sets the HTTP status code for this response. This must be called before the response is committed (before the first
   * byte of the body is written), otherwise it has no effect on what the client receives.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setStatus(404);
   * }</pre>
   *
   * @param status The HTTP status code to send to the client.
   */
  public void setStatus(int status) {
    this.status = status;
  }

  /**
   * Returns the custom status message (reason phrase) that will be sent on the status line, or {@code null} if the
   * default reason phrase for the status code should be used.
   *
   * @return The custom status message, or {@code null} if none was set.
   */
  public String getStatusMessage() {
    return statusMessage;
  }

  /**
   * Sets a custom status message (reason phrase) to send on the status line in place of the default phrase for the
   * status code. This must be set before the response is committed.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setStatus(418);
   * response.setStatusMessage("I'm a teapot");
   * }</pre>
   *
   * @param statusMessage The reason phrase to send on the status line.
   */
  public void setStatusMessage(String statusMessage) {
    this.statusMessage = statusMessage;
  }

  /**
   * Returns a {@link Writer} for writing the response body as text. The writer is created lazily on first call and
   * encodes characters using the charset from {@link #getCharset()} (derived from the {@code Content-Type} header), so
   * the {@code Content-Type} should be set before this is called. The same writer instance is returned on subsequent
   * calls. Writing through the writer commits the response.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setContentType("text/html; charset=UTF-8");
   * response.getWriter().write("<h1>Hello</h1>");
   * }</pre>
   *
   * @return The writer for the response body.
   */
  public Writer getWriter() {
    Charset charset = getCharset();
    if (writer == null) {
      writer = new OutputStreamWriter(getOutputStream(), charset);
    }

    return writer;
  }

  /**
   * Determines whether the response has been committed. Once committed, the status, headers, and cookies can no longer
   * be changed and {@link #reset()} will throw.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * if (!response.isCommitted()) {
   *   response.setStatus(500);
   * }
   * }</pre>
   *
   * @return True if the response has been committed, meaning at least one byte was written back to the client. False
   *     otherwise.
   */
  public boolean isCommitted() {
    return outputStream.isCommitted();
  }

  /**
   * Indicates whether compression is currently enabled for the response body. This reflects the configured intent set
   * via {@link #setCompress(boolean)}; whether compression is actually applied also depends on the request's accepted
   * encodings, which {@link #willCompress()} accounts for.
   *
   * @return {@code true} if compression will be utilized when writing the HTTP OutputStream.
   */
  public boolean isCompress() {
    return outputStream.isCompress();
  }

  /**
   * Provides runtime configuration for HTTP response compression. This can be called as many times as you wish prior to
   * the first byte being written to the HTTP OutputStream.
   * <p>
   * An {@link IllegalStateException} will be thrown if you call this method after writing to the OutputStream.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setCompress(true);
   * response.setContentType("application/json");
   * response.getOutputStream().write(bytes);
   * }</pre>
   *
   * @param compress {@code true} to enable the response to be written back compressed.
   */
  public void setCompress(boolean compress) {
    outputStream.setCompress(compress);
  }

  /**
   * Removes every cookie with the given name from this response, across all paths it may have been added under. This
   * only affects cookies that have not yet been written; it must be called before the response is committed.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.removeCookie("session");
   * }</pre>
   *
   * @param name The name of the cookie to remove.
   */
  public void removeCookie(String name) {
    cookies.values().forEach(map -> map.remove(name));
  }

  /**
   * Removes all values for the named header. The lookup is case-insensitive because header names are stored
   * lower-cased. A {@code null} name is ignored. This must be called before the response is committed.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.removeHeader("Content-Type");
   * }</pre>
   *
   * @param name The header name to remove.
   */
  public void removeHeader(String name) {
    if (name != null) {
      headers.remove(name.toLowerCase(Locale.ROOT));
    }
  }

  /**
   * Hard resets this response if it hasn't been committed yet. If the response has been committed back to the client,
   * this throws up.
   * <p>
   * Resetting clears all cookies and headers, clears any recorded exception, resets the underlying output stream,
   * discards the cached {@link Writer}, restores the status to {@code 200}, and clears the custom status message.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * if (!response.isCommitted()) {
   *   response.reset();
   *   response.setStatus(500);
   * }
   * }</pre>
   *
   * @throws IllegalStateException If the response has already been committed.
   */
  public void reset() {
    if (outputStream.isCommitted()) {
      throw new IllegalStateException("The HTTPResponse can't be reset after it has been committed, meaning at least one byte was written back to the client.");
    }

    cookies.clear();
    headers.clear();
    exception = null;
    outputStream.reset();
    status = 200;
    statusMessage = null;
    writer = null;
  }

  /**
   * Sends a redirect to the client using a status code of 302. This sets the {@code Location} header to the given URI
   * and the status to {@code 302}. It must be called before the response is committed.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.sendRedirect("/login");
   * }</pre>
   *
   * @param uri The URI to redirect to.
   */
  public void sendRedirect(String uri) {
    sendRedirect(uri, HTTPValues.Status.MovedTemporarily);
  }

  /**
   * Sends a redirect to the client using the given status code. This sets the {@code Location} header to the given URI
   * and the status to the supplied code. It must be called before the response is committed.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.sendRedirect("/new-home", 301);
   * }</pre>
   *
   * @param uri    The URI to redirect to.
   * @param status The status code to use.
   * @throws IllegalArgumentException If the status code is not in the {@code 300}-{@code 399} range.
   */
  public void sendRedirect(String uri, int status) {
    if (status < 300 || status > 399) {
      throw new IllegalArgumentException("Status code must be between 300 and 399.");
    }

    setHeader(HTTPValues.Headers.Location, uri);
    this.status = status;
  }

  /**
   * Adds a date header to the response using a ZonedDateTime object that is converted to an RFC 1123 date string.
   * <p>
   * This delegates to {@link #addHeader(String, String)}, so it appends a value rather than replacing existing ones. It
   * must be called before the response is committed.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setDateHeader("Last-Modified", ZonedDateTime.now());
   * }</pre>
   *
   * @param name  The name of the header.
   * @param value The date to use.
   */
  public void setDateHeader(String name, ZonedDateTime value) {
    addHeader(name, DateTimeFormatter.RFC_1123_DATE_TIME.format(value));
  }

  /**
   * Set the header, replacing any existing header values. If you wish to add to existing response headers, use the
   * {@link #addHeader(String, String)} instead.
   * <p>
   * If either parameter are null, the header will not be added to the response.
   * <p>
   * Header names are stored lower-cased internally. This must be called before the response is committed.
   *
   * <pre>{@code
   * HTTPResponse response = ...;
   * response.setHeader("Cache-Control", "no-cache");
   * }</pre>
   *
   * @param name  The header name.
   * @param value The header value.
   */
  public void setHeader(String name, String value) {
    if (name == null || value == null) {
      return;
    }

    headers.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(List.of(value)));
  }

  /**
   * Indicates whether the response body will actually be compressed when written. Unlike {@link #isCompress()}, which
   * only reports the configured intent, this also factors in everything currently known about the request and stream
   * state (such as the client's accepted encodings) to decide whether compression will really be applied.
   *
   * @return {@code true} if compression has been requested and, as far as we know, it will be applied.
   */
  public boolean willCompress() {
    return outputStream.willCompress();
  }
}


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

  private ProtocolSwitchHandler switchProtocolsHandler;
  private Map<String, String> switchProtocolsHeaders;
  private String switchProtocolsTarget;

  private Map<String, List<String>> trailers;

  private Writer writer;

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
   * If either parameter are null, the header will not be added to the response.
   *
   * @param name  the header name
   * @param value the header value
   */
  public void addHeader(String name, String value) {
    if (name == null || value == null) {
      return;
    }

    headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(value);
  }

  public void addTrailer(String name, String value) {
    rejectIfForbiddenTrailer(name);
    if (trailers == null) {
      trailers = new HashMap<>();
    }
    trailers.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(value);
  }

  public void clearHeaders() {
    headers.clear();
  }

  /**
   * Closes the HTTP response to ensure that the client is notified that the server is finished responding. This closes
   * the Writer or the OutputStream if they are available. The Writer is preferred if it exists so that it is properly
   * flushed.
   */
  public void close() throws IOException {
    if (writer != null) {
      writer.close();
    } else {
      outputStream.close();
    }
  }

  public boolean containsHeader(String name) {
    String key = name.toLowerCase(Locale.ROOT);
    return headers.containsKey(key) && !headers.get(key).isEmpty();
  }

  public boolean failure() {
    return status < 200 || status > 299;
  }

  /**
   * Flushes any buffered response (including the preamble) to the client. This is a force flush operation.
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

  public Long getContentLength() {
    if (containsHeader(HTTPValues.Headers.ContentLength)) {
      return Long.parseLong(getHeader(HTTPValues.Headers.ContentLength));
    }

    return null;
  }

  public void setContentLength(long length) {
    setHeader(HTTPValues.Headers.ContentLength, Long.toString(length));
  }

  public String getContentType() {
    return getHeader(HTTPValues.Headers.ContentType);
  }

  public void setContentType(String contentType) {
    setHeader(HTTPValues.Headers.ContentType, contentType);
  }

  public List<Cookie> getCookies() {
    return cookies.values()
                  .stream()
                  .flatMap(map -> map.values().stream())
                  .collect(Collectors.toList());
  }

  public Throwable getException() {
    return exception;
  }

  public void setException(Throwable exception) {
    this.exception = exception;
  }

  public String getHeader(String name) {
    String key = name.toLowerCase(Locale.ROOT);
    return headers.containsKey(key) && !headers.get(key).isEmpty() ? headers.get(key).getFirst() : null;
  }

  public List<String> getHeaders(String key) {
    return headers.get(key.toLowerCase(Locale.ROOT));
  }

  public Map<String, List<String>> getHeadersMap() {
    return headers;
  }

  public OutputStream getOutputStream() {
    return outputStream;
  }

  public void setOutputStream(HTTPOutputStream outputStream) {
    this.outputStream = outputStream;
  }

  public String getRedirect() {
    return getHeader(HTTPValues.Headers.Location);
  }

  public int getStatus() {
    return status;
  }

  public void setStatus(int status) {
    this.status = status;
  }

  public String getStatusMessage() {
    return statusMessage;
  }

  public void setStatusMessage(String statusMessage) {
    this.statusMessage = statusMessage;
  }

  public ProtocolSwitchHandler getSwitchProtocolsHandler() {
    return switchProtocolsHandler;
  }

  public Map<String, String> getSwitchProtocolsHeaders() {
    return switchProtocolsHeaders == null ? Map.of() : switchProtocolsHeaders;
  }

  public String getSwitchProtocolsTarget() {
    return switchProtocolsTarget;
  }

  public Map<String, List<String>> getTrailers() {
    return trailers == null ? Map.of() : trailers;
  }

  public Writer getWriter() {
    Charset charset = getCharset();
    if (writer == null) {
      writer = new OutputStreamWriter(getOutputStream(), charset);
    }

    return writer;
  }

  public boolean hasTrailers() {
    return trailers != null && !trailers.isEmpty();
  }

  /**
   * @return True if the response has been committed, meaning at least one byte was written back to the client. False
   *     otherwise.
   */
  public boolean isCommitted() {
    return outputStream.isCommitted();
  }

  /**
   * @return true if compression will be utilized when writing the HTTP OutputStream.
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
   * @param compress true to enable the response to be written back compressed.
   */
  public void setCompress(boolean compress) {
    outputStream.setCompress(compress);
  }

  public boolean isProtocolSwitchPending() {
    return switchProtocolsHandler != null;
  }

  public void removeCookie(String name) {
    cookies.values().forEach(map -> map.remove(name));
  }

  /**
   * Remove a header by name.
   *
   * @param name the header name to remove
   */
  public void removeHeader(String name) {
    if (name != null) {
      headers.remove(name.toLowerCase(Locale.ROOT));
    }
  }

  /**
   * Hard resets this response if it hasn't been committed yet. If the response has been committed back to the client,
   * this throws up.
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
   * Sends a redirect to the client using a status code of 302.
   *
   * @param uri The URI to redirect to.
   */
  public void sendRedirect(String uri) {
    sendRedirect(uri, HTTPValues.Status.MovedTemporarily);
  }

  /**
   * Sends a redirect to the client using the given status code.
   *
   * @param uri    The URI to redirect to.
   * @param status The status code to use.
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
   *
   * @param name  the header name
   * @param value the header value
   */
  public void setHeader(String name, String value) {
    if (name == null || value == null) {
      return;
    }

    headers.put(name.toLowerCase(Locale.ROOT), new ArrayList<>(List.of(value)));
  }

  public void setTrailer(String name, String value) {
    rejectIfForbiddenTrailer(name);
    if (trailers == null) {
      trailers = new HashMap<>();
    }
    List<String> list = new ArrayList<>(1);
    list.add(value);
    trailers.put(name.toLowerCase(Locale.ROOT), list);
  }

  /**
   * Records the intent to perform a protocol switch. The worker will emit a {@code 101 Switching Protocols} response
   * preamble and then hand the raw socket to the supplied handler. Normal response writing is bypassed — the handler
   * owns the socket from that point on.
   *
   * @param protocol          the protocol token for the {@code Upgrade} response header.
   * @param additionalHeaders extra headers to include in the 101 preamble, or {@code null} for none.
   * @param handler           the handler that will take ownership of the socket after the 101 is flushed.
   */
  public void switchProtocols(String protocol, Map<String, String> additionalHeaders, ProtocolSwitchHandler handler) {
    if (protocol == null || protocol.isEmpty()) {
      throw new IllegalArgumentException("Protocol name must not be empty");
    }
    if (handler == null) {
      throw new IllegalArgumentException("Handler must not be null");
    }
    if (additionalHeaders != null) {
      for (String name : additionalHeaders.keySet()) {
        if (name.equalsIgnoreCase("Connection") || name.equalsIgnoreCase("Upgrade")) {
          throw new IllegalArgumentException("Header [" + name + "] is set automatically by switchProtocols and must not appear in additionalHeaders");
        }
      }
    }
    this.switchProtocolsTarget = protocol;
    this.switchProtocolsHeaders = additionalHeaders;
    this.switchProtocolsHandler = handler;
  }

  /**
   * @return true if compression has been requested and as far as we know, we will.
   */
  public boolean willCompress() {
    return outputStream.willCompress();
  }

  private void rejectIfForbiddenTrailer(String name) {
    if (HTTPValues.ForbiddenTrailers.Names.contains(name.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("Header name [" + name + "] is forbidden as a trailer per RFC 9110 §6.5.2");
    }
  }
}


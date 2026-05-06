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
package org.lattejava.http.util;

import module java.base;
import module org.lattejava.http;

import org.lattejava.http.ParseException;
import org.lattejava.http.io.PushbackInputStream;

public final class HTTPTools {
  private static final boolean[] TOKEN_CHARS = buildTokenCharTable();

  private static Logger logger;

  /**
   * Return the maximum request body size for the requested content type.
   *
   * @param contentType        the content-type of the request
   * @param maxRequestBodySize the maximum request size configuration
   * @return the maximum request size, or -1 if no limit should be enforced.
   */
  public static int getMaxRequestBodySize(String contentType, Map<String, Integer> maxRequestBodySize) {
    if (contentType == null) {
      return maxRequestBodySize.get("*");
    }

    // Exact match
    contentType = contentType.toLowerCase(Locale.ROOT);
    Integer maximumSize = maxRequestBodySize.get(contentType);
    if (maximumSize != null) {
      return maximumSize;
    }

    // Ignore subtype by replacing it with '*'. RFC 1341 says a subtype is required which means each Content-Type must contain a /.
    // - But be more defensive, and account for a case where no subtype has been defined.
    int index = contentType.indexOf('/');
    if (index != -1) {
      maximumSize = maxRequestBodySize.get(contentType.substring(0, index) + "/*");
    }

    // RFC 1341 indicates subtypes cannot be nested. So if we do not yet have a match, use the default key '*'.
    return maximumSize != null
        ? maximumSize
        : maxRequestBodySize.get("*");
  }

  /**
   * Statically sets up the logger, mostly for trace logging.
   *
   * @param loggerFactory The logger factory.
   */
  public static void initialize(LoggerFactory loggerFactory) {
    HTTPTools.logger = loggerFactory.getLogger(HTTPTools.class);
  }

  /**
   * @param ch The character as a since HTTP is ASCII
   * @return True if the character is an ASCII control character.
   */
  public static boolean isControlCharacter(byte ch) {
    return ch >= 0 && ch <= 31;
  }

  /**
   * Determines if the given character (byte) is a digit (i.e. 0-9)
   *
   * @param ch The character as a byte since HTTP is ASCII.
   * @return True if the character is a digit.
   */
  public static boolean isDigitCharacter(byte ch) {
    return ch >= '0' && ch <= '9';
  }

  /**
   * Determines if the given character (byte) is an allowed hexadecimal character (i.e. 0-9a-zA-Z)
   *
   * @param ch The character as a byte since HTTP is ASCII.
   * @return True if the character is a hexadecimal character.
   */
  public static boolean isHexadecimalCharacter(byte ch) {
    return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F');
  }

  /**
   * Determines if the given character (byte) is an allowed HTTP token character (header field names, methods, etc).
   * <p>
   * Covered by <a
   * href="https://www.rfc-editor.org/rfc/rfc9110.html#name-fields">https://www.rfc-editor.org/rfc/rfc9110.html#name-fields</a>
   *
   * @param ch The character as a byte since HTTP is ASCII.
   * @return True if the character is a token character.
   */
  public static boolean isTokenCharacter(byte ch) {
    return ch == '!' || ch == '#' || ch == '$' || ch == '%' || ch == '&' || ch == '\'' || ch == '*' || ch == '+' || ch == '-' || ch == '.' ||
        ch == '^' || ch == '_' || ch == '`' || ch == '|' || ch == '~' || (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') ||
        (ch >= '0' && ch <= '9');
  }

  /**
   * Naively determines if the given character (byte) is an allowed URI character.
   *
   * @param ch The character as a byte since URIs are ASCII.
   * @return True if the character is a URI character.
   */
  public static boolean isURICharacter(byte ch) {
    return ch >= '!' && ch <= '~';
  }

  // RFC 9110 §5.5 field-content = field-vchar [ 1*( SP / HTAB / field-vchar ) field-vchar ], where field-vchar = VCHAR / obs-text. Bare
  // CR and bare LF are excluded — accepting LF here would let an attacker splice a second header into a field value, and a front-end proxy
  // that splits on LF would disagree with us on header boundaries. See docs/security/audit-2026-04-20.md Vuln 3.
  public static boolean isValueCharacter(byte ch) {
    int intVal = ch & 0xFF;  // Convert the value into an integer without extending the sign bit.
    return isURICharacter(ch) || intVal == ' ' || intVal == '\t' || intVal >= 0x80;
  }

  /**
   * Build a {@link ParseException} that can be thrown.
   *
   * @param b     the byte that caused the exception
   * @param state the current parser state
   * @return a throwable exception
   */
  public static ParseException makeParseException(byte b, Enum<? extends Enum<?>> state) {
    // Trying to print a control characters can mess up the logging format.
    var hex = HexFormat.of().withUpperCase().formatHex(new byte[]{b});
    String message = HTTPTools.isControlCharacter(b)
        ? "Unexpected character. Dec [" + b + "] Hex [" + hex + "]"
        : "Unexpected character. Dec [" + b + "] Hex [" + hex + "] Symbol [" + ((char) b) + "]";
    return new ParseException(message + " Parse state [" + state + "]", state.name());
  }

  /**
   * Parses URL encoded data either from a URL parameter list in the query string or the form body.
   *
   * @param data    The data as a character array.
   * @param start   The start index to start parsing from.
   * @param length  The length to parse.
   * @param charset The charset used to decode the key and value. May be null, and will default to UTF-8.
   * @param result  The result Map to put the value into.
   */
  public static void parseEncodedData(byte[] data, int start, int length, Charset charset, Map<String, List<String>> result) {
    // Default to UTF-8
    if (charset == null) {
      charset = StandardCharsets.UTF_8;
    }

    boolean inName = true;
    String name = null;
    String value;
    for (int i = start; i < length; i++) {
      if (data[i] == '=' && inName) {
        // Names can't start with an equal sign
        if (i == start) {
          start++;
          continue;
        }

        inName = false;

        try {
          name = URLDecoder.decode(new String(data, start, i - start, charset), charset);
        } catch (Exception e) {
          name = null; // Malformed
        }

        start = i + 1;
      } else if (data[i] == '&' && !inName) {
        inName = true;

        if (name == null || start > i) {
          continue; // Malformed
        }

        //noinspection DuplicatedCode
        try {
          if (start < i) {
            value = URLDecoder.decode(new String(data, start, i - start, charset), charset);
          } else {
            value = "";
          }

          result.computeIfAbsent(name, key -> new LinkedList<>()).add(value);
        } catch (Exception ignore) {
          // Ignore
        }

        start = i + 1;
        name = null;
      }
    }

    if (name != null && !inName) {
      //noinspection DuplicatedCode
      try {
        if (start < length) {
          value = URLDecoder.decode(new String(data, start, length - start, charset), charset);
        } else {
          value = "";
        }

        result.computeIfAbsent(name, key -> new LinkedList<>()).add(value);
      } catch (Exception ignore) {
        // Ignore
      }
    }
  }

  /**
   * Parses URL encoded data either from a URL parameter list in the query string or the form body. Assumes the values
   * are UTF-8 encoded.
   *
   * @param data   The data as a character array.
   * @param start  The start index to start parsing from.
   * @param length The length to parse.
   * @param result The result Map to put the value into.
   */
  public static void parseEncodedData(byte[] data, int start, int length, Map<String, List<String>> result) {
    parseEncodedData(data, start, length, StandardCharsets.UTF_8, result);
  }

  /**
   * Parses an HTTP header value that is a standard semicolon separated list of values.
   *
   * @param value The header value.
   * @return The HeaderValue record.
   */
  public static HeaderValue parseHeaderValue(String value) {
    String headerValue = null;
    Map<String, String> parameters = null;
    char[] chars = value.toCharArray();
    boolean inQuote = false;
    int start = 0;
    for (int i = 0; i < chars.length; i++) {
      char c = chars[i];
      if (!inQuote && c == ';') {
        if (headerValue == null) {
          headerValue = new String(chars, start, i - start);
        } else {
          if (parameters == null) {
            parameters = new HashMap<>();
          }

          parseHeaderParameter(chars, start, i, parameters);
        }

        start = -1;
      } else if (!inQuote && !Character.isWhitespace(c) && start == -1) {
        start = i;
      } else if (!inQuote && c == '"') {
        inQuote = true;
      } else if (inQuote && c == '\\' && i < chars.length - 2 && chars[i + 1] == '"') {
        i++; // Skip the next quote character since it is escaped
      } else if (inQuote && c == '"') {
        inQuote = false;
      }
    }

    // Add any final part
    if (start != -1) {
      if (headerValue == null) {
        headerValue = new String(chars, start, chars.length - start);
      } else {
        if (parameters == null) {
          parameters = new HashMap<>();
        }

        parseHeaderParameter(chars, start, chars.length, parameters);
      }
    }

    if (parameters == null) {
      parameters = Map.of();
    }

    return new HeaderValue(headerValue, parameters);
  }

  /**
   * Parses the request preamble directly from the given InputStream.
   * <p>
   * The HTTP request is made up of the request line, headers and an optional body. The request preamble comprises the
   * Request line and the Headers. All that remains after the preamble is the optional body.
   *
   * @param inputStream          The input stream to read the preamble from.
   * @param maxRequestHeaderSize The maximum number of bytes to read for the header. If exceed throw an exception.
   * @param request              The HTTP request to populate.
   * @param requestBuffer        A buffer used for reading to help reduce memory thrashing.
   * @param readObserver         An observer that is called once one byte has been read.
   * @throws IOException If the read fails.
   */
  public static void parseRequestPreamble(PushbackInputStream inputStream, int maxRequestHeaderSize, HTTPRequest request,
                                          byte[] requestBuffer, Runnable readObserver)
      throws IOException {
    RequestPreambleState state = RequestPreambleState.RequestMethod;
    // Local byte[]+int instead of ByteArrayOutputStream. Same per-request allocation pattern (one byte[] backing the value buffer) but
    // without the wrapper object's synchronized write(int) and method-dispatch overhead. JFR profiling showed BAOS.write(int) at ~12% of
    // parseRequestPreamble's CPU time. Capacity grows by doubling on overflow, mirroring BAOS behavior.
    byte[] valueBuffer = new byte[512];
    int valueLen = 0;
    String headerName = null;

    int read = 0;
    int index = 0;
    int premableLength = 0;

    while (state != RequestPreambleState.Complete) {
      long start = System.currentTimeMillis();
      read = inputStream.read(requestBuffer);

      // We have not yet reached the end of the preamble. If there are no more bytes to read, the connection must have been closed by the client.
      if (read < 0) {
        long waited = System.currentTimeMillis() - start;
        throw new ConnectionClosedException(String.format("Read returned [%d] after waiting [%d] ms", read, waited));
      }

      logger.trace("Read [{}] from client for preamble.", read);

      // Tell the callback that we've read at least one byte
      if (premableLength == 0) {
        readObserver.run();
      }

      for (index = 0; index < read && state != RequestPreambleState.Complete; index++) {
        // If there is a state transition, store the value properly and reset the buffer (if needed)
        byte ch = requestBuffer[index];
        RequestPreambleState nextState = state.next(ch);
        if (nextState != state) {
          switch (state) {
            case RequestMethod -> request.setMethod(HTTPMethod.of(new String(valueBuffer, 0, valueLen, StandardCharsets.UTF_8)));
            case RequestPath -> request.setPath(new String(valueBuffer, 0, valueLen, StandardCharsets.UTF_8));
            case RequestProtocol -> request.setProtocol(new String(valueBuffer, 0, valueLen, StandardCharsets.UTF_8));
            case HeaderName -> headerName = new String(valueBuffer, 0, valueLen, StandardCharsets.UTF_8);
            case HeaderValue -> request.addHeader(headerName, new String(valueBuffer, 0, valueLen, StandardCharsets.UTF_8));
          }

          // If the next state is storing, reset the buffer and seed it with the transition byte.
          if (nextState.store()) {
            valueLen = 0;
            valueBuffer[valueLen++] = ch;
          }
        } else if (state.store()) {
          if (valueLen == valueBuffer.length) {
            valueBuffer = Arrays.copyOf(valueBuffer, valueBuffer.length * 2);
          }
          valueBuffer[valueLen++] = ch;
        }

        state = nextState;
      }

      // index is the number of bytes we processed as part of the preamble
      premableLength += index;
      if (maxRequestHeaderSize != -1 && premableLength > maxRequestHeaderSize) {
        throw new RequestHeadersTooLargeException(maxRequestHeaderSize, "The maximum size of the request header has been exceeded. The maximum size is [" + maxRequestHeaderSize + "] bytes.");
      }
    }

    // Push back the leftover bytes
    if (index < read) {
      inputStream.push(requestBuffer, index, read - index);
    }
  }

  /**
   * Validates that a cookie value, domain, or path contains no CR, LF, NUL, or {@code ;}. The first three split the
   * HTTP response; a semicolon would inject an additional cookie attribute (e.g., {@code Secure},
   * {@code Domain=attacker.example}).
   *
   * @param value     The value to validate.
   * @param fieldName A human-readable description of the field, used in the exception message.
   * @throws IllegalArgumentException If the value contains CR, LF, NUL, or {@code ;}.
   */
  public static void validateResponseCookieAttribute(String value, String fieldName) {
    if (value == null) {
      return;
    }

    // String.indexOf(char) is a HotSpot intrinsic (vectorized on compact strings), materially faster than a per-char charAt loop on the
    // common path where no bad chars are present.
    int cr = value.indexOf('\r');
    int lf = value.indexOf('\n');
    int nul = value.indexOf('\0');
    int sc = value.indexOf(';');
    if (cr < 0 && lf < 0 && nul < 0 && sc < 0) {
      return;
    }

    int badIdx = firstNonNegativeMin(cr, lf, nul, sc);
    throw makeInvalidFieldCharException(value.charAt(badIdx), badIdx, fieldName);
  }

  /**
   * Validates that a string intended for a response field value (header value, status message, redirect URI) contains
   * no CR, LF, or NUL. Any of these would split the HTTP response and allow an attacker to forge headers or additional
   * responses.
   *
   * @param value     The value to validate.
   * @param fieldName A human-readable description of the field, used in the exception message.
   * @throws IllegalArgumentException If the value contains CR, LF, or NUL.
   */
  public static void validateResponseFieldValue(String value, String fieldName) {
    if (value == null) {
      return;
    }

    int cr = value.indexOf('\r');
    int lf = value.indexOf('\n');
    int nul = value.indexOf('\0');
    if (cr < 0 && lf < 0 && nul < 0) {
      return;
    }

    int badIdx = firstNonNegativeMin(cr, lf, nul);
    throw makeInvalidFieldCharException(value.charAt(badIdx), badIdx, fieldName);
  }

  /**
   * Validates that a response header name contains only RFC 7230 tchar characters, rejecting CR, LF, NUL, colon,
   * whitespace, and any non-ASCII byte. A caller-supplied name that bypasses this check would enable HTTP response
   * header injection.
   *
   * @param name The header name to validate.
   * @throws IllegalArgumentException If the name is empty or contains any non-token character.
   */
  public static void validateResponseHeaderName(String name) {
    if (name == null) {
      return;
    }

    if (name.isEmpty()) {
      throw new IllegalArgumentException("Response header name must not be empty.");
    }

    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (c >= 128 || !TOKEN_CHARS[c]) {
        throw new IllegalArgumentException("Invalid character [0x" + String.format("%02X", (int) c) + "] at index [" + i + "] in response header name.");
      }
    }
  }

  /**
   * Writes the HTTP response head section (status line, headers, etc).
   *
   * @param response     The response.
   * @param outputStream The output stream to write the preamble to.
   * @throws IOException If the stream threw an exception.
   */
  public static void writeResponsePreamble(HTTPResponse response, OutputStream outputStream) throws IOException {
    // Single choke point for HTTP response-splitting defense (see docs/security/audit-2026-04-20.md Vuln 4). Every surface that can put
    // attacker-influenced bytes into the response preamble — status message, header names/values, cookie components — is validated here
    // before any byte is written. If a bad value is found, the IAE propagates to the worker's catch-all, which resets the response and
    // returns a clean 500, rather than flushing a partially-written (and splittable) preamble.
    validateResponseFieldValue(response.getStatusMessage(), "status message");
    for (var entry : response.getHeadersMap().entrySet()) {
      String name = entry.getKey();
      validateResponseHeaderName(name);
      for (String value : entry.getValue()) {
        validateResponseFieldValue(value, "value of response header [" + name + "]");
      }
    }

    var cookies = response.getCookies();
    for (var cookie : cookies) {
      validateResponseHeaderName(cookie.name);
      validateResponseCookieAttribute(cookie.value, "value of cookie [" + cookie.name + "]");
      validateResponseCookieAttribute(cookie.domain, "domain of cookie [" + cookie.name + "]");
      validateResponseCookieAttribute(cookie.path, "path of cookie [" + cookie.name + "]");
    }

    writeStatusLine(response, outputStream);

    // Explicit UTF-8 instead of platform-default getBytes(): same bytes on a UTF-8-default JVM (the dominant case) but portable across
    // non-UTF-8 defaults. For ASCII-only header tokens — the dominant traffic — HotSpot takes the compact-string fast path and skips the
    // encoder loop. Preserves existing behavior for non-ASCII header values, which is asserted by the utf8HeaderValues test.
    for (var headers : response.getHeadersMap().entrySet()) {
      String name = headers.getKey();
      for (String value : headers.getValue()) {
        outputStream.write(name.getBytes(StandardCharsets.UTF_8));
        outputStream.write(HTTPValues.ControlBytes.ColonSpace);
        outputStream.write(value.getBytes(StandardCharsets.UTF_8));
        outputStream.write(HTTPValues.ControlBytes.CRLF);
      }
    }

    for (var cookie : cookies) {
      outputStream.write(HTTPValues.HeaderBytes.SetCookie);
      outputStream.write(HTTPValues.ControlBytes.ColonSpace);
      outputStream.write(cookie.toResponseHeader().getBytes(StandardCharsets.UTF_8));
      outputStream.write(HTTPValues.ControlBytes.CRLF);
    }

    outputStream.write(HTTPValues.ControlBytes.CRLF);
  }

  private static boolean[] buildTokenCharTable() {
    boolean[] table = new boolean[128];
    for (int c = 0; c < 128; c++) {
      table[c] = isTokenCharacter((byte) c);
    }
    return table;
  }

  private static int firstNonNegativeMin(int... values) {
    int min = Integer.MAX_VALUE;
    for (int v : values) {
      if (v >= 0 && v < min) {
        min = v;
      }
    }
    return min;
  }

  private static IllegalArgumentException makeInvalidFieldCharException(char c, int index, String fieldName) {
    return new IllegalArgumentException("Invalid character [0x" + String.format("%02X", (int) c) + "] at index [" + index + "] in " + fieldName + ".");
  }

  private static void parseHeaderParameter(char[] chars, int start, int end, Map<String, String> parameters) {
    boolean encoded = false;
    Charset charset = null;
    String name = null;
    for (int i = start; i < end; i++) {
      if (name == null && chars[i] == '*') {
        encoded = true;
        name = new String(chars, start, i - start).toLowerCase(Locale.ROOT);
        start = i + 2;
      } else if (name == null && chars[i] == '=') {
        name = new String(chars, start, i - start).toLowerCase(Locale.ROOT);
        start = i + 1;
      } else if (name != null && encoded && charset == null && chars[i] == '\'') {
        String charsetName = new String(chars, start, i - start);
        try {
          charset = Charset.forName(charsetName);
        } catch (IllegalCharsetNameException e) {
          charset = StandardCharsets.UTF_8; // Fallback to UTF-8
        }
        start = i + 1;
      } else if (name != null && encoded && charset != null && chars[i] == '\'') {
        start = i + 1;
      }
    }

    // This is an invalid parameter, but we won't fail here
    if (start >= end) {
      if (name != null) {
        parameters.put(name, "");
      }

      return;
    }

    if (chars[start] == '"') {
      start++;
    }

    if (chars[end - 1] == '"') {
      end--;
    }

    String encodedValue = new String(chars, start, end - start);
    String value = URLDecoder.decode(encodedValue, Objects.requireNonNullElse(charset, StandardCharsets.UTF_8));

    if (name == null) {
      name = value;
      value = "";
    }

    // Prefer the encoded version
    if (!parameters.containsKey(name) || encoded) {
      parameters.put(name, value);
    }
  }

  /**
   * Writes out the status line to the given OutputStream.
   *
   * @param response The response to pull the status information from.
   * @param out      The OutputStream.
   * @throws IOException If the stream threw an exception.
   */
  private static void writeStatusLine(HTTPResponse response, OutputStream out) throws IOException {
    out.write(HTTPValues.ProtocolBytes.HTTTP1_1);
    out.write(' ');
    out.write(Integer.toString(response.getStatus()).getBytes(StandardCharsets.UTF_8));
    out.write(' ');
    if (response.getStatusMessage() != null) {
      out.write(response.getStatusMessage().getBytes(StandardCharsets.UTF_8));
    }
    out.write(HTTPValues.ControlBytes.CRLF);
  }

  /**
   * A record that stores a parameterized header value.
   *
   * @param value      The initial value of the header.
   * @param parameters The parameters.
   */
  public record HeaderValue(String value, Map<String, String> parameters) {
  }
}

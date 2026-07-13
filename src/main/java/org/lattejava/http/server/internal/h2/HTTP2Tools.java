/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal.h2;

import module java.base;
import module org.lattejava.http;

import java.lang.System.Logger.Level;

import org.lattejava.http.server.internal.*;

/**
 * Stateless HTTP/2 helpers extracted from the connection: SETTINGS negotiation, RFC 9113 §8.1.2 header validation,
 * and HTTPRequest construction from a decoded header list. All methods are pure given their parameters.
 */
public final class HTTP2Tools {
  private static final System.Logger logger = System.getLogger(HTTP2Tools.class.getName());

  private HTTP2Tools() {
  }

  /**
   * Runs the server side of the SETTINGS exchange: sends the local SETTINGS (with the connection-window advertisement
   * coalesced into the same flight when {@code connectionWindowSize > 65_535}), requires the peer's first frame to be
   * a non-ACK SETTINGS, applies it, and ACKs. Never emits error frames and never touches the socket — a
   * {@link HTTP2Result.ConnectionError} tells the caller which GOAWAY to send. IOExceptions from a dead peer
   * propagate; there is nobody to send a GOAWAY to.
   */
  public static HTTP2Result negotiateSettings(HTTP2FrameReader reader, HTTP2FrameWriter frameWriter, OutputStream out,
                                              HTTP2Settings localSettings, HTTP2Settings peerSettings,
                                              int connectionWindowSize)
      throws IOException {
    // Send the settings over to the client, but don't flush
    frameWriter.writeFrame(new HTTP2Frame.SettingsFrame(0, localSettings.toPayload()));

    // RFC 9113 §6.9.2 — the connection window is only adjustable via WINDOW_UPDATE, and the grant is causally
    // independent of the peer's SETTINGS, so it rides the first flight coalesced with the server preface. Go, nginx,
    // and browsers all advertise the same way.
    if (connectionWindowSize > 65_535) {
      frameWriter.writeFrame(new HTTP2Frame.WindowUpdateFrame(0, connectionWindowSize - 65_535));
    }
    out.flush();

    HTTP2Frame firstFrame;
    try {
      firstFrame = reader.readFrame();
    } catch (HTTP2FrameReader.FrameSizeException e) {
      logger.log(Level.DEBUG, "Frame size violation before SETTINGS: [{0}]", e.getMessage());
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.FRAME_SIZE_ERROR);
    } catch (HTTP2FrameReader.HeaderListSizeException e) {
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.ENHANCE_YOUR_CALM);
    } catch (HTTP2FrameReader.ProtocolException e) {
      logger.log(Level.DEBUG, "Protocol violation before SETTINGS: [{0}]", e.getMessage());
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR);
    }

    if (!(firstFrame instanceof HTTP2Frame.SettingsFrame(int flags, byte[] payload)) || (flags & HTTP2Frame.FLAG_ACK) != 0) {
      logger.log(Level.DEBUG, "Expected client SETTINGS frame after preface");
      return new HTTP2Result.ConnectionError(HTTP2ErrorCode.PROTOCOL_ERROR);
    }

    try {
      peerSettings.applyPayload(payload);
    } catch (HTTP2Settings.HTTP2SettingsException e) {
      logger.log(Level.DEBUG, "Invalid client SETTINGS: [{0}]", e.getMessage());
      return new HTTP2Result.ConnectionError(e.errorCode);
    }

    frameWriter.writeFrame(new HTTP2Frame.SettingsFrame(HTTP2Frame.FLAG_ACK, new byte[0]));
    out.flush();
    return HTTP2Result.OK;
  }

  /**
   * Validates the decoded header list per RFC 9113 §8.1.2.* and §8.2.1. Returns {@code true} if valid. On any
   * violation returns {@code false}; the caller is responsible for emitting RST_STREAM(PROTOCOL_ERROR).
   */
  public static boolean validateHeaders(List<HPACKDynamicTable.HeaderField> fields, boolean isTrailer) {
    boolean seenRegularHeader = false;
    Set<String> seenPseudo = new HashSet<>();

    for (var f : fields) {
      String name = f.name();

      // §8.2.1: a field name must be a non-empty token (HPACK happily encodes a zero-length name string).
      if (name.isEmpty()) {
        return false;
      }

      // §8.1.2/1: header names MUST be lowercase.
      for (int i = 0; i < name.length(); i++) {
        char c = name.charAt(i);
        if (c >= 'A' && c <= 'Z') {
          return false;
        }
      }

      // §8.2.1: field values MUST NOT contain NUL, CR, or LF at any position, and MUST NOT start or end with SP or
      // HTAB. HPACK strings are length-prefixed — there is no OWS grammar in the binary encoding, so edge whitespace
      // cannot be produced by a compliant sender and is treated as malformed rather than trimmed. Embedded CR/LF/NUL
      // is the header-injection primitive for anything that logs fields or re-serializes them to HTTP/1.1.
      String value = f.value();
      int valueLength = value.length();
      if (valueLength > 0) {
        char first = value.charAt(0);
        char last = value.charAt(valueLength - 1);
        if (first == ' ' || first == '\t' || last == ' ' || last == '\t') {
          return false;
        }
      }
      for (int i = 0; i < valueLength; i++) {
        char c = value.charAt(i);
        if (c == 0 || c == '\r' || c == '\n') {
          return false;
        }
      }

      boolean isPseudo = name.startsWith(":");
      if (isPseudo) {
        // §8.1.2.1/3: pseudo-headers are forbidden in trailers.
        if (isTrailer) {
          return false;
        }
        // §8.1.2.1/4: pseudo-header after a regular header.
        if (seenRegularHeader) {
          return false;
        }
        // §8.1.2.1/1 + §8.1.2.1/2: unknown pseudo-header or response pseudo-header in request.
        if (!HTTPValues.Headers.RequestPseudoHeaders.contains(name)) {
          return false;
        }
        // §8.1.2.3/5–7: pseudo-headers MUST NOT appear more than once.
        if (!seenPseudo.add(name)) {
          return false;
        }
      } else {
        seenRegularHeader = true;
        // §8.2.1: field names must be valid tokens (RFC 9110 §5.1) — HPACK can encode arbitrary bytes in the name
        // string. Pseudo-headers are exempt (":" is not a token character); they are validated by whitelist above.
        for (int i = 0; i < name.length(); i++) {
          char c = name.charAt(i);
          if (c > '~' || !HTTPTools.isTokenCharacter((byte) c)) {
            return false;
          }
        }
        // §8.1.2.2/1: connection-specific headers are forbidden.
        if (HTTPValues.Headers.ConnectionSpecificHeaders.contains(name)) {
          return false;
        }
        // §8.1.2.2/2: TE header may only contain "trailers".
        if (name.equals("te") && !f.value().equalsIgnoreCase("trailers")) {
          return false;
        }
      }
    }

    if (!isTrailer) {
      // §8.1.2.3/2,3,4: required request pseudo-headers must be present.
      if (!seenPseudo.contains(":method")) {
        return false;
      }
      if (!seenPseudo.contains(":scheme")) {
        return false;
      }
      if (!seenPseudo.contains(":path")) {
        return false;
      }
      // §8.1.2.3/1: :path must not be empty.
      for (var f : fields) {
        if (f.name().equals(":path") && f.value().isEmpty()) {
          return false;
        }
      }
    }

    return true;
  }

  /**
   * Builds an {@link HTTPRequest} from a decoded HPACK header list. Scheme, port, and remote address are passed
   * explicitly so this method has no dependency on the listener or socket.
   */
  public static HTTPRequest buildRequest(List<HPACKDynamicTable.HeaderField> fields, HTTPContext context,
                                         String contextPath, String scheme, int port, String remoteAddress) {
    HTTPRequest req = new HTTPRequest(context, contextPath, scheme, port, remoteAddress);
    req.setProtocol("HTTP/2.0");
    for (var field : fields) {
      String name = field.name();
      String value = field.value();
      switch (name) {
        case ":method" -> req.setMethod(HTTPMethod.of(value));
        case ":path" -> req.setPath(value); // setPath handles query-string splitting internally
        case ":scheme" -> {
        } // Scheme arrives as a parameter (derived by the caller from the listener's TLS state); pseudo-header recorded but not applied
        case ":authority" -> req.addHeader("Host", value);
        default -> req.addHeader(name, value);
      }
    }
    return req;
  }
}

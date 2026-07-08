/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.util;

import module java.base;

/**
 * Resumable finite-state-machine parser for an HTTP/1.1 field block — a sequence of
 * {@code field-name ":" OWS field-value OWS CRLF} lines terminated by a bare CRLF. Shared by the request-header,
 * chunked-trailer, and multipart part-header parse paths.
 *
 * <p>Implements the HTTP/1.1 field-line grammar (RFC 9110 §5): field names are tokens
 * ({@link HTTPTools#isTokenCharacter}) and field values are sequences of field-vchars
 * ({@link HTTPTools#isValueCharacter}). The OWS (any mix of SP and HTAB) surrounding the value is not part of the
 * value and is stripped before emission. A field is emitted to the {@link FieldConsumer} once per non-empty value, at
 * the value-to-CR transition; a field whose value is empty (or nothing but OWS) never enters the {@code Value} state
 * and is therefore dropped, mirroring the request-preamble parser.
 *
 * <p>The parser is fed buffer slices via {@link #feed}; its state and partial name/value accumulators persist across
 * calls, so a field — or the whole block — may span any number of feeds. The caller owns the buffer and the read loop.
 *
 * @author Brian Pontarelli
 */
public final class HTTPFieldParser {
  private long bytesConsumed;
  private byte[] nameBuffer = new byte[64];
  private int nameLength;
  private FieldState state = FieldState.Start;
  private byte[] valueBuffer = new byte[256];
  private int valueLength;

  /**
   * @return The total number of bytes consumed across all {@link #feed} calls. Used by the request-preamble path to
   *     enforce the maximum header size.
   */
  public long bytesConsumed() {
    return bytesConsumed;
  }

  /**
   * Drives the FSM over {@code buffer[offset, offset + length)}, emitting each completed field to {@code consumer}.
   * Stops at the terminating bare CRLF ({@link #isComplete()} becomes {@code true}) or at the end of the slice.
   *
   * @param buffer   The source buffer.
   * @param offset   The start index to read from.
   * @param length   The number of bytes available from {@code offset}.
   * @param consumer The sink for completed fields.
   * @return The number of bytes consumed from the slice.
   */
  public int feed(byte[] buffer, int offset, int length, FieldConsumer consumer) {
    int index = offset;
    int end = offset + length;
    FieldState current = state;
    for (; index < end && current != FieldState.Complete; index++) {
      byte ch = buffer[index];
      FieldState next = current.next(ch);
      if (next != current) {
        // The only transition out of Value is Value -> FieldCR, so this fires exactly once per non-empty field.
        if (current == FieldState.Value) {
          // Strip trailing OWS — it is not part of the value. The value cannot become empty here: leading OWS never
          // enters the buffer (the Colon state skips it), so the first byte is always a non-OWS value character.
          int trimmedLength = valueLength;
          while (valueBuffer[trimmedLength - 1] == ' ' || valueBuffer[trimmedLength - 1] == '\t') {
            trimmedLength--;
          }

          consumer.field(
              new String(nameBuffer, 0, nameLength, StandardCharsets.UTF_8),
              new String(valueBuffer, 0, trimmedLength, StandardCharsets.UTF_8)
          );
        }

        // Seed the destination buffer when entering a storing state. The buffers are never zero-length, so the first
        // write after the reset needs no bounds check.
        if (next == FieldState.Name) {
          nameLength = 0;
          nameBuffer[nameLength++] = ch;
        } else if (next == FieldState.Value) {
          valueLength = 0;
          valueBuffer[valueLength++] = ch;
        }
      } else if (current == FieldState.Name) {
        if (nameLength == nameBuffer.length) {
          nameBuffer = Arrays.copyOf(nameBuffer, nameBuffer.length * 2);
        }

        nameBuffer[nameLength++] = ch;
      } else if (current == FieldState.Value) {
        if (valueLength == valueBuffer.length) {
          valueBuffer = Arrays.copyOf(valueBuffer, valueBuffer.length * 2);
        }

        valueBuffer[valueLength++] = ch;
      }
      current = next;
    }

    state = current;

    int consumed = index - offset;
    bytesConsumed += consumed;

    return consumed;
  }

  /**
   * @return {@code true} once the terminating bare CRLF has been consumed.
   */
  public boolean isComplete() {
    return state == FieldState.Complete;
  }

  private enum FieldState {
    Start {
      @Override
      FieldState next(byte ch) {
        if (ch == '\r') {
          return BlockCR;
        } else if (HTTPTools.isTokenCharacter(ch)) {
          return Name;
        }
        
        throw HTTPTools.makeParseException(ch, this);
      }
    },

    Name {
      @Override
      FieldState next(byte ch) {
        if (HTTPTools.isTokenCharacter(ch)) {
          return Name;
        } else if (ch == ':') {
          return Colon;
        }

        throw HTTPTools.makeParseException(ch, this);
      }
    },

    Colon {
      @Override
      FieldState next(byte ch) {
        // OWS = *( SP / HTAB ) — both are value characters, so this check must come first for the skip to win.
        if (ch == ' ' || ch == '\t') {
          return Colon;
        } else if (ch == '\r') {
          return FieldCR;
        } else if (HTTPTools.isValueCharacter(ch)) {
          return Value;
        }

        throw HTTPTools.makeParseException(ch, this);
      }
    },

    Value {
      @Override
      FieldState next(byte ch) {
        if (ch == '\r') {
          return FieldCR;
        } else if (HTTPTools.isValueCharacter(ch)) {
          return Value;
        }

        throw HTTPTools.makeParseException(ch, this);
      }
    },

    FieldCR {
      @Override
      FieldState next(byte ch) {
        if (ch == '\n') {
          return Start;
        }

        throw HTTPTools.makeParseException(ch, this);
      }
    },

    BlockCR {
      @Override
      FieldState next(byte ch) {
        if (ch == '\n') {
          return Complete;
        }

        throw HTTPTools.makeParseException(ch, this);
      }
    },

    Complete {
      @Override
      FieldState next(byte ch) {
        return Complete;
      }
    };

    abstract FieldState next(byte ch);
  }
}

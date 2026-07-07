/*
 * Copyright (c) 2022-2026, FusionAuth, All Rights Reserved
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

import static org.lattejava.http.util.HTTPTools.*;

/**
 * Finite state machine parser for the start-line (request line) of an HTTP/1.1 request. The header block is parsed
 * separately by {@link HTTPFieldParser}.
 *
 * @author Brian Pontarelli
 */
public enum RequestPreambleState {
  RequestMethod {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == ' ') {
        return RequestMethodSP;
      } else if (HTTPTools.isTokenCharacter(ch)) {
        return RequestMethod;
      }

      throw makeParseException(ch, this);
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  RequestMethodSP {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == ' ') {
        return RequestMethodSP;
      } else if (HTTPTools.isURICharacter(ch)) {
        return RequestPath;
      }

      throw makeParseException(ch, this);
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  RequestPath {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == ' ') {
        return RequestPathSP;
      } else if (HTTPTools.isURICharacter(ch)) {
        return RequestPath;
      }

      throw makeParseException(ch, this);
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  RequestPathSP {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == ' ') {
        return RequestPathSP;
      } else if (HTTPTools.isURICharacter(ch)) {
        return RequestProtocol;
      }

      throw makeParseException(ch, this);
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  RequestProtocol {
    @Override
    public RequestPreambleState next(byte ch) {
      // While this server only supports HTTP/1.1, allow the request protocol to be parsed for any valid version.
      // - The supported version will be validated elsewhere.
      if (ch == 'H' || ch == 'T' || ch == 'P' || ch == '/' || ch == '.' || (ch >= '0' && ch <= '9')) {
        return RequestProtocol;
      } else if (ch == '\r') {
        return RequestCR;
      }

      throw makeParseException(ch, this);
    }

    @Override
    public boolean store() {
      return true;
    }
  },

  RequestCR {
    @Override
    public RequestPreambleState next(byte ch) {
      if (ch == '\n') {
        return RequestLF;
      }

      throw makeParseException(ch, this);
    }

    @Override
    public boolean store() {
      return false;
    }
  },

  RequestLF {
    @Override
    public RequestPreambleState next(byte ch) {
      // Terminal: the request line is complete. parseRequestPreamble hands the header block to HTTPFieldParser and never
      // calls next() on this state.
      return null;
    }

    @Override
    public boolean store() {
      return false;
    }
  };

  public abstract RequestPreambleState next(byte ch);

  public abstract boolean store();
}

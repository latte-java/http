/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

import module java.base;

/**
 * RFC 7541 Appendix B HPACK static Huffman code. Encodes/decodes byte sequences using the fixed code table.
 *
 * @author Daniel DeGroff
 */
public final class HPACKHuffman {
  // RFC 7541 Appendix B — 256 byte symbols plus EOS (index 256).
  private static final int[] CODES = new int[257];
  private static final int[] LENGTHS = new int[257];

  static {
    // RFC 7541 Appendix B full table (symbol, hex code, bit length)
    CODES[  0] = 0x1ff8;       LENGTHS[  0] = 13;
    CODES[  1] = 0x7fffd8;     LENGTHS[  1] = 23;
    CODES[  2] = 0xfffffe2;    LENGTHS[  2] = 28;
    CODES[  3] = 0xfffffe3;    LENGTHS[  3] = 28;
    CODES[  4] = 0xfffffe4;    LENGTHS[  4] = 28;
    CODES[  5] = 0xfffffe5;    LENGTHS[  5] = 28;
    CODES[  6] = 0xfffffe6;    LENGTHS[  6] = 28;
    CODES[  7] = 0xfffffe7;    LENGTHS[  7] = 28;
    CODES[  8] = 0xfffffe8;    LENGTHS[  8] = 28;
    CODES[  9] = 0xffffea;     LENGTHS[  9] = 24;
    CODES[ 10] = 0x3ffffffc;   LENGTHS[ 10] = 30;
    CODES[ 11] = 0xfffffe9;    LENGTHS[ 11] = 28;
    CODES[ 12] = 0xfffffea;    LENGTHS[ 12] = 28;
    CODES[ 13] = 0x3ffffffd;   LENGTHS[ 13] = 30;
    CODES[ 14] = 0xfffffeb;    LENGTHS[ 14] = 28;
    CODES[ 15] = 0xfffffec;    LENGTHS[ 15] = 28;
    CODES[ 16] = 0xfffffed;    LENGTHS[ 16] = 28;
    CODES[ 17] = 0xfffffee;    LENGTHS[ 17] = 28;
    CODES[ 18] = 0xfffffef;    LENGTHS[ 18] = 28;
    CODES[ 19] = 0xffffff0;    LENGTHS[ 19] = 28;
    CODES[ 20] = 0xffffff1;    LENGTHS[ 20] = 28;
    CODES[ 21] = 0xffffff2;    LENGTHS[ 21] = 28;
    CODES[ 22] = 0x3ffffffe;   LENGTHS[ 22] = 30;
    CODES[ 23] = 0xffffff3;    LENGTHS[ 23] = 28;
    CODES[ 24] = 0xffffff4;    LENGTHS[ 24] = 28;
    CODES[ 25] = 0xffffff5;    LENGTHS[ 25] = 28;
    CODES[ 26] = 0xffffff6;    LENGTHS[ 26] = 28;
    CODES[ 27] = 0xffffff7;    LENGTHS[ 27] = 28;
    CODES[ 28] = 0xffffff8;    LENGTHS[ 28] = 28;
    CODES[ 29] = 0xffffff9;    LENGTHS[ 29] = 28;
    CODES[ 30] = 0xffffffa;    LENGTHS[ 30] = 28;
    CODES[ 31] = 0xffffffb;    LENGTHS[ 31] = 28;
    CODES[ 32] = 0x14;         LENGTHS[ 32] = 6;   // ' '
    CODES[ 33] = 0x3f8;        LENGTHS[ 33] = 10;  // '!'
    CODES[ 34] = 0x3f9;        LENGTHS[ 34] = 10;  // '"'
    CODES[ 35] = 0xffa;        LENGTHS[ 35] = 12;  // '#'
    CODES[ 36] = 0x1ff9;       LENGTHS[ 36] = 13;  // '$'
    CODES[ 37] = 0x15;         LENGTHS[ 37] = 6;   // '%'
    CODES[ 38] = 0xf8;         LENGTHS[ 38] = 8;   // '&'
    CODES[ 39] = 0x7fa;        LENGTHS[ 39] = 11;  // '\''
    CODES[ 40] = 0x3fa;        LENGTHS[ 40] = 10;  // '('
    CODES[ 41] = 0x3fb;        LENGTHS[ 41] = 10;  // ')'
    CODES[ 42] = 0xf9;         LENGTHS[ 42] = 8;   // '*'
    CODES[ 43] = 0x7fb;        LENGTHS[ 43] = 11;  // '+'
    CODES[ 44] = 0xfa;         LENGTHS[ 44] = 8;   // ','
    CODES[ 45] = 0x16;         LENGTHS[ 45] = 6;   // '-'
    CODES[ 46] = 0x17;         LENGTHS[ 46] = 6;   // '.'
    CODES[ 47] = 0x18;         LENGTHS[ 47] = 6;   // '/'
    CODES[ 48] = 0x0;          LENGTHS[ 48] = 5;   // '0'
    CODES[ 49] = 0x1;          LENGTHS[ 49] = 5;   // '1'
    CODES[ 50] = 0x2;          LENGTHS[ 50] = 5;   // '2'
    CODES[ 51] = 0x19;         LENGTHS[ 51] = 6;   // '3'
    CODES[ 52] = 0x1a;         LENGTHS[ 52] = 6;   // '4'
    CODES[ 53] = 0x1b;         LENGTHS[ 53] = 6;   // '5'
    CODES[ 54] = 0x1c;         LENGTHS[ 54] = 6;   // '6'
    CODES[ 55] = 0x1d;         LENGTHS[ 55] = 6;   // '7'
    CODES[ 56] = 0x1e;         LENGTHS[ 56] = 6;   // '8'
    CODES[ 57] = 0x1f;         LENGTHS[ 57] = 6;   // '9'
    CODES[ 58] = 0x5c;         LENGTHS[ 58] = 7;   // ':'
    CODES[ 59] = 0xfb;         LENGTHS[ 59] = 8;   // ';'
    CODES[ 60] = 0x7ffc;       LENGTHS[ 60] = 15;  // '<'
    CODES[ 61] = 0x20;         LENGTHS[ 61] = 6;   // '='
    CODES[ 62] = 0xffb;        LENGTHS[ 62] = 12;  // '>'
    CODES[ 63] = 0x3fc;        LENGTHS[ 63] = 10;  // '?'
    CODES[ 64] = 0x1ffa;       LENGTHS[ 64] = 13;  // '@'
    CODES[ 65] = 0x21;         LENGTHS[ 65] = 6;   // 'A'
    CODES[ 66] = 0x5d;         LENGTHS[ 66] = 7;   // 'B'
    CODES[ 67] = 0x5e;         LENGTHS[ 67] = 7;   // 'C'
    CODES[ 68] = 0x5f;         LENGTHS[ 68] = 7;   // 'D'
    CODES[ 69] = 0x60;         LENGTHS[ 69] = 7;   // 'E'
    CODES[ 70] = 0x61;         LENGTHS[ 70] = 7;   // 'F'
    CODES[ 71] = 0x62;         LENGTHS[ 71] = 7;   // 'G'
    CODES[ 72] = 0x63;         LENGTHS[ 72] = 7;   // 'H'
    CODES[ 73] = 0x64;         LENGTHS[ 73] = 7;   // 'I'
    CODES[ 74] = 0x65;         LENGTHS[ 74] = 7;   // 'J'
    CODES[ 75] = 0x66;         LENGTHS[ 75] = 7;   // 'K'
    CODES[ 76] = 0x67;         LENGTHS[ 76] = 7;   // 'L'
    CODES[ 77] = 0x68;         LENGTHS[ 77] = 7;   // 'M'
    CODES[ 78] = 0x69;         LENGTHS[ 78] = 7;   // 'N'
    CODES[ 79] = 0x6a;         LENGTHS[ 79] = 7;   // 'O'
    CODES[ 80] = 0x6b;         LENGTHS[ 80] = 7;   // 'P'
    CODES[ 81] = 0x6c;         LENGTHS[ 81] = 7;   // 'Q'
    CODES[ 82] = 0x6d;         LENGTHS[ 82] = 7;   // 'R'
    CODES[ 83] = 0x6e;         LENGTHS[ 83] = 7;   // 'S'
    CODES[ 84] = 0x6f;         LENGTHS[ 84] = 7;   // 'T'
    CODES[ 85] = 0x70;         LENGTHS[ 85] = 7;   // 'U'
    CODES[ 86] = 0x71;         LENGTHS[ 86] = 7;   // 'V'
    CODES[ 87] = 0x72;         LENGTHS[ 87] = 7;   // 'W'
    CODES[ 88] = 0xfc;         LENGTHS[ 88] = 8;   // 'X'
    CODES[ 89] = 0x73;         LENGTHS[ 89] = 7;   // 'Y'
    CODES[ 90] = 0xfd;         LENGTHS[ 90] = 8;   // 'Z'
    CODES[ 91] = 0x1ffb;       LENGTHS[ 91] = 13;  // '['
    CODES[ 92] = 0x7fff0;      LENGTHS[ 92] = 19;  // '\'
    CODES[ 93] = 0x1ffc;       LENGTHS[ 93] = 13;  // ']'
    CODES[ 94] = 0x3ffc;       LENGTHS[ 94] = 14;  // '^'
    CODES[ 95] = 0x22;         LENGTHS[ 95] = 6;   // '_'
    CODES[ 96] = 0x7ffd;       LENGTHS[ 96] = 15;  // '`'
    CODES[ 97] = 0x3;          LENGTHS[ 97] = 5;   // 'a'
    CODES[ 98] = 0x23;         LENGTHS[ 98] = 6;   // 'b'
    CODES[ 99] = 0x4;          LENGTHS[ 99] = 5;   // 'c'
    CODES[100] = 0x24;         LENGTHS[100] = 6;   // 'd'
    CODES[101] = 0x5;          LENGTHS[101] = 5;   // 'e'
    CODES[102] = 0x25;         LENGTHS[102] = 6;   // 'f'
    CODES[103] = 0x26;         LENGTHS[103] = 6;   // 'g'
    CODES[104] = 0x27;         LENGTHS[104] = 6;   // 'h'
    CODES[105] = 0x6;          LENGTHS[105] = 5;   // 'i'
    CODES[106] = 0x74;         LENGTHS[106] = 7;   // 'j'
    CODES[107] = 0x75;         LENGTHS[107] = 7;   // 'k'
    CODES[108] = 0x28;         LENGTHS[108] = 6;   // 'l'
    CODES[109] = 0x29;         LENGTHS[109] = 6;   // 'm'
    CODES[110] = 0x2a;         LENGTHS[110] = 6;   // 'n'
    CODES[111] = 0x7;          LENGTHS[111] = 5;   // 'o'
    CODES[112] = 0x2b;         LENGTHS[112] = 6;   // 'p'
    CODES[113] = 0x76;         LENGTHS[113] = 7;   // 'q'
    CODES[114] = 0x2c;         LENGTHS[114] = 6;   // 'r'
    CODES[115] = 0x8;          LENGTHS[115] = 5;   // 's'
    CODES[116] = 0x9;          LENGTHS[116] = 5;   // 't'
    CODES[117] = 0x2d;         LENGTHS[117] = 6;   // 'u'
    CODES[118] = 0x77;         LENGTHS[118] = 7;   // 'v'
    CODES[119] = 0x78;         LENGTHS[119] = 7;   // 'w'
    CODES[120] = 0x79;         LENGTHS[120] = 7;   // 'x'
    CODES[121] = 0x7a;         LENGTHS[121] = 7;   // 'y'
    CODES[122] = 0x7b;         LENGTHS[122] = 7;   // 'z'
    CODES[123] = 0x7ffe;       LENGTHS[123] = 15;  // '{'
    CODES[124] = 0x7fc;        LENGTHS[124] = 11;  // '|'
    CODES[125] = 0x3ffd;       LENGTHS[125] = 14;  // '}'
    CODES[126] = 0x1ffd;       LENGTHS[126] = 13;  // '~'
    CODES[127] = 0xffffffc;    LENGTHS[127] = 28;
    CODES[128] = 0xfffe6;      LENGTHS[128] = 20;
    CODES[129] = 0x3fffd2;     LENGTHS[129] = 22;
    CODES[130] = 0xfffe7;      LENGTHS[130] = 20;
    CODES[131] = 0xfffe8;      LENGTHS[131] = 20;
    CODES[132] = 0x3fffd3;     LENGTHS[132] = 22;
    CODES[133] = 0x3fffd4;     LENGTHS[133] = 22;
    CODES[134] = 0x3fffd5;     LENGTHS[134] = 22;
    CODES[135] = 0x7fffd9;     LENGTHS[135] = 23;
    CODES[136] = 0x3fffd6;     LENGTHS[136] = 22;
    CODES[137] = 0x7fffda;     LENGTHS[137] = 23;
    CODES[138] = 0x7fffdb;     LENGTHS[138] = 23;
    CODES[139] = 0x7fffdc;     LENGTHS[139] = 23;
    CODES[140] = 0x7fffdd;     LENGTHS[140] = 23;
    CODES[141] = 0x7fffde;     LENGTHS[141] = 23;
    CODES[142] = 0xffffeb;     LENGTHS[142] = 24;
    CODES[143] = 0x7fffdf;     LENGTHS[143] = 23;
    CODES[144] = 0xffffec;     LENGTHS[144] = 24;
    CODES[145] = 0xffffed;     LENGTHS[145] = 24;
    CODES[146] = 0x3fffd7;     LENGTHS[146] = 22;
    CODES[147] = 0x7fffe0;     LENGTHS[147] = 23;
    CODES[148] = 0xffffee;     LENGTHS[148] = 24;
    CODES[149] = 0x7fffe1;     LENGTHS[149] = 23;
    CODES[150] = 0x7fffe2;     LENGTHS[150] = 23;
    CODES[151] = 0x7fffe3;     LENGTHS[151] = 23;
    CODES[152] = 0x7fffe4;     LENGTHS[152] = 23;
    CODES[153] = 0x1fffdc;     LENGTHS[153] = 21;
    CODES[154] = 0x3fffd8;     LENGTHS[154] = 22;
    CODES[155] = 0x7fffe5;     LENGTHS[155] = 23;
    CODES[156] = 0x3fffd9;     LENGTHS[156] = 22;
    CODES[157] = 0x7fffe6;     LENGTHS[157] = 23;
    CODES[158] = 0x7fffe7;     LENGTHS[158] = 23;
    CODES[159] = 0xffffef;     LENGTHS[159] = 24;
    CODES[160] = 0x3fffda;     LENGTHS[160] = 22;
    CODES[161] = 0x1fffdd;     LENGTHS[161] = 21;
    CODES[162] = 0xfffe9;      LENGTHS[162] = 20;
    CODES[163] = 0x3fffdb;     LENGTHS[163] = 22;
    CODES[164] = 0x3fffdc;     LENGTHS[164] = 22;
    CODES[165] = 0x7fffe8;     LENGTHS[165] = 23;
    CODES[166] = 0x7fffe9;     LENGTHS[166] = 23;
    CODES[167] = 0x1fffde;     LENGTHS[167] = 21;
    CODES[168] = 0x7fffea;     LENGTHS[168] = 23;
    CODES[169] = 0x3fffdd;     LENGTHS[169] = 22;
    CODES[170] = 0x3fffde;     LENGTHS[170] = 22;
    CODES[171] = 0xfffff0;     LENGTHS[171] = 24;
    CODES[172] = 0x1fffdf;     LENGTHS[172] = 21;
    CODES[173] = 0x3fffdf;     LENGTHS[173] = 22;
    CODES[174] = 0x7fffeb;     LENGTHS[174] = 23;
    CODES[175] = 0x7fffec;     LENGTHS[175] = 23;
    CODES[176] = 0x1fffe0;     LENGTHS[176] = 21;
    CODES[177] = 0x1fffe1;     LENGTHS[177] = 21;
    CODES[178] = 0x3fffe0;     LENGTHS[178] = 22;
    CODES[179] = 0x1fffe2;     LENGTHS[179] = 21;
    CODES[180] = 0x7fffed;     LENGTHS[180] = 23;
    CODES[181] = 0x3fffe1;     LENGTHS[181] = 22;
    CODES[182] = 0x7fffee;     LENGTHS[182] = 23;
    CODES[183] = 0x7fffef;     LENGTHS[183] = 23;
    CODES[184] = 0xfffea;      LENGTHS[184] = 20;
    CODES[185] = 0x3fffe2;     LENGTHS[185] = 22;
    CODES[186] = 0x3fffe3;     LENGTHS[186] = 22;
    CODES[187] = 0x3fffe4;     LENGTHS[187] = 22;
    CODES[188] = 0x7ffff0;     LENGTHS[188] = 23;
    CODES[189] = 0x3fffe5;     LENGTHS[189] = 22;
    CODES[190] = 0x3fffe6;     LENGTHS[190] = 22;
    CODES[191] = 0x7ffff1;     LENGTHS[191] = 23;
    CODES[192] = 0x3ffffe0;    LENGTHS[192] = 26;
    CODES[193] = 0x3ffffe1;    LENGTHS[193] = 26;
    CODES[194] = 0xfffeb;      LENGTHS[194] = 20;
    CODES[195] = 0x7fff1;      LENGTHS[195] = 19;
    CODES[196] = 0x3fffe7;     LENGTHS[196] = 22;
    CODES[197] = 0x7ffff2;     LENGTHS[197] = 23;
    CODES[198] = 0x3fffe8;     LENGTHS[198] = 22;
    CODES[199] = 0x1ffffec;    LENGTHS[199] = 25;
    CODES[200] = 0x3ffffe2;    LENGTHS[200] = 26;
    CODES[201] = 0x3ffffe3;    LENGTHS[201] = 26;
    CODES[202] = 0x3ffffe4;    LENGTHS[202] = 26;
    CODES[203] = 0x7ffffde;    LENGTHS[203] = 27;
    CODES[204] = 0x7ffffdf;    LENGTHS[204] = 27;
    CODES[205] = 0x3ffffe5;    LENGTHS[205] = 26;
    CODES[206] = 0xfffff1;     LENGTHS[206] = 24;
    CODES[207] = 0x1ffffed;    LENGTHS[207] = 25;
    CODES[208] = 0x7fff2;      LENGTHS[208] = 19;
    CODES[209] = 0x1fffe3;     LENGTHS[209] = 21;
    CODES[210] = 0x3ffffe6;    LENGTHS[210] = 26;
    CODES[211] = 0x7ffffe0;    LENGTHS[211] = 27;
    CODES[212] = 0x7ffffe1;    LENGTHS[212] = 27;
    CODES[213] = 0x3ffffe7;    LENGTHS[213] = 26;
    CODES[214] = 0x7ffffe2;    LENGTHS[214] = 27;
    CODES[215] = 0xfffff2;     LENGTHS[215] = 24;
    CODES[216] = 0x1fffe4;     LENGTHS[216] = 21;
    CODES[217] = 0x1fffe5;     LENGTHS[217] = 21;
    CODES[218] = 0x3ffffe8;    LENGTHS[218] = 26;
    CODES[219] = 0x3ffffe9;    LENGTHS[219] = 26;
    CODES[220] = 0xffffffd;    LENGTHS[220] = 28;
    CODES[221] = 0x7ffffe3;    LENGTHS[221] = 27;
    CODES[222] = 0x7ffffe4;    LENGTHS[222] = 27;
    CODES[223] = 0x7ffffe5;    LENGTHS[223] = 27;
    CODES[224] = 0xfffec;      LENGTHS[224] = 20;
    CODES[225] = 0xfffff3;     LENGTHS[225] = 24;
    CODES[226] = 0xfffed;      LENGTHS[226] = 20;
    CODES[227] = 0x1fffe6;     LENGTHS[227] = 21;
    CODES[228] = 0x3fffe9;     LENGTHS[228] = 22;
    CODES[229] = 0x1fffe7;     LENGTHS[229] = 21;
    CODES[230] = 0x1fffe8;     LENGTHS[230] = 21;
    CODES[231] = 0x7ffff3;     LENGTHS[231] = 23;
    CODES[232] = 0x3fffea;     LENGTHS[232] = 22;
    CODES[233] = 0x3fffeb;     LENGTHS[233] = 22;
    CODES[234] = 0x1ffffee;    LENGTHS[234] = 25;
    CODES[235] = 0x1ffffef;    LENGTHS[235] = 25;
    CODES[236] = 0xfffff4;     LENGTHS[236] = 24;
    CODES[237] = 0xfffff5;     LENGTHS[237] = 24;
    CODES[238] = 0x3ffffea;    LENGTHS[238] = 26;
    CODES[239] = 0x7ffff4;     LENGTHS[239] = 23;
    CODES[240] = 0x3ffffeb;    LENGTHS[240] = 26;
    CODES[241] = 0x7ffffe6;    LENGTHS[241] = 27;
    CODES[242] = 0x3ffffec;    LENGTHS[242] = 26;
    CODES[243] = 0x3ffffed;    LENGTHS[243] = 26;
    CODES[244] = 0x7ffffe7;    LENGTHS[244] = 27;
    CODES[245] = 0x7ffffe8;    LENGTHS[245] = 27;
    CODES[246] = 0x7ffffe9;    LENGTHS[246] = 27;
    CODES[247] = 0x7ffffea;    LENGTHS[247] = 27;
    CODES[248] = 0x7ffffeb;    LENGTHS[248] = 27;
    CODES[249] = 0xfffffe0;    LENGTHS[249] = 28;
    CODES[250] = 0x7ffffec;    LENGTHS[250] = 27;
    CODES[251] = 0x7ffffed;    LENGTHS[251] = 27;
    CODES[252] = 0x7ffffee;    LENGTHS[252] = 27;
    CODES[253] = 0x7ffffef;    LENGTHS[253] = 27;
    CODES[254] = 0x7fffff0;    LENGTHS[254] = 27;
    CODES[255] = 0xfffffe1;    LENGTHS[255] = 28;
    // EOS
    CODES[256] = 0x3fffffff;   LENGTHS[256] = 30;
  }

  private HPACKHuffman() {}

  public static byte[] decode(byte[] input) {
    var out = new ByteArrayOutputStream();
    long acc = 0;
    int bits = 0;
    int i = 0;
    outer:
    while (i < input.length || bits >= 5) {
      while (bits < 30 && i < input.length) {
        acc = (acc << 8) | (input[i] & 0xFF);
        bits += 8;
        i++;
      }
      // Try to match a symbol from MSB of acc.
      for (int sym = 0; sym < 256; sym++) {
        int len = LENGTHS[sym];
        if (len <= bits) {
          int candidate = (int) ((acc >> (bits - len)) & ((1L << len) - 1));
          if (candidate == CODES[sym]) {
            out.write(sym);
            bits -= len;
            continue outer;
          }
        }
      }
      // Could be padding (EOS prefix is all 1s). Verify remaining bits are all 1s.
      if (bits > 0 && bits < 8) {
        long padMask = (1L << bits) - 1;
        long pad = acc & padMask;
        if (pad != padMask) {
          throw new IllegalArgumentException("Invalid Huffman padding (must be EOS prefix all-1s)");
        }
        break;
      }
      throw new IllegalArgumentException("Invalid Huffman encoding: cannot match symbol at bits remaining [" + bits + "]");
    }
    return out.toByteArray();
  }

  public static byte[] encode(byte[] input) {
    long acc = 0;
    int bits = 0;
    var out = new ByteArrayOutputStream();
    for (byte b : input) {
      int sym = b & 0xFF;
      acc = (acc << LENGTHS[sym]) | CODES[sym];
      bits += LENGTHS[sym];
      while (bits >= 8) {
        bits -= 8;
        out.write((int) ((acc >> bits) & 0xFF));
      }
    }
    if (bits > 0) {
      // Pad with EOS prefix (all 1-bits).
      acc = (acc << (8 - bits)) | ((1L << (8 - bits)) - 1);
      out.write((int) (acc & 0xFF));
    }
    return out.toByteArray();
  }
}

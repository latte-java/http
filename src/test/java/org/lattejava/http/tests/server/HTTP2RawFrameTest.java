/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HPACKDecoder;
import org.lattejava.http.server.internal.HPACKDynamicTable;

import static org.testng.Assert.*;

/**
 * Raw frame conformance tests for HTTP/2. Each test sends specific frame sequences over a raw h2c socket and asserts
 * RFC 9113 conformance at the connection level: correct GOAWAY error codes or correct transparent-ignore behavior.
 *
 * <p>Frame helpers are inlined for self-containment (rather than relying on a shared base class that does not yet
 * expose these utilities).
 *
 * @author Daniel DeGroff
 */
public class HTTP2RawFrameTest extends BaseTest {
  /**
   * Minimal HPACK block representing a valid GET / request over HTTP/2. Uses only indexed header field
   * representations from the static table (RFC 7541 §6.1):
   * <ul>
   *   <li>{@code 0x82} — index 2: {@code :method: GET}</li>
   *   <li>{@code 0x84} — index 4: {@code :path: /}</li>
   *   <li>{@code 0x86} — index 6: {@code :scheme: https}</li>
   *   <li>{@code 0x41, 0x0f, ...} — literal {@code :authority: localhost}</li>
   * </ul>
   * Passing this as the HEADERS payload gives the server a decodable header block, which is required for tests
   * that need to exercise post-HEADERS logic (e.g. stream-id ordering).
   */
  private static final byte[] MINIMAL_HPACK_GET = {
      (byte) 0x82,                          // :method: GET
      (byte) 0x84,                          // :path: /
      (byte) 0x86,                          // :scheme: https → server accepts either scheme; using literal below instead
      // :authority: localhost (literal with indexing, name from static table index 1)
      (byte) 0x41, 0x09,
      'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
  };

  /**
   * Open an h2c prior-knowledge connection and return the socket after the handshake is complete (server
   * SETTINGS and SETTINGS ACK have been drained).
   */
  private Socket openH2cConnection(int port) throws Exception {
    var sock = new Socket("127.0.0.1", port);
    var out = sock.getOutputStream();
    out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes());
    out.write(new byte[]{0, 0, 0, 0x4, 0, 0, 0, 0, 0});  // empty SETTINGS
    out.flush();

    var in = sock.getInputStream();
    // Drain server SETTINGS.
    byte[] header = in.readNBytes(9);
    int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
    in.readNBytes(length);
    // Drain SETTINGS ACK.
    in.readNBytes(9);
    return sock;
  }

  /**
   * Write a 9-byte frame header.
   */
  private void writeFrameHeader(OutputStream out, int length, int type, int flags, int streamId) throws Exception {
    out.write(new byte[]{
        (byte) ((length >> 16) & 0xFF), (byte) ((length >> 8) & 0xFF), (byte) (length & 0xFF),
        (byte) type, (byte) flags,
        (byte) ((streamId >> 24) & 0x7F), (byte) ((streamId >> 16) & 0xFF),
        (byte) ((streamId >> 8) & 0xFF), (byte) (streamId & 0xFF)
    });
  }

  /**
   * Drain inbound frames until GOAWAY (type {@code 0x7}) arrives or the connection closes. Returns the GOAWAY
   * error code, or {@code -1} if EOF arrived first.
   */
  private int readUntilGoaway(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      int read = in.readNBytes(rest, 0, 8);
      if (read != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      byte[] payload = in.readNBytes(length);
      if (type == 0x7) {  // GOAWAY
        if (payload.length < 8) {
          return -1;
        }
        return ((payload[4] & 0xFF) << 24) | ((payload[5] & 0xFF) << 16) | ((payload[6] & 0xFF) << 8) | (payload[7] & 0xFF);
      }
    }
  }

  /**
   * Read and discard frames until a HEADERS frame (type {@code 0x1}) arrives. Returns the stream-id of the
   * response HEADERS frame, or {@code -1} on EOF.
   */
  private int readUntilResponseHeaders(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      int read = in.readNBytes(rest, 0, 8);
      if (read != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      int streamId = ((rest[4] & 0x7F) << 24) | ((rest[5] & 0xFF) << 16) | ((rest[6] & 0xFF) << 8) | (rest[7] & 0xFF);
      in.readNBytes(length);
      if (type == 0x1) {  // HEADERS
        return streamId;
      }
    }
  }

  /**
   * RFC 9113 §8.4 — clients MUST NOT send PUSH_PROMISE frames. The server must respond with
   * {@code GOAWAY(PROTOCOL_ERROR)} (error code {@code 0x1}).
   */
  @Test
  public void push_promise_inbound_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // PUSH_PROMISE: type=0x5, stream-id=1, promised-stream-id=2 (4-byte prefix) + empty header block.
        // Total payload = 4 bytes (promised stream-id only, no header block).
        writeFrameHeader(out, 4, 0x5, 0x4 /* END_HEADERS */, 1);
        out.write(new byte[]{0, 0, 0, 2});  // promised stream id = 2
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for inbound PUSH_PROMISE; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §4.3 / §6.10 — a header block whose encoded payload exceeds the peer's SETTINGS_MAX_FRAME_SIZE MUST be
   * split across one HEADERS frame plus one or more CONTINUATION frames. Emitting a single oversize HEADERS frame
   * either (a) crashes the writer (current bug — AIOOBE in HTTP2FrameWriter) or (b) violates FRAME_SIZE_ERROR.
   */
  @Test
  public void large_response_headers_fragmented_into_continuation() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      // 250 unique headers × ~150 bytes each — encoded HPACK block will exceed peer's default 16384 max-frame-size.
      for (int i = 0; i < 250; i++) {
        res.setHeader("x-bulk-" + i, "v".repeat(150));
      }
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        var in = sock.getInputStream();
        boolean headersStarted = false;
        boolean endHeadersSeen = false;
        int continuationCount = 0;
        while (!endHeadersSeen) {
          int b0 = in.read();
          if (b0 == -1) {
            fail("EOF before response HEADERS block completed; writer likely crashed on oversize HEADERS frame");
          }
          byte[] rest = new byte[8];
          int read = in.readNBytes(rest, 0, 8);
          assertEquals(read, 8, "Short frame header read");
          int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
          int type = rest[2] & 0xFF;
          int flags = rest[3] & 0xFF;
          assertTrue(length <= 16384, "Frame length [" + length + "] exceeds peer MAX_FRAME_SIZE [16384]");
          in.readNBytes(length);
          if (type == 0x1) {  // HEADERS
            assertFalse(headersStarted, "Multiple HEADERS frames on the same stream");
            headersStarted = true;
            if ((flags & 0x4) != 0) endHeadersSeen = true;
          } else if (type == 0x9) {  // CONTINUATION
            assertTrue(headersStarted, "CONTINUATION without prior HEADERS");
            continuationCount++;
            if ((flags & 0x4) != 0) endHeadersSeen = true;
          } else if (type == 0x7) {  // GOAWAY
            fail("Server sent GOAWAY instead of fragmenting HEADERS into CONTINUATION");
          }
        }
        assertTrue(continuationCount > 0,
            "Expected at least one CONTINUATION frame for oversize HEADERS block; got [" + continuationCount + "]");
      }
    }
  }

  /**
   * RFC 9113 §8.1 — a second HEADERS block on a stream that has not END_STREAM'd carries request trailers and MUST
   * be accepted. Current bug at HTTP2Connection.java:284-287 rejects every second HEADERS with RST_STREAM(STREAM_CLOSED).
   */
  @Test
  public void request_trailers_accepted_not_reset_as_stream_closed() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    AtomicReference<String> trailerValue = new AtomicReference<>();
    HTTPHandler handler = (req, res) -> {
      // Drain body so trailers are processed by the server.
      req.getInputStream().readAllBytes();
      String value = req.getTrailer("x-trailer");
      if (value != null) {
        trailerValue.set(value);
      }
      res.setStatus(200);
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // POST headers with TE: trailers (RFC 9113 §8.1 — required for the server to know trailers may follow).
        // HPACK: :method POST (static idx 3 = 0x83), :path / (idx 4 = 0x84), :scheme http (idx 6 = 0x86),
        //        content-length 4 (literal with indexing, name idx 28 = 0x5c, value "4"),
        //        te trailers (literal w/o indexing, name idx 58 = 0x1a [no — te is not in static table for h2 reuse, encode as literal name])
        // Simpler: use literal-without-indexing for te to avoid table mismatch.
        byte[] postHeaders = new byte[]{
            (byte) 0x83,                                              // :method: POST
            (byte) 0x84,                                              // :path: /
            (byte) 0x86,                                              // :scheme: http
            (byte) 0x41, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't',  // :authority: localhost
            (byte) 0x5c, 0x01, '4',                                   // content-length: 4
            (byte) 0x00, 0x02, 't', 'e', 0x08, 't', 'r', 'a', 'i', 'l', 'e', 'r', 's'  // te: trailers
        };
        writeFrameHeader(out, postHeaders.length, 0x1, 0x4 /* END_HEADERS, no END_STREAM */, 1);
        out.write(postHeaders);

        // DATA frame with 4-byte body, no END_STREAM (trailers will carry it).
        writeFrameHeader(out, 4, 0x0, 0, 1);
        out.write("body".getBytes());

        // Trailers: HEADERS with END_HEADERS | END_STREAM, single literal "x-trailer: end".
        byte[] trailers = new byte[]{
            (byte) 0x00, 0x09, 'x', '-', 't', 'r', 'a', 'i', 'l', 'e', 'r', 0x03, 'e', 'n', 'd'
        };
        writeFrameHeader(out, trailers.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(trailers);
        out.flush();

        sock.setSoTimeout(5000);
        var in = sock.getInputStream();
        int responseStreamId = readUntilResponseHeaders(in);
        assertEquals(responseStreamId, 1,
            "Expected response HEADERS on stream 1 (current bug: trailers HEADERS triggers RST_STREAM(STREAM_CLOSED))");
        assertEquals(trailerValue.get(), "end", "Server should expose request trailer via req.getTrailers()");
      }
    }
  }

  /**
   * RFC 9113 §8.1 — response trailers MUST be sent as a HEADERS frame with END_STREAM AFTER the final DATA frame,
   * and the final DATA frame MUST NOT have END_STREAM set. Currently exercised only indirectly via GRPCInteropTest;
   * this direct test pins the wire-level behavior so a regression in setTrailersFollow / emitTrailers fails fast.
   */
  @Test
  public void response_trailers_emitted_as_headers_frame_after_final_data() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      // Signal that the response will have trailers (so the output stream defers END_STREAM on the final DATA).
      res.setTrailer("x-checksum", "abc123");
      try (var out = res.getOutputStream()) {
        out.write("hello".getBytes());
      }
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Send a basic GET request on stream 1.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS|END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        var in = sock.getInputStream();

        // Read frames in order. Track: did we see response HEADERS, then DATA frames (last one MUST NOT have
        // END_STREAM), then trailers HEADERS (MUST have END_STREAM and decode to include x-checksum: abc123).
        boolean sawResponseHeaders = false;
        boolean sawDataFrame = false;
        int lastDataFlags = -1;
        byte[] trailerBlock = null;
        int trailerFlags = -1;
        int sawHeadersCount = 0;
        for (int i = 0; i < 20 && trailerBlock == null; i++) {
          byte[] hdr = in.readNBytes(9);
          if (hdr.length < 9) break;
          int len = ((hdr[0] & 0xFF) << 16) | ((hdr[1] & 0xFF) << 8) | (hdr[2] & 0xFF);
          int type = hdr[3] & 0xFF;
          int flags = hdr[4] & 0xFF;
          byte[] payload = in.readNBytes(len);
          if (payload.length < len) break;
          switch (type) {
            case 0x1 -> { // HEADERS
              sawHeadersCount++;
              if (sawHeadersCount == 1) {
                sawResponseHeaders = true;
              } else {
                trailerBlock = payload;
                trailerFlags = flags;
              }
            }
            case 0x0 -> { // DATA
              sawDataFrame = true;
              lastDataFlags = flags;
            }
            default -> {} // SETTINGS, WINDOW_UPDATE, etc. — ignore.
          }
        }

        assertTrue(sawResponseHeaders, "Expected response HEADERS frame on stream 1");
        assertTrue(sawDataFrame, "Expected at least one DATA frame");
        assertEquals(lastDataFlags & 0x1, 0,
            "Final DATA frame MUST NOT have END_STREAM when trailers follow; flags=[" + lastDataFlags + "]");
        assertNotNull(trailerBlock, "Expected trailers HEADERS frame after final DATA");
        assertEquals(trailerFlags & 0x1, 0x1,
            "Trailers HEADERS frame MUST have END_STREAM; flags=[" + trailerFlags + "]");

        // Decode the trailer block to verify x-checksum: abc123.
        var decoder = new HPACKDecoder(new HPACKDynamicTable(4096));
        var fields = decoder.decode(trailerBlock);
        boolean foundChecksum = fields.stream()
            .anyMatch(f -> f.name().equals("x-checksum") && f.value().equals("abc123"));
        assertTrue(foundChecksum, "Expected x-checksum: abc123 in trailer block; decoded: [" + fields + "]");
      }
    }
  }

  /**
   * When the writer thread exits and the connection is dead, an in-flight handler virtual-thread must not park
   * indefinitely on {@code writerQueue.put()} or in the {@code HTTP2OutputStream} flow-control wait loop.
   *
   * <p>Reproduction: client sends GET, then closes the socket before the response can be written. The reader
   * thread exits and the writer thread drains its sentinel and exits. The handler (mid-write of a large body) must
   * detect the dead connection within a bounded time and exit, instead of spinning forever on flow control or
   * blocking forever on a full {@code writerQueue}.
   */
  @Test
  public void handler_thread_does_not_hang_after_writer_dies() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    AtomicBoolean handlerCompleted = new AtomicBoolean(false);
    CountDownLatch handlerStarted = new CountDownLatch(1);
    HTTPHandler handler = (req, res) -> {
      res.setStatus(200);
      handlerStarted.countDown();
      try {
        // Write 1 MB. With default initial window 65535, the handler will exhaust the window quickly and
        // block in HTTP2OutputStream's flow-control wait loop. Once the client closes, no WINDOW_UPDATE
        // will arrive and the loop must abort instead of spinning forever.
        byte[] payload = new byte[1024 * 1024];
        res.getOutputStream().write(payload);
        res.getOutputStream().flush();
      } catch (IOException ignored) {
        // Expected once the connection is detected dead.
      }
      handlerCompleted.set(true);
    };
    try (var server = makeServer("http", handler, listener).start()) {
      var sock = openH2cConnection(server.getActualPort());
      var out = sock.getOutputStream();
      writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
      out.write(MINIMAL_HPACK_GET);
      out.flush();

      assertTrue(handlerStarted.await(5, TimeUnit.SECONDS), "Handler should start within 5s");
      // Brief pause to let the handler enter the write/flow-control path.
      Thread.sleep(100);
      // Abruptly close the client socket. Server reader notices EOF, finally enqueues sentinel, writer
      // takes sentinel and exits. Connection is dead. Handler must detect this and abort.
      sock.close();

      long deadline = System.currentTimeMillis() + 10_000;
      while (!handlerCompleted.get() && System.currentTimeMillis() < deadline) {
        Thread.sleep(50);
      }
      assertTrue(handlerCompleted.get(),
          "Handler did not exit within 10s of client close — likely hung in flow-control wait or writerQueue.put()");
    }
  }

  /**
   * RFC 9113 §5.5 — unknown frame types MUST be ignored. Send an unknown frame (type {@code 0xFE}), then a normal
   * HEADERS request. The server should respond successfully, proving the unknown frame was silently discarded.
   */
  @Test
  public void unknown_frame_type_silently_ignored() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Unknown frame type 0xFE with 4-byte payload on stream 0.
        writeFrameHeader(out, 4, 0xFE, 0, 0);
        out.write(new byte[]{0x1, 0x2, 0x3, 0x4});

        // Follow with a valid HEADERS request on stream 1.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int streamId = readUntilResponseHeaders(sock.getInputStream());
        assertEquals(streamId, 1, "Expected response HEADERS on stream 1 after unknown frame was silently ignored");
      }
    }
  }

  /**
   * RFC 9113 §5.3.2 — PRIORITY frames are valid but advisory only; the server MUST parse and discard them.
   * Send a PRIORITY frame, then a valid HEADERS request. The server should respond successfully.
   */
  @Test
  public void priority_frame_silently_ignored() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // PRIORITY: type=0x2, payload=5 bytes (stream dependency 4 bytes + weight 1 byte), stream-id=1.
        writeFrameHeader(out, 5, 0x2, 0, 1);
        out.write(new byte[]{0, 0, 0, 0, 0});  // exclusive=0, dependency=0, weight=0

        // Follow with a valid HEADERS request on stream 1.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int streamId = readUntilResponseHeaders(sock.getInputStream());
        assertEquals(streamId, 1, "Expected response HEADERS on stream 1 after PRIORITY was silently ignored");
      }
    }
  }

  /**
   * RFC 9113 §5.1 — a DATA frame on a recently-closed stream (one the client just RST'd) must produce
   * {@code GOAWAY(STREAM_CLOSED)} (error code {@code 0x5}).
   */
  @Test
  public void data_on_recently_closed_stream_triggers_stream_closed() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Open stream 1 with END_STREAM (no body — handler completes immediately).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        // RST stream 1 — server marks stream 1 as recently closed.
        writeFrameHeader(out, 4, 0x3 /* RST_STREAM */, 0, 1);
        out.write(new byte[]{0, 0, 0, 0x8}); // error code = CANCEL (0x8)
        // Send DATA on stream 1 — must produce GOAWAY(STREAM_CLOSED=0x5).
        writeFrameHeader(out, 5, 0x0 /* DATA */, 0x1 /* END_STREAM */, 1);
        out.write(new byte[]{1, 2, 3, 4, 5});
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x5, "Expected GOAWAY(STREAM_CLOSED=0x5) for DATA on recently-closed stream; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §5.1.1 — stream IDs MUST be strictly monotonically increasing. Sending a HEADERS on a stream
   * whose ID is lower than a previously seen stream ID must result in {@code GOAWAY(PROTOCOL_ERROR)} (error code
   * {@code 0x1}).
   */
  @Test
  public void decreasing_stream_id_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // First HEADERS on stream 5.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 5);
        out.write(MINIMAL_HPACK_GET);
        // Second HEADERS on stream 3 — lower than 5, a protocol error.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 3);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for decreasing stream ID; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §5.1 — a HEADERS frame on a recently-closed stream (one the client just RST'd) must produce
   * {@code GOAWAY(STREAM_CLOSED)} (error code {@code 0x5}).
   */
  @Test
  public void headers_on_recently_closed_stream_triggers_stream_closed() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Open stream 1 with END_STREAM (no body — handler completes immediately).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        // RST stream 1 — server marks stream 1 as recently closed.
        writeFrameHeader(out, 4, 0x3 /* RST_STREAM */, 0, 1);
        out.write(new byte[]{0, 0, 0, 0x8}); // error code = CANCEL (0x8)
        // Send HEADERS on stream 1 again — must produce GOAWAY(STREAM_CLOSED=0x5).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS | END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x5, "Expected GOAWAY(STREAM_CLOSED=0x5) for HEADERS on recently-closed stream; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.10 — once a HEADERS frame without END_HEADERS is sent, the next frame on the connection MUST be
   * a CONTINUATION on the same stream. Any other frame type or stream ID triggers {@code GOAWAY(PROTOCOL_ERROR)}
   * (error code {@code 0x1}).
   */
  @Test
  public void interleaved_frame_during_headers_continuation_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Send HEADERS on stream 1 WITHOUT END_HEADERS — this starts a header block.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x0 /* no END_HEADERS, no END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        // Now send a DATA frame on stream 3 instead of CONTINUATION on stream 1 — protocol error.
        writeFrameHeader(out, 0, 0x0, 0x1 /* END_STREAM */, 3);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for interleaved frame mid-header-block; got: " + errorCode);
      }
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────
  // Per-frame validators added in batch 1 (h2spec batch 1)
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  /**
   * RFC 9113 §6.1 — DATA frame with stream ID 0 must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void data_on_stream_zero_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // DATA frame on stream 0 — RFC 9113 §6.1 requires GOAWAY(PROTOCOL_ERROR).
        writeFrameHeader(out, 4, 0x0 /* DATA */, 0x1 /* END_STREAM */, 0);
        out.write(new byte[]{1, 2, 3, 4});
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for DATA on stream 0; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.1 — DATA frame with an invalid pad length (padLen >= frame payload length)
   * must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void data_with_invalid_pad_length_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // First open stream 1 so DATA isn't on an idle stream.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS|END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);

        // DATA on stream 1, PADDED flag set, padLen = 10 but payload length is only 5 (1 pad-len byte + 4 data/pad).
        // padLen (10) > remaining (4) → invalid pad length → PROTOCOL_ERROR.
        // Payload: [padLen=10] [data: 1,2,3,4] — total 5 bytes, padLen claims 10.
        writeFrameHeader(out, 5, 0x0 /* DATA */, 0x8 /* PADDED */, 1);
        out.write(new byte[]{10, 1, 2, 3, 4}); // padLen=10, only 4 actual bytes remain
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for invalid DATA pad length; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.2 — HEADERS frame with stream ID 0 must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void headers_on_stream_zero_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // HEADERS frame on stream 0 — RFC 9113 §5.1.1 requires GOAWAY(PROTOCOL_ERROR).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS|END_STREAM */, 0);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for HEADERS on stream 0; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §5.1.1 — client-initiated streams must use odd stream IDs. A HEADERS frame
   * with an even stream ID must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void headers_with_even_stream_id_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // HEADERS frame on stream 2 (even) — RFC 9113 §5.1.1 requires GOAWAY(PROTOCOL_ERROR).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS|END_STREAM */, 2);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for HEADERS on even stream ID 2; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.5 — SETTINGS frame with non-zero stream ID must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void settings_with_non_zero_stream_id_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // SETTINGS on stream 1 — RFC 9113 §6.5 requires stream ID 0; any other is PROTOCOL_ERROR.
        writeFrameHeader(out, 0, 0x4 /* SETTINGS */, 0, 1);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for SETTINGS on stream 1; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.5.2 — SETTINGS ENABLE_PUSH value other than 0 or 1 must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void settings_enable_push_invalid_value_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // SETTINGS: ENABLE_PUSH (0x2) = 2 — invalid; must be 0 or 1.
        byte[] payload = {0, 2, 0, 0, 0, 2}; // id=2 (ENABLE_PUSH), value=2
        writeFrameHeader(out, payload.length, 0x4 /* SETTINGS */, 0, 0);
        out.write(payload);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for ENABLE_PUSH=2; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.5.2 — SETTINGS INITIAL_WINDOW_SIZE exceeding 2^31-1 must trigger GOAWAY(FLOW_CONTROL_ERROR).
   */
  @Test
  public void settings_initial_window_size_too_large_triggers_flow_control_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // SETTINGS: INITIAL_WINDOW_SIZE (0x4) = 2^31 (0x80000000) — exceeds 2^31-1.
        byte[] payload = {0, 4, (byte) 0x80, 0, 0, 0}; // id=4, value=2^31
        writeFrameHeader(out, payload.length, 0x4 /* SETTINGS */, 0, 0);
        out.write(payload);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x3, "Expected GOAWAY(FLOW_CONTROL_ERROR=0x3) for INITIAL_WINDOW_SIZE > 2^31-1; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.5.2 — SETTINGS MAX_FRAME_SIZE below 2^14 (16384) must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void settings_max_frame_size_too_small_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // SETTINGS: MAX_FRAME_SIZE (0x5) = 1024 — below the minimum of 16384.
        byte[] payload = {0, 5, 0, 0, 0x04, 0}; // id=5, value=1024
        writeFrameHeader(out, payload.length, 0x4 /* SETTINGS */, 0, 0);
        out.write(payload);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for MAX_FRAME_SIZE below 16384; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.7 — PING frame with non-zero stream ID must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void ping_with_non_zero_stream_id_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // PING on stream 1 — RFC 9113 §6.7 requires stream ID 0; any other is PROTOCOL_ERROR.
        writeFrameHeader(out, 8, 0x6 /* PING */, 0, 1);
        out.write(new byte[8]);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for PING on stream 1; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.8 — GOAWAY frame with non-zero stream ID must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void goaway_with_non_zero_stream_id_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // GOAWAY on stream 1 — RFC 9113 §6.8 requires stream ID 0; any other is PROTOCOL_ERROR.
        // GOAWAY payload: last-stream-id (4 bytes) + error-code (4 bytes).
        writeFrameHeader(out, 8, 0x7 /* GOAWAY */, 0, 1);
        out.write(new byte[]{0, 0, 0, 0, 0, 0, 0, 0}); // lastStreamId=0, errorCode=NO_ERROR
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for GOAWAY on stream 1; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.9 — WINDOW_UPDATE with increment 0 on the connection (stream 0)
   * must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void window_update_zero_increment_on_connection_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // WINDOW_UPDATE on stream 0 with increment 0 — RFC 9113 §6.9 requires GOAWAY(PROTOCOL_ERROR).
        writeFrameHeader(out, 4, 0x8 /* WINDOW_UPDATE */, 0, 0);
        out.write(new byte[]{0, 0, 0, 0}); // increment = 0
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for WINDOW_UPDATE increment=0 on connection; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.9 — WINDOW_UPDATE with increment 0 on a stream must trigger RST_STREAM(PROTOCOL_ERROR).
   */
  @Test
  public void window_update_zero_increment_on_stream_triggers_rst_stream() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> {
      // Slow handler to keep the stream open long enough for the client's WINDOW_UPDATE.
      try { Thread.sleep(200); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
      res.setStatus(200);
    };
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // Open stream 1 — no END_STREAM so stream stays open.
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x1, 0x4 | 0x1 /* END_HEADERS|END_STREAM */, 1);
        out.write(MINIMAL_HPACK_GET);
        // WINDOW_UPDATE on stream 1 with increment 0 — RFC 9113 §6.9 requires RST_STREAM(PROTOCOL_ERROR).
        writeFrameHeader(out, 4, 0x8 /* WINDOW_UPDATE */, 0, 1);
        out.write(new byte[]{0, 0, 0, 0}); // increment = 0
        out.flush();

        // Expect RST_STREAM(PROTOCOL_ERROR=0x1) on stream 1.
        sock.setSoTimeout(5000);
        int errorCode = readUntilRstStream(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected RST_STREAM(PROTOCOL_ERROR=0x1) for WINDOW_UPDATE increment=0 on stream 1; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §6.10 — CONTINUATION frame with no preceding HEADERS (no active header block)
   * must trigger GOAWAY(PROTOCOL_ERROR).
   */
  @Test
  public void continuation_without_preceding_headers_triggers_protocol_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // CONTINUATION on stream 1 with no preceding HEADERS (no active header block) —
        // RFC 9113 §6.10 requires GOAWAY(PROTOCOL_ERROR).
        writeFrameHeader(out, MINIMAL_HPACK_GET.length, 0x9 /* CONTINUATION */, 0x4 /* END_HEADERS */, 1);
        out.write(MINIMAL_HPACK_GET);
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x1, "Expected GOAWAY(PROTOCOL_ERROR=0x1) for CONTINUATION without preceding HEADERS; got: " + errorCode);
      }
    }
  }

  /**
   * RFC 9113 §4.2 — PRIORITY frame with wrong length (not 5 bytes) must trigger GOAWAY(FRAME_SIZE_ERROR).
   */
  @Test
  public void priority_wrong_length_triggers_frame_size_error() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      try (var sock = openH2cConnection(server.getActualPort())) {
        var out = sock.getOutputStream();
        // PRIORITY frame on stream 1 with 4-byte payload (must be exactly 5) — FRAME_SIZE_ERROR.
        writeFrameHeader(out, 4, 0x2 /* PRIORITY */, 0, 1);
        out.write(new byte[]{0, 0, 0, 0});
        out.flush();

        sock.setSoTimeout(5000);
        int errorCode = readUntilGoaway(sock.getInputStream());
        assertEquals(errorCode, 0x6, "Expected GOAWAY(FRAME_SIZE_ERROR=0x6) for PRIORITY with wrong length; got: " + errorCode);
      }
    }
  }

  /**
   * Drain inbound frames until RST_STREAM (type {@code 0x3}) arrives or the connection closes.
   * Returns the RST_STREAM error code, or {@code -1} if EOF or GOAWAY arrived first.
   */
  private int readUntilRstStream(InputStream in) throws Exception {
    while (true) {
      int b0 = in.read();
      if (b0 == -1) {
        return -1;
      }
      byte[] rest = new byte[8];
      int read = in.readNBytes(rest, 0, 8);
      if (read != 8) {
        return -1;
      }
      int length = ((b0 & 0xFF) << 16) | ((rest[0] & 0xFF) << 8) | (rest[1] & 0xFF);
      int type = rest[2] & 0xFF;
      byte[] payload = in.readNBytes(length);
      if (type == 0x3) { // RST_STREAM
        if (payload.length < 4) {
          return -1;
        }
        return ((payload[0] & 0xFF) << 24) | ((payload[1] & 0xFF) << 16) | ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF);
      }
      if (type == 0x7) { // GOAWAY — connection error instead
        return -1;
      }
    }
  }

  /**
   * When the writer thread dies (broken pipe / peer reset mid-write), the reader must not park indefinitely on
   * {@code writerQueue.put()}. The {@code writerDead} flag plus reader interrupt close the deadlock by triggering the
   * reader's finally block, which interrupts handler virtual threads. Within a few seconds the connection should be
   * fully torn down.
   *
   * <p>This test is intentionally light — fully deterministic reproduction would need a test hook to inject a
   * {@code writeFrame} failure. The test passes if the {@code timeOut} does not fire.
   */
  @Test(timeOut = 10_000)
  public void reader_exits_within_timeout_after_socket_abort() throws Exception {
    var listener = new HTTPListenerConfiguration(0).withH2cPriorKnowledgeEnabled(true);
    HTTPHandler handler = (req, res) -> res.setStatus(200);
    try (var server = makeServer("http", handler, listener).start()) {
      Socket sock = openH2cConnection(server.getActualPort());
      var out = sock.getOutputStream();
      byte[] headers = new byte[]{
          (byte) 0x82, (byte) 0x84, (byte) 0x86,
          (byte) 0x41, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'
      };
      writeFrameHeader(out, headers.length, 0x1, 0x4 | 0x1, 1);
      out.write(headers);
      out.flush();
      // Abort the socket from the client side. The server's writer will fail next writeFrame and exit.
      sock.setSoLinger(true, 0);
      sock.close();
      // Give the server time to clean up. The 10-second @Test timeout is the safety net.
      Thread.sleep(1500);
    }
  }
}

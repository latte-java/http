/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2Frame;
import org.lattejava.http.server.internal.HTTP2FrameReader;
import org.lattejava.http.server.internal.HTTP2FrameWriter;

import static org.testng.Assert.*;

public class HTTP2FrameReaderTest {
  private byte[] header(int length, int type, int flags, int streamId) {
    return new byte[]{
        (byte)((length >> 16) & 0xFF), (byte)((length >> 8) & 0xFF), (byte)(length & 0xFF),
        (byte) type, (byte) flags,
        (byte)((streamId >> 24) & 0x7F), (byte)((streamId >> 16) & 0xFF), (byte)((streamId >> 8) & 0xFF), (byte)(streamId & 0xFF)
    };
  }

  @Test
  public void reads_data_frame() throws Exception {
    byte[] payload = "hello".getBytes();
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(payload.length, 0x0, 0x1, 7));
    bytes.write(payload);

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(bytes.toByteArray()), new byte[16384]);
    HTTP2Frame frame = reader.readFrame();

    assertTrue(frame instanceof HTTP2Frame.DataFrame);
    var data = (HTTP2Frame.DataFrame) frame;
    assertEquals(data.streamId(), 7);
    assertEquals(data.flags(), 0x1);
    assertEquals(data.payload(), payload);
  }

  @Test
  public void reads_settings_ack_with_empty_payload() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(0, 0x4, 0x1, 0));

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(bytes.toByteArray()), new byte[16384]);
    HTTP2Frame frame = reader.readFrame();

    assertTrue(frame instanceof HTTP2Frame.SettingsFrame);
    assertEquals(frame.flags(), 0x1);
  }

  @Test
  public void reads_window_update() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(4, 0x8, 0, 3));
    bytes.write(new byte[]{0, 0, 0, 100});

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(bytes.toByteArray()), new byte[16384]);
    HTTP2Frame frame = reader.readFrame();

    var wu = (HTTP2Frame.WindowUpdateFrame) frame;
    assertEquals(wu.streamId(), 3);
    assertEquals(wu.windowSizeIncrement(), 100);
  }

  @Test
  public void reads_unknown_frame_type() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(2, 0xFE, 0, 5));
    bytes.write(new byte[]{1, 2});

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(bytes.toByteArray()), new byte[16384]);
    HTTP2Frame frame = reader.readFrame();

    assertTrue(frame instanceof HTTP2Frame.UnknownFrame);
    var unk = (HTTP2Frame.UnknownFrame) frame;
    assertEquals(unk.type(), 0xFE);
  }

  @Test
  public void rst_stream_with_wrong_payload_length_throws() throws Exception {
    var bytes = new ByteArrayOutputStream();
    bytes.write(header(3, 0x3, 0, 1)); // RST_STREAM payload must be exactly 4
    bytes.write(new byte[]{1, 2, 3});

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(bytes.toByteArray()), new byte[16384]);
    expectThrows(HTTP2FrameReader.FrameSizeException.class, reader::readFrame);
  }

  @Test
  public void round_trip_data_frame() throws Exception {
    var sink = new ByteArrayOutputStream();
    var writer = new HTTP2FrameWriter(sink, new byte[16384 + 9]);
    writer.writeFrame(new HTTP2Frame.DataFrame(7, 0x1, "hello".getBytes()));

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(sink.toByteArray()), new byte[16384]);
    var frame = (HTTP2Frame.DataFrame) reader.readFrame();
    assertEquals(frame.streamId(), 7);
    assertEquals(frame.flags(), 0x1);
    assertEquals(frame.payload(), "hello".getBytes());
  }

  @Test
  public void round_trip_goaway() throws Exception {
    var sink = new ByteArrayOutputStream();
    var writer = new HTTP2FrameWriter(sink, new byte[16384 + 9]);
    writer.writeFrame(new HTTP2Frame.GoawayFrame(13, 0x1, new byte[0]));

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(sink.toByteArray()), new byte[16384]);
    var frame = (HTTP2Frame.GoawayFrame) reader.readFrame();
    assertEquals(frame.lastStreamId(), 13);
    assertEquals(frame.errorCode(), 0x1);
  }

  @Test
  public void round_trip_ping() throws Exception {
    var sink = new ByteArrayOutputStream();
    var writer = new HTTP2FrameWriter(sink, new byte[16384 + 9]);
    byte[] opaque = {1, 2, 3, 4, 5, 6, 7, 8};
    writer.writeFrame(new HTTP2Frame.PingFrame(0, opaque));

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(sink.toByteArray()), new byte[16384]);
    var frame = (HTTP2Frame.PingFrame) reader.readFrame();
    assertEquals(frame.opaqueData(), opaque);
  }

  @Test
  public void round_trip_rst_stream() throws Exception {
    var sink = new ByteArrayOutputStream();
    var writer = new HTTP2FrameWriter(sink, new byte[16384 + 9]);
    writer.writeFrame(new HTTP2Frame.RSTStreamFrame(5, 0x3));

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(sink.toByteArray()), new byte[16384]);
    var frame = (HTTP2Frame.RSTStreamFrame) reader.readFrame();
    assertEquals(frame.streamId(), 5);
    assertEquals(frame.errorCode(), 0x3);
  }

  @Test
  public void round_trip_settings_with_payload() throws Exception {
    byte[] payload = {0, 1, 0, 0, 0x10, 0}; // HEADER_TABLE_SIZE = 4096
    var sink = new ByteArrayOutputStream();
    var writer = new HTTP2FrameWriter(sink, new byte[16384 + 9]);
    writer.writeFrame(new HTTP2Frame.SettingsFrame(0, payload));

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(sink.toByteArray()), new byte[16384]);
    var frame = (HTTP2Frame.SettingsFrame) reader.readFrame();
    assertEquals(frame.payload(), payload);
  }

  @Test
  public void round_trip_window_update() throws Exception {
    var sink = new ByteArrayOutputStream();
    var writer = new HTTP2FrameWriter(sink, new byte[16384 + 9]);
    writer.writeFrame(new HTTP2Frame.WindowUpdateFrame(11, 1024));

    var reader = new HTTP2FrameReader(new ByteArrayInputStream(sink.toByteArray()), new byte[16384]);
    var frame = (HTTP2Frame.WindowUpdateFrame) reader.readFrame();
    assertEquals(frame.streamId(), 11);
    assertEquals(frame.windowSizeIncrement(), 1024);
  }
}

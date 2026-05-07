/*
 * Copyright (c) 2026 Latte Java
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.tests.server;

import module java.base;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.server.internal.HTTP2Stream;
import org.lattejava.http.server.internal.HTTP2Stream.Event;
import org.lattejava.http.server.internal.HTTP2Stream.State;

import static org.testng.Assert.*;

public class HTTP2StreamStateMachineTest {
  @Test
  public void idle_to_open_on_recv_headers() {
    var s = new HTTP2Stream(1, 65535, 65535);
    assertEquals(s.state(), State.IDLE);
    s.applyEvent(Event.RECV_HEADERS_NO_END_STREAM);
    assertEquals(s.state(), State.OPEN);
  }

  @Test
  public void idle_to_half_closed_remote_on_recv_headers_with_end_stream() {
    var s = new HTTP2Stream(1, 65535, 65535);
    s.applyEvent(Event.RECV_HEADERS_END_STREAM);
    assertEquals(s.state(), State.HALF_CLOSED_REMOTE);
  }

  @Test
  public void open_to_half_closed_remote_on_recv_data_with_end_stream() {
    var s = new HTTP2Stream(1, 65535, 65535);
    s.applyEvent(Event.RECV_HEADERS_NO_END_STREAM);
    s.applyEvent(Event.RECV_DATA_END_STREAM);
    assertEquals(s.state(), State.HALF_CLOSED_REMOTE);
  }

  @Test
  public void half_closed_remote_to_closed_on_send_data_with_end_stream() {
    var s = new HTTP2Stream(1, 65535, 65535);
    s.applyEvent(Event.RECV_HEADERS_END_STREAM);
    s.applyEvent(Event.SEND_HEADERS_NO_END_STREAM);
    s.applyEvent(Event.SEND_DATA_END_STREAM);
    assertEquals(s.state(), State.CLOSED);
  }

  @Test
  public void rst_stream_from_any_state_closes() {
    var s = new HTTP2Stream(1, 65535, 65535);
    s.applyEvent(Event.RECV_HEADERS_NO_END_STREAM);
    s.applyEvent(Event.RECV_RST_STREAM);
    assertEquals(s.state(), State.CLOSED);
  }

  @Test
  public void illegal_event_throws() {
    var s = new HTTP2Stream(1, 65535, 65535);
    expectThrows(IllegalStateException.class, () -> s.applyEvent(Event.RECV_DATA_END_STREAM));
  }
}

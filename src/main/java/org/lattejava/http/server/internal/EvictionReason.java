/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
package org.lattejava.http.server.internal;

/**
 * Why the reaper evicted a connection. Returned by {@link HTTPConnection#check(long)} and passed to
 * {@link HTTPConnection#evict(EvictionReason)}.
 */
public enum EvictionReason {
  MaxAge,
  ProcessingTimeout,
  SlowRead,
  SlowWrite
}

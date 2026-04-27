/*
 * Copyright (c) 2015-2025, FusionAuth, All Rights Reserved
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

import static java.time.temporal.ChronoField.*;

/**
 * Tools for dates, all dates are parsed and formatted using an RFC 5322 compatible format.
 * <p>
 * https://datatracker.ietf.org/doc/html/rfc5322
 *
 * @author Brian Pontarelli
 */
public final class DateTools {
  /**
   * Cached IMF-fixdate string for the current second. Per-second resolution is sufficient for an HTTP {@code Date}
   * header and avoids re-formatting on every request. Stale entries are simply replaced — the resulting string is
   * deterministic for any given second so concurrent replacements are harmless.
   */
  private static final AtomicReference<CachedDate> dateCache = new AtomicReference<>();

  public static final DateTimeFormatter RFC_5322_DATE_TIME;

  static {
    Map<Long, String> dow = new HashMap<>();
    dow.put(1L, "Mon");
    dow.put(2L, "Tue");
    dow.put(3L, "Wed");
    dow.put(4L, "Thu");
    dow.put(5L, "Fri");
    dow.put(6L, "Sat");
    dow.put(7L, "Sun");
    Map<Long, String> moy = new HashMap<>();
    moy.put(1L, "Jan");
    moy.put(2L, "Feb");
    moy.put(3L, "Mar");
    moy.put(4L, "Apr");
    moy.put(5L, "May");
    moy.put(6L, "Jun");
    moy.put(7L, "Jul");
    moy.put(8L, "Aug");
    moy.put(9L, "Sep");
    moy.put(10L, "Oct");
    moy.put(11L, "Nov");
    moy.put(12L, "Dec");

    RFC_5322_DATE_TIME = new DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .parseLenient()
        .optionalStart()
        .appendText(DAY_OF_WEEK, dow)
        .appendLiteral(", ")
        .optionalEnd()
        .appendValue(DAY_OF_MONTH, 2, 2, SignStyle.NOT_NEGATIVE)
        .appendLiteral(' ')
        .appendText(MONTH_OF_YEAR, moy)
        .appendLiteral(' ')
        .appendValue(YEAR, 4)  // 2 digit year not handled
        .appendLiteral(' ')
        .appendValue(HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(MINUTE_OF_HOUR, 2)
        .optionalStart()
        .appendLiteral(':')
        .appendValue(SECOND_OF_MINUTE, 2)
        .optionalEnd()
        .appendLiteral(' ')
        .appendOffset("+HHMM", "GMT")  // should handle UT/Z/EST/EDT/CST/CDT/MST/MDT/PST/MDT
        .toFormatter();
  }

  private DateTools() {
  }

  /**
   * Returns the current time formatted as an HTTP IMF-fixdate (RFC 1123 / RFC 9110 §5.6.7) suitable for an HTTP
   * {@code Date} header. Result is cached at one-second resolution to avoid re-formatting on every request.
   */
  public static String currentHTTPDate() {
    long second = System.currentTimeMillis() / 1000L;
    CachedDate cached = dateCache.get();
    if (cached != null && cached.second == second) {
      return cached.value;
    }
    String formatted = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochSecond(second).atZone(ZoneOffset.UTC));
    dateCache.set(new CachedDate(second, formatted));
    return formatted;
  }

  public static String format(ZonedDateTime value) {
    return value.format(DateTools.RFC_5322_DATE_TIME);
  }

  public static ZonedDateTime parse(String value) {
    try {
      return ZonedDateTime.parse(value, DateTools.RFC_5322_DATE_TIME);
    } catch (Exception e) {
      return null;
    }
  }

  private record CachedDate(long second, String value) {
  }
}

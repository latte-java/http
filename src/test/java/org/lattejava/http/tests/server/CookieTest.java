/*
 * Copyright (c) 2021-2025, FusionAuth, All Rights Reserved
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
package org.lattejava.http.tests.server;

import module java.base;
import module java.net.http;
import module org.lattejava.http;
import module org.testng;

import org.lattejava.http.Cookie;

import static org.testng.Assert.*;

/**
 * @author Brian Pontarelli
 */
public class CookieTest extends BaseTest {
  @Test
  public void cookieInjection() throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertNull(req.getCookie("injected"));

      res.setHeader(HTTPValues.Headers.ContentType, "text/plain");
      res.setStatus(200);
      res.getOutputStream().close();
    };

    try (HTTPServer ignore = makeServer("http", handler).start()) {
      URI uri = URI.create("http://localhost:4242/%0d%0aSet-Cookie:injected=true%0d%0a?client_id=foo");
      CookieManager cookieHandler = new CookieManager();
      try (var client = makeClient("http", cookieHandler)) {
        HttpRequest request = HttpRequest.newBuilder()
                                         .uri(uri)
                                         .GET()
                                         .build();

        var response = client.send(request, _ -> HttpResponse.BodySubscribers.discarding());
        assertEquals(response.statusCode(), 200);

        List<HttpCookie> cookies = cookieHandler.getCookieStore().get(uri);
        assertEquals(response.statusCode(), 200);
        assertNull(response.headers().firstValue("Set-Cookie").orElse(null));
        assertEquals(cookies.size(), 0);
      }
    }
  }

  @Test
  public void fromRequestHeader() {
    List<Cookie> cookies = Cookie.fromRequestHeader("foo=bar; baz=fred");
    assertEquals(cookies.size(), 2);
    assertEquals(cookies.get(0), new Cookie("foo", "bar"));
    assertEquals(cookies.get(1), new Cookie("baz", "fred"));

    cookies = Cookie.fromRequestHeader("foo=bar==; baz=fred==");
    assertEquals(cookies.size(), 2);
    assertEquals(cookies.get(0), new Cookie("foo", "bar=="));
    assertEquals(cookies.get(1), new Cookie("baz", "fred=="));

    cookies = Cookie.fromRequestHeader("foo=; baz=");
    assertEquals(cookies.size(), 0);

    cookies = Cookie.fromRequestHeader("foo=");
    assertEquals(cookies.size(), 0);

    cookies = Cookie.fromRequestHeader("=");
    assertEquals(cookies.size(), 0);

    cookies = Cookie.fromRequestHeader("=bar");
    assertEquals(cookies.size(), 0);

    cookies = Cookie.fromRequestHeader(";");
    assertEquals(cookies.size(), 0);

    cookies = Cookie.fromRequestHeader(";;;;;");
    assertEquals(cookies.size(), 0);
  }

  @SuppressWarnings("ConstantConditions")
  @Test
  public void fromResponseHeader() {
    Cookie cookie = Cookie.fromResponseHeader("foo=bar; Path=/foo/bar; Domain=lattejava.org; Max-Age=1; Secure; HttpOnly; SameSite=Lax");
    assertEquals(cookie.domain, "lattejava.org");
    assertNull(cookie.expires);
    assertTrue(cookie.httpOnly);
    assertEquals((long) cookie.maxAge, 1L);
    assertEquals(cookie.name, "foo");
    assertEquals(cookie.path, "/foo/bar");
    assertEquals(cookie.sameSite, Cookie.SameSite.Lax);
    assertTrue(cookie.secure);
    assertEquals(cookie.value, "bar");

    cookie = Cookie.fromResponseHeader("foo=bar; Domain=lattejava.org; Expires=Wed, 21 Oct 2015 07:28:00 GMT; SameSite=None");
    assertEquals(cookie.domain, "lattejava.org");
    assertEquals(cookie.expires, ZonedDateTime.of(2015, 10, 21, 7, 28, 0, 0, ZoneOffset.UTC));
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertEquals(cookie.sameSite, Cookie.SameSite.None);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "bar");

    cookie = Cookie.fromResponseHeader("     foo=bar;    \nDomain=lattejava.org;   \t Expires=Wed, 21 Oct 2015 07:28:00 GMT;      \rSameSite=None");
    assertEquals(cookie.domain, "lattejava.org");
    assertEquals(cookie.expires, ZonedDateTime.of(2015, 10, 21, 7, 28, 0, 0, ZoneOffset.UTC));
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertEquals(cookie.sameSite, Cookie.SameSite.None);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "bar");

    // Base 64 encoded with padding
    cookie = Cookie.fromResponseHeader("foo=slkjsdoiuewljklk==");
    assertNull(cookie.domain);
    assertNull(cookie.expires);
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertNull(cookie.sameSite);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "slkjsdoiuewljklk==");

    // Broken but parseable
    cookie = Cookie.fromResponseHeader("foo=bar;Domain=lattejava.org;Expires=Wed, 21 Oct 2015 07:28:00 GMT;SameSite=None");
    assertEquals(cookie.domain, "lattejava.org");
    assertEquals(cookie.expires, ZonedDateTime.of(2015, 10, 21, 7, 28, 0, 0, ZoneOffset.UTC));
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertEquals(cookie.sameSite, Cookie.SameSite.None);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "bar");

    // Empty values
    cookie = Cookie.fromResponseHeader("foo=bar;Domain=;Expires=;SameSite=");
    assertEquals(cookie.domain, "");
    assertNull(cookie.expires);
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertNull(cookie.sameSite);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "bar");

    // Quoted value, no other attributes
    // https://www.rfc-editor.org/rfc/rfc6265#section-4.1.1
    // cookie-value = *cookie-octet / ( DQUOTE *cookie-octet DQUOTE )
    cookie = Cookie.fromResponseHeader("foo=\"bar\";");
    assertNull(cookie.domain);
    assertNull(cookie.expires);
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertNull(cookie.sameSite);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "bar");

    // Quoted value, additional attribute
    cookie = Cookie.fromResponseHeader("foo=\"bar\"; SameSite=");
    assertNull(cookie.domain);
    assertNull(cookie.expires);
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertNull(cookie.sameSite);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "bar");

    // Missing closing quote
    // - This is not a valid value, but we are handling it anyway.
    cookie = Cookie.fromResponseHeader("foo=\"bar; SameSite=");
    assertNull(cookie.domain);
    assertNull(cookie.expires);
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertNull(cookie.sameSite);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "bar");

    // Missing opening quote
    // - This is not a valid value, but we are handling it anyway.
    cookie = Cookie.fromResponseHeader("foo=bar\"; SameSite=");
    assertNull(cookie.domain);
    assertNull(cookie.expires);
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertNull(cookie.sameSite);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "bar");

    // Broken attributes
    cookie = Cookie.fromResponseHeader("foo=bar;  =lattejava.org; =Wed, 21 Oct 2015 07:28:00 GMT; =1; =Lax");
    assertNull(cookie.domain);
    assertNull(cookie.expires);
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertNull(cookie.sameSite);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "bar");

    // Empty value cookie
    cookie = Cookie.fromResponseHeader("a=");
    assertNull(cookie.domain);
    assertNull(cookie.expires);
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "a");
    assertNull(cookie.path);
    assertNull(cookie.sameSite);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "");

    // Empty value cookie
    cookie = Cookie.fromResponseHeader("a=; Max-Age=1");
    assertNull(cookie.domain);
    assertNull(cookie.expires);
    assertFalse(cookie.httpOnly);
    assertEquals((long) cookie.maxAge, 1L);
    assertEquals(cookie.name, "a");
    assertNull(cookie.path);
    assertNull(cookie.sameSite);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "");

    // Empty values
    // - Max-Age and Expires should never be empty
    cookie = Cookie.fromResponseHeader("foo=;Domain=;SameSite=");
    assertEquals(cookie.domain, "");
    assertNull(cookie.expires);
    assertFalse(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertNull(cookie.path);
    assertNull(cookie.sameSite);
    assertFalse(cookie.secure);
    assertEquals(cookie.value, "");

    // Null values
    cookie = Cookie.fromResponseHeader("foo;Domain;Expires;Max-Age;SameSite");
    assertNull(cookie);

    // Null values
    cookie = Cookie.fromResponseHeader("   =bar;  =lattejava.org; =Wed, 21 Oct 2015 07:28:00 GMT; =1; =Lax");
    assertNull(cookie);

    // Null values at the start
    cookie = Cookie.fromResponseHeader("=bar;=lattejava.org;Expires;Max-Age;SameSite");
    assertNull(cookie);

    // Borked cookie
    cookie = Cookie.fromResponseHeader("=;");
    assertNull(cookie);

    // Borked cookie
    cookie = Cookie.fromResponseHeader(";");
    assertNull(cookie);

    // Borked cookie
    cookie = Cookie.fromResponseHeader(";;;;;");
    assertNull(cookie);

    // Borked cookie
    cookie = Cookie.fromResponseHeader("");
    assertNull(cookie);

    // Borked cookie
    cookie = Cookie.fromResponseHeader("=");
    assertNull(cookie);

    // Borked cookie
    cookie = Cookie.fromResponseHeader("=a");
    assertNull(cookie);

    // Borked coookie, ending with a semicolon;
    cookie = Cookie.fromResponseHeader("foo=%2Fbar; Path=/; Secure; HTTPonly;");
    assertNull(cookie.domain);
    assertNull(cookie.expires);
    assertTrue(cookie.httpOnly);
    assertNull(cookie.maxAge);
    assertEquals(cookie.name, "foo");
    assertEquals(cookie.path, "/");
    assertNull(cookie.sameSite);
    assertTrue(cookie.secure);
    assertEquals(cookie.value, "%2Fbar");

    // additional attributes
    // - name and value
    cookie = Cookie.fromResponseHeader("foo=;utm=123");
    assertEquals(cookie.name, "foo");
    assertEquals(cookie.getAttribute("utm"), "123");

    // additional attributes
    // - name, sep but no value
    cookie = Cookie.fromResponseHeader("foo=;utm=");
    assertEquals(cookie.name, "foo");
    assertEquals(cookie.getAttribute("utm"), "");

    // additional attributes
    // - name only
    cookie = Cookie.fromResponseHeader("foo=;utm");
    assertEquals(cookie.name, "foo");
    assertEquals(cookie.getAttribute("utm"), "");

    // additional attributes
    // - multiple
    cookie = Cookie.fromResponseHeader("foo=;foo=bar;bar=baz;bing=boom");
    assertEquals(cookie.name, "foo");
    assertEquals(cookie.getAttribute("foo"), "bar");
    assertEquals(cookie.getAttribute("bar"), "baz");
    assertEquals(cookie.getAttribute("bing"), "boom");

    // has attribute
    cookie = Cookie.fromResponseHeader("foo=;foo=bar;bar=baz;bing=boom");
    assertTrue(cookie.hasAttribute("foo"));
    assertTrue(cookie.hasAttribute("bar"));
    assertTrue(cookie.hasAttribute("bing"));
    assertFalse(cookie.hasAttribute("booya"));
    assertFalse(cookie.hasAttribute("baz"));
    assertFalse(cookie.hasAttribute("boom"));
  }

  @Test(dataProvider = "schemes")
  public void roundTripMultiple(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getPath(), "/api/system/version");
      assertEquals(req.getCookie("request").value, "request-value");
      assertEquals(req.getCookie("request-2").value, "request-value-2");

      res.addCookie(new Cookie("response", "response-value").with(c -> c.setSameSite(Cookie.SameSite.Lax)));
      res.addCookie(new Cookie("response-2", "response-value-2").with(c -> c.setMaxAge(42L)));
      res.setStatus(200);
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      CookieManager cookieHandler = new CookieManager();
      try (var client = makeClient(scheme, cookieHandler)) {
        var response = client.send(
            HttpRequest.newBuilder().uri(uri).header(HTTPValues.Headers.Cookie, "request=request-value").header(HTTPValues.Headers.Cookie, "request-2=request-value-2").header(HTTPValues.Headers.ContentType, "application/json").GET().build(),
            _ -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        List<HttpCookie> cookies = cookieHandler.getCookieStore().get(uri);
        assertEquals(response.statusCode(), 200);
        assertEquals(cookies.size(), 2);
        assertEquals(cookies.stream().filter(c -> c.getName().equals("response")).findFirst().orElseThrow().getValue(), "response-value");
        assertEquals(cookies.stream().filter(c -> c.getName().equals("response-2")).findFirst().orElseThrow().getValue(), "response-value-2");
        assertTrue(response.headers().allValues("Set-Cookie").contains("response=response-value; SameSite=Lax"));
        assertTrue(response.headers().allValues("Set-Cookie").contains("response-2=response-value-2; Max-Age=42"));
      }
    }
  }

  @Test(dataProvider = "schemes")
  public void roundTripSingle(String scheme) throws Exception {
    HTTPHandler handler = (req, res) -> {
      assertEquals(req.getPath(), "/api/system/version");
      assertEquals(req.getCookie("request").value, "request-value");

      res.addCookie(new Cookie("response", "response-value").with(c -> c.setSameSite(Cookie.SameSite.Lax)));
      res.setStatus(200);
    };

    CountingInstrumenter instrumenter = new CountingInstrumenter();
    try (HTTPServer ignore = makeServer(scheme, handler, instrumenter).start()) {
      URI uri = makeURI(scheme, "");
      CookieManager cookieHandler = new CookieManager();
      try (var client = makeClient(scheme, cookieHandler)) {
        var response = client.send(
            HttpRequest.newBuilder().uri(uri).header(HTTPValues.Headers.Cookie, "request=request-value").header(HTTPValues.Headers.ContentType, "application/json").GET().build(),
            _ -> HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8)
        );

        List<HttpCookie> cookies = cookieHandler.getCookieStore().get(uri);
        assertEquals(response.statusCode(), 200);
        assertEquals(cookies.size(), 1);
        assertEquals(cookies.getFirst().getName(), "response");
        assertEquals(cookies.getFirst().getValue(), "response-value");
        assertEquals(response.headers().firstValue("Set-Cookie").orElse(null), "response=response-value; SameSite=Lax");
      }
    }
  }

  @Test
  public void toRequestHeader() {
    assertEquals(new Cookie("foo", "bar").toRequestHeader(), "foo=bar");
  }
}

/*
 * Copyright (c) 2026 The Latte Project
 * SPDX-License-Identifier: MIT
 */
module org.lattejava.http.tests {
  requires jackson5;
  requires java.net.http;
  requires org.lattejava.http;
  requires org.testng;
  requires restify;
  opens org.lattejava.http.tests.io to org.testng;
  opens org.lattejava.http.tests.security to org.testng;
  opens org.lattejava.http.tests.server to org.testng;
  opens org.lattejava.http.tests.util to org.testng;
}

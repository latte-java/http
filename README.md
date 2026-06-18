## Java HTTP client and server ![semver 2.0.0 compliant](http://img.shields.io/badge/semver-2.0.0-brightgreen.svg?style=flat-square) [![test](https://github.com/latte-java/http/actions/workflows/test.yml/badge.svg?branch=main)](https://github.com/latte-java/http/actions/workflows/test.yml)

### Latest versions

* Latest stable version: `0.1.0`

The goal of this project is to build a full-featured HTTP server and client in plain Java without the use of any libraries. The server supports HTTP/1.1 and HTTP/2 (h2 over TLS via ALPN, h2c prior-knowledge or via Upgrade/101). The client and server will use Project Loom virtual threads and blocking I/O so that the Java VM will handle all the context switching between virtual threads as they block on I/O.

For more information about Project Loom and virtual threads, please review the following link.
* https://blogs.oracle.com/javamagazine/post/java-virtual-threads


## FusionAuth's HTTP server

This library is a fork of FusionAuth's java-http library. Brian Pontarelli and Daniel DeGroff wrote that library while working on FusionAuth. When Brian and Daniel started the Latte Project, they decided it was simplest to simply copy the code from the FusionAuth library and continue on with it.

## Project Goals

- Very fast
- Easy to make a simple web server like you can in Node.js
- No dependencies
- To not boil the ocean. This is a purpose-built HTTP server that probably won't do everything

## Installation

If you are using Latte, you can add this to your build file:

```groovy
dependency(id: "org.lattejava:http:0.1.0")
```

**NOTE:** We might support Maven in the future, but the plan for now is to support the Latte CLI tool.

## Examples Usages:

Creating a server is simple:

```java
import org.lattejava.http.server.HTTPListenerConfiguration;
import org.lattejava.http.server.HTTPServer;
import org.lattejava.http.server.HTTPHandler;

public class Example {
  public static void main(String... args) {
    HTTPHandler handler = (req, res) -> {
      // Handler code goes here
    };

    HTTPServer server = new HTTPServer().withHandler(handler)
                                        .withListener(new HTTPListenerConfiguration(4242));
    server.start();
    // Use server
    server.close();
  }
}
```

Since the `HTTPServer` class implements `java.io.Closeable`, you can also use a try-resource block like this:

```java
import org.lattejava.http.server.HTTPListenerConfiguration;
import org.lattejava.http.server.HTTPServer;
import org.lattejava.http.server.HTTPHandler;

public class Example {
  public static void main(String... args) {
    HTTPHandler handler = (req, res) -> {
      // Handler code goes here
    };

    try (HTTPServer server = new HTTPServer().withHandler(handler)
                                             .withListener(new HTTPListenerConfiguration(4242))) {
      server.start();
      // When this block exits, the server will be shutdown
    }
  }
}
```

You can also set various options on the server using the `with` methods on the class like this:

```java
import java.time.Duration;

import org.lattejava.http.server.HTTPListenerConfiguration;
import org.lattejava.http.server.HTTPServer;
import org.lattejava.http.server.HTTPHandler;

public class Example {
  public static void main(String... args) {
    HTTPHandler handler = (req, res) -> {
      // Handler code goes here
    };

    HTTPServer server = new HTTPServer().withHandler(handler)
                                        .withShutdownDuration(Duration.ofSeconds(10L))
                                        .withListener(new HTTPListenerConfiguration(4242));
    server.start();
    // Use server
    server.close();
  }
}
```

### TLS

The HTTP server implements TLS `1.0-1.3` using the Java SSLEngine. To enable TLS for your server, you need to create an `HTTPListenerConfiguration` that includes a certificate and private key. Most production use-cases will use a proxy such as Apache, Nginx, ALBs, etc. In development, it is recommended that you set up self-signed certificates and load those into the HTTP server.

To set up self-signed certificates on macOS, you can use the program `mkcert` with the following example. 

```shell
brew install mkcert
mkcert -install
mkdir -p ~/dev/certificates
mkcert -cert-file ~/dev/certificates/example.org.pem -key-file ~/dev/certificates/example.org.key example.org
```

Note, if you are using Linux, once you install `mkcert` the instructions should be the same.

In production environments, your certificate will likely be signed by one or more intermediate Certificate Authorities. In addition to the server certificate, ensure that all intermediate CA certificates in the chain are included in your pem file.

Now you can load these into the HTTP server like this:

```java
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

import org.lattejava.http.server.HTTPHandler;
import org.lattejava.http.server.HTTPServer;

public class Example {
  private static String certificate;

  private static String privateKey;

  public static void main(String[] args) throws Exception {
    String homeDir = System.getProperty("user.home");
    certificate = Files.readString(Paths.get(homeDir + "/dev/certificates/example.org.pem"));
    privateKey = Files.readString(Paths.get(homeDir + "/dev/certificates/example.org.key"));

    HTTPHandler handler = (req, res) -> {
      // Handler code goes here
    };

    HTTPServer server = new HTTPServer().withHandler(handler)
                                        .withListener(new HTTPListenerConfiguration(4242, certificate, privateKey));
    // Use server
    server.close();
  }
}
```

And finally, you'll need to add the domain name to your hosts file to ensure that the SNI lookup handles the certificate correctly. For this example, you would use this entry in the `/etc/hosts` file:

```text
127.0.0.1 example.org
```

Then you can open `https://example.org` in a browser or call it using an HTTP client (i.e. Insomnia, Postman, etc or in code).

## Performance

A key purpose for this project is to obtain screaming performance. Here are benchmark results comparing Latte's `http` against other Java HTTP servers.

These benchmarks ensure `http` stays near the top in raw throughput, and we'll be working on claiming the top position -- even if only for bragging rights, since in practice your database and application code will be the bottleneck long before the HTTP server.

All servers implement the same request handler that reads the request body and returns a `200`. All servers were tested over plain HTTP (no TLS) to isolate server performance.

<!-- PERF-SUMMARY-START -->
Latte HTTP is competitive with the fastest production HTTP servers across most workloads. Where it pulls clearly ahead is the **blocking-IO scenario**, which simulates a handler waiting on a database, cache, or downstream HTTP call — the most common shape for real web apps. Virtual threads park for free; worker-pool servers (Tomcat, Jetty) are bottlenecked by their default thread-pool size.

**Headline scenario: `h2-io`** (handler does `Thread.sleep(10ms)` per request, 10 conns × 100 streams = 1000 in-flight)

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |       70,676 |    125 |             13.94 |             19.41 |        100.0% |
| Latte http    |       67,159 |    122 |             14.39 |             27.60 |         95.0% |
| Latte http    |       69,337 |     52 |             14.22 |             25.88 |         98.1% |
| Helidon       |       70,915 |      0 |             13.97 |             26.44 |        100.3% |
| Helidon       |       69,149 |      0 |             14.15 |             32.11 |         97.8% |
| Helidon       |       72,902 |      0 |             13.52 |             29.75 |        103.1% |
| Jetty         |       11,249 |  84764 |             68.82 |            238.04 |         15.9% |
| Jetty         |       11,305 |  85573 |             68.63 |            233.18 |         15.9% |
| Jetty         |       10,530 |  81843 |             72.77 |            236.41 |         14.8% |
| Netty         |       78,023 |      0 |             12.76 |             28.17 |        110.3% |
| Netty         |       78,059 |      0 |             12.70 |             27.47 |        110.4% |
| Netty         |       78,021 |      0 |             12.80 |             34.93 |        110.3% |
| Apache Tomcat |       14,966 |      0 |             66.66 |            125.24 |         21.1% |
| Apache Tomcat |       14,962 |      0 |             66.71 |            124.59 |         21.1% |
| Apache Tomcat |       14,761 |      0 |             67.62 |            147.34 |         20.8% |
| Undertow      |        6,826 |      0 |            146.11 |            182.30 |          9.6% |
| Undertow      |        6,792 |      0 |            146.83 |            184.29 |          9.6% |
| Undertow      |        6,778 |      0 |            147.13 |            191.21 |          9.5% |

**See [docs/BENCHMARKS.md](docs/BENCHMARKS.md)** for the full 6-scenario breakdown across self / jetty / tomcat / netty — including HTTP/1, CPU-bound, multiplexed stream concurrency, browser-shape connection concurrency, large-response throughput, and per-scenario rationale on what each scenario was designed to expose.

_Benchmark performed 2026-05-21 on Darwin, arm64, 10 cores, Apple M4, 24GB RAM (MacBook Air)._
_OS: macOS 15.7.3._
_Java: openjdk version "25.0.2" 2026-01-20 LTS._
<!-- PERF-SUMMARY-END -->

See [benchmarks/README.md](benchmarks/README.md) for full usage and options.

## Protocol support

Detailed conformance status lives in the per-version spec docs:

- [HTTP/1.1](docs/design/2026-04-27-HTTP1.1.md) — implemented
- [HTTP/2](docs/design/2026-05-05-HTTP2.md) — implemented (RFC 9113, HPACK, h2c, ALPN, gRPC)
- [HTTP/3](docs/design/2026-05-09-HTTP3.md) — out of scope until JDK QUIC API

The HTTP client is not yet implemented.

## FAQ

### Why virtual threads and not NIO?

Let's face it, NIO is insanely complex to write and maintain. The first three versions of FusionAuth's `java-http` (the library this project is based on) used NIO with non-blocking selectors. We encountered a ton bugs, performance issues, etc. If you compare the `0.3-maintenance` branch with `main` of that project, you'll quickly see that switching to virtual threads and standard blocking I/O made our code **MUCH** simpler. 

Therefore, when we moved the HTTP server out of FusionAuth and into The Latte Project, we just continued along the path with virtual threads.

## Helping out

We are looking for Java developers that are interested in helping us build the client and server. If you know a ton about networks and protocols and love writing clean, high-performance Java, contact us via GitHub.

## Building with Latte

**Note:** This project uses the Latte CLI tool. The Latte Project website has detailed instructions on installing and using Latte:

https://lattejava.org

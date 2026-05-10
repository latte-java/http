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

<!-- H1-BENCHMARK-START -->
### HTTP/1.1 (wrk)

#### Hello scenario (low concurrency, baseline)

| Server         | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|----------------|-------------:|-------------:|-----------------:|-----------------:|--------------:|
| Latte http     |      110,989 |            0 |              0.99 |              7.34 |        100.0% |
| Jetty          |      111,548 |            0 |              1.03 |              6.03 |        100.5% |
| Netty          |      114,546 |            0 |              0.94 |              5.52 |        103.2% |
| Apache Tomcat  |      109,330 |            0 |              1.01 |              7.49 |         98.5% |

#### Under stress (1,000 concurrent connections)

| Server         | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|----------------|-------------:|-------------:|-----------------:|-----------------:|--------------:|
| Latte http     |       82,254 |        554.4 |             11.56 |             30.93 |        100.0% |
| Jetty          |      109,041 |            0 |              9.11 |             31.58 |        132.5% |
| Netty          |      105,667 |            0 |              9.19 |             25.33 |        128.4% |
| Apache Tomcat  |      106,486 |            0 |              9.22 |             28.18 |        129.4% |

_JDK HttpServer (`com.sun.net.httpserver`) is included as a baseline since it ships with the JDK and requires no dependencies. However, as the stress test shows, it is not suitable for production workloads — it suffers significant failures under high concurrency._

_Benchmark performed 2026-05-10 on Darwin, arm64, 10 cores, Apple M4, 24GB RAM (MacBook Air)._
_OS: macOS 15.7.3._
_Java: openjdk version "25.0.2" 2026-01-20 LTS._

To reproduce:
```bash
cd benchmarks
./run-benchmarks.sh --scenarios hello,high-concurrency
./update-readme.sh
```
<!-- H1-BENCHMARK-END -->

<!-- H2-BENCHMARK-START -->
### HTTP/2 (h2load)

#### h2-hello (1 connection × 100 streams)

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      208,621 |      0 |              0.45 |              1.48 |        100.0% |
| Jetty         |       27,191 | 1011316 |              0.42 |              1.38 |         13.0% |
| Netty         |      224,840 |      0 |              0.42 |              1.59 |        107.7% |
| Apache Tomcat |       48,976 |      0 |              2.03 |              4.96 |         23.4% |

#### h2-high-concurrency (10 connections × 100 streams each)

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      369,994 |     13 |              2.75 |             26.85 |        100.0% |
| Jetty         |       85,130 | 1954545 |              2.31 |             18.63 |         23.0% |
| Netty         |      768,319 |      0 |              1.32 |              9.53 |        207.6% |
| Apache Tomcat |      140,423 |      0 |              6.61 |             37.43 |         37.9% |

_JDK HttpServer does not support HTTP/2 and is excluded from h2 results._

_Benchmark performed 2026-05-10 on Darwin, arm64, 10 cores, Apple M4, 24GB RAM (MacBook Air)._
_OS: macOS 15.7.3._
_Java: openjdk version "25.0.2" 2026-01-20 LTS._

To reproduce (requires `brew install nghttp2`):
```bash
cd benchmarks
./run-benchmarks.sh --scenarios h2-hello,h2-high-concurrency
./update-readme.sh
```
<!-- H2-BENCHMARK-END -->

See [benchmarks/README.md](benchmarks/README.md) for full usage and options.

## Todos and Roadmap

### Server tasks

* [x] Basic HTTP 1.1
* [x] Support Accept-Encoding (gzip, deflate), by default and per response options.
* [x] Support Content-Encoding (gzip, deflate)
* [x] Support Keep-Alive
* [x] Support Expect-Continue 100
* [x] Support Transfer-Encoding: chunked on request for streaming.
* [x] Support Transfer-Encoding: chunked on response
* [x] Support cookies in request and response
* [x] Support form data
* [x] Support multipart form data
* [x] Support TLS
* [ ] Support trailers
* [ ] Support HTTP 2

### Client tasks

* [ ] Basic HTTP 1.1
* [ ] Support Keep-Alive
* [ ] Support TLS
* [ ] Support Expect-Continue 100
* [ ] Support chunked request and response
* [ ] Support streaming entity bodies
* [ ] Support form data
* [ ] Support multipart form data
* [ ] Support HTTP 2

## FAQ

### Why virtual threads and not NIO?

Let's face it, NIO is insanely complex to write and maintain. The first three versions of FusionAuth's `java-http` (the library this project is based on) used NIO with non-blocking selectors. We encountered a ton bugs, performance issues, etc. If you compare the `0.3-maintenance` branch with `main` of that project, you'll quickly see that switching to virtual threads and standard blocking I/O made our code **MUCH** simpler. 

Therefore, when we moved the HTTP server out of FusionAuth and into The Latte Project, we just continued along the path with virtual threads.

## Helping out

We are looking for Java developers that are interested in helping us build the client and server. If you know a ton about networks and protocols and love writing clean, high-performance Java, contact us via GitHub.

## Building with Latte

**Note:** This project uses the Latte CLI tool. The Latte Project website has detailed instructions on installing and using Latte:

https://lattejava.org

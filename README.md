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
| Latte http     |      105,574 | 0
30.096727
30.088667 |              2.72 |             52.83 |        100.0% |
| Latte http     |      107,446 | 0
30.096727
30.088667 |              2.43 |             52.36 |        101.7% |
| Latte http     |      106,799 | 0
30.096727
30.088667 |              2.34 |             52.28 |        101.1% |
| Jetty          |      107,697 | 0
30.097703
30.095233 |              3.12 |             62.33 |        102.0% |
| Jetty          |      109,394 | 0
30.097703
30.095233 |              1.93 |             36.25 |        103.6% |
| Jetty          |      108,551 | 0
30.097703
30.095233 |              2.18 |             46.13 |        102.8% |
| Netty          |       76,391 | 0
30.082156
30.080803 |              2.09 |             27.94 |         72.3% |
| Netty          |       67,544 | 0
30.082156
30.080803 |              2.17 |             27.02 |         63.9% |
| Netty          |       68,297 | 0
30.082156
30.080803 |              2.35 |             33.39 |         64.6% |
| Apache Tomcat  |      108,102 | 0
30.017092
30.108437 |              2.71 |             66.58 |        102.3% |
| Apache Tomcat  |      106,415 | 0
30.017092
30.108437 |              2.06 |             42.15 |        100.7% |
| Apache Tomcat  |       98,576 | 0
30.017092
30.108437 |              2.52 |             54.73 |         93.3% |

#### Under stress (1,000 concurrent connections)

| Server         | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|----------------|-------------:|-------------:|-----------------:|-----------------:|--------------:|
| Latte http     |      109,902 | 0
30.094795
30.07674 |             11.07 |             95.25 |        100.0% |
| Latte http     |      111,234 | 0
30.094795
30.07674 |             10.95 |             95.87 |        101.2% |
| Latte http     |      110,389 | 0
30.094795
30.07674 |             11.01 |             95.94 |        100.4% |
| Jetty          |      111,408 | 0
30.103014
30.102409 |             13.00 |            127.57 |        101.3% |
| Jetty          |      105,841 | 0
30.103014
30.102409 |             10.98 |             87.65 |         96.3% |
| Jetty          |      102,883 | 0
30.103014
30.102409 |             11.50 |             99.94 |         93.6% |
| Netty          |       69,248 | 0
30.10154
30.101195 |             15.49 |             81.56 |         63.0% |
| Netty          |       72,079 | 0
30.10154
30.101195 |             14.35 |             63.20 |         65.5% |
| Netty          |       68,830 | 0
30.10154
30.101195 |             15.55 |             78.61 |         62.6% |
| Apache Tomcat  |       92,349 | 0
30.09523
30.057853 |             12.70 |             96.17 |         84.0% |
| Apache Tomcat  |       90,597 | 0
30.09523
30.057853 |             13.11 |            106.60 |         82.4% |
| Apache Tomcat  |       91,855 | 0
30.09523
30.057853 |             12.85 |            103.00 |         83.5% |

_JDK HttpServer (`com.sun.net.httpserver`) is included as a baseline since it ships with the JDK and requires no dependencies. However, as the stress test shows, it is not suitable for production workloads — it suffers significant failures under high concurrency._

_Benchmark performed 2026-05-19 on Darwin, arm64, 10 cores, Apple M4, 24GB RAM (MacBook Air)._
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


#### h2-high-stream-concurrency (10 conns × 100 streams (many-streams-per-conn))

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      416,662 |     41 |              2.49 |             20.65 |        100.0% |
| Latte http    |      427,828 |     79 |              2.27 |             17.28 |        102.6% |
| Latte http    |      423,837 |     51 |              2.28 |             17.91 |        101.7% |
| Jetty         |       97,005 | 10391862 |              2.30 |             18.61 |         23.2% |
| Jetty         |      120,511 | 11853706 |              1.94 |             15.75 |         28.9% |
| Jetty         |      123,707 | 11855823 |              1.87 |             15.59 |         29.6% |
| Netty         |      360,943 |      0 |              3.18 |             32.57 |         86.6% |
| Netty         |      424,843 |      0 |              2.26 |             18.10 |        101.9% |
| Netty         |      447,247 |      0 |              2.14 |             16.17 |        107.3% |
| Apache Tomcat |       96,950 |      0 |              9.96 |             75.42 |         23.2% |
| Apache Tomcat |      124,105 |      0 |              7.24 |             34.00 |         29.7% |
| Apache Tomcat |       39,812 |      0 |              8.09 |             54.32 |          9.5% |

_JDK HttpServer does not support HTTP/2 and is excluded from h2 results._

_Benchmark performed 2026-05-19 on Darwin, arm64, 10 cores, Apple M4, 24GB RAM (MacBook Air)._
_OS: macOS 15.7.3._
_Java: openjdk version "25.0.2" 2026-01-20 LTS._

To reproduce (requires `brew install nghttp2`):
```bash
cd benchmarks
./run-benchmarks.sh --scenarios h2-hello,h2-high-stream-concurrency,h2-tls-hello,h2-tls-high-stream-concurrency
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

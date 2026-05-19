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
| Latte http     |      108,797 | 0
30.083963
30.097131 |              2.09 |             45.00 |        100.0% |
| Latte http     |      110,284 | 0
30.083963
30.097131 |              1.94 |             36.37 |        101.3% |
| Latte http     |      110,257 | 0
30.083963
30.097131 |              1.69 |             29.88 |        101.3% |
| Jetty          |      109,498 | 0
30.060432
30.099789 |              2.24 |             46.98 |        100.6% |
| Jetty          |      109,948 | 0
30.060432
30.099789 |              2.20 |             48.53 |        101.0% |
| Jetty          |      109,688 | 0
30.060432
30.099789 |              1.89 |             36.89 |        100.8% |
| Netty          |      118,376 | 0
30.096132
30.094708 |              1.71 |             34.88 |        108.8% |
| Netty          |      118,583 | 0
30.096132
30.094708 |              1.57 |             28.59 |        108.9% |
| Netty          |      118,791 | 0
30.096132
30.094708 |              1.66 |             30.58 |        109.1% |
| Apache Tomcat  |      106,284 | 0
30.056908
30.083028 |              2.01 |             42.86 |         97.6% |
| Apache Tomcat  |      108,619 | 0
30.056908
30.083028 |              2.46 |             59.76 |         99.8% |
| Apache Tomcat  |      107,101 | 0
30.056908
30.083028 |              1.87 |             35.77 |         98.4% |

#### Under stress (1,000 concurrent connections)

| Server         | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|----------------|-------------:|-------------:|-----------------:|-----------------:|--------------:|
| Latte http     |      109,981 | 0
30.074827
30.10052 |             12.18 |            122.79 |        100.0% |
| Latte http     |      108,223 | 0
30.074827
30.10052 |             11.62 |            107.71 |         98.4% |
| Latte http     |      108,003 | 0
30.074827
30.10052 |             11.65 |            105.30 |         98.2% |
| Jetty          |      107,708 | 0
30.087102
30.084869 |             10.80 |             88.47 |         97.9% |
| Jetty          |      107,401 | 0
30.087102
30.084869 |             11.06 |             95.17 |         97.6% |
| Jetty          |      104,184 | 0
30.087102
30.084869 |             11.21 |             91.36 |         94.7% |
| Netty          |      118,564 | 0
30.092719
30.054519 |              9.53 |             70.41 |        107.8% |
| Netty          |      116,723 | 0
30.092719
30.054519 |              9.29 |             55.93 |        106.1% |
| Netty          |      115,749 | 0
30.092719
30.054519 |              9.66 |             64.79 |        105.2% |
| Apache Tomcat  |      105,949 | 0
30.087836
30.092695 |             11.35 |             98.63 |         96.3% |
| Apache Tomcat  |      105,312 | 0
30.087836
30.092695 |             11.16 |             91.29 |         95.7% |
| Apache Tomcat  |      104,869 | 0
30.087836
30.092695 |             11.47 |            100.30 |         95.3% |

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

#### h2-high-stream-concurrency (10 conns × 100 streams)

Backend / proxy shape: many streams per connection. Netty's home field (event-loop demuxes inline).

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      402,164 |     34 |              2.58 |             24.25 |        100.0% |
| Latte http    |      412,814 |     29 |              2.35 |             19.91 |        102.6% |
| Latte http    |      411,817 |     22 |              2.33 |             19.22 |        102.4% |
| Jetty         |       87,425 | 9778515 |              2.42 |             19.00 |         21.7% |
| Jetty         |      123,293 | 11423204 |              1.94 |             13.19 |         30.6% |
| Jetty         |      126,898 | 11478196 |              1.87 |             12.83 |         31.5% |
| Netty         |      798,743 |      0 |              1.58 |             14.72 |        198.6% |
| Netty         |      797,113 |      0 |              1.20 |              4.41 |        198.2% |
| Netty         |      888,556 |      0 |              1.07 |              2.88 |        220.9% |
| Apache Tomcat |      130,045 |      0 |              7.56 |             47.92 |         32.3% |
| Apache Tomcat |      148,239 |      0 |              6.19 |             27.01 |         36.8% |
| Apache Tomcat |      149,968 |      0 |              5.90 |             22.13 |         37.2% |

#### h2-high-connection-concurrency (500 conns × 2 streams)

Browser / CDN shape: same 1000 in-flight, but many sockets with few streams each.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      191,980 |      0 |              3.44 |             11.12 |        100.0% |
| Latte http    |      189,268 |      0 |              3.45 |             10.77 |         98.5% |
| Latte http    |      191,117 |      0 |              3.43 |             10.23 |         99.5% |
| Jetty         |      161,026 | 308595 |              3.84 |             18.17 |         83.8% |
| Jetty         |      149,013 | 263443 |              4.30 |             24.38 |         77.6% |
| Jetty         |      144,496 | 259575 |              4.48 |             26.30 |         75.2% |
| Netty         |      272,410 |      0 |              2.47 |              9.06 |        141.8% |
| Netty         |      268,324 |      0 |              2.49 |              9.00 |        139.7% |
| Netty         |      261,282 |      0 |              2.55 |              9.29 |        136.0% |
| Apache Tomcat |      109,036 |      0 |              7.59 |             39.59 |         56.7% |
| Apache Tomcat |      101,057 |      0 |              7.82 |             36.68 |         52.6% |
| Apache Tomcat |      107,790 |      0 |              7.38 |             30.19 |         56.1% |

#### h2-compute (CPU-bound, chained SHA-256)

Handler does ~500us–1ms of real CPU work per request. Protocol overhead becomes <20% of cost; all servers should converge near the CPU-bound ceiling.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |       24,996 |     14 |             39.94 |            224.43 |        100.0% |
| Latte http    |       25,359 |      4 |             39.38 |            225.81 |        101.4% |
| Latte http    |       24,967 |      2 |             40.00 |            239.47 |         99.8% |
| Jetty         |       12,244 | 278419 |             36.11 |            220.42 |         48.9% |
| Jetty         |       12,631 | 301098 |             32.82 |            183.20 |         50.5% |
| Jetty         |       15,493 | 263514 |             30.64 |            190.76 |         61.9% |
| Netty         |       24,836 |      0 |             40.16 |            207.17 |         99.3% |
| Netty         |       24,555 |      0 |             40.63 |            210.44 |         98.2% |
| Netty         |       25,716 |      0 |             38.79 |            200.23 |        102.8% |
| Apache Tomcat |       16,297 |      0 |             60.84 |            933.82 |         65.2% |
| Apache Tomcat |       23,598 |      0 |             41.92 |            306.58 |         94.4% |
| Apache Tomcat |       23,024 |      0 |             43.08 |            298.45 |         92.1% |

#### h2-io (blocking-IO, Thread.sleep 10ms)

Simulates a downstream call. Worker-pool servers (Tomcat, Jetty) hit their default pool size as a hard ceiling; virtual-thread / event-loop servers don't.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |       75,804 |    138 |             13.03 |             31.56 |        100.0% |
| Latte http    |       75,758 |    138 |             13.07 |             34.93 |         99.9% |
| Latte http    |       77,151 |    107 |             12.74 |             30.82 |        101.7% |
| Jetty         |       11,249 |  84764 |             68.82 |            238.04 |         14.8% |
| Jetty         |       11,305 |  85573 |             68.63 |            233.18 |         14.9% |
| Jetty         |       10,530 |  81843 |             72.77 |            236.41 |         13.8% |
| Netty         |       78,023 |      0 |             12.76 |             28.17 |        102.9% |
| Netty         |       78,059 |      0 |             12.70 |             27.47 |        102.9% |
| Netty         |       78,021 |      0 |             12.80 |             34.93 |        102.9% |
| Apache Tomcat |       14,966 |      0 |             66.66 |            125.24 |         19.7% |
| Apache Tomcat |       14,962 |      0 |             66.71 |            124.59 |         19.7% |
| Apache Tomcat |       14,761 |      0 |             67.62 |            147.34 |         19.4% |

#### h2-stream (128KB response, per-chunk flush)

Handler writes 16 × 8KB chunks with explicit flush() between. Tests honor-flush wire path — Latte/Jetty emit per-chunk DATA frames; Tomcat coalesces; Netty sends FullHttpResponse (no chunking).

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |        4,095 |      0 |             24.95 |            187.80 |        100.0% |
| Latte http    |        4,096 |      0 |             18.36 |            159.42 |        100.0% |
| Latte http    |        4,097 |      0 |             20.81 |            176.47 |        100.0% |
| Jetty         |          929 | 474708 |             57.51 |            144.15 |         22.6% |
| Jetty         |       12,665 | 6346773 |              4.69 |             43.76 |        309.2% |
| Jetty         |       14,384 | 7315271 |              4.11 |             33.98 |        351.2% |
| Netty         |       32,169 |      0 |             31.00 |            174.38 |        785.4% |
| Netty         |       31,563 |      0 |             31.55 |            182.97 |        770.6% |
| Netty         |       30,517 |      0 |             32.64 |            182.65 |        745.1% |
| Apache Tomcat |          336 |      0 |             31.09 |            137.81 |          8.2% |
| Apache Tomcat |        1,434 |      0 |             26.92 |            163.85 |         35.0% |
| Apache Tomcat |           40 |      0 |             19.70 |             36.42 |           .9% |

#### h2-large-response (128KB response, one-shot)

Handler writes the body once; server chooses framing. Counterpart to h2-stream — the gap quantifies the cost of honoring per-chunk flush.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |        4,102 |    136 |             23.82 |            180.66 |        100.0% |
| Latte http    |        4,105 |    364 |             27.61 |            236.29 |        100.0% |
| Latte http    |        4,103 |    238 |             25.95 |            249.78 |        100.0% |
| Jetty         |       18,550 | 15482622 |              2.66 |             24.76 |        452.1% |
| Jetty         |       19,408 | 14119955 |              2.68 |             22.88 |        473.0% |
| Jetty         |       16,241 | 12892714 |              2.82 |             23.03 |        395.8% |
| Netty         |       29,733 |      0 |             33.57 |            193.57 |        724.7% |
| Netty         |       29,529 |      0 |             33.76 |            192.68 |        719.8% |
| Netty         |       29,995 |      0 |             33.30 |            192.97 |        731.1% |
| Apache Tomcat |       29,717 |      0 |             25.90 |            147.46 |        724.3% |
| Apache Tomcat |       19,897 |      0 |             22.69 |            136.51 |        485.0% |
| Apache Tomcat |        7,250 |      0 |             28.85 |            186.56 |        176.7% |

_JDK HttpServer does not support HTTP/2 and is excluded from h2 results._

_Benchmark performed 2026-05-19 on Darwin, arm64, 10 cores, Apple M4, 24GB RAM (MacBook Air)._
_OS: macOS 15.7.3._
_Java: openjdk version "25.0.2" 2026-01-20 LTS._

To reproduce (requires `brew install nghttp2`):
```bash
cd benchmarks
./run-benchmarks.sh --scenarios h2-hello,h2-high-stream-concurrency,h2-high-connection-concurrency,h2-compute,h2-io,h2-stream,h2-large-response,h2-tls-hello,h2-tls-high-stream-concurrency
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

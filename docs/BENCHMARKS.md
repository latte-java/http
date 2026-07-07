# Latte HTTP — Benchmarks

Auto-generated benchmark results. To regenerate, run `./benchmarks/run-benchmarks.sh` followed by `./benchmarks/update-readme.sh`. Methodology, scenario design, and per-vendor handler-implementation notes live in [`benchmarks/README.md`](../benchmarks/README.md).

## Methodology in one paragraph

Each scenario runs against six servers — Latte (`self`), Jetty, Tomcat, Netty, Undertow (NIO/XNIO), and the JDK's built-in HttpServer (HTTP/1.1 scenarios only, as a zero-dependency baseline) — with identical wire-level load (same `wrk` or `h2load` invocation, same request shape). Numbers below are a single 30-second trial per scenario. The fair-rerun protocol runs each vendor in isolation with a machine cool-down between vendors, to remove accumulated-thermal bias from sustained multi-vendor matrices. The `benchmarks/README.md` document describes what each scenario is designed to expose and which vendor handler-implementation asymmetries are deliberate.

## How to read these tables

- **RPS** is the headline throughput. Higher is better.
- **vs Latte http** column compares each vendor against Latte for that scenario.
- **Errors** column flags scenarios where a vendor's HTTP/2 implementation produced large wire-error counts — typically a benchmark-config issue on that vendor's side (Jetty's h2c numbers are persistently affected this way; the throughput reading is unreliable for that vendor regardless).
- Differences within ±10% across trials are within normal trial-to-trial noise. Treat anything tighter than that as a tie.

---

<!-- H1-BENCHMARK-START -->
### HTTP/1.1 (wrk)

#### Hello scenario (low concurrency, baseline)

| Server         | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|----------------|-------------:|-------------:|-----------------:|-----------------:|--------------:|
| Latte http     |      107,828 |            0 |              0.88 |              1.00 |        100.0% |
| JDK HttpServer |      102,944 |            0 |              0.91 |              1.28 |         95.4% |
| Jetty          |      103,542 |            0 |              0.95 |              1.05 |         96.0% |
| Netty          |      109,445 |            0 |              0.87 |              1.02 |        101.4% |
| Apache Tomcat  |      107,241 |            0 |              0.87 |              1.81 |         99.4% |
| Undertow       |      105,917 |            0 |              0.93 |              1.29 |         98.2% |

#### Under stress (1,000 concurrent connections)

| Server         | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|----------------|-------------:|-------------:|-----------------:|-----------------:|--------------:|
| Latte http     |      107,665 |            0 |              9.19 |              9.73 |        100.0% |
| JDK HttpServer |       51,861 |      19719.8 |              6.40 |             29.41 |         48.1% |
| Jetty          |      103,723 |            0 |              9.56 |             10.28 |         96.3% |
| Netty          |      101,203 |            0 |              9.80 |             10.65 |         93.9% |
| Apache Tomcat  |       94,663 |            0 |             10.30 |             13.94 |         87.9% |
| Undertow       |      104,627 |            0 |              9.44 |             10.08 |         97.1% |

_JDK HttpServer (`com.sun.net.httpserver`) is included as a baseline since it ships with the JDK and requires no dependencies. However, as the stress test shows, it is not suitable for production workloads — it suffers significant failures under high concurrency._

_Benchmark performed 2026-07-07 on Darwin, arm64, 10 cores, Apple M4, 32GB RAM (MacBook Air)._
_OS: macOS 26.5.1._
_Java: openjdk version "25.0.2" 2026-01-20 LTS._

To reproduce:
```bash
cd benchmarks
./run-benchmarks.sh --scenarios hello,high-concurrency
./update-readme.sh
```
<!-- H1-BENCHMARK-END -->

---

<!-- H2-BENCHMARK-START -->
### HTTP/2 (h2load)

#### h2-hello (1 connection × 100 streams)

Baseline h2 throughput — single connection, many concurrent streams.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      292,400 |      0 |              0.33 |              0.61 |        100.0% |
| Jetty         |       14,085 | 8704553 |              0.30 |              0.49 |          4.8% |
| Netty         |      286,653 |      0 |              0.34 |              0.77 |         98.0% |
| Apache Tomcat |       57,475 |      0 |              1.76 |              3.27 |         19.6% |
| Undertow      |      125,668 |      0 |              0.80 |              1.21 |         42.9% |

#### h2-high-stream-concurrency (10 conns × 100 streams)

Backend / proxy shape: many streams per connection. Netty's home field (event-loop demuxes inline).

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      568,165 |      0 |              1.75 |              8.00 |        100.0% |
| Jetty         |      133,186 | 10255249 |              1.96 |             15.54 |         23.4% |
| Netty         |    1,085,233 |      0 |              0.85 |              2.48 |        191.0% |
| Apache Tomcat |      161,319 |      0 |              5.73 |             20.58 |         28.3% |
| Undertow      |      277,218 |      0 |              3.59 |              4.97 |         48.7% |

#### h2-high-connection-concurrency (500 conns × 2 streams)

Browser / CDN shape: same 1000 in-flight, but many sockets with few streams each.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      190,632 |      0 |              4.57 |             10.27 |        100.0% |
| Jetty         |      174,173 | 342512 |              3.77 |             26.58 |         91.3% |
| Netty         |      332,131 |      0 |              2.16 |              9.28 |        174.2% |
| Apache Tomcat |      132,403 |      0 |              6.46 |             23.98 |         69.4% |
| Undertow      |      199,987 |      0 |              4.18 |             16.63 |        104.9% |

#### h2-compute (CPU-bound, chained SHA-256)

Handler does ~500us–1ms of real CPU work per request. Protocol overhead becomes <20% of cost; all servers should converge near the CPU-bound ceiling.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |       28,494 |      0 |             35.02 |            143.95 |        100.0% |
| Jetty         |       13,245 | 282892 |             33.39 |            202.24 |         46.4% |
| Netty         |       32,896 |      0 |             30.33 |             36.53 |        115.4% |
| Apache Tomcat |       21,691 |      0 |             45.84 |            402.25 |         76.1% |
| Undertow      |       27,157 |      0 |             36.79 |             52.37 |         95.3% |

#### h2-io (blocking-IO, Thread.sleep 10ms)

Simulates a downstream call. Worker-pool servers (Tomcat, Jetty) hit their default pool size as a hard ceiling; virtual-thread / event-loop servers don't.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |       62,700 |      0 |             15.78 |             18.58 |        100.0% |
| Jetty         |       10,946 |  86567 |             69.60 |            190.60 |         17.4% |
| Netty         |       77,834 |      0 |             12.85 |             14.44 |        124.1% |
| Apache Tomcat |       15,257 |      0 |             65.45 |             71.33 |         24.3% |
| Undertow      |        5,812 |      0 |            171.55 |            190.78 |          9.2% |

#### h2-stream (128KB response, per-chunk flush)

Handler writes 16 × 8KB chunks with explicit flush() between. Tests honor-flush wire path — Latte/Jetty emit per-chunk DATA frames; Tomcat coalesces; Netty sends FullHttpResponse (no chunking).

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |       70,642 |      0 |             10.38 |             38.86 |        100.0% |
| Jetty         |       16,842 | 7750451 |              3.66 |             24.09 |         23.8% |
| Netty         |       34,712 |      0 |             28.70 |             53.15 |         49.1% |
| Apache Tomcat |          181 |      0 |             36.05 |            139.29 |           .2% |
| Undertow      |       20,934 |      0 |             47.70 |             58.54 |         29.6% |

#### h2-large-response (128KB response, one-shot)

Handler writes the body once; server chooses framing. Counterpart to h2-stream — the gap quantifies the cost of honoring per-chunk flush.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |       55,151 |      0 |             15.53 |             47.71 |        100.0% |
| Jetty         |       23,467 | 16353770 |              2.37 |             14.91 |         42.5% |
| Netty         |       33,943 |      0 |             29.38 |             51.42 |         61.5% |
| Apache Tomcat |        9,221 |      0 |             21.66 |             96.74 |         16.7% |
| Undertow      |       35,720 |      0 |             27.94 |             33.90 |         64.7% |

#### h2-tls-hello (TLS+ALPN, 1 connection × 100 streams)

Same shape as h2-hello but over TLS+ALPN.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      413,050 |      0 |              0.20 |              0.44 |        100.0% |
| Jetty         |       30,116 | 3898688 |              0.62 |              0.86 |          7.2% |
| Netty         |      283,100 |      0 |              0.34 |              0.48 |         68.5% |
| Apache Tomcat |       48,420 |      0 |              2.06 |              3.36 |         11.7% |
| Undertow      |      122,794 |      0 |              0.81 |              1.28 |         29.7% |

#### h2-tls-high-stream-concurrency (TLS+ALPN, 10 conns × 100 streams)

Same shape as h2-high-stream-concurrency but over TLS+ALPN.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      771,056 |      0 |              0.97 |              5.19 |        100.0% |
| Jetty         |      102,362 | 11711525 |              1.94 |             15.12 |         13.2% |
| Netty         |    1,031,687 |      0 |              0.91 |              2.68 |        133.8% |
| Apache Tomcat |      113,956 |      0 |              7.55 |             18.66 |         14.7% |
| Undertow      |      284,886 |      0 |              3.50 |              5.23 |         36.9% |

_TLS scenarios use a self-signed certificate at `benchmarks/certs/server.crt` (benchmark fixture only). All four servers terminate TLS and use ALPN to negotiate h2._

_JDK HttpServer does not support HTTP/2 and is excluded from h2 results._

_Benchmark performed 2026-07-07 on Darwin, arm64, 10 cores, Apple M4, 32GB RAM (MacBook Air)._
_OS: macOS 26.5.1._
_Java: openjdk version "25.0.2" 2026-01-20 LTS._

To reproduce (requires `brew install nghttp2`):
```bash
cd benchmarks
./run-benchmarks.sh --scenarios h2-hello,h2-high-stream-concurrency,h2-high-connection-concurrency,h2-compute,h2-io,h2-stream,h2-large-response,h2-tls-hello,h2-tls-high-stream-concurrency
./update-readme.sh
```
<!-- H2-BENCHMARK-END -->

---

## Architectural context

See [`docs/design/2026-05-05-HTTP2.md`](design/2026-05-05-HTTP2.md) for the dated performance-findings sections, including JFR-profile-driven CPU hotspot analysis and the current Plan F item proposing writer-thread batching for h2 DATA emission.

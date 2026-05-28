# Latte HTTP — Benchmarks

Auto-generated benchmark results. To regenerate, run `./benchmarks/run-benchmarks.sh` followed by `./benchmarks/update-readme.sh`. Methodology, scenario design, and per-vendor handler-implementation notes live in [`benchmarks/README.md`](../benchmarks/README.md).

## Methodology in one paragraph

Each scenario runs against six servers — Latte (`self`), Jetty, Tomcat, Netty, Helidon WebServer (virtual-thread + blocking I/O — Latte's architectural counterpart), and Undertow (NIO/XNIO) — with identical wire-level load (same `wrk` or `h2load` invocation, same request shape). Numbers below are best-of-3 trials × 30 seconds. The fair-rerun protocol runs each vendor in isolation with a machine cool-down between vendors, to remove accumulated-thermal bias from sustained multi-vendor matrices. The `benchmarks/README.md` document describes what each scenario is designed to expose and which vendor handler-implementation asymmetries are deliberate.

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
| Latte http     |      112,387 |            0 |              0.97 |              7.02 |        100.0% |
| Latte http     |      111,957 |            0 |              1.01 |              7.69 |         99.6% |
| Latte http     |      111,266 |            0 |              1.04 |              9.11 |         99.0% |
| Helidon        |      116,619 |            0 |              0.90 |              3.86 |        103.7% |
| Helidon        |      117,528 |            0 |              0.87 |              2.62 |        104.5% |
| Helidon        |      114,508 |            0 |              0.99 |              8.62 |        101.8% |
| Jetty          |      109,498 |            0 |              2.24 |             46.98 |         97.4% |
| Jetty          |      109,948 |            0 |              2.20 |             48.53 |         97.8% |
| Jetty          |      109,688 |            0 |              1.89 |             36.89 |         97.5% |
| Netty          |      118,376 |            0 |              1.71 |             34.88 |        105.3% |
| Netty          |      118,583 |            0 |              1.57 |             28.59 |        105.5% |
| Netty          |      118,791 |            0 |              1.66 |             30.58 |        105.6% |
| Apache Tomcat  |      106,284 |            0 |              2.01 |             42.86 |         94.5% |
| Apache Tomcat  |      108,619 |            0 |              2.46 |             59.76 |         96.6% |
| Apache Tomcat  |      107,101 |            0 |              1.87 |             35.77 |         95.2% |
| Undertow       |      103,972 |            0 |              7.42 |            108.33 |         92.5% |
| Undertow       |      109,097 |            0 |              1.53 |             25.15 |         97.0% |
| Undertow       |      109,088 |            0 |              1.49 |             24.55 |         97.0% |

#### Under stress (1,000 concurrent connections)

| Server         | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|----------------|-------------:|-------------:|-----------------:|-----------------:|--------------:|
| Latte http     |      111,592 |            0 |              9.06 |             25.22 |        100.0% |
| Latte http     |      111,892 |            0 |              8.98 |             20.41 |        100.2% |
| Latte http     |      110,539 |            0 |              9.11 |             25.02 |         99.0% |
| Helidon        |      105,242 |            0 |             14.21 |            237.63 |         94.3% |
| Helidon        |      112,201 |            0 |              8.99 |             24.65 |        100.5% |
| Helidon        |        2,568 |          2.8 |             10.59 |             31.79 |          2.3% |
| Jetty          |      107,708 |            0 |             10.80 |             88.47 |         96.5% |
| Jetty          |      107,401 |            0 |             11.06 |             95.17 |         96.2% |
| Jetty          |      104,184 |            0 |             11.21 |             91.36 |         93.3% |
| Netty          |      118,564 |            0 |              9.53 |             70.41 |        106.2% |
| Netty          |      116,723 |            0 |              9.29 |             55.93 |        104.5% |
| Netty          |      115,749 |            0 |              9.66 |             64.79 |        103.7% |
| Apache Tomcat  |      105,949 |            0 |             11.35 |             98.63 |         94.9% |
| Apache Tomcat  |      105,312 |            0 |             11.16 |             91.29 |         94.3% |
| Apache Tomcat  |      104,869 |            0 |             11.47 |            100.30 |         93.9% |
| Undertow       |      107,930 |            0 |              9.89 |             51.31 |         96.7% |
| Undertow       |      107,130 |            0 |             10.21 |             60.49 |         96.0% |
| Undertow       |      107,446 |            0 |             10.55 |             69.91 |         96.2% |

_JDK HttpServer (`com.sun.net.httpserver`) is included as a baseline since it ships with the JDK and requires no dependencies. However, as the stress test shows, it is not suitable for production workloads — it suffers significant failures under high concurrency._

_Benchmark performed 2026-05-21 on Darwin, arm64, 10 cores, Apple M4, 24GB RAM (MacBook Air)._
_OS: macOS 15.7.3._
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
| Latte http    |      258,762 |      0 |              0.38 |              0.94 |        100.0% |
| Latte http    |      153,719 |      0 |              0.52 |              1.35 |         59.4% |
| Latte http    |       10,932 |      0 |              1.56 |              4.98 |          4.2% |
| Helidon       |      150,127 |      0 |              0.67 |              1.44 |         58.0% |
| Helidon       |      149,625 |      0 |              0.66 |              1.06 |         57.8% |
| Helidon       |      150,157 |      0 |              0.66 |              1.03 |         58.0% |
| Undertow      |      114,307 |      0 |              0.88 |              1.53 |         44.1% |
| Undertow      |      113,974 |      0 |              0.88 |              1.37 |         44.0% |
| Undertow      |      115,314 |      0 |              0.86 |              1.34 |         44.5% |

#### h2-high-stream-concurrency (10 conns × 100 streams)

Backend / proxy shape: many streams per connection. Netty's home field (event-loop demuxes inline).

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      266,846 |     59 |              3.35 |             42.36 |        100.0% |
| Latte http    |      455,208 |     17 |              2.21 |             20.54 |        170.5% |
| Latte http    |      454,312 |     40 |              2.14 |             15.48 |        170.2% |
| Helidon       |      634,016 |      0 |              1.52 |             13.12 |        237.5% |
| Helidon       |      645,248 |      0 |              1.47 |             14.49 |        241.8% |
| Helidon       |      629,049 |      0 |              1.49 |             13.67 |        235.7% |
| Jetty         |       87,425 | 9778515 |              2.42 |             19.00 |         32.7% |
| Jetty         |      123,293 | 11423204 |              1.94 |             13.19 |         46.2% |
| Jetty         |      126,898 | 11478196 |              1.87 |             12.83 |         47.5% |
| Netty         |      798,743 |      0 |              1.58 |             14.72 |        299.3% |
| Netty         |      797,113 |      0 |              1.20 |              4.41 |        298.7% |
| Netty         |      888,556 |      0 |              1.07 |              2.88 |        332.9% |
| Apache Tomcat |      130,045 |      0 |              7.56 |             47.92 |         48.7% |
| Apache Tomcat |      148,239 |      0 |              6.19 |             27.01 |         55.5% |
| Apache Tomcat |      149,968 |      0 |              5.90 |             22.13 |         56.1% |
| Undertow      |      151,911 |      0 |              5.22 |             18.94 |         56.9% |
| Undertow      |      175,103 |      0 |              5.36 |             10.00 |         65.6% |
| Undertow      |      137,276 |      0 |              6.49 |             10.23 |         51.4% |

#### h2-high-connection-concurrency (500 conns × 2 streams)

Browser / CDN shape: same 1000 in-flight, but many sockets with few streams each.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      227,290 |      0 |              2.89 |             12.14 |        100.0% |
| Latte http    |      219,433 |      0 |              2.96 |             10.75 |         96.5% |
| Latte http    |      222,975 |      0 |              2.93 |             10.03 |         98.1% |
| Helidon       |      166,024 |      0 |              3.89 |             28.82 |         73.0% |
| Helidon       |      180,630 |      0 |              3.59 |             23.92 |         79.4% |
| Helidon       |      136,502 |      0 |              4.85 |             34.02 |         60.0% |
| Jetty         |      161,026 | 308595 |              3.84 |             18.17 |         70.8% |
| Jetty         |      149,013 | 263443 |              4.30 |             24.38 |         65.5% |
| Jetty         |      144,496 | 259575 |              4.48 |             26.30 |         63.5% |
| Netty         |      272,410 |      0 |              2.47 |              9.06 |        119.8% |
| Netty         |      268,324 |      0 |              2.49 |              9.00 |        118.0% |
| Netty         |      261,282 |      0 |              2.55 |              9.29 |        114.9% |
| Apache Tomcat |      109,036 |      0 |              7.59 |             39.59 |         47.9% |
| Apache Tomcat |      101,057 |      0 |              7.82 |             36.68 |         44.4% |
| Apache Tomcat |      107,790 |      0 |              7.38 |             30.19 |         47.4% |
| Undertow      |       83,232 |      0 |             12.82 |            116.98 |         36.6% |
| Undertow      |      171,292 |      0 |              4.61 |             30.65 |         75.3% |
| Undertow      |      188,288 |      0 |              4.05 |             23.01 |         82.8% |

#### h2-compute (CPU-bound, chained SHA-256)

Handler does ~500us–1ms of real CPU work per request. Protocol overhead becomes <20% of cost; all servers should converge near the CPU-bound ceiling.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |       28,196 |      1 |             35.38 |            173.35 |        100.0% |
| Latte http    |       27,555 |    202 |             36.23 |            154.98 |         97.7% |
| Latte http    |       26,306 |      5 |             37.96 |            170.94 |         93.2% |
| Helidon       |       20,636 |      0 |             48.37 |            274.48 |         73.1% |
| Helidon       |       23,376 |      0 |             42.64 |            201.88 |         82.9% |
| Helidon       |       21,943 |      0 |             45.51 |            241.60 |         77.8% |
| Jetty         |       12,244 | 278419 |             36.11 |            220.42 |         43.4% |
| Jetty         |       12,631 | 301098 |             32.82 |            183.20 |         44.7% |
| Jetty         |       15,493 | 263514 |             30.64 |            190.76 |         54.9% |
| Netty         |       24,836 |      0 |             40.16 |            207.17 |         88.0% |
| Netty         |       24,555 |      0 |             40.63 |            210.44 |         87.0% |
| Netty         |       25,716 |      0 |             38.79 |            200.23 |         91.2% |
| Apache Tomcat |       16,297 |      0 |             60.84 |            933.82 |         57.7% |
| Apache Tomcat |       23,598 |      0 |             41.92 |            306.58 |         83.6% |
| Apache Tomcat |       23,024 |      0 |             43.08 |            298.45 |         81.6% |
| Undertow      |       29,909 |      0 |             33.34 |            107.59 |        106.0% |
| Undertow      |       30,393 |      0 |             32.89 |            100.15 |        107.7% |
| Undertow      |       30,517 |      0 |             32.70 |            100.03 |        108.2% |

#### h2-io (blocking-IO, Thread.sleep 10ms)

Simulates a downstream call. Worker-pool servers (Tomcat, Jetty) hit their default pool size as a hard ceiling; virtual-thread / event-loop servers don't.

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

#### h2-stream (128KB response, per-chunk flush)

Handler writes 16 × 8KB chunks with explicit flush() between. Tests honor-flush wire path — Latte/Jetty emit per-chunk DATA frames; Tomcat coalesces; Netty sends FullHttpResponse (no chunking).

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |        4,096 |      0 |             17.14 |             93.64 |        100.0% |
| Latte http    |        4,098 |      0 |             16.91 |            103.62 |        100.0% |
| Latte http    |        4,096 |      0 |             17.60 |            109.93 |         99.9% |
| Helidon       |       38,450 |      0 |             23.63 |            134.59 |        938.6% |
| Helidon       |       37,972 |      0 |             24.40 |            135.49 |        926.9% |
| Helidon       |       39,052 |      0 |             23.52 |            128.83 |        953.3% |
| Jetty         |          929 | 474708 |             57.51 |            144.15 |         22.6% |
| Jetty         |       12,665 | 6346773 |              4.69 |             43.76 |        309.1% |
| Jetty         |       14,384 | 7315271 |              4.11 |             33.98 |        351.1% |
| Netty         |       32,169 |      0 |             31.00 |            174.38 |        785.2% |
| Netty         |       31,563 |      0 |             31.55 |            182.97 |        770.4% |
| Netty         |       30,517 |      0 |             32.64 |            182.65 |        744.9% |
| Apache Tomcat |          336 |      0 |             31.09 |            137.81 |          8.2% |
| Apache Tomcat |        1,434 |      0 |             26.92 |            163.85 |         35.0% |
| Apache Tomcat |           40 |      0 |             19.70 |             36.42 |           .9% |
| Undertow      |       19,956 |      0 |             50.04 |            121.95 |        487.1% |
| Undertow      |       19,764 |      0 |             50.53 |            132.62 |        482.4% |
| Undertow      |       19,918 |      0 |             50.14 |            129.11 |        486.2% |

#### h2-large-response (128KB response, one-shot)

Handler writes the body once; server chooses framing. Counterpart to h2-stream — the gap quantifies the cost of honoring per-chunk flush.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |        4,101 |      0 |             19.27 |             99.85 |        100.0% |
| Latte http    |        4,097 |      0 |             17.02 |             97.09 |         99.9% |
| Latte http    |        4,105 |    126 |             18.46 |            114.26 |        100.1% |
| Helidon       |       35,502 |      0 |             25.89 |            121.80 |        865.7% |
| Helidon       |       36,600 |      0 |             25.13 |            125.42 |        892.5% |
| Helidon       |       35,647 |      0 |             25.98 |            134.03 |        869.3% |
| Jetty         |       18,550 | 15482622 |              2.66 |             24.76 |        452.3% |
| Jetty         |       19,408 | 14119955 |              2.68 |             22.88 |        473.3% |
| Jetty         |       16,241 | 12892714 |              2.82 |             23.03 |        396.0% |
| Netty         |       29,733 |      0 |             33.57 |            193.57 |        725.0% |
| Netty         |       29,529 |      0 |             33.76 |            192.68 |        720.1% |
| Netty         |       29,995 |      0 |             33.30 |            192.97 |        731.4% |
| Apache Tomcat |       29,717 |      0 |             25.90 |            147.46 |        724.6% |
| Apache Tomcat |       19,897 |      0 |             22.69 |            136.51 |        485.2% |
| Apache Tomcat |        7,250 |      0 |             28.85 |            186.56 |        176.8% |
| Undertow      |       32,030 |      0 |             31.20 |             99.33 |        781.0% |
| Undertow      |       31,830 |      0 |             31.37 |             91.27 |        776.2% |
| Undertow      |       32,276 |      0 |             30.96 |            102.22 |        787.0% |

#### h2-tls-hello (TLS+ALPN, 1 connection × 100 streams)

Same shape as h2-hello but over TLS+ALPN.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      329,314 |      0 |              0.24 |              0.87 |        100.0% |
| Latte http    |      333,185 |      0 |              0.23 |              0.35 |        101.1% |
| Latte http    |      323,024 |      0 |              0.23 |              0.46 |         98.0% |
| Helidon       |       93,093 |      0 |              1.01 |              2.57 |         28.2% |
| Helidon       |      103,957 |      0 |              0.95 |              1.69 |         31.5% |
| Helidon       |      105,912 |      0 |              0.93 |              1.53 |         32.1% |
| Undertow      |      114,638 |      0 |              0.87 |              1.54 |         34.8% |
| Undertow      |      114,852 |      0 |              0.86 |              1.28 |         34.8% |
| Undertow      |      115,418 |      0 |              0.86 |              1.25 |         35.0% |

#### h2-tls-high-stream-concurrency (TLS+ALPN, 10 conns × 100 streams)

Same shape as h2-high-stream-concurrency but over TLS+ALPN.

| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|
| Latte http    |      392,501 |      0 |              1.60 |              4.23 |        100.0% |
| Latte http    |      395,372 |      0 |              1.59 |              4.15 |        100.7% |
| Latte http    |      393,592 |      0 |              1.59 |              4.00 |        100.2% |
| Helidon       |      289,865 |      0 |              2.37 |             10.09 |         73.8% |
| Helidon       |      293,416 |      0 |              2.34 |             10.61 |         74.7% |
| Helidon       |      284,657 |      0 |              2.41 |             10.90 |         72.5% |
| Undertow      |      247,212 |      0 |              3.97 |              6.58 |         62.9% |
| Undertow      |      244,582 |      0 |              4.04 |              6.68 |         62.3% |
| Undertow      |      243,902 |      0 |              4.06 |              6.85 |         62.1% |

_TLS scenarios use a self-signed certificate at `benchmarks/certs/server.crt` (benchmark fixture only). All four servers terminate TLS and use ALPN to negotiate h2._

_JDK HttpServer does not support HTTP/2 and is excluded from h2 results._

_Benchmark performed 2026-05-21 on Darwin, arm64, 10 cores, Apple M4, 24GB RAM (MacBook Air)._
_OS: macOS 15.7.3._
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

See [`docs/specs/HTTP2.md`](specs/HTTP2.md) for the dated performance-findings sections, including JFR-profile-driven CPU hotspot analysis and the current Plan F item proposing writer-thread batching for h2 DATA emission.

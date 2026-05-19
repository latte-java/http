# Latte HTTP — Benchmarks

Auto-generated benchmark results. To regenerate, run `./benchmarks/run-benchmarks.sh` followed by `./benchmarks/update-readme.sh`. Methodology, scenario design, and per-vendor handler-implementation notes live in [`benchmarks/README.md`](../benchmarks/README.md).

## Methodology in one paragraph

Each scenario runs against four servers — Latte (`self`), Jetty, Tomcat, Netty — with identical wire-level load (same `wrk` or `h2load` invocation, same request shape). Numbers below are best-of-3 trials × 30 seconds. The 2026-05-19 results use a per-vendor fair-rerun protocol: each vendor runs in isolation with a 15-minute machine cool-down between vendors, to remove accumulated-thermal bias from sustained multi-vendor matrices. The `benchmarks/README.md` document describes what each scenario is designed to expose and which vendor handler-implementation asymmetries are deliberate.

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
| Latte http     |      108,797 |            0 |              2.09 |             45.00 |        100.0% |
| Latte http     |      110,284 |            0 |              1.94 |             36.37 |        101.3% |
| Latte http     |      110,257 |            0 |              1.69 |             29.88 |        101.3% |
| Jetty          |      109,498 |            0 |              2.24 |             46.98 |        100.6% |
| Jetty          |      109,948 |            0 |              2.20 |             48.53 |        101.0% |
| Jetty          |      109,688 |            0 |              1.89 |             36.89 |        100.8% |
| Netty          |      118,376 |            0 |              1.71 |             34.88 |        108.8% |
| Netty          |      118,583 |            0 |              1.57 |             28.59 |        108.9% |
| Netty          |      118,791 |            0 |              1.66 |             30.58 |        109.1% |
| Apache Tomcat  |      106,284 |            0 |              2.01 |             42.86 |         97.6% |
| Apache Tomcat  |      108,619 |            0 |              2.46 |             59.76 |         99.8% |
| Apache Tomcat  |      107,101 |            0 |              1.87 |             35.77 |         98.4% |

#### Under stress (1,000 concurrent connections)

| Server         | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs Latte http |
|----------------|-------------:|-------------:|-----------------:|-----------------:|--------------:|
| Latte http     |      109,981 |            0 |             12.18 |            122.79 |        100.0% |
| Latte http     |      108,223 |            0 |             11.62 |            107.71 |         98.4% |
| Latte http     |      108,003 |            0 |             11.65 |            105.30 |         98.2% |
| Jetty          |      107,708 |            0 |             10.80 |             88.47 |         97.9% |
| Jetty          |      107,401 |            0 |             11.06 |             95.17 |         97.6% |
| Jetty          |      104,184 |            0 |             11.21 |             91.36 |         94.7% |
| Netty          |      118,564 |            0 |              9.53 |             70.41 |        107.8% |
| Netty          |      116,723 |            0 |              9.29 |             55.93 |        106.1% |
| Netty          |      115,749 |            0 |              9.66 |             64.79 |        105.2% |
| Apache Tomcat  |      105,949 |            0 |             11.35 |             98.63 |         96.3% |
| Apache Tomcat  |      105,312 |            0 |             11.16 |             91.29 |         95.7% |
| Apache Tomcat  |      104,869 |            0 |             11.47 |            100.30 |         95.3% |

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

---

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

---

## Architectural context

See [`docs/specs/HTTP2.md`](specs/HTTP2.md) for the dated performance-findings sections, including JFR-profile-driven CPU hotspot analysis and the current Plan F item proposing writer-thread batching for h2 DATA emission.

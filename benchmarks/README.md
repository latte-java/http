# Benchmarks

Automated benchmarking framework for comparing java-http against other Java HTTP server implementations.

## Prerequisites

- **Java 25+** with `JAVA_HOME` set (e.g., `export JAVA_HOME=/opt/homebrew/opt/openjdk@25`). The `self` server depends on the root `org.lattejava:http` build, which targets Java 25 bytecode.
- **Latte** build tool (`latte` on PATH)
- **wrk** HTTP benchmark tool (`brew install wrk` on macOS)
- **jq** for JSON processing (`brew install jq` on macOS)
- **h2load** (optional) for HTTP/2 scenarios (`brew install nghttp2` on macOS). The runner skips `h2-*` scenarios gracefully when h2load is absent.

## Quick Start

```bash
# Run all servers, all scenarios, default 30s duration
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./run-benchmarks.sh

# Quick smoke test
JAVA_HOME=/opt/homebrew/opt/openjdk@25 ./run-benchmarks.sh --servers self --scenarios hello --duration 5s
```

## Servers Under Test

| Server | Directory | Description |
|--------|-----------|-------------|
| `self` | `self/` | java-http (the project being benchmarked) |
| `jdk-httpserver` | `jdk-httpserver/` | JDK built-in `com.sun.net.httpserver.HttpServer` |
| `jetty` | `jetty/` | Eclipse Jetty 12.0.x embedded server |
| `netty` | `netty/` | Netty 4.1.x with HTTP codec |
| `tomcat` | `tomcat/` | Apache Tomcat 11.0.x embedded |

All servers implement the same endpoints on port 8080:
- `GET /` - No-op (reads body, returns empty 200 response)
- `GET /no-read` - No-op (does not read body, returns empty 200 response)
- `GET /hello` - Returns "Hello world"
- `GET /file?size=N` - Returns N bytes of generated content (default 1MB)
- `POST /load` - Base64-encodes request body and returns it

## Usage

```
./run-benchmarks.sh [OPTIONS]

Options:
  --servers <list>     Comma-separated server list (default: all)
  --scenarios <list>   Comma-separated scenario list (default: all)
  --label <name>       Label for the results file
  --output <dir>       Output directory (default: benchmarks/results/)
  --duration <time>    Duration per scenario (default: 30s)
  -h, --help           Show this help
```

### Examples

```bash
# All servers, hello scenario only, 10s
./run-benchmarks.sh --scenarios hello --duration 10s

# Compare self vs netty
./run-benchmarks.sh --servers self,netty --scenarios hello,baseline --duration 10s

# Full suite with a label (results saved as results/2026-02-17-m4-full.json)
./run-benchmarks.sh --label m4-full --duration 30s

# Single server quick check
./run-benchmarks.sh --servers self --scenarios hello --duration 5s
```

## Scenarios

### HTTP/1.1 (wrk)

| Scenario | Endpoint | Threads | Connections | Purpose |
|----------|----------|---------|-------------|---------|
| `baseline` | `GET /` | 12 | 100 | No-op throughput ceiling |
| `hello` | `GET /hello` | 12 | 100 | Small response body |
| `post-load` | `POST /load` | 12 | 100 | POST with body, Base64 response |
| `large-file` | `GET /file?size=1048576` | 4 | 10 | 1MB response throughput |
| `high-concurrency` | `GET /` | 12 | 1000 | Connection pressure |
| `mixed` | Rotates endpoints | 12 | 100 | Real-world mix |
| `browser-headers` | `GET /` | 12 | 100 | Realistic browser header set |

### HTTP/2 (h2load — requires `brew install nghttp2`)

h2load uses h2c prior-knowledge (plaintext HTTP/2 without an upgrade handshake). The canonical h2 workload is *few TCP connections × many concurrent streams*, which has no direct h1.1 equivalent.

| Scenario | Endpoint | Threads | TCP Connections | Streams/conn | Total in-flight | Purpose |
|----------|----------|---------|-----------------|--------------|-----------------|---------|
| `h2-hello` | `GET /hello` | 1 | 1 | 100 | 100 | Baseline h2 throughput — single connection, many streams |
| `h2-high-stream-concurrency` | `GET /hello` | 4 | 10 | 100 | 1000 | Many-streams-per-conn (backend / proxy shape) — favors event-loop demux |
| `h2-high-connection-concurrency` | `GET /hello` | 4 | 500 | 2 | 1000 | Many-conns-few-streams (browser / CDN shape) — same in-flight, different topology |
| `h2-compute` | `GET /compute?rounds=5000` | 4 | 10 | 100 | 1000 | CPU-bound — chained SHA-256 ~500us–1ms/req; protocol becomes <20% of cost |
| `h2-io` | `GET /io?ms=10` | 4 | 10 | 100 | 1000 | Blocking-IO simulation — 10ms sleep per request; tests thread/IO model under wait |
| `h2-stream` | `GET /stream?size=131072` | 4 | 10 | 100 | 1000 | 128KB response, handler forces per-8KB flush — tests honor-flush wire path |
| `h2-large-response` | `GET /large-response?size=131072` | 4 | 10 | 100 | 1000 | 128KB response, handler writes once — server chooses framing |

#### Scenario design notes

Each h2 scenario was chosen to stress a specific axis of server design. The number that comes out is meaningful only in the context of the workload it was meant to expose — there is no single "h2 throughput" measurement.

- **`h2-hello`** — baseline. Tiny request, tiny response, one TCP connection, 100 multiplexed streams. Measures the per-stream + per-frame overhead with all batching effects intact and zero application work. Useful as a sanity / warmup number, not as a peer-comparison headline.

- **`h2-high-stream-concurrency`** (10 conn × 100 streams) — the canonical h2 multiplexing showcase. Maps to backend service-to-service traffic where a small pool of pinned HTTP/2 connections carries lots of concurrent requests, or to reverse-proxy / API-gateway shapes. This is **Netty's home field**: a single event-loop thread demuxes 100 streams per socket inline, which is the exact pattern its `Http2MultiplexHandler` was built for. Worker-pool servers (Tomcat, Jetty) pay per-stream dispatch cost; Latte pays per-stream virtual-thread mount cost.

- **`h2-high-connection-concurrency`** (500 conn × 2 streams) — inverse topology, same 1000 in-flight. Maps to browser-facing or CDN traffic, where each end-user maintains one or a few connections and the server sees lots of distinct sockets. Tests accept-loop throughput, connection-state bookkeeping, and the kernel's pending-SYN backlog. **Tomcat is structurally disadvantaged here** because its connection per-thread / per-worker model doesn't scale to many concurrent sockets the way a virtual-thread or event-loop model does. Latte's virtual-thread-per-connection design and Netty's event-loop both handle this shape cleanly; both also depend on `SO_BACKLOG` being above the connection count.

- **`h2-compute`** (chained SHA-256 × 5000 rounds, ~500µs–1ms CPU per request) — protocol-overhead-stress-test inverted. By making the handler genuinely CPU-bound, this scenario reduces the protocol stack to <20% of per-request cost, which means all servers should converge near the CPU-bound ceiling (~6–10k RPS for a single core × ms-scale work, scaled by core count). Differences here largely reflect **how much fixed overhead each server adds on top of the actual work** — Latte and Tomcat lose ground to Netty proportional to their per-request protocol cost. Useful as a "what does this server do in a real app" reading.

- **`h2-io`** (`Thread.sleep(10ms)`) — simulates a downstream call (DB query, cache lookup, microservice fetch). **Architectural model is everything here**. Latte's virtual threads park essentially for free; Netty's `ctx.executor().schedule()` schedules the response asynchronously without blocking the event loop; **Tomcat and Jetty pay their worker-pool size as a hard ceiling** — at default 200 worker threads and 10ms sleep, theoretical max throughput is 20k RPS regardless of CPU, network, or anything else. This scenario maps most directly to "what happens when an app handler waits for IO," which is what real apps do constantly. Tomcat / Jetty numbers here will jump if you increase their worker-pool sizes; the architectural ceiling does not.

- **`h2-stream`** (128KB body, handler forces per-8KB `flush()`) — tests the **honor-flush wire path**. The handler explicitly writes 16 × 8KB chunks with `OutputStream.flush()` between each. Latte and Jetty honor `flush()` literally — each call drains the buffer into a DATA frame and enqueues it for the wire. Tomcat treats servlet `flush()` as a hint and largely ignores it; Netty's bench handler sends a `FullHttpResponse` and lets the codec fragment. So **this scenario partially measures wire-level fidelity to handler intent**, not raw throughput. The right baseline for "honor-flush throughput" is Latte and Jetty; the Tomcat / Netty numbers here are a "what they do when asked to chunk" reading.

- **`h2-large-response`** (128KB body, single `write()`) — counterpart to `h2-stream`. Handler writes the entire body once and lets `close()` push it to the wire; the server chooses how to fragment into DATA frames (HTTP/2 `MAX_FRAME_SIZE` typically 16KB). This is what most "large response" endpoints actually look like in practice (file downloads, JSON dumps, response bodies built from a buffer). **Netty's `h2-large-response` should match its `h2-stream`** (it uses `FullHttpResponse` for both); the gap between `h2-stream` and `h2-large-response` for Latte / Jetty quantifies the cost of honoring per-chunk flush.

#### Handler-level asymmetries to be aware of when reading the data

The benchmark `LoadHandler` is implemented separately for each server (in `benchmarks/self/`, `benchmarks/netty/`, `benchmarks/jetty/`, `benchmarks/tomcat/`) using each server's idiomatic API. A few asymmetries are deliberate and worth knowing:

- **Latte / Tomcat / Jetty** call `Thread.sleep(ms)` directly for `/io`. Latte parks the virtual thread; Tomcat / Jetty park a worker-pool thread. **Netty cannot use `Thread.sleep`** — that would block the event loop and stall every stream on the channel. Netty uses `ctx.executor().schedule()` to dispatch the response asynchronously. This is the fair Netty-idiomatic equivalent; production Netty code would do the same.

- **`/stream` chunked write**: Latte and Jetty actually emit per-chunk DATA frames on the wire. Tomcat's servlet `flush()` is a hint the container can ignore; for the 8KB chunks in our `/stream` handler Tomcat likely coalesces. Netty's handler doesn't chunk at all — it sends `FullHttpResponse` and the HTTP/2 codec fragments into MAX_FRAME_SIZE-bounded DATA frames automatically.

- **`/compute` on Netty runs on the event loop thread**. CPU-heavy work in a Netty handler is normally offloaded to an executor in production, but for this benchmark we keep it on the event loop (simplest implementation; matches the rest of the handler). The 500µs–1ms of SHA work doesn't starve other streams long enough to matter for throughput, but production Netty deployments with longer handlers would offload. Latte / Tomcat / Jetty don't have this concern — their handlers are blocking-style by design.

- **`HttpObjectAggregator(10 MB)`** on Netty (h2c + TLS pipelines) means the request body is fully buffered before the handler runs. For the bodyless GETs in our scenarios this is free, but it does add a setup cost not present in Latte's handler.

- **JVM args** for each server come from each `benchmarks/<vendor>/build/dist/start.sh` (or `catalina.sh` for Tomcat). Heap / GC / virtual-thread settings are intentionally left at each server's default rather than uniformly tuned — the point of the comparison is "what does this server do out of the box," not "after careful tuning." Numbers may move 10–25% with vendor-specific tuning (Tomcat in particular benefits from a larger `maxThreads` for `h2-io`).

Per-vendor h2c support:

| Server | HTTP/2 support | Notes |
|--------|----------------|-------|
| `self` (Latte) | h2c prior-knowledge on port 8080 | Enabled in the HTTP/2 implementation branch |
| `jetty` | h2c prior-knowledge on port 8080 | `HTTP2CServerConnectionFactory` alongside `HttpConnectionFactory` on the same `ServerConnector` |
| `tomcat` | h2c prior-knowledge + Upgrade on port 8080 | `<UpgradeProtocol className="org.apache.coyote.http2.Http2Protocol"/>` in `server.xml`; Tomcat 11 handles both the `PRI *` preface and the `Upgrade: h2c` header |
| `netty` | h2c prior-knowledge + Upgrade on port 8080 | `CleartextHttp2ServerUpgradeHandler` detects h2c preface, h1.1 Upgrade, or plain h1.1 — all on one port |
| `jdk-httpserver` | HTTP/1.1 only — h2-* scenarios skipped | `com.sun.net.httpserver.HttpServer` has no HTTP/2 support; `run-benchmarks.sh` skips h2-* scenarios for this server |

## Results

Results are written as JSON to `benchmarks/results/` with an ISO timestamp format:
```
results/YYYY-MM-DDTHH-MM-SSZ.json
results/YYYY-MM-DDTHH-MM-SSZ-<label>.json
```

### Comparing Results

Use the compare script to compare two result files:
```bash
./compare-results.sh results/2026-02-17T20-30-00Z-baseline.json results/2026-02-17T21-00-00Z-after-change.json
```

This shows normalized ratios to identify performance regressions or improvements.

### Updating the README

To update the main project README with the latest benchmark results:
```bash
./update-readme.sh
```

This reads the most recent JSON from `results/` and replaces the `## Performance` section in the project root `README.md`.

## Performance testing & profiling (`self`)

`run-benchmarks.sh` answers "how do we compare to Jetty/Netty/Tomcat/Helidon/Undertow?". For
the different question — "did my change to `self` actually help?" — use
`perf-test.sh`. It runs wrk against `self` only, attaches a JFR recording to
the JVM, and emits a single JSON file with both wrk metrics (RPS, latency,
errors) and JFR metrics (allocation rate, GC pauses, peak heap).

```bash
./perf-test.sh                                    # 3 trials × 30s, browser-headers scenario
./perf-test.sh --scenario hello --duration 10s    # tighter loop
./perf-test.sh --baseline perf-results/<earlier>.json   # diff inline at end
./perf-test.sh --detailed                         # add hot methods + alloc sites
./compare-perf.sh perf-results/A.json perf-results/B.json   # diff two existing runs
```

### Agent / human workflow for performance changes

1. Check out `main` (or the parent of the change you're testing).
2. Run a baseline:
   ```bash
   ./perf-test.sh --label before
   ```
3. Apply the change.
4. Run again with the baseline pinned:
   ```bash
   ./perf-test.sh --label after --baseline perf-results/<timestamp>-before.json
   ```
5. Read the inline `=== perf-test delta ===` table. Improvements are green,
   regressions red (when stdout is a TTY).

### Output schema

Each run produces `perf-results/<timestamp>[-label].json` with this shape:

- `summary` — median/min/max for nine metrics across all trials.
- `trials_raw` — per-trial wrk + JFR records, plus the per-trial `.jfr` path.
- `system`, `git` — machine info and the commit being tested (with a `dirty`
  flag set when the working tree has uncommitted changes).
- `detailed` — `null` unless `--detailed` was passed; then an object with
  `hot_methods` (top 20 by sample count) and `alloc_sites` (top 20 by
  allocation event count) drawn from the median-RPS trial.

### What the metrics mean

| Metric                 | Direction       | What it tells you |
|------------------------|-----------------|-------------------|
| `rps`                  | higher = better | Observable throughput at the wrk client. The headline. |
| `avg_latency_us`       | lower = better  | Mean per-request latency at the wrk client. |
| `p99_us`               | lower = better  | Tail latency. More sensitive to GC pauses than `avg_latency_us`. |
| `errors`               | lower = better  | Sum of wrk's connect/read/write/timeout error buckets. Should be 0 on a healthy run. |
| `alloc_bytes_per_req`  | lower = better  | The closest proxy for "did this change reduce allocations." Normalises by load, so it's stable across runs at slightly different RPS. |
| `alloc_bytes_per_sec`  | lower = better  | Raw allocation rate. Useful sanity-check; biased by RPS. |
| `gc_pause_ms_total`    | lower = better  | Total time the JVM spent in GC during the recording. Drops when allocation pressure drops. |
| `gc_count`             | lower = better  | Number of collections. |
| `heap_peak_mb`         | lower = better  | Worst-case heap (max `heap_before_gc`). Catches regressions that grow working set even when alloc rate stays flat. |

Direction notes:
- A good change typically moves `alloc_bytes_per_req` and `gc_pause_ms_total`
  down *together*, and pulls `rps` up and `p99_us` down. If `rps` rises but
  allocations don't move, the JIT may have figured something out independently
  of the change — consider `--detailed` to confirm where time and allocations
  are now flowing.
- `errors` jumping above 0 invalidates the run; investigate the server log
  before trusting any of the other deltas.

### Going deeper: opening the JFR in JMC

Each trial's JFR file is kept under `perf-results/<timestamp>[-label]/trial-<n>.jfr`.
Open it in [JDK Mission Control](https://jdk.java.net/jmc/) for richer views
(thread states, lock contention, escape-analysis hints, hot paths). The numeric
summary is enough for go/no-go on a change; JMC is for "where do I attack next."

### Quick checks from the command line

```bash
jfr summary perf-results/<timestamp>/trial-1.jfr
jfr print --events jdk.ExecutionSample --stack-depth 5 \
    perf-results/<timestamp>/trial-1.jfr | head -40
jfr print --events jdk.ObjectAllocationSample --stack-depth 3 \
    perf-results/<timestamp>/trial-1.jfr | head -40
```

## Building Individual Servers

Each server can be built independently using Latte:

```bash
# Most servers
cd benchmarks/<server> && latte clean app

# Tomcat (different target)
cd benchmarks/tomcat && latte clean tomcat

# Start a server manually
cd benchmarks/<server>/build/dist && ./start.sh
```

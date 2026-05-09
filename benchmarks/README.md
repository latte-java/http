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
| `h2-hello` | `GET /hello` | 4 | 1 | 100 | 100 | Baseline h2 throughput — single connection, many streams |
| `h2-high-concurrency` | `GET /hello` | 4 | 10 | 100 | 1000 | Showcases h2 multiplexing (1000 in-flight over 10 TCP connections vs 1000 for h1.1) |

The Latte (`self`) benchmark server enables h2c prior-knowledge by default. Per-vendor h2 enablement (Jetty, Tomcat, Netty) is a separate follow-up task.

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

`run-benchmarks.sh` answers "how do we compare to Jetty/Netty/Tomcat?". For
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

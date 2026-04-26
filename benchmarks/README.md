# Benchmarks

Automated benchmarking framework for comparing java-http against other Java HTTP server implementations.

## Prerequisites

- **Java 21+** with `JAVA_HOME` set (e.g., `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`)
- **Latte** build tool (`latte` on PATH)
- **wrk** HTTP benchmark tool (`brew install wrk` on macOS)
- **jq** for JSON processing (`brew install jq` on macOS)

## Quick Start

```bash
# Run all servers, all scenarios, default 30s duration
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./run-benchmarks.sh

# Quick smoke test
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./run-benchmarks.sh --servers self --scenarios hello --duration 5s
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

| Scenario | Endpoint | wrk Threads | Connections | Purpose |
|----------|----------|-------------|-------------|---------|
| `baseline` | `GET /` | 12 | 100 | No-op throughput ceiling |
| `hello` | `GET /hello` | 12 | 100 | Small response body |
| `post-load` | `POST /load` | 12 | 100 | POST with body, Base64 response |
| `large-file` | `GET /file?size=1048576` | 4 | 10 | 1MB response throughput |
| `high-concurrency` | `GET /` | 12 | 1000 | Connection pressure |
| `mixed` | Rotates endpoints | 12 | 100 | Real-world mix |

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

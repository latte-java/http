# HTTP/2 Performance + Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish performance baselines for the HTTP/2 implementation against HTTP/1.1 (same library) and against peer Java servers (Jetty, Tomcat, Netty, Helidon Níma). Identify and fix any obvious hotspots. Final spec sweep so `HTTP2.md` accurately reflects the implementation.

**Architecture:** Extend the existing benchmark framework at `docs/plans/benchmark-spec.md` with HTTP/2 scenarios. Add `h2load` as a second benchmark tool (it's the standard h2 load generator; `wrk` doesn't speak h2). Run scenarios against latte-java and peer servers, publish numbers, profile hotspots with Java Flight Recorder, fix what's worth fixing.

**Tech Stack:** `h2load` (from nghttp2), `wrk` (already used), JFR for profiling, JMH if microbenchmarks become useful.

**Reference spec:** `docs/superpowers/specs/2026-05-05-http2-design.md` §"Test plan" layer 5; `docs/plans/benchmark-spec.md`.

**Depends on:** Plans D and E merged (h2 working and conformant).

---

## Important note on scope

Plans D and E left the HTTP/2 implementation correct but unoptimized. This plan finds the gap between "works" and "fast" — but the *specific* hotspots are unknown until profiled. Like Plan E, the iteration phase (Task 5) cannot be enumerated upfront.

Tasks 1–3 set up the harness; Task 4 is the discovery run; Task 5 is "iterate on hotspots." Tasks 6–7 close out the docs.

---

## File Structure

| File | Status | Responsibility |
|---|---|---|
| `tools/install-h2load.sh` | Create | Install `h2load` (from `nghttp2`) for the host platform |
| `benchmarks/scenarios/h2-*.lua` (or shell wrappers around h2load) | Create | h2-aware scenarios mirroring the existing five h1.1 scenarios |
| `benchmarks/run.sh` | Modify | Add `--protocol h2` switch; route to `h2load` when set |
| `benchmarks/results/<date>-h2.json` | Generated | Output of benchmark runs |
| `docs/plans/benchmark-spec.md` | Modify | Add h2 scenarios, peer-server h2 setup, results table |
| `docs/specs/HTTP2.md` | Modify | Final pass — flip remaining items, capture benchmark numbers, peer comparison |

---

## Phase 1 — Tooling

### Task 1: Add `h2load` installer script

**Files:**
- Create: `tools/install-h2load.sh`

- [ ] **Step 1: Write the script**

`h2load` is part of `nghttp2`. On macOS: `brew install nghttp2`. On Linux: usually `apt install nghttp2`. The script just verifies it's on PATH and otherwise prints install instructions:

```bash
#!/usr/bin/env bash
set -euo pipefail
if command -v h2load >/dev/null 2>&1; then
  echo "h2load found: $(h2load --version | head -1)"
  exit 0
fi
echo "h2load not installed. Install via:" >&2
case "$(uname -s)" in
  Darwin) echo "  brew install nghttp2" >&2 ;;
  Linux)  echo "  sudo apt-get install nghttp2 (Debian/Ubuntu) or sudo dnf install nghttp2 (Fedora)" >&2 ;;
  *) echo "  see https://nghttp2.org/" >&2 ;;
esac
exit 1
```

- [ ] **Step 2: Commit**

```bash
chmod +x tools/install-h2load.sh
git add tools/install-h2load.sh
git commit -m "Add h2load installer / verification script"
```

---

### Task 2: Add h2 scenarios to benchmark framework

**Files:**
- Modify: `benchmarks/run.sh`
- Create: `benchmarks/h2-scenarios/*.sh` (one wrapper per scenario)

- [ ] **Step 1: Read the existing benchmark layout**

Run: `ls benchmarks/`
Expected: existing wrk scenarios under `benchmarks/scenarios/`. Read `benchmarks/run.sh` to understand how a single run is parameterized.

- [ ] **Step 2: Add h2-aware scenarios**

Mirror the existing five endpoints (`/`, `/no-read`, `/hello`, `/file?size=N`, `POST /load`) but invoke `h2load` instead of `wrk`. Example for `/hello`:

```bash
#!/usr/bin/env bash
# benchmarks/h2-scenarios/hello.sh
HOST="${1:-http://127.0.0.1:8080}"
DURATION="${2:-30}"
CONNECTIONS="${3:-100}"
STREAMS_PER_CONN="${4:-100}"

h2load \
  --duration="${DURATION}" \
  --clients="${CONNECTIONS}" \
  --max-concurrent-streams="${STREAMS_PER_CONN}" \
  --threads=4 \
  "${HOST}/hello"
```

`h2load` outputs human-readable text by default. For machine consumption, parse out the relevant lines or use `--log-file` and post-process. (Do whatever the existing `wrk` scenarios do for JSON output and follow that pattern.)

- [ ] **Step 3: Add `--protocol h2` switch to `run.sh`**

When `--protocol h2`: route to `benchmarks/h2-scenarios/*` instead of `benchmarks/scenarios/*`.

- [ ] **Step 4: Run a smoke test**

```bash
./benchmarks/run.sh --server=self --protocol=h2 --scenario=hello --duration=5
```

Expected: prints `h2load` output with non-zero successful requests.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add h2 scenarios to benchmark framework using h2load"
```

---

### Task 3: Add h2 setup for peer servers

**Files:**
- Modify: `benchmarks/jetty/`, `benchmarks/tomcat/`, `benchmarks/netty/`, possibly add `benchmarks/helidon-nima/`

- [ ] **Step 1: For each peer, enable HTTP/2**

Read each peer's existing benchmark config (the `Server.java` or equivalent in `benchmarks/<peer>/`). Add the protocol-specific HTTP/2 enablement:
- **Jetty**: `HTTP2CServerConnectionFactory` (cleartext h2c) or `HTTP2ServerConnectionFactory` (TLS).
- **Tomcat**: `Http2Protocol` upgrade protocol on the connector.
- **Netty**: `Http2FrameCodecBuilder` on the channel pipeline.
- **Helidon Níma**: HTTP/2 is on by default (Níma is the Loom-native Helidon).

Reference each peer's documentation. For each, port 8080 should now serve h2c when probed with `h2load -h http://127.0.0.1:8080/`.

- [ ] **Step 2: Smoke-test each**

```bash
./benchmarks/run.sh --server=jetty --protocol=h2 --scenario=hello --duration=5
./benchmarks/run.sh --server=tomcat --protocol=h2 --scenario=hello --duration=5
./benchmarks/run.sh --server=netty --protocol=h2 --scenario=hello --duration=5
```

- [ ] **Step 3: Commit per peer**

```bash
git commit -m "Enable HTTP/2 on Jetty benchmark server"
# ... etc.
```

---

## Phase 2 — Baseline run + profiling

### Task 4: First full h2 benchmark run

- [ ] **Step 1: Run the full matrix**

```bash
./benchmarks/run.sh --all --protocol=h2 --duration=30 > benchmarks/results/$(date +%Y-%m-%d)-h2.txt
```

Run on dedicated hardware per the existing benchmark guidance. Capture: req/s, p50/p90/p99 latency, errors, for every (server × scenario) pair.

- [ ] **Step 2: Run h1.1 baseline on the same hardware for comparison**

```bash
./benchmarks/run.sh --all --protocol=h1 --duration=30 > benchmarks/results/$(date +%Y-%m-%d)-h1.txt
```

- [ ] **Step 3: Compile results into a table**

Add to `docs/plans/benchmark-spec.md` under a new "HTTP/2 Results" section. Table format:

```markdown
### HTTP/2 throughput — `/hello` endpoint, 100 connections × 100 streams, 30s

| Server | req/s | p50 | p90 | p99 |
|---|---|---|---|---|
| latte-java   | 250,000 | 1.2 ms | 2.4 ms |  8 ms |
| Jetty 12     | 320,000 | 0.9 ms | 1.8 ms |  5 ms |
| Tomcat 11    | 280,000 | 1.1 ms | 2.0 ms |  6 ms |
| Netty 4      | 410,000 | 0.7 ms | 1.4 ms |  4 ms |
| Helidon Níma | 240,000 | 1.3 ms | 2.6 ms |  9 ms |

(numbers illustrative — fill in from actual run)
```

- [ ] **Step 4: Commit raw results**

```bash
git add benchmarks/results/ docs/plans/benchmark-spec.md
git commit -m "Capture initial HTTP/2 benchmark results"
```

---

### Task 5: Profile and fix hotspots — iterative

**For each scenario where latte-java is meaningfully behind a peer (>20%):**

- [ ] **Step 1: Run with JFR enabled**

```bash
JAVA_OPTS="-XX:+FlightRecorder -XX:StartFlightRecording=filename=h2-hello.jfr,duration=30s" ./benchmarks/run.sh --server=self --protocol=h2 --scenario=hello --duration=35
```

- [ ] **Step 2: Open the JFR file in JDK Mission Control or `jfr` CLI**

```bash
jfr summary h2-hello.jfr
jfr print --events JavaMonitorWait,JavaMonitorEnter h2-hello.jfr | head -30
jfr print --events ObjectAllocationInNewTLAB h2-hello.jfr | head -30
```

- [ ] **Step 3: Identify the dominant cost**

Common HTTP/2 hotspots — which ones are likely depends on the implementation choices that landed:
- **HPACK encoding** — string allocation per header. Fix: introduce Huffman-encoded write path (Plan C deferred this — Encoder writes literal-only); switch to a small encode buffer reused per HEADERS frame.
- **DATA frame allocation** — `byte[]` per frame in the writer queue. Fix: pool DATA frame payloads, or write directly to socket bypassing the queue for the hot path.
- **`ArrayBlockingQueue<byte[]>` contention** on per-stream pipes — virtual-thread-on-monitor fits poorly under heavy contention. Fix: replace with a lock-free MPSC queue, or simpler — pre-bound the producer side via flow control such that the queue never blocks.
- **HPACK dynamic-table linear scan** — `O(n)` lookup per header on encode. Fix: parallel hash map keyed by (name, value) and (name).
- **Thread-creation cost** — virtual threads are cheap but not free. If the hotspot is `Thread.start`, consider a per-stream context object reused across short-lived handlers (probably not the issue but worth checking).

- [ ] **Step 4: For each identified hotspot, apply the fix on a topic branch, re-benchmark, and commit if it helps**

Per-fix commit message format:
```
Optimize HPACK encoder: skip dynamic-table lookup for known sensitive names

Before: 280K req/s on /hello h2; after: 320K req/s.
JFR: HPACKEncoder.encode time -38%.
```

- [ ] **Step 5: Re-run the full matrix after each round of fixes**

```bash
./benchmarks/run.sh --all --protocol=h2 --duration=30 > benchmarks/results/$(date +%Y-%m-%d)-h2-r2.txt
```

- [ ] **Step 6: Stop iterating when**

Either: (a) latte-java is within 20% of the median peer on every scenario, or (b) you've spent the time budget for this plan and the remaining gaps are tracked as named follow-ups in `HTTP2.md` "Performance follow-ups" section.

---

## Phase 3 — Documentation polish

### Task 6: Update `HTTP2.md` with final status

**Files:**
- Modify: `docs/specs/HTTP2.md`

- [ ] **Step 1: Walk every row**

Every row in `HTTP2.md` should now be ✅ or 🚫 (out of scope) — no remaining ⚠️ or ❌ except where deliberately deferred. For deferred items, change them to ⚠️ with a citation to a follow-up issue or a note in the "Performance follow-ups" section.

- [ ] **Step 2: Update peer comparison**

Replace each "❌ planned" / "✅" with the actual current status. Add a "perf parity" row pointing to the latest benchmark-spec results.

- [ ] **Step 3: Add a benchmark summary**

Right above "Bug ledger", add:

```markdown
## Performance summary

Most recent benchmark run: 2026-MM-DD on [hardware description]. See `docs/plans/benchmark-spec.md` for the full table and methodology. latte-java is currently within X% of the median peer on `/hello` and within Y% on `/file?size=1MB`. Known performance follow-ups:

- [ ] HPACK Huffman encoding (currently disabled; planned)
- [ ] DATA frame payload pooling
- [ ] (other items surfaced during Task 5)
```

- [ ] **Step 4: Commit**

```bash
git add docs/specs/HTTP2.md
git commit -m "Final HTTP2.md sweep: all items resolved or tracked as follow-ups"
```

---

### Task 7: Update top-level README

**Files:**
- Modify: `README.md` (and the auto-generated performance table if applicable per `benchmark-spec.md`)

- [ ] **Step 1: Mention HTTP/2 support**

Add a one-line note under the project description:
```markdown
Supports HTTP/1.1 and HTTP/2 (h2 over TLS via ALPN, h2c via Upgrade and prior-knowledge). Zero-dependency. Java 21 virtual threads, blocking I/O.
```

- [ ] **Step 2: Re-run the README perf table generator (if `benchmark-spec.md` defines one)**

Check `benchmark-spec.md` for the auto-gen instruction; run it.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "README: announce HTTP/2 support"
```

---

### Task 8: Final tag

- [ ] **Step 1: Tag the release-candidate commit**

```bash
git tag -a http2-shipped -m "Plans A–F complete: HTTP/2 conformant, interop-tested, benchmarked"
```

- [ ] **Step 2: Run the entire test suite one more time**

```bash
latte clean int --excludePerformance --excludeTimeouts
latte int-h2spec
```

Expected: ALL PASS.

---

## Self-review checklist

- ✅ `h2load` is the right tool for HTTP/2 benchmarks; `wrk` doesn't speak h2
- ✅ Peer-server h2 enablement covered
- ✅ Profiling step explicit (JFR commands given)
- ✅ Hotspot fixes are iterative, not enumerated upfront — matches reality
- ✅ Final doc pass closes out `HTTP2.md`
- ⚠️ Task 5's specific fixes cannot be enumerated until profiling has been done — the plan acknowledges this
- ⚠️ Acceptable-perf threshold ("within 20% of median peer") is a starting bar; the user may want tighter — adjust during execution
- ⚠️ HPACK Huffman encoding was deferred in Plan C and may surface here as a hotspot — anticipated, not guaranteed

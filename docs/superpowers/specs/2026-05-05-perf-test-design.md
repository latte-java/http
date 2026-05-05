# perf-test.sh — design

Date: 2026-05-05
Status: approved

## Goal

Give an agent (or human) a single command that runs a load test against `self`,
records a JFR profile alongside it, and emits a numeric summary that can be
diffed across runs. The point is to move from "I think this change reduces
allocations" to "this change reduced allocations from X to Y bytes/request and
moved RPS from A to B." Without that, JIT/GC behaviour at runtime makes
performance work guesswork.

The `jwt` benchmark suite has the equivalent for JMH microbenchmarks
(`--profile gc`, `--profile stack`). HTTP servers don't fit JMH; the moral
equivalent for a long-lived server is JFR run alongside `wrk`.

## Scope

- **In scope:** `self` (this repo's HTTP server) only. Profiling third-party
  servers (Jetty, Netty, Tomcat) is out of scope — they aren't ours to optimize.
- **In scope:** before/after diffing, automated metric extraction, agent
  workflow documentation.
- **Out of scope:** continuous-integration regression detection, automatic
  failure thresholds. The output is read by humans/agents who decide whether a
  delta is meaningful.

## Tooling layout

### New files

- `benchmarks/perf-test.sh` — runs wrk + JFR against `self` for N trials,
  aggregates results into a single JSON file, optionally diffs against a
  baseline file.
- `benchmarks/compare-perf.sh` — standalone diff between two `perf-test` JSON
  files. Used when both runs already exist on disk.
- `benchmarks/perf-results/` — output directory (gitignored except for any
  intentionally committed reference baselines).

### Removed

- `benchmarks/profile.sh` — `perf-test.sh` records JFR per trial and keeps the
  files on disk, so `profile.sh` is redundant. Removing avoids two scripts that
  do almost the same JFR-attach dance with different output paths.

### Why a separate output directory

`benchmarks/results/` holds wrk-only multi-vendor JSON (current
`run-benchmarks.sh` schema). `perf-test.sh` writes a different schema (wrk +
JFR + trials aggregation). Keeping them in separate directories means
`compare-results.sh` and `compare-perf.sh` never have to inspect a file to
guess which schema it follows.

## JFR extraction

The script uses the JDK's `jfr` CLI — no extra Java helper, no extra build
step. Three sources of metrics:

1. `jfr summary <file>` — GC count and pause times (parsed from stable
   labelled text output).
2. `jfr print --json --events jdk.GCHeapSummary <file>` — heap-before/after
   for each GC. Allocation rate is computed as the sum of (heap-before(GC[n]) −
   heap-after(GC[n−1])) divided by recording duration.
3. `jfr print --json --events jdk.GCHeapSummary <file>` (same query) — peak
   heap is `max(heap-before)` across the recording.

`alloc_bytes_per_req = total_alloc_bytes / wrk_total_requests` — derived in the
script from the wrk request count and the JFR-derived total.

JFR is started with `-XX:StartFlightRecording=delay=5s,duration=<run>,settings=profile,dumponexit=true,filename=<path>`,
matching what `profile.sh` does today. The 5s delay skips wrk warmup so the
recording reflects steady state.

## Output schema

One JSON file per `perf-test.sh` invocation, written to `perf-results/`:

```json
{
  "version": 1,
  "timestamp": "2026-05-05T12:00:00Z",
  "scenario": "browser-headers",
  "duration": "30s",
  "trials": 3,
  "system":  { "os": "...", "arch": "...", "cpuModel": "...",
               "cpuCores": 12, "ramGB": 32, "javaVersion": "..." },
  "git":     { "sha": "abc123", "dirty": false },
  "summary": {
    "rps":                 { "median": 124000, "min": 121000, "max": 126500 },
    "avg_latency_us":      { "median": 800,    "min": 780,    "max": 820   },
    "p99_us":              { "median": 4200,   "min": 4100,   "max": 4400  },
    "errors":              { "median": 0,      "min": 0,      "max": 2     },
    "alloc_bytes_per_sec": { "median": 1.2e8,  "min": 1.1e8,  "max": 1.3e8 },
    "alloc_bytes_per_req": { "median": 970,    "min": 940,    "max": 1010  },
    "gc_pause_ms_total":   { "median": 18,     "min": 16,     "max": 22    },
    "gc_count":            { "median": 12,     "min": 11,     "max": 13    },
    "heap_peak_mb":        { "median": 280,    "min": 270,    "max": 290   }
  },
  "trials_raw": [
    { "trial": 1,
      "wrk": { "rps": ..., "avg_latency_us": ..., "p99_us": ..., "errors": ..., "total_requests": ... },
      "jfr": { "alloc_bytes_per_sec": ..., "gc_pause_ms_total": ..., ... },
      "jfr_file": "perf-results/2026-05-05T12-00-00Z/trial-1.jfr"
    }
  ],
  "detailed": null
}
```

Aggregation rules:
- Median is the per-trial value at index `floor(N/2)` of the sorted array
  (matches what `run-benchmarks.sh --trials` already does, if anything; if not,
  we define this here and reuse).
- Min/max are the per-trial extremes.
- With `--trials 1`, median == min == max. The schema is the same so consumers
  don't have to special-case.

`detailed` is `null` for the default mode. With `--detailed`, it becomes:

```json
"detailed": {
  "hot_methods": [ { "method": "...", "samples": 1234, "pct": 12.4 }, ... ],
  "alloc_sites": [ { "site": "...",   "events":  890, "pct":  8.1 }, ... ]
}
```

`hot_methods` from `jfr print --events jdk.ExecutionSample`, top 20 by stack
frame count. `alloc_sites` from `jfr print --events jdk.ObjectAllocationInNewTLAB`,
top 20 by event count. Both extracted from one trial (the median-RPS trial) to
avoid noise from cross-trial sample-count drift.

## CLI

```
perf-test.sh [OPTIONS]

Options:
  --scenario   <name>    Scenario to run (default: browser-headers)
  --duration   <time>    Wrk run duration (default: 30s)
  --trials     <n>       Number of trials (default: 3)
  --detailed             Include hot-methods + alloc-sites in report
  --baseline   <file>    Compare against an existing perf-results JSON; print delta
  --label      <name>    Appended to results filename
  --output     <dir>     Output directory (default: benchmarks/perf-results/)
  -h, --help             This message
```

Scenario names are the same as `run-benchmarks.sh` (`baseline`, `hello`,
`post-load`, `large-file`, `high-concurrency`, `mixed`, `browser-headers`).
Reuses the same `scenarios/*.lua` files for wrk.

`--baseline <file>` makes the script print a delta after the run completes:

```
=== Delta vs benchmarks/perf-results/2026-05-04T...json ===

Metric                     Baseline      Current     Δ
rps                         118,200      124,000   +4.9%
avg_latency_us                  840          800   -4.8%
p99_us                        4,500        4,200   -6.7%
alloc_bytes_per_req           1,180          970  -17.8%
gc_pause_ms_total                24           18  -25.0%
gc_count                         15           12  -20.0%
heap_peak_mb                    295          280   -5.1%
errors                            0            0      0
```

Direction interpretation: for `rps` higher is better; for everything else lower
is better. The script colours improvements green and regressions red when
stdout is a TTY.

## compare-perf.sh

Standalone diff for when both inputs already exist:

```
compare-perf.sh <baseline.json> <comparison.json>
```

Same delta table as the inline `--baseline` mode of `perf-test.sh`. If both
inputs have a `detailed` section, also prints a side-by-side hot-methods and
alloc-sites table. Mixed mode (one detailed, one not) prints the core table
and notes the detailed view was skipped.

## Documentation

A new "Performance testing & profiling" section in `benchmarks/README.md`
covers:

1. **When to use which tool** — `run-benchmarks.sh` for "how do we compare to
   Jetty/Netty/Tomcat" (cross-vendor RPS); `perf-test.sh` for "did my last
   commit help."
2. **Agent workflow** — checkout main, run `perf-test.sh --label baseline`,
   apply change, run `perf-test.sh --baseline perf-results/<baseline>.json`,
   read inline delta.
3. **Metric meanings** — what each summary number tells you, and what
   directional move counts as "good." The agent will use this to reason about
   *why* RPS moved, not just that it did. Specifically:
   - `rps` and `p99_us` — observable end-user-facing throughput/latency.
   - `alloc_bytes_per_req` — closest proxy for "did this change reduce
     allocations." Less noisy than `alloc_bytes_per_sec` because it normalizes
     by load.
   - `gc_pause_ms_total` — total time the JVM spent in GC across the
     recording. Drops when allocation pressure drops.
   - `heap_peak_mb` — worst-case heap; useful for catching a regression that
     enlarges working set even if alloc rate stays flat.
4. **JMC analysis** — point to the kept `.jfr` files for cases where the
   numeric summary isn't enough.

## Implementation notes

- The script is shell + jq, matching the rest of `benchmarks/`.
- Trial loop: per trial, start server with JFR args, wait for ready, run wrk,
  stop server, read JFR, append per-trial record to a tmp array, sleep 2s.
  At the end, compute `summary` aggregations from the trial array.
- JFR file naming: `perf-results/<timestamp>[-label]/trial-<n>.jfr`. The
  per-run JSON sits at `perf-results/<timestamp>[-label].json`.
- Server JVM args: pass JFR via `JAVA_OPTS` (existing `start.sh` already
  honours this — confirmed at `benchmarks/self/src/main/script/start.sh:45`).
- Exit codes: `perf-test.sh` exits 0 on a successful run regardless of delta
  direction. `--baseline` deltas are informational; the script does not turn
  regressions into non-zero exits. Future work could add a
  `--fail-on-regression` flag if CI integration becomes a goal.

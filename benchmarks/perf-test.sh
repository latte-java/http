#!/usr/bin/env bash

#
# Copyright (c) 2026, The Latte Project
#
# Licensed under the MIT License. See LICENSE in the project root for full license text.
#
# Self-only load + JFR harness. Runs wrk against the self benchmark server
# while a JFR recording captures GC and allocation events, then aggregates
# the numeric summary across N trials into a single JSON file. Optionally
# diffs against a baseline.
#

set -euo pipefail

ulimit -S -n 32768

# --- Defaults ---
SCENARIO="browser-headers"
DURATION="30s"
TRIALS=3
DETAILED=0
BASELINE=""
LABEL=""
OUTPUT_DIR=""

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
SELF_DIR="${SCRIPT_DIR}/self"
SCENARIO_DIR="${SCRIPT_DIR}/scenarios"
DEFAULT_OUTPUT_DIR="${SCRIPT_DIR}/perf-results"

usage() {
  cat <<EOF
Usage: $0 [OPTIONS]

Run wrk against the self benchmark server with JFR profiling, aggregate
metrics across N trials, and optionally compare against a baseline.

Options:
  --scenario   <name>    Scenario to run (default: browser-headers)
  --duration   <time>    Wrk run duration (default: 30s)
  --trials     <n>       Number of trials (default: 3)
  --detailed             Include hot-methods + alloc-sites in report
  --baseline   <file>    Compare against an existing perf-results JSON; print delta
  --label      <name>    Appended to results filename
  --output     <dir>     Output directory (default: benchmarks/perf-results/)
  -h, --help             This message
EOF
  exit 0
}

# --- Parse args ---
while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) SCENARIO="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --trials)   TRIALS="$2"; shift 2 ;;
    --detailed) DETAILED=1; shift ;;
    --baseline) BASELINE="$2"; shift 2 ;;
    --label)    LABEL="$2"; shift 2 ;;
    --output)   OUTPUT_DIR="$2"; shift 2 ;;
    -h|--help)  usage ;;
    *)          echo "ERROR: Unknown option: $1" >&2; usage ;;
  esac
done

OUTPUT_DIR="${OUTPUT_DIR:-${DEFAULT_OUTPUT_DIR}}"

# --- Prerequisites ---
for cmd in wrk latte java jq curl jfr; do
  command -v "${cmd}" >/dev/null || {
    echo "ERROR: '${cmd}' is not installed or not in PATH." >&2
    exit 1
  }
done

# --- Scenario validation (mirrors run-benchmarks.sh) ---
case "${SCENARIO}" in
  baseline)         THREADS=12; CONNS=100;  ENDPOINT="/" ;;
  hello)            THREADS=12; CONNS=100;  ENDPOINT="/hello" ;;
  post-load)        THREADS=12; CONNS=100;  ENDPOINT="/load" ;;
  large-file)       THREADS=4;  CONNS=10;   ENDPOINT="/file?size=1048576" ;;
  high-concurrency) THREADS=12; CONNS=1000; ENDPOINT="/" ;;
  mixed)            THREADS=12; CONNS=100;  ENDPOINT="/" ;;
  browser-headers)  THREADS=12; CONNS=100;  ENDPOINT="/" ;;
  *) echo "ERROR: Unknown scenario [${SCENARIO}]" >&2; exit 1 ;;
esac

SCENARIO_FILE="${SCENARIO_DIR}/${SCENARIO}.lua"
[[ -f "${SCENARIO_FILE}" ]] || { echo "ERROR: scenario file missing: ${SCENARIO_FILE}" >&2; exit 1; }

if [[ -n "${BASELINE}" && ! -f "${BASELINE}" ]]; then
  echo "ERROR: baseline file not found: ${BASELINE}" >&2
  exit 1
fi

if ! [[ "${TRIALS}" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: --trials must be a positive integer (got: ${TRIALS})" >&2
  exit 1
fi

# --- Compute output paths ---
TIMESTAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
SUFFIX=""
[[ -n "${LABEL}" ]] && SUFFIX="-${LABEL}"
RESULT_FILE="${OUTPUT_DIR}/${TIMESTAMP}${SUFFIX}.json"
TRIAL_DIR="${OUTPUT_DIR}/${TIMESTAMP}${SUFFIX}"

mkdir -p "${OUTPUT_DIR}" "${TRIAL_DIR}"

# --- Server lifecycle helpers ---

SERVER_PID=""
SERVER_LOG=""

start_server() {
  local extra_java_opts="${1:-}"

  EXISTING_PID="$(lsof -ti :8080 2>/dev/null || true)"
  if [[ -n "${EXISTING_PID}" ]]; then
    echo "ERROR: Port 8080 is in use by PID ${EXISTING_PID}." >&2
    exit 1
  fi

  SERVER_LOG="${TRIAL_DIR}/server.log"
  JAVA_OPTS="${extra_java_opts}" \
    bash -c "cd '${SELF_DIR}/build/dist' && exec ./start.sh" >"${SERVER_LOG}" 2>&1 &
  SERVER_PID=$!

  for _ in $(seq 1 30); do
    if ! kill -0 "${SERVER_PID}" 2>/dev/null; then
      echo "ERROR: Server process died during startup. See ${SERVER_LOG}" >&2
      return 1
    fi
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ 2>/dev/null | grep -q 200; then
      return 0
    fi
    sleep 1
  done
  echo "ERROR: Server did not start within 30s. See ${SERVER_LOG}" >&2
  return 1
}

stop_server() {
  if [[ -n "${SERVER_PID}" ]] && kill -0 "${SERVER_PID}" 2>/dev/null; then
    kill "${SERVER_PID}" 2>/dev/null || true
    sleep 1
    kill -9 "${SERVER_PID}" 2>/dev/null || true
  fi
  SERVER_PID=""
  # Also catch anything still on 8080 (catalina, child procs, etc.)
  local pids
  pids="$(lsof -ti :8080 2>/dev/null || true)"
  [[ -n "${pids}" ]] && echo "${pids}" | xargs kill 2>/dev/null || true
}

trap stop_server EXIT INT TERM

# --- Build self once up front ---

echo "--- Building self ---"
(cd "${SELF_DIR}" && latte clean app) >"${TRIAL_DIR}/build.log" 2>&1 || {
  echo "ERROR: build failed. See ${TRIAL_DIR}/build.log" >&2
  exit 1
}

# --- Duration helpers ---
# wrk accepts "30s", "5m", "1h"; we need the value in seconds for JFR.

duration_to_seconds() {
  local dur="$1"
  local num="${dur%[smhSMH]}"
  local unit="${dur#"${num}"}"
  case "${unit}" in
    s|S|"") echo "${num}" ;;
    m|M)    echo $(( num * 60 )) ;;
    h|H)    echo $(( num * 3600 )) ;;
    *)      echo "${num}" ;;
  esac
}

WARMUP_SECS=5
DURATION_SECS="$(duration_to_seconds "${DURATION}")"
JFR_DURATION_SECS="${DURATION_SECS}"  # JFR records during the steady-state portion

# Pad the wrk run so it covers the warmup + the full JFR window plus 5s of slack.
WRK_DURATION_SECS=$(( WARMUP_SECS + JFR_DURATION_SECS + 5 ))
WRK_DURATION="${WRK_DURATION_SECS}s"

# --- JFR metric extraction ---
#
# extract_jfr_metrics <jfr_file> <duration_secs>
#
# Emits a single-line JSON object with:
#   alloc_bytes_per_sec — sum of (heap_before(GC[n]) - heap_after(GC[n-1])) / duration
#   gc_count            — number of garbage collections
#   gc_pause_ms_total   — sum of GC pause times
#   gc_pause_ms_max     — longest individual GC pause
#   heap_peak_mb        — max(heap_before) across the recording, in MB
#
# Note on alloc rate: jdk.GCHeapSummary events have a "when" field of
# "Before GC" or "After GC". Pairing consecutive (After GC[n-1], Before GC[n])
# events gives the bytes allocated between collections. Sum / duration = rate.
# This is more accurate than sampled allocation events because it counts the
# delta the JVM actually had to GC.

extract_jfr_metrics() {
  local jfr_file="$1"
  local duration_secs="$2"

  # Pull GCHeapSummary events as JSON; sort by start time; project to a flat array.
  # try/catch is required: when .recording is absent, iterating .recording.events[]
  # raises a runtime error — the // operator only catches false/null, not errors.
  local heap_events
  heap_events="$(jfr print --json --events jdk.GCHeapSummary "${jfr_file}" \
    | jq -c '
        [ ((try .recording.events[] catch null) // .events[])
          | { ts: .startTime, when: .values.when, used: (.values.heapUsed | tonumber) } ]
        | sort_by(.ts)
      ')"

  # Compute alloc bytes (sum of before[n] - after[n-1]).
  local alloc_bytes
  alloc_bytes="$(echo "${heap_events}" | jq '
    reduce .[] as $e (
      { total: 0, last_after: null };
      if $e.when == "Before GC" and .last_after != null
      then .total += ($e.used - .last_after) | (if .total < 0 then .total = 0 else . end)
      elif $e.when == "After GC"
      then .last_after = $e.used
      else .
      end
    ) | .total
  ')"

  local heap_peak_bytes
  heap_peak_bytes="$(echo "${heap_events}" | jq '[.[] | select(.when == "Before GC") | .used] | (max // 0)')"

  # GC count comes from `jfr summary` — its labelled output is stable across JDKs.
  local summary
  summary="$(jfr summary "${jfr_file}")"

  local gc_count
  gc_count="$(echo "${summary}" | awk '/jdk\.GarbageCollection/ {print $2; exit}')"
  gc_count="${gc_count:-0}"

  # Sum + max GCPhasePause durations directly from the events for precision.
  # `jfr print --events jdk.GCPhasePause` prints lines like:
  #   jdk.GCPhasePause { startTime = ..., duration = 1.234 ms, ... }
  local pause_lines
  pause_lines="$(jfr print --events jdk.GCPhasePause "${jfr_file}" 2>/dev/null \
    | awk '/duration = / {
        for (i=1; i<=NF; i++) if ($i == "duration") { print $(i+2), $(i+3); break }
      }')"

  # pause_lines is one "<value> <unit>" per line; convert all to milliseconds.
  local gc_pause_ms_total gc_pause_ms_max
  read -r gc_pause_ms_total gc_pause_ms_max <<< "$(echo "${pause_lines}" | awk '
    {
      val = $1; unit = $2
      if      (unit == "ns")  ms = val / 1e6
      else if (unit == "us")  ms = val / 1e3
      else if (unit == "ms")  ms = val
      else if (unit == "s")   ms = val * 1e3
      else                    ms = val
      total += ms
      if (ms > max) max = ms
    }
    END { printf "%.3f %.3f", (total ? total : 0), (max ? max : 0) }
  ')"

  jq -n \
    --argjson alloc_bytes "${alloc_bytes:-0}" \
    --argjson duration "${duration_secs}" \
    --argjson heap_peak_bytes "${heap_peak_bytes:-0}" \
    --argjson gc_count "${gc_count}" \
    --arg     gc_pause_total "${gc_pause_ms_total}" \
    --arg     gc_pause_max "${gc_pause_ms_max}" '
    {
      alloc_bytes_per_sec: ( ($alloc_bytes / $duration) | floor ),
      heap_peak_mb:        ( ($heap_peak_bytes / 1048576) | floor ),
      gc_count:            $gc_count,
      gc_pause_ms_total:   ($gc_pause_total | tonumber),
      gc_pause_ms_max:     ($gc_pause_max | tonumber)
    }'
}

# --- Run a single trial with JFR ---
# Returns "wrk-json|jfr-file" on stdout for the caller to split.

run_wrk_trial() {
  local trial="$1"
  local jfr_file="${TRIAL_DIR}/trial-${trial}.jfr"

  local jfr_opts="-XX:StartFlightRecording=delay=${WARMUP_SECS}s,duration=${JFR_DURATION_SECS}s,filename=${jfr_file},settings=profile,dumponexit=true"

  start_server "${jfr_opts}" || return 1

  echo "  trial ${trial}/${TRIALS}: wrk ${THREADS}t/${CONNS}c for ${WRK_DURATION} (JFR ${WARMUP_SECS}s..+${JFR_DURATION_SECS}s)..." >&2
  local wrk_output
  wrk_output="$(SCENARIO_DIR="${SCENARIO_DIR}" \
    wrk -t"${THREADS}" -c"${CONNS}" -d"${WRK_DURATION}" \
        -s "${SCENARIO_FILE}" \
        "http://localhost:8080${ENDPOINT}" 2>&1)"

  # Give JFR a moment to flush before we kill the JVM.
  sleep 2
  stop_server

  if [[ ! -f "${jfr_file}" ]]; then
    echo "ERROR: JFR file not produced at ${jfr_file}. Server log:" >&2
    tail -30 "${SERVER_LOG}" >&2
    return 1
  fi

  local json_line
  json_line="$(echo "${wrk_output}" | grep '^{' | tail -1)"
  if [[ -z "${json_line}" ]]; then
    echo "ERROR: wrk produced no JSON line. Output:" >&2
    echo "${wrk_output}" >&2
    return 1
  fi

  # Print "wrk-json|jfr-file" so the caller can tee both.
  echo "${json_line}|${jfr_file}"
}

# --- Trial loop (single trial in this task; full loop in Task 7) ---

echo "=== perf-test (${TIMESTAMP}) ==="
echo "  Scenario: ${SCENARIO} | Duration: ${DURATION} | Trials: ${TRIALS}"
echo ""

TRIAL_RESULT="$(run_wrk_trial 1)"
WRK_JSON="${TRIAL_RESULT%|*}"
JFR_FILE="${TRIAL_RESULT##*|}"

echo ""
JFR_METRICS="$(extract_jfr_metrics "${JFR_FILE}" "${JFR_DURATION_SECS}")"

ALLOC_PER_SEC="$(echo "${JFR_METRICS}" | jq -r '.alloc_bytes_per_sec')"
# Use rps * JFR_DURATION_SECS rather than wrk's total request count: wrk runs
# for warmup + JFR + slack seconds, so its request count spans a longer window
# than the JFR recording. Dividing JFR-window allocations by the full-window
# request count understates alloc_bytes_per_req by ~25% at default settings.
RPS_FLOAT="$(echo "${WRK_JSON}" | jq -r '.rps')"
JFR_REQUESTS="$(awk -v rps="${RPS_FLOAT}" -v dur="${JFR_DURATION_SECS}" \
  'BEGIN { printf "%d", rps * dur }')"
ALLOC_PER_REQ=0
if [[ "${JFR_REQUESTS}" -gt 0 ]]; then
  ALLOC_PER_REQ=$(( (ALLOC_PER_SEC * JFR_DURATION_SECS) / JFR_REQUESTS ))
fi

TRIAL_JSON="$(jq -n \
  --argjson trial 1 \
  --argjson wrk "${WRK_JSON}" \
  --argjson jfr "${JFR_METRICS}" \
  --argjson alloc_per_req "${ALLOC_PER_REQ}" \
  --arg     jfr_file "${JFR_FILE#${SCRIPT_DIR}/}" \
  '{
    trial: $trial,
    wrk: $wrk,
    jfr: ($jfr + { alloc_bytes_per_req: $alloc_per_req }),
    jfr_file: $jfr_file
  }')"

echo "Trial 1 record:"
echo "${TRIAL_JSON}" | jq .

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

# --- Run a single trial (wrk only for now; JFR added in Task 4) ---
# Returns the wrk JSON line on stdout.

run_wrk_trial() {
  local trial="$1"
  local jfr_file="$2"  # currently unused; wired in Task 4

  start_server "" || return 1

  echo "  trial ${trial}/${TRIALS}: wrk ${THREADS}t/${CONNS}c for ${DURATION}..." >&2
  local wrk_output
  wrk_output="$(SCENARIO_DIR="${SCENARIO_DIR}" \
    wrk -t"${THREADS}" -c"${CONNS}" -d"${DURATION}" \
        -s "${SCENARIO_FILE}" \
        "http://localhost:8080${ENDPOINT}" 2>&1)"

  stop_server

  local json_line
  json_line="$(echo "${wrk_output}" | grep '^{' | tail -1)"
  if [[ -z "${json_line}" ]]; then
    echo "ERROR: wrk produced no JSON line. Output:" >&2
    echo "${wrk_output}" >&2
    return 1
  fi
  echo "${json_line}"
}

# --- Trial loop (single trial in this task; full loop in Task 7) ---

echo "=== perf-test (${TIMESTAMP}) ==="
echo "  Scenario: ${SCENARIO} | Duration: ${DURATION} | Trials: ${TRIALS}"
echo ""

WRK_JSON="$(run_wrk_trial 1 "")"
echo ""
echo "wrk trial 1 JSON:"
echo "${WRK_JSON}" | jq .

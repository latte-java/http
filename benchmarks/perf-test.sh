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

# --- Plan dry-run output ---
echo "=== perf-test (${TIMESTAMP}) ==="
echo "  Scenario:  ${SCENARIO} (${THREADS}t / ${CONNS}c / endpoint=${ENDPOINT})"
echo "  Duration:  ${DURATION} per trial"
echo "  Trials:    ${TRIALS}"
echo "  Detailed:  $((DETAILED == 1 ? 1 : 0))"
echo "  Baseline:  ${BASELINE:-(none)}"
echo "  Output:    ${RESULT_FILE}"
echo "  JFR dir:   ${TRIAL_DIR}"
echo ""
echo "(skeleton — execution logic added in subsequent tasks)"

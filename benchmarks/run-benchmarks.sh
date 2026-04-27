#!/usr/bin/env bash

#
# Copyright (c) 2025, FusionAuth, All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
# either express or implied. See the License for the specific
# language governing permissions and limitations under the License.
#

set -euo pipefail

# Raise the soft file descriptor limit for this process and its children (wrk, server start.sh, etc.).
# macOS defaults can be as low as 256, which is insufficient for high-concurrency benchmarks where
# wrk opens 1000+ simultaneous connections. This only affects the current process tree — it does not
# change system defaults or persist after the script exits. Linux and macOS only.
ulimit -S -n 32768

# Resolve script directory
SOURCE="${BASH_SOURCE[0]}"
while [[ -h ${SOURCE} ]]; do
  SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" >/dev/null && pwd)"
  SOURCE="$(readlink "${SOURCE}")"
  [[ ${SOURCE} != /* ]] && SOURCE="${SCRIPT_DIR}/${SOURCE}"
done
SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" >/dev/null && pwd)"

# Defaults
ALL_SERVERS="self jdk-httpserver jetty netty tomcat"
ALL_SCENARIOS="baseline hello post-load large-file high-concurrency mixed browser-headers"
SERVERS="${ALL_SERVERS}"
SCENARIOS="${ALL_SCENARIOS}"
LABEL=""
OUTPUT_DIR="${SCRIPT_DIR}/results"
DURATION="30s"
TRIALS=1

usage() {
  echo "Usage: $0 [OPTIONS]"
  echo ""
  echo "Options:"
  echo "  --servers <list>     Comma-separated server list (default: all)"
  echo "                       Available: ${ALL_SERVERS// /, }"
  echo "  --scenarios <list>   Comma-separated scenario list (default: all)"
  echo "                       Available: ${ALL_SCENARIOS// /, }"
  echo "  --label <name>       Label for the results file"
  echo "  --output <dir>       Output directory (default: benchmarks/results/)"
  echo "  --duration <time>    Duration per scenario (default: 30s)"
  echo "  --trials <n>         Number of trials per scenario (default: 1)"
  echo "  -h, --help           Show this help"
  exit 0
}

# Parse arguments
while [[ $# -gt 0 ]]; do
  case "$1" in
    --servers)   SERVERS="${2//,/ }"; shift 2 ;;
    --scenarios) SCENARIOS="${2//,/ }"; shift 2 ;;
    --label)     LABEL="$2"; shift 2 ;;
    --output)    OUTPUT_DIR="$2"; shift 2 ;;
    --duration)  DURATION="$2"; shift 2 ;;
    --trials)    TRIALS="$2"; shift 2 ;;
    -h|--help)   usage ;;
    *)           echo "Unknown option: $1"; usage ;;
  esac
done

# --- Prerequisites ---

check_command() {
  if ! command -v "$1" &>/dev/null; then
    echo "ERROR: '$1' is not installed or not in PATH."
    exit 1
  fi
}

check_command wrk
check_command latte
check_command java
check_command curl
check_command jq

# Tomcat's catalina.sh falls back to `/usr/libexec/java_home` when JAVA_HOME is unset. On macOS that returns whichever JDK Apple's
# system-wide registry chooses (often the oldest one — on this dev machine, JDK 8), which does not recognize --add-opens and refuses
# to start. Resolve JAVA_HOME from whatever `java` is on PATH so every server runs on the same JDK we're benchmarking with.
if [[ -z "${JAVA_HOME:-}" ]]; then
  RESOLVED_JAVA_HOME="$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.home/ {print $2; exit}')"
  if [[ -n "${RESOLVED_JAVA_HOME}" && -d "${RESOLVED_JAVA_HOME}" ]]; then
    export JAVA_HOME="${RESOLVED_JAVA_HOME}"
  fi
fi

# --- Parse duration to seconds ---

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

DURATION_SECS="$(duration_to_seconds "${DURATION}")"

# --- Elapsed timer helpers ---
# Starts a background process that prints elapsed time on the current line.
# Usage: start_timer "prefix message"
#        ... long-running command ...
#        stop_timer
TIMER_PID=""
TIMER_START=0
start_timer() {
  local msg="$1"
  TIMER_START="${SECONDS}"
  (
    while true; do
      local elapsed=$(( SECONDS - TIMER_START ))
      printf "\r    %s ... %ds" "${msg}" "${elapsed}"
      sleep 1
    done
  ) &
  TIMER_PID=$!
}

stop_timer() {
  if [[ -n "${TIMER_PID}" ]]; then
    kill "${TIMER_PID}" 2>/dev/null || true
    wait "${TIMER_PID}" 2>/dev/null || true
    TIMER_PID=""
    printf "\r\033[K"  # Clear the timer line
  fi
  TIMER_ELAPSED=$(( SECONDS - TIMER_START ))
}
trap stop_timer EXIT

# --- Banner ---

SUITE_START="${SECONDS}"
SUITE_START_TIME="$(date '+%H:%M:%S')"
echo "=== latte-java http Benchmark Suite (started ${SUITE_START_TIME}) ==="
echo ""

# --- System metadata ---

OS="$(uname -s)"
ARCH="$(uname -m)"
CPU_CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo unknown)"
if [[ "${OS}" == "Darwin" ]]; then
  CPU_MODEL="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
  RAM_GB="$(( $(sysctl -n hw.memsize 2>/dev/null || echo 0) / 1073741824 ))"
  MACHINE_MODEL="$(system_profiler SPHardwareDataType 2>/dev/null | grep 'Model Name' | sed 's/.*: *//' || echo unknown)"
  OS_VERSION="$(sw_vers -productName 2>/dev/null || echo macOS) $(sw_vers -productVersion 2>/dev/null || echo unknown)"
else
  CPU_MODEL="$(lscpu 2>/dev/null | grep 'Model name' | sed 's/.*: *//' || echo unknown)"
  RAM_GB="$(( $(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}' || echo 0) / 1048576 ))"
  MACHINE_MODEL="$(cat /sys/devices/virtual/dmi/id/product_name 2>/dev/null || echo unknown)"
  if [[ -f /etc/os-release ]]; then
    OS_VERSION="$(. /etc/os-release && echo "${PRETTY_NAME}")"
  else
    OS_VERSION="Linux $(uname -r)"
  fi
fi
JAVA_VERSION="$(java -version 2>&1 | head -1 || echo unknown)"
WRK_VERSION="$(set +o pipefail; wrk -v 2>&1 | head -1)"

echo "Machine:  ${MACHINE_MODEL}"
echo "OS:       ${OS_VERSION}"
echo "System:   ${OS} ${ARCH}, ${CPU_CORES} cores, ${RAM_GB}GB RAM"
echo "CPU:      ${CPU_MODEL}"
echo "Java:     ${JAVA_VERSION}"
echo "wrk:      ${WRK_VERSION}"
echo "Duration: ${DURATION} (${DURATION_SECS}s)"
echo ""

# --- Scenario configuration ---
# Maps scenario name -> "threads connections endpoint"

scenario_config() {
  case "$1" in
    baseline)         echo "12 100 /" ;;
    hello)            echo "12 100 /hello" ;;
    post-load)        echo "12 100 /load" ;;
    large-file)       echo "4 10 /file?size=1048576" ;;
    high-concurrency) echo "12 1000 /" ;;
    mixed)            echo "12 100 /" ;;
    browser-headers)  echo "12 100 /" ;;
    *)                echo ""; return 1 ;;
  esac
}

# --- Server build and start configuration ---

server_build_target() {
  case "$1" in
    tomcat) echo "clean tomcat" ;;
    *)      echo "clean app" ;;
  esac
}

start_server() {
  local server="$1"
  local server_dir="${SCRIPT_DIR}/${server}"
  local log_file="${server_dir}/build/server.log"

  case "${server}" in
    tomcat)
      (cd "${server_dir}/build/dist/tomcat/apache-tomcat/bin" && ./catalina.sh run) >"${log_file}" 2>&1 &
      ;;
    *)
      (cd "${server_dir}/build/dist" && ./start.sh) >"${log_file}" 2>&1 &
      ;;
  esac

  SERVER_PID=$!
}

wait_for_server() {
  local timeout=30
  local elapsed=0
  while [[ ${elapsed} -lt ${timeout} ]]; do
    if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ 2>/dev/null | grep -q "200"; then
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  echo "ERROR: Server did not start within ${timeout}s"
  return 1
}

stop_server() {
  if [[ -n "${SERVER_PID:-}" ]]; then
    # Kill the process group to ensure child processes are also terminated
    kill -- -"${SERVER_PID}" 2>/dev/null || kill "${SERVER_PID}" 2>/dev/null || true
    wait "${SERVER_PID}" 2>/dev/null || true
    SERVER_PID=""
  fi

  # Also check for anything still on port 8080
  local pids
  pids="$(lsof -ti :8080 2>/dev/null || true)"
  if [[ -n "${pids}" ]]; then
    echo "${pids}" | xargs kill 2>/dev/null || true
    sleep 1
  fi
}

# --- Run a single wrk benchmark ---
# Args: $1=server, $2=scenario, $3=threads, $4=connections, $5=endpoint, $6=trial
# Appends result to RESULTS_JSON
run_wrk_benchmark() {
  local server="$1" scenario="$2" threads="$3" connections="$4" endpoint="$5" trial="$6"

  local trial_label=""
  if [[ "${TRIALS}" -gt 1 ]]; then
    trial_label=" [trial ${trial}/${TRIALS}]"
  fi
  echo "  [wrk] Running: ${scenario} (${threads}t, ${connections}c, ${DURATION}) -> ${endpoint}${trial_label}"

  # Run wrk and capture JSON output from the Lua done() callback
  start_timer "[wrk] ${server}/${scenario}${trial_label}"
  local wrk_output
  wrk_output="$(wrk -t"${threads}" -c"${connections}" -d"${DURATION}" \
    -s "${SCENARIO_DIR}/${scenario}.lua" \
    "http://localhost:8080${endpoint}" 2>&1)"
  stop_timer

  # The JSON line is the last line of output (from the done() callback)
  local json_line
  json_line="$(echo "${wrk_output}" | grep '^{' | tail -1)"

  if [[ -z "${json_line}" ]]; then
    echo "    WARNING: No JSON output from wrk for ${server}/${scenario}"
    echo "    wrk output: ${wrk_output}"
    return
  fi

  # Build the result entry
  local result_entry
  result_entry="$(jq -n \
    --arg server "${server}" \
    --arg tool "wrk" \
    --arg protocol "http/1.1" \
    --arg scenario "${scenario}" \
    --argjson threads "${threads}" \
    --argjson connections "${connections}" \
    --arg duration "${DURATION}" \
    --arg endpoint "${endpoint}" \
    --argjson trial "${trial}" \
    --argjson metrics "${json_line}" \
    '{
      server: $server,
      tool: $tool,
      protocol: $protocol,
      scenario: $scenario,
      config: { threads: $threads, connections: $connections, duration: $duration, endpoint: $endpoint, trial: $trial },
      metrics: $metrics
    }'
  )"

  RESULTS_JSON="$(echo "${RESULTS_JSON}" | jq --argjson entry "${result_entry}" '. + [$entry]')"

  # Print summary
  local rps avg_lat p99_lat errors
  rps="$(echo "${json_line}" | jq -r '.rps')"
  avg_lat="$(echo "${json_line}" | jq -r '.avg_latency_us')"
  p99_lat="$(echo "${json_line}" | jq -r '.p99_us')"
  errors="$(echo "${json_line}" | jq -r '.errors_connect + .errors_read + .errors_write + .errors_timeout')"
  printf "    RPS: %'.0f | Avg Latency: %'.0f us | P99: %'.0f us | Errors: %d | Duration: %ds\n" \
    "${rps}" "${avg_lat}" "${p99_lat}" "${errors}" "${TIMER_ELAPSED}"
}

# --- Run benchmarks ---

SCENARIO_DIR="${SCRIPT_DIR}/scenarios"
export SCENARIO_DIR

mkdir -p "${OUTPUT_DIR}"

TIMESTAMP="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# Use ISO timestamp for filename (colons replaced with dashes for filesystem safety)
DATE_LABEL="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
if [[ -n "${LABEL}" ]]; then
  RESULT_FILE="${OUTPUT_DIR}/${DATE_LABEL}-${LABEL}.json"
else
  RESULT_FILE="${OUTPUT_DIR}/${DATE_LABEL}.json"
fi

# Collect all results as JSON array elements
RESULTS_JSON="[]"

for server in ${SERVERS}; do
  server_dir="${SCRIPT_DIR}/${server}"

  if [[ ! -d "${server_dir}" ]]; then
    echo "WARNING: Server directory not found: ${server_dir}, skipping."
    continue
  fi

  echo "--- Building ${server} ---"
  build_target="$(server_build_target "${server}")"
  (cd "${server_dir}" && latte ${build_target}) || {
    echo "ERROR: Failed to build ${server}, skipping."
    continue
  }

  echo "--- Starting ${server} ---"
  stop_server
  start_server "${server}"

  if ! wait_for_server; then
    echo "ERROR: ${server} failed to start, skipping."
    stop_server
    continue
  fi

  echo "--- ${server} is ready on port 8080 ---"
  echo ""

  for scenario in ${SCENARIOS}; do
    config="$(scenario_config "${scenario}")" || {
      echo "WARNING: Unknown scenario: ${scenario}, skipping."
      continue
    }

    read -r threads connections endpoint <<< "${config}"
    for trial in $(seq 1 "${TRIALS}"); do
      run_wrk_benchmark "${server}" "${scenario}" "${threads}" "${connections}" "${endpoint}" "${trial}"
    done
  done

  echo ""
  echo "--- Stopping ${server} ---"
  stop_server
  echo ""
done

# --- Write results file ---

FULL_RESULT="$(jq -n \
  --argjson version 1 \
  --arg timestamp "${TIMESTAMP}" \
  --arg os "${OS}" \
  --arg arch "${ARCH}" \
  --arg osVersion "${OS_VERSION}" \
  --arg machineModel "${MACHINE_MODEL}" \
  --arg cpuModel "${CPU_MODEL}" \
  --argjson cpuCores "${CPU_CORES}" \
  --argjson ramGB "${RAM_GB}" \
  --arg javaVersion "${JAVA_VERSION}" \
  --arg description "Local benchmark" \
  --arg wrkVersion "${WRK_VERSION}" \
  --argjson results "${RESULTS_JSON}" \
  '{
    version: $version,
    timestamp: $timestamp,
    system: {
      os: $os,
      arch: $arch,
      osVersion: $osVersion,
      machineModel: $machineModel,
      cpuModel: $cpuModel,
      cpuCores: $cpuCores,
      ramGB: $ramGB,
      javaVersion: $javaVersion,
      description: $description
    },
    tools: {
      wrkVersion: $wrkVersion
    },
    results: $results
  }'
)"

echo "${FULL_RESULT}" > "${RESULT_FILE}"
echo "=== Results written to ${RESULT_FILE} ==="
echo ""

# --- Print summary table ---

echo "=== Summary ==="
echo ""
printf "%-15s %-18s %12s %12s %12s %8s\n" "Server" "Scenario" "RPS" "Avg Lat(us)" "P99(us)" "Errors"
printf "%-15s %-18s %12s %12s %12s %8s\n" "---------------" "------------------" "------------" "------------" "------------" "--------"

echo "${RESULTS_JSON}" | jq -r '.[] | [.server, .scenario, (.metrics.rps | tostring), (.metrics.avg_latency_us | tostring), (.metrics.p99_us | tostring), ((.metrics.errors_connect + .metrics.errors_read + .metrics.errors_write + .metrics.errors_timeout) | tostring)] | @tsv' | \
  while IFS=$'\t' read -r srv scn rps avg p99 errs; do
    printf "%-15s %-18s %12.0f %12.0f %12d %8d\n" "${srv}" "${scn}" "${rps}" "${avg}" "${p99}" "${errs}"
  done

SUITE_ELAPSED=$(( SECONDS - SUITE_START ))
SUITE_MINS=$(( SUITE_ELAPSED / 60 ))
SUITE_SECS=$(( SUITE_ELAPSED % 60 ))
echo ""
echo "=== Done (${SUITE_MINS}m ${SUITE_SECS}s) ==="

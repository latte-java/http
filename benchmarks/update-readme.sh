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

# Resolve script directory
SOURCE="${BASH_SOURCE[0]}"
while [[ -h ${SOURCE} ]]; do
  SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" >/dev/null && pwd)"
  SOURCE="$(readlink "${SOURCE}")"
  [[ ${SOURCE} != /* ]] && SOURCE="${SCRIPT_DIR}/${SOURCE}"
done
SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" >/dev/null && pwd)"

RESULTS_DIR="${SCRIPT_DIR}/results"
README="${SCRIPT_DIR}/../README.md"

# Find the latest results file
LATEST="$(ls -t "${RESULTS_DIR}"/*.json 2>/dev/null | head -1)"

if [[ -z "${LATEST}" ]]; then
  echo "ERROR: No results files found in ${RESULTS_DIR}/"
  exit 1
fi

echo "Using results from: ${LATEST}"

# Extract system info and timestamp
TIMESTAMP="$(jq -r '.timestamp' "${LATEST}")"
SYSTEM_DESC="$(jq -r '[.system.os, .system.arch, (.system.cpuCores | tostring) + " cores", .system.cpuModel] | join(", ")' "${LATEST}")"
RAM_GB="$(jq -r '.system.ramGB' "${LATEST}")"
JAVA_VERSION="$(jq -r '.system.javaVersion' "${LATEST}")"
MACHINE_MODEL="$(jq -r '.system.machineModel // "unknown"' "${LATEST}")"
OS_VERSION="$(jq -r '.system.osVersion // ""' "${LATEST}")"
DATE_FORMATTED="$(echo "${TIMESTAMP}" | cut -d'T' -f1)"

MACHINE_LINE=""
if [[ "${MACHINE_MODEL}" != "unknown" && -n "${MACHINE_MODEL}" ]]; then
  MACHINE_LINE=" (${MACHINE_MODEL})"
fi
OS_LINE=""
if [[ -n "${OS_VERSION}" && "${OS_VERSION}" != "null" ]]; then
  OS_LINE=$'\n'"_OS: ${OS_VERSION}._"
fi

# Server display name mapping
server_display_name() {
  case "$1" in
    self)            echo "Latte http" ;;
    jdk-httpserver)  echo "JDK HttpServer" ;;
    jetty)           echo "Jetty" ;;
    netty)           echo "Netty" ;;
    tomcat)          echo "Apache Tomcat" ;;
    *)               echo "$1" ;;
  esac
}

# ---------------------------------------------------------------------------
# HTTP/1.1 helpers
# ---------------------------------------------------------------------------

# Generate an HTTP/1.1 wrk results table for a given scenario.
# Args: $1=scenario, $2=tool_filter, $3=self_rps
generate_h1_table() {
  local scenario="$1"
  local tool="$2"
  local self_rps="$3"

  echo "| Server         | Requests/sec | Failures/sec | Avg latency (ms) | P99 latency (ms) | vs Latte http |"
  echo "|----------------|-------------:|-------------:|-----------------:|-----------------:|--------------:|"

  jq -r --arg scenario "${scenario}" --arg tool "${tool}" \
    '[.results[] | select(.scenario == $scenario and .tool == $tool)] | sort_by(if .server == "self" then "" else .server end) | .[] | [.server, (.metrics.rps | tostring), ((.metrics.errors_connect + .metrics.errors_read + .metrics.errors_write + .metrics.errors_timeout) | tostring), (.metrics.avg_latency_us | tostring), (.metrics.p99_us | tostring)] | @tsv' \
    "${LATEST}" | while IFS=$'\t' read -r server rps errors avg_lat p99_lat; do

    display_name="$(server_display_name "${server}")"

    # Convert microseconds to milliseconds
    avg_lat_ms="$(printf "%.2f" "$(echo "scale=4; ${avg_lat} / 1000" | bc)")"
    p99_lat_ms="$(printf "%.2f" "$(echo "scale=4; ${p99_lat} / 1000" | bc)")"

    # Calculate failures per second from total errors / duration
    duration_s="$(jq -r --arg server "${server}" --arg scenario "${scenario}" --arg tool "${tool}" \
      '.results[] | select(.server == $server and .scenario == $scenario and .tool == $tool) | .metrics.duration_us / 1e6' "${LATEST}")"
    if [[ -n "${duration_s}" && "${duration_s}" != "0" && "${duration_s}" != "null" ]]; then
      fps="$(echo "scale=1; ${errors} / ${duration_s}" | bc)"
    else
      fps="0"
    fi

    # Normalized performance vs Latte http
    # Use bc for numeric zero-check since jq may output "0.00" rather than "0".
    if [[ -n "${self_rps}" && "${self_rps}" != "null" ]] && \
       [[ "$(echo "${self_rps} > 0" | bc -l 2>/dev/null)" == "1" ]]; then
      normalized="$(echo "scale=1; ${rps} * 100 / ${self_rps}" | bc)"
    else
      normalized="?"
    fi

    rps_formatted="$(printf "%'.0f" "${rps}")"
    printf "| %-14s | %12s | %12s | %17s | %17s | %12s%% |\n" \
      "${display_name}" "${rps_formatted}" "${fps}" "${avg_lat_ms}" "${p99_lat_ms}" "${normalized}"
  done
}

# ---------------------------------------------------------------------------
# HTTP/2 helpers
# ---------------------------------------------------------------------------

# Generate an HTTP/2 h2load results table for a given scenario.
# Args: $1=scenario, $2=self_rps
generate_h2_table() {
  local scenario="$1"
  local self_rps="$2"

  echo "| Server        | Requests/sec | Errors | Avg latency (ms) | P99 latency (ms) | vs Latte http |"
  echo "|---------------|-------------:|-------:|-----------------:|-----------------:|--------------:|"

  jq -r --arg scenario "${scenario}" \
    '[.results[] | select(.scenario == $scenario and .tool == "h2load")] | sort_by(if .server == "self" then "" else .server end) | .[] | [.server, (.metrics.rps | tostring), (.metrics.errors_other | tostring), (.metrics.avg_latency_us | tostring), (.metrics.p99_us | tostring)] | @tsv' \
    "${LATEST}" | while IFS=$'\t' read -r server rps errors avg_lat p99_lat; do

    display_name="$(server_display_name "${server}")"

    # Convert microseconds to milliseconds
    avg_lat_ms="$(printf "%.2f" "$(echo "scale=4; ${avg_lat} / 1000" | bc)")"
    p99_lat_ms="$(printf "%.2f" "$(echo "scale=4; ${p99_lat} / 1000" | bc)")"

    # Normalized performance vs Latte http
    # Use bc for numeric zero-check since jq may output "0.00" rather than "0".
    if [[ -n "${self_rps}" && "${self_rps}" != "null" ]] && \
       [[ "$(echo "${self_rps} > 0" | bc -l 2>/dev/null)" == "1" ]]; then
      normalized="$(echo "scale=1; ${rps} * 100 / ${self_rps}" | bc)"
    else
      normalized="?"
    fi

    rps_formatted="$(printf "%'.0f" "${rps}")"
    printf "| %-13s | %12s | %6s | %17s | %17s | %12s%% |\n" \
      "${display_name}" "${rps_formatted}" "${errors}" "${avg_lat_ms}" "${p99_lat_ms}" "${normalized}"
  done
}

# ---------------------------------------------------------------------------
# Inject a block of content between two HTML comment markers in README.md.
# The markers themselves are preserved; only the content between them is replaced.
# Args: $1=start_marker, $2=end_marker, $3=content_file
# ---------------------------------------------------------------------------
inject_section() {
  local start_marker="$1"
  local end_marker="$2"
  local content_file="$3"

  python3 - "${README}" "${start_marker}" "${end_marker}" "${content_file}" << 'PYEOF'
import sys, re

readme_path, start_marker, end_marker, content_path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

with open(readme_path, 'r') as f:
    text = f.read()

with open(content_path, 'r') as f:
    new_content = f.read().rstrip('\n')

# Escape markers for use as literal strings in regex
sm = re.escape(start_marker)
em = re.escape(end_marker)

pattern = rf'({sm})\n.*?({em})'
replacement = rf'\1\n{new_content}\n\2'

new_text, n = re.subn(pattern, replacement, text, count=1, flags=re.DOTALL)
if n == 0:
    print(f"ERROR: Could not find markers '{start_marker}' ... '{end_marker}' in README.md", file=sys.stderr)
    sys.exit(1)

with open(readme_path, 'w') as f:
    f.write(new_text)
PYEOF
}

# ---------------------------------------------------------------------------
# Build HTTP/1.1 section content
# ---------------------------------------------------------------------------

# Pick primary h1.1 scenario — prefer hello, fall back to baseline
H1_SCENARIO="hello"
if ! jq -e '.results[] | select(.scenario == "hello" and .tool == "wrk")' "${LATEST}" &>/dev/null; then
  H1_SCENARIO="baseline"
fi

HAS_H1_DATA=false
if jq -e --arg s "${H1_SCENARIO}" '.results[] | select(.scenario == $s and .tool == "wrk")' "${LATEST}" &>/dev/null; then
  HAS_H1_DATA=true
fi

HAS_HIGH_CONCURRENCY=false
if jq -e '.results[] | select(.scenario == "high-concurrency" and .tool == "wrk")' "${LATEST}" &>/dev/null; then
  HAS_HIGH_CONCURRENCY=true
fi

H1_FILE="$(mktemp)"
trap 'rm -f "${H1_FILE}"' EXIT

if [[ "${HAS_H1_DATA}" == "true" ]]; then
  SELF_RPS="$(jq -r --arg s "${H1_SCENARIO}" '.results[] | select(.server == "self" and .scenario == $s and .tool == "wrk") | .metrics.rps' "${LATEST}" 2>/dev/null | head -1 || echo "0")"
  [[ -z "${SELF_RPS}" || "${SELF_RPS}" == "null" ]] && SELF_RPS="0"

  {
    echo "### HTTP/1.1 (wrk)"
    echo ""
    echo "#### Hello scenario (low concurrency, baseline)"
    echo ""
    generate_h1_table "${H1_SCENARIO}" "wrk" "${SELF_RPS}"

    if [[ "${HAS_HIGH_CONCURRENCY}" == "true" ]]; then
      HC_SELF_RPS="$(jq -r '.results[] | select(.server == "self" and .scenario == "high-concurrency" and .tool == "wrk") | .metrics.rps' "${LATEST}" 2>/dev/null | head -1 || echo "0")"
      [[ -z "${HC_SELF_RPS}" || "${HC_SELF_RPS}" == "null" ]] && HC_SELF_RPS="0"

      echo ""
      echo "#### Under stress (1,000 concurrent connections)"
      echo ""
      generate_h1_table "high-concurrency" "wrk" "${HC_SELF_RPS}"
      echo ""
      echo "_JDK HttpServer (\`com.sun.net.httpserver\`) is included as a baseline since it ships with the JDK and requires no dependencies. However, as the stress test shows, it is not suitable for production workloads — it suffers significant failures under high concurrency._"
    fi

    echo ""
    printf "_Benchmark performed %s on %s, %sGB RAM%s._%s\n" \
      "${DATE_FORMATTED}" "${SYSTEM_DESC}" "${RAM_GB}" "${MACHINE_LINE}" "${OS_LINE}"
    echo "_Java: ${JAVA_VERSION}._"
    echo ""
    echo "To reproduce:"
    echo '```bash'
    echo "cd benchmarks"
    if [[ "${HAS_HIGH_CONCURRENCY}" == "true" ]]; then
      echo "./run-benchmarks.sh --scenarios ${H1_SCENARIO},high-concurrency"
    else
      echo "./run-benchmarks.sh --scenarios ${H1_SCENARIO}"
    fi
    echo "./update-readme.sh"
    echo '```'
  } > "${H1_FILE}"

  inject_section "<!-- H1-BENCHMARK-START -->" "<!-- H1-BENCHMARK-END -->" "${H1_FILE}"
  echo "README.md HTTP/1.1 section updated."
else
  echo "No HTTP/1.1 wrk results found — skipping H1 section update."
fi

# ---------------------------------------------------------------------------
# Build HTTP/2 section content
# ---------------------------------------------------------------------------

HAS_H2_HELLO=false
if jq -e '.results[] | select(.scenario == "h2-hello" and .tool == "h2load")' "${LATEST}" &>/dev/null; then
  HAS_H2_HELLO=true
fi

HAS_H2_HC=false
if jq -e '.results[] | select(.scenario == "h2-high-concurrency" and .tool == "h2load")' "${LATEST}" &>/dev/null; then
  HAS_H2_HC=true
fi

H2_FILE="$(mktemp)"
trap 'rm -f "${H1_FILE}" "${H2_FILE}"' EXIT

if [[ "${HAS_H2_HELLO}" == "true" || "${HAS_H2_HC}" == "true" ]]; then
  H2_SELF_RPS="$(jq -r '.results[] | select(.server == "self" and .scenario == "h2-hello" and .tool == "h2load") | .metrics.rps' "${LATEST}" 2>/dev/null | head -1 || echo "0")"
  [[ -z "${H2_SELF_RPS}" || "${H2_SELF_RPS}" == "null" ]] && H2_SELF_RPS="0"

  H2_HC_SELF_RPS="$(jq -r '.results[] | select(.server == "self" and .scenario == "h2-high-concurrency" and .tool == "h2load") | .metrics.rps' "${LATEST}" 2>/dev/null | head -1 || echo "0")"
  [[ -z "${H2_HC_SELF_RPS}" || "${H2_HC_SELF_RPS}" == "null" ]] && H2_HC_SELF_RPS="0"

  {
    echo "### HTTP/2 (h2load)"
    echo ""

    if [[ "${HAS_H2_HELLO}" == "true" ]]; then
      echo "#### h2-hello (1 connection × 100 streams)"
      echo ""
      generate_h2_table "h2-hello" "${H2_SELF_RPS}"
    fi

    if [[ "${HAS_H2_HC}" == "true" ]]; then
      echo ""
      echo "#### h2-high-concurrency (10 connections × 100 streams each)"
      echo ""
      generate_h2_table "h2-high-concurrency" "${H2_HC_SELF_RPS}"
    fi

    echo ""
    echo "_JDK HttpServer does not support HTTP/2 and is excluded from h2 results._"
    echo ""
    printf "_Benchmark performed %s on %s, %sGB RAM%s._%s\n" \
      "${DATE_FORMATTED}" "${SYSTEM_DESC}" "${RAM_GB}" "${MACHINE_LINE}" "${OS_LINE}"
    echo "_Java: ${JAVA_VERSION}._"
    echo ""
    echo "To reproduce (requires \`brew install nghttp2\`):"
    echo '```bash'
    echo "cd benchmarks"
    echo "./run-benchmarks.sh --scenarios h2-hello,h2-high-concurrency"
    echo "./update-readme.sh"
    echo '```'
  } > "${H2_FILE}"

  inject_section "<!-- H2-BENCHMARK-START -->" "<!-- H2-BENCHMARK-END -->" "${H2_FILE}"
  echo "README.md HTTP/2 section updated."
else
  echo "No HTTP/2 h2load results found — skipping H2 section update (placeholder table preserved)."
fi

echo "Done."

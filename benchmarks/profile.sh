#!/usr/bin/env bash

#
# Copyright (c) 2026, FusionAuth, All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Profile the self benchmark server with JFR (JDK Flight Recorder).
#
# Builds and starts the server with JFR enabled, runs wrk against it, captures the .jfr file.
# Open the resulting file in JDK Mission Control (https://jdk.java.net/jmc/) for analysis.
#

set -euo pipefail

ulimit -S -n 32768

SCENARIO="${1:-realistic}"
DURATION_SECS=30
WARMUP_SECS=5
WRK_DURATION_SECS=$(( DURATION_SECS + WARMUP_SECS + 5 ))

SCRIPT_DIR="$(cd -P "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
SELF_DIR="${SCRIPT_DIR}/self"
SCENARIO_FILE="${SCRIPT_DIR}/scenarios/${SCENARIO}.lua"
TIMESTAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
OUTPUT_DIR="${SCRIPT_DIR}/profiles"
JFR_FILE="${OUTPUT_DIR}/${TIMESTAMP}-${SCENARIO}.jfr"

if [[ ! -f "${SCENARIO_FILE}" ]]; then
  echo "ERROR: Unknown scenario [${SCENARIO}]. Expected scenario file at ${SCENARIO_FILE}" >&2
  exit 1
fi

# Map scenario name to wrk threads/connections/endpoint (mirrors run-benchmarks.sh)
case "${SCENARIO}" in
  baseline)         THREADS=12;  CONNS=100;  ENDPOINT="/" ;;
  hello)            THREADS=12;  CONNS=100;  ENDPOINT="/hello" ;;
  post-load)        THREADS=12;  CONNS=100;  ENDPOINT="/load" ;;
  large-file)       THREADS=4;   CONNS=10;   ENDPOINT="/file?size=1048576" ;;
  high-concurrency) THREADS=12;  CONNS=1000; ENDPOINT="/" ;;
  mixed)            THREADS=12;  CONNS=100;  ENDPOINT="/" ;;
  realistic)        THREADS=12;  CONNS=100;  ENDPOINT="/" ;;
  *)                echo "ERROR: Unknown scenario [${SCENARIO}]" >&2; exit 1 ;;
esac

echo "=== JFR profile run: ${SCENARIO} ==="
echo "  JFR window: ${WARMUP_SECS}s warmup → ${DURATION_SECS}s recording"
echo "  wrk:        ${THREADS} threads, ${CONNS} connections, ${WRK_DURATION_SECS}s"
echo "  Endpoint:   ${ENDPOINT}"
echo ""

mkdir -p "${OUTPUT_DIR}"

# --- Build ---

echo "--- Building self ---"
(cd "${SELF_DIR}" && latte clean app) >/dev/null

# --- Free port ---

EXISTING_PID="$(lsof -ti :8080 2>/dev/null || true)"
if [[ -n "${EXISTING_PID}" ]]; then
  echo "WARNING: Port 8080 is in use by PID ${EXISTING_PID}. Kill it manually and re-run." >&2
  exit 1
fi

# --- Start server with JFR ---

# JFR records for DURATION_SECS starting WARMUP_SECS into the JVM lifetime, then auto-writes the file. settings=profile gives CPU sampling
# + allocation profiling + lock contention with low overhead. dumponexit ensures the file is written if the JVM is killed before the JFR
# duration completes.
export JAVA_OPTS="-XX:StartFlightRecording=delay=${WARMUP_SECS}s,duration=${DURATION_SECS}s,filename=${JFR_FILE},settings=profile,dumponexit=true"

echo "--- Starting server (JFR will record ${WARMUP_SECS}s..${WARMUP_SECS}+${DURATION_SECS}s after start) ---"
SERVER_LOG="${SELF_DIR}/build/server-profile.log"
(cd "${SELF_DIR}/build/dist" && ./start.sh) >"${SERVER_LOG}" 2>&1 &
SERVER_PID=$!

cleanup() {
  if kill -0 "${SERVER_PID}" 2>/dev/null; then
    kill "${SERVER_PID}" 2>/dev/null || true
    sleep 1
    kill -9 "${SERVER_PID}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# Wait for server ready
for _ in $(seq 1 30); do
  if curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ 2>/dev/null | grep -q 200; then
    READY=1
    break
  fi
  sleep 1
done
if [[ -z "${READY:-}" ]]; then
  echo "ERROR: Server did not start within 30s. See ${SERVER_LOG}" >&2
  exit 1
fi

echo "--- Server ready, running wrk for ${WRK_DURATION_SECS}s ---"
# SCENARIO_DIR lets the scenario file dofile() its sibling helpers (json-report.lua, etc.).
SCENARIO_DIR="${SCRIPT_DIR}/scenarios" \
wrk -t"${THREADS}" -c"${CONNS}" -d"${WRK_DURATION_SECS}s" \
    -s "${SCENARIO_FILE}" \
    "http://localhost:8080${ENDPOINT}" 2>&1 | tail -10

# Wait a beat for JFR to flush (it auto-writes when duration elapses).
sleep 2

echo "--- Stopping server ---"
cleanup
trap - EXIT

# --- Report ---

if [[ -f "${JFR_FILE}" ]]; then
  SIZE_KB="$(($(stat -f%z "${JFR_FILE}" 2>/dev/null || stat -c%s "${JFR_FILE}") / 1024))"
  echo ""
  echo "=== JFR file: ${JFR_FILE} (${SIZE_KB} KB) ==="
  echo ""
  echo "Open in JDK Mission Control (jmc) or run quick views:"
  echo "  jfr summary ${JFR_FILE}"
  echo "  jfr print --events jdk.ExecutionSample --stack-depth 5 ${JFR_FILE} | head -50"
  echo "  jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB --stack-depth 3 ${JFR_FILE} | head -50"
else
  echo "ERROR: JFR file not produced. Server log:" >&2
  tail -30 "${SERVER_LOG}" >&2
  exit 1
fi

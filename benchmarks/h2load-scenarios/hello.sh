#!/usr/bin/env bash
#
# Copyright (c) 2026 Latte Java
# SPDX-License-Identifier: MIT
#
# Sample h2load wrapper — h2 equivalent of benchmarks/scenarios/hello.lua.
# Run: ./benchmarks/h2load-scenarios/hello.sh <host:port>
# Or: ./tools/install-h2load.sh first to verify h2load is on PATH.
#
# This is a starting-point scenario. Full benchmark suite (h2 versions of
# all wrk scenarios) is deferred to a follow-up; running benchmarks is
# user-gated due to perf cost.

set -euo pipefail

HOST="${1:-http://127.0.0.1:8080}"
DURATION="${2:-10}"
CONNECTIONS="${3:-10}"
STREAMS_PER_CONN="${4:-100}"

if ! command -v h2load >/dev/null 2>&1; then
  echo "h2load not on PATH. Run ./tools/install-h2load.sh for instructions." >&2
  exit 1
fi

h2load \
  --duration="${DURATION}" \
  --clients="${CONNECTIONS}" \
  --max-concurrent-streams="${STREAMS_PER_CONN}" \
  --threads=2 \
  "${HOST}/hello"

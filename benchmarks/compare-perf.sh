#!/usr/bin/env bash

#
# Copyright (c) 2026, The Latte Project
#
# Licensed under the MIT License. See LICENSE in the project root for full license text.
#
# Diff two perf-test result files. Prints a normalized delta table for the
# nine summary metrics, plus an optional side-by-side detailed view when both
# files have a `detailed` section.
#

set -euo pipefail

usage() {
  cat <<EOF
Usage: $0 <baseline.json> <comparison.json>

Compares two perf-test JSON files (median values) and prints a delta table.
Higher-is-better for rps; lower-is-better for everything else. ANSI colour
is used when stdout is a TTY (green = improvement, red = regression).
EOF
  exit 1
}

[[ $# -eq 2 ]] || usage
BASELINE="$1"
COMPARISON="$2"
[[ -f "${BASELINE}"   ]] || { echo "ERROR: baseline not found: ${BASELINE}" >&2; exit 2; }
[[ -f "${COMPARISON}" ]] || { echo "ERROR: comparison not found: ${COMPARISON}" >&2; exit 2; }

# --- TTY colour ---
if [[ -t 1 ]]; then
  GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; RESET=$'\033[0m'
else
  GREEN=""; RED=""; DIM=""; RESET=""
fi

# --- Direction map: which way is "better" for each metric ---
# higher-better: rps. lower-better: everything else.
# Uses a function instead of declare -A for bash 3.2 (macOS default) compatibility.
metric_direction() {
  case "$1" in
    rps) echo "higher" ;;
    *)   echo "lower"  ;;
  esac
}

# Order matters for output readability.
METRICS=(rps avg_latency_us p99_us alloc_bytes_per_req alloc_bytes_per_sec
         gc_pause_ms_total gc_count heap_peak_mb errors)

# --- Header ---
echo "=== perf-test delta ==="
echo "  Baseline:    ${BASELINE}"
echo "    git:       $(jq -r '.git.sha + (if .git.dirty then "+dirty" else "" end)' "${BASELINE}")"
echo "    scenario:  $(jq -r '.scenario' "${BASELINE}") (${DIM}$(jq -r '.duration' "${BASELINE}") × $(jq -r '.trials' "${BASELINE}") trial(s)${RESET})"
echo "  Comparison:  ${COMPARISON}"
echo "    git:       $(jq -r '.git.sha + (if .git.dirty then "+dirty" else "" end)' "${COMPARISON}")"
echo "    scenario:  $(jq -r '.scenario' "${COMPARISON}") (${DIM}$(jq -r '.duration' "${COMPARISON}") × $(jq -r '.trials' "${COMPARISON}") trial(s)${RESET})"
echo ""

# --- Delta table ---
printf "%-22s %14s %14s %10s\n" "Metric" "Baseline" "Current" "Δ"
printf "%-22s %14s %14s %10s\n" "----------------------" "--------------" "--------------" "----------"

for metric in "${METRICS[@]}"; do
  base="$(jq -r ".summary.${metric}.median" "${BASELINE}")"
  cur="$(jq -r  ".summary.${metric}.median" "${COMPARISON}")"

  # Defensive guard: jq -r emits literal "null" for missing fields. Without
  # this, awk coerces "null" to 0 and we'd print misleading +∞% or -100% deltas.
  if [[ "${base}" == "null" || "${cur}" == "null" ]]; then
    printf "%-22s %14s %14s %10s\n" "${metric}" "${base}" "${cur}" "N/A"
    continue
  fi

  # Format delta. Skip percent if baseline is 0.
  if awk "BEGIN { exit !($base == 0) }"; then
    if awk "BEGIN { exit !($cur == 0) }"; then
      delta="    0"
      colour=""
    else
      delta="  +∞%"
      colour=""
    fi
  else
    pct="$(awk -v b="$base" -v c="$cur" 'BEGIN { printf "%+.1f%%", ((c - b) / b) * 100 }')"
    direction="$(metric_direction "${metric}")"
    if awk "BEGIN { exit !($cur == $base) }"; then
      colour=""
    elif [[ "${direction}" == "higher" ]]; then
      if awk "BEGIN { exit !($cur > $base) }"; then colour="${GREEN}"; else colour="${RED}"; fi
    else
      if awk "BEGIN { exit !($cur < $base) }"; then colour="${GREEN}"; else colour="${RED}"; fi
    fi
    delta="${pct}"
  fi

  # Format numbers with thousands separators where it helps readability.
  base_fmt="$(printf "%'.0f" "$base" 2>/dev/null || echo "$base")"
  cur_fmt="$(printf  "%'.0f" "$cur"  2>/dev/null || echo "$cur")"

  printf "%-22s %14s %14s %s%10s%s\n" "${metric}" "${base_fmt}" "${cur_fmt}" "${colour}" "${delta}" "${RESET}"
done

# --- Detailed section (only if both files have it) ---
HAS_BASE_DETAIL="$(jq -r 'if .detailed == null then "no" else "yes" end' "${BASELINE}")"
HAS_CUR_DETAIL="$( jq -r 'if .detailed == null then "no" else "yes" end' "${COMPARISON}")"

if [[ "${HAS_BASE_DETAIL}" == "yes" && "${HAS_CUR_DETAIL}" == "yes" ]]; then
  echo ""
  echo "=== Detailed: top hot methods (baseline → comparison) ==="
  paste \
    <(jq -r '.detailed.hot_methods[] | "\(.pct)% \(.method)"' "${BASELINE}"   | head -10) \
    <(jq -r '.detailed.hot_methods[] | "\(.pct)% \(.method)"' "${COMPARISON}" | head -10) \
    | column -t -s $'\t'
  echo ""
  echo "=== Detailed: top allocation sites (baseline → comparison) ==="
  paste \
    <(jq -r '.detailed.alloc_sites[] | "\(.pct)% \(.site)"' "${BASELINE}"   | head -10) \
    <(jq -r '.detailed.alloc_sites[] | "\(.pct)% \(.site)"' "${COMPARISON}" | head -10) \
    | column -t -s $'\t'
elif [[ "${HAS_BASE_DETAIL}" == "yes" || "${HAS_CUR_DETAIL}" == "yes" ]]; then
  echo ""
  echo "(detailed view skipped — only one of the two runs has --detailed data)"
fi

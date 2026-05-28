#!/usr/bin/env bash

#
# Copyright (c) 2026 Latte Java
# SPDX-License-Identifier: MIT
#

SOURCE="${BASH_SOURCE[0]}"
while [[ -h ${SOURCE} ]]; do
  SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" >/dev/null && pwd)"
  SOURCE="$(readlink "${SOURCE}")"
  [[ ${SOURCE} != /* ]] && SOURCE="${SCRIPT_DIR}/${SOURCE}"
done
SCRIPT_DIR="$(cd -P "$(dirname "${SOURCE}")" > /dev/null && pwd)"

if [[ ! -d lib ]]; then
  echo "Unable to locate library files needed to run the benchmark. [lib]. Ensure you run this from build/dist."
  exit 1
fi

CLASSPATH=.
for f in lib/*.jar; do
  CLASSPATH=${CLASSPATH}:${f}
done

suspend=""
if [[ $# -ge 1 && $1 == "--suspend" ]]; then
  suspend="-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=8000"
  shift
fi

ulimit -S -n 32768
java ${suspend} -cp "${CLASSPATH}" org.lattejava.http.benchmark.HelidonLoadServer

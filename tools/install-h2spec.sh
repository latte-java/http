#!/usr/bin/env bash
# Download h2spec for the host platform into build/h2spec.
set -euo pipefail

VERSION="${H2SPEC_VERSION:-2.6.0}"
DIR="build"
BIN="${DIR}/h2spec"

if [[ -x "${BIN}" ]]; then
  echo "h2spec already present at ${BIN}"
  exit 0
fi

mkdir -p "${DIR}"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64|Darwin-x86_64) ASSET="h2spec_darwin_amd64.tar.gz" ;;
  Linux-x86_64) ASSET="h2spec_linux_amd64.tar.gz" ;;
  *) echo "unsupported platform $(uname -s)-$(uname -m)" >&2; exit 1 ;;
esac

URL="https://github.com/summerwind/h2spec/releases/download/v${VERSION}/${ASSET}"
echo "Downloading ${URL}"
curl -fsSL "${URL}" -o "${DIR}/h2spec.tar.gz"
tar -xzf "${DIR}/h2spec.tar.gz" -C "${DIR}"
rm "${DIR}/h2spec.tar.gz"
chmod +x "${BIN}"
echo "Installed h2spec ${VERSION} at ${BIN}"

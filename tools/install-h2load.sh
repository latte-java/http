#!/usr/bin/env bash
# Verify h2load (from nghttp2) is installed. h2load is the HTTP/2 equivalent
# of wrk — wrk doesn't speak h2.
set -euo pipefail

if command -v h2load >/dev/null 2>&1; then
  echo "h2load found: $(h2load --version | head -1)"
  exit 0
fi

echo "h2load not installed. Install via:" >&2
case "$(uname -s)" in
  Darwin) echo "  brew install nghttp2" >&2 ;;
  Linux)  echo "  sudo apt-get install nghttp2 (Debian/Ubuntu)" >&2
          echo "  sudo dnf install nghttp2 (Fedora)" >&2 ;;
  *) echo "  see https://nghttp2.org/" >&2 ;;
esac
exit 1

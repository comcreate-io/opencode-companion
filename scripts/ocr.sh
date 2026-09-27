#!/usr/bin/env bash
set -euo pipefail

# Official review-only tool; never a runtime dependency or global installation.
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
version=1.12.9
checksum=9105c7081b8362a1cb0167f1ddfd1437e147a0e3c9a5af89c03c8859a5b0f0e8
binary="$root/.android-local/review/bin/ocr"

if [[ $(uname -s) != Linux || $(uname -m) != x86_64 ]]; then
  echo 'This pinned launcher supports Linux x86_64; see docs/CODE_REVIEW.md.' >&2
  exit 1
fi
if [[ ! -f "$binary" ]]; then
  mkdir -p -- "$(dirname -- "$binary")"
  download=$(mktemp "${binary}.XXXXXX")
  trap 'rm -f -- "$download"' EXIT
  curl --fail --location --silent --show-error --proto '=https' --proto-redir '=https' \
    --connect-timeout 15 --max-time 180 \
    "https://github.com/alibaba/open-code-review/releases/download/v${version}/opencodereview-linux-amd64" \
    --output "$download"
  printf '%s  %s\n' "$checksum" "$download" | sha256sum --check --status
  chmod 755 "$download"
  mv -- "$download" "$binary"
fi
printf '%s  %s\n' "$checksum" "$binary" | sha256sum --check --status
cd -- "$root"
exec "$binary" "$@"

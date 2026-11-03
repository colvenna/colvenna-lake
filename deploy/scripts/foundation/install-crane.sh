#!/usr/bin/env bash
# Puts the pinned crane into <dir> (default deploy/.tools), its archive checked against its SHA-256.
# crane copies and tags images by digest (deploy/scripts/doris/copy-images.sh, the release scripts).
# install-tools.sh runs it for an environment's tools; it needs no environment, so CI and the offline
# tests of deploy/scripts/release/promote.sh run it alone.
# Usage: deploy/scripts/foundation/install-crane.sh [<dir>]
set -euo pipefail
tools_dir="${1:-$(cd "$(dirname "$0")/../.." && pwd)/.tools}"
crane_version=v0.22.1
case "$(uname -m)" in
  aarch64) crane_archive=go-containerregistry_Linux_arm64.tar.gz
    crane_sha256=898c0cff975f898a33e8c4580bdafb0e7c02c7faa33374e946762f97c4ab7110 ;;
  x86_64) crane_archive=go-containerregistry_Linux_x86_64.tar.gz
    crane_sha256=0ab7a1d6932a213aed964ce97666c3077fe691c8606413674a8b3e0b9ec4cda0 ;;
  *) echo "The tools installer supports arm64 and amd64" >&2; exit 1 ;;
esac
mkdir -p "$tools_dir"
curl -fsSL "https://github.com/google/go-containerregistry/releases/download/$crane_version/$crane_archive" \
  -o "$tools_dir/$crane_archive"
printf '%s  %s\n' "$crane_sha256" "$tools_dir/$crane_archive" | sha256sum --check
tar -xzf "$tools_dir/$crane_archive" -C "$tools_dir" crane
chmod 0755 "$tools_dir/crane"
rm "$tools_dir/$crane_archive"
echo "crane $crane_version is in $tools_dir"

#!/usr/bin/env bash
# Builds and tests colvenna-cli in the pinned Go image (Go 1.27.3) and lints it in the pinned
# golangci-lint image, with the module cache in ~/.cache/colvenna-go so reruns work offline.
# `check.sh build` also writes dist/colvenna-<os>-<arch> binaries.
set -euo pipefail
cli="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$cli/.." && pwd)"
go_image="golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132"
lint_image="golangci/golangci-lint@sha256:ad862ba6b3798cbe0fd9fd7408d498fd74fbd2623a92406b2fd3898faf0bf98f"
cache="${GOMODCACHE_DIR:-$HOME/.cache/colvenna-go}"
mkdir -p "$cache"
# in_image <image> <command>...: runs the command in the module's directory, in that image.
in_image() {
  docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e GOCACHE=/tmp/go-build -e GOMODCACHE=/gomod \
    -e GOTOOLCHAIN=local \
    -v "$cache:/gomod" -v "$repo:/work" -w /work/colvenna-cli "$@"
}
go_run() { in_image "$go_image" "$@"; }
lint_run() { in_image "$lint_image" "$@"; }
go_run go mod verify
go_run go vet ./...
go_run go test ./...
lint_run golangci-lint run --config ../.golangci.yml
lint_run golangci-lint fmt --config ../.golangci.yml --diff
if [[ "${1:-}" == build ]]; then
  sha="$(git -C "$repo" rev-parse --short HEAD)"
  # A build of uncommitted changes says so in its version.
  [[ -z "$(git -C "$repo" status --porcelain)" ]] || sha="$sha-dirty"
  for target in linux/amd64 linux/arm64 darwin/arm64; do
    go_run env CGO_ENABLED=0 GOOS="${target%/*}" GOARCH="${target#*/}" go build -trimpath \
      -ldflags "-s -w -X github.com/colvenna/colvenna-lake/colvenna-cli/internal/cli.version=$sha" \
      -o "dist/colvenna-${target%/*}-${target#*/}" .
  done
fi

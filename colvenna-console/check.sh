#!/usr/bin/env bash
# Builds and tests colvenna-console in the pinned Go image (Go 1.27.3) and lints it in the pinned
# golangci-lint image, with the module cache in ~/.cache/colvenna-go so reruns work offline.
set -euo pipefail
console="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$console/.." && pwd)"
go_image="golang:1.27.3-bookworm@sha256:21ade014faf952365b9ecbd8dbdb87176fbacb82fac9fb132d9f2a7b8cc3f132"
lint_image="golangci/golangci-lint@sha256:ad862ba6b3798cbe0fd9fd7408d498fd74fbd2623a92406b2fd3898faf0bf98f"
cache="${GOMODCACHE_DIR:-$HOME/.cache/colvenna-go}"
mkdir -p "$cache"
# in_image <image> <command>...: runs the command in the module's directory, in that image.
in_image() {
  docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e GOCACHE=/tmp/go-build -e GOMODCACHE=/gomod \
    -e GOTOOLCHAIN=local \
    -v "$cache:/gomod" -v "$repo:/work" -w /work/colvenna-console "$@"
}
go_run() { in_image "$go_image" "$@"; }
lint_run() { in_image "$lint_image" "$@"; }
go_run go mod verify
go_run go vet ./...
go_run go test ./...
lint_run golangci-lint run --config ../.golangci.yml
lint_run golangci-lint fmt --config ../.golangci.yml --diff

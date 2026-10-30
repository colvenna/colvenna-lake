#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
image="golangci/golangci-lint@sha256:ad862ba6b3798cbe0fd9fd7408d498fd74fbd2623a92406b2fd3898faf0bf98f"
docker_args=(
  run --rm
  -v "$repo_root:/work:ro"
  -w /work/assets/architecture
  "$image"
  golangci-lint
)
docker "${docker_args[@]}" run --config ../../.golangci.yml
docker "${docker_args[@]}" fmt --config ../../.golangci.yml --diff

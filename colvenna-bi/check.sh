#!/usr/bin/env bash
# Builds the BI image for this machine, runs colvenna_bi's unit tests in it (they need Superset's own
# packages: PyJWT, cryptography, SQLAlchemy, Flask, pydoris), and imports the configuration.
set -euo pipefail
bi="$(cd "$(dirname "$0")" && pwd)"
docker buildx build --load -t colvenna-bi:check "$bi" >/dev/null
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e PYTHONPATH=/src -e PYTHONDONTWRITEBYTECODE=1 \
  -v "$bi:/src:ro" -w /src --entrypoint python colvenna-bi:check -m unittest discover -s tests -v 2>&1 | tail -n 30
# The configuration and every module import in the image (the dialect needs pydoris).
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/tmp -e PYTHONDONTWRITEBYTECODE=1 -e SUPERSET_SECRET_KEY=x \
  -e COLVENNA_BI_METADATA_URI=sqlite:// -e COLVENNA_BI_GUEST_TOKEN_SECRET=x --entrypoint python colvenna-bi:check -c \
  'import superset_config, colvenna_bi.dialect, colvenna_bi.sync, colvenna_bi.setup, colvenna_bi.tls; print("config imports")'

# Apache Superset 6.1.0 (Python 3.11) with the Doris and PostgreSQL drivers and Colvenna Lake's
# extensions (colvenna_bi, superset_config.py). mysqlclient, which pydoris needs, builds from source:
# its build tools stay in the first stage.
FROM apache/superset:6.1.0-py311@sha256:95b060f3450f3314019700642c20d2149dc0e0b41d65c0ef8a1bd8018fbf385c AS build
USER root
RUN apt-get update && apt-get install -y --no-install-recommends build-essential pkg-config \
      default-libmysqlclient-dev && rm -rf /var/lib/apt/lists/*
COPY requirements.txt /tmp/requirements.txt
RUN pip wheel --no-cache-dir --wheel-dir /wheels -r /tmp/requirements.txt

FROM apache/superset:6.1.0-py311@sha256:95b060f3450f3314019700642c20d2149dc0e0b41d65c0ef8a1bd8018fbf385c
USER root
RUN apt-get update && apt-get install -y --no-install-recommends libmariadb3 \
      && rm -rf /var/lib/apt/lists/*
COPY --from=build /wheels /wheels
COPY requirements.txt /tmp/requirements.txt
# Into Superset's own environment (/app/.venv), which has no pip of its own; what it already has
# stays (pip installs only what the requirements lack). Not uv: it crashes under emulation when the
# image is built for amd64 on arm64.
RUN pip --python /app/.venv/bin/python install --no-cache-dir --no-index --find-links /wheels \
      -r /tmp/requirements.txt && rm -rf /wheels /tmp/requirements.txt
COPY colvenna_bi /app/colvenna/colvenna_bi
COPY superset_config.py /app/colvenna/superset_config.py
ENV PYTHONPATH=/app/colvenna SUPERSET_CONFIG_PATH=/app/colvenna/superset_config.py
USER 1000:1000

#!/usr/bin/env bash
# Checks Colvenna Lake's alerts and panels as the chart ships them, without a cluster: renders the
# colvenna-monitoring chart, checks the Prometheus configuration and alert rules it renders with
# promtool (the Prometheus image the chart runs, here from upstream by its pinned digest:
# deploy/images/third-party.tsv), runs their unit tests (rules-test.yaml), and checks that the
# checked-in dashboard is what dashboard.py writes. Needs helm, docker and python3; CI runs it.
#
# usage: check-rules.sh
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../../.." && pwd)"
chart="$repo/deploy/helm/colvenna-monitoring"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# The list as manifest.py reads it (and validates it): name, upstream reference, digest, tag.
list="$(python3 "$repo/deploy/scripts/release/manifest.py" third-party)"
image="$(awk -F'\t' '$1 == "prometheus" {print $2}' <<<"$list")"
[[ "$image" == *@sha256:* ]] || { echo "no pinned Prometheus image in deploy/images/third-party.tsv" >&2; exit 1; }
# The chart requires its images, which the ConfigMaps rendered here do not use.
images=(--set images.prometheus="$image" --set images.grafana=grafana)
helm template check "$chart" --namespace colvenna-monitoring "${images[@]}" \
  --show-only templates/prometheus-config.yaml >"$work/configmap.yaml"
# The ConfigMap's two files, each a literal block indented four spaces.
python3 - "$work" <<'PY'
import pathlib, re, sys
work = pathlib.Path(sys.argv[1])
text = (work / "configmap.yaml").read_text()
for name in ("prometheus.yml", "rules.yaml"):
    m = re.search(r"^  " + re.escape(name) + r": \|\n((?:    .*\n|\n)+)", text, re.M)
    if not m:
        sys.exit(f"{name} is not in the rendered ConfigMap")
    body = "".join(line[4:] + "\n" for line in m.group(1).splitlines())
    (work / name).write_text(body)
PY
sed -i 's|/etc/prometheus/rules/rules.yaml|/c/rules.yaml|' "$work/prometheus.yml"
cp "$here/rules-test.yaml" "$work/rules-test.yaml"
chmod -R a+rX "$work"
promtool() {
  docker run --rm --network none -v "$work:/c:ro" -w /c --entrypoint promtool "$image" "$@"
}
promtool check config --syntax-only /c/prometheus.yml
promtool check rules /c/rules.yaml
promtool test rules /c/rules-test.yaml

# The panels as the chart renders them: valid JSON, the latency targets filled in.
helm template check "$chart" --namespace colvenna-monitoring "${images[@]}" \
  --show-only templates/grafana-config.yaml >"$work/grafana-config.yaml"
python3 - "$work/grafana-config.yaml" <<'PY'
import json, re, sys
text = open(sys.argv[1]).read()
m = re.search(r"^  colvenna-lake.json: \|\n((?:    .*\n|\n)+)", text, re.M)
body = "".join(line[4:] + "\n" for line in m.group(1).splitlines())
if "__TARGET_" in body:
    sys.exit("the rendered dashboard still holds a placeholder")
json.loads(body)
PY
python3 "$here/dashboard.py" "$work/dashboard.json"
if ! cmp -s "$work/dashboard.json" "$chart/files/dashboard.json"; then
  echo "files/dashboard.json is not what dashboard.py writes: run it and commit the result" >&2
  exit 1
fi
echo "rules, their tests and the dashboard check out"

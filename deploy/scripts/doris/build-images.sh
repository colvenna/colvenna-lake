#!/usr/bin/env bash
# Builds the platform's Doris FE and BE images from the recipe in deploy/images/doris, from a
# committed tree, and pushes them to the environment's repository, colvenna-lake-<env>/query-engine (a
# modified build based on Apache Doris is distributed under a name of its own:
# release/trademarks.md), tagged build-<sha>-fe and build-<sha>-be (the repository keeps the last
# two builds). The recipe: the Apache Doris 4.1.4
# images, the PostgreSQL JDBC driver, and the Paimon 2.0.0 reader that prepare-reader.py compiles
# and merges from pinned Paimon jars, Doris sources and the stock BE's own connector jars (all
# checked against reader-lock.json).
#
# The images an install runs are the pinned ones (lib/common.sh's DORIS_FE_DIGEST and
# DORIS_BE_DIGEST, put in place by copy-images.sh). This build reproduces them: it compares the
# reader it prepared, jar by jar, with the one inside the pinned FE and BE, and records both
# digests and the comparison in <evidence-dir>.
# Usage: build-images.sh <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"
assert_account
evidence="${1:?usage: build-images.sh <evidence-dir>}"
mkdir -p "$evidence"
sha="$(committed_sha)"
recipe="$DEPLOY_DIR/images/doris"
work="$(mktemp -d "$TOOLS_DIR/doris-build.XXXXXX")"
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
trap 'rm -rf "$work" "$DOCKER_CONFIG"' EXIT
aws ecr get-login-password | docker login --username AWS --password-stdin "$ECR_REGISTRY" >/dev/null

# The stock BE's connector jars, which the reader is merged from.
stock_be="$(sed -n 's/^FROM //p' "$recipe/be.Dockerfile")"
docker pull -q "$stock_be" >/dev/null
container="$(docker create "$stock_be")"
extensions=/opt/apache-doris/be/lib/java_extensions
mkdir -p "$work/stock"
docker cp "$container:$extensions/paimon-connector/paimon-connector-jar-with-dependencies.jar" "$work/stock/connector.jar"
docker cp "$container:$extensions/preload-extensions/preload-extensions-jar-with-dependencies.jar" "$work/stock/preload.jar"
docker cp "$container:$extensions/java-udf/java-udf-jar-with-dependencies.jar" "$work/stock/udf.jar"
docker rm "$container" >/dev/null

context="$work/context"
mkdir -p "$context/jars"
python3 "$recipe/prepare-reader.py" --stock-be-connector "$work/stock/connector.jar" \
  --stock-preload "$work/stock/preload.jar" --stock-udf "$work/stock/udf.jar" \
  --cache "$TOOLS_DIR/doris-reader-cache" --output "$context/reader" >"$evidence/prepare.json"
python3 "$recipe/prepare-reader.py" --verify-output "$context/reader"
cp "$context/reader/receipt.json" "$evidence/receipt.json"
driver="$context/jars/postgresql-42.7.8.jar"
curl -fsSL https://repo1.maven.org/maven2/org/postgresql/postgresql/42.7.8/postgresql-42.7.8.jar -o "$driver"
printf '%s  %s\n' 2a32a9dcbc42d67a50ad3a0de5efd102c8d2be46720045f2cbd6689f160ab7c7 "$driver" \
  | sha256sum --check >/dev/null

for component in fe be; do
  docker buildx build --platform linux/amd64 --provenance=false \
    --label "org.opencontainers.image.revision=$sha" --label org.opencontainers.image.version=4.1.4 \
    -f "$recipe/$component.Dockerfile" -t "$DORIS_REPOSITORY:build-$sha-$component" \
    --metadata-file "$work/$component-metadata.json" --push "$context" >"$evidence/$component-build.log" 2>&1
  python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["containerimage.digest"])' \
    "$work/$component-metadata.json" >"$evidence/$component-digest.txt"
  echo "$component: $DORIS_REPOSITORY@$(cat "$evidence/$component-digest.txt")"
done

# The reader inside the pinned images, against the one prepared here.
for component in fe be; do
  digest="$DORIS_FE_DIGEST"
  [[ "$component" == be ]] && digest="$DORIS_BE_DIGEST"
  docker pull -q "$DORIS_REPOSITORY@$digest" >/dev/null
  container="$(docker create "$DORIS_REPOSITORY@$digest")"
  docker cp "$container:/opt/apache-doris/paimon2-reader-receipt.json" "$work/pinned-$component.json"
  docker rm "$container" >/dev/null
done
python3 - "$evidence/receipt.json" "$work/pinned-fe.json" "$work/pinned-be.json" <<'PY' | tee "$evidence/comparison.txt"
import json, sys
built = json.load(open(sys.argv[1]))
same = True
for path in sys.argv[2:]:
    pinned = json.load(open(path))
    for key in ("stock_inputs_sha256", "output_sha256"):
        if built[key] != pinned[key]:
            same = False
            for name in sorted(set(built[key]) | set(pinned[key])):
                if built[key].get(name) != pinned[key].get(name):
                    print(f"{key} {name}: built {built[key].get(name)}, pinned {pinned[key].get(name)}")
print("reader jars identical to the pinned images'" if same else "reader jars differ from the pinned images'")
sys.exit(0 if same else 1)
PY

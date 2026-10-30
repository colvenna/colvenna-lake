#!/usr/bin/env bash
# After the engine flows: did any client call an endpoint colvenna-catalog lacks? Collects the catalog
# pods' logs since <since> and fails on any "no such endpoint", or on a 501 for an action other
# than those declined by design (partition listing of tables whose partitions live in files, where
# the Paimon client falls back to the file system).
# Usage: check-catalog-logs.sh <since, RFC 3339> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
since="${1:?usage: check-catalog-logs.sh <since> <evidence-dir>}"
evidence="${2:?usage: check-catalog-logs.sh <since> <evidence-dir>}"
mkdir -p "$evidence"
logs="$evidence/catalog.log"
: >"$logs"
for pod in $(kubectl -n "$CATALOG_NAMESPACE" get pods -l app.kubernetes.io/name=colvenna-catalog \
  -o jsonpath='{.items[*].metadata.name}'); do
  kubectl -n "$CATALOG_NAMESPACE" logs "$pod" --since-time="$since" >>"$logs"
done
missing="$(grep -c 'no such endpoint' "$logs" || true)"
grep -oE 'not implemented \([A-Z_]+\)' "$logs" | sort | uniq -c >"$evidence/not-implemented.txt" || true
unexpected="$(grep -oE 'not implemented \([A-Z_]+\)' "$logs" | grep -cv 'LIST_PARTITIONS' || true)"
{
  echo "log lines: $(wc -l <"$logs")"
  echo "no such endpoint: $missing"
  echo "501 by action:"
  cat "$evidence/not-implemented.txt"
} | tee "$evidence/endpoint-check.txt"
if [[ "$missing" != 0 || "$unexpected" != 0 ]]; then
  echo "FAIL: a client called an endpoint the catalog does not serve" | tee -a "$evidence/endpoint-check.txt"
  exit 1
fi
echo PASS | tee -a "$evidence/endpoint-check.txt"

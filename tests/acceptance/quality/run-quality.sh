#!/usr/bin/env bash
# Acceptance on EKS: batch quality checks of a Kafka event table and the gate they keep on its
# publication, from declaration to deletion. Writes PASS/FAIL lines to <evidence>/verdicts.tsv
# and fails if any check fails.
#
# - A Kafka source (the development Kafka) writes clicks partitioned by day, published to a
#   reader principal and read by a dashboard; a LakeQuality declares not-null, unique, row-count
#   and freshness rules and gates the publication. Before any check the gate is closed: the reader
#   is refused, the dashboard serves no one.
# - Yesterday's and today's partitions arrive clean: checks of each pass (`colvenna check --wait`
#   exits 0), the gate opens, the reader and the dashboard are served.
# - Bad events arrive in today's partition (no user, repeated ids): its check fails the two
#   rules (exit 3), the gate closes and the publication is withdrawn; the failed snapshot is
#   tagged and each failed rule's locate statement lists exactly the bad events, read through the
#   tag. The same check repeated on the same snapshot and time writes the same results. Deleting
#   the failed checks' tasks does not open the gate: it stands on the store's results.
# - A waiver naming someone else is refused by the API server, a user without the right cannot
#   make one, one naming a rule that passed is Rejected; `colvenna waive` (its approver the caller)
#   opens the gate again. A check whose run is killed writes nothing and changes nothing.
# - Changed rules need a check of their own (the gate closes, the waiver is superseded); a
#   passing check reopens it; a gate whose newest check is older than its maxAge closes.
# - Every change of the gate is in the store's decisions, with its check and waiver; deleting the
#   LakeQuality records the removal and drops its tag.
#
# usage: run-quality.sh <run-id> <evidence-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
# shellcheck source=../../../deploy/scripts/lib/catalog-admin.sh
source "$REPO_DIR/deploy/scripts/lib/catalog-admin.sh"
assert_account
ensure_kubeconfig
run="${1:?usage: run-quality.sh <run-id> <evidence-dir>}"
evidence="${2:?usage: run-quality.sh <run-id> <evidence-dir>}"
[[ "$run" =~ ^[a-z0-9]{1,12}$ ]] || { echo "run id: lowercase letters and digits, at most 12" >&2; exit 1; }
sha="$(committed_sha)"
here="$REPO_DIR/tests/acceptance/quality"
lake="$REPO_DIR/tests/acceptance/lake.sh"
mkdir -p "$evidence"/{status,dumps,batches,locate}
exec > >(tee -a "$evidence/run.log") 2>&1
echo "run $run at $sha by $(aws sts get-caller-identity --query Arn --output text)"
for image in operator kafka sql; do
  echo "$image $(image_ref "$image")" | tee -a "$evidence/images.txt"
done
failed=0
step() { echo "$(date -u +%FT%TZ)	$*" | tee -a "$evidence/timeline.tsv"; }
verdict() {
  # One line per verdict: a detail's newlines and tabs become spaces.
  local detail="${3:-}"
  detail="${detail//$'\n'/ }"
  echo "$(date -u +%FT%TZ)	$1	$2	${detail//$'\t'/ }" | tee -a "$evidence/verdicts.tsv"
  [[ "$2" == PASS ]] || failed=1
}
colvenna="$(colvenna_cli)"
"$colvenna" version | tee -a "$evidence/images.txt"
c() { "$colvenna" -n "$JOB_NAMESPACE" "$@"; }

name="q-$run" topic="qual-$run" db="ods_qual_$run"
table="$db.clicks" reader="qual-reader-$run" dashboard="qd-$run"
store="quality_${JOB_NAMESPACE//-/_}"
bootstrap="$DEV_KAFKA_RELEASE-kafka-bootstrap.$DEV_KAFKA_NAMESPACE.svc:9093"
client_label="colvenna-lake.io/dev-kafka-client"
bi="colvenna-bi-client"
fe="$DORIS_CLUSTER-fe-service.$DORIS_NAMESPACE.svc"
yesterday="$(date -u -d yesterday +%F)" today="$(date -u +%F)"
small="{jobManager: {cpu: 0.5, memory: 1536m}, taskManager: {cpu: 0.5, memory: 2048m}}"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'admin_close; rm -rf "$work"' EXIT

# get <kind> <name> <file>: the object as JSON, kept as evidence.
get() { kubectl -n "$JOB_NAMESPACE" get "$1" "$2" -o json >"$3"; }

# await <label> <timeout-seconds> <kind> <name> <python expression over s, the object>
await() {
  local label="$1" end=$((SECONDS + $2)) started=$SECONDS file="$evidence/status/$1.json"
  while ((SECONDS < end)); do
    if get "$3" "$4" "$file" 2>/dev/null && python3 - "$file" "$5" <<'PY'; then
import json, sys
s = json.load(open(sys.argv[1]))
st = s.get("status") or {}
def cond(t):
    return next((c.get("status") for c in st.get("conditions", []) if c.get("type") == t), None)
def reason(t):
    return next((c.get("reason") for c in st.get("conditions", []) if c.get("type") == t), None)
def msg(t):
    return next((c.get("message") for c in st.get("conditions", []) if c.get("type") == t), None) or ""
gate = st.get("gate") or {}
sys.exit(0 if eval(sys.argv[2]) else 1)
PY
      verdict "$label" PASS "$((SECONDS - started))s"
      return 0
    fi
    sleep 5
  done
  verdict "$label" FAIL "not within $2s: $5"
  python3 -c 'import json,sys; s=json.load(open(sys.argv[1])).get("status",{}); print(s.get("phase"), s.get("message"))' "$file" || true
  return 1
}

# reader_status: what colvenna-catalog answers the reader for the table (200 published, 403 not).
reader_status() {
  kubectl -n "$JOB_NAMESPACE" get secret "$reader-token" -o jsonpath='{.data.token}' | base64 -d \
    | python3 -c 'import sys; print("header = \"Authorization: Bearer %s\"" % sys.stdin.read())' \
      >"$ADMIN_DIR/reader.curlrc"
  curl -s -o /dev/null -w '%{http_code}' --cacert "$ADMIN_DIR/ca.crt" -K "$ADMIN_DIR/reader.curlrc" \
    "https://localhost:$ADMIN_PORT/v1/colvenna/databases/$db/tables/clicks"
}

# await_reader <label> <code>: the reader gets that answer within two minutes.
await_reader() {
  local end=$((SECONDS + 120)) got
  while ((SECONDS < end)); do
    got="$(reader_status)"
    if [[ "$got" == "$2" ]]; then
      verdict "$1" PASS "colvenna-catalog answers the reader $got"
      return 0
    fi
    sleep 5
  done
  verdict "$1" FAIL "colvenna-catalog answers the reader $got, not $2"
}

# dashboard_query <out>: the dashboard user's query of its view, from the BI namespace.
dashboard_query() {
  local pod="qd-query-$RANDOM" database
  database="$(kubectl -n "$JOB_NAMESPACE" get lakedashboard "$dashboard" -o jsonpath='{.status.database}')"
  kubectl -n "$bi" create secret generic "$pod" --from-file=password="$work/analyst" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat >"$work/$pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod, namespace: $bi}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: mysql
      image: $MYSQL_CLIENT_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "-c"]
      args:
        - |
          export MYSQL_PWD="\$(cat /in/password)"
          mysql -h $fe -P 9030 -u '$JOB_NAMESPACE.$dashboard.analyst' --connect-timeout=10 --batch \
            -e 'SELECT SUM(n) FROM \`$database\`.clicks_live' 2>&1 | sed 's/^ERROR [0-9]* ([A-Z0-9]*)[^:]*: /ERROR /'
      resources: {requests: {cpu: 10m, memory: 64Mi}, limits: {memory: 128Mi}}
      volumeMounts: [{name: in, mountPath: /in, readOnly: true}]
  volumes: [{name: in, secret: {secretName: $pod}}]
YAML
  run_pod "$bi" "$pod" "$work/$pod.yaml" >"$1" || true
  kubectl -n "$bi" delete secret "$pod" --ignore-not-found >/dev/null
}

# await_dashboard <label> served|refused: the user's query answers rows, or an error.
await_dashboard() {
  local end=$((SECONDS + 180)) out="$evidence/status/$1.txt"
  while ((SECONDS < end)); do
    dashboard_query "$out"
    if [[ "$2" == served ]] && ! grep -q '^ERROR' "$out" && [[ -s "$out" ]]; then
      verdict "$1" PASS "the user's query answers: $(tr '\n' ' ' <"$out")"
      return 0
    fi
    if [[ "$2" == refused ]] && grep -q '^ERROR' "$out"; then
      verdict "$1" PASS "$(head -1 "$out")"
      return 0
    fi
    sleep 10
  done
  verdict "$1" FAIL "$2 expected: $(tr '\n' ' ' <"$out")"
}

# check <task> <expected-exit> [<colvenna check flags>...]: a check by the CLI, waited for.
check() {
  local task="$1" want="$2" code=0
  shift 2
  c check "$table" --name "$task" --wait --wait-timeout 20m "$@" >"$evidence/status/$task.cli" 2>&1 \
    || code=$?
  get laketask "$task" "$evidence/status/$task.json" || true
  if [[ "$code" == "$want" ]]; then
    verdict "check-$task-exit" PASS "colvenna check exited $code: $(tail -1 "$evidence/status/$task.cli")"
  else
    verdict "check-$task-exit" FAIL "colvenna check exited $code, not $want: $(tail -3 "$evidence/status/$task.cli")"
  fi
}

uid() { kubectl -n "$JOB_NAMESPACE" get laketask "$1" -o jsonpath='{.metadata.uid}'; }

# results <label> <task> <rule>=<passed>...: the check's rows in the store.
results() {
  local label="$1" task="$2"
  shift 2
  bash "$lake" rows "$evidence/dumps/results-$label.jsonl" "$store.results" >/dev/null
  if python3 "$here/quality.py" results "$evidence/dumps/results-$label.jsonl" "$(uid "$task")" "$@" \
    >"$evidence/dumps/results-$label.verdict.json"; then
    verdict "results-$label" PASS "$(python3 -c 'import json,sys; print([(r["rule"], r["passed"], r["measured"], r["failing_rows"]) for r in json.load(open(sys.argv[1]))["rows"]])' "$evidence/dumps/results-$label.verdict.json")"
  else
    verdict "results-$label" FAIL "$(cat "$evidence/dumps/results-$label.verdict.json")"
  fi
}

# produce <batch-file>: sends key|value lines to the topic as the producer.
produce() {
  local pod="qual-produce-$RANDOM"
  kubectl -n "$DEV_KAFKA_NAMESPACE" get secret qual-producer -o jsonpath='{.data.sasl\.jaas\.config}' \
    | base64 -d >"$work/jaas"
  kubectl -n "$JOB_NAMESPACE" create secret generic "$pod" --from-file=sasl.jaas.config="$work/jaas" \
    --from-file=ca.crt="$work/kafka-ca.pem" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  rm -f "$work/jaas"
  kubectl -n "$JOB_NAMESPACE" create configmap "$pod" --from-file=batch.txt="$1" \
    --dry-run=client -o yaml | kubectl apply -f - >/dev/null
  cat >"$work/$pod.yaml" <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext: {runAsNonRoot: true, runAsUser: 1001, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: kafka
      image: $KAFKA_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      command: ["bash", "-ec"]
      args:
        - |
          printf 'security.protocol=SASL_SSL\nsasl.mechanism=SCRAM-SHA-512\nssl.truststore.type=PEM\nssl.truststore.location=/client/ca.crt\n' >/tmp/client.properties
          printf 'sasl.jaas.config=%s\n' "\$(cat /client/sasl.jaas.config)" >>/tmp/client.properties
          /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server $bootstrap --producer.config /tmp/client.properties --topic $topic --property parse.key=true --property key.separator='|' </data/batch.txt
          echo produced \$(wc -l </data/batch.txt)
      resources: {requests: {cpu: 100m, memory: 512Mi}, limits: {memory: 512Mi}}
      volumeMounts:
        - {name: client, mountPath: /client, readOnly: true}
        - {name: data, mountPath: /data, readOnly: true}
  volumes:
    - {name: client, secret: {secretName: $pod}}
    - {name: data, configMap: {name: $pod}}
YAML
  run_pod "$JOB_NAMESPACE" "$pod" "$work/$pod.yaml" || true
  kubectl -n "$JOB_NAMESPACE" delete secret,configmap "$pod" --ignore-not-found >/dev/null
}

# batch <name> <day> <count> [--nulls N] [--dups N]: generates, sends and waits for the events.
batch() {
  local label="$1" day="$2" count="$3" sent
  shift 3
  python3 "$here/quality.py" events "$run-$label" "$day" "$count" "$@" \
    --expected "$evidence/batches/$label.json" >"$evidence/batches/$label.txt"
  produce "$evidence/batches/$label.txt"
  sent="$(wc -l <"$evidence/batches/$label.txt")"
  total=$((total + sent))
  # A miss is a FAIL verdict; the run goes on to its cleanup.
  await "events-$label" 600 lakesource "$name" \
    "st.get('phase') == 'Running' and int((st.get('events') or {}).get('accepted') or 0) >= $total" || true
}
total=0

# ------------------------------------------------------------------- Kafka, reader, Doris
step "development Kafka with topic $topic and the users qual-producer, qual-ingest"
cat >"$evidence/kafka-values.yaml" <<YAML
topics:
  - {name: dev-events, partitions: 3, retentionMs: 86400000}
  - {name: $topic, partitions: 1, retentionMs: 86400000}
users:
  - {name: dev-producer, write: [dev-events]}
  - {name: dev-consumer, read: [dev-events], groups: [dev-]}
  - {name: qual-producer, write: [$topic]}
  - {name: qual-ingest, read: [$topic], groups: [colvenna.]}
YAML
bash "$REPO_DIR/tests/fixtures/kafka/dev-kafka.sh" install "$evidence/kafka" -f "$evidence/kafka-values.yaml"
for r in "kafkatopic/$topic" kafkauser/qual-producer kafkauser/qual-ingest; do
  kubectl -n "$DEV_KAFKA_NAMESPACE" wait "$r" --for=condition=Ready --timeout=5m >/dev/null
done
for ns in "$JOB_NAMESPACE" "$OPERATOR_NAMESPACE"; do
  kubectl label namespace "$ns" --overwrite "$client_label=true" >/dev/null
done
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret "$DEV_KAFKA_RELEASE-cluster-ca-cert" \
  -o jsonpath='{.data.ca\.crt}' | base64 -d >"$work/kafka-ca.pem"
kubectl -n "$JOB_NAMESPACE" create configmap "$name-kafka-ca" --from-file=ca.pem="$work/kafka-ca.pem" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$DEV_KAFKA_NAMESPACE" get secret qual-ingest -o jsonpath='{.data.password}' | base64 -d \
  >"$work/ingest"
kubectl -n "$JOB_NAMESPACE" create secret generic "$name-kafka" --from-literal=username=qual-ingest \
  --from-file=password="$work/ingest" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
rm -f "$work/ingest"

admin_open
ensure_principal_token "$reader" "$JOB_NAMESPACE" "$reader-token" P1D
kubectl -n "$DORIS_NAMESPACE" get doriscluster "$DORIS_CLUSTER" >/dev/null \
  || { echo "no Doris: run deploy/scripts/doris/install-doris.sh first" >&2; exit 1; }
openssl rand -hex 16 | tr -d '\n' >"$work/analyst"
kubectl -n "$JOB_NAMESPACE" create secret generic "$dashboard-analyst" --from-file=password="$work/analyst" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null

# ---------------------------------------------------------------------- declarations
cat >"$evidence/source.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeSource
metadata: {name: $name, namespace: $JOB_NAMESPACE}
spec:
  type: kafka
  kafka:
    bootstrapServers: $bootstrap
    topics: [$topic]
    credentialsSecret: {name: $name-kafka}
    caConfigMap: $name-kafka-ca
  events:
    fields:
      - {name: event_id, type: STRING, required: true, maxLength: 64}
      - {name: user_id, type: BIGINT}
    eventTime: {field: event_time}
    maxOutOfOrderness: 1m
    idleness: 30s
  target: {database: $db, table: clicks}
  governance: {owner: web-team, level: L2, retention: 7d}
  readers: [$reader]
  job: $small
  deletionPolicy: Delete
YAML
cat >"$evidence/quality.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeQuality
metadata: {name: $name, namespace: $JOB_NAMESPACE}
spec:
  table: {database: $db, table: clicks}
  rules:
    - {name: user-present, notNull: {column: user_id, minRatio: "0.99"}}
    - {name: event-unique, unique: {columns: [event_id]}}
    - {name: volume, rowCount: {min: 50}}
    - {name: fresh, freshness: {column: event_time_ms, maxAge: 2h}}
  gate: {publish: true}
YAML
cat >"$evidence/dashboard.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeDashboard
metadata: {name: $dashboard, namespace: $JOB_NAMESPACE}
spec:
  tables: [$table]
  queries:
    - {name: clicks_live, serve: external, sql: "SELECT dt, COUNT(*) AS n FROM $table GROUP BY dt"}
  users:
    - {name: analyst, passwordSecret: {name: $dashboard-analyst}, queries: [clicks_live]}
YAML
step "declare $name (source and LakeQuality) and $dashboard"
# The namespace the dashboard's reader queries Doris from: a Doris client, nothing else.
kubectl create namespace "$bi" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$bi" --overwrite pod-security.kubernetes.io/enforce=restricted \
  colvenna-lake.io/doris-client=true >/dev/null
c apply -f "$evidence/source.yaml"
await source-running 900 lakesource "$name" \
  "st.get('phase') == 'Running' and cond('TargetReady') == 'True'" || true
c apply -f "$evidence/quality.yaml"
await gate-closed-before-any-check 300 lakequality "$name" \
  "st.get('phase') == 'Ready' and gate.get('open') is False and gate.get('cause') == 'NoCheck' \
   and st.get('store') == '$store'" || true
# The rejections table has no gate: it is published, the events table is withheld.
await source-withheld-before-any-check 300 lakesource "$name" \
  "'withheld: $table' in msg('Published') and 'quality gate' in msg('Published')" || true
await_reader reader-refused-before-any-check 403
c apply -f "$evidence/dashboard.yaml"
await dashboard-refused-before-any-check 300 lakedashboard "$dashboard" \
  "st.get('phase') == 'Failed' and 'quality gate' in (st.get('message') or '')" || true

# --------------------------------------------------------------------- clean partitions
step "yesterday's partition ($yesterday) and today's ($today), clean"
batch d1 "$yesterday" 60
batch d2 "$today" 60
check c1 0 --partition "dt=$yesterday"
results c1 c1 user-present=true event-unique=true volume=true fresh=true
await gate-open-after-c1 120 lakequality "$name" "gate.get('open') is True and gate.get('cause') == 'Passed'" || true
check c2 0 --partition "dt=$today"
results c2 c2 user-present=true event-unique=true volume=true fresh=true
await source-published 120 lakesource "$name" \
  "cond('Published') == 'True' and '$table' in msg('Published') and 'withheld' not in msg('Published')" || true
await_reader reader-served-after-passing-checks 200
await dashboard-ready 300 lakedashboard "$dashboard" "st.get('phase') == 'Ready'" || true
await_dashboard dashboard-served-after-passing-checks served

# ----------------------------------------------------------------------- bad events
step "bad events in today's partition: 5 without a user, 3 repeated ids"
batch bad "$today" 0 --nulls 5 --dups 0
# Repeats of ids sent in d2.
python3 - "$evidence/batches/d2.txt" "$evidence/batches/dups.txt" "$evidence/batches/bad.json" <<'PY'
import json, sys
import datetime
lines = open(sys.argv[1]).read().splitlines()[:3]
out = []
# Now, not their first time: behind the watermark they would be rejected as LATE.
now = datetime.datetime.now(datetime.timezone.utc).isoformat()
for line in lines:
    key, value = line.split("|", 1)
    event = json.loads(value)
    event["user_id"] = 99
    event["event_time"] = now
    out.append(key + "|" + json.dumps(event))
open(sys.argv[2], "w").write("\n".join(out) + "\n")
expected = json.load(open(sys.argv[3]))
expected["dups"] = [json.loads(l.split("|", 1)[1])["event_id"] for l in lines]
json.dump(expected, open(sys.argv[3], "w"))
PY
produce "$evidence/batches/dups.txt"
total=$((total + 3))
await events-dups 600 lakesource "$name" \
  "int((st.get('events') or {}).get('accepted') or 0) >= $total" || true
check c3 3 --partition "dt=$today"
results c3 c3 user-present=false event-unique=false volume=true fresh=true
if python3 -c 'import json,sys; c=json.load(open(sys.argv[1]))["status"]["check"]; sys.exit(0 if c["verdict"] == "Failed" and sorted(c["failed"]) == ["event-unique", "user-present"] else 1)' "$evidence/status/c3.json"; then
  verdict c3-verdict PASS "Failed [event-unique, user-present] in the task's status"
else
  verdict c3-verdict FAIL "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"].get("check"))' "$evidence/status/c3.json")"
fi
await gate-closed-after-c3 120 lakequality "$name" \
  "gate.get('open') is False and gate.get('cause') == 'Failed' and 'c3' in gate.get('message', '') \
   and any(x.get('task') == 'c3' and x.get('tag') for x in st.get('checks', []))" || true
await source-withheld-after-c3 120 lakesource "$name" \
  "'withheld: $table' in msg('Published') and 'c3' in msg('Published')" || true
await_reader reader-refused-after-failed-check 403
await dashboard-refused-after-failed-check 300 lakedashboard "$dashboard" \
  "st.get('phase') == 'Failed' and 'quality gate' in (st.get('message') or '')" || true
await_dashboard dashboard-user-refused-after-failed-check refused
if grep -qx GateClosed <<<"$(kubectl -n "$JOB_NAMESPACE" get events --field-selector "involvedObject.name=$name,type=Warning" \
  -o jsonpath='{range .items[*]}{.reason}{"\n"}{end}')"; then
  verdict gate-closed-warned PASS "Warning GateClosed on LakeQuality $name"
else
  verdict gate-closed-warned FAIL "no Warning GateClosed"
fi

# ------------------------------------------------------------------- locating the rows
step "the failed rules' locate statements, read through the check's tag"
get lakequality "$name" "$evidence/status/quality-after-c3.json"
tag="$(python3 -c 'import json,sys; print(next(x["tag"] for x in json.load(open(sys.argv[1]))["status"]["checks"] if x["task"] == "c3"))' "$evidence/status/quality-after-c3.json")"
bash "$lake" table "$evidence/dumps/clicks-tags.jsonl" "$table" >/dev/null
c3snap="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"]["check"]["snapshot"])' "$evidence/status/c3.json")"
if python3 -c 'import json,sys; t=[json.loads(l) for l in open(sys.argv[1]) if l.startswith("{")][0]; sys.exit(0 if t["tags"].get(sys.argv[2]) == int(sys.argv[3]) else 1)' \
  "$evidence/dumps/clicks-tags.jsonl" "$tag" "$c3snap"; then
  verdict failed-snapshot-tagged PASS "$tag on snapshot $c3snap"
else
  verdict failed-snapshot-tagged FAIL "$tag not on snapshot $c3snap: $(head -c 400 "$evidence/dumps/clicks-tags.jsonl")"
fi
for pair in user-present:nulls event-unique:dups; do
  rule="${pair%%:*}" kind="${pair#*:}"
  python3 - "$evidence/dumps/results-c3.jsonl" "$(uid c3)" "$rule" "$tag" "$c3snap" \
    >"$evidence/locate/$rule.sql" <<'PY'
import json, sys
path, uid, rule, tag, snapshot = sys.argv[1:]
for line in open(path):
    if line.startswith("{"):
        o = json.loads(line)
        r = o.get("row") or {}
        if o.get("type") == "ROW" and r.get("task_uid") == uid and r.get("rule") == rule:
            print(r["locate"].replace("'scan.snapshot-id'='%s'" % snapshot, "'scan.tag-name'='%s'" % tag))
PY
  bash "$lake" query "$evidence/locate/$rule.jsonl" "$evidence/locate/$rule.sql" "$table" >/dev/null || true
  if python3 "$here/quality.py" locate "$evidence/locate/$rule.jsonl" "$evidence/batches/bad.json" "$kind" \
    >"$evidence/locate/$rule.verdict.json"; then
    verdict "locate-$rule" PASS "$(head -c 200 "$evidence/locate/$rule.sql") -> $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["listed"])' "$evidence/locate/$rule.verdict.json")"
  else
    verdict "locate-$rule" FAIL "$(cat "$evidence/locate/$rule.verdict.json")"
  fi
done

step "the same check again: the same snapshot, time and partition"
c3asof="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"]["check"]["asOf"])' "$evidence/status/c3.json")"
check c4 3 --partition "dt=$today" --snapshot "$c3snap" --as-of "$c3asof"
bash "$lake" rows "$evidence/dumps/results-rerun.jsonl" "$store.results" >/dev/null
if python3 "$here/quality.py" same "$evidence/dumps/results-rerun.jsonl" "$(uid c3)" "$(uid c4)" \
  >"$evidence/dumps/rerun.verdict.json"; then
  verdict rerun-same-results PASS "c3 and c4 wrote the same $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["rules"])' "$evidence/dumps/rerun.verdict.json")"
else
  verdict rerun-same-results FAIL "$(cat "$evidence/dumps/rerun.verdict.json")"
fi

# ----------------------------------------------------------------------------- waivers
c4uid="$(uid c4)"
step "the failed checks' tasks deleted: the gate stays closed on their results"
kubectl -n "$JOB_NAMESPACE" delete laketask c3 c4 >/dev/null
sleep 45
await gate-closed-after-tasks-deleted 60 lakequality "$name" \
  "gate.get('open') is False and gate.get('cause') == 'Failed' and gate.get('checkUid') == '$c4uid'" || true
await_reader reader-refused-after-tasks-deleted 403

step "waivers: forged approver, no right, a rule that passed, then colvenna waive"
me="$(kubectl auth whoami -o jsonpath='{.status.userInfo.username}')"
cat >"$work/forged.yaml" <<YAML
apiVersion: colvenna-lake.io/v1alpha1
kind: LakeWaiver
metadata:
  name: forged-$run
  namespace: $JOB_NAMESPACE
  annotations: {colvenna-lake.io/approved-by: someone-else}
spec: {check: c4, rules: [user-present], reason: forged}
YAML
if kubectl create -f "$work/forged.yaml" >"$evidence/status/forged.txt" 2>&1; then
  verdict waiver-forged-approver-refused FAIL "the API server accepted it"
  kubectl -n "$JOB_NAMESPACE" delete lakewaiver "forged-$run" --ignore-not-found >/dev/null
else
  verdict waiver-forged-approver-refused PASS "$(tail -1 "$evidence/status/forged.txt")"
fi
nobody="system:serviceaccount:$JOB_NAMESPACE:qual-nobody"
sed "s/someone-else/$nobody/" "$work/forged.yaml" >"$work/nobody.yaml"
if kubectl --as="$nobody" create -f "$work/nobody.yaml" >"$evidence/status/nobody.txt" 2>&1; then
  verdict waiver-without-right-refused FAIL "a user without RBAC on lakewaivers made one"
  kubectl -n "$JOB_NAMESPACE" delete lakewaiver "forged-$run" --ignore-not-found >/dev/null
else
  verdict waiver-without-right-refused PASS "$(tail -1 "$evidence/status/nobody.txt")"
fi
c waive c4 --name "wide-$run" --rule user-present --rule volume --reason "acceptance: names a rule that passed"
await waiver-of-a-passed-rule-rejected 120 lakewaiver "wide-$run" \
  "st.get('phase') == 'Rejected' and 'volume' in st.get('message', '')" || true
c waive c4 --name "w-$run" --rule user-present --rule event-unique \
  --reason "acceptance: the owner accepts the partition's known gaps"
await waiver-accepted 120 lakewaiver "w-$run" \
  "st.get('phase') == 'Accepted' and st.get('approvedBy') == '$me' and st.get('checkUid') == '$c4uid'" || true
await gate-waived 120 lakequality "$name" \
  "gate.get('open') is True and gate.get('cause') == 'Waived' and gate.get('waivers') == ['w-$run']" || true
await_reader reader-served-after-waiver 200
await dashboard-ready-after-waiver 300 lakedashboard "$dashboard" "st.get('phase') == 'Ready'" || true

# ------------------------------------------------------------------ an interrupted check
step "a check whose run is killed writes nothing and changes nothing"
c check "$table" --name c5 --retries 0 >/dev/null
end=$((SECONDS + 300)) pod5=""
while ((SECONDS < end)); do
  run5="$(kubectl -n "$JOB_NAMESPACE" get laketask c5 -o jsonpath='{.status.run}' 2>/dev/null || true)"
  if [[ -n "$run5" ]]; then
    pod5="$(kubectl -n "$JOB_NAMESPACE" get pods -l "job-name=$run5" \
      --field-selector=status.phase=Running -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)"
    [[ -n "$pod5" ]] && break
  fi
  sleep 1
done
# The JVM is the container's first process and handles SIGTERM: it exits before writing.
kubectl -n "$JOB_NAMESPACE" exec "$pod5" -c task -- sh -c 'kill -TERM 1' || true
await interrupted-check-failed 600 laketask c5 \
  "st.get('phase') == 'Failed' and reason('Finished') == 'RunFailed' and (st.get('check') or {}).get('verdict') is None" || true
bash "$lake" rows "$evidence/dumps/results-c5.jsonl" "$store.results" >/dev/null
if python3 - "$evidence/dumps/results-c5.jsonl" "$(uid c5)" <<'PY'; then
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.startswith("{")]
sys.exit(0 if not any((o.get("row") or {}).get("task_uid") == sys.argv[2] for o in rows) else 1)
PY
  verdict interrupted-check-wrote-nothing PASS "no results of c5 in $store.results"
else
  verdict interrupted-check-wrote-nothing FAIL "c5 has results"
fi
await gate-unchanged-by-interrupted-check 60 lakequality "$name" \
  "gate.get('cause') == 'Waived' and not any(x.get('task') == 'c5' for x in st.get('checks', []))" || true

# ------------------------------------------------------------- rules change, maxAge
step "changed rules: no event-unique rule, user-present at 0.9, and a gate maxAge of 6m"
python3 - "$evidence/quality.yaml" "$evidence/quality-v2.yaml" <<'PY'
import sys
s = open(sys.argv[1]).read()
s = s.replace('    - {name: event-unique, unique: {columns: [event_id]}}\n', '')
s = s.replace('minRatio: "0.99"', 'minRatio: "0.9"')
s = s.replace('gate: {publish: true}', 'gate: {publish: true, maxAge: 6m}')
open(sys.argv[2], "w").write(s)
PY
c apply -f "$evidence/quality-v2.yaml"
await gate-closed-on-new-rules 120 lakequality "$name" \
  "gate.get('open') is False and gate.get('cause') == 'NoCheck'" || true
await waiver-superseded-by-new-rules 120 lakewaiver "w-$run" "st.get('phase') == 'Superseded'" || true
await_reader reader-refused-on-new-rules 403
check c6 0
results c6 c6 user-present=true volume=true fresh=true
await gate-open-under-new-rules 120 lakequality "$name" "gate.get('open') is True and gate.get('cause') == 'Passed'" || true
await_reader reader-served-under-new-rules 200
await gate-expired 600 lakequality "$name" "gate.get('open') is False and gate.get('cause') == 'Expired'" || true
await_reader reader-refused-once-expired 403

# ---------------------------------------------------------------------- the record
step "the store's decisions, and the deletion"
kubectl -n "$JOB_NAMESPACE" delete lakedashboard "$dashboard" --timeout=10m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete lakequality "$name" --timeout=5m >/dev/null || true
bash "$lake" rows "$evidence/dumps/decisions.jsonl" "$store.decisions" >/dev/null
if python3 "$here/quality.py" decisions "$evidence/dumps/decisions.jsonl" "$table" \
  NoCheck Passed Failed Waived NoCheck Passed Expired Removed >"$evidence/dumps/decisions.verdict.json"; then
  verdict decisions-recorded PASS "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["causes"])' "$evidence/dumps/decisions.verdict.json")"
else
  verdict decisions-recorded FAIL "$(head -c 600 "$evidence/dumps/decisions.verdict.json")"
fi
if python3 - "$evidence/dumps/decisions.jsonl" "$table" "w-$run" "$me" <<'PY'; then
import json, sys
path, table, waiver, me = sys.argv[1:]
for line in open(path):
    if line.startswith("{"):
        r = json.loads(line).get("row") or {}
        if r.get("checked_table") == table and r.get("cause") == "Waived":
            w = r.get("waivers") or ""
            sys.exit(0 if waiver in w and "approved by " + me in w and "known gaps" in w else 1)
sys.exit(1)
PY
  verdict waiver-decision-names-approver PASS "the Waived decision names w-$run, approved by $me, and its reason"
else
  verdict waiver-decision-names-approver FAIL "no Waived decision naming w-$run and $me"
fi
bash "$lake" table "$evidence/dumps/clicks-tags-after.jsonl" "$table" >/dev/null
if python3 -c 'import json,sys; t=[json.loads(l) for l in open(sys.argv[1]) if l.startswith("{")][0]; sys.exit(0 if not any(n.startswith("colvenna-check-") for n in t["tags"]) else 1)' \
  "$evidence/dumps/clicks-tags-after.jsonl"; then
  verdict tags-dropped-with-lakequality PASS "no colvenna-check- tag left on $table"
else
  verdict tags-dropped-with-lakequality FAIL "$(head -c 400 "$evidence/dumps/clicks-tags-after.jsonl")"
fi
kubectl -n "$JOB_NAMESPACE" delete lakewaiver "w-$run" "wide-$run" --ignore-not-found >/dev/null
kubectl -n "$JOB_NAMESPACE" delete laketask c1 c2 c5 c6 --ignore-not-found >/dev/null
kubectl -n "$JOB_NAMESPACE" delete lakesource "$name" --timeout=10m >/dev/null || true
kubectl -n "$JOB_NAMESPACE" delete secret "$reader-token" "$dashboard-analyst" "$name-kafka" \
  --ignore-not-found >/dev/null
kubectl -n "$JOB_NAMESPACE" delete configmap "$name-kafka-ca" --ignore-not-found >/dev/null
revoke_principal "$reader" >/dev/null 2>&1 || true

echo
cut -f2,3 "$evidence/verdicts.tsv" | sort | uniq -c | awk '{print $2, $1}' | sort | uniq
awk -F'\t' '$3 != "PASS"' "$evidence/verdicts.tsv"
exit "$failed"

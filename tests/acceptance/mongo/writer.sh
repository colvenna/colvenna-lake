#!/usr/bin/env bash
# Continuous writes to the MongoDB run's MongoDB, from a pod, until stopped:
#   writer.sh start <mongo-name> <database> <pod>   inserts orders, updates and deletes sampled ones,
#                                                   and, after 300 rounds, sets a new field channel
#   writer.sh stop <pod>                            stops it and prints how many rounds it ran
# As root, its password from the Secret through the environment, never an argument.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../common.sh
source "$(dirname "$0")/../common.sh"
assert_account
ensure_kubeconfig
usage="usage: writer.sh start <mongo-name> <database> <pod> | stop <pod>"
command="${1:?$usage}"

case "$command" in
  start)
    mongo="${2:?$usage}" database="${3:?$usage}" pod="${4:?$usage}"
    [[ "$mongo" =~ ^[a-z][a-z0-9-]{0,30}$ && "$database" =~ ^[a-z0-9_]+$ && "$pod" =~ ^[a-z0-9-]+$ ]] \
      || { echo "$usage" >&2; exit 1; }
    work="$(mktemp -d)"
    chmod 700 "$work"
    cat >"$work/writer.js" <<JS
const conn = new Mongo("mongodb://$mongo-0.$mongo.$JOB_NAMESPACE.svc:27017/?tls=true&tlsCAFile=/tls/ca.pem&replicaSet=rs0");
conn.getDB("admin").auth("root", process.env.MONGO_PASSWORD);
const db = conn.getDB("$database");
let n = 0;
while (true) {
  try {
    const order = {customer: Math.floor(Math.random() * 200) + 1, amount: Math.round(Math.random() * 10000) / 100,
                   state: "new", tags: ["writer"], address: {city: "Bergen", zip: n % 50}, round: n};
    db.orders.insertOne(order);
    const [a] = db.orders.aggregate([{\$sample: {size: 1}}]).toArray();
    if (a) {
      const set = {state: "paid", amount: Math.round(Math.random() * 10000) / 100, round: n};
      if (n >= 300) { set.channel = (n % 2) ? "web" : "store"; }
      db.orders.updateOne({_id: a._id}, {\$set: set});
    }
    if (n % 5 === 0) {
      const [b] = db.orders.aggregate([{\$sample: {size: 1}}]).toArray();
      if (b) { db.orders.deleteOne({_id: b._id}); }
    }
    if (n % 7 === 0) {
      db.customers.updateOne({_id: "c-" + (n % 200 + 1)}, {\$set: {tier: "gold", round: n}});
    }
    n++;
  } catch (e) {
    print("error " + e.message);
  }
  print("rounds=" + n);
  sleep(500);
}
JS
    kubectl -n "$JOB_NAMESPACE" create configmap "$pod" --from-file=writer.js="$work/writer.js" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    rm -rf "$work"
    kubectl -n "$JOB_NAMESPACE" delete pod "$pod" --ignore-not-found --wait=true >/dev/null
    kubectl -n "$JOB_NAMESPACE" apply -f - >/dev/null <<YAML
apiVersion: v1
kind: Pod
metadata: {name: $pod, labels: {colvenna-lake.io/role: writer}}
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  terminationGracePeriodSeconds: 5
  securityContext: {runAsNonRoot: true, runAsUser: 999, seccompProfile: {type: RuntimeDefault}}
  containers:
    - name: writer
      image: $MONGO_IMAGE
      securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
      env:
        - name: MONGO_PASSWORD
          valueFrom: {secretKeyRef: {name: $mongo-root, key: password}}
      command: ["mongosh", "--quiet", "--nodb", "/writer/writer.js"]
      resources: {requests: {cpu: 100m, memory: 256Mi}, limits: {memory: 256Mi}}
      volumeMounts:
        - {name: writer, mountPath: /writer, readOnly: true}
        - {name: ca, mountPath: /tls, readOnly: true}
  volumes:
    - {name: writer, configMap: {name: $pod}}
    - {name: ca, configMap: {name: $mongo-ca}}
YAML
    kubectl -n "$JOB_NAMESPACE" wait --for=condition=Ready "pod/$pod" --timeout=180s >/dev/null
    ;;
  stop)
    pod="${2:?$usage}"
    rounds="$(kubectl -n "$JOB_NAMESPACE" logs "$pod" --tail=1 2>/dev/null || true)"
    kubectl -n "$JOB_NAMESPACE" delete pod "$pod" --ignore-not-found --wait=true >/dev/null
    kubectl -n "$JOB_NAMESPACE" delete configmap "$pod" --ignore-not-found >/dev/null
    echo "${rounds:-rounds=0}"
    ;;
  *)
    echo "$usage" >&2
    exit 1
    ;;
esac

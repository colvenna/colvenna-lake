#!/usr/bin/env bash
# A development MongoDB for the MongoDB run, standing in for the user's: MongoDB 7.0, one member of
# replica set rs0, TLS required (a CA made here), access control on (a key file and users), data on
# an EBS volume so the member can be deleted and come back. For development and testing only.
#   install <name> <evidence>  StatefulSet <name>, its Service and NetworkPolicy, the replica set
#                              and the users root (admin) and colvenna_cdc (the source's: find and
#                              changeStream on every database, read on local for the oplog window);
#                              ConfigMap <name>-ca (ca.pem) and Secrets <name>-root, <name>-cdc
#                              (username, password)
#   shell <name>               runs stdin as a mongosh script file as root, in the member's pod;
#                              prints what it prints and nothing else (passwords travel in the
#                              script, never in arguments)
#   purge <name>               deletes it all, the volume included
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../../acceptance/common.sh
source "$(dirname "$0")/../../acceptance/common.sh"
assert_account
ensure_kubeconfig
usage="usage: dev-mongo.sh install <name> <evidence> | shell <name> | purge <name>"
command="${1:?$usage}"
name="${2:?$usage}"
[[ "$name" =~ ^[a-z][a-z0-9-]{0,30}$ ]] || { echo "$usage" >&2; exit 1; }
host="$name-0.$name.$JOB_NAMESPACE.svc"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT

# mongosh_root: stdin as a script, as root over TLS to the member.
mongosh_root() {
  kubectl -n "$JOB_NAMESPACE" get secret "$name-root" -o jsonpath='{.data.password}' | base64 -d >"$work/root"
  {
    printf 'db.getSiblingDB("admin").auth("root", %s);\n' "$(python3 -c 'import json,sys; print(json.dumps(open(sys.argv[1]).read()))' "$work/root")"
    cat
  } | kubectl -n "$JOB_NAMESPACE" exec -i "$name-0" -c mongo -- sh -c \
    "f=\$(mktemp) && cat >\"\$f\" && mongosh --quiet --tls --tlsCAFile /tls/ca.pem --host $host --norc --file \"\$f\"; s=\$?; rm -f \"\$f\"; exit \$s"
  rm -f "$work/root"
}

case "$command" in
  install)
    evidence="${3:?$usage}"
    mkdir -p "$evidence"
    ensure_storage_class
    # The CA and the member's certificate, for the names clients use.
    openssl req -x509 -newkey rsa:2048 -nodes -days 7 -subj "/CN=$name-ca" \
      -keyout "$work/ca.key" -out "$work/ca.pem" 2>/dev/null
    openssl req -newkey rsa:2048 -nodes -subj "/CN=$host" -keyout "$work/server.key" \
      -out "$work/server.csr" 2>/dev/null
    printf 'subjectAltName=DNS:%s,DNS:%s.%s.svc,DNS:localhost\nextendedKeyUsage=serverAuth,clientAuth\n' \
      "$host" "$name" "$JOB_NAMESPACE" >"$work/ext"
    openssl x509 -req -in "$work/server.csr" -CA "$work/ca.pem" -CAkey "$work/ca.key" \
      -CAcreateserial -days 7 -extfile "$work/ext" -out "$work/server.crt" 2>/dev/null
    cat "$work/server.crt" "$work/server.key" >"$work/server.pem"
    openssl rand -base64 756 | tr -d '\n' >"$work/keyfile"
    kubectl -n "$JOB_NAMESPACE" create secret generic "$name-tls" --from-file=server.pem="$work/server.pem" \
      --from-file=ca.pem="$work/ca.pem" --from-file=keyfile="$work/keyfile" --dry-run=client -o yaml \
      | kubectl apply -f - >/dev/null
    kubectl -n "$JOB_NAMESPACE" create configmap "$name-ca" --from-file=ca.pem="$work/ca.pem" \
      --dry-run=client -o yaml | kubectl apply -f - >/dev/null
    for user in root cdc; do
      if ! kubectl -n "$JOB_NAMESPACE" get secret "$name-$user" >/dev/null 2>&1; then
        openssl rand -hex 24 | tr -d '\n' >"$work/password"
        kubectl -n "$JOB_NAMESPACE" create secret generic "$name-$user" \
          --from-literal=username="$([[ $user == root ]] && echo root || echo colvenna_cdc)" \
          --from-file=password="$work/password" >/dev/null
        rm -f "$work/password"
      fi
    done
    rm -f "$work"/*.key "$work/server.pem" "$work/keyfile"
    cat >"$evidence/$name.yaml" <<YAML
apiVersion: v1
kind: Service
metadata: {name: $name, namespace: $JOB_NAMESPACE}
spec:
  clusterIP: None
  # The member finds itself in the replica set's configuration by this name as it starts, before
  # it is ready: without it a member that comes back stays REMOVED.
  publishNotReadyAddresses: true
  selector: {app: $name}
  ports: [{name: mongo, port: 27017}]
---
# Only the job namespace's pods and colvenna-operator reach it.
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: $name, namespace: $JOB_NAMESPACE}
spec:
  podSelector: {matchLabels: {app: $name}}
  policyTypes: [Ingress]
  ingress:
    - from:
        - podSelector: {}
        - namespaceSelector: {matchLabels: {kubernetes.io/metadata.name: $OPERATOR_NAMESPACE}}
      ports: [{port: 27017, protocol: TCP}]
---
apiVersion: apps/v1
kind: StatefulSet
metadata: {name: $name, namespace: $JOB_NAMESPACE}
spec:
  serviceName: $name
  replicas: 1
  selector: {matchLabels: {app: $name}}
  template:
    metadata: {labels: {app: $name}}
    spec:
      automountServiceAccountToken: false
      securityContext: {runAsNonRoot: true, runAsUser: 999, runAsGroup: 999, fsGroup: 999, seccompProfile: {type: RuntimeDefault}}
      initContainers:
        # mongod resolves its own name once, as it starts.
        - name: dns
          image: $MONGO_IMAGE
          securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
          command: ["sh", "-c", "for i in \$(seq 1 90); do getent hosts $host && exit 0; sleep 2; done; exit 1"]
        # mongod refuses a key file others can read; the Secret's copy belongs to root.
        - name: keyfile
          image: $MONGO_IMAGE
          securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
          command: ["sh", "-ec", "cp /secret/keyfile /run-mongo/keyfile && chmod 400 /run-mongo/keyfile"]
          volumeMounts:
            - {name: tls, mountPath: /secret, readOnly: true}
            - {name: run, mountPath: /run-mongo}
      containers:
        - name: mongo
          image: $MONGO_IMAGE
          securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
          args: ["--replSet", "rs0", "--bind_ip_all", "--auth", "--keyFile", "/run-mongo/keyfile",
                 "--tlsMode", "requireTLS", "--tlsCertificateKeyFile", "/tls/server.pem",
                 "--tlsCAFile", "/tls/ca.pem", "--tlsAllowConnectionsWithoutCertificates"]
          ports: [{containerPort: 27017}]
          resources: {requests: {cpu: 250m, memory: 1Gi}, limits: {memory: 1Gi}}
          volumeMounts:
            - {name: data, mountPath: /data/db}
            - {name: tls, mountPath: /tls, readOnly: true}
            - {name: run, mountPath: /run-mongo}
      volumes:
        - {name: tls, secret: {secretName: $name-tls}}
        - {name: run, emptyDir: {}}
  volumeClaimTemplates:
    - metadata: {name: data}
      spec:
        accessModes: [ReadWriteOnce]
        storageClassName: colvenna-gp3
        resources: {requests: {storage: 5Gi}}
YAML
    kubectl apply -f "$evidence/$name.yaml" >/dev/null
    kubectl -n "$JOB_NAMESPACE" rollout status "statefulset/$name" --timeout=10m >/dev/null
    # The replica set, then the first user through the localhost exception, then the source's.
    kubectl -n "$JOB_NAMESPACE" exec -i "$name-0" -c mongo -- \
      mongosh --quiet --tls --tlsCAFile /tls/ca.pem --host localhost --norc >/dev/null <<JS
try { rs.status(); } catch (e) { rs.initiate({_id: "rs0", members: [{_id: 0, host: "$host:27017"}]}); }
JS
    for _ in $(seq 1 30); do
      # A here-string: grep -q on a pipe under pipefail can read the writer's SIGPIPE as no match.
      grep -qx true <<<"$(kubectl -n "$JOB_NAMESPACE" exec "$name-0" -c mongo -- mongosh --quiet --tls \
        --tlsCAFile /tls/ca.pem --host localhost --norc --eval 'db.hello().isWritablePrimary' \
        2>/dev/null)" && break
      sleep 5
    done
    kubectl -n "$JOB_NAMESPACE" get secret "$name-root" -o jsonpath='{.data.password}' | base64 -d >"$work/root"
    kubectl -n "$JOB_NAMESPACE" get secret "$name-cdc" -o jsonpath='{.data.password}' | base64 -d >"$work/cdc"
    python3 - "$work/root" "$work/cdc" >"$work/users.js" <<'PY'
import json, sys
root, cdc = (json.dumps(open(p).read()) for p in sys.argv[1:])
print(f"""
const admin = db.getSiblingDB("admin");
// The localhost exception lets the first user be created, and nothing else: not even a listing.
try {{
  admin.createUser({{user: "root", pwd: {root}, roles: ["root"]}});
}} catch (e) {{
  // There is a user already: the exception is closed.
}}
admin.auth("root", {root});
if (!admin.getRole("colvenna_cdc")) {{
  admin.createRole({{role: "colvenna_cdc", roles: [],
    privileges: [{{resource: {{db: "", collection: ""}},
                  actions: ["find", "changeStream", "listCollections", "listDatabases",
                            "splitVector", "collStats", "listIndexes"]}}]}});
}}
if (!admin.getUser("colvenna_cdc")) {{
  admin.createUser({{user: "colvenna_cdc", pwd: {cdc},
    roles: [{{role: "colvenna_cdc", db: "admin"}}, {{role: "read", db: "local"}}]}});
}}
print("users ready");
""")
PY
    rm -f "$work/root" "$work/cdc"
    # As a file: on stdin, mongosh runs a script line by line as its prompt would. The pod's shell
    # expands the variables.
    # shellcheck disable=SC2016
    kubectl -n "$JOB_NAMESPACE" exec -i "$name-0" -c mongo -- sh -c \
      'f=$(mktemp) && cat >"$f" && mongosh --quiet --tls --tlsCAFile /tls/ca.pem --host localhost --norc --file "$f"; s=$?; rm -f "$f"; exit $s' \
      <"$work/users.js" | tee "$evidence/$name-users.txt"
    grep -qx "users ready" "$evidence/$name-users.txt" || { echo "the users were not created" >&2; exit 1; }
    rm -f "$work/users.js"
    ;;
  shell)
    mongosh_root
    ;;
  purge)
    kubectl -n "$JOB_NAMESPACE" delete statefulset,service,networkpolicy "$name" --ignore-not-found --wait=true >/dev/null
    kubectl -n "$JOB_NAMESPACE" delete pvc "data-$name-0" --ignore-not-found --wait=true >/dev/null
    kubectl -n "$JOB_NAMESPACE" delete secret "$name-tls" "$name-root" "$name-cdc" --ignore-not-found >/dev/null
    kubectl -n "$JOB_NAMESPACE" delete configmap "$name-ca" --ignore-not-found >/dev/null
    echo "purged $name"
    ;;
  *)
    echo "$usage" >&2
    exit 1
    ;;
esac

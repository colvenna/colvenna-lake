#!/usr/bin/env bash
# Stands in for a user's existing Trino in the Trino acceptance: Trino 484, one coordinator that
# also runs the work, with the catalogs given (properties files; they hold the credentials, so they
# go into a Secret). It is not part of the platform's install (HLD 10.2).
#  - Nothing reaches it over the network: no Service, and a NetworkPolicy that admits nothing.
#    It is queried through `kubectl exec` (the cluster's IAM authentication).
#  - Its pod has no AWS identity (no IRSA): table data is reachable only with the credentials the
#    catalog vends.
#  - It trusts colvenna-catalog's CA, which signs Polaris's certificate, besides the JDK's.
# usage: install-trino.sh <evidence-dir> <catalogs-dir>
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../../acceptance/common.sh
source "$(dirname "$0")/../../acceptance/common.sh"
# shellcheck source=../../acceptance/trino/trino.sh
source "$ACCEPTANCE_DIR/trino/trino.sh"
assert_account
ensure_kubeconfig
evidence="${1:?usage: install-trino.sh <evidence-dir> <catalogs-dir>}"
catalogs="${2:?usage: install-trino.sh <evidence-dir> <catalogs-dir>}"
mkdir -p "$evidence"
sha="$(committed_sha)"
work="$(mktemp -d)"
chmod 700 "$work"
trap 'rm -rf "$work"' EXIT

kubectl create namespace "$TRINO_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$TRINO_NAMESPACE" --overwrite \
  pod-security.kubernetes.io/enforce=restricted "$POLARIS_CLIENT_LABEL=true" >/dev/null
kubectl -n "$CATALOG_NAMESPACE" get secret colvenna-catalog-ca-key -o jsonpath='{.data.ca\.crt}' \
  | base64 -d >"$work/ca.crt"
kubectl -n "$TRINO_NAMESPACE" create configmap colvenna-ca --from-file=ca.crt="$work/ca.crt" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl -n "$TRINO_NAMESPACE" create secret generic trino-catalogs --from-file="$catalogs" \
  --dry-run=client -o yaml | kubectl apply -f - >/dev/null
ls "$catalogs" >"$evidence/catalogs.txt"
cat >"$work/trino.yaml" <<YAML
apiVersion: v1
kind: ConfigMap
metadata: {name: trino-config, namespace: $TRINO_NAMESPACE}
data:
  config.properties: |
    coordinator=true
    node-scheduler.include-coordinator=true
    http-server.http.port=8080
    discovery.uri=http://localhost:8080
    query.max-memory=1GB
    query.max-memory-per-node=1GB
  node.properties: |
    node.environment=acceptance
    node.data-dir=/data/trino
  jvm.config: |
    -server
    -Xmx2G
    -XX:+UseG1GC
    -XX:+ExitOnOutOfMemoryError
    -Djdk.attach.allowAttachSelf=true
    -Djavax.net.ssl.trustStore=/trust/cacerts.p12
    -Djavax.net.ssl.trustStorePassword=changeit
    -Djavax.net.ssl.trustStoreType=PKCS12
  log.properties: |
    io.trino=INFO
---
apiVersion: apps/v1
kind: Deployment
metadata: {name: trino, namespace: $TRINO_NAMESPACE, labels: {app: trino}}
spec:
  replicas: 1
  selector: {matchLabels: {app: trino}}
  template:
    metadata:
      labels: {app: trino}
      annotations:
        colvenna-lake.io/source-sha: "$sha"
        colvenna-lake.io/catalogs: "$(sha256sum "$catalogs"/* | sha256sum | cut -c1-16)"
    spec:
      automountServiceAccountToken: false
      securityContext: {runAsNonRoot: true, runAsUser: 1000, runAsGroup: 1000, fsGroup: 1000, seccompProfile: {type: RuntimeDefault}}
      initContainers:
        # The JDK's CAs and colvenna-catalog's, for Polaris's certificate.
        - name: trust
          image: $TRINO_IMAGE
          securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
          command: ["sh", "-ec"]
          args:
            - |
              cp "\$JAVA_HOME/lib/security/cacerts" /trust/cacerts.p12
              chmod 600 /trust/cacerts.p12
              "\$JAVA_HOME/bin/keytool" -importcert -noprompt -alias colvenna-catalog -file /ca/ca.crt -keystore /trust/cacerts.p12 -storepass changeit
          volumeMounts:
            - {name: trust, mountPath: /trust}
            - {name: ca, mountPath: /ca, readOnly: true}
      containers:
        - name: trino
          image: $TRINO_IMAGE
          securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: ["ALL"]}}
          ports: [{name: http, containerPort: 8080}]
          readinessProbe:
            exec: {command: ["sh", "-c", "trino --execute 'SELECT 1' >/dev/null"]}
            periodSeconds: 15
            timeoutSeconds: 10
            failureThreshold: 20
          resources: {requests: {cpu: 500m, memory: 3Gi}, limits: {memory: 3Gi}}
          volumeMounts:
            - {name: config, mountPath: /etc/trino/config.properties, subPath: config.properties, readOnly: true}
            - {name: config, mountPath: /etc/trino/node.properties, subPath: node.properties, readOnly: true}
            - {name: config, mountPath: /etc/trino/jvm.config, subPath: jvm.config, readOnly: true}
            - {name: config, mountPath: /etc/trino/log.properties, subPath: log.properties, readOnly: true}
            - {name: catalogs, mountPath: /etc/trino/catalog, readOnly: true}
            - {name: trust, mountPath: /trust, readOnly: true}
            - {name: data, mountPath: /data/trino}
            - {name: tmp, mountPath: /tmp}
      volumes:
        - {name: config, configMap: {name: trino-config}}
        - {name: catalogs, secret: {secretName: trino-catalogs}}
        - {name: ca, configMap: {name: colvenna-ca}}
        - {name: trust, emptyDir: {}}
        - {name: data, emptyDir: {}}
        - {name: tmp, emptyDir: {}}
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata: {name: trino, namespace: $TRINO_NAMESPACE}
spec:
  podSelector: {matchLabels: {app: trino}}
  policyTypes: [Ingress]
  ingress: []
YAML
cp "$work/trino.yaml" "$evidence/trino.yaml"
if grep -Eq 'kind: (Service|Ingress|Gateway|HTTPRoute)' "$work/trino.yaml"; then
  echo "The manifests expose Trino" >&2
  exit 1
fi
kubectl apply -f "$work/trino.yaml" >/dev/null
kubectl -n "$TRINO_NAMESPACE" rollout restart deploy/trino >/dev/null
kubectl -n "$TRINO_NAMESPACE" rollout status deploy/trino --timeout=10m
kubectl -n "$TRINO_NAMESPACE" get pods,networkpolicies -o wide >"$evidence/objects.txt"

#!/usr/bin/env bash
# Installs the Strimzi 1.2.0 operator that dev-kafka.sh's development Kafka runs on, watching only
# its namespace (colvenna-lake-kafka, Pod Security baseline). The chart is checked against its pinned
# SHA-256, and its rendered manifests must be private (no public Service or Ingress). The operator,
# and the entity operator it starts, run from the environment's own copy of Strimzi's operator
# image, by digest (deploy/images/third-party.tsv); the brokers' image is the Kafka resource's
# (dev-kafka.sh), and a development Kafka an earlier commit installed is moved to this commit's copy
# here, so that after the install nothing runs from upstream. Rerunning upgrades in place.
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../../acceptance/common.sh
source "$(dirname "$0")/../../acceptance/common.sh"
assert_account
ensure_kubeconfig
charts="$TOOLS_DIR/charts"
chart="$charts/strimzi-kafka-operator-helm-3-chart-1.2.0.tgz"
mkdir -p "$charts"
if [[ ! -f "$chart" ]]; then
  curl -fsSL -o "$chart" \
    https://github.com/strimzi/strimzi-kafka-operator/releases/download/1.2.0/strimzi-kafka-operator-helm-3-chart-1.2.0.tgz
fi
printf '%s  %s\n' 0f8a50b2f19bd99482f9fd6e17cf42902f72f9e594a136813ac3f0b7af422efd "$chart" \
  | sha256sum --check >/dev/null

# The chart joins <registry>/<repository>/<name>@<digest> for each image it names.
images=()
for key in image topicOperator.image userOperator.image kafkaInit.image; do
  images+=(--set "$key.registry=$ECR_REGISTRY" --set "$key.repository=$(env_name colvenna-lake)"
    --set "$key.name=${THIRD_PARTY_REPOSITORY##*/}" --set "$key.digest=$(third_party_digest strimzi-operator)")
done
helm template colvenna-strimzi "$chart" --namespace "$DEV_KAFKA_NAMESPACE" \
  --values "$(dirname "$0")/strimzi-values.yaml" "${images[@]}" --include-crds \
  | node "$REPO_DIR/deploy/scripts/lib/assert-private-manifests.cjs"
kubectl create namespace "$DEV_KAFKA_NAMESPACE" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
kubectl label namespace "$DEV_KAFKA_NAMESPACE" --overwrite \
  pod-security.kubernetes.io/enforce=baseline >/dev/null
helm_upgrade colvenna-strimzi "$chart" --namespace "$DEV_KAFKA_NAMESPACE" \
  --values "$(dirname "$0")/strimzi-values.yaml" "${images[@]}" --wait --timeout 10m >/dev/null
for crd in kafkas.kafka.strimzi.io kafkanodepools.kafka.strimzi.io kafkatopics.kafka.strimzi.io \
  kafkausers.kafka.strimzi.io; do
  kubectl wait "crd/$crd" --for=condition=Established --timeout=5m >/dev/null
done
kubectl -n "$DEV_KAFKA_NAMESPACE" rollout status deployment/strimzi-cluster-operator --timeout=5m
# The development Kafka the acceptance flows leave (dev-kafka.sh install), on this commit's copy of
# its image: its values kept, the broker rolled once when the image changes.
if helm status "$DEV_KAFKA_RELEASE" --namespace "$DEV_KAFKA_NAMESPACE" >/dev/null 2>&1; then
  helm_upgrade "$DEV_KAFKA_RELEASE" "$REPO_DIR/tests/fixtures/kafka/colvenna-dev-kafka" --namespace "$DEV_KAFKA_NAMESPACE" \
    --reuse-values --set image="$KAFKA_IMAGE" >/dev/null
  # Its broker rolled onto the image, and the Kafka resource ready as its operator last reconciled
  # this very spec (observedGeneration is the generation the upgrade made): a Ready left from before
  # the change does not count. The operator reconciles after the upgrade returns.
  started=$SECONDS
  kafka_state() {
    brokers="$(kubectl -n "$DEV_KAFKA_NAMESPACE" get pods -l "strimzi.io/cluster=$DEV_KAFKA_RELEASE" -o \
      jsonpath='{range .items[*]}{range .spec.containers[?(@.name=="kafka")]}{.image}{"\n"}{end}{end}' | sort -u)"
    IFS='|' read -r generation observed ready < <(kubectl -n "$DEV_KAFKA_NAMESPACE" get "kafka/$DEV_KAFKA_RELEASE" \
      -o jsonpath='{.metadata.generation}|{.status.observedGeneration}|{.status.conditions[?(@.type=="Ready")].status}{"\n"}')
    [[ "$brokers" == "$KAFKA_IMAGE" && -n "$generation" && "$observed" == "$generation" && "$ready" == True ]]
  }
  until kafka_state; do
    if ((SECONDS - started >= 1200)); then
      echo "The development Kafka is not on $KAFKA_IMAGE and ready after $((SECONDS - started)) s: its broker runs" \
        "${brokers:-nothing}; generation ${generation:-none}, observed ${observed:-none}, Ready ${ready:-none}" >&2
      exit 1
    fi
    sleep 10
  done
  echo "development Kafka $DEV_KAFKA_RELEASE on $KAFKA_IMAGE"
fi

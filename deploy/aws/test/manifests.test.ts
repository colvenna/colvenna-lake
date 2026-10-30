import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { resolve } from 'node:path';
import test from 'node:test';

const guard = resolve(__dirname, '../../scripts/lib/assert-private-manifests.cjs');

function check(manifest: string) {
  // The gate's yaml dependency is this package's.
  return spawnSync(process.execPath, [guard], {
    input: manifest, encoding: 'utf8',
    env: { ...process.env, NODE_PATH: resolve(__dirname, '../node_modules') },
  });
}

test('private Kubernetes services and workloads pass the manifest gate', () => {
  const result = check(`apiVersion: v1
kind: List
items:
  - apiVersion: v1
    kind: Service
    metadata: {name: internal}
    spec: {type: ClusterIP, ports: [{port: 8080}]}
  - apiVersion: apps/v1
    kind: Deployment
    metadata: {name: worker}
    spec:
      template:
        spec:
          containers:
            - name: worker
              ports: [{containerPort: 8080}]
`);
  assert.equal(result.status, 0, result.stderr);
});

test('public Service paths, routes, and host ports fail closed', () => {
  const exposed = [
    'kind: Service\nmetadata: {name: external}\nspec: {type: ClusterIP, externalIPs: [203.0.113.1]}',
    'kind: Service\nmetadata: {name: node}\nspec: {type: NodePort}',
    'kind: Service\nmetadata: {name: lb}\nspec: {type: LoadBalancer}',
    'kind: Gateway\nmetadata: {name: web}',
    `kind: CronJob
metadata: {name: worker}
spec:
  jobTemplate:
    spec:
      template:
        spec:
          containers:
            - name: worker
              ports: [{containerPort: 8080, hostPort: 8080}]`,
    `kind: FlinkDeployment
metadata: {name: flink}
spec:
  flinkConfiguration:
    kubernetes.rest-service.exposed.type: NodePort`,
    `kind: FlinkDeployment
metadata: {name: flink}
spec:
  flinkConfiguration:
    kubernetes.rest-service.exposed.type: ClusterIP
  ingress: {template: "flink.example.com"}`,
    `kind: FlinkDeployment
metadata: {name: flink}
spec:
  flinkConfiguration:
    kubernetes.rest-service.exposed.type: ClusterIP
  podTemplate:
    spec:
      containers:
        - name: flink-main-container
          env:
            - {name: COLVENNA_KAFKA_PASSWORD, value: naked-secret}`,
  ];
  for (const manifest of exposed) {
    const result = check(manifest);
    assert.notEqual(result.status, 0, `Unexpectedly accepted:\n${manifest}`);
  }
});

test('Doris cluster requires a Secret and private FE and BE services', () => {
  const privateDoris = `apiVersion: doris.selectdb.com/v1
kind: DorisCluster
metadata: {name: colvenna-doris}
spec:
  authSecret: colvenna-doris-auth
  feSpec:
    service: {type: ClusterIP}
    configMapInfo: {configMapName: colvenna-doris-fe}
    serviceAccount: doris
  beSpec:
    service: {type: ClusterIP}
    skipDefaultSystemInit: true
    serviceAccount: doris
`;
  assert.equal(check(privateDoris).status, 0);
  for (const exposed of [
    privateDoris.replace('authSecret: colvenna-doris-auth', ''),
    privateDoris.replace('configMapInfo: {configMapName: colvenna-doris-fe}', ''),
    privateDoris.replace('feSpec:\n    service: {type: ClusterIP}',
      'feSpec:\n    service: {type: LoadBalancer}'),
    privateDoris.replace('beSpec:\n    service: {type: ClusterIP}',
      'beSpec:\n    service: {type: NodePort}'),
    privateDoris.replace('    skipDefaultSystemInit: true\n', ''),
    privateDoris.replace('    skipDefaultSystemInit: true',
      '    skipDefaultSystemInit: true\n    systemInitialization: {initImage: alpine}'),
    privateDoris.replace('authSecret: colvenna-doris-auth',
      'authSecret: colvenna-doris-auth\n  adminUser: {name: root, password: exposed}'),
  ]) {
    assert.notEqual(check(exposed).status, 0, `Unexpectedly accepted:\n${exposed}`);
  }
});

test('Strimzi Kafka requires private TLS, SCRAM authentication, and topic grants', () => {
  const privateKafka = `apiVersion: kafka.strimzi.io/v1
kind: Kafka
metadata: {name: colvenna-events}
spec:
  kafka:
    authorization: {type: simple}
    config: {auto.create.topics.enable: false}
    listeners:
      - {name: tls, port: 9093, type: internal, tls: true, authentication: {type: scram-sha-512}}
`;
  assert.equal(check(privateKafka).status, 0);
  for (const replacement of [
    'type: loadbalancer',
    'tls: false',
    'type: none',
  ]) {
    const source = replacement === 'type: none'
      ? 'type: scram-sha-512' : replacement.startsWith('tls:') ? 'tls: true' : 'type: internal';
    assert.notEqual(check(privateKafka.replace(source, replacement)).status, 0);
  }
  assert.notEqual(check(privateKafka.replace('authorization: {type: simple}', '')).status, 0);
  assert.notEqual(check(privateKafka.replace('auto.create.topics.enable: false', '')).status, 0);
  assert.equal(check(`apiVersion: kafka.strimzi.io/v1
kind: KafkaUser
metadata: {name: colvenna-producer}
spec:
  authentication: {type: scram-sha-512}
  authorization:
    type: simple
    acls:
      - resource: {type: topic, name: colvenna-events, patternType: literal}
        operations: [Write, Describe]
`).status, 0);
  assert.notEqual(check(`apiVersion: kafka.strimzi.io/v1
kind: KafkaUser
metadata: {name: colvenna-producer}
spec:
  authentication: {type: scram-sha-512}
  authorization:
    type: simple
    acls:
      - resource: {type: topic, name: '*', patternType: literal}
        operations: [Write]
`).status, 0);
  assert.notEqual(check(`apiVersion: kafka.strimzi.io/v1
kind: KafkaUser
metadata: {name: colvenna-producer}
spec:
  authentication: {type: tls}
`).status, 0);
});

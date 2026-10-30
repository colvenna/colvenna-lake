const fs = require('node:fs');
const YAML = require('yaml');

function podSpecs(manifest) {
  switch (manifest.kind) {
    case 'Pod':
      return [manifest.spec];
    case 'Deployment':
    case 'StatefulSet':
    case 'DaemonSet':
    case 'ReplicaSet':
    case 'Job':
      return [manifest.spec?.template?.spec];
    case 'CronJob':
      return [manifest.spec?.jobTemplate?.spec?.template?.spec];
    case 'FlinkDeployment':
      return [
        manifest.spec?.podTemplate?.spec,
        manifest.spec?.jobManager?.podTemplate?.spec,
        manifest.spec?.taskManager?.podTemplate?.spec,
      ];
    default:
      return [];
  }
}

function assertPrivate(manifest) {
  const name = manifest.metadata?.name || '<unnamed>';
  if (manifest.kind === 'List') {
    for (const item of manifest.items || []) assertPrivate(item);
    return;
  }
  if (manifest.kind === 'Service') {
    const spec = manifest.spec || {};
    const type = spec.type || 'ClusterIP';
    if (type !== 'ClusterIP' || (spec.externalIPs || []).length > 0
      || spec.loadBalancerIP || (spec.ports || []).some((port) => port.nodePort)) {
      throw new Error(`Refusing externally reachable Service ${name}`);
    }
  }
  if ([
    'Ingress', 'IngressRoute', 'Route', 'Gateway', 'HTTPRoute', 'GRPCRoute',
    'TCPRoute', 'UDPRoute', 'TLSRoute', 'VirtualService',
  ].includes(manifest.kind)) {
    throw new Error(`Refusing unreviewed public route ${manifest.kind}/${name}`);
  }
  if (manifest.kind === 'FlinkDeployment'
    && (manifest.spec?.ingress
      || manifest.spec?.flinkConfiguration?.['kubernetes.rest-service.exposed.type'] !== 'ClusterIP')) {
    throw new Error(`Refusing unreviewed Flink REST exposure ${name}`);
  }
  if (manifest.kind === 'DorisCluster') {
    const spec = manifest.spec || {};
    const services = [spec.feSpec?.service, spec.beSpec?.service];
    if (!spec.authSecret || spec.adminUser?.password
      || !spec.feSpec?.configMapInfo?.configMapName
      || !spec.feSpec?.serviceAccount || !spec.beSpec?.serviceAccount
      || spec.beSpec?.skipDefaultSystemInit !== true
      || spec.beSpec?.systemInitialization
      || services.some((service) => !service || service.type !== 'ClusterIP'
        || service.loadBalancerIP || (service.externalIPs || []).length > 0
        || (service.servicePorts || []).some((port) => port.nodePort))) {
      throw new Error(`Refusing unauthenticated or externally reachable Doris cluster ${name}`);
    }
  }
  if (manifest.apiVersion?.startsWith('kafka.strimzi.io/')
    && manifest.kind === 'Kafka') {
    const listeners = manifest.spec?.kafka?.listeners;
    if (!Array.isArray(listeners) || listeners.length === 0
      || listeners.some((listener) => listener.type !== 'internal'
        || listener.tls !== true
        || listener.authentication?.type !== 'scram-sha-512')
      || manifest.spec?.kafka?.authorization?.type !== 'simple'
      || manifest.spec?.kafka?.config?.['auto.create.topics.enable'] !== false) {
      throw new Error(`Refusing unauthenticated or public Kafka listener ${name}`);
    }
  }
  if (manifest.apiVersion?.startsWith('kafka.strimzi.io/')
    && manifest.kind === 'KafkaUser') {
    const acls = manifest.spec?.authorization?.acls;
    if (manifest.spec?.authentication?.type !== 'scram-sha-512'
      || manifest.spec?.authorization?.type !== 'simple'
      || !Array.isArray(acls) || acls.length === 0
      || acls.some((acl) => !['topic', 'group'].includes(acl.resource?.type)
        || !/^colvenna-[a-z0-9-]+$/.test(acl.resource?.name || '')
        || acl.resource?.patternType !== 'literal'
        || !Array.isArray(acl.operations) || acl.operations.length === 0
        || acl.operations.some((operation) => !['Read', 'Write', 'Describe']
          .includes(operation)))) {
      throw new Error(`Refusing Kafka user without authenticated topic grants ${name}`);
    }
  }
  if (podSpecs(manifest).some((spec) => spec?.hostNetwork
    || (spec?.containers || []).concat(spec?.initContainers || [])
      .some((container) => (container.ports || []).some((port) => port.hostPort)))) {
    throw new Error(`Refusing host network or host port ${manifest.kind}/${name}`);
  }
  if (podSpecs(manifest).some((spec) =>
    (spec?.containers || []).concat(spec?.initContainers || [])
      .some((container) => (container.env || []).some((entry) =>
        /PASSWORD|TOKEN|SECRET|PRIVATE_KEY|ACCESS_KEY/i.test(entry.name || '')
        && entry.value !== undefined)))) {
    throw new Error(`Refusing inline credential environment variable ${manifest.kind}/${name}`);
  }
}

const manifests = YAML.parseAllDocuments(fs.readFileSync(0, 'utf8'));
for (const document of manifests) {
  if (document.errors.length > 0) {
    throw new Error(`Invalid Helm manifest: ${document.errors[0]}`);
  }
  const manifest = document.toJS();
  if (!manifest) continue;
  assertPrivate(manifest);
}

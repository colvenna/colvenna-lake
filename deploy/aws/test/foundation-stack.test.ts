import assert from 'node:assert/strict';
import test from 'node:test';
import { App, assertions } from 'aws-cdk-lib';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { FoundationStack, dorisTag } from '../src/foundation-stack';

const app = new App();
const stack = new FoundationStack(app, 'FoundationTest', {
  env: { account: '111122223333', region: 'us-west-2' },
  environment: 'uat',
  adminPrincipalArn: 'arn:aws:iam::111122223333:user/work',
  operatorCidr: '203.0.113.7/32',
  availabilityZones: ['us-west-2a', 'us-west-2b'],
  sourceSha: 'a'.repeat(40),
});
const template = assertions.Template.fromStack(stack).toJSON();
const resources = template.Resources as Record<
  string,
  { Type: string; Properties: Record<string, any>; DeletionPolicy?: string }
>;

function only(type: string) {
  const matches = Object.values(resources).filter((resource) => resource.Type === type);
  assert.equal(matches.length, 1, `expected one ${type}`);
  return matches[0];
}

/** The one resource of that type whose logical id starts so. */
function named(type: string, prefix: string) {
  const matches = Object.entries(resources).filter(([id, r]) => r.Type === type && id.startsWith(prefix));
  assert.equal(matches.length, 1, `expected one ${type} ${prefix}`);
  return matches[0][1];
}

function manifests() {
  return Object.values(resources)
    .filter((resource) => resource.Type === 'Custom::AWSCDK-EKS-KubernetesResource')
    .flatMap((resource) => {
      const encoded = resource.Properties.Manifest;
      return typeof encoded === 'string' ? JSON.parse(encoded) : [];
    });
}

test('the warehouse\'s access logs and the VPC\'s flow logs go to a private audit log bucket', () => {
  const audit = named('AWS::S3::Bucket', 'AuditLogs');
  assert.equal(audit.DeletionPolicy, 'Retain');
  assert.equal(audit.Properties.BucketEncryption.ServerSideEncryptionConfiguration[0]
    .ServerSideEncryptionByDefault.SSEAlgorithm, 'AES256');
  assert.equal(audit.Properties.PublicAccessBlockConfiguration.RestrictPublicBuckets, true);
  assert.equal(audit.Properties.OwnershipControls.Rules[0].ObjectOwnership, 'BucketOwnerEnforced');
  assert.equal(audit.Properties.LifecycleConfiguration.Rules[0].ExpirationInDays, 365);

  const warehouse = named('AWS::S3::Bucket', 'Warehouse');
  const auditId = Object.keys(resources).find((id) => id.startsWith('AuditLogs')
    && resources[id].Type === 'AWS::S3::Bucket');
  assert.deepEqual(warehouse.Properties.LoggingConfiguration,
    { DestinationBucketName: { Ref: auditId }, LogFilePrefix: 'warehouse-access/' });
  const statements = named('AWS::S3::BucketPolicy', 'AuditLogsPolicy').Properties.PolicyDocument
    .Statement as Record<string, any>[];
  const delivery = statements.find((st) => st.Principal?.Service === 'logging.s3.amazonaws.com');
  assert.ok(delivery, 'the S3 logging service may write the access logs');
  assert.equal(delivery!.Action, 's3:PutObject');
  assert.match(JSON.stringify(delivery!.Resource), /warehouse-access\/\*/);
  const warehouseId = Object.keys(resources).find((id) => id.startsWith('Warehouse')
    && resources[id].Type === 'AWS::S3::Bucket');
  assert.deepEqual(delivery!.Condition.ArnLike['aws:SourceArn'], { 'Fn::GetAtt': [warehouseId, 'Arn'] },
    'only for the warehouse');
  assert.ok(delivery!.Condition.StringEquals['aws:SourceAccount'], 'only from this account');
  const flowDelivery = statements.filter((st) => st.Principal?.Service === 'delivery.logs.amazonaws.com');
  assert.deepEqual(flowDelivery.map((st) => st.Action).sort(),
    [['s3:GetBucketAcl', 's3:ListBucket'], 's3:PutObject'].sort(), 'the flow logs\' delivery, in the policy itself');
  for (const st of flowDelivery) {
    assert.ok(st.Condition.StringEquals['aws:SourceAccount'] && st.Condition.ArnLike['aws:SourceArn']);
  }
  const put = flowDelivery.find((st) => st.Action === 's3:PutObject')!;
  assert.equal(put.Condition.StringEquals['s3:x-amz-acl'], 'bucket-owner-full-control');
  assert.match(JSON.stringify(put.Resource), /\/vpc-flow\/AWSLogs\/111122223333\/\*/);
  assert.ok(statements.some((st) => st.Effect === 'Deny' && st.Condition?.Bool?.['aws:SecureTransport'] === 'false'));

  const flowLog = only('AWS::EC2::FlowLog');
  assert.equal(flowLog.Properties.TrafficType, 'ALL');
  assert.equal(flowLog.Properties.LogDestinationType, 's3');
  assert.match(JSON.stringify(flowLog.Properties.LogDestination), /AuditLogs.*vpc-flow/);
});

test('the warehouse and catalog are private and recoverable after stack removal', () => {
  const bucket = named('AWS::S3::Bucket', 'Warehouse');
  assert.equal(bucket.DeletionPolicy, 'Retain');
  assert.equal(bucket.Properties.VersioningConfiguration, undefined);
  assert.equal(bucket.Properties.BucketEncryption.ServerSideEncryptionConfiguration[0]
    .ServerSideEncryptionByDefault.SSEAlgorithm, 'AES256');
  assert.deepEqual(bucket.Properties.PublicAccessBlockConfiguration, {
    BlockPublicAcls: true,
    BlockPublicPolicy: true,
    IgnorePublicAcls: true,
    RestrictPublicBuckets: true,
  });
  const bucketPolicy = named('AWS::S3::BucketPolicy', 'WarehousePolicy');
  assert.ok(bucketPolicy.Properties.PolicyDocument.Statement.some((statement: Record<string, any>) =>
    statement.Effect === 'Deny' && statement.Condition?.Bool?.['aws:SecureTransport'] === 'false'));

  const database = only('AWS::RDS::DBInstance');
  assert.equal(database.DeletionPolicy, 'Snapshot');
  assert.equal(database.Properties.PubliclyAccessible, false);
  assert.equal(database.Properties.StorageEncrypted, true);
  assert.equal(database.Properties.Engine, 'postgres');

  const repositories = Object.values(resources).filter((r) => r.Type === 'AWS::ECR::Repository');
  assert.deepEqual(repositories.map((r) => r.Properties.RepositoryName).sort(),
    ['colvenna-lake-uat/flink-base', 'colvenna-lake-uat/query-engine', 'colvenna-lake-uat/third-party']);
  for (const repository of repositories) {
    assert.equal(repository.Properties.ImageTagMutability, 'IMMUTABLE');
  }
  // The query engine's pinned images never expire; its unpinned rebuilds, tagged build-..., are kept
  // to the last two (four images, FE and BE). The pinned tags' rules name common.sh's DORIS_TAG.
  const rules = (name: string) => JSON.parse(repositories.find(
    (r) => r.Properties.RepositoryName === name)!.Properties.LifecyclePolicy.LifecyclePolicyText).rules;
  const engine = rules('colvenna-lake-uat/query-engine').sort((a: any, b: any) => a.rulePriority - b.rulePriority);
  const common = readFileSync(join(__dirname, '../../scripts/lib/common.sh'), 'utf8');
  const pinned = (key: string) => common.match(new RegExp(`^${key}=(\\S+)$`, 'm'))![1];
  assert.equal(dorisTag(), pinned('DORIS_TAG'));
  const kept = (pattern: string) => ({
    tagStatus: 'tagged', tagPatternList: [pattern], countType: 'imageCountMoreThan', countNumber: 1000,
  });
  assert.deepEqual(engine.map((r: any) => r.selection), [
    kept(`${pinned('DORIS_TAG')}-fe-*`), kept(`${pinned('DORIS_TAG')}-be-*`),
    { tagStatus: 'tagged', tagPrefixList: ['build-'], countType: 'imageCountMoreThan', countNumber: 4 },
  ]);
  // As ECR evaluates them: a rule selects an image when each of its filters matches one of the
  // image's tags, and the first rule that selects it is the only one that may expire it. A pinned
  // digest, tagged as common.sh's doris_tag tags it, is its kept rule's even when it is a rebuild that
  // keeps its build- tag too (#278); a rebuild alone is the build rule's; any other tag, one that
  // holds -fe- too, no rule's.
  const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const matches = (pattern: string, tag: string) =>
    new RegExp(`^${pattern.split('*').map(escape).join('.*')}$`).test(tag);
  const selects = (rule: any, tags: string[]) => (rule.selection.tagPrefixList
    ? rule.selection.tagPrefixList.every((prefix: string) => tags.some((tag) => tag.startsWith(prefix)))
    : rule.selection.tagPatternList.every((pattern: string) => tags.some((tag) => matches(pattern, tag))));
  const firstRule = (...tags: string[]) => engine.findIndex((rule: any) => selects(rule, tags));
  const components = ['fe', 'be'];
  for (const component of components) {
    const digest = pinned(`DORIS_${component.toUpperCase()}_DIGEST`);
    const tag = `${pinned('DORIS_TAG')}-${component}-${digest.slice(7, 19)}`;
    const build = `build-${'b'.repeat(40)}-${component}`;
    assert.equal(firstRule(tag), components.indexOf(component), tag);
    assert.equal(firstRule(build, tag), components.indexOf(component), `${build} ${tag}`);
    assert.equal(firstRule(build), 2, build);
    assert.equal(firstRule(`9.9.9-other-${component}-${digest.slice(7, 19)}`), -1);
  }
  assert.deepEqual(rules('colvenna-lake-uat/flink-base').map((r: any) => r.selection.countNumber), [5]);
  // Every pinned third-party digest stays: an environment runs its copies, never upstream.
  const thirdParty = repositories.find((r) => r.Properties.RepositoryName === 'colvenna-lake-uat/third-party')!;
  assert.equal(thirdParty.Properties.LifecyclePolicy, undefined);
  assert.deepEqual(thirdParty.Properties.ImageScanningConfiguration, { ScanOnPush: true });
});

test('the query engine\'s images are distributed under a name of their own, not Doris\'s', () => {
  // A modified build based on Apache Doris (release/trademarks.md): neither its repository nor the
  // output deploy/scripts/doris/copy-images.sh reads names it Doris.
  const engine = Object.entries(resources).filter(([, r]) => r.Type === 'AWS::ECR::Repository'
    && r.Properties.RepositoryName === 'colvenna-lake-uat/query-engine');
  assert.equal(engine.length, 1);
  assert.match(engine[0][0], /^QueryEngineImageRepository/);
  assert.deepEqual(template.Outputs.QueryEngineImageRepositoryUri.Value['Fn::Join'][1].slice(-2),
    ['/', { Ref: engine[0][0] }]);
  for (const [id, r] of Object.entries(resources)) {
    if (r.Type === 'AWS::ECR::Repository') {
      assert.doesNotMatch(`${id} ${r.Properties.RepositoryName}`, /doris/i);
    }
  }
  assert.deepEqual(Object.keys(template.Outputs).filter((key) => /doris/i.test(key)), []);
});

test('the catalog accepts PostgreSQL traffic only from the EKS node security group', () => {
  const ingress = Object.values(resources).filter((resource) =>
    resource.Type === 'AWS::EC2::SecurityGroupIngress'
    && JSON.stringify(resource.Properties.GroupId).includes('CatalogDatabaseSecurityGroup'));
  assert.equal(ingress.length, 1);
  assert.equal(ingress[0].Properties.CidrIp, undefined);
  assert.equal(ingress[0].Properties.CidrIpv6, undefined);
  assert.ok(JSON.stringify(ingress[0].Properties.SourceSecurityGroupId)
    .includes('ClusterSecurityGroupId'));
});

test('EKS runs Kubernetes 1.36 and grants only the given admin principal', () => {
  const cluster = only('Custom::AWSCDK-EKS-Cluster');
  assert.equal(cluster.Properties.Config.version, '1.36');
  const bootstrapAddress = Object.entries(resources).find(([id]) => id.startsWith('ApiBootstrapAddress'));
  assert.ok(bootstrapAddress);
  assert.equal(bootstrapAddress[1].Type, 'AWS::EC2::EIP');
  assert.equal(bootstrapAddress[1].Properties.Domain, 'vpc');
  const bootstrapCidr = template.Outputs.BootstrapApiCidr.Value;
  assert.ok(JSON.stringify(bootstrapCidr).includes(bootstrapAddress[0]));
  assert.deepEqual(cluster.Properties.Config.resourcesVpcConfig.publicAccessCidrs, [bootstrapCidr]);
  const natGateway = only('AWS::EC2::NatGateway');
  assert.equal(JSON.stringify(natGateway.Properties.AllocationId).includes(bootstrapAddress[0]), false);
  assert.equal(cluster.Properties.Config.tags.Project, 'colvenna-lake');
  assert.equal(cluster.Properties.Config.tags.SourceSha, 'a'.repeat(40));
  const nodegroups = Object.values(resources).filter((resource) => resource.Type === 'AWS::EKS::Nodegroup');
  assert.equal(nodegroups.length, 2);
  for (const nodegroup of nodegroups) {
    assert.equal(nodegroup.Properties.AmiType, 'AL2023_x86_64_STANDARD');
    // deploy.sh upgrades the node groups: EKS refuses a version update beside the SourceSha tag's.
    assert.equal(nodegroup.Properties.Version, undefined);
  }
  assert.equal(template.Outputs.SourceSha.Value, 'a'.repeat(40));
  assert.equal(cluster.Properties.Config.accessConfig.authenticationMode, 'API_AND_CONFIG_MAP');
  const admin = only('AWS::EKS::AccessEntry');
  assert.equal(admin.Properties.PrincipalArn, 'arn:aws:iam::111122223333:user/work');
  assert.equal(admin.Properties.AccessPolicies[0].AccessScope.Type, 'cluster');
});

test('the cluster keeps its version at the end of standard support, and its add-ons follow it', () => {
  const cluster = only('Custom::AWSCDK-EKS-Cluster');
  assert.deepEqual(cluster.Properties.Config.upgradePolicy, { supportType: 'EXTENDED' });
  assert.equal(template.Outputs.UpgradeSupportType.Value, 'EXTENDED');
  assert.equal(template.Outputs.KubernetesVersion.Value, '1.36');
  const addons = Object.fromEntries(Object.values(resources)
    .filter((resource) => resource.Type === 'AWS::EKS::Addon')
    .map((resource) => [resource.Properties.AddonName, resource.Properties.AddonVersion]));
  assert.deepEqual(addons, {
    'aws-ebs-csi-driver': 'v1.66.0-eksbuild.1',
    'vpc-cni': 'v1.22.4-eksbuild.3',
    'kube-proxy': 'v1.36.0-eksbuild.48',
    coredns: 'v1.14.7-eksbuild.11',
  });
  assert.ok(addons['kube-proxy'].startsWith(`v${template.Outputs.KubernetesVersion.Value}.`));
  const nodegroupIds = Object.entries(resources)
    .filter(([, resource]) => resource.Type === 'AWS::EKS::Nodegroup').map(([id]) => id).sort();
  for (const name of ['kube-proxy', 'coredns']) {
    const addon = Object.values(resources).find((resource) =>
      resource.Type === 'AWS::EKS::Addon' && resource.Properties.AddonName === name) as { DependsOn?: string[] };
    assert.deepEqual(nodegroupIds.filter((id) => addon.DependsOn?.includes(id)), nodegroupIds);
  }
});

test('the cluster enforces NetworkPolicy', () => {
  const addon = Object.values(resources).find((resource) =>
    resource.Type === 'AWS::EKS::Addon' && resource.Properties.AddonName === 'vpc-cni');
  assert.ok(addon);
  assert.deepEqual(JSON.parse(addon.Properties.ConfigurationValues), { enableNetworkPolicy: 'true' });
  assert.equal(addon.Properties.ResolveConflicts, 'OVERWRITE');
});

test('the control-plane and catalog database logs are kept a year', () => {
  const [clusterId] = Object.entries(resources).find(([, resource]) =>
    resource.Type === 'Custom::AWSCDK-EKS-Cluster')!;
  const [databaseId] = Object.entries(resources).find(([, resource]) =>
    resource.Type === 'AWS::RDS::DBInstance')!;
  const retentions = Object.values(resources).filter((resource) => resource.Type === 'Custom::LogRetention');
  assert.deepEqual(retentions.map((resource) => resource.Properties.RetentionInDays), [365, 365]);
  assert.deepEqual(retentions.map((resource) => resource.Properties.LogGroupName), [
    { 'Fn::Join': ['', ['/aws/eks/', { Ref: clusterId }, '/cluster']] },
    { 'Fn::Join': ['', ['/aws/rds/instance/', { Ref: databaseId }, '/postgresql']] },
  ]);
});

test('Kubernetes public info endpoints do not grant anonymous access', () => {
  const patch = only('Custom::AWSCDK-EKS-KubernetesPatch');
  assert.equal(patch.DeletionPolicy, 'Retain');
  assert.equal(patch.Properties.ResourceName, 'clusterrolebinding/system:public-info-viewer');
  assert.equal(patch.Properties.PatchType, 'merge');
  const binding = JSON.parse(patch.Properties.ApplyPatchJson);
  assert.equal(binding.metadata.annotations['rbac.authorization.kubernetes.io/autoupdate'], 'false');
  assert.equal(binding.metadata.annotations['colvenna-lake/source-sha'], 'a'.repeat(40));
  assert.equal(binding.metadata.annotations['colvenna-lake/operator-cidr'], '203.0.113.7/32');
  assert.deepEqual(binding.subjects, [{
    kind: 'Group', apiGroup: 'rbac.authorization.k8s.io', name: 'system:authenticated',
  }]);
  assert.equal(patch.Properties.RestorePatchJson, patch.Properties.ApplyPatchJson);
});

test('the cluster and node groups follow foundation-spec.json, as the installer preflight reads it', () => {
  // deploy/scripts/lib/preflight.sh checks the region and zones offer these before anything is made.
  const spec = JSON.parse(readFileSync(join(__dirname, '../src/foundation-spec.json'), 'utf8'));
  assert.deepEqual(Object.keys(spec).sort(), ['kubernetesVersion', 'queryInstanceType', 'writeInstanceType']);
  assert.equal(only('Custom::AWSCDK-EKS-Cluster').Properties.Config.version, spec.kubernetesVersion);
  const types = Object.values(resources).filter((r) => r.Type === 'AWS::EKS::Nodegroup')
    .map((r) => `${r.Properties.Labels['colvenna-lake/role']}=${r.Properties.InstanceTypes.join(',')}`).sort();
  assert.deepEqual(types, [`query=${spec.queryInstanceType}`, `write=${spec.writeInstanceType}`]);
  // Two Elastic IPs a new environment allocates (the preflight's quota check): the NAT gateway's
  // and the unassociated bootstrap address; one VPC.
  assert.equal(Object.values(resources).filter((r) => r.Type === 'AWS::EC2::EIP').length, 2);
  assert.equal(Object.values(resources).filter((r) => r.Type === 'AWS::EC2::VPC').length, 1);
});

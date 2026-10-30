import assert from 'node:assert/strict';
import test from 'node:test';
import { App, assertions, Duration, Stack } from 'aws-cdk-lib';
import { CatalogStack } from '../src/catalog-stack';
import { EntryStack } from '../src/entry-stack';
import { envResource, envStack } from '../src/env';
import { FoundationStack } from '../src/foundation-stack';
import { OpenLayerStack } from '../src/open-layer-stack';
import { JOB_ROLE_PREFIX, MAX_ROLE_NAME, OperatorStack } from '../src/operator-stack';
import { RELEASE_STACK, ReleaseStack } from '../src/release-stack';
import { TestMysqlStack, TestMysqlStackProps } from '../src/test-mysql-stack';
import { TestPostgresStack } from '../src/test-postgres-stack';

// Every name the stacks give AWS fits the service's limit, for the longest environment name
// deploy/env.sh accepts (8 characters) in a region with one of the longest names: the prefix
// colvenna-lake-<env>, four characters longer than the product's former one (#271), leaves the
// rest of a name that much less room.
const environment = 'uate2xyz';
const region = 'ap-southeast-5';
const env = { account: '111122223333', region };
const common = { env, environment, sourceSha: 'a'.repeat(40) };
const zones: [string, string] = [`${region}a`, `${region}b`];
// What CloudFormation names a bucket that has no name: <stack>-<logical id>-<12 random>, lower case.
const generatedBucket = (stack: string, logicalId: string) =>
  `${stack}-${logicalId}-abcdefghijkl`.toLowerCase();
const bucketName = generatedBucket(envStack(environment, 'Foundation'), 'Warehouse0123ABCD');
// The stacks on the foundation's warehouse and cluster.
const onWarehouse = {
  ...common,
  bucketName,
  oidcIssuer: `https://oidc.eks.${region}.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF`,
};
const sources: TestMysqlStackProps = {
  ...common,
  vpcId: 'vpc-12345678',
  eksSecurityGroupId: 'sg-12345678',
  isolatedSubnetIds: ['subnet-11111111', 'subnet-22222222'],
  isolatedSubnetAzs: zones,
  isolatedRouteTableIds: ['rtb-11111111', 'rtb-22222222'],
};
// The longest managed namespace the operator stack takes for this environment.
const longestNamespace = 'a'.repeat(MAX_ROLE_NAME - envResource(environment, JOB_ROLE_PREFIX).length);
// build: the stack; named: whether it names resources itself (the test sources leave their names to
// CloudFormation and CDK).
const stacks: Record<string, { build: (app: App) => Stack; named: boolean }> = {
  Foundation: {
    named: true,
    build: (app) => new FoundationStack(app, envStack(environment, 'Foundation'), {
      ...common,
      adminPrincipalArn: 'arn:aws:iam::111122223333:role/deployer',
      operatorCidr: '203.0.113.7/32',
      availabilityZones: zones,
    }),
  },
  Catalog: { named: true, build: (app) => new CatalogStack(app, envStack(environment, 'Catalog'), onWarehouse) },
  Operator: {
    named: true,
    build: (app) => new OperatorStack(app, envStack(environment, 'Operator'), {
      ...onWarehouse, managedNamespaces: ['colvenna-data', longestNamespace],
    }),
  },
  OpenLayer: { named: true, build: (app) => new OpenLayerStack(app, envStack(environment, 'OpenLayer'), onWarehouse) },
  Entry: {
    named: true,
    build: (app) => new EntryStack(app, envStack(environment, 'Entry'), {
      ...common,
      oidcIssuer: onWarehouse.oidcIssuer,
      vpcId: 'vpc-0123456789abcdef0',
      publicSubnetIds: ['subnet-0123456789abcdef0', 'subnet-0123456789abcdef1'],
      publicSubnetCidrs: ['10.84.0.0/24', '10.84.1.0/24'],
      availabilityZones: zones,
      clusterSecurityGroupId: 'sg-0123456789abcdef0',
      hostedZoneId: 'Z0EXAMPLE1234567890',
      hostedZoneName: 'example.com',
      domain: `colvenna-${environment}.example.com`,
      sessionTimeout: Duration.hours(8),
    }),
  },
  TestMysql: { named: false, build: (app) => new TestMysqlStack(app, envStack(environment, 'TestMysql'), sources) },
  TestPostgres: {
    named: false,
    build: (app) => new TestPostgresStack(app, envStack(environment, 'TestPostgres'), {
      ...sources,
      catalogSubnetGroupName: `${envStack(environment, 'Foundation').toLowerCase()}-catalogdatabasesubnetgroup-example`,
    }),
  },
  Release: { named: true, build: (app) => new ReleaseStack(app, RELEASE_STACK, { env, sourceSha: common.sourceSha }) },
};

// type -> property -> [the service's limit, the characters it allows (when it says)].
const ECR_NAME = /^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:\/[a-z0-9]+(?:[._-][a-z0-9]+)*)*$/;
const LIMITS: Record<string, Record<string, [number, RegExp?]>> = {
  'AWS::IAM::Role': { RoleName: [64, /^[\w+=,.@-]+$/] },
  'AWS::IAM::ManagedPolicy': { ManagedPolicyName: [128] },
  'AWS::IAM::Policy': { PolicyName: [128] },
  'AWS::ECR::Repository': { RepositoryName: [256, ECR_NAME] },
  'AWS::SecretsManager::Secret': { Name: [512] },
  'AWS::Logs::LogGroup': { LogGroupName: [512, /^[.\-_/#A-Za-z0-9]+$/] },
  'AWS::Cognito::UserPool': { UserPoolName: [128] },
  'AWS::Cognito::UserPoolClient': { ClientName: [128] },
  'AWS::Cognito::UserPoolDomain': { Domain: [63, /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/] },
  'AWS::Cognito::UserPoolGroup': { GroupName: [128] },
  'AWS::S3::Bucket': { BucketName: [63, /^[a-z0-9][a-z0-9.-]*[a-z0-9]$/] },
  'AWS::ElasticLoadBalancingV2::LoadBalancer': { Name: [32] },
  'AWS::ElasticLoadBalancingV2::TargetGroup': { Name: [32] },
  'AWS::Lambda::Function': { FunctionName: [64] },
  'AWS::KMS::Alias': { AliasName: [256] },
  'AWS::SSM::Parameter': { Name: [2048] },
  'AWS::EC2::SecurityGroup': { GroupName: [255] },
  'AWS::RDS::DBInstance': { DBInstanceIdentifier: [63] },
  'AWS::EKS::Nodegroup': { NodegroupName: [63] },
};

type Resource = { Type: string; Properties?: Record<string, unknown> };

for (const [component, { build, named }] of Object.entries(stacks)) {
  test(`${component}: every name it gives AWS fits, for the longest environment name`, () => {
    const stack = build(new App());
    assert.ok(stack.stackName.length <= 128, stack.stackName);
    const resources = assertions.Template.fromStack(stack).toJSON().Resources as Record<string, Resource>;
    const names: string[] = [];
    for (const [logicalId, resource] of Object.entries(resources)) {
      for (const [property, [limit, allowed]] of Object.entries(LIMITS[resource.Type] ?? {})) {
        const value = resource.Properties?.[property];
        if (typeof value !== 'string') {
          continue; // none given (CloudFormation makes one up to the limit) or not known until deployed
        }
        names.push(value);
        assert.ok(value.length <= limit, `${resource.Type} ${property} ${value}: ${value.length} > ${limit}`);
        assert.ok(allowed === undefined || allowed.test(value), `${resource.Type} ${property} ${value}`);
      }
      // A bucket without a name gets the stack's, which the stacks after the foundation check its
      // warehouse bucket's name by: it must not be cut short.
      if (resource.Type === 'AWS::S3::Bucket' && resource.Properties?.BucketName === undefined) {
        const generated = generatedBucket(stack.stackName, logicalId);
        assert.ok(generated.length <= 63, `${generated}: ${generated.length} > 63`);
      }
    }
    assert.ok(!named || names.length > 0, 'names something');
  });
}

test('the warehouse bucket of the longest environment name is whole, as the later stacks expect', () => {
  assert.equal(bucketName, 'colvennalakeuate2xyzfoundation-warehouse0123abcd-abcdefghijkl');
  assert.ok(bucketName.length <= 63);
  // The operator stack's job roles leave a namespace name the rest of the 64.
  assert.equal(longestNamespace.length, 36);
  assert.throws(() => new OperatorStack(new App(), 'TooLong', {
    ...onWarehouse, managedNamespaces: [`${longestNamespace}a`],
  }), /at most 36 characters/);
});

import assert from 'node:assert/strict';
import test from 'node:test';
import { App, Duration, Stack } from 'aws-cdk-lib';
import { Annotations, Match } from 'aws-cdk-lib/assertions';
import { CatalogStack } from '../src/catalog-stack';
import { EntryStack } from '../src/entry-stack';
import { FoundationStack } from '../src/foundation-stack';
import { nagChecks } from '../src/nag';
import { OpenLayerStack } from '../src/open-layer-stack';
import { OperatorStack } from '../src/operator-stack';
import { RELEASE_STACK, ReleaseStack } from '../src/release-stack';
import { TestMysqlStack, TestMysqlStackProps } from '../src/test-mysql-stack';
import { TestPostgresStack } from '../src/test-postgres-stack';

// Every stack passes cdk-nag's AWS Solutions rules as app.ts applies them: each finding is fixed,
// or acknowledged with its reason beside its resource. A new finding fails here before a deploy.
const env = { account: '111122223333', region: 'us-west-2' };
const common = { env, environment: 'uat', sourceSha: 'a'.repeat(40) };
const bucketName = 'colvennalakeuatfoundation-warehouse0123abcd-xyz';
const oidcIssuer = 'https://oidc.eks.us-west-2.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF';
const sources: TestMysqlStackProps = {
  ...common,
  vpcId: 'vpc-12345678',
  eksSecurityGroupId: 'sg-12345678',
  isolatedSubnetIds: ['subnet-11111111', 'subnet-22222222'],
  isolatedSubnetAzs: ['us-west-2a', 'us-west-2b'],
  isolatedRouteTableIds: ['rtb-11111111', 'rtb-22222222'],
};
const stacks: Record<string, (app: App) => Stack> = {
  foundation: (app) => new FoundationStack(app, 'Foundation', {
    ...common,
    adminPrincipalArn: 'arn:aws:iam::111122223333:user/work',
    operatorCidr: '203.0.113.7/32',
    availabilityZones: ['us-west-2a', 'us-west-2b'],
  }),
  catalog: (app) => new CatalogStack(app, 'Catalog', { ...common, bucketName, oidcIssuer }),
  operator: (app) => new OperatorStack(app, 'Operator', {
    ...common, bucketName, oidcIssuer, managedNamespaces: ['colvenna-data', 'colvenna-second'],
  }),
  'open-layer': (app) => new OpenLayerStack(app, 'OpenLayer', { ...common, bucketName, oidcIssuer }),
  entry: (app) => new EntryStack(app, 'Entry', {
    ...common,
    oidcIssuer,
    vpcId: 'vpc-0123456789abcdef0',
    publicSubnetIds: ['subnet-0123456789abcdef0', 'subnet-0123456789abcdef1'],
    publicSubnetCidrs: ['10.84.0.0/24', '10.84.1.0/24'],
    availabilityZones: ['us-west-2a', 'us-west-2b'],
    clusterSecurityGroupId: 'sg-0123456789abcdef0',
    hostedZoneId: 'Z0EXAMPLE1234567890',
    hostedZoneName: 'example.com',
    domain: 'colvenna-uat.example.com',
    sessionTimeout: Duration.hours(8),
  }),
  'test-mysql': (app) => new TestMysqlStack(app, 'TestMysql', sources),
  'test-postgres': (app) => new TestPostgresStack(app, 'TestPostgres', {
    ...sources,
    catalogSubnetGroupName: 'colvennalakeuatfoundation-catalogdatabasesubnetgroup-example',
  }),
  release: (app) => new ReleaseStack(app, RELEASE_STACK, { env, sourceSha: common.sourceSha }),
  'release-shared': (app) => new ReleaseStack(app, RELEASE_STACK, {
    env, sourceSha: common.sourceSha, pullAccounts: ['222222222222'],
  }),
};

for (const [name, build] of Object.entries(stacks)) {
  test(`${name}: no cdk-nag finding is left unacknowledged`, () => {
    const app = new App();
    const stack = build(app);
    nagChecks(app);
    const annotations = Annotations.fromStack(stack);
    const found = [
      ...annotations.findError('*', Match.stringLikeRegexp('AwsSolutions-')),
      ...annotations.findWarning('*', Match.stringLikeRegexp('AwsSolutions-')),
    ].map((m) => `${m.id}: ${String(m.entry.data).slice(0, 160)}`);
    assert.deepEqual(found, []);
  });
}

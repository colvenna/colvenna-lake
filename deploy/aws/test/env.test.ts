import assert from 'node:assert/strict';
import test from 'node:test';
import { App, assertions } from 'aws-cdk-lib';
import { CatalogStack } from '../src/catalog-stack';
import {
  accountFromContext, envFromContext, envResource, envStack, isOidcIssuer, isZoneOf, stackRegion, standardRegion,
  synthesisAccount, UNRESOLVED_ACCOUNT,
} from '../src/env';
import { FoundationStack } from '../src/foundation-stack';

type Resource = { Type: string; Properties: Record<string, any> };
const resources = (stack: FoundationStack | CatalogStack) => Object.values(
  assertions.Template.fromStack(stack).toJSON().Resources as Record<string, Resource>,
);

test('every environment is named, and its names are its own', () => {
  assert.equal(envFromContext('uat'), 'uat');
  for (const bad of [undefined, '', 'UAT', '1uat', 'uat-1', 'abcdefghi', 3]) {
    assert.throws(() => envFromContext(bad), /-c env/, String(bad));
  }
  // The development PoC (tag history/m1-m5) named its stacks and resources after m<n>.
  for (const reserved of ['m', 'm3', 'poc']) {
    assert.throws(() => envFromContext(reserved), /reserved/, reserved);
  }
  // The release registry's repositories are colvenna-lake-release/...
  assert.throws(() => envFromContext('release'), /release is reserved/);
  assert.equal(envStack('uat', 'Foundation'), 'ColvennaLakeUatFoundation');
  assert.equal(envStack('prod', 'OpenLayer'), 'ColvennaLakeProdOpenLayer');
  assert.equal(envResource('uat', 'colvenna-lake/flink-base'), 'colvenna-lake-uat/flink-base');
  assert.equal(envResource('uat', '/colvenna-lake/console-audit'), '/colvenna-lake-uat/console-audit');
});

const foundation = (extra: object) => new FoundationStack(new App(), 'FoundationEnv', {
  env: { account: '111122223333', region: 'us-west-2' },
  environment: 'uat',
  adminPrincipalArn: 'arn:aws:iam::111122223333:role/deployer',
  operatorCidr: '203.0.113.7/32',
  availabilityZones: ['us-west-2a', 'us-west-2b'],
  sourceSha: 'a'.repeat(40),
  ...extra,
});

test('the foundation names its image repositories and tags its resources by environment', () => {
  const repositories = (environment: string) => resources(foundation({ environment }))
    .filter((r) => r.Type === 'AWS::ECR::Repository');
  for (const environment of ['uat', 'prod']) {
    const found = repositories(environment);
    assert.deepEqual(found.map((r) => r.Properties.RepositoryName).sort(),
      [`colvenna-lake-${environment}/flink-base`, `colvenna-lake-${environment}/query-engine`,
        `colvenna-lake-${environment}/third-party`]);
    for (const repository of found) {
      assert.ok(repository.Properties.Tags.some(
        (t: any) => t.Key === 'Environment' && t.Value === environment));
    }
  }
});

test('the node groups are one node each unless sized', () => {
  const sizes = (extra: object) => resources(foundation(extra))
    .filter((r) => r.Type === 'AWS::EKS::Nodegroup')
    .map((r) => `${r.Properties.Labels['colvenna-lake/role']}=${r.Properties.ScalingConfig.DesiredSize}`).sort();
  assert.deepEqual(sizes({}), ['query=1', 'write=1']);
  assert.deepEqual(sizes({ writeNodes: 2, queryNodes: 2 }), ['query=2', 'write=2']);
  assert.throws(() => sizes({ writeNodes: 5 }), /writeNodes is 1 to 4/);
  assert.throws(() => sizes({ queryNodes: 0 }), /queryNodes 1 to 3/);
});

test('the catalog names everything by environment, on that environment\'s warehouse bucket', () => {
  const names = (environment: string, bucketName: string) => {
    const found = resources(new CatalogStack(new App(), 'CatalogEnv', {
      env: { account: '111122223333', region: 'us-west-2' },
      environment,
      bucketName,
      oidcIssuer: 'https://oidc.eks.us-west-2.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF',
      sourceSha: 'a'.repeat(40),
    }));
    const of = (type: string, key: string) =>
      found.filter((r) => r.Type === type).map((r) => r.Properties[key]).sort();
    return {
      repositories: of('AWS::ECR::Repository', 'RepositoryName'),
      roles: of('AWS::IAM::Role', 'RoleName'),
      secrets: of('AWS::SecretsManager::Secret', 'Name'),
    };
  };
  assert.deepEqual(names('uat', 'colvennalakeuatfoundation-warehouse0123abcd-xyz'), {
    repositories: ['colvenna-lake-uat/colvenna-catalog', 'colvenna-lake-uat/test-spark'],
    roles: ['colvenna-lake-uat-catalog', 'colvenna-lake-uat-catalog-data', 'colvenna-lake-uat-doris',
      'colvenna-lake-uat-test-flink', 'colvenna-lake-uat-test-spark'],
    secrets: ['colvenna-lake-uat/catalog-db'],
  });
  // Another environment's bucket is not this one's.
  assert.throws(() => names('uat', 'colvennalakeprodfoundation-warehouse0123abcd-xyz'), /warehouse bucket/);
  assert.throws(() => names('uat', 'some-other-bucket'), /warehouse bucket/);
});

test('an environment lives in a standard AWS region, not China or GovCloud', () => {
  for (const region of ['us-west-2', 'us-east-1', 'eu-central-1', 'ap-southeast-1', 'sa-east-1']) {
    assert.equal(standardRegion(region), region);
  }
  for (const bad of [undefined, '', 'us-west', 'US-WEST-2', 'cn-north-1', 'cn-northwest-1', 'us-gov-west-1',
    'us-iso-east-1', 3]) {
    assert.throws(() => standardRegion(bad), /not a standard AWS region/, String(bad));
  }
  assert.ok(isOidcIssuer('https://oidc.eks.eu-central-1.amazonaws.com/id/ABC123', 'eu-central-1'));
  assert.ok(!isOidcIssuer('https://oidc.eks.us-west-2.amazonaws.com/id/ABC123', 'eu-central-1'));
  assert.ok(isZoneOf('eu-central-1b', 'eu-central-1'));
  assert.ok(!isZoneOf('us-west-2a', 'eu-central-1'));
  assert.ok(!isZoneOf('eu-central-1-az1', 'eu-central-1'));
});

test('the foundation takes its region from its env and its two zones from the deploy, in that region', () => {
  const inRegion = (region: string, availabilityZones: string[]) =>
    foundation({ env: { account: '111122223333', region }, availabilityZones });
  const zones = resources(inRegion('eu-central-1', ['eu-central-1a', 'eu-central-1b']))
    .filter((r) => r.Type === 'AWS::EC2::Subnet').map((r) => r.Properties.AvailabilityZone);
  assert.deepEqual([...new Set(zones)].sort(), ['eu-central-1a', 'eu-central-1b']);
  assert.throws(() => inRegion('eu-central-1', ['us-west-2a', 'us-west-2b']), /two zones of eu-central-1/);
  assert.throws(() => inRegion('eu-central-1', ['eu-central-1a']), /two zones of eu-central-1/);
  assert.throws(() => inRegion('eu-central-1', ['eu-central-1a', 'eu-central-1a']), /two zones of eu-central-1/);
  assert.throws(() => inRegion('cn-north-1', ['cn-north-1a', 'cn-north-1b']), /not a standard AWS region/);
  // Any account, given concretely; the cluster's admin is of that account.
  assert.ok(foundation({ env: { account: '444455556666', region: 'us-west-2' },
    adminPrincipalArn: 'arn:aws:iam::444455556666:role/admin' }));
  assert.throws(() => foundation({ env: { account: '444455556666', region: 'us-west-2' } }),
    /principal of the stack's account, 444455556666/);
  assert.throws(() => foundation({ env: { region: 'us-west-2' } }), /concrete AWS account/);
});

test('a stack checks the EKS issuer against its own region', () => {
  const catalog = (region: string, oidcIssuer: string) => new CatalogStack(new App(), 'CatalogRegion', {
    env: { account: '111122223333', region },
    environment: 'uat',
    bucketName: 'colvennalakeuatfoundation-warehouse0123abcd-xyz',
    oidcIssuer,
    sourceSha: 'a'.repeat(40),
  });
  assert.ok(catalog('eu-central-1', 'https://oidc.eks.eu-central-1.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF'));
  assert.throws(() => catalog('eu-central-1', 'https://oidc.eks.us-west-2.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF'),
    /EKS OIDC issuer/);
  assert.throws(() => catalog('us-gov-west-1', 'https://oidc.eks.us-gov-west-1.amazonaws.com/id/ABC'),
    /not a standard AWS region/);
});

test('the account is given, 12 digits, never the CLI\'s default', () => {
  assert.equal(accountFromContext('111122223333'), '111122223333');
  for (const bad of [undefined, '', '11112222333', '1111222233334', 'abcdefghijkl', 111122223333]) {
    assert.throws(() => accountFromContext(bad), /-c account/, String(bad));
  }
  assert.equal(stackRegion({ account: '111122223333', region: 'eu-central-1' }, 'A stack'), 'eu-central-1');
  for (const account of [undefined, '${Token[AWS.AccountId.1]}', 'unknown-account']) {
    assert.throws(() => stackRegion({ account, region: 'us-west-2' }, 'A stack'),
      /A stack needs a concrete AWS account/, String(account));
  }
});

test('a synthesis is for -c account only when the CLI resolved the credentials to it (#233)', () => {
  assert.equal(synthesisAccount('111122223333', '111122223333'), '111122223333');
  // Credentials of another account: refused, and not as unresolved, so cdk_deploy never retries it.
  assert.throws(() => synthesisAccount('111122223333', '444455556666'), (e: Error) =>
    e.message === 'The credentials are of account 444455556666, not -c account=111122223333'
    && !e.message.includes(UNRESOLVED_ACCOUNT));
  // No account resolved (no CDK_DEFAULT_ACCOUNT): refused with the message cdk_deploy retries on.
  for (const cli of [undefined, '']) {
    assert.throws(() => synthesisAccount('111122223333', cli), (e: Error) =>
      e.message.startsWith(`The CDK CLI ${UNRESOLVED_ACCOUNT} (no CDK_DEFAULT_ACCOUNT)`)
      && e.message.includes('-c account=111122223333') && !e.message.includes('credentials are of account'),
    String(cli));
  }
  // -c account is checked first, whatever the CLI resolved.
  for (const cli of ['111122223333', undefined]) {
    assert.throws(() => synthesisAccount(undefined, cli), /-c account=<account>/, String(cli));
  }
});

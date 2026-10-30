import assert from 'node:assert/strict';
import test from 'node:test';
import { App, assertions } from 'aws-cdk-lib';
import { CatalogStack } from '../src/catalog-stack';

const app = new App();
const stack = new CatalogStack(app, 'CatalogTest', {
  env: { account: '111122223333', region: 'us-west-2' },
  environment: 'uat',
  bucketName: 'colvennalakeuatfoundation-warehouse0123abcd-xyz',
  oidcIssuer: 'https://oidc.eks.us-west-2.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF',
  sourceSha: 'a'.repeat(40),
});
type Resource = { Type: string; Properties: Record<string, any> };
const resources = Object.values(
  assertions.Template.fromStack(stack).toJSON().Resources as Record<string, Resource>,
);
const roles = resources.filter((r) => r.Type === 'AWS::IAM::Role');
const policies = resources.filter((r) => r.Type === 'AWS::IAM::Policy');

function role(name: string): Resource {
  const found = roles.find((r) => r.Properties.RoleName === name);
  assert.ok(found, `role ${name}`);
  return found;
}

function statementsOf(roleName: string): Array<Record<string, any>> {
  const logicalIds = Object.entries(
    assertions.Template.fromStack(stack).toJSON().Resources as Record<string, Resource>,
  ).filter(([, r]) => r.Type === 'AWS::IAM::Role' && r.Properties.RoleName === roleName)
    .map(([id]) => id);
  const attached = policies.filter((p) => JSON.stringify(p.Properties.Roles)
    .includes(logicalIds[0]));
  return attached.flatMap((p) => p.Properties.PolicyDocument.Statement);
}

test('server and data roles trust only the catalog service account', () => {
  for (const name of ['colvenna-lake-uat-catalog', 'colvenna-lake-uat-catalog-data']) {
    const trust = JSON.stringify(role(name).Properties.AssumeRolePolicyDocument);
    assert.match(trust, /system:serviceaccount:colvenna-catalog:colvenna-catalog"/);
    assert.match(trust, /sts\.amazonaws\.com/);
  }
});

test('data role allows sessions long enough for Paimon token refresh', () => {
  assert.equal(role('colvenna-lake-uat-catalog-data').Properties.MaxSessionDuration, 43200);
});

test('catalog roles reach the warehouse prefix only, without wildcard S3 actions', () => {
  for (const name of ['colvenna-lake-uat-catalog', 'colvenna-lake-uat-catalog-data']) {
    const statements = statementsOf(name);
    const text = JSON.stringify(statements);
    assert.doesNotMatch(text, /s3:\*|PutBucketPolicy|DeleteBucket|CreateBucket/);
    const objects = statements.find((s) => JSON.stringify(s.Action).includes('s3:PutObject'));
    assert.ok(objects);
    assert.match(JSON.stringify(objects.Resource), /\/warehouse\/\*/);
    assert.doesNotMatch(JSON.stringify(objects.Resource), /flink-state/);
  }
});

test('engine roles never reach table data', () => {
  for (const [namespace, name] of [
    ['colvenna-test-engines', 'test-flink'], ['colvenna-test-engines', 'test-spark'],
    ['colvenna-doris', 'doris'],
  ]) {
    const trust = JSON.stringify(
      role(`colvenna-lake-uat-${name}`).Properties.AssumeRolePolicyDocument);
    assert.match(trust, new RegExp(`system:serviceaccount:${namespace}:${name}"`));
    const text = JSON.stringify(statementsOf(`colvenna-lake-uat-${name}`));
    assert.doesNotMatch(text, /[/"]warehouse([/"*]|$)/);
    if (name === 'test-flink') {
      assert.match(text, new RegExp(`flink-state/test-engines/${name}/\\*`));
    } else {
      assert.deepEqual(statementsOf(`colvenna-lake-uat-${name}`), [], `${name} has no S3 access`);
    }
  }
});

test('catalog images are immutable and scanned', () => {
  const repositories = resources.filter((r) => r.Type === 'AWS::ECR::Repository');
  assert.deepEqual(
    repositories.map((r) => r.Properties.RepositoryName).sort(),
    ['colvenna-lake-uat/colvenna-catalog', 'colvenna-lake-uat/test-spark'],
  );
  for (const repository of repositories) {
    assert.equal(repository.Properties.ImageTagMutability, 'IMMUTABLE');
    assert.equal(repository.Properties.ImageScanningConfiguration.ScanOnPush, true);
  }
});

test('the stack needs a concrete account', () => {
  assert.throws(() => new CatalogStack(new App(), 'Elsewhere', {
    env: { region: 'us-west-2' },
    environment: 'uat',
    bucketName: 'colvennalakeuatfoundation-warehouse0123abcd-xyz',
    oidcIssuer: 'https://oidc.eks.us-west-2.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF',
    sourceSha: 'a'.repeat(40),
  }), /needs a concrete AWS account/);
});

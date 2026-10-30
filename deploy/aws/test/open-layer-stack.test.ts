import assert from 'node:assert/strict';
import test from 'node:test';
import { App, assertions } from 'aws-cdk-lib';
import { OpenLayerStack } from '../src/open-layer-stack';

const app = new App();
const stack = new OpenLayerStack(app, 'OpenLayerTest', {
  env: { account: '111122223333', region: 'us-west-2' },
  environment: 'uat',
  bucketName: 'colvennalakeuatfoundation-warehouse0123abcd-xyz',
  oidcIssuer: 'https://oidc.eks.us-west-2.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF',
  sourceSha: 'a'.repeat(40),
});
type Resource = { Type: string; Properties: Record<string, any> };
const template = assertions.Template.fromStack(stack).toJSON().Resources as Record<string, Resource>;
const resources = Object.values(template);
const roles = resources.filter((r) => r.Type === 'AWS::IAM::Role');
const role = (name: string) => roles.find((r) => r.Properties.RoleName === name)!;
/** The policy documents attached to the role of that name. */
const policies = (name: string) => {
  const id = Object.keys(template).find((k) => template[k] === role(name))!;
  return JSON.stringify(
    resources
      .filter((r) => r.Type === 'AWS::IAM::Policy')
      .filter((p) => JSON.stringify(p.Properties.Roles).includes(`"${id}"`))
      .map((p) => p.Properties.PolicyDocument),
  );
};

test('Polaris is trusted only as its service account and may only assume the read role', () => {
  const trust = JSON.stringify(role('colvenna-lake-uat-polaris').Properties.AssumeRolePolicyDocument);
  assert.match(trust, /system:serviceaccount:colvenna-polaris:polaris"/);
  const text = policies('colvenna-lake-uat-polaris');
  assert.doesNotMatch(text, /s3:/);
  assert.match(text, /sts:AssumeRole/);
});

test('the read role reads the warehouse and cannot write or delete', () => {
  const text = policies('colvenna-lake-uat-iceberg-read');
  assert.match(text, /s3:GetObject/);
  assert.match(text, /warehouse\/\*/);
  assert.doesNotMatch(text, /s3:PutObject|s3:DeleteObject|s3:\*/);
  const trust = JSON.stringify(
    role('colvenna-lake-uat-iceberg-read').Properties.AssumeRolePolicyDocument);
  assert.doesNotMatch(trust, /oidc/);
  assert.equal(role('colvenna-lake-uat-iceberg-read').Properties.MaxSessionDuration, 3600);
});

test('the reader image repository is immutable and scanned', () => {
  const repos = resources.filter((r) => r.Type === 'AWS::ECR::Repository');
  assert.deepEqual(repos.map((r) => r.Properties.RepositoryName), ['colvenna-lake-uat/test-readers']);
  assert.equal(repos[0].Properties.ImageTagMutability, 'IMMUTABLE');
});

test('the stack needs a concrete account', () => {
  assert.throws(() => new OpenLayerStack(new App(), 'Elsewhere', {
    env: { region: 'us-west-2' },
    environment: 'uat',
    bucketName: 'colvennalakeuatfoundation-warehouse0123abcd-xyz',
    oidcIssuer: 'https://oidc.eks.us-west-2.amazonaws.com/id/ABC',
    sourceSha: 'a'.repeat(40),
  }), /needs a concrete AWS account/);
});

test('another environment names its roles and repository apart', () => {
  const props = {
    env: { account: '111122223333', region: 'us-west-2' },
    oidcIssuer: 'https://oidc.eks.us-west-2.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF',
    sourceSha: 'a'.repeat(40),
  };
  const names = (environment: string, bucketName: string) => {
    const t = assertions.Template.fromStack(
      new OpenLayerStack(new App(), 'OpenLayerEnv', { ...props, bucketName, environment }),
    ).toJSON().Resources as Record<string, Resource>;
    const of = (type: string, key: string) => Object.values(t)
      .filter((r) => r.Type === type).map((r) => r.Properties[key]).sort();
    return [...of('AWS::IAM::Role', 'RoleName'), ...of('AWS::ECR::Repository', 'RepositoryName')];
  };
  assert.deepEqual(names('uat', 'colvennalakeuatfoundation-warehouse0123abcd-xyz'),
    ['colvenna-lake-uat-iceberg-read', 'colvenna-lake-uat-polaris', 'colvenna-lake-uat/test-readers']);
  assert.deepEqual(names('prod', 'colvennalakeprodfoundation-warehouse0123abcd-xyz'),
    ['colvenna-lake-prod-iceberg-read', 'colvenna-lake-prod-polaris',
      'colvenna-lake-prod/test-readers']);
});

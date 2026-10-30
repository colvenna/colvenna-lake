import assert from 'node:assert/strict';
import test from 'node:test';
import { App, assertions } from 'aws-cdk-lib';
import { OperatorStack, OperatorStackProps } from '../src/operator-stack';

const props: OperatorStackProps = {
  env: { account: '111122223333', region: 'us-west-2' },
  environment: 'uat',
  bucketName: 'colvennalakeuatfoundation-warehouse0123abcd-xyz',
  oidcIssuer: 'https://oidc.eks.us-west-2.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF',
  sourceSha: 'a'.repeat(40),
};
type Resource = { Type: string; Properties: Record<string, any> };
type Template = { Resources: Record<string, Resource>; Outputs: Record<string, any> };
const template = (extra: Partial<OperatorStackProps> = {}): Template =>
  assertions.Template.fromStack(new OperatorStack(new App(), 'OperatorTest', { ...props, ...extra }))
    .toJSON() as Template;
const ofType = (t: Template, type: string) =>
  Object.entries(t.Resources).filter(([, r]) => r.Type === type);
const two = template({ managedNamespaces: ['colvenna-data', 'colvenna-second'] });

/** The job roles by namespace: logical id, name, trust and the statements attached to it. */
const jobRoles = (t: Template) => {
  const out: Record<string, { id: string; name: string; trust: string; statements: any[] }> = {};
  for (const [id, role] of ofType(t, 'AWS::IAM::Role')) {
    const trust = JSON.stringify(role.Properties.AssumeRolePolicyDocument);
    const sub = trust.match(/system:serviceaccount:([a-z0-9-]+):colvenna-flink"/);
    assert.ok(sub, `${id} trusts a job service account`);
    const statements = ofType(t, 'AWS::IAM::Policy')
      .filter(([, p]) => p.Properties.Roles.some((r: any) => r.Ref === id))
      .flatMap(([, p]) => p.Properties.PolicyDocument.Statement);
    out[sub[1]] = { id, name: role.Properties.RoleName, trust, statements };
  }
  return out;
};

/** The object keys and list prefixes a role's statements allow, as S3 StringLike patterns. */
const reach = (statements: any[]) => {
  const objects: string[] = [];
  const prefixes: string[] = [];
  for (const s of statements) {
    const actions: string[] = [].concat(s.Action);
    if (actions.includes('s3:ListBucket')) {
      prefixes.push(...s.Condition.StringLike['s3:prefix']);
    }
    if (actions.includes('s3:GetObject')) {
      const text = JSON.stringify(s.Resource);
      objects.push(...[...text.matchAll(/:::[a-z0-9-]+\/([^"]+)"/g)].map((m) => m[1]));
    }
  }
  return { objects, prefixes };
};
const like = (patterns: string[], value: string) => patterns.some((p) => new RegExp(
  `^${p.split('*').map((x) => x.replace(/[.?+^$()[\]{}|\\/]/g, '\\$&')).join('.*')}$`,
).test(value));

test('by default the job role trusts only the job service account of colvenna-data', () => {
  const roles = jobRoles(template());
  assert.deepEqual(Object.keys(roles), ['colvenna-data']);
  assert.equal(roles['colvenna-data'].name, 'colvenna-lake-uat-jobs-colvenna-data');
  assert.match(roles['colvenna-data'].trust, /sts\.amazonaws\.com/);
});

test('each managed namespace has its own role, reaching only its own Flink state', () => {
  const roles = jobRoles(two);
  assert.deepEqual(Object.keys(roles).sort(), ['colvenna-data', 'colvenna-second']);
  for (const [ns, other] of [['colvenna-data', 'colvenna-second'], ['colvenna-second', 'colvenna-data']]) {
    const role = roles[ns];
    assert.equal(role.name, `colvenna-lake-uat-jobs-${ns}`);
    assert.doesNotMatch(role.trust, new RegExp(`serviceaccount:${other}:`));
    const { objects, prefixes } = reach(role.statements);
    assert.deepEqual(objects, [`flink-state/${ns}/*`]);
    // Its own state, as Flink writes it and the erasure check lists it: Hadoop's S3A HEADs the
    // root itself first, which S3 answers 404, not 403, only with the bare prefix listable.
    assert.ok(like(objects, `flink-state/${ns}/orders-1a2b3c4d/checkpoints/chk-1/_metadata`));
    assert.ok(like(prefixes, `flink-state/${ns}`));
    assert.ok(like(prefixes, `flink-state/${ns}/`));
    // Never the other namespace's.
    assert.ok(!like(objects, `flink-state/${other}/orders-1a2b3c4d/checkpoints/x`));
    const others = [`flink-state/${other}`, `flink-state/${other}/`, 'flink-state/'];
    for (const prefix of others) {
      assert.ok(!like(prefixes, prefix), `${ns} lists ${prefix}`);
    }
  }
});

test('the chart gets each namespace with its own role', () => {
  const roles = jobRoles(two);
  // {"colvenna-data":"<arn>","colvenna-second":"<arn>"}, as an Fn::Join of strings and role ARNs.
  const parts: any[] = two.Outputs.JobRoleArns.Value['Fn::Join'][1];
  const pairs: Record<string, string> = {};
  parts.forEach((part, i) => {
    const key = typeof part === 'string' ? part.match(/"([a-z0-9-]+)":"$/) : null;
    if (key) {
      pairs[key[1]] = parts[i + 1]['Fn::GetAtt'][0];
    }
  });
  assert.deepEqual(pairs, {
    'colvenna-data': roles['colvenna-data'].id,
    'colvenna-second': roles['colvenna-second'].id,
  });
});

test('jobs reach their Flink state only, never table data', () => {
  const text = JSON.stringify(
    ofType(two, 'AWS::IAM::Policy').map(([, p]) => p.Properties.PolicyDocument),
  );
  // The bucket's own name contains "warehouse"; the table prefix is warehouse/.
  assert.doesNotMatch(text, /bhrc\/warehouse/);
  assert.doesNotMatch(text, /s3:\*/);
  assert.match(text, /flink-state\/colvenna-data\/\*/);
  assert.match(text, /flink-state\/colvenna-second\/\*/);
});

test('image repositories are immutable and scanned', () => {
  const repos = ofType(template(), 'AWS::ECR::Repository').map(([, r]) => r);
  assert.deepEqual(
    repos.map((r) => r.Properties.RepositoryName).sort(),
    ['colvenna-lake-uat/colvenna-jobs', 'colvenna-lake-uat/colvenna-operator'],
  );
  for (const repo of repos) {
    assert.equal(repo.Properties.ImageTagMutability, 'IMMUTABLE');
    assert.equal(repo.Properties.ImageScanningConfiguration.ScanOnPush, true);
  }
});

test('the stack needs a concrete account', () => {
  assert.throws(() => new OperatorStack(new App(), 'Elsewhere', {
    ...props,
    env: { region: 'us-west-2' },
  }), /needs a concrete AWS account/);
});

test('the stack refuses namespaces a role cannot be named after', () => {
  for (const managedNamespaces of [[], ['Colvenna'], ['colvenna-data', 'colvenna-data'], ['a'.repeat(42)]]) {
    assert.throws(() => new OperatorStack(new App(), 'Bad', { ...props, managedNamespaces }),
      /distinct namespace names/, JSON.stringify(managedNamespaces));
  }
  // colvenna-lake-uat-jobs-<namespace>: 23 characters of the role name's 64 are the prefix's.
  const longest = { ...props, managedNamespaces: ['a'.repeat(41)] };
  assert.doesNotThrow(() => new OperatorStack(new App(), 'Longest', longest));
});

test('no managed namespace may begin with another one\'s name', () => {
  // colvenna-data's bare prefix would list colvenna-data-b's state.
  const prefixed = { ...props, managedNamespaces: ['colvenna-data', 'colvenna-data-b'] };
  assert.throws(() => new OperatorStack(new App(), 'Prefixed', prefixed), /another's name: colvenna-data/);
});

test('another environment names its roles and repositories apart', () => {
  const prod = template({
    environment: 'prod', bucketName: 'colvennalakeprodfoundation-warehouse0123abcd-xyz',
  });
  assert.deepEqual(Object.values(jobRoles(prod)).map((r) => r.name),
    ['colvenna-lake-prod-jobs-colvenna-data']);
  assert.deepEqual(
    ofType(prod, 'AWS::ECR::Repository').map(([, r]) => r.Properties.RepositoryName).sort(),
    ['colvenna-lake-prod/colvenna-jobs', 'colvenna-lake-prod/colvenna-operator'],
  );
  // The longer prefix leaves a namespace name fewer characters.
  const longest = {
    ...props, environment: 'prod', bucketName: 'colvennalakeprodfoundation-warehouse0123abcd-xyz',
    managedNamespaces: ['a'.repeat(41)],
  };
  assert.throws(() => new OperatorStack(new App(), 'TooLong', longest), /at most 40 characters/);
});

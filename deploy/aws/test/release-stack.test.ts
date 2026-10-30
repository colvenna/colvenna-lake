import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import test from 'node:test';
import { App, assertions } from 'aws-cdk-lib';
import { RELEASE_REPOSITORIES, RELEASE_STACK, ReleaseStack } from '../src/release-stack';

const build = (props: { account?: string; region?: string; sourceSha?: string; pullAccounts?: string[] } = {}) =>
  new ReleaseStack(new App(), RELEASE_STACK, {
    env: { account: 'account' in props ? props.account : '111111111111', region: props.region ?? 'eu-central-1' },
    sourceSha: props.sourceSha ?? 'a'.repeat(40),
    pullAccounts: props.pullAccounts,
  });
type Resource = { Type: string; Properties: Record<string, any>; DeletionPolicy?: string };
const template = assertions.Template.fromStack(build()).toJSON();
const repositories = Object.values(template.Resources as Record<string, Resource>)
  .filter((r) => r.Type === 'AWS::ECR::Repository');

test('one repository per pinned image, named as an environment\'s, under colvenna-lake-release', () => {
  assert.deepEqual(
    repositories.map((r) => r.Properties.RepositoryName).sort(),
    Object.keys(RELEASE_REPOSITORIES).map((name) => `colvenna-lake-release/${name}`).sort(),
  );
  for (const name of ['flink-base', 'colvenna-catalog', 'charts/colvenna-catalog', 'colvenna-operator', 'colvenna-jobs',
    'colvenna-console', 'colvenna-bi', 'query-engine', 'test-readers', 'colvenna-cli', 'third-party']) {
    assert.ok(name in RELEASE_REPOSITORIES, name);
  }
  // A repository name with a slash, as the catalog release's chart has (#295), is an output too.
  const outputs = template.Outputs as Record<string, { Value: string }>;
  assert.equal(outputs.RepositoryChartsColvennaCatalog.Value,
    '111111111111.dkr.ecr.eu-central-1.amazonaws.com/colvenna-lake-release/charts/colvenna-catalog');
});

test('the query engine, a modified build based on Apache Doris, is released under a name of its own', () => {
  // release/trademarks.md: no repository of the stack is named Doris (a repository dropped from it,
  // as v1.0.0-rc.1's doris was, stays in the account outside the stack: every repository is retained).
  assert.deepEqual(Object.keys(RELEASE_REPOSITORIES).filter((name) => /doris/i.test(name)), []);
  assert.match(RELEASE_REPOSITORIES['query-engine'], /based on Apache Doris 4\.1\.4/);
  const outputs = template.Outputs as Record<string, { Value: string }>;
  assert.equal(outputs.RepositoryQueryEngine.Value,
    '111111111111.dkr.ecr.eu-central-1.amazonaws.com/colvenna-lake-release/query-engine');
  assert.deepEqual(Object.keys(outputs).filter((key) => /doris/i.test(key)), []);
});

test('tags never move, pushes are scanned, and nothing is removed with the stack', () => {
  for (const r of repositories) {
    assert.equal(r.Properties.ImageTagMutability, 'IMMUTABLE');
    assert.deepEqual(r.Properties.ImageScanningConfiguration, { ScanOnPush: true });
    assert.equal(r.DeletionPolicy, 'Retain');
    // No lifecycle rule: every candidate stays installable.
    assert.equal(r.Properties.LifecyclePolicy, undefined);
    assert.equal(r.Properties.EmptyOnDelete, undefined);
  }
});

test('the registry is in its stack\'s account and standard region, from a committed SHA', () => {
  const outputs = Object.values(template.Outputs as Record<string, { Value: string }>).map((o) => o.Value);
  assert.ok(outputs.includes('111111111111.dkr.ecr.eu-central-1.amazonaws.com/colvenna-lake-release/colvenna-jobs'));
  assert.throws(() => build({ region: 'cn-north-1' }), /standard AWS region/);
  assert.throws(() => build({ account: undefined }), /needs its account/);
  assert.throws(() => build({ sourceSha: 'main' }), /committed SHA/);
});

test('only its own account reads it, unless other accounts are named to pull, and then only pull', () => {
  for (const r of repositories) {
    assert.equal(r.Properties.RepositoryPolicyText, undefined);
  }
  const shared = Object.values(assertions.Template.fromStack(build({ pullAccounts: ['222222222222', '333333333333'] }))
    .toJSON().Resources as Record<string, Resource>).filter((r) => r.Type === 'AWS::ECR::Repository');
  assert.equal(shared.length, repositories.length);
  for (const r of shared) {
    const [statement, ...more] = r.Properties.RepositoryPolicyText.Statement;
    assert.deepEqual(more, []);
    assert.equal(statement.Effect, 'Allow');
    assert.equal(statement.Condition, undefined);
    // Each account's IAM principals, never anyone.
    const principals = JSON.stringify(statement.Principal);
    assert.match(principals, /iam::222222222222:root/);
    assert.match(principals, /iam::333333333333:root/);
    assert.doesNotMatch(principals, /"\*"/);
    assert.deepEqual([...statement.Action].sort(),
      ['ecr:BatchCheckLayerAvailability', 'ecr:BatchGetImage', 'ecr:GetDownloadUrlForLayer']);
  }
  for (const bad of [['12345'], ['22222222222a'], ['*']]) {
    assert.throws(() => build({ pullAccounts: bad }), /pullAccounts/, String(bad));
  }
});

test('the stack and its repositories are the ones the manifest names (deploy/scripts/release/manifest.py)', () => {
  const tool = path.join(__dirname, '..', '..', 'scripts', 'release', 'manifest.py');
  const run = (...args: string[]) => execFileSync('python3', [tool, ...args], { encoding: 'utf8' }).trim();
  assert.equal(run('stack'), RELEASE_STACK);
  const named = new Set(run('names', 'v0.0.0-dev.1').split('\n').map((row) => row.split('\t')[1]));
  assert.deepEqual([...named].sort(), Object.keys(RELEASE_REPOSITORIES).sort());
});

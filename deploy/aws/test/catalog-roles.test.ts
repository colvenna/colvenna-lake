import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import test from 'node:test';
import { App, assertions } from 'aws-cdk-lib';
import { CatalogStack } from '../src/catalog-stack';

/**
 * Colvenna Catalog is a product of its own (#295), and Colvenna Lake runs its release's image and
 * chart; the catalog's IAM roles stay in Colvenna Lake's catalog stack, because the catalog's CDK app is
 * no released artifact an install could fetch by digest. These tests hold that stack's server and data
 * roles to what the catalog's own EKS stack grants them (fixtures/colvenna-catalog-eks-roles.json, its
 * deploy/aws/src/eks-stack.ts at the pinned release, normalized as here): the same trust, the same
 * session ceiling, every grant of the catalog's, and beyond them only what is listed as Colvenna Lake's
 * own, with its reason.
 */
const BUCKET = 'colvennalakeuatfoundation-warehouse0123abcd-xyz';
const ISSUER = 'oidc.eks.us-west-2.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF';
const ACCOUNT = '111122223333';
const fixture = JSON.parse(readFileSync(resolve(__dirname, 'fixtures/colvenna-catalog-eks-roles.json'), 'utf8'));
const pin = JSON.parse(readFileSync(resolve(__dirname, '../../images/colvenna-catalog.json'), 'utf8'));

/**
 * What Colvenna Lake grants beyond the catalog's stack, and why. Listing the bare warehouse prefix
 * keeps S3A's probe of the warehouse root's own key (a HEAD on `warehouse`, the directory without its
 * slash) a 404 rather than a 403, as Colvenna Lake's roles have done since the catalog first ran on
 * EKS (M2); the catalog's stack lists keys under `warehouse/` only.
 */
const LAKE_ONLY = [
  'Allow s3:ListBucket arn:aws:s3:::<bucket> if StringLike s3:prefix=warehouse',
  'Allow s3:ListBucketMultipartUploads arn:aws:s3:::<bucket> if StringLike s3:prefix=warehouse',
];

type Resource = { Type: string; Properties: Record<string, any> };
type Normalized = { maxSessionDuration: number | null; trust: string[]; grants: string[] };

/** A template value as text: Fn::Join resolved, the partition aws, the test's names as placeholders. */
function text(value: unknown): string {
  let out: string;
  if (typeof value === 'string') {
    out = value;
  } else if (value && typeof value === 'object' && 'Fn::Join' in value) {
    const [separator, parts] = (value as { 'Fn::Join': [string, unknown[]] })['Fn::Join'];
    out = parts.map(text).join(separator);
  } else if (value && typeof value === 'object' && (value as { Ref?: string }).Ref === 'AWS::Partition') {
    out = 'aws';
  } else {
    throw new Error(`not a value the normalization resolves: ${JSON.stringify(value)}`);
  }
  return out.split(BUCKET).join('<bucket>').split(ISSUER).join('<issuer>').split(ACCOUNT).join('<account>');
}

const list = <T>(value: T | T[]): T[] => (Array.isArray(value) ? value : [value]);

/** A policy's statements as one line per action, resource and condition value: "<effect> <action> <resource>[ if <op> <key>=<value>]". */
function grants(statements: Array<Record<string, any>>): string[] {
  const out = new Set<string>();
  for (const s of statements) {
    const conditions = Object.entries((s.Condition ?? {}) as Record<string, Record<string, unknown>>);
    assert.ok(conditions.length <= 1 && conditions.every(([, kv]) => Object.keys(kv).length === 1),
      `one condition key at most, so that its values are alternatives: ${JSON.stringify(s.Condition)}`);
    const alternatives = conditions.flatMap(([op, kv]) => Object.entries(kv)
      .flatMap(([key, values]) => list(values).map((v) => ` if ${op} ${text(key)}=${text(v)}`)));
    for (const action of list(s.Action)) {
      for (const resource of list(s.Resource)) {
        for (const condition of alternatives.length > 0 ? alternatives : ['']) {
          out.add(`${s.Effect} ${action} ${text(resource)}${condition}`);
        }
      }
    }
  }
  return [...out].sort();
}

/** A role of a template, its trust, session ceiling and grants, normalized. */
function normalized(template: { Resources: Record<string, Resource> }, roleName: string): Normalized {
  const entries = Object.entries(template.Resources);
  const found = entries.filter(([, r]) => r.Type === 'AWS::IAM::Role' && r.Properties.RoleName === roleName);
  assert.equal(found.length, 1, `one role ${roleName}`);
  const [logicalId, role] = found[0];
  const statements = entries.map(([, r]) => r)
    .filter((r) => r.Type === 'AWS::IAM::Policy' && r.Properties.Roles.some((x: any) => x.Ref === logicalId))
    .flatMap((r) => r.Properties.PolicyDocument.Statement);
  const trust = (role.Properties.AssumeRolePolicyDocument.Statement as Array<Record<string, any>>)
    .flatMap((s) => list(s.Action).map((action) => `${s.Effect} ${action} ${text(s.Principal.Federated)}`
      + Object.entries(s.Condition as Record<string, Record<string, unknown>>).sort()
        .flatMap(([op, kv]) => Object.entries(kv).sort().map(([k, v]) => ` if ${op} ${text(k)}=${text(v)}`)).join('')));
  return { maxSessionDuration: role.Properties.MaxSessionDuration ?? null, trust: trust.sort(), grants: grants(statements) };
}

const lake = assertions.Template.fromStack(new CatalogStack(new App(), 'CatalogRolesTest', {
  env: { account: ACCOUNT, region: 'us-west-2' },
  environment: 'uat',
  bucketName: BUCKET,
  oidcIssuer: `https://${ISSUER}`,
  sourceSha: 'a'.repeat(40),
})).toJSON() as { Resources: Record<string, Resource> };
const LAKE_ROLES = { server: 'colvenna-lake-uat-catalog', data: 'colvenna-lake-uat-catalog-data' };

test('the comparison is with the catalog release Colvenna Lake pins', () => {
  // A new pin needs the fixture read again from that release's EKS stack.
  assert.equal(fixture.candidate, pin.candidate);
  assert.equal(fixture.repository, pin.repository);
  assert.deepEqual(Object.keys(fixture.roles).sort(), ['data', 'server']);
});

for (const [kind, name] of Object.entries(LAKE_ROLES)) {
  test(`the ${kind} role trusts what the catalog's does, for as long`, () => {
    const ours = normalized(lake, name);
    assert.deepEqual(ours.trust, fixture.roles[kind].trust);
    assert.equal(ours.maxSessionDuration, fixture.roles[kind].maxSessionDuration);
  });

  test(`the ${kind} role grants all the catalog's does, and beyond it only Colvenna Lake's listed own`, () => {
    const ours = normalized(lake, name).grants;
    assert.deepEqual(fixture.roles[kind].grants.filter((g: string) => !ours.includes(g)), [], 'missing');
    assert.deepEqual(ours.filter((g) => !fixture.roles[kind].grants.includes(g)), LAKE_ONLY, 'beyond');
  });
}

test('the fixture is the catalog\'s EKS stack, when its synthesized template is given', (t) => {
  // COLVENNA_CATALOG_EKS_TEMPLATE: the catalog's CatalogEksStack at the pinned release, synthesized
  // with account 111122223333, region us-west-2, environment uat, the issuer and bucket above,
  // namespace and service account colvenna-catalog and warehouse prefix warehouse.
  const path = process.env.COLVENNA_CATALOG_EKS_TEMPLATE;
  if (!path) {
    t.skip('COLVENNA_CATALOG_EKS_TEMPLATE not given');
    return;
  }
  const theirs = JSON.parse(readFileSync(path, 'utf8'));
  assert.deepEqual({ server: normalized(theirs, 'colvenna-catalog-uat-server'), data: normalized(theirs, 'colvenna-catalog-uat-data') },
    fixture.roles);
});

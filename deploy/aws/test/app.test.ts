import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import test from 'node:test';
import { UNRESOLVED_ACCOUNT } from '../src/env';

/**
 * src/app.ts run as the CDK CLI runs it: the context in CDK_CONTEXT_JSON, and CDK_DEFAULT_ACCOUNT
 * only when the CLI resolved the credentials' account (#233). Nothing else of the caller's
 * environment, so no AWS credentials: the account check comes before anything else.
 */
function synthesize(cli: string | undefined) {
  const outdir = mkdtempSync(join(tmpdir(), 'colvenna-app-'));
  try {
    const out = spawnSync(process.execPath, ['--require', 'ts-node/register', 'src/app.ts'], {
      cwd: resolve(__dirname, '..'),
      encoding: 'utf8',
      env: {
        PATH: process.env.PATH,
        TS_NODE_TRANSPILE_ONLY: 'true',
        CDK_OUTDIR: outdir,
        CDK_DEFAULT_REGION: 'us-west-2',
        CDK_CONTEXT_JSON: JSON.stringify({ component: 'release', account: '111122223333', sourceSha: 'a'.repeat(40) }),
        ...(cli === undefined ? {} : { CDK_DEFAULT_ACCOUNT: cli }),
      },
    });
    return { status: out.status, stderr: out.stderr };
  } finally {
    rmSync(outdir, { recursive: true, force: true });
  }
}

test('the app refuses a run whose CLI resolved no account, saying so (#233)', () => {
  const { status, stderr } = synthesize(undefined);
  assert.notEqual(status, 0);
  assert.match(stderr, new RegExp(`Error: The CDK CLI ${UNRESOLVED_ACCOUNT} \\(no CDK_DEFAULT_ACCOUNT\\)`));
  assert.doesNotMatch(stderr, /credentials are of account undefined/);
});

test('the app refuses credentials of another account, not as unresolved', () => {
  const { status, stderr } = synthesize('444455556666');
  assert.notEqual(status, 0);
  assert.match(stderr, /Error: The credentials are of account 444455556666, not -c account=111122223333/);
  assert.ok(!stderr.includes(UNRESOLVED_ACCOUNT), stderr);
});

/**
 * The environment a stack belongs to (deploy/env.sh's COLVENNA_ENV), from `-c env=<name>`: its stacks
 * and AWS resource names are its own, so several environments live side by side in an account.
 */
export function envFromContext(value: unknown): string {
  if (typeof value !== 'string' || !/^[a-z][a-z0-9]{0,7}$/.test(value)) {
    throw new Error('-c env=<env>: a lowercase letter, then lowercase letters and digits, at most 8 in all');
  }
  // The development PoC (tag history/m1-m5) named its stacks and resources after m<n>.
  if (/^(m[0-9]?|poc)$/.test(value)) {
    throw new Error(`-c env: ${value} is reserved, the development PoC's names start so`);
  }
  // The release registry's repositories are colvenna-lake-release/... (release-stack.ts).
  if (value === 'release') {
    throw new Error('-c env: release is reserved, the release registry\'s names start so');
  }
  return value;
}

/** The environment's stack: Foundation -> ColvennaLakeUatFoundation. */
export function envStack(env: string, component: string): string {
  return `ColvennaLake${env[0].toUpperCase()}${env.slice(1)}${component}`;
}

/** The environment's AWS resource name, its first colvenna-lake made colvenna-lake-<env>. */
export function envResource(env: string, name: string): string {
  return name.replace('colvenna-lake', `colvenna-lake-${env}`);
}

/**
 * The AWS account an environment is installed into (deploy/env.sh's COLVENNA_ACCOUNT), from
 * `-c account=<id>`: 12 digits.
 */
export function accountFromContext(value: unknown): string {
  if (typeof value !== 'string' || !/^[0-9]{12}$/.test(value)) {
    throw new Error('-c account=<account>: the 12-digit AWS account the environment is installed into');
  }
  return value;
}

/**
 * What synthesisAccount says when the CDK CLI resolved no account: deploy/scripts/lib/cdk.sh's
 * cdk_deploy runs the CLI again when, and only when, a run failed saying it.
 */
export const UNRESOLVED_ACCOUNT = 'resolved no AWS account from the credentials';

/**
 * The account a synthesis is for: `-c account` (accountFromContext), once it is the account of the
 * credentials the CDK CLI deploys with, which the CLI gives the app as CDK_DEFAULT_ACCOUNT (`cli`).
 * A stack must never land in another account, so credentials of another one are refused.
 *
 * The CLI asks STS for that account once, as it starts; when no answer comes in time (credentials
 * from the instance metadata service, or STS, on a loaded host: issue #233), it sets no
 * CDK_DEFAULT_ACCOUNT, and the account stays unresolved for the rest of its run, its deployment
 * too. That is refused with its own message (UNRESOLVED_ACCOUNT): nothing has been deployed, and a
 * new run asks again. Taking the account from anywhere else would check `-c account` against
 * itself, and the CLI could not deploy the stack anyway.
 */
export function synthesisAccount(context: unknown, cli: string | undefined): string {
  const account = accountFromContext(context);
  if (cli === undefined || cli === '') {
    throw new Error(`The CDK CLI ${UNRESOLVED_ACCOUNT} (no CDK_DEFAULT_ACCOUNT), so they cannot be `
      + `checked against -c account=${account}: STS or the credentials did not answer in time; run it again`);
  }
  if (cli !== account) {
    throw new Error(`The credentials are of account ${cli}, not -c account=${account}`);
  }
  return account;
}

/**
 * A standard AWS region (partition aws), e.g. us-west-2 or eu-central-1, which deploy/env.sh's
 * COLVENNA_REGION names, checked as its standard_region does: three parts, the last a digit, and not
 * cn-...; GovCloud's (us-gov-west-1) have four. China and GovCloud regions are other partitions,
 * with their own ARNs and endpoints: not supported.
 */
export function standardRegion(value: unknown): string {
  if (typeof value !== 'string' || !/^[a-z]{2}-[a-z]+-[1-9]$/.test(value) || value.startsWith('cn-')) {
    throw new Error(`${String(value)} is not a standard AWS region (partition aws), e.g. us-west-2`);
  }
  return value;
}

/**
 * The region of a stack's env, once its account is a concrete AWS account (12 digits, not left to
 * the CLI's credentials) and its region a standard one; `subject` begins the error, e.g. 'The
 * catalog stack'.
 */
export function stackRegion(env: { account?: string; region?: string } | undefined, subject: string): string {
  if (typeof env?.account !== 'string' || !/^[0-9]{12}$/.test(env.account)) {
    throw new Error(`${subject} needs a concrete AWS account (12 digits), not ${String(env?.account)}`);
  }
  return standardRegion(env.region);
}

/** Whether it is the OIDC issuer of an EKS cluster in that region. */
export function isOidcIssuer(issuer: string, region: string): boolean {
  return new RegExp(`^https://oidc\\.eks\\.${region}\\.amazonaws\\.com/id/[A-Za-z0-9]+$`).test(issuer);
}

/** Whether it is one of that region's availability zones, e.g. us-west-2a. */
export function isZoneOf(zone: string, region: string): boolean {
  return new RegExp(`^${region}[a-z]$`).test(zone);
}

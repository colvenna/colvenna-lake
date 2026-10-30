import { App, Duration } from 'aws-cdk-lib';
import { CatalogStack } from './catalog-stack';
import { EntryStack } from './entry-stack';
import { envFromContext, envStack, stackRegion, synthesisAccount } from './env';
import { FoundationStack } from './foundation-stack';
import { nagChecks } from './nag';
import { OpenLayerStack } from './open-layer-stack';
import { OperatorStack } from './operator-stack';
import { RELEASE_STACK, ReleaseStack } from './release-stack';
import { TestMysqlStack } from './test-mysql-stack';
import { TestPostgresStack } from './test-postgres-stack';

// One stack per synthesis: -c component=<component> -c env=<env> -c account=<account>, and that
// component's context, which deploy/scripts/<component>/deploy-stack.sh (or the fixtures'
// deploy-sources.sh) reads from the stacks it builds on. The release registry (-c component=release,
// deploy/scripts/release/deploy-registry.sh) is the account's, in no environment.
// The account is the environment's (deploy/env.sh's COLVENNA_ACCOUNT, -c account; the release
// registry's: the caller's), and the credentials the CLI synthesizes with must be of it, as the CLI
// resolved them (synthesisAccount; a run that resolved none is refused, and cdk_deploy runs it
// again); the region the environment's (COLVENNA_REGION, which the scripts export as AWS_REGION), a
// standard one.
const app = new App();
const account = synthesisAccount(app.node.tryGetContext('account'), process.env.CDK_DEFAULT_ACCOUNT);
const region = stackRegion({ account, region: process.env.CDK_DEFAULT_REGION }, 'Synthesizing colvenna-lake');
const component = app.node.tryGetContext('component');
// Every stack but the release registry is an environment's.
const env = component === 'release' ? '' : envFromContext(app.node.tryGetContext('env'));
const context = (key: string): string => {
  const value = app.node.tryGetContext(key);
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`Missing -c ${key}=...`);
  }
  return value;
};
const optional = (key: string): string | undefined => {
  const value = app.node.tryGetContext(key);
  return typeof value === 'string' && value.length > 0 ? value : undefined;
};
const list = (key: string) => context(key).split(',');
const common = { env: { account, region }, environment: env, sourceSha: context('sourceSha') };

switch (context('component')) {
  case 'release':
    // -c pullAccounts=<id>,<id>: other accounts that may pull the release's images (default none).
    new ReleaseStack(app, RELEASE_STACK, {
      env: { account, region },
      sourceSha: context('sourceSha'),
      pullAccounts: optional('pullAccounts')?.split(','),
    });
    break;
  case 'foundation': {
    // -c writeNodes=<n> -c queryNodes=<n>: the node groups' sizes (default 1 each).
    const nodes = (key: string) => {
      const value = optional(key);
      return value === undefined ? undefined : Number(value);
    };
    new FoundationStack(app, envStack(env, 'Foundation'), {
      ...common,
      adminPrincipalArn: context('adminPrincipalArn'),
      operatorCidr: context('operatorCidr'),
      availabilityZones: list('availabilityZones'),
      writeNodes: nodes('writeNodes'),
      queryNodes: nodes('queryNodes'),
    });
    break;
  }
  case 'catalog':
    new CatalogStack(app, envStack(env, 'Catalog'), {
      ...common,
      bucketName: context('bucketName'),
      oidcIssuer: context('oidcIssuer'),
    });
    break;
  case 'operator': {
    // -c managedNamespaces=a,b: the operator's managed namespaces (default: colvenna-data alone).
    const managedNamespaces = optional('managedNamespaces');
    new OperatorStack(app, envStack(env, 'Operator'), {
      ...common,
      bucketName: context('bucketName'),
      oidcIssuer: context('oidcIssuer'),
      managedNamespaces: managedNamespaces?.split(','),
    });
    break;
  }
  case 'open-layer':
    new OpenLayerStack(app, envStack(env, 'OpenLayer'), {
      ...common,
      bucketName: context('bucketName'),
      oidcIssuer: context('oidcIssuer'),
    });
    break;
  case 'entry':
    new EntryStack(app, envStack(env, 'Entry'), {
      ...common,
      oidcIssuer: context('oidcIssuer'),
      vpcId: context('vpcId'),
      publicSubnetIds: list('publicSubnetIds'),
      publicSubnetCidrs: list('publicSubnetCidrs'),
      availabilityZones: list('availabilityZones'),
      clusterSecurityGroupId: context('clusterSecurityGroupId'),
      hostedZoneId: context('hostedZoneId'),
      hostedZoneName: context('hostedZoneName'),
      domain: context('domain'),
      sessionTimeout: Duration.seconds(Number(context('sessionTimeoutSeconds'))),
    });
    break;
  case 'test-mysql':
  case 'test-postgres': {
    const sources = {
      ...common,
      vpcId: context('vpcId'),
      eksSecurityGroupId: context('eksSecurityGroupId'),
      isolatedSubnetIds: list('isolatedSubnetIds') as [string, string],
      isolatedSubnetAzs: list('isolatedSubnetAzs') as [string, string],
      isolatedRouteTableIds: list('isolatedRouteTableIds') as [string, string],
    };
    if (component === 'test-mysql') {
      new TestMysqlStack(app, envStack(env, 'TestMysql'), sources);
    } else {
      new TestPostgresStack(app, envStack(env, 'TestPostgres'), {
        ...sources,
        catalogSubnetGroupName: context('catalogSubnetGroupName'),
      });
    }
    break;
  }
  default:
    throw new Error('-c component: foundation, catalog, operator, open-layer, entry, test-mysql, test-postgres '
      + `or release, not ${component}`);
}
// cdk-nag's AWS Solutions rules: a finding not acknowledged beside its resource fails the synthesis.
nagChecks(app);

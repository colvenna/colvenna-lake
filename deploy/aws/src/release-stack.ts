import {
  CfnOutput,
  RemovalPolicy,
  Stack,
  StackProps,
  Tags,
  aws_ecr as ecr,
  aws_iam as iam,
} from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { standardRegion } from './env';

/** The release registry's stack: its account's, in no environment. */
export const RELEASE_STACK = 'ColvennaLakeRelease';
/** Its repositories' prefix, as colvenna-lake-<env> is an environment's (deploy/env.sh's env_name). */
export const RELEASE_PREFIX = 'colvenna-lake-release';
/**
 * The repositories, named as an environment's own (colvenna-lake-<env>/<name>), and what each holds:
 * the images a release candidate's manifest pins (deploy/scripts/release/manifest.py), each tagged
 * <candidate> or <candidate>-<variant>, and the copies of the Colvenna Catalog release it pins, its
 * image and its chart, so that a candidate installs from this one registry. A repository dropped from
 * this list leaves the stack but stays in the account with its images (RETAIN), as doris, which held
 * v1.0.0-rc.1's FE and BE, did when #258 added query-engine for them and dropped doris from the list
 * (release/trademarks.md).
 */
export const RELEASE_REPOSITORIES: Record<string, string> = {
  'flink-base': 'the jobs\' foundation: Flink 1.20.4 with Paimon 2.0.0',
  'colvenna-catalog': 'Colvenna Catalog\'s server image, the release the candidate pins '
    + '(deploy/images/colvenna-catalog.json), copied by digest from the catalog\'s release',
  'charts/colvenna-catalog': 'Colvenna Catalog\'s Helm chart, an OCI artifact, the release the candidate '
    + 'pins, copied by digest from the catalog\'s release',
  'colvenna-operator': 'colvenna-operator',
  'colvenna-jobs': 'the five colvenna-jobs images, one per source type',
  'colvenna-console': 'colvenna-console',
  'colvenna-bi': 'the BI interface (Superset with colvenna_bi)',
  'query-engine': 'the query engine FE and BE: a modified build based on Apache Doris 4.1.4, '
    + 'with the Paimon 2.0.0 reader',
  'test-readers': 'the fixtures\' Spark Iceberg reader (--fixtures)',
  'colvenna-cli': 'the colvenna CLI binaries, as one layer of an image',
  'third-party': 'the third-party images the platform and the fixtures run (deploy/images/third-party.tsv), '
    + 'each tagged <candidate>-<name> by its upstream digest',
};

export interface ReleaseStackProps extends StackProps {
  sourceSha: string;
  /**
   * Other accounts whose installs may pull the release's images (-c pullAccounts=<id>,<id>): each
   * repository's policy lets those accounts' IAM principals pull, nothing more (never anyone). None by
   * default, and no policy: only the registry's own account, by IAM.
   */
  pullAccounts?: string[];
}

/**
 * The release registry: what every release candidate was built into and is installed from
 * (deploy/install.sh --release, from whichever registry the candidate's manifest names). In the
 * account and standard region of the stack's env. Tags never move, and nothing here is removed with
 * the stack or by an environment's uninstall: an environment's repositories are
 * colvenna-lake-<env>/..., and the environment name release is reserved (deploy/env.sh).
 */
export class ReleaseStack extends Stack {
  constructor(scope: Construct, id: string, props: ReleaseStackProps) {
    super(scope, id, props);
    const account = props.env?.account;
    if (account === undefined || !/^[0-9]{12}$/.test(account)) {
      throw new Error('The release registry needs its account (env.account)');
    }
    const region = standardRegion(props.env?.region);
    if (!/^[0-9a-f]{40}$/.test(props.sourceSha)) {
      throw new Error('Expected a committed SHA');
    }
    const pullers = props.pullAccounts ?? [];
    if (pullers.some((p) => !/^[0-9]{12}$/.test(p))) {
      throw new Error('-c pullAccounts: 12-digit account IDs, comma-separated');
    }
    Tags.of(this).add('Project', 'colvenna-lake');
    Tags.of(this).add('Purpose', 'release');
    Tags.of(this).add('SourceSha', props.sourceSha);

    for (const [name, holds] of Object.entries(RELEASE_REPOSITORIES)) {
      // A construct's id and an output's name take no slash: charts/colvenna-catalog is ChartsColvennaCatalog.
      const id = name.replace(/\//g, '-');
      const repository = new ecr.Repository(this, `Repository-${id}`, {
        repositoryName: `${RELEASE_PREFIX}/${name}`,
        imageScanOnPush: true,
        imageTagMutability: ecr.TagMutability.IMMUTABLE,
        removalPolicy: RemovalPolicy.RETAIN,
      });
      if (pullers.length > 0) {
        repository.addToResourcePolicy(new iam.PolicyStatement({
          sid: 'ReleasePull',
          principals: pullers.map((p) => new iam.AccountPrincipal(p)),
          actions: ['ecr:BatchGetImage', 'ecr:GetDownloadUrlForLayer', 'ecr:BatchCheckLayerAvailability'],
        }));
      }
      new CfnOutput(this, `Repository${id.replace(/(^|-)(.)/g, (_, _dash, c: string) => c.toUpperCase())}`, {
        value: `${account}.dkr.ecr.${region}.amazonaws.com/${RELEASE_PREFIX}/${name}`,
        description: holds,
      });
    }
  }
}

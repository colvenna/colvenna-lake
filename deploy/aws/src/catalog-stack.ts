import {
  CfnOutput,
  Duration,
  RemovalPolicy,
  Stack,
  StackProps,
  Tags,
  aws_ecr as ecr,
  aws_iam as iam,
  aws_s3 as s3,
  aws_secretsmanager as secretsmanager,
} from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { envResource, isOidcIssuer, stackRegion } from './env';
import { SECRET_ROTATION, acknowledge, objectsUnder } from './nag';

export interface CatalogStackProps extends StackProps {
  bucketName: string;
  oidcIssuer: string;
  sourceSha: string;
  /** deploy/env.sh's COLVENNA_ENV. */
  environment: string;
}

/** Kubernetes identities the stack trusts; the Helm chart and flow scripts use the same names. */
export const CATALOG_NAMESPACE = 'colvenna-catalog';
export const CATALOG_SERVICE_ACCOUNT = 'colvenna-catalog';
export const ENGINE_NAMESPACE = 'colvenna-test-engines';
/** Doris 4.1.4 images run as root, so Doris gets its own namespace at Pod Security "baseline". */
export const DORIS_NAMESPACE = 'colvenna-doris';
/**
 * Engine identities. `output` names the stack output with the role ARN; only Flink keeps state
 * (checkpoints) in S3. None of them reaches table data: tables are read with vended credentials.
 */
export const ENGINE_SERVICE_ACCOUNTS = [
  { name: 'test-flink', namespace: ENGINE_NAMESPACE, output: 'FlinkEngineRoleArn', state: true },
  { name: 'test-spark', namespace: ENGINE_NAMESPACE, output: 'SparkEngineRoleArn', state: false },
  { name: 'doris', namespace: DORIS_NAMESPACE, output: 'DorisEngineRoleArn', state: false },
];

/**
 * AWS side of colvenna-catalog.
 *
 * - The server role is the catalog pod's IRSA identity: it reads and writes table metadata under
 *   the warehouse prefix and nothing else.
 * - The data role is what the catalog assumes, with a per-table session policy, to vend short-lived
 *   table credentials. It trusts only the catalog's service account and allows
 *   sessions up to 12 hours, because Paimon clients refresh any credential with less than one hour
 *   left and role chaining would cap sessions at one hour.
 * - Engine roles give flow pods S3 access only to Flink state, never to table data, so table reads
 *   must go through vended credentials and direct reads are denied.
 */
export class CatalogStack extends Stack {
  constructor(scope: Construct, id: string, props: CatalogStackProps) {
    super(scope, id, props);
    const region = stackRegion(props.env, 'The catalog stack');
    if (!/^[0-9a-f]{40}$/.test(props.sourceSha)
      || !new RegExp(`^colvennalake${props.environment}foundation-warehouse[0-9a-z-]+$`).test(props.bucketName)
      || !isOidcIssuer(props.oidcIssuer, region)) {
      throw new Error('Expected a committed SHA, the foundation warehouse bucket, and the EKS OIDC issuer');
    }
    const named = (name: string) => envResource(props.environment, name);

    Tags.of(this).add('Project', 'colvenna-lake');
    Tags.of(this).add('Environment', props.environment);
    Tags.of(this).add('Purpose', 'colvenna-catalog');
    Tags.of(this).add('SourceSha', props.sourceSha);

    const issuerPath = props.oidcIssuer.slice('https://'.length);
    const provider = iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(
      this,
      'EksOidcProvider',
      `arn:aws:iam::${this.account}:oidc-provider/${issuerPath}`,
    );
    const serviceAccount = (namespace: string, name: string) =>
      new iam.OpenIdConnectPrincipal(provider, {
        StringEquals: {
          [`${issuerPath}:aud`]: 'sts.amazonaws.com',
          [`${issuerPath}:sub`]: `system:serviceaccount:${namespace}:${name}`,
        },
      });
    const bucket = s3.Bucket.fromBucketName(this, 'Warehouse', props.bucketName);

    const repository = new ecr.Repository(this, 'CatalogImages', {
      repositoryName: named('colvenna-lake/colvenna-catalog'),
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      lifecycleRules: [{ maxImageCount: 10 }],
      removalPolicy: RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });

    const flowImages = new ecr.Repository(this, 'SparkFlowImages', {
      repositoryName: named('colvenna-lake/test-spark'),
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      lifecycleRules: [{ maxImageCount: 5 }],
      removalPolicy: RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });

    const warehouseActions = [
      's3:GetObject',
      's3:PutObject',
      's3:DeleteObject',
      's3:AbortMultipartUpload',
      's3:ListMultipartUploadParts',
    ];
    const grantWarehouse = (role: iam.Role) => {
      role.addToPolicy(new iam.PolicyStatement({
        actions: ['s3:GetBucketLocation'],
        resources: [bucket.bucketArn],
      }));
      role.addToPolicy(new iam.PolicyStatement({
        actions: ['s3:ListBucket', 's3:ListBucketMultipartUploads'],
        resources: [bucket.bucketArn],
        conditions: { StringLike: { 's3:prefix': ['warehouse', 'warehouse/*'] } },
      }));
      role.addToPolicy(new iam.PolicyStatement({
        actions: warehouseActions,
        resources: [bucket.arnForObjects('warehouse/*')],
      }));
    };

    const server = new iam.Role(this, 'ServerRole', {
      roleName: named('colvenna-lake-catalog'),
      description: 'colvenna-catalog pod identity: table metadata under the warehouse prefix',
      assumedBy: serviceAccount(CATALOG_NAMESPACE, CATALOG_SERVICE_ACCOUNT),
    });
    grantWarehouse(server);
    acknowledge(server, {
      id: 'AwsSolutions-IAM5',
      reason: 'Table metadata: the objects under the warehouse prefix only',
      appliesTo: [objectsUnder(props.bucketName, 'warehouse')],
    });

    const data = new iam.Role(this, 'DataRole', {
      roleName: named('colvenna-lake-catalog-data'),
      description: 'Assumed by colvenna-catalog with a per-table session policy to vend data tokens',
      assumedBy: serviceAccount(CATALOG_NAMESPACE, CATALOG_SERVICE_ACCOUNT),
      maxSessionDuration: Duration.hours(12),
    });
    grantWarehouse(data);
    acknowledge(data, {
      id: 'AwsSolutions-IAM5',
      reason: 'The ceiling for per-table data tokens, each narrowed by its session policy: '
        + 'the objects under the warehouse prefix only',
      appliesTo: [objectsUnder(props.bucketName, 'warehouse')],
    });

    for (const { name, namespace, output, state } of ENGINE_SERVICE_ACCOUNTS) {
      const role = new iam.Role(this, `EngineRole-${name}`, {
        roleName: named(`colvenna-lake-${name}`),
        description: state
          ? `${name} flow pods: Flink state only, no table data`
          : `${name} flow pods: no S3 access, tables only through vended credentials`,
        assumedBy: serviceAccount(namespace, name),
      });
      new CfnOutput(this, output, { value: role.roleArn, description: name });
      if (!state) {
        continue;
      }
      role.addToPolicy(new iam.PolicyStatement({
        actions: ['s3:GetBucketLocation'],
        resources: [bucket.bucketArn],
      }));
      role.addToPolicy(new iam.PolicyStatement({
        actions: ['s3:ListBucket'],
        resources: [bucket.bucketArn],
        conditions: { StringLike: { 's3:prefix': [`flink-state/test-engines/${name}`, `flink-state/test-engines/${name}/*`] } },
      }));
      role.addToPolicy(new iam.PolicyStatement({
        actions: warehouseActions,
        resources: [bucket.arnForObjects(`flink-state/test-engines/${name}/*`)],
      }));
      acknowledge(role, {
        id: 'AwsSolutions-IAM5',
        reason: 'Its own Flink state: the objects under its flink-state prefix only',
        appliesTo: [objectsUnder(props.bucketName, `flink-state/test-engines/${name}`)],
      });
    }

    const databaseSecret = new secretsmanager.Secret(this, 'CatalogDatabaseSecret', {
      secretName: named('colvenna-lake/catalog-db'),
      description: 'PostgreSQL role colvenna_catalog for the colvenna-catalog metadata database',
      generateSecretString: {
        secretStringTemplate: JSON.stringify({ username: 'colvenna_catalog', dbname: 'colvenna_catalog' }),
        generateStringKey: 'password',
        excludePunctuation: true,
        passwordLength: 40,
      },
      removalPolicy: RemovalPolicy.DESTROY,
    });
    acknowledge(databaseSecret, SECRET_ROTATION);

    new CfnOutput(this, 'CatalogRepositoryUri', { value: repository.repositoryUri });
    new CfnOutput(this, 'SparkRepositoryUri', { value: flowImages.repositoryUri });
    new CfnOutput(this, 'ServerRoleArn', { value: server.roleArn });
    new CfnOutput(this, 'DataRoleArn', { value: data.roleArn });
    new CfnOutput(this, 'CatalogDatabaseSecretArn', { value: databaseSecret.secretArn });
    new CfnOutput(this, 'SourceSha', { value: props.sourceSha });
  }
}

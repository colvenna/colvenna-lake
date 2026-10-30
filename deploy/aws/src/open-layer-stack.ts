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
} from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { envResource, isOidcIssuer, stackRegion } from './env';
import { acknowledge, objectsUnder } from './nag';

export interface OpenLayerStackProps extends StackProps {
  bucketName: string;
  oidcIssuer: string;
  sourceSha: string;
  /** deploy/env.sh's COLVENNA_ENV. */
  environment: string;
}

/** Polaris's namespace and service account, as deploy/scripts/open-layer/install-polaris.sh names them. */
export const POLARIS_NAMESPACE = 'colvenna-polaris';
export const POLARIS_SERVICE_ACCOUNT = 'polaris';

/**
 * AWS side of the open layer.
 *
 * - Polaris, the Iceberg REST catalog external readers query, has a pod identity that may do one
 *   thing: assume the read role, as its storage configuration names it.
 * - The read role is what Polaris assumes, with a per-table session policy, to read a published
 *   table's Iceberg metadata and to vend readers their credentials. It may only read the warehouse:
 *   nothing a reader is handed can write or delete Paimon's files.
 * - The reader image (Spark with Iceberg) is the acceptance runs' external reader.
 */
export class OpenLayerStack extends Stack {
  constructor(scope: Construct, id: string, props: OpenLayerStackProps) {
    super(scope, id, props);
    const region = stackRegion(props.env, 'The open layer stack');
    if (!/^[0-9a-f]{40}$/.test(props.sourceSha)
      || !new RegExp(`^colvennalake${props.environment}foundation-warehouse[0-9a-z-]+$`)
        .test(props.bucketName)
      || !isOidcIssuer(props.oidcIssuer, region)) {
      throw new Error('Expected a committed SHA, the foundation warehouse bucket, and the EKS OIDC issuer');
    }

    Tags.of(this).add('Project', 'colvenna-lake');
    Tags.of(this).add('Environment', props.environment);
    Tags.of(this).add('Purpose', 'open-layer');
    Tags.of(this).add('SourceSha', props.sourceSha);
    const named = (name: string) => envResource(props.environment, name);

    const issuerPath = props.oidcIssuer.slice('https://'.length);
    const provider = iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(
      this,
      'EksOidcProvider',
      `arn:aws:iam::${this.account}:oidc-provider/${issuerPath}`,
    );
    const bucket = s3.Bucket.fromBucketName(this, 'Warehouse', props.bucketName);

    const polaris = new iam.Role(this, 'PolarisRole', {
      roleName: named('colvenna-lake-polaris'),
      description: 'Polaris pod identity: assumes the Iceberg read role, nothing else',
      assumedBy: new iam.OpenIdConnectPrincipal(provider, {
        StringEquals: {
          [`${issuerPath}:aud`]: 'sts.amazonaws.com',
          [`${issuerPath}:sub`]:
            `system:serviceaccount:${POLARIS_NAMESPACE}:${POLARIS_SERVICE_ACCOUNT}`,
        },
      }),
    });

    const read = new iam.Role(this, 'IcebergReadRole', {
      roleName: named('colvenna-lake-iceberg-read'),
      description: 'Assumed by Polaris with a per-table session policy: reads the warehouse only',
      assumedBy: new iam.ArnPrincipal(polaris.roleArn),
      maxSessionDuration: Duration.hours(1),
    });
    read.assumeRolePolicy?.addStatements(new iam.PolicyStatement({
      actions: ['sts:TagSession'],
      principals: [new iam.ArnPrincipal(polaris.roleArn)],
    }));
    read.addToPolicy(new iam.PolicyStatement({
      actions: ['s3:GetBucketLocation'],
      resources: [bucket.bucketArn],
    }));
    read.addToPolicy(new iam.PolicyStatement({
      actions: ['s3:ListBucket'],
      resources: [bucket.bucketArn],
      conditions: { StringLike: { 's3:prefix': ['warehouse', 'warehouse/*'] } },
    }));
    read.addToPolicy(new iam.PolicyStatement({
      actions: ['s3:GetObject', 's3:GetObjectVersion'],
      resources: [bucket.arnForObjects('warehouse/*')],
    }));
    acknowledge(read, {
      id: 'AwsSolutions-IAM5',
      reason: 'The ceiling for per-table read credentials, each narrowed by its session policy: '
        + 'reads under the warehouse prefix only',
      appliesTo: [objectsUnder(props.bucketName, 'warehouse')],
    });
    polaris.addToPolicy(new iam.PolicyStatement({
      actions: ['sts:AssumeRole', 'sts:TagSession'],
      resources: [read.roleArn],
    }));

    const readerImages = new ecr.Repository(this, 'ReaderImages', {
      repositoryName: named('colvenna-lake/test-readers'),
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      lifecycleRules: [{ maxImageCount: 20 }],
      removalPolicy: RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });

    new CfnOutput(this, 'PolarisRoleArn', { value: polaris.roleArn });
    new CfnOutput(this, 'IcebergReadRoleArn', { value: read.roleArn });
    new CfnOutput(this, 'ReaderRepositoryUri', { value: readerImages.repositoryUri });
    new CfnOutput(this, 'WarehouseRoot', { value: `s3://${props.bucketName}/warehouse/` });
    new CfnOutput(this, 'SourceSha', { value: props.sourceSha });
  }
}

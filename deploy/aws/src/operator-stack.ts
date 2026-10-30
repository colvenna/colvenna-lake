import {
  CfnOutput,
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

export interface OperatorStackProps extends StackProps {
  bucketName: string;
  oidcIssuer: string;
  sourceSha: string;
  /** The chart's managedNamespaces, each given its own job role; default JOB_NAMESPACE alone. */
  managedNamespaces?: string[];
  /** deploy/env.sh's COLVENNA_ENV. */
  environment: string;
}

/**
 * The default managed namespace, where declarations go unless the install names others, and
 * the jobs' service account in every managed namespace, as deploy/scripts (JOB_NAMESPACE) and the
 * chart's jobs.serviceAccount default name them.
 */
export const JOB_NAMESPACE = 'colvenna-data';
export const JOB_SERVICE_ACCOUNT = 'colvenna-flink';

/** Each managed namespace's job role is named after it: envResource of this, and the namespace. */
export const JOB_ROLE_PREFIX = 'colvenna-lake-jobs-';
/** IAM's limit on a role name: what the prefix leaves of it is the namespace's. */
export const MAX_ROLE_NAME = 64;

/**
 * AWS side of colvenna-operator.
 *
 * - Images: the operator and the Flink jobs it deploys, tagged with the source SHA.
 * - Each managed namespace's jobs have their own role, trusted by that namespace's job service
 *   account only, which reaches only their Flink state (checkpoints, savepoints, HA metadata)
 *   under flink-state/<namespace>/. Table data goes through colvenna-catalog's per-table data
 *   tokens, so the roles have no warehouse access and a direct read is denied.
 * - The operator has no AWS role at all: it reads tables with its own catalog data tokens.
 */
export class OperatorStack extends Stack {
  constructor(scope: Construct, id: string, props: OperatorStackProps) {
    super(scope, id, props);
    const region = stackRegion(props.env, 'The operator stack');
    if (!/^[0-9a-f]{40}$/.test(props.sourceSha)
      || !new RegExp(`^colvennalake${props.environment}foundation-warehouse[0-9a-z-]+$`)
        .test(props.bucketName)
      || !isOidcIssuer(props.oidcIssuer, region)) {
      throw new Error('Expected a committed SHA, the foundation warehouse bucket, and the EKS OIDC issuer');
    }
    const namespaces = props.managedNamespaces ?? [JOB_NAMESPACE];
    const named = (name: string) => envResource(props.environment, name);
    const rolePrefix = named(JOB_ROLE_PREFIX);
    const longest = MAX_ROLE_NAME - rolePrefix.length;
    const label = /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/;
    if (namespaces.length === 0 || new Set(namespaces).size !== namespaces.length
      || namespaces.some((ns) => ns.length > longest || !label.test(ns))) {
      throw new Error(`Expected distinct namespace names of at most ${longest} characters`);
    }
    // A role may list its state root without the slash: Hadoop's S3A HEADs the root before it
    // lists it, and S3 answers that 404 rather than 403 only so. That listing also returns the
    // state of any namespace whose name starts with this one's.
    const prefixed = namespaces.find((a) => namespaces.some((b) => a !== b && b.startsWith(a)));
    if (prefixed !== undefined) {
      throw new Error(`No managed namespace may begin with another's name: ${prefixed}`);
    }

    Tags.of(this).add('Project', 'colvenna-lake');
    Tags.of(this).add('Environment', props.environment);
    Tags.of(this).add('Purpose', 'colvenna-operator');
    Tags.of(this).add('SourceSha', props.sourceSha);

    const issuerPath = props.oidcIssuer.slice('https://'.length);
    const provider = iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(
      this,
      'EksOidcProvider',
      `arn:aws:iam::${this.account}:oidc-provider/${issuerPath}`,
    );
    const bucket = s3.Bucket.fromBucketName(this, 'Warehouse', props.bucketName);

    const repository = (id: string, name: string) => new ecr.Repository(this, id, {
      repositoryName: name,
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      lifecycleRules: [{ maxImageCount: 20 }],
      removalPolicy: RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });
    const operatorImages = repository('OperatorImages', named('colvenna-lake/colvenna-operator'));
    const jobImages = repository('JobImages', named('colvenna-lake/colvenna-jobs'));

    const roleArns: Record<string, string> = {};
    for (const ns of namespaces) {
      const state = `flink-state/${ns}`;
      const role = new iam.Role(this, `JobRole-${ns}`, {
        roleName: `${rolePrefix}${ns}`,
        description: `colvenna-operator jobs in ${ns}: their Flink state only, no table data`,
        assumedBy: new iam.OpenIdConnectPrincipal(provider, {
          StringEquals: {
            [`${issuerPath}:aud`]: 'sts.amazonaws.com',
            [`${issuerPath}:sub`]: `system:serviceaccount:${ns}:${JOB_SERVICE_ACCOUNT}`,
          },
        }),
      });
      role.addToPolicy(new iam.PolicyStatement({
        actions: ['s3:GetBucketLocation'],
        resources: [bucket.bucketArn],
      }));
      role.addToPolicy(new iam.PolicyStatement({
        actions: ['s3:ListBucket'],
        resources: [bucket.bucketArn],
        conditions: { StringLike: { 's3:prefix': [state, `${state}/*`] } },
      }));
      role.addToPolicy(new iam.PolicyStatement({
        actions: [
          's3:GetObject',
          's3:PutObject',
          's3:DeleteObject',
          's3:AbortMultipartUpload',
          's3:ListMultipartUploadParts',
        ],
        resources: [bucket.arnForObjects(`${state}/*`)],
      }));
      acknowledge(role, {
        id: 'AwsSolutions-IAM5',
        reason: 'Its namespace\'s Flink state: the objects under its flink-state prefix only',
        appliesTo: [objectsUnder(props.bucketName, state)],
      });
      roleArns[ns] = role.roleArn;
    }

    new CfnOutput(this, 'OperatorRepositoryUri', { value: operatorImages.repositoryUri });
    new CfnOutput(this, 'JobRepositoryUri', { value: jobImages.repositoryUri });
    // The chart's jobs.roleArns: each managed namespace's role, by namespace (JSON).
    new CfnOutput(this, 'JobRoleArns', { value: this.toJsonString(roleArns) });
    new CfnOutput(this, 'CheckpointRoot', { value: `s3://${props.bucketName}/flink-state` });
    new CfnOutput(this, 'SourceSha', { value: props.sourceSha });
  }
}

import { Aspects, Stack } from 'aws-cdk-lib';
import { AwsSolutionsChecks, NagPackSuppression, NagSuppressions } from 'cdk-nag';
import { IConstruct } from 'constructs';

/**
 * cdk-nag's AWS Solutions rules on everything under the scope: app.ts applies them to the stack it
 * synthesizes, test/nag.test.ts to every stack the tests build. A finding not acknowledged, with
 * its reason, beside the resource it is about fails the synthesis, so the deploy too.
 */
export function nagChecks(scope: IConstruct): void {
  Aspects.of(scope).add(new AwsSolutionsChecks());
}

/** Acknowledges findings on a construct and everything under it, each with its reason. */
export function acknowledge(construct: IConstruct, ...findings: NagPackSuppression[]): void {
  NagSuppressions.addResourceSuppressions(construct, findings, true);
}

/** A literal string as it reads inside a regular expression. */
const literal = (text: string) => text.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&');

/** The IAM5 finding of a role reaching the objects under one prefix of the bucket, and no more. */
export function objectsUnder(bucketName: string, prefix: string): { regex: string } {
  return { regex: `/^Resource::arn:<AWS::Partition>:s3:::${literal(bucketName)}\\/${literal(prefix)}\\/\\*$/g` };
}

/**
 * What CDK makes itself to set log groups' retention (RDS's cloudwatchLogsRetention, logs.LogRetention):
 * one Lambda function per stack, with the AWS managed basic execution role and
 * logs:PutRetentionPolicy on any log group.
 */
export function acknowledgeLogRetention(stack: Stack): void {
  const singleton = stack.node.children.find((c) => c.node.id.startsWith('LogRetention'));
  if (!singleton) {
    return;
  }
  acknowledge(singleton,
    {
      id: 'AwsSolutions-IAM4',
      reason: 'CDK\'s log retention function: the AWS managed Lambda basic execution role',
      appliesTo: ['Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole'],
    },
    {
      id: 'AwsSolutions-IAM5',
      reason: 'CDK\'s log retention function sets the retention of log groups that RDS (and, in the '
        + 'foundation, EKS) create, named only at run time',
      appliesTo: ['Resource::*'],
    });
}

/** Reasons the design gives for the RDS rules, for an instance of the platform or a test source. */
export const RDS_DESIGN: NagPackSuppression[] = [
  {
    id: 'AwsSolutions-RDS3',
    reason: 'Single-AZ by design at this size (docs/manual/cost.md); a production deployment may choose Multi-AZ',
  },
  {
    id: 'AwsSolutions-RDS10',
    reason: 'Deletion leaves a final snapshot (RemovalPolicy.SNAPSHOT); '
      + 'deploy/uninstall.sh removes the environment whole, data included',
  },
  {
    id: 'AwsSolutions-RDS11',
    reason: 'The default port: its security group admits only the cluster, so another port would hide nothing',
  },
];

/** Accepted: an RDS instance's master secret has no automatic rotation (SMG4). */
export const MASTER_SECRET: NagPackSuppression = {
  id: 'AwsSolutions-SMG4',
  reason: 'Accepted risk: the master user is used only by the install scripts, to create each service\'s own '
    + 'database user; no service reads it',
};

/** Accepted: a test source's master secret has no automatic rotation (SMG4). */
export const TEST_SOURCE_MASTER_SECRET: NagPackSuppression = {
  id: 'AwsSolutions-SMG4',
  reason: 'Accepted risk: a test source\'s master user is used only by tests/fixtures/sources/sources.sh, '
    + 'to create the CDC login and load the fixtures\' SQL',
};

/** Accepted: a service's database secret has no automatic rotation (SMG4). */
export const SECRET_ROTATION: NagPackSuppression = {
  id: 'AwsSolutions-SMG4',
  reason: 'Accepted risk: the install scripts copy it into a Kubernetes Secret that the service reads at start, '
    + 'so it changes by rerunning the install, not by Secrets Manager rotation',
};

/** The AWS managed policies EKS requires of the cluster, node, kubectl handler and EBS CSI roles. */
export const EKS_MANAGED_POLICIES: NagPackSuppression = {
  id: 'AwsSolutions-IAM4',
  reason: 'The AWS managed policies EKS requires of the cluster, node group, kubectl handler and EBS CSI driver roles',
  appliesTo: [
    'Policy::arn:<AWS::Partition>:iam::aws:policy/AmazonEKSClusterPolicy',
    'Policy::arn:<AWS::Partition>:iam::aws:policy/AmazonEKSWorkerNodePolicy',
    'Policy::arn:<AWS::Partition>:iam::aws:policy/AmazonEKS_CNI_Policy',
    'Policy::arn:<AWS::Partition>:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly',
    'Policy::arn:<AWS::Partition>:iam::aws:policy/AmazonEC2ContainerRegistryPullOnly',
    'Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy',
    'Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole',
    'Policy::arn:<AWS::Partition>:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole',
    { regex: '/AmazonElasticContainerRegistryPublicReadOnly/' },
  ],
};

/** What CDK's EKS construct makes for itself: its cluster and kubectl providers. */
export const EKS_PROVIDERS: NagPackSuppression[] = [
  EKS_MANAGED_POLICIES,
  {
    id: 'AwsSolutions-IAM5',
    reason: 'CDK\'s EKS providers invoke their own functions, any version of them',
    appliesTo: [{ regex: '/^Resource::<[A-Za-z0-9]+\\.Arn>:\\*$/g' }],
  },
  {
    id: 'AwsSolutions-L1',
    reason: 'CDK\'s kubectl handler: its runtime goes with the CDK release and the kubectl layer',
  },
  { id: 'AwsSolutions-SF1', reason: 'CDK\'s cluster provider waiter only polls the cluster\'s creation' },
  { id: 'AwsSolutions-SF2', reason: 'CDK\'s cluster provider waiter only polls the cluster\'s creation' },
];

import {
  CfnResource,
  CfnOutput,
  Duration,
  RemovalPolicy,
  Stack,
  StackProps,
  Tags,
  aws_ec2 as ec2,
  aws_ecr as ecr,
  aws_eks as eks,
  aws_iam as iam,
  aws_logs as logs,
  aws_rds as rds,
  aws_s3 as s3,
} from 'aws-cdk-lib';
import { KubectlV36Layer } from '@aws-cdk/lambda-layer-kubectl-v36';
import { Construct } from 'constructs';
import { readFileSync } from 'node:fs';
import { isIP } from 'node:net';
import { join } from 'node:path';
import { envResource, isZoneOf, stackRegion } from './env';
// The cluster's Kubernetes version and the node groups' instance types, which the installer's
// preflight (deploy/scripts/lib/preflight.sh) also reads: it refuses a region or zones without them
// before anything is made.
import spec from './foundation-spec.json';
import {
  EKS_MANAGED_POLICIES, EKS_PROVIDERS, MASTER_SECRET, RDS_DESIGN, acknowledge, acknowledgeLogRetention,
} from './nag';

/**
 * deploy/scripts/lib/common.sh's DORIS_TAG, which every pinned query engine tag begins with (its
 * doris_tag), read from that one line as lib/preflight.sh reads the pinned digests there.
 */
export function dorisTag(): string {
  const common = readFileSync(join(__dirname, '../../scripts/lib/common.sh'), 'utf8');
  const tag = common.match(/^DORIS_TAG=([0-9A-Za-z._-]+)$/m)?.[1];
  if (!tag) {
    throw new Error('deploy/scripts/lib/common.sh sets no DORIS_TAG');
  }
  return tag;
}

export interface FoundationStackProps extends StackProps {
  adminPrincipalArn: string;
  operatorCidr: string;
  sourceSha: string;
  /** deploy/env.sh's COLVENNA_ENV. */
  environment: string;
  /**
   * The VPC's two availability zones, in the stack's region: given, not looked up, so that a new
   * region needs no cdk.context.json entry and a running VPC never moves (deploy.sh keeps them).
   */
  availabilityZones: string[];
  /** How many write and query nodes run (default 1 each; at most 4 and 3). */
  writeNodes?: number;
  queryNodes?: number;
}

const WAREHOUSE = 'warehouse/';

export class FoundationStack extends Stack {
  private readonly zones: string[];

  /** The given zones, never the context lookup Stack would make. */
  override get availabilityZones(): string[] {
    return this.zones;
  }

  constructor(scope: Construct, id: string, props: FoundationStackProps) {
    super(scope, id, props);
    const region = stackRegion(props.env, 'The foundation stack');
    if (props.availabilityZones.length !== 2
      || props.availabilityZones.some((zone) => !isZoneOf(zone, region))
      || props.availabilityZones[0] === props.availabilityZones[1]) {
      throw new Error(`availabilityZones must be two zones of ${region}`);
    }
    this.zones = props.availabilityZones;
    if (!new RegExp(`^arn:aws:iam::${props.env?.account}:(user|role)/[A-Za-z0-9+=,.@_/-]+$`).test(props.adminPrincipalArn)) {
      throw new Error(`adminPrincipalArn must be a principal of the stack's account, ${props.env?.account}`);
    }
    const [operatorAddress, prefix] = props.operatorCidr.split('/');
    if (prefix !== '32' || isIP(operatorAddress) !== 4) {
      throw new Error('operatorCidr must be one public IPv4 /32 address');
    }
    if (!/^[0-9a-f]{40}$/.test(props.sourceSha)) {
      throw new Error('sourceSha must be a 40-character Git commit SHA');
    }
    const writeNodes = props.writeNodes ?? 1;
    const queryNodes = props.queryNodes ?? 1;
    if (![1, 2, 3, 4].includes(writeNodes) || ![1, 2, 3].includes(queryNodes)) {
      throw new Error('writeNodes is 1 to 4, queryNodes 1 to 3');
    }
    Tags.of(this).add('Project', 'colvenna-lake');
    Tags.of(this).add('Environment', props.environment);
    Tags.of(this).add('SourceSha', props.sourceSha);

    const vpc = new ec2.Vpc(this, 'Vpc', {
      ipAddresses: ec2.IpAddresses.cidr('10.84.0.0/16'),
      availabilityZones: props.availabilityZones,
      natGateways: 1,
      subnetConfiguration: [
        { name: 'public', subnetType: ec2.SubnetType.PUBLIC, cidrMask: 24 },
        { name: 'compute', subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS, cidrMask: 21 },
        { name: 'database', subnetType: ec2.SubnetType.PRIVATE_ISOLATED, cidrMask: 24 },
      ],
    });
    vpc.addGatewayEndpoint('S3Endpoint', { service: ec2.GatewayVpcEndpointAwsService.S3 });
    // EKS rejects documentation-only CIDRs. An unassociated EIP gives the
    // public endpoint a valid source address that cannot originate traffic.
    const apiBootstrapAddress = new ec2.CfnEIP(this, 'ApiBootstrapAddress', {
      domain: 'vpc',
    });
    const bootstrapApiCidr = `${apiBootstrapAddress.attrPublicIp}/32`;

    // A year of the warehouse's S3 server access logs (who read or wrote which table files) and of
    // the VPC's flow logs (what the cluster talked to).
    const auditLogs = new s3.Bucket(this, 'AuditLogs', {
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      lifecycleRules: [{ expiration: Duration.days(365) }],
      removalPolicy: RemovalPolicy.RETAIN,
    });
    // The flow logs' delivery, granted here in the bucket policy CloudFormation owns: left to the
    // log service, it would rewrite that policy when the flow log is made (as AWS documents).
    const flowLogPrefix = 'vpc-flow/';
    const logDelivery = new iam.ServicePrincipal('delivery.logs.amazonaws.com');
    const fromThisAccountsLogs = {
      StringEquals: { 'aws:SourceAccount': this.account },
      ArnLike: { 'aws:SourceArn': this.formatArn({ service: 'logs', resource: '*' }) },
    };
    auditLogs.addToResourcePolicy(new iam.PolicyStatement({
      principals: [logDelivery],
      actions: ['s3:PutObject'],
      resources: [auditLogs.arnForObjects(`${flowLogPrefix}AWSLogs/${this.account}/*`)],
      conditions: {
        ...fromThisAccountsLogs,
        StringEquals: { ...fromThisAccountsLogs.StringEquals, 's3:x-amz-acl': 'bucket-owner-full-control' },
      },
    }));
    auditLogs.addToResourcePolicy(new iam.PolicyStatement({
      principals: [logDelivery],
      actions: ['s3:GetBucketAcl', 's3:ListBucket'],
      resources: [auditLogs.bucketArn],
      conditions: fromThisAccountsLogs,
    }));
    vpc.addFlowLog('FlowLog', {
      destination: ec2.FlowLogDestination.toS3(auditLogs, flowLogPrefix),
      trafficType: ec2.FlowLogTrafficType.ALL,
    });

    const bucket = new s3.Bucket(this, 'Warehouse', {
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      versioned: false,
      lifecycleRules: [{ abortIncompleteMultipartUploadAfter: Duration.days(7) }],
      removalPolicy: RemovalPolicy.RETAIN,
      autoDeleteObjects: false,
    });
    // Its S3 server access logs go to the audit log bucket, delivered under a bucket policy for the
    // logging service, as that bucket takes no ACLs (bucket-owner-enforced ownership).
    (bucket.node.defaultChild as s3.CfnBucket).loggingConfiguration = {
      destinationBucketName: auditLogs.bucketName,
      logFilePrefix: 'warehouse-access/',
    };
    auditLogs.addToResourcePolicy(new iam.PolicyStatement({
      principals: [new iam.ServicePrincipal('logging.s3.amazonaws.com')],
      actions: ['s3:PutObject'],
      resources: [auditLogs.arnForObjects('warehouse-access/*')],
      conditions: {
        ArnLike: { 'aws:SourceArn': bucket.bucketArn },
        StringEquals: { 'aws:SourceAccount': this.account },
      },
    }));
    // The S3 request metrics expose 5xx/503 independently from job and catalog health.
    (bucket.node.defaultChild as s3.CfnBucket).addPropertyOverride('MetricsConfigurations', [
      { Id: 'warehouse-requests', Prefix: WAREHOUSE },
      { Id: 'flink-state-requests', Prefix: 'flink-state/' },
    ]);
    // The engines' images: the Flink base the jobs build on, and the query engine's FE and BE.
    const imageRepository = (id: string, name: string) => new ecr.Repository(this, id, {
      repositoryName: envResource(props.environment, name),
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      emptyOnDelete: true,
      removalPolicy: RemovalPolicy.DESTROY,
    });
    const flinkImageRepository = imageRepository('FlinkImageRepository', 'colvenna-lake/flink-base');
    flinkImageRepository.addLifecycleRule({ maxImageCount: 5 });
    // The query engine: a modified build based on Apache Doris 4.1.4 (deploy/images/doris), so it is
    // distributed under a name of its own, not Doris's (release/trademarks.md). A pinned FE or BE,
    // tagged <DORIS_TAG>-fe-<digest prefix> or -be-<digest prefix> (deploy/scripts/lib/common.sh's
    // doris_tag), never expires; the rebuilds of deploy/scripts/doris/build-images.sh (tagged
    // build-<sha>-fe and -be) that are not pinned are kept to the last two. A pinned digest may be a
    // rebuild that keeps its build- tag too (#278): ECR selects an image by any one of its tags, and a
    // rule never expires an image an earlier rule selects, so the pinned tags' rules come first and
    // keep a thousand pinned digests each, more than will ever be pinned. They name DORIS_TAG itself:
    // no other tag that happens to hold "-fe-" is kept forever.
    const queryEngineImageRepository = imageRepository('QueryEngineImageRepository', 'colvenna-lake/query-engine');
    for (const component of ['fe', 'be']) {
      queryEngineImageRepository.addLifecycleRule({
        description: `the pinned ${component.toUpperCase()}s, kept`,
        tagPatternList: [`${dorisTag()}-${component}-*`],
        maxImageCount: 1000,
      });
    }
    queryEngineImageRepository.addLifecycleRule({ tagPrefixList: ['build-'], maxImageCount: 4 });
    // The third-party images the platform and the fixtures run (deploy/images/third-party.tsv),
    // copied by digest before anything runs them (the install's third-party step): the cluster
    // never pulls them from upstream, which may drop a digest. Each pinned digest is kept, tagged
    // <name>-<digest prefix>: no lifecycle rule.
    const thirdPartyImageRepository = imageRepository('ThirdPartyImageRepository', 'colvenna-lake/third-party');

    // The control plane's, the nodes' and the self-managed add-ons' Kubernetes version
    // (foundation-spec.json). Standard support for 1.36 ends 2027-08-02 (aws eks
    // describe-cluster-versions); deploy.sh prints the date. Raising it upgrades a running cluster in
    // place (docs/manual/install.md, 7), together with the kubectl layer of the same version.
    if (spec.kubernetesVersion !== '1.36') {
      throw new Error(`foundation-spec.json's Kubernetes ${spec.kubernetesVersion} needs its kubectl layer (KubectlV36Layer is 1.36)`);
    }
    const kubernetesVersion = eks.KubernetesVersion.of(spec.kubernetesVersion);
    // At the end of standard support the cluster stays on its version and Extended Support is
    // charged, rather than AWS upgrading the control plane alone to a version the platform has not
    // run on. The handler applies the policy only when it creates a cluster: deploy.sh sets it on
    // an existing one.
    const upgradeSupportType = 'EXTENDED';
    const cluster = new eks.Cluster(this, 'Cluster', {
      vpc,
      vpcSubnets: [{ subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS }],
      version: kubernetesVersion,
      kubectlLayer: new KubectlV36Layer(this, 'KubectlLayer'),
      defaultCapacity: 0,
      authenticationMode: eks.AuthenticationMode.API_AND_CONFIG_MAP,
      endpointAccess: eks.EndpointAccess.PUBLIC_AND_PRIVATE,
      clusterLogging: [
        eks.ClusterLoggingTypes.API,
        eks.ClusterLoggingTypes.AUDIT,
        eks.ClusterLoggingTypes.AUTHENTICATOR,
      ],
      tags: {
        Project: 'colvenna-lake',
        Environment: props.environment,
        SourceSha: props.sourceSha,
      },
    });
    // ClusterOptions does not expose publicAccessCidrs. With the pinned CDK
    // version, override the L2 cluster's underlying CloudFormation resource.
    const clusterResource = cluster.node.findChild('Resource').node.findChild('Resource')
      .node.findChild('Default') as CfnResource;
    clusterResource.addPropertyOverride(
      'Config.resourcesVpcConfig.publicAccessCidrs',
      [bootstrapApiCidr],
    );
    clusterResource.addPropertyOverride('Config.upgradePolicy', { supportType: upgradeSupportType });
    const adminAccess = new eks.CfnAccessEntry(this, 'AdminAccess', {
      clusterName: cluster.clusterName,
      principalArn: props.adminPrincipalArn,
      type: 'STANDARD',
      accessPolicies: [{
        accessScope: { type: 'cluster' },
        policyArn: 'arn:aws:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy',
      }],
    });
    adminAccess.node.addDependency(cluster);
    // EKS makes the control-plane log group itself and sets no retention. Once the cluster is up,
    // make the group if EKS has not yet, and keep it a year, as the entry's audit logs are. Deleting
    // the stack leaves the group; uninstall.sh deletes it.
    new logs.LogRetention(this, 'ClusterLogRetention', {
      logGroupName: `/aws/eks/${cluster.clusterName}/cluster`,
      retention: logs.RetentionDays.ONE_YEAR,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    // Kubernetes creates this binding itself, so patch the existing object.
    // Restoring anonymous access during stack deletion would open a gap before
    // the EKS endpoint disappears; keep the patch on deletion as well.
    const authenticatedPublicInfo = {
      metadata: {
        annotations: {
          'rbac.authorization.kubernetes.io/autoupdate': 'false',
          'colvenna-lake/source-sha': props.sourceSha,
          'colvenna-lake/operator-cidr': props.operatorCidr,
        },
      },
      subjects: [{
        apiGroup: 'rbac.authorization.k8s.io',
        kind: 'Group',
        name: 'system:authenticated',
      }],
    };
    new eks.KubernetesPatch(this, 'AuthenticatedPublicInfo', {
      cluster,
      resourceName: 'clusterrolebinding/system:public-info-viewer',
      patchType: eks.PatchType.MERGE,
      applyPatch: authenticatedPublicInfo,
      restorePatch: authenticatedPublicInfo,
      removalPolicy: RemovalPolicy.RETAIN,
    });

    const writeNodegroup = cluster.addNodegroupCapacity('WriteNodes', {
      instanceTypes: [new ec2.InstanceType(spec.writeInstanceType)],
      amiType: eks.NodegroupAmiType.AL2023_X86_64_STANDARD,
      diskSize: 80,
      minSize: 1,
      desiredSize: writeNodes,
      maxSize: 4,
      subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      labels: { 'colvenna-lake/role': 'write' },
    });
    const queryNodegroup = cluster.addNodegroupCapacity('QueryNodes', {
      instanceTypes: [new ec2.InstanceType(spec.queryInstanceType)],
      amiType: eks.NodegroupAmiType.AL2023_X86_64_STANDARD,
      diskSize: 100,
      minSize: 1,
      desiredSize: queryNodes,
      maxSize: 3,
      subnets: { subnetType: ec2.SubnetType.PRIVATE_WITH_EGRESS },
      labels: { 'colvenna-lake/role': 'query' },
    });
    // The node groups name no version: they are made at the cluster's, and after a control-plane
    // upgrade deploy.sh upgrades them. EKS refuses a node group's version update together with any
    // other change to it, and every deploy changes their SourceSha tag.

    const ebsCsi = cluster.addServiceAccount('EbsCsi', {
      name: 'ebs-csi-controller-sa',
      namespace: 'kube-system',
    });
    ebsCsi.role.addManagedPolicy(
      iam.ManagedPolicy.fromAwsManagedPolicyName('service-role/AmazonEBSCSIDriverPolicy'),
    );
    const ebsAddon = new eks.CfnAddon(this, 'EbsCsiAddon', {
      addonName: 'aws-ebs-csi-driver',
      addonVersion: 'v1.66.0-eksbuild.1',
      clusterName: cluster.clusterName,
      serviceAccountRoleArn: ebsCsi.role.roleArn,
      resolveConflicts: 'OVERWRITE',
    });
    ebsAddon.node.addDependency(ebsCsi);
    // The VPC CNI as a managed add-on with its network policy agent on: every NetworkPolicy the
    // platform installs (colvenna-catalog, Doris, Marquez, Polaris, monitoring) is enforced only so.
    new eks.CfnAddon(this, 'VpcCniAddon', {
      addonName: 'vpc-cni',
      addonVersion: 'v1.22.4-eksbuild.3',
      clusterName: cluster.clusterName,
      resolveConflicts: 'OVERWRITE',
      configurationValues: JSON.stringify({ enableNetworkPolicy: 'true' }),
    });
    // kube-proxy and CoreDNS, which EKS installs unmanaged and never upgrades, as managed add-ons of
    // the cluster's version, upgraded after the nodes (CoreDNS runs on them).
    const kubeProxyAddon = new eks.CfnAddon(this, 'KubeProxyAddon', {
      addonName: 'kube-proxy',
      addonVersion: 'v1.36.0-eksbuild.48',
      clusterName: cluster.clusterName,
      resolveConflicts: 'OVERWRITE',
    });
    const coreDnsAddon = new eks.CfnAddon(this, 'CoreDnsAddon', {
      addonName: 'coredns',
      addonVersion: 'v1.14.7-eksbuild.11',
      clusterName: cluster.clusterName,
      resolveConflicts: 'OVERWRITE',
    });
    for (const addon of [kubeProxyAddon, coreDnsAddon]) {
      addon.node.addDependency(writeNodegroup, queryNodegroup);
    }

    const database = new rds.DatabaseInstance(this, 'CatalogDatabase', {
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      engine: rds.DatabaseInstanceEngine.postgres({ version: rds.PostgresEngineVersion.VER_16 }),
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.SMALL),
      credentials: rds.Credentials.fromGeneratedSecret('colvenna_admin'),
      databaseName: 'paimon_catalog',
      allocatedStorage: 20,
      maxAllocatedStorage: 100,
      storageEncrypted: true,
      publiclyAccessible: false,
      backupRetention: Duration.days(1),
      deletionProtection: false,
      removalPolicy: RemovalPolicy.SNAPSHOT,
      cloudwatchLogsExports: ['postgresql'],
      cloudwatchLogsRetention: logs.RetentionDays.ONE_YEAR,
    });
    database.connections.allowDefaultPortFrom(cluster.clusterSecurityGroup);
    acknowledge(database, ...RDS_DESIGN, MASTER_SECRET);
    // Acknowledged once the cluster has all its parts: node groups, service accounts, providers.
    acknowledge(cluster,
      {
        id: 'AwsSolutions-EKS1',
        reason: 'The public endpoint admits only the installer\'s /32 (publicAccessCidrs, narrowed by deploy.sh), '
          + 'and every request needs IAM authentication',
      },
      {
        id: 'AwsSolutions-EKS2',
        reason: 'The api, audit and authenticator logs record who did what; '
          + 'controllerManager and scheduler logs are operational detail',
        appliesTo: ['LogExport::controllerManager', 'LogExport::scheduler'],
      },
      EKS_MANAGED_POLICIES);
    acknowledge(cluster.node.findChild('Resource'), {
      id: 'AwsSolutions-IAM5',
      reason: 'CDK\'s cluster creation role, as CDK\'s EKS construct writes it: '
        + 'EKS, IAM and EC2 actions on any resource',
      appliesTo: ['Resource::*'],
    });
    for (const provider of ['@aws-cdk--aws-eks.ClusterResourceProvider',
      '@aws-cdk--aws-eks.KubectlProvider']) {
      const made = this.node.tryFindChild(provider);
      if (made) {
        acknowledge(made, ...EKS_PROVIDERS);
      }
    }
    acknowledgeLogRetention(this);

    new CfnOutput(this, 'ClusterName', { value: cluster.clusterName });
    new CfnOutput(this, 'KubernetesVersion', { value: kubernetesVersion.version });
    new CfnOutput(this, 'UpgradeSupportType', { value: upgradeSupportType });
    new CfnOutput(this, 'BootstrapApiCidr', { value: bootstrapApiCidr });
    new CfnOutput(this, 'VpcId', { value: vpc.vpcId });
    new CfnOutput(this, 'WarehouseBucket', { value: bucket.bucketName });
    new CfnOutput(this, 'WarehouseUri', { value: `s3://${bucket.bucketName}/${WAREHOUSE}` });
    new CfnOutput(this, 'SourceSha', { value: props.sourceSha });
    new CfnOutput(this, 'FlinkImageRepositoryUri', {
      value: flinkImageRepository.repositoryUri,
    });
    new CfnOutput(this, 'QueryEngineImageRepositoryUri', {
      value: queryEngineImageRepository.repositoryUri,
    });
    new CfnOutput(this, 'ThirdPartyImageRepositoryUri', {
      value: thirdPartyImageRepository.repositoryUri,
    });
    new CfnOutput(this, 'CatalogEndpoint', { value: database.dbInstanceEndpointAddress });
    new CfnOutput(this, 'CatalogSecretArn', { value: database.secret!.secretArn });
  }
}

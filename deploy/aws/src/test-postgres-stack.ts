import {
  CfnOutput,
  Duration,
  RemovalPolicy,
  Stack,
  StackProps,
  Tags,
  aws_cloudwatch as cloudwatch,
  aws_ec2 as ec2,
  aws_logs as logs,
  aws_rds as rds,
  aws_secretsmanager as secretsmanager,
} from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { isZoneOf, stackRegion } from './env';
import {
  RDS_DESIGN, SECRET_ROTATION, TEST_SOURCE_MASTER_SECRET, acknowledge, acknowledgeLogRetention,
} from './nag';

export interface TestPostgresStackProps extends StackProps {
  sourceSha: string;
  /** deploy/env.sh's COLVENNA_ENV. */
  environment: string;
  vpcId: string;
  eksSecurityGroupId: string;
  catalogSubnetGroupName: string;
  isolatedSubnetIds: [string, string];
  isolatedSubnetAzs: [string, string];
  isolatedRouteTableIds: [string, string];
}

export class TestPostgresStack extends Stack {
  constructor(scope: Construct, id: string, props: TestPostgresStackProps) {
    super(scope, id, props);
    const region = stackRegion(props.env, 'The PostgreSQL test source stack');
    if (!/^[0-9a-f]{40}$/.test(props.sourceSha)
      || !/^vpc-[0-9a-f]+$/.test(props.vpcId)
      || !/^sg-[0-9a-f]+$/.test(props.eksSecurityGroupId)
      || !new RegExp(`^colvennalake${props.environment}foundation-catalogdatabasesubnetgroup[a-z0-9-]+$`)
        .test(props.catalogSubnetGroupName)
      || props.isolatedSubnetIds.some((subnet) => !/^subnet-[0-9a-f]+$/.test(subnet))
      || props.isolatedSubnetIds[0] === props.isolatedSubnetIds[1]
      || props.isolatedSubnetAzs.some((az) => !isZoneOf(az, region))
      || props.isolatedSubnetAzs[0] === props.isolatedSubnetAzs[1]
      || props.isolatedRouteTableIds.some((routeTable) => !/^rtb-[0-9a-f]+$/.test(routeTable))
      || props.isolatedRouteTableIds[0] === props.isolatedRouteTableIds[1]) {
      throw new Error('Source SHA and private foundation network attributes are required');
    }
    Tags.of(this).add('Project', 'colvenna-lake');
    Tags.of(this).add('Environment', props.environment);
    Tags.of(this).add('Purpose', 'acceptance-test-postgres-source');
    Tags.of(this).add('SourceSha', props.sourceSha);

    const vpc = ec2.Vpc.fromVpcAttributes(this, 'FoundationVpc', {
      vpcId: props.vpcId,
      availabilityZones: props.isolatedSubnetAzs,
      isolatedSubnetIds: props.isolatedSubnetIds,
      isolatedSubnetRouteTableIds: props.isolatedRouteTableIds,
    });
    const sourceSecurityGroup = new ec2.SecurityGroup(this, 'PostgresSourceSecurityGroup', {
      vpc,
      allowAllOutbound: false,
      description: 'Private PostgreSQL CDC source, reachable only from Colvenna Lake EKS',
    });
    sourceSecurityGroup.addIngressRule(
      ec2.Peer.securityGroupId(props.eksSecurityGroupId),
      ec2.Port.tcp(5432),
      'Authenticated TLS EKS CDC clients only',
    );
    const engine = rds.DatabaseInstanceEngine.postgres({
      version: rds.PostgresEngineVersion.VER_16_13,
    });
    const parameters = new rds.ParameterGroup(this, 'PostgresCdcParameters', {
      engine,
      parameters: {
        'rds.logical_replication': '1',
        'rds.force_ssl': '1',
        max_replication_slots: '10',
        max_wal_senders: '25',
        max_slot_wal_keep_size: '128',
      },
    });
    const database = new rds.DatabaseInstance(this, 'PostgresSource', {
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      subnetGroup: rds.SubnetGroup.fromSubnetGroupName(
        this, 'FoundationCatalogSubnetGroup', props.catalogSubnetGroupName,
      ),
      securityGroups: [sourceSecurityGroup],
      engine,
      parameterGroup: parameters,
      instanceType: ec2.InstanceType.of(ec2.InstanceClass.T4G, ec2.InstanceSize.SMALL),
      credentials: rds.Credentials.fromGeneratedSecret('colvenna_admin'),
      databaseName: 'source_db',
      allocatedStorage: 20,
      maxAllocatedStorage: 100,
      storageEncrypted: true,
      publiclyAccessible: false,
      backupRetention: Duration.days(1),
      deletionProtection: false,
      removalPolicy: RemovalPolicy.SNAPSHOT,
      cloudwatchLogsExports: ['postgresql', 'upgrade'],
      cloudwatchLogsRetention: logs.RetentionDays.ONE_YEAR,
    });
    new cloudwatch.Alarm(this, 'ReplicationSlotLagAlarm', {
      alarmDescription: 'Private PostgreSQL CDC slot WAL lag is nearing the 128 MiB retention limit',
      metric: new cloudwatch.Metric({
        namespace: 'AWS/RDS',
        metricName: 'OldestReplicationSlotLag',
        dimensionsMap: { DBInstanceIdentifier: database.instanceIdentifier },
        statistic: 'Maximum',
        period: Duration.minutes(1),
        unit: cloudwatch.Unit.BYTES,
      }),
      threshold: 64 * 1024 * 1024,
      evaluationPeriods: 1,
      treatMissingData: cloudwatch.TreatMissingData.NOT_BREACHING,
    });
    const cdcUserSecret = new secretsmanager.Secret(this, 'CdcUserSecret', {
      generateSecretString: {
        secretStringTemplate: JSON.stringify({ username: 'colvenna_pgcdc' }),
        generateStringKey: 'password',
        excludePunctuation: true,
        passwordLength: 32,
      },
    });

    acknowledge(database, ...RDS_DESIGN, TEST_SOURCE_MASTER_SECRET);
    acknowledge(cdcUserSecret, SECRET_ROTATION);
    acknowledgeLogRetention(this);

    new CfnOutput(this, 'SourceSha', { value: props.sourceSha });
    new CfnOutput(this, 'FoundationVpcId', { value: props.vpcId });
    new CfnOutput(this, 'FoundationCatalogSubnetGroupName', {
      value: props.catalogSubnetGroupName,
    });
    new CfnOutput(this, 'PostgresEndpoint', { value: database.dbInstanceEndpointAddress });
    new CfnOutput(this, 'PostgresAdminSecretArn', { value: database.secret!.secretArn });
    new CfnOutput(this, 'PostgresCdcSecretArn', { value: cdcUserSecret.secretArn });
  }
}

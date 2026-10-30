import {
  CfnOutput,
  Duration,
  RemovalPolicy,
  Stack,
  StackProps,
  Tags,
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

export interface TestMysqlStackProps extends StackProps {
  sourceSha: string;
  /** deploy/env.sh's COLVENNA_ENV. */
  environment: string;
  vpcId: string;
  eksSecurityGroupId: string;
  isolatedSubnetIds: [string, string];
  isolatedSubnetAzs: [string, string];
  isolatedRouteTableIds: [string, string];
}

export class TestMysqlStack extends Stack {
  constructor(scope: Construct, id: string, props: TestMysqlStackProps) {
    super(scope, id, props);
    const region = stackRegion(props.env, 'The MySQL test source stack');
    if (!/^[0-9a-f]{40}$/.test(props.sourceSha)
      || !/^vpc-[0-9a-f]+$/.test(props.vpcId)
      || !/^sg-[0-9a-f]+$/.test(props.eksSecurityGroupId)
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
    Tags.of(this).add('Purpose', 'acceptance-test-mysql-source');
    Tags.of(this).add('SourceSha', props.sourceSha);

    const vpc = ec2.Vpc.fromVpcAttributes(this, 'FoundationVpc', {
      vpcId: props.vpcId,
      availabilityZones: props.isolatedSubnetAzs,
      isolatedSubnetIds: props.isolatedSubnetIds,
      isolatedSubnetRouteTableIds: props.isolatedRouteTableIds,
    });
    const sourceSecurityGroup = new ec2.SecurityGroup(this, 'MySqlSourceSecurityGroup', {
      vpc,
      allowAllOutbound: false,
      description: 'Private MySQL CDC source, reachable only from Colvenna Lake EKS',
    });
    sourceSecurityGroup.addIngressRule(
      ec2.Peer.securityGroupId(props.eksSecurityGroupId),
      ec2.Port.tcp(3306),
      'Authenticated EKS CDC clients only',
    );
    // 8.4: 8.0's standard support ended 2026-07-31, and RDS Extended Support then cost more than the
    // instance. An environment installed on 8.0 is upgraded in place when this stack is deployed.
    const version = rds.MysqlEngineVersion.VER_8_4_10;
    const engine = rds.DatabaseInstanceEngine.mysql({ version });
    const parameters = new rds.ParameterGroup(this, 'MySqlCdcParameters', {
      engine,
      parameters: {
        binlog_format: 'ROW',
        binlog_row_image: 'FULL',
        // The source contract's triggers: with the binlog on, RDS MySQL 8.4 refuses them to the admin
        // user (ERROR 1419; it has SET_ANY_DEFINER but not SUPER) unless this is set. 8.0 allowed it.
        log_bin_trust_function_creators: '1',
      },
    });
    const database = new rds.DatabaseInstance(this, 'MySqlSource', {
      vpc,
      vpcSubnets: { subnetType: ec2.SubnetType.PRIVATE_ISOLATED },
      securityGroups: [sourceSecurityGroup],
      engine,
      parameterGroup: parameters,
      allowMajorVersionUpgrade: true,
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
      cloudwatchLogsExports: ['error', 'slowquery'],
      cloudwatchLogsRetention: logs.RetentionDays.ONE_YEAR,
    });
    const cdcUserSecret = new secretsmanager.Secret(this, 'CdcUserSecret', {
      generateSecretString: {
        secretStringTemplate: JSON.stringify({ username: 'colvenna_cdc' }),
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
    new CfnOutput(this, 'MySqlEndpoint', { value: database.dbInstanceEndpointAddress });
    new CfnOutput(this, 'MySqlEngineVersion', { value: version.mysqlFullVersion });
    new CfnOutput(this, 'MySqlAdminSecretArn', { value: database.secret!.secretArn });
    new CfnOutput(this, 'MySqlCdcSecretArn', { value: cdcUserSecret.secretArn });
  }
}

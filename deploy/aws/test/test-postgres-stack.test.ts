import assert from 'node:assert/strict';
import test from 'node:test';
import { App, assertions } from 'aws-cdk-lib';
import { TestPostgresStack } from '../src/test-postgres-stack';

const stack = new TestPostgresStack(new App(), 'PostgresSourceTest', {
  env: { account: '111122223333', region: 'us-west-2' },
  environment: 'uat',
  sourceSha: 'a'.repeat(40),
  vpcId: 'vpc-12345678',
  eksSecurityGroupId: 'sg-12345678',
  catalogSubnetGroupName: 'colvennalakeuatfoundation-catalogdatabasesubnetgroup-example',
  isolatedSubnetIds: ['subnet-11111111', 'subnet-22222222'],
  isolatedSubnetAzs: ['us-west-2a', 'us-west-2b'],
  isolatedRouteTableIds: ['rtb-11111111', 'rtb-22222222'],
});
const resources = assertions.Template.fromStack(stack).toJSON().Resources as Record<
  string,
  { Type: string; Properties: Record<string, any>; DeletionPolicy?: string }
>;

function only(type: string) {
  const matches = Object.values(resources).filter((resource) => resource.Type === type);
  assert.equal(matches.length, 1, `expected one ${type}`);
  return matches[0];
}

test('PostgreSQL source is encrypted, TLS-only and reachable only from EKS', () => {
  const database = only('AWS::RDS::DBInstance');
  assert.equal(database.Properties.Engine, 'postgres');
  assert.equal(database.Properties.EngineVersion, '16.13');
  assert.equal(database.Properties.PubliclyAccessible, false);
  assert.equal(database.Properties.StorageEncrypted, true);
  assert.equal(database.Properties.BackupRetentionPeriod, 1);
  assert.equal(database.DeletionPolicy, 'Snapshot');
  assert.equal(
    database.Properties.DBSubnetGroupName,
    'colvennalakeuatfoundation-catalogdatabasesubnetgroup-example',
  );
  assert.equal(
    Object.values(resources).filter((resource) => resource.Type === 'AWS::RDS::DBSubnetGroup').length,
    0,
  );
  const group = only('AWS::EC2::SecurityGroup');
  assert.equal(group.Properties.VpcId, 'vpc-12345678');
  assert.deepEqual(group.Properties.SecurityGroupIngress, [{
    Description: 'Authenticated TLS EKS CDC clients only',
    FromPort: 5432,
    IpProtocol: 'tcp',
    SourceSecurityGroupId: 'sg-12345678',
    ToPort: 5432,
  }]);
  assert.equal(JSON.stringify(group.Properties).includes('0.0.0.0/0'), false);
});

test('logical replication has bounded WAL, generated credentials and private image', () => {
  const parameters = only('AWS::RDS::DBParameterGroup');
  assert.equal(parameters.Properties.Family, 'postgres16');
  assert.deepEqual(parameters.Properties.Parameters, {
    'rds.logical_replication': '1',
    'rds.force_ssl': '1',
    max_replication_slots: '10',
    max_wal_senders: '25',
    max_slot_wal_keep_size: '128',
  });
  const secrets = Object.values(resources).filter((resource) =>
    resource.Type === 'AWS::SecretsManager::Secret');
  assert.equal(secrets.length, 2);
  assert.ok(secrets.every((secret) => secret.Properties.GenerateSecretString.GenerateStringKey
    === 'password'));
  assert.equal(Object.values(resources).some((resource) => resource.Type === 'AWS::ECR::Repository'), false);
  const alarm = only('AWS::CloudWatch::Alarm');
  assert.equal(alarm.Properties.MetricName, 'OldestReplicationSlotLag');
  assert.equal(alarm.Properties.Threshold, 64 * 1024 * 1024);
  assert.equal(alarm.Properties.TreatMissingData, 'notBreaching');
});

test('the source database logs are kept a year', () => {
  const retentions = Object.values(resources).filter((resource) => resource.Type === 'Custom::LogRetention');
  assert.deepEqual(retentions.map((resource) => resource.Properties.RetentionInDays), [365, 365]);
  assert.deepEqual(retentions.map((resource) => resource.Properties.LogGroupName['Fn::Join'][1].at(-1)),
    ['postgresql', 'upgrade'].map((kind) => `/${kind}`));
});

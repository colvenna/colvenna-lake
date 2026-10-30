import assert from 'node:assert/strict';
import test from 'node:test';
import { App, assertions } from 'aws-cdk-lib';
import { TestMysqlStack } from '../src/test-mysql-stack';

const stack = new TestMysqlStack(new App(), 'MySqlSourceTest', {
  env: { account: '111122223333', region: 'us-west-2' },
  environment: 'uat',
  sourceSha: 'a'.repeat(40),
  vpcId: 'vpc-12345678',
  eksSecurityGroupId: 'sg-12345678',
  isolatedSubnetIds: ['subnet-11111111', 'subnet-22222222'],
  isolatedSubnetAzs: ['us-west-2a', 'us-west-2b'],
  isolatedRouteTableIds: ['rtb-11111111', 'rtb-22222222'],
});
const template = assertions.Template.fromStack(stack).toJSON();
const resources = template.Resources as Record<
  string,
  { Type: string; Properties: Record<string, any>; DeletionPolicy?: string }
>;

function only(type: string) {
  const matches = Object.values(resources).filter((resource) => resource.Type === type);
  assert.equal(matches.length, 1, `expected one ${type}`);
  return matches[0];
}

test('MySQL source is encrypted in private subnets and reachable only from EKS', () => {
  const database = only('AWS::RDS::DBInstance');
  assert.equal(database.Properties.Engine, 'mysql');
  assert.equal(database.Properties.EngineVersion, '8.4.10');
  assert.equal(database.Properties.AllowMajorVersionUpgrade, true);
  assert.equal(template.Outputs.MySqlEngineVersion.Value, '8.4.10');
  assert.equal(database.Properties.PubliclyAccessible, false);
  assert.equal(database.Properties.StorageEncrypted, true);
  assert.equal(database.Properties.BackupRetentionPeriod, 1);
  assert.equal(database.DeletionPolicy, 'Snapshot');
  assert.deepEqual(only('AWS::RDS::DBSubnetGroup').Properties.SubnetIds, [
    'subnet-11111111', 'subnet-22222222',
  ]);
  const group = only('AWS::EC2::SecurityGroup');
  assert.equal(group.Properties.VpcId, 'vpc-12345678');
  assert.deepEqual(group.Properties.SecurityGroupIngress, [{
    Description: 'Authenticated EKS CDC clients only',
    FromPort: 3306,
    IpProtocol: 'tcp',
    SourceSecurityGroupId: 'sg-12345678',
    ToPort: 3306,
  }]);
  assert.equal(JSON.stringify(group.Properties).includes('0.0.0.0/0'), false);
});

test('CDC source has row binlog, generated credentials, and a private image repository', () => {
  const parameters = only('AWS::RDS::DBParameterGroup');
  assert.equal(parameters.Properties.Family, 'mysql8.4');
  assert.deepEqual(parameters.Properties.Parameters, {
    binlog_format: 'ROW',
    binlog_row_image: 'FULL',
    log_bin_trust_function_creators: '1',
  });
  const secrets = Object.values(resources).filter((resource) =>
    resource.Type === 'AWS::SecretsManager::Secret');
  assert.equal(secrets.length, 2);
  assert.ok(secrets.every((secret) => secret.Properties.GenerateSecretString.GenerateStringKey
    === 'password'));
  assert.equal(Object.values(resources).some((resource) => resource.Type === 'AWS::ECR::Repository'), false);
});

test('the source database logs are kept a year', () => {
  const retentions = Object.values(resources).filter((resource) => resource.Type === 'Custom::LogRetention');
  assert.deepEqual(retentions.map((resource) => resource.Properties.RetentionInDays), [365, 365]);
  assert.deepEqual(retentions.map((resource) => resource.Properties.LogGroupName['Fn::Join'][1].at(-1)),
    ['error', 'slowquery'].map((kind) => `/${kind}`));
});

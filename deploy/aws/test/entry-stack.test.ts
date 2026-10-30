import assert from 'node:assert/strict';
import test from 'node:test';
import { App, Duration, assertions } from 'aws-cdk-lib';
import { EntryStack, EntryStackProps } from '../src/entry-stack';

const props: EntryStackProps = {
  env: { account: '111122223333', region: 'us-west-2' },
  environment: 'uat',
  sourceSha: 'a'.repeat(40),
  oidcIssuer: 'https://oidc.eks.us-west-2.amazonaws.com/id/0123456789ABCDEF0123456789ABCDEF',
  vpcId: 'vpc-0123456789abcdef0',
  publicSubnetIds: ['subnet-0123456789abcdef0', 'subnet-0123456789abcdef1'],
  publicSubnetCidrs: ['10.84.0.0/24', '10.84.1.0/24'],
  availabilityZones: ['us-west-2a', 'us-west-2b'],
  clusterSecurityGroupId: 'sg-0123456789abcdef0',
  hostedZoneId: 'Z0EXAMPLE1234567890',
  hostedZoneName: 'example.com',
  domain: 'colvenna-uat.example.com',
  sessionTimeout: Duration.hours(8),
};
const stack = new EntryStack(new App(), 'EntryTest', props);
type Resource = { Type: string; Properties: Record<string, any> };
const template = assertions.Template.fromStack(stack).toJSON().Resources as Record<string, Resource>;
const resources = Object.values(template);
const ofType = (type: string) => resources.filter((r) => r.Type === type);
const idOf = (resource: Resource) => Object.keys(template).find((k) => template[k] === resource)!;
const role = (name: string) => ofType('AWS::IAM::Role').find((r) => r.Properties.RoleName === name)!;
/** The policy documents attached to the role of that name. */
const policies = (name: string) => JSON.stringify(ofType('AWS::IAM::Policy')
  .filter((p) => JSON.stringify(p.Properties.Roles).includes(`"${idOf(role(name))}"`))
  .map((p) => p.Properties.PolicyDocument));

test('the user pool has no self sign-up, requires TOTP MFA and a strong password', () => {
  const [pool] = ofType('AWS::Cognito::UserPool');
  assert.equal(pool.Properties.AdminCreateUserConfig.AllowAdminCreateUserOnly, true);
  assert.equal(pool.Properties.MfaConfiguration, 'ON');
  assert.deepEqual(pool.Properties.EnabledMfas, ['SOFTWARE_TOKEN_MFA']);
  assert.ok(pool.Properties.Policies.PasswordPolicy.MinimumLength >= 14);
  assert.equal(pool.Properties.DeletionProtection, 'ACTIVE');
  assert.equal(pool.Properties.UserPoolTier, 'PLUS');
  assert.equal(pool.Properties.UserPoolAddOns.AdvancedSecurityMode, 'AUDIT');
  const [delivery] = ofType('AWS::Cognito::LogDeliveryConfiguration');
  assert.equal(delivery.Properties.LogConfigurations[0].EventSource, 'userAuthEvents');
  const arn = JSON.stringify(delivery.Properties.LogConfigurations[0].CloudWatchLogsConfiguration.LogGroupArn);
  assert.match(arn, /:log-group:/);
  assert.doesNotMatch(arn, /:\*/);
  const groups = ofType('AWS::Cognito::UserPoolGroup').map((g) => g.Properties.GroupName).sort();
  assert.deepEqual(groups, ['colvenna-admin', 'colvenna-bi', 'colvenna-operator']);
});

test('the client has a secret, the code grant only, and five-minute access tokens', () => {
  const [client] = ofType('AWS::Cognito::UserPoolClient');
  const p = client.Properties;
  assert.equal(p.GenerateSecret, true);
  assert.deepEqual(p.AllowedOAuthFlows, ['code']);
  assert.equal(p.AccessTokenValidity, 5);
  assert.equal(p.IdTokenValidity, 5);
  assert.equal(p.TokenValidityUnits.AccessToken, 'minutes');
  assert.equal(p.EnableTokenRevocation, true);
  assert.equal(p.PreventUserExistenceErrors, 'ENABLED');
  // No password, SRP or custom flow of the API: signing in is the hosted UI's alone.
  assert.deepEqual(p.ExplicitAuthFlows, ['ALLOW_REFRESH_TOKEN_AUTH']);
  assert.deepEqual(p.CallbackURLs, ['https://console.colvenna-uat.example.com/oauth2/idpresponse',
    'https://bi.colvenna-uat.example.com/oauth2/idpresponse']);
  assert.deepEqual(p.LogoutURLs, ['https://console.colvenna-uat.example.com/', 'https://bi.colvenna-uat.example.com/']);
  assert.deepEqual(p.WriteAttributes, []);
});

test('the stack publishes the role mapping and the session cookie the applications use', () => {
  const outputs = assertions.Template.fromStack(stack).toJSON().Outputs;
  assert.deepEqual(JSON.parse(outputs.RoleGroups.Value),
    { 'colvenna-admin': 'admin', 'colvenna-operator': 'operator', 'colvenna-bi': 'bi' });
  assert.equal(outputs.consoleSessionCookie.Value, 'colvenna-console-auth');
  assert.equal(outputs.biSessionCookie.Value, 'colvenna-bi-auth');
  const rule = ofType('AWS::ElasticLoadBalancingV2::ListenerRule')[0];
  const auth = rule.Properties.Actions.find((a: { Type: string }) => a.Type === 'authenticate-cognito');
  assert.equal(auth.AuthenticateCognitoConfig.SessionCookieName, 'colvenna-console-auth');
});

test('the load balancer is public on TLS and redirects HTTP', () => {
  const [alb] = ofType('AWS::ElasticLoadBalancingV2::LoadBalancer');
  assert.equal(alb.Properties.Scheme, 'internet-facing');
  const attributes = Object.fromEntries(alb.Properties.LoadBalancerAttributes
    .map((a: { Key: string; Value: string }) => [a.Key, a.Value]));
  assert.equal(attributes['routing.http.drop_invalid_header_fields.enabled'], 'true');
  assert.equal(attributes['access_logs.s3.enabled'], 'true');
  const listeners = ofType('AWS::ElasticLoadBalancingV2::Listener');
  const http = listeners.find((l) => l.Properties.Port === 80)!;
  assert.equal(http.Properties.DefaultActions[0].Type, 'redirect');
  assert.equal(http.Properties.DefaultActions[0].RedirectConfig.Protocol, 'HTTPS');
  const https = listeners.find((l) => l.Properties.Port === 443)!;
  assert.equal(https.Properties.Protocol, 'HTTPS');
  assert.match(https.Properties.SslPolicy, /TLS13/);
  // Nothing is served by default: an unknown host gets a fixed 404.
  assert.deepEqual(https.Properties.DefaultActions.map((a: { Type: string }) => a.Type), ['fixed-response']);
  assert.equal(listeners.length, 2);
});

test('every rule signs in through Cognito before it forwards', () => {
  const rules = ofType('AWS::ElasticLoadBalancingV2::ListenerRule');
  assert.ok(rules.length >= 2);
  for (const rule of rules) {
    const types = [...rule.Properties.Actions]
      .sort((a: { Order: number }, b: { Order: number }) => a.Order - b.Order)
      .map((a: { Type: string }) => a.Type);
    assert.deepEqual(types, ['authenticate-cognito', 'forward']);
    const auth = rule.Properties.Actions.find((a: { Type: string }) => a.Type === 'authenticate-cognito');
    assert.equal(auth.AuthenticateCognitoConfig.OnUnauthenticatedRequest, 'authenticate');
    assert.equal(auth.AuthenticateCognitoConfig.SessionTimeout, 28800);
  }
});

test('only the load balancer reaches the pods, and only on the application port', () => {
  const ingress = ofType('AWS::EC2::SecurityGroupIngress')
    .filter((r) => r.Properties.GroupId === 'sg-0123456789abcdef0');
  assert.equal(ingress.length, 1);
  assert.equal(ingress[0].Properties.FromPort, 8443);
  assert.equal(ingress[0].Properties.ToPort, 8443);
  assert.ok(ingress[0].Properties.SourceSecurityGroupId);
  const groups = ofType('AWS::ElasticLoadBalancingV2::TargetGroup');
  for (const group of groups) {
    assert.equal(group.Properties.TargetType, 'ip');
    assert.equal(group.Properties.Protocol, 'HTTPS');
    assert.equal(group.Properties.Port, 8443);
  }
});

test('the access log bucket is private and refuses plain HTTP', () => {
  const [bucket] = ofType('AWS::S3::Bucket');
  const block = bucket.Properties.PublicAccessBlockConfiguration;
  assert.equal(block.BlockPublicAcls && block.BlockPublicPolicy && block.IgnorePublicAcls
    && block.RestrictPublicBuckets, true);
  const policy = JSON.stringify(ofType('AWS::S3::BucketPolicy')[0].Properties.PolicyDocument);
  assert.match(policy, /aws:SecureTransport/);
});

test('the controller can bind targets and describe, and cannot create or change load balancers', () => {
  const trust = JSON.stringify(role('colvenna-lake-uat-lbc').Properties.AssumeRolePolicyDocument);
  assert.match(trust, /system:serviceaccount:kube-system:aws-load-balancer-controller"/);
  const text = policies('colvenna-lake-uat-lbc');
  assert.match(text, /elasticloadbalancing:RegisterTargets/);
  assert.doesNotMatch(text, /Create|Delete|Modify|Authorize|Revoke|SetSecurityGroups|AddTags|:\*"/);
});

test('each application has its host, cookie and health check', () => {
  const rules = ofType('AWS::ElasticLoadBalancingV2::ListenerRule');
  const hosts = rules.map((r) => r.Properties.Conditions[0].HostHeaderConfig.Values[0]).sort();
  assert.deepEqual(hosts, ['bi.colvenna-uat.example.com', 'console.colvenna-uat.example.com']);
  const cookies = rules.map((r) => r.Properties.Actions
    .find((a: { Type: string }) => a.Type === 'authenticate-cognito').AuthenticateCognitoConfig.SessionCookieName).sort();
  assert.deepEqual(cookies, ['colvenna-bi-auth', 'colvenna-console-auth']);
  const health = ofType('AWS::ElasticLoadBalancingV2::TargetGroup').map((g) => g.Properties.HealthCheckPath).sort();
  assert.deepEqual(health, ['/health', '/healthz']);
});

test('the BI interface may sign users out and nothing else', () => {
  const trust = JSON.stringify(role('colvenna-lake-uat-bi').Properties.AssumeRolePolicyDocument);
  assert.match(trust, /system:serviceaccount:colvenna-bi:colvenna-bi"/);
  const text = policies('colvenna-lake-uat-bi');
  assert.match(text, /cognito-idp:AdminUserGlobalSignOut/);
  assert.doesNotMatch(text, /logs:|s3:|"Resource":"\*"/);
});

test('the console writes its audit and signs users out, nothing else', () => {
  const trust = JSON.stringify(role('colvenna-lake-uat-console').Properties.AssumeRolePolicyDocument);
  assert.match(trust, /system:serviceaccount:colvenna-console:colvenna-console"/);
  const text = policies('colvenna-lake-uat-console');
  assert.match(text, /logs:PutLogEvents/);
  assert.match(text, /cognito-idp:AdminUserGlobalSignOut/);
  assert.doesNotMatch(text, /s3:|cognito-idp:Admin(?!UserGlobalSignOut)|"[a-z0-9-]+:\*"|"Resource":"\*"/);
});

test('the stack refuses no concrete account, upper-case domains and out-of-range sessions', () => {
  assert.throws(() => new EntryStack(new App(), 'Elsewhere', {
    ...props, env: { region: 'us-west-2' },
  }), /needs a concrete AWS account/);
  assert.throws(() => new EntryStack(new App(), 'Upper', { ...props, domain: 'Colvenna-uat.example.com' }));
  assert.throws(() => new EntryStack(new App(), 'Outside', { ...props, domain: 'colvenna.example.org' }));
  assert.throws(() => new EntryStack(new App(), 'Long', { ...props, sessionTimeout: Duration.days(2) }));
});

test('another environment names its pool, domain, roles, logs and repositories apart', () => {
  const names = (environment: string) => {
    const t = assertions.Template.fromStack(new EntryStack(new App(), 'EntryEnv', {
      ...props, environment, domain: `colvenna-${environment}.example.com`,
    })).toJSON().Resources as Record<string, Resource>;
    const of = (type: string, key: string) => Object.values(t)
      .filter((r) => r.Type === type).map((r) => r.Properties[key]).sort();
    return {
      pool: of('AWS::Cognito::UserPool', 'UserPoolName'),
      client: of('AWS::Cognito::UserPoolClient', 'ClientName'),
      domain: of('AWS::Cognito::UserPoolDomain', 'Domain'),
      logs: of('AWS::Logs::LogGroup', 'LogGroupName'),
      roles: of('AWS::IAM::Role', 'RoleName').filter((n) => typeof n === 'string'),
      repositories: of('AWS::ECR::Repository', 'RepositoryName'),
    };
  };
  for (const environment of ['uat', 'prod']) {
    assert.deepEqual(names(environment), {
      pool: [`colvenna-lake-${environment}`],
      client: [`colvenna-lake-${environment}-entry`],
      domain: [`colvenna-lake-${environment}-111122223333`],
      logs: [`/aws/vendedlogs/colvenna-lake-${environment}/user-activity`,
        `/colvenna-lake-${environment}/console-audit`],
      roles: [`colvenna-lake-${environment}-bi`, `colvenna-lake-${environment}-console`,
        `colvenna-lake-${environment}-lbc`],
      repositories: [`colvenna-lake-${environment}/colvenna-bi`, `colvenna-lake-${environment}/colvenna-console`],
    });
  }
});

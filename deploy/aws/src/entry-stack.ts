import {
  ArnFormat,
  CfnOutput,
  Duration,
  RemovalPolicy,
  Stack,
  StackProps,
  Tags,
  aws_certificatemanager as acm,
  aws_cognito as cognito,
  aws_ec2 as ec2,
  aws_ecr as ecr,
  aws_elasticloadbalancingv2 as elbv2,
  aws_elasticloadbalancingv2_actions as actions,
  aws_iam as iam,
  aws_logs as logs,
  aws_route53 as route53,
  aws_route53_targets as targets,
  aws_s3 as s3,
} from 'aws-cdk-lib';
import { Construct } from 'constructs';
import { envResource, isOidcIssuer, isZoneOf, stackRegion } from './env';
import { acknowledge } from './nag';

export interface EntryStackProps extends StackProps {
  sourceSha: string;
  oidcIssuer: string;
  vpcId: string;
  /** The VPC's public subnets, one per availability zone, in the order of availabilityZones. */
  publicSubnetIds: string[];
  publicSubnetCidrs: string[];
  availabilityZones: string[];
  /** The EKS cluster security group, which the nodes and so the pods carry. */
  clusterSecurityGroupId: string;
  /** The parent zone the entry's names go into, e.g. example.com. */
  hostedZoneId: string;
  hostedZoneName: string;
  /** The entry's domain under the zone, e.g. colvenna-uat.example.com. */
  domain: string;
  /** How long a sign-in lasts at the load balancer before the user signs in again. */
  sessionTimeout: Duration;
  /** deploy/env.sh's COLVENNA_ENV. */
  environment: string;
}

/** The console's namespace and service account, as deploy/scripts/entry/install-console.sh names them. */
export const CONSOLE_NAMESPACE = 'colvenna-console';
export const CONSOLE_SERVICE_ACCOUNT = 'colvenna-console';
/** The BI interface's (deploy/scripts/entry/install-bi.sh). */
export const BI_NAMESPACE = 'colvenna-bi';
export const BI_SERVICE_ACCOUNT = 'colvenna-bi';
/** Where the load balancer controller runs (deploy/scripts/entry/install-lbc.sh). */
export const LBC_NAMESPACE = 'kube-system';
export const LBC_SERVICE_ACCOUNT = 'aws-load-balancer-controller';

/** The port every application behind the entry serves HTTPS on. */
export const APP_PORT = 8443;

/**
 * The entry's one definition of who may do what: each user pool group and the role it grants.
 * The applications get it as the stack's RoleGroups output.
 */
export const ROLE_GROUPS: Record<string, { role: string; description: string }> = {
  'colvenna-admin': { role: 'admin', description: 'Platform administrators' },
  'colvenna-operator': { role: 'operator', description: 'Operators: run the pipeline' },
  'colvenna-bi': { role: 'bi', description: 'BI users: query the lake through the BI interface' },
};

/**
 * The applications behind the entry: each a host under the domain with its own target group, the
 * load balancer's session cookie for it, and the path its health checks ask.
 */
export const APPS = [
  { name: 'console', host: 'console', cookie: 'colvenna-console-auth', health: '/healthz' },
  { name: 'bi', host: 'bi', cookie: 'colvenna-bi-auth', health: '/health' },
];

/**
 * The authenticated public entry of the platform's web interfaces.
 *
 * - An internet-facing Application Load Balancer takes TLS on a certificate for the entry's domain
 *   and redirects plain HTTP. Every request for an application's host signs in through Cognito
 *   first; any other host gets a fixed 404. The load balancer forwards to the pods (IP targets,
 *   HTTPS) and nothing else reaches them: the cluster security group admits the load balancer
 *   only on the application port, and the pods' NetworkPolicy admits only the public subnets.
 * - Cognito holds the users: no self sign-up, TOTP MFA required, a strong password policy. Its
 *   groups are the roles the applications grant. Threat protection, in audit mode, logs every
 *   sign-in and failed sign-in with the user to a log group. Access tokens last five minutes, so the load
 *   balancer refreshes a session every five minutes and a disabled or signed-out user loses it
 *   then.
 * - The access logs (every request, including those refused at the load balancer) go to a bucket;
 *   the applications write their own audit to a log group.
 * - The load balancer controller only binds the pods to the target groups the stack made: it may
 *   register and deregister targets and describe, nothing more, so no Ingress or Service can
 *   create a load balancer through it.
 */
export class EntryStack extends Stack {
  constructor(scope: Construct, id: string, props: EntryStackProps) {
    super(scope, id, props);
    const region = stackRegion(props.env, 'The entry stack');
    if (!/^[0-9a-f]{40}$/.test(props.sourceSha)
      || !isOidcIssuer(props.oidcIssuer, region)
      || props.availabilityZones.some((zone) => !isZoneOf(zone, region))
      || !/^vpc-[0-9a-f]+$/.test(props.vpcId)
      || !/^sg-[0-9a-f]+$/.test(props.clusterSecurityGroupId)
      || props.publicSubnetIds.length < 2
      || props.publicSubnetIds.length !== props.availabilityZones.length
      || props.publicSubnetIds.length !== props.publicSubnetCidrs.length
      || !props.domain.endsWith(`.${props.hostedZoneName}`)
      || props.domain !== props.domain.toLowerCase()) {
      throw new Error('Expected a committed SHA, the EKS OIDC issuer, the VPC with two or more public '
        + 'subnets, the cluster security group, and a lowercase domain under the zone');
    }
    const timeout = props.sessionTimeout.toSeconds();
    if (timeout < 60 || timeout > Duration.days(1).toSeconds()) {
      throw new Error('The session timeout is between one minute and one day');
    }

    Tags.of(this).add('Project', 'colvenna-lake');
    Tags.of(this).add('Environment', props.environment);
    Tags.of(this).add('Purpose', 'web-entry');
    Tags.of(this).add('SourceSha', props.sourceSha);
    const named = (name: string) => envResource(props.environment, name);

    const urlOf = (app: { host: string }) => `https://${app.host}.${props.domain}`;

    // Users and roles.
    const pool = new cognito.UserPool(this, 'Users', {
      userPoolName: named('colvenna-lake'),
      // Plus, for threat protection's user activity log: who signed in or failed to, from where.
      featurePlan: cognito.FeaturePlan.PLUS,
      standardThreatProtectionMode: cognito.StandardThreatProtectionMode.AUDIT_ONLY,
      selfSignUpEnabled: false,
      signInAliases: { username: true },
      signInCaseSensitive: false,
      standardAttributes: { email: { required: false, mutable: true } },
      mfa: cognito.Mfa.REQUIRED,
      mfaSecondFactor: { otp: true, sms: false },
      passwordPolicy: {
        minLength: 14,
        requireLowercase: true,
        requireUppercase: true,
        requireDigits: true,
        requireSymbols: true,
        tempPasswordValidity: Duration.days(3),
      },
      accountRecovery: cognito.AccountRecovery.EMAIL_ONLY,
      deletionProtection: true,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    // Every sign-in, successful or not, with the user, the outcome and the address it came from.
    const userActivity = new logs.LogGroup(this, 'UserActivity', {
      logGroupName: named('/aws/vendedlogs/colvenna-lake/user-activity'),
      retention: logs.RetentionDays.ONE_YEAR,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    new cognito.CfnLogDeliveryConfiguration(this, 'UserActivityExport', {
      userPoolId: pool.userPoolId,
      logConfigurations: [{
        eventSource: 'userAuthEvents',
        logLevel: 'INFO',
        // The log group's ARN itself: logGroupArn ends in ':*', which Cognito refuses.
        cloudWatchLogsConfiguration: {
          logGroupArn: this.formatArn({
            service: 'logs',
            resource: 'log-group',
            resourceName: userActivity.logGroupName,
            arnFormat: ArnFormat.COLON_RESOURCE_NAME,
          }),
        },
      }],
    });
    for (const [group, { description }] of Object.entries(ROLE_GROUPS)) {
      new cognito.CfnUserPoolGroup(this, `Group-${group}`, {
        userPoolId: pool.userPoolId,
        groupName: group,
        description,
      });
    }
    // The sign-in pages: Cognito's own domain and its classic hosted UI.
    const domain = pool.addDomain('SignIn', {
      cognitoDomain: { domainPrefix: named(`colvenna-lake-${this.account}`) },
      managedLoginVersion: cognito.ManagedLoginVersion.CLASSIC_HOSTED_UI,
    });
    // One client for every application. Only the load balancer holds its secret, and signing in
    // goes through the hosted UI alone: no password or SRP flow of the API is open.
    const client = pool.addClient('Entry', {
      userPoolClientName: named('colvenna-lake-entry'),
      generateSecret: true,
      oAuth: {
        flows: { authorizationCodeGrant: true },
        scopes: [cognito.OAuthScope.OPENID, cognito.OAuthScope.EMAIL, cognito.OAuthScope.PROFILE],
        callbackUrls: APPS.map((app) => `${urlOf(app)}/oauth2/idpresponse`),
        // Signing out returns to the application's root, which signs in again.
        logoutUrls: APPS.map((app) => `${urlOf(app)}/`),
      },
      supportedIdentityProviders: [cognito.UserPoolClientIdentityProvider.COGNITO],
      accessTokenValidity: Duration.minutes(5),
      idTokenValidity: Duration.minutes(5),
      refreshTokenValidity: Duration.hours(12),
      enableTokenRevocation: true,
      preventUserExistenceErrors: true,
      // No one changes their own attributes through the client.
      writeAttributes: new cognito.ClientAttributes(),
    });
    // Left unset, Cognito would open the SRP and custom flows; only token refresh stays.
    (client.node.defaultChild as cognito.CfnUserPoolClient).explicitAuthFlows = ['ALLOW_REFRESH_TOKEN_AUTH'];

    // Name and certificate.
    const zone = route53.HostedZone.fromHostedZoneAttributes(this, 'Zone', {
      hostedZoneId: props.hostedZoneId,
      zoneName: props.hostedZoneName,
    });
    const certificate = new acm.Certificate(this, 'Certificate', {
      domainName: `*.${props.domain}`,
      validation: acm.CertificateValidation.fromDns(zone),
    });

    // The load balancer.
    const vpc = ec2.Vpc.fromVpcAttributes(this, 'Vpc', {
      vpcId: props.vpcId,
      availabilityZones: props.availabilityZones,
      publicSubnetIds: props.publicSubnetIds,
      publicSubnetIpv4CidrBlocks: props.publicSubnetCidrs,
    });
    const albSecurityGroup = new ec2.SecurityGroup(this, 'EntrySecurityGroup', {
      vpc,
      description: 'colvenna-lake web entry: HTTPS and HTTP from anywhere; out to the pods and to Cognito',
      allowAllOutbound: false,
    });
    albSecurityGroup.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(443), 'HTTPS');
    albSecurityGroup.addIngressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(80), 'HTTP, redirected');
    acknowledge(albSecurityGroup, {
      id: 'AwsSolutions-EC23',
      reason: 'The public web entry: 443, and 80 that only redirects to it; '
        + 'every request is signed in through Cognito, with MFA, before it is forwarded',
    });
    // The authenticate action calls Cognito's token and user info endpoints.
    albSecurityGroup.addEgressRule(ec2.Peer.anyIpv4(), ec2.Port.tcp(443), 'Cognito endpoints');
    const cluster = ec2.SecurityGroup.fromSecurityGroupId(this, 'ClusterSecurityGroup',
      props.clusterSecurityGroupId, { mutable: false });
    albSecurityGroup.addEgressRule(cluster, ec2.Port.tcp(APP_PORT), 'Application pods');
    new ec2.CfnSecurityGroupIngress(this, 'PodsFromEntry', {
      groupId: props.clusterSecurityGroupId,
      ipProtocol: 'tcp',
      fromPort: APP_PORT,
      toPort: APP_PORT,
      sourceSecurityGroupId: albSecurityGroup.securityGroupId,
      description: 'colvenna-lake web entry to the application pods',
    });

    const accessLogs = new s3.Bucket(this, 'AccessLogs', {
      // Load balancer access logs support SSE-S3 only.
      encryption: s3.BucketEncryption.S3_MANAGED,
      blockPublicAccess: s3.BlockPublicAccess.BLOCK_ALL,
      enforceSSL: true,
      objectOwnership: s3.ObjectOwnership.BUCKET_OWNER_ENFORCED,
      lifecycleRules: [{ expiration: Duration.days(365) }],
      removalPolicy: RemovalPolicy.RETAIN,
    });
    acknowledge(accessLogs, {
      id: 'AwsSolutions-S1',
      reason: 'The load balancer\'s access log bucket itself: its own access logs would only log the logging',
    });
    const alb = new elbv2.ApplicationLoadBalancer(this, 'Entry', {
      vpc,
      internetFacing: true,
      vpcSubnets: { subnets: vpc.publicSubnets },
      securityGroup: albSecurityGroup,
      dropInvalidHeaderFields: true,
      desyncMitigationMode: elbv2.DesyncMitigationMode.STRICTEST,
      idleTimeout: Duration.seconds(120),
    });
    alb.logAccessLogs(accessLogs, 'entry');

    alb.addListener('Http', {
      port: 80,
      open: false,
      defaultAction: elbv2.ListenerAction.redirect({ protocol: 'HTTPS', port: '443', permanent: true }),
    });
    const https = alb.addListener('Https', {
      port: 443,
      open: false,
      certificates: [certificate],
      sslPolicy: elbv2.SslPolicy.TLS13_RES,
      // A host the entry does not serve gets nothing.
      defaultAction: elbv2.ListenerAction.fixedResponse(404, {
        contentType: 'text/plain',
        messageBody: 'Not found',
      }),
    });

    const targetGroups: Record<string, elbv2.ApplicationTargetGroup> = {};
    APPS.forEach((app, index) => {
      const group = new elbv2.ApplicationTargetGroup(this, `Targets-${app.name}`, {
        vpc,
        targetType: elbv2.TargetType.IP,
        protocol: elbv2.ApplicationProtocol.HTTPS,
        port: APP_PORT,
        deregistrationDelay: Duration.seconds(30),
        healthCheck: {
          protocol: elbv2.Protocol.HTTPS,
          path: app.health,
          healthyHttpCodes: '200',
          interval: Duration.seconds(15),
          healthyThresholdCount: 2,
          unhealthyThresholdCount: 3,
        },
      });
      targetGroups[app.name] = group;
      https.addAction(`Route-${app.name}`, {
        priority: 10 * (index + 1),
        conditions: [elbv2.ListenerCondition.hostHeaders([`${app.host}.${props.domain}`])],
        action: new actions.AuthenticateCognitoAction({
          userPool: pool,
          userPoolClient: client,
          userPoolDomain: domain,
          scope: 'openid email profile',
          sessionCookieName: app.cookie,
          sessionTimeout: props.sessionTimeout,
          onUnauthenticatedRequest: elbv2.UnauthenticatedAction.AUTHENTICATE,
          next: elbv2.ListenerAction.forward([group]),
        }),
      });
      new route53.ARecord(this, `Name-${app.name}`, {
        zone,
        recordName: `${app.host}.${props.domain}.`,
        target: route53.RecordTarget.fromAlias(new targets.LoadBalancerTarget(alb)),
      });
    });

    // Pod identities.
    const issuerPath = props.oidcIssuer.slice('https://'.length);
    const provider = iam.OpenIdConnectProvider.fromOpenIdConnectProviderArn(this, 'EksOidcProvider',
      `arn:aws:iam::${this.account}:oidc-provider/${issuerPath}`);
    const podRole = (id: string, roleName: string, description: string, namespace: string,
      serviceAccount: string) => new iam.Role(this, id, {
      roleName,
      description,
      assumedBy: new iam.OpenIdConnectPrincipal(provider, {
        StringEquals: {
          [`${issuerPath}:aud`]: 'sts.amazonaws.com',
          [`${issuerPath}:sub`]: `system:serviceaccount:${namespace}:${serviceAccount}`,
        },
      }),
    });

    const lbc = podRole('LoadBalancerControllerRole', named('colvenna-lake-lbc'),
      'AWS Load Balancer Controller: binds pods to the entry target groups, nothing else',
      LBC_NAMESPACE, LBC_SERVICE_ACCOUNT);
    lbc.addToPolicy(new iam.PolicyStatement({
      actions: [
        'elasticloadbalancing:DescribeLoadBalancers',
        'elasticloadbalancing:DescribeListeners',
        'elasticloadbalancing:DescribeTargetGroups',
        'elasticloadbalancing:DescribeTargetGroupAttributes',
        'elasticloadbalancing:DescribeTargetHealth',
        'elasticloadbalancing:DescribeTags',
        'ec2:DescribeVpcs',
        'ec2:DescribeSubnets',
        'ec2:DescribeAvailabilityZones',
        'ec2:DescribeInstances',
        'ec2:DescribeNetworkInterfaces',
        'ec2:DescribeSecurityGroups',
      ],
      resources: ['*'],
    }));
    acknowledge(lbc, {
      id: 'AwsSolutions-IAM5',
      reason: 'Describe calls only, which take no resource-level permissions; '
        + 'it registers targets in the entry\'s target groups only',
      appliesTo: ['Resource::*'],
    });
    lbc.addToPolicy(new iam.PolicyStatement({
      actions: ['elasticloadbalancing:RegisterTargets', 'elasticloadbalancing:DeregisterTargets'],
      resources: Object.values(targetGroups).map((group) => group.targetGroupArn),
    }));

    const audit = new logs.LogGroup(this, 'ConsoleAudit', {
      logGroupName: named('/colvenna-lake/console-audit'),
      retention: logs.RetentionDays.ONE_YEAR,
      removalPolicy: RemovalPolicy.RETAIN,
    });
    const consoleRole = podRole('ConsoleRole', named('colvenna-lake-console'),
      'colvenna-console: writes its audit and signs users out', CONSOLE_NAMESPACE, CONSOLE_SERVICE_ACCOUNT);
    consoleRole.addToPolicy(new iam.PolicyStatement({
      actions: ['logs:CreateLogStream', 'logs:PutLogEvents'],
      resources: [`${audit.logGroupArn}:*`],
    }));
    acknowledge(consoleRole, {
      id: 'AwsSolutions-IAM5',
      reason: 'The log streams of its own audit log group only',
      appliesTo: [{ regex: '/^Resource::<ConsoleAudit[A-Za-z0-9]*\\.Arn>:\\*$/g' }],
    });
    // Signing out ends the user's refresh tokens, so a copied session cookie stops working when
    // its access token expires.
    consoleRole.addToPolicy(new iam.PolicyStatement({
      actions: ['cognito-idp:AdminUserGlobalSignOut'],
      resources: [pool.userPoolArn],
    }));

    const biRole = podRole('BiRole', named('colvenna-lake-bi'), 'colvenna-bi (Superset): signs users out',
      BI_NAMESPACE, BI_SERVICE_ACCOUNT);
    biRole.addToPolicy(new iam.PolicyStatement({
      actions: ['cognito-idp:AdminUserGlobalSignOut'],
      resources: [pool.userPoolArn],
    }));
    const biImages = new ecr.Repository(this, 'BiImages', {
      repositoryName: named('colvenna-lake/colvenna-bi'),
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      lifecycleRules: [{ maxImageCount: 20 }],
      removalPolicy: RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });

    const consoleImages = new ecr.Repository(this, 'ConsoleImages', {
      repositoryName: named('colvenna-lake/colvenna-console'),
      imageScanOnPush: true,
      imageTagMutability: ecr.TagMutability.IMMUTABLE,
      lifecycleRules: [{ maxImageCount: 20 }],
      removalPolicy: RemovalPolicy.DESTROY,
      emptyOnDelete: true,
    });

    new CfnOutput(this, 'UserPoolId', { value: pool.userPoolId });
    new CfnOutput(this, 'RoleGroups', {
      value: JSON.stringify(Object.fromEntries(Object.entries(ROLE_GROUPS).map(([g, { role }]) => [g, role]))),
    });
    new CfnOutput(this, 'UserPoolClientId', { value: client.userPoolClientId });
    new CfnOutput(this, 'Issuer', { value: `https://cognito-idp.${this.region}.amazonaws.com/${pool.userPoolId}` });
    new CfnOutput(this, 'SignInDomain', { value: domain.baseUrl() });
    new CfnOutput(this, 'LoadBalancerArn', { value: alb.loadBalancerArn });
    new CfnOutput(this, 'LoadBalancerDnsName', { value: alb.loadBalancerDnsName });
    for (const app of APPS) {
      new CfnOutput(this, `${app.name}TargetGroupArn`, { value: targetGroups[app.name].targetGroupArn });
      new CfnOutput(this, `${app.name}Url`, { value: urlOf(app) });
      new CfnOutput(this, `${app.name}SessionCookie`, { value: app.cookie });
    }
    new CfnOutput(this, 'PublicSubnetCidrs', { value: props.publicSubnetCidrs.join(',') });
    new CfnOutput(this, 'AccessLogBucket', { value: accessLogs.bucketName });
    new CfnOutput(this, 'ConsoleAuditLogGroup', { value: audit.logGroupName });
    new CfnOutput(this, 'UserActivityLogGroup', { value: userActivity.logGroupName });
    new CfnOutput(this, 'ConsoleRoleArn', { value: consoleRole.roleArn });
    new CfnOutput(this, 'LoadBalancerControllerRoleArn', { value: lbc.roleArn });
    new CfnOutput(this, 'ConsoleRepositoryUri', { value: consoleImages.repositoryUri });
    new CfnOutput(this, 'BiRoleArn', { value: biRole.roleArn });
    new CfnOutput(this, 'BiRepositoryUri', { value: biImages.repositoryUri });
    new CfnOutput(this, 'SessionTimeoutSeconds', { value: String(timeout) });
    new CfnOutput(this, 'SourceSha', { value: props.sourceSha });
  }
}

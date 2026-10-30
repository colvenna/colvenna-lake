import json
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "security"))
import exposure  # noqa: E402

VPC = "vpc-env"
# The entry's load balancer ID, as AWS makes it: 16 hex digits.
ALB_ID = "0123456789abcdef"
ALB_ARN = "arn:aws:elasticloadbalancing:us-west-2:111:loadbalancer/app/Entry-x/" + ALB_ID
DENY_HTTP = {"Statement": [{"Effect": "Deny", "Principal": {"AWS": "*"}, "Action": "s3:*",
                            "Condition": {"Bool": {"aws:SecureTransport": "false"}}}]}
# The inventory's id in raw/env.json, and a denial run's id: the verdict rows that count.
INVENTORY = "inventory-20261022T000000Z-abc123"
RUN = "r1"
BPA = {"PublicAccessBlockConfiguration": {"BlockPublicAcls": True, "IgnorePublicAcls": True,
                                          "BlockPublicPolicy": True, "RestrictPublicBuckets": True}}


def read_json(path):
    with open(path) as f:
        return json.load(f)


class Raw:
    """A raw/ directory as inventory.sh writes it: one clean environment, plus others of the account."""

    def __init__(self):
        self.dir = tempfile.mkdtemp()
        self.raw = os.path.join(self.dir, "raw")
        os.makedirs(self.raw)
        self.put("env.json", {"env": "uat", "region": "us-west-2", "account": "111", "inventory_id": INVENTORY, "vpc": VPC,
                              "cluster": "c1", "warehouse_bucket": "wh", "entry_stack": "E",
                              "entry_domain": "colvenna-uat.example.com", "alb_arn": ALB_ARN,
                              "alb_dns": "entry-x-1.us-west-2.elb.amazonaws.com", "alb_name": "Entry-x",
                              "stacks": ["F", "E"], "apps": ["console", "bi"]})
        self.put("stack-resources.json", [
            {"stack": "F", "type": "AWS::EC2::EIP", "logical": "Spare", "physical": "192.0.2.134"},
            {"stack": "F", "type": "AWS::S3::Bucket", "logical": "W", "physical": "wh"},
            {"stack": "E", "type": "AWS::EC2::SecurityGroup", "logical": "EntrySg", "physical": "sg-entry"},
            {"stack": "E", "type": "AWS::Cognito::UserPool", "logical": "Pool", "physical": "pool-1"},
            {"stack": "F", "type": "AWS::Lambda::Function", "logical": "H", "physical": "F-handler"},
            {"stack": "F", "type": "AWS::ECR::Repository", "logical": "R", "physical": "colvenna-lake-uat/flink-base"},
            {"stack": "F", "type": "AWS::SecretsManager::Secret", "logical": "S",
             "physical": "arn:aws:secretsmanager:us-west-2:111:secret:colvenna-lake-uat/catalog-db-abc"},
        ])
        self.put("enis-public.json", {"NetworkInterfaces": [
            {"VpcId": VPC, "InterfaceType": "nat_gateway", "Description": "Interface for NAT Gateway nat-1",
             "Association": {"PublicIp": "192.0.2.144"}},
            {"VpcId": VPC, "InterfaceType": "interface", "Description": "ELB app/Entry-x/" + ALB_ID,
             "Association": {"PublicIp": "192.0.2.152"}},
            {"VpcId": "vpc-other", "InterfaceType": "interface", "Description": "ELB app/Other/def",
             "Association": {"PublicIp": "192.0.2.159"}}]})
        self.put("addresses.json", {"Addresses": [{"PublicIp": "192.0.2.134", "AllocationId": "eipalloc-1"},
                                                  {"PublicIp": "192.0.2.144", "AssociationId": "a-1"}]})
        self.put("load-balancers.json", {"LoadBalancers": [
            {"LoadBalancerArn": ALB_ARN, "LoadBalancerName": "Entry-x", "VpcId": VPC, "Scheme": "internet-facing",
             "Type": "application", "DNSName": "entry-x-1.us-west-2.elb.amazonaws.com"},
            {"LoadBalancerArn": "arn:other", "LoadBalancerName": "Other", "VpcId": "vpc-other",
             "Scheme": "internet-facing", "Type": "application"}]})
        self.put("classic-load-balancers.json", {"LoadBalancerDescriptions": []})
        self.put("lb/Entry-x/listeners.json", {"Listeners": [
            {"Port": 80, "Protocol": "HTTP", "DefaultActions": [{"Type": "redirect", "RedirectConfig": {"Protocol": "HTTPS"}}]},
            {"Port": 443, "Protocol": "HTTPS", "SslPolicy": "p"}]})
        self.put("lb/Entry-x/rules-80.json", {"Rules": [{"IsDefault": True, "Actions": [{"Type": "redirect"}]}]})
        self.put("lb/Entry-x/rules-443.json", {"Rules": [
            {"IsDefault": True, "Actions": [{"Type": "fixed-response"}]},
            {"IsDefault": False, "Priority": "1", "Actions": [{"Type": "authenticate-cognito", "Order": 1},
                                                              {"Type": "forward", "Order": 2}]}]})
        self.put("lb/Entry-x/ssl-443.json", {"SslPolicies": [{"SslProtocols": ["TLSv1.3", "TLSv1.2"]}]})
        self.put("eks.json", {"name": "c1", "resourcesVpcConfig": {"endpointPublicAccess": True, "endpointPrivateAccess": True,
                                                                  "publicAccessCidrs": ["192.0.2.100/32"]},
                              "accessConfig": {"authenticationMode": "API_AND_CONFIG_MAP"}})
        self.put("eks-others.json", [])
        self.put("k8s-services.json", [{"namespace": "colvenna-catalog", "name": "colvenna-catalog", "type": "ClusterIP"}])
        self.put("k8s-ingresses.json", [])
        self.put("k8s-host-ports.json", [])
        self.put("s3-account.json", BPA)
        self.put("s3/wh/bpa.json", BPA)
        self.put("s3/wh/policy.json", {"Policy": json.dumps(DENY_HTTP)})
        self.put("s3/wh/policy-status.json", {"PolicyStatus": {"IsPublic": False}})
        self.put("s3/wh/acl.json", {"Grants": [{"Grantee": {"Type": "CanonicalUser"}, "Permission": "FULL_CONTROL"}]})
        self.put("s3/wh/encryption.json", {"ServerSideEncryptionConfiguration": {}})
        self.put("cognito-pools.json", {"UserPools": [{"Id": "pool-1"}, {"Id": "pool-other"}]})
        self.put("cognito/pool-1/pool.json", {"Domain": "colvenna-lake-uat-111", "MfaConfiguration": "ON",
                                             "AllowAdminCreateUserOnly": True})
        self.put("cognito/pool-1/client-c1.json", {
            "ClientId": "c1", "HasSecret": True, "ExplicitAuthFlows": ["ALLOW_REFRESH_TOKEN_AUTH"],
            "AllowedOAuthFlows": ["code"], "SupportedIdentityProviders": ["COGNITO"],
            "CallbackURLs": ["https://console.colvenna-uat.example.com/oauth2/idpresponse"],
            "LogoutURLs": ["https://bi.colvenna-uat.example.com/"]})
        self.put("cognito/pool-other/pool.json", {"Domain": "someone-else"})
        self.put("lambda/us-west-2/functions.json", ["F-handler", "other-fn"])
        self.put("lambda/us-west-2/F-handler.urls.json", {"FunctionUrlConfigs": []})
        self.put("lambda/us-west-2/other-fn.urls.json", {"FunctionUrlConfigs": []})
        self.put("lambda/us-west-2/F-handler.policy.json", None)
        self.put("security-groups-open-ipv4.json", {"SecurityGroups": [
            {"GroupId": "sg-entry", "GroupName": "entry", "VpcId": VPC, "IpPermissions": [
                {"IpProtocol": "tcp", "FromPort": 443, "ToPort": 443, "IpRanges": [{"CidrIp": "0.0.0.0/0"}]},
                {"IpProtocol": "tcp", "FromPort": 80, "ToPort": 80, "IpRanges": [{"CidrIp": "0.0.0.0/0"}]}]},
            {"GroupId": "sg-ssh", "GroupName": "jump", "VpcId": "vpc-other", "IpPermissions": [
                {"IpProtocol": "tcp", "FromPort": 22, "ToPort": 22, "IpRanges": [{"CidrIp": "0.0.0.0/0"}]}]}]})
        self.put("security-groups-open-ipv6.json", {"SecurityGroups": []})
        self.put("apigateway-rest.json", {"items": []})
        self.put("apigateway-v2.json", {"Items": [{"ApiId": "a1", "Name": "theirs", "ProtocolType": "HTTP",
                                                   "Tags": {"aws:cloudformation:stack-name": "Someone"}}]})
        self.put("cloudfront.json", {"DistributionList": {"Items": []}})
        self.put("rds-instances.json", [{"id": "db1", "public": False, "vpc": VPC, "address": "db1.x", "port": 5432}])
        self.put("ecr-repositories.json", [{"repositoryName": "colvenna-lake-uat/flink-base"},
                                           {"repositoryName": "colvenna-lake-release/colvenna-catalog"},
                                           {"repositoryName": "someone/else"}])
        self.put("ecr/colvenna-lake-uat__flink-base.policy.json", None)
        self.put("ecr/colvenna-lake-release__colvenna-catalog.policy.json", None)
        self.put("secrets/colvenna-lake-uat__catalog-db-abc.policy.json", {"ResourcePolicy": None})
        self.put("route53.json", [
            {"Name": "console.colvenna-uat.example.com.", "Type": "A",
             "AliasTarget": {"DNSName": "dualstack.entry-x-1.us-west-2.elb.amazonaws.com."}},
            {"Name": "_abc.colvenna-uat.example.com.", "Type": "CNAME",
             "ResourceRecords": [{"Value": "_x.acm-validations.aws."}]}])

    def put(self, name, value):
        path = os.path.join(self.raw, name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as f:
            json.dump(value, f)

    def classify(self):
        inv = exposure.Inventory(self.dir).classify()
        exposure.write_inventory(self.dir, inv)
        return inv, {name: (result, detail) for name, result, detail in inv.checks}

    def close(self):
        shutil.rmtree(self.dir)


class HelperTest(unittest.TestCase):
    def test_is_nat_or_spare_recognizes_patterns(self):
        self.assertTrue(exposure.is_nat_or_spare("NAT gateway (egress only)"))
        self.assertTrue(exposure.is_nat_or_spare("unassociated Elastic IP"))
        self.assertFalse(exposure.is_nat_or_spare("load balancer app/Entry-x/abc"))
        self.assertFalse(exposure.is_nat_or_spare("interface i-123"))


class ClassifyTest(unittest.TestCase):
    def setUp(self):
        self.raw = Raw()

    def tearDown(self):
        self.raw.close()

    def test_a_clean_environment_passes_every_check(self):
        _, checks = self.raw.classify()
        failed = {k: v for k, v in checks.items() if v[0] != "PASS"}
        self.assertEqual(failed, {})
        self.assertEqual(len(checks), 17)

    def test_others_of_the_account_do_not_count_against_the_environment(self):
        inv, _ = self.raw.classify()
        others = {(k, i) for k, i, *_ in inv.others}
        self.assertIn(("load-balancer", "Other"), others)
        self.assertIn(("security-group", "sg-ssh"), others)
        self.assertNotIn("someone/else", [r[1] for r in inv.rows + inv.others])

    def test_a_function_url_anywhere_in_the_region_fails(self):
        self.raw.put("lambda/us-west-2/other-fn.urls.json", {"FunctionUrlConfigs": [{"AuthType": "AWS_IAM"}]})
        _, checks = self.raw.classify()
        self.assertEqual(checks["lambda-function-urls-none"][0], "FAIL")
        self.assertIn("other-fn (other, auth AWS_IAM)", checks["lambda-function-urls-none"][1])

    def test_a_second_public_load_balancer_of_the_environment_fails(self):
        lbs = read_json(os.path.join(self.raw.raw, "load-balancers.json"))
        lbs["LoadBalancers"].append({"LoadBalancerArn": "arn:nlb", "LoadBalancerName": "Nlb", "VpcId": VPC,
                                     "Scheme": "internet-facing", "Type": "network"})
        self.raw.put("load-balancers.json", lbs)
        _, checks = self.raw.classify()
        self.assertEqual(checks["load-balancers-only-the-entry"][0], "FAIL")

    def test_a_rule_that_forwards_without_signing_in_fails(self):
        self.raw.put("lb/Entry-x/rules-443.json", {"Rules": [
            {"IsDefault": True, "Actions": [{"Type": "fixed-response"}]},
            {"IsDefault": False, "Priority": "2", "Actions": [{"Type": "forward"}]}]})
        _, checks = self.raw.classify()
        self.assertEqual(checks["entry-listeners-sign-in-first"][0], "FAIL")

    def test_an_open_eks_endpoint_fails(self):
        self.raw.put("eks.json", {"name": "c1", "resourcesVpcConfig": {"endpointPublicAccess": True, "endpointPrivateAccess": True,
                                                                      "publicAccessCidrs": ["0.0.0.0/0"]},
                                  "accessConfig": {"authenticationMode": "API"}})
        _, checks = self.raw.classify()
        self.assertEqual(checks["eks-endpoint-iam-and-narrow"][0], "FAIL")

    def test_bucket_problems_are_named(self):
        self.raw.put("s3/wh/policy.json", {"Policy": json.dumps({"Statement": [
            {"Effect": "Allow", "Principal": "*", "Action": "s3:GetObject"}]})})
        self.raw.put("s3/wh/acl.json", {"Grants": [{"Grantee": {"URI": "http://acs.amazonaws.com/groups/global/AllUsers"}}]})
        _, checks = self.raw.classify()
        result, detail = checks["s3-buckets-private-and-tls-only"]
        self.assertEqual(result, "FAIL")
        for problem in ("a statement allows everyone", "ACL grants", "no deny of plain HTTP"):
            self.assertIn(problem, detail)

    def test_self_sign_up_or_a_password_flow_fails(self):
        self.raw.put("cognito/pool-1/pool.json", {"MfaConfiguration": "OPTIONAL", "AllowAdminCreateUserOnly": False})
        client = read_json(os.path.join(self.raw.raw, "cognito/pool-1/client-c1.json"))
        client["ExplicitAuthFlows"].append("ALLOW_USER_PASSWORD_AUTH")
        self.raw.put("cognito/pool-1/client-c1.json", client)
        _, checks = self.raw.classify()
        result, detail = checks["cognito-admin-only-mfa-code-flow"]
        self.assertEqual(result, "FAIL")
        self.assertIn("self sign-up allowed", detail)
        self.assertIn("ALLOW_USER_PASSWORD_AUTH", detail)

    def test_an_address_outside_the_nat_and_the_entry_fails(self):
        enis = read_json(os.path.join(self.raw.raw, "enis-public.json"))
        enis["NetworkInterfaces"].append({"VpcId": VPC, "InterfaceType": "interface", "Description": "",
                                          "Attachment": {"InstanceId": "i-1"}, "Association": {"PublicIp": "192.0.2.103"}})
        self.raw.put("enis-public.json", enis)
        _, checks = self.raw.classify()
        self.assertEqual(checks["public-addresses-only-nat-and-entry"][0], "FAIL")

    def test_another_open_group_in_the_vpc_fails(self):
        groups = read_json(os.path.join(self.raw.raw, "security-groups-open-ipv4.json"))
        groups["SecurityGroups"].append({"GroupId": "sg-nodes", "GroupName": "nodes", "VpcId": VPC, "IpPermissions": [
            {"IpProtocol": "-1", "Ipv6Ranges": [{"CidrIpv6": "::/0"}]}]})
        self.raw.put("security-groups-open-ipv4.json", groups)
        _, checks = self.raw.classify()
        self.assertEqual(checks["security-groups-open-only-the-entry"][0], "FAIL")

    def test_public_repository_and_secret_policies_fail(self):
        everyone = json.dumps({"Statement": [{"Effect": "Allow", "Principal": {"AWS": ["*"]}, "Action": "*"}]})
        self.raw.put("ecr/colvenna-lake-release__colvenna-catalog.policy.json", {"policyText": everyone})
        self.raw.put("secrets/colvenna-lake-uat__catalog-db-abc.policy.json", {"ResourcePolicy": everyone})
        _, checks = self.raw.classify()
        self.assertEqual(checks["ecr-no-public-policy"][0], "FAIL")
        self.assertEqual(checks["secrets-no-public-policy"][0], "FAIL")

    def test_a_name_pointing_elsewhere_fails(self):
        self.raw.put("route53.json", [{"Name": "console.colvenna-uat.example.com.", "Type": "A",
                                       "AliasTarget": {"DNSName": "elsewhere.example.net."}}])
        _, checks = self.raw.classify()
        self.assertEqual(checks["dns-names-only-the-entry"][0], "FAIL")


class MatrixTest(unittest.TestCase):
    def setUp(self):
        self.raw = Raw()
        self.raw.classify()
        self.uat = tempfile.mkdtemp()

    def tearDown(self):
        self.raw.close()
        shutil.rmtree(self.uat)

    def verdicts(self, results, run=RUN, mode="w"):
        with open(os.path.join(self.raw.dir, "verdicts.tsv"), mode) as f:
            for name, result in results.items():
                f.write("t\t%s\t%s\tdetail\t%s\n" % (name, result, run))

    def all_live_passing(self):
        env = exposure.load(os.path.join(self.raw.raw, "env.json"))
        rows = exposure.read_rows(os.path.join(self.raw.dir, "inventory.tsv"))
        names = {}
        for _, _, by_class in exposure.matrix_rows(rows, env):
            for items in by_class.values():
                for item in items:
                    if item[0] in ("live", "inventory"):
                        names[item[1]] = "PASS"
        return names

    def test_every_internet_resource_gets_a_row(self):
        env = exposure.load(os.path.join(self.raw.raw, "env.json"))
        rows = exposure.read_rows(os.path.join(self.raw.dir, "inventory.tsv"))
        resources = [r for r, _, _ in exposure.matrix_rows(rows, env)]
        self.assertEqual(resources, ["192.0.2.134", "192.0.2.144", "https://console.colvenna-uat.example.com",
                                     "https://bi.colvenna-uat.example.com", "c1", "wh", "pool-1", "image registry"])

    def test_non_alb_non_nat_address_is_included_in_matrix(self):
        enis = read_json(os.path.join(self.raw.raw, "enis-public.json"))
        enis["NetworkInterfaces"].append({"VpcId": VPC, "InterfaceType": "interface", "Description": "",
                                          "Attachment": {"InstanceId": "i-mystery"}, "Association": {"PublicIp": "192.0.2.109"}})
        self.raw.put("enis-public.json", enis)
        self.raw.classify()
        env = exposure.load(os.path.join(self.raw.raw, "env.json"))
        rows = exposure.read_rows(os.path.join(self.raw.dir, "inventory.tsv"))
        resources = [r for r, _, _ in exposure.matrix_rows(rows, env)]
        self.assertIn("192.0.2.109", resources)
        # No evidence is defined for it, so every probe passing still fails the matrix.
        self.verdicts(self.all_live_passing())
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN)
        self.assertEqual(result, "FAIL")
        for c in exposure.CLASSES:
            self.assertIn("192.0.2.109/%s MISSING" % c, detail)

    def test_without_uat_evidence_the_live_checks_carry_the_matrix(self):
        self.verdicts(self.all_live_passing())
        (name, result, detail), = exposure.matrix(self.raw.dir, RUN)
        self.assertEqual((name, result), ("denial-matrix-complete", "PASS"), detail)
        md = exposure.read_text(os.path.join(self.raw.dir, "matrix.md"))
        self.assertIn("N/A：nothing listens on it", md)

    def test_a_failed_probe_fails_its_cell(self):
        live = self.all_live_passing()
        live["deny-eks-without-rbac"] = "FAIL"
        self.verdicts(live)
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN)
        self.assertEqual(result, "FAIL")
        self.assertIn("c1/unauthorized FAIL", detail)

    def test_a_class_without_any_evidence_is_missing(self):
        live = self.all_live_passing()
        del live["deny-ecr-anonymous"]
        self.verdicts(live)
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN)
        self.assertEqual(result, "FAIL")
        self.assertIn("image registry/unauthenticated MISSING", detail)

    def test_uat_references_take_the_latest_rerun(self):
        self.verdicts(self.all_live_passing())
        with open(os.path.join(self.uat, "summary.tsv"), "w") as f:
            f.write("entry\tno-role-refused\tFAIL\tfirst run\n")
            f.write("entry-2\tno-role-refused\tPASS\trerun\n")
            f.write("bi\troles-erin-refused\tPASS\t403\n")
        os.makedirs(os.path.join(self.uat, "catalog-tokens-2"))
        with open(os.path.join(self.uat, "catalog-tokens-2", "data-token-checks.txt"), "w") as f:
            f.write("identity without a grant gets none expected=403 actual=403\n")
            f.write("whole bucket: list expected=deny actual=allow\n")
        verdicts = exposure.read_verdicts(os.path.join(self.raw.dir, "verdicts.tsv"))
        runs = exposure.read_uat(self.uat)
        self.assertEqual(exposure.evaluate(exposure.uat("entry", "no-role-refused"), verdicts, self.uat, runs)[0], "PASS")
        self.assertEqual(exposure.evaluate(exposure.uat("bi", "lake-table-refused"), verdicts, self.uat, runs)[0], "MISSING")
        line = exposure.uat_line("catalog-tokens", "catalog-tokens/data-token-checks.txt", "whole bucket: list")
        self.assertEqual(exposure.evaluate(line, verdicts, self.uat, runs)[0], "FAIL")
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN, self.uat)
        self.assertEqual(result, "FAIL")
        self.assertIn("wh/unauthorized FAIL", detail)

    def complete_uat(self):
        """A UAT root holding a PASS for every UAT reference of the matrix."""
        env = exposure.load(os.path.join(self.raw.raw, "env.json"))
        rows = exposure.read_rows(os.path.join(self.raw.dir, "inventory.tsv"))
        with open(os.path.join(self.uat, "summary.tsv"), "w") as summary:
            for _, _, by_class in exposure.matrix_rows(rows, env):
                for items in by_class.values():
                    for item in items:
                        if item[0] == "uat":
                            summary.write("%s\t%s\tPASS\tok\n" % item[1:])
                        elif item[0] == "uat-line":
                            os.makedirs(os.path.join(self.uat, item[1]), exist_ok=True)
                            with open(os.path.join(self.uat, item[2]), "a") as f:
                                f.write("%s expected=403 actual=403\n" % item[3])

    def test_with_complete_uat_evidence_every_class_passes(self):
        self.verdicts(self.all_live_passing())
        self.complete_uat()
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN, self.uat)
        self.assertEqual(result, "PASS", detail)
        section = exposure.section(self.raw.dir, RUN)
        self.assertIn("#### 三类拒绝", section)
        # The section is for the repository: the deployment's values are placeholders.
        self.assertIn("| `https://console.<入口域名>` | console behind the entry | PASS", section)
        self.assertNotIn("colvenna-uat.example.com", section)

    def test_a_uat_check_the_uat_evidence_lacks_is_missing(self):
        self.verdicts(self.all_live_passing())
        self.complete_uat()
        with open(os.path.join(self.uat, "summary.tsv")) as f:
            kept = [line for line in f if "\troles-erin-refused\t" not in line]
        with open(os.path.join(self.uat, "summary.tsv"), "w") as f:
            f.writelines(kept)
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN, self.uat)
        self.assertEqual(result, "FAIL")
        self.assertIn("https://bi.colvenna-uat.example.com/unauthorized MISSING", detail)


    def test_a_class_of_only_reasons_is_n_a_and_passes(self):
        self.verdicts(self.all_live_passing())
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN)
        self.assertEqual(result, "PASS", detail)
        cells = self.cells()
        self.assertEqual(cells[("192.0.2.144", "unauthorized")], "N/A")
        self.assertEqual(cells[("image registry", "bypass")], "N/A")

    def cells(self):
        """matrix.tsv's outcome of each (resource, class)."""
        with open(os.path.join(self.raw.dir, "matrix.tsv")) as f:
            return {(r, c): o for r, c, o, *_ in (line.rstrip("\n").split("\t") for line in list(f)[1:])}

    def evidence_of(self, resource, kind):
        """The (result, where) rows of matrix.tsv for one resource and class."""
        with open(os.path.join(self.raw.dir, "matrix.tsv")) as f:
            return [(res, where) for r, c, _, res, where in (line.rstrip("\n").split("\t") for line in list(f)[1:])
                    if r == resource and c == kind]

    def test_the_user_pools_unauthorized_class_carries_its_n_a_reason(self):
        self.verdicts(self.all_live_passing())
        exposure.matrix(self.raw.dir, RUN)
        self.assertEqual(self.cells()[("pool-1", "unauthorized")], "PASS")
        self.assertIn(("N/A", exposure.COGNITO_UNAUTHORIZED_NA), self.evidence_of("pool-1", "unauthorized"))
        md = exposure.read_text(os.path.join(self.raw.dir, "matrix.md"))
        # The reason shows; the N/A item is in neither count.
        self.assertIn("PASS（1/1 项，另 2 项 UAT 未提供，另 1 项 N/A：%s）" % exposure.COGNITO_UNAUTHORIZED_NA, md)

    def test_the_template_gives_the_user_pools_n_a_reason_in_the_same_words(self):
        template = os.path.join(os.path.dirname(__file__), "..", "..", "release", "security-template.md")
        self.assertIn(exposure.COGNITO_UNAUTHORIZED_NA, exposure.read_text(template))

    def test_buckets_and_the_registry_rest_on_the_under_privileged_probes(self):
        live = self.all_live_passing()
        self.assertIn("deny-s3-wh-underprivileged", live)
        self.assertIn("deny-ecr-underprivileged", live)
        live["deny-s3-wh-underprivileged"] = "FAIL"
        del live["deny-ecr-underprivileged"]
        self.verdicts(live)
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN)
        self.assertEqual(result, "FAIL")
        self.assertIn("wh/unauthorized FAIL", detail)
        self.assertIn("image registry/unauthorized MISSING", detail)

    def test_the_nat_and_spare_addresses_rest_on_the_positive_control(self):
        live = self.all_live_passing()
        live["deny-address-positive-control"] = "FAIL"
        self.verdicts(live)
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN)
        self.assertEqual(result, "FAIL")
        self.assertIn("192.0.2.134/unauthenticated FAIL", detail)
        self.assertIn("192.0.2.144/unauthenticated FAIL", detail)
        self.assertIn(("FAIL", "deny-address-positive-control: detail"), self.evidence_of("192.0.2.144", "unauthenticated"))

    def test_only_this_inventorys_and_this_runs_rows_count(self):
        live = self.all_live_passing()
        live["deny-eks-without-rbac"] = "FAIL"
        self.verdicts(live)
        # Another run's later PASS does not hide this run's FAIL; another inventory's FAIL does not count.
        self.verdicts({"deny-eks-without-rbac": "PASS"}, run="r2", mode="a")
        self.verdicts({"rds-none-public": "FAIL"}, run="inventory-older", mode="a")
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN)
        self.assertEqual(result, "FAIL")
        self.assertIn("c1/unauthorized FAIL", detail)
        self.assertNotIn("rds", detail)
        # A check only another run passed is missing for this one.
        live = self.all_live_passing()
        del live["deny-ecr-anonymous"]
        self.verdicts(live)
        self.verdicts({"deny-ecr-anonymous": "PASS"}, run="r2", mode="a")
        (_, result, detail), = exposure.matrix(self.raw.dir, RUN)
        self.assertIn("image registry/unauthenticated MISSING", detail)
        self.assertNotIn("r2", exposure.section(self.raw.dir, RUN).split("#### 拒绝检查")[1])

    def test_the_section_counts_n_a_on_its_own_with_its_reason(self):
        live = self.all_live_passing()
        self.verdicts(live)
        with open(os.path.join(self.raw.dir, "verdicts.tsv"), "a") as f:
            f.write("t\tdeny-something-optional\tN/A\tnot installed here\t%s\n" % RUN)
        exposure.matrix(self.raw.dir, RUN)
        section = exposure.section(self.raw.dir, RUN)
        self.assertIn("检查共 %d 项：%d 项 PASS，1 项 N/A，0 项 FAIL。" % (len(live) + 1, len(live)), section)
        self.assertIn("| `deny-something-optional` | N/A | not installed here |", section)

    def test_the_section_gives_each_address_one_number_in_every_cell(self):
        # #267: the inventory's checks and the probes with their real details. The NAT's address is
        # in a check, its row, the matrix, a check's name and the EKS probe's egress; the EKS
        # endpoint's addresses only in that probe.
        inv, _ = self.raw.classify()
        with open(os.path.join(self.raw.dir, "verdicts.tsv"), "w") as f:
            for name, result, detail in inv.checks:
                f.write("t\t%s\t%s\t%s\t%s\n" % (name, result, detail, INVENTORY))
            f.write("t\tdeny-address-192.0.2.144-no-inbound\tPASS\tNAT gateway (egress only); accepted on: none\t%s\n" % RUN)
            f.write("t\tdeny-eks-from-another-address\tPASS\tegress 192.0.2.144;192.0.2.167 000 28;192.0.2.168 000 28;\t%s\n" % RUN)
        exposure.matrix(self.raw.dir, RUN)
        section = exposure.section(self.raw.dir, RUN)
        for line in ("| `public-addresses-only-nat-and-entry` | PASS | <公网地址 1> unassociated Elastic IP; "
                     "<公网地址 2> NAT gateway (egress only); <公网地址 3> load balancer app/<入口负载均衡器>/<…> |",
                     # The one address the endpoint admits is the host's the check ran on (deploy.sh).
                     "| `eks-endpoint-iam-and-narrow` | PASS | public True from ['<执行机的出口地址>/32'];",
                     "| eks-endpoint | `<EKS 集群>` | env | IAM (access entries, API_AND_CONFIG_MAP), from <执行机的出口地址>/32 only |",
                     "| address | `<公网地址 2>` | env | none listens | NAT gateway (egress only) |",
                     "| address | `<公网地址 3>` | env | the load balancer's listeners | load balancer app/<入口负载均衡器>/<…> |",
                     "| `<公网地址 2>` | NAT gateway (egress only) |",
                     "| `deny-address-<公网地址 2>-no-inbound` | PASS |",
                     "| `deny-eks-from-another-address` | PASS | egress <公网地址 2>;<公网地址 4> 000 28;<公网地址 5> 000 28; |"):
            self.assertIn(line, section)
        self.assertNotIn(ALB_ID, section)

    def test_a_placeholder_in_a_resources_code_span_is_not_made_code_again(self):
        # A resource named by the environment's VPC: its row, its matrix row and a probe's name hold
        # <VPC> inside a code span; the row's note holds it and an ARN outside one.
        self.raw.classify()
        with open(os.path.join(self.raw.dir, "inventory.tsv"), "a") as f:
            f.write("vpc-endpoint\t%s/endpoint-1\tenv\tinternet\tno\tIAM\tin %s, by %s\n" % (VPC, VPC, ALB_ARN))
        self.verdicts({"deny-endpoint-%s-anonymous" % VPC: "PASS"})
        exposure.matrix(self.raw.dir, RUN)
        section = exposure.section(self.raw.dir, RUN)
        for line in ("| vpc-endpoint | `<VPC>/endpoint-1` | env | IAM | in `<VPC>`, by `<ARN>` |",
                     "| `<VPC>/endpoint-1` | vpc-endpoint (no denial evidence defined) |",
                     "| `deny-endpoint-<VPC>-anonymous` | PASS |"):
            self.assertIn(line, section)
        self.assertNotIn("``", section)


class PreflightTest(unittest.TestCase):
    SHA = "a" * 40

    def setUp(self):
        self.raw = Raw()
        self.raw.classify()
        with open(os.path.join(self.raw.dir, "scripts-sha.txt"), "w") as f:
            f.write(self.SHA + "\n")
        with open(os.path.join(self.raw.dir, "verdicts.tsv"), "w") as f:
            f.write("t\trds-none-public\tPASS\td\t%s\n" % INVENTORY)

    def tearDown(self):
        self.raw.close()

    def problems(self, env="uat", sha=SHA):
        return exposure.preflight(self.raw.dir, env, sha)

    def test_this_inventorys_directory_is_accepted(self):
        self.assertEqual(self.problems(), [])

    def test_another_environments_inventory_is_refused(self):
        self.assertIn("no inventory of uate2", self.problems(env="uate2")[0])

    def test_another_commits_scripts_are_refused(self):
        (problem,) = self.problems(sha="b" * 40)
        self.assertIn("the inventory ran the scripts at %s, these are at %s" % (self.SHA, "b" * 40), problem)
        os.remove(os.path.join(self.raw.dir, "scripts-sha.txt"))
        self.assertIn("an unrecorded commit", self.problems()[0])

    def test_rows_of_any_other_run_are_refused(self):
        with open(os.path.join(self.raw.dir, "verdicts.tsv"), "a") as f:
            f.write("t\tdeny-x\tPASS\td\tr9\n")
            f.write("t\trds-none-public\tPASS\td\tinventory-older\n")
            f.write("t\tlegacy\tPASS\td\n")
        (problem,) = self.problems()
        self.assertIn("(no run id), inventory-older, r9", problem)

    def test_a_directory_with_an_earlier_denial_run_is_refused(self):
        os.makedirs(os.path.join(self.raw.dir, "denials"))
        (problem,) = self.problems()
        self.assertIn("already holds a denial run (denials)", problem)

    def test_an_inventory_without_an_id_is_refused(self):
        env = read_json(os.path.join(self.raw.raw, "env.json"))
        del env["inventory_id"]
        self.raw.put("env.json", env)
        problems = self.problems()
        self.assertIn("recorded no id", problems[0])
        self.assertIn("rows of other runs (%s)" % INVENTORY, problems[1])


class UnderprivilegedTest(unittest.TestCase):
    ROLE = "colvenna-lake-uat-console"
    DENIED = ("An error occurred (AccessDenied) when calling the ListObjectsV2 operation: User: "
              "arn:aws:sts::111:assumed-role/colvenna-lake-uat-console/botocore-session-1 is not authorized")

    def results(self, log, checks):
        return {c: (r, d) for c, r, d in exposure.underprivileged(log, self.ROLE, checks)}

    def test_each_check_reads_only_its_own_line(self):
        log = "deny-s3-a-underprivileged\t254\t%s\n" % self.DENIED
        got = self.results(log, ["deny-s3-a-underprivileged", "deny-s3-b-underprivileged", "deny-ecr-underprivileged"])
        self.assertEqual(got["deny-s3-a-underprivileged"][0], "PASS")
        self.assertEqual(got["deny-s3-b-underprivileged"], ("FAIL", "0 result lines of this request in the probe's log, not 1"))
        self.assertEqual(got["deny-ecr-underprivileged"][0], "FAIL")

    def test_a_request_that_succeeded_fails_whatever_it_printed(self):
        got = self.results("deny-s3-a-underprivileged\t0\t%s\n" % self.DENIED, ["deny-s3-a-underprivileged"])
        self.assertEqual(got["deny-s3-a-underprivileged"][0], "FAIL")
        self.assertIn("exit 0", got["deny-s3-a-underprivileged"][1])

    def test_a_failure_that_is_no_denial_of_the_role_fails(self):
        log = ("deny-s3-a-underprivileged\t253\tUnable to locate credentials\n"
               "deny-s3-b-underprivileged\t254\t%s\n" % self.DENIED.replace("uat-console", "uat-bi"))
        got = self.results(log, ["deny-s3-a-underprivileged", "deny-s3-b-underprivileged"])
        self.assertEqual({r for r, _ in got.values()}, {"FAIL"})

    def test_two_lines_for_one_check_fail(self):
        line = "deny-s3-a-underprivileged\t254\t%s\n" % self.DENIED
        got = self.results(line * 2, ["deny-s3-a-underprivileged"])
        self.assertEqual(got["deny-s3-a-underprivileged"][0], "FAIL")


class ExactScanTest(unittest.TestCase):
    PASSWORD = "Aa1!passwordvalue0123456789"
    TOTP = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    SESSION = "sessionvalue-abcdefghijklmnopqrstuvwxyz"
    HTTPONLY = "httponlyvalue-0123456789abcdef"
    SECRET = "clientsecret0123456789abcdefghijklmnop"
    TOKEN = "eyJhbGciOiJSUzI1NiJ9.serviceaccount.token"

    def setUp(self):
        self.state = tempfile.mkdtemp()
        self.evidence = tempfile.mkdtemp()
        self.write(self.state, "sec-r1-op.pw", self.PASSWORD)
        self.write(self.state, "sec-r1-op.totp", self.TOTP)
        self.write(self.state, "sec-r1-op.totp.used", "59000000")
        # entry.py's jar: http.cookiejar.MozillaCookieJar.save
        self.write(self.state, "sec-r1-op.cookies",
                   "# Netscape HTTP Cookie File\n# This is a generated file!  Do not edit.\n\n"
                   "bi.example.com\tFALSE\t/\tTRUE\t\tAWSELBAuthSessionCookie-0\t%s\n"
                   "#HttpOnly_auth.example.com\tFALSE\t/\tTRUE\t1800000000\tcognito\t%s\n"
                   "auth.example.com\tFALSE\t/\tTRUE\t\tlang\ten\n" % (self.SESSION, self.HTTPONLY))
        self.write(self.state, "client-secret", self.SECRET + "\n")
        self.write(self.state, "sa-token", self.TOKEN)
        self.write(self.evidence, "denials.log", "fine\n")
        self.write(self.evidence, "raw/env.json", "{}")

    def tearDown(self):
        shutil.rmtree(self.state)
        shutil.rmtree(self.evidence)

    def write(self, base, name, text):
        os.makedirs(os.path.dirname(os.path.join(base, name)), exist_ok=True)
        with open(os.path.join(base, name), "w") as f:
            f.write(text)

    def scan(self, made=exposure.SECRET_TYPES):
        return exposure.exact_scan(self.evidence, self.state, made)

    def test_the_files_the_scripts_write_are_read_by_type(self):
        values = exposure.secret_values(self.state)
        self.assertEqual(values["password"], [self.PASSWORD])
        self.assertEqual(values["totp"], [self.TOTP])
        self.assertEqual(values["cookie"], [self.SESSION, self.HTTPONLY, "en"])
        self.assertEqual(values["client-secret"], [self.SECRET])
        self.assertEqual(values["sa-token"], [self.TOKEN])

    def test_clean_evidence_passes_with_the_counts_of_each_type(self):
        name, result, detail = self.scan()
        self.assertEqual((name, result), ("evidence-has-no-actual-credentials", "PASS"), detail)
        self.assertEqual(detail, "none found; scanned 2 files for 1 password, 1 totp, 3 cookie, 1 client-secret, "
                                 "1 sa-token values (1 shorter than 8 characters not looked for)")

    def test_a_copy_anywhere_fails_by_file_and_type_never_the_value(self):
        # The section, and a file of no usual extension.
        self.write(self.evidence, "section.md", "| x | %s |\n" % self.HTTPONLY)
        self.write(self.evidence, "denials/probe.out", "secret=" + self.SECRET)
        _, result, detail = self.scan()
        self.assertEqual(result, "FAIL")
        self.assertIn("a cookie value in section.md", detail)
        self.assertIn("a client-secret value in %s" % os.path.join("denials", "probe.out"), detail)
        for value in (self.HTTPONLY, self.SECRET):
            self.assertNotIn(value, detail)

    def test_a_type_the_run_made_without_a_value_to_look_for_fails(self):
        os.remove(os.path.join(self.state, "sec-r1-op.totp"))
        _, result, detail = self.scan()
        self.assertEqual(result, "FAIL")
        self.assertIn("totp: the run made some, but none was found to scan for", detail)
        # A requests-only run made no TOTP secret: nothing is missing.
        self.assertEqual(self.scan(["password", "client-secret"])[1], "PASS")


class RunDenialsShellTest(unittest.TestCase):
    """Functions of run-denials.sh, run on their own with stand-ins for aws and kubectl."""

    SCRIPT = os.path.join(os.path.dirname(__file__), "security", "run-denials.sh")

    def function(self, name):
        text = exposure.read_text(self.SCRIPT)
        start = text.index("\n%s() {" % name) + 1
        return text[start:text.index("\n}\n", start) + 3]

    def run_bash(self, script):
        import subprocess
        return subprocess.run(["bash", "-c", script], capture_output=True, text=True, check=True).stdout

    def test_cleanup_deletes_what_a_wrong_acceptance_leaves_in_requests_only_mode(self):
        state = tempfile.mkdtemp()
        calls = tempfile.mkstemp()[1]
        try:
            self.run_bash("""
requests_only=true pool=p1 prefix=sec-r1 put_key=security-probe/r1 namespace=colvenna-sec-r1
probe_namespace=colvenna-console probe_pod=sec-r1-underprivileged state=%s
aws() { echo "aws $*" >>%s; }
kubectl() { echo "kubectl $*" >>%s; }
rows() { echo wh; }
%s
cleanup""" % (state, calls, calls, self.function("cleanup")))
            out = exposure.read_text(calls)
        finally:
            os.remove(calls)
        self.assertIn("aws cognito-idp admin-delete-user --user-pool-id p1 --username sec-r1-signup", out)
        self.assertIn("aws s3api delete-object --bucket wh --key security-probe/r1", out)
        self.assertIn("kubectl -n colvenna-console delete pod sec-r1-underprivileged", out)
        self.assertFalse(os.path.exists(state))

    def test_an_n_a_verdict_fails_nothing(self):
        d = tempfile.mkdtemp()
        try:
            out = self.run_bash("""
evidence=%s run=r1 failed=0
%s
verdict deny-x N/A "a reason" >/dev/null
echo "after N/A: $failed"
verdict deny-y FAIL "a failure" >/dev/null
echo "after FAIL: $failed"
""" % (d, self.function("verdict")))
            self.assertEqual(out.split("\n")[-3:-1], ["after N/A: 0", "after FAIL: 1"])
            rows = exposure.read_verdict_rows(os.path.join(d, "verdicts.tsv"))
            self.assertEqual([(r[1], r[2], r[3], r[4]) for r in rows],
                             [("deny-x", "N/A", "a reason", "r1"), ("deny-y", "FAIL", "a failure", "r1")])
        finally:
            shutil.rmtree(d)


class RedactTest(unittest.TestCase):
    ENV = {"account": "123456789012", "cluster": "Cluster9EE0221C-0123456789abcdef0123456789abcdef",
           "vpc": "vpc-0123456789abcdef0", "entry_domain": "colvenna-uat.zone.test", "pool": "us-west-2_AbCdEfGhI",
           "alb_name": "Colven-Entry-AbCdEfGhIjKl", "alb_dns": "Colven-Entry-AbCdEfGhIjKl-123.us-west-2.elb.amazonaws.com",
           "warehouse_bucket": "colvennalakeuatfoundation-warehouse50b5fe91-abcdefghijkl", "client": "2iadktclientid0123"}

    def test_the_deployments_values_become_placeholders(self):
        text = "\n".join([
            "| 账号 | `123456789012` |",
            "by arn:aws:sts::123456789012:assumed-role/some_role/i-0123456789abcdef0; at 852f20da",
            "['arn:aws:elasticloadbalancing:us-west-2:123456789012:loadbalancer/app/Colven-Entry-AbCdEfGhIjKl/0123456789abcdef']",
            "`https://console.colvenna-uat.zone.test` 302 `https://colvenna-lake-uat-123456789012.auth.us-west-2.amazoncognito.com/login`",
            "302 `https://colvenna-lake-uate2-123456789012.auth.us-east-2.amaz` (a probe's first 60 characters)",
            "dualstack.colven-entry-abcdefghijkl-123.us-west-2.elb.amazonaws.com; ABCDEF.gr7.us-west-2.eks.amazonaws.com",
            "net-1a2b3c4d5e6f7a8b.elb.us-west-2.amazonaws.com, vpce-0123456789abcdef0, client 2iadktclientid0123",
            "sg-0123456789abcdef0 (ColvennaLakeUatEntry-EntrySecurityGroup7B01EF29-wiAvTC5MmAh6) in vpc-0123456789abcdef0",
            "Cluster9EE0221C-0123456789abcdef0123456789abcdef; us-west-2_AbCdEfGhI",
            "deny-s3-colvennalakeuatentry-accesslogs8b620eca-7jpu9dx3eyje-underprivileged",
            "10.84.8.23 and 203.0.113.7/32, open to 0.0.0.0/0 and ::/0; Doris 4.1.4, EKS 1.36.4",  # redaction: allow (a synthetic internal address)
        ])
        out = exposure.redact(text, self.ENV)
        for real in ("123456789012", "arn:aws", "zone.test", "amazoncognito.com", ".amaz", "elb.amazonaws.com",
                     "eks.amazonaws.com", "0123456789abcdef", "AbCdEfGhI", "wiAvTC5MmAh6", "7jpu9dx3eyje",
                     "10.84.8.23", "203.0.113.7", "some_role", "2iadktclientid0123", "1a2b3c4d5e6f7a8b"):  # redaction: allow (the same)
            self.assertNotIn(real, out)
        for kept in ("`<ACCOUNT_ID>`", "`https://console.<入口域名>`", "0.0.0.0/0", "::/0", "4.1.4", "1.36.4",
                     "852f20da", "<公网地址 1>/32", "<内网地址 1>", "deny-s3-colvennalakeuatentry-accesslogs<…>-underprivileged",
                     "ColvennaLakeUatEntry-EntrySecurityGroup7B01EF29-<…>", "by `<ARN>`; at", "['`<ARN>`']", "<vpce-…>"):
            self.assertIn(kept, out)
        # Idempotent: a section already redacted stays as it is.
        self.assertEqual(out, exposure.redact(out, self.ENV))

    def test_each_address_keeps_its_own_number(self):
        out = exposure.redact("deny-address-198.51.100.1-no-inbound; deny-address-198.51.100.2-no-inbound; "
                              "198.51.100.1 again; fe80::1 and 2600:1f14:abc:def0::1", self.ENV)
        self.assertEqual("deny-address-<公网地址 1>-no-inbound; deny-address-<公网地址 2>-no-inbound; "
                         "<公网地址 1> again; <IPv6 地址 1> and <IPv6 地址 2>", out)

    def test_one_redaction_numbers_an_address_once_in_all_its_texts(self):
        # A section redacts cell by cell, before it cuts a cell short: its cells share the numbers.
        hide = exposure.Redaction(self.ENV)
        cells = [hide(text) for text in ("198.51.100.1 unassociated Elastic IP; 198.51.100.2 NAT gateway",
                                         "public True from ['198.51.100.9/32']", "egress 198.51.100.2;198.51.100.10 000 28",
                                         "fe80::1 and 2600:1f14:abc:def0::1", "2600:1f14:abc:def0::1 again")]
        self.assertEqual(["<公网地址 1> unassociated Elastic IP; <公网地址 2> NAT gateway",
                          "public True from ['<公网地址 3>/32']", "egress <公网地址 2>;<公网地址 4> 000 28",
                          "<IPv6 地址 1> and <IPv6 地址 2>", "<IPv6 地址 2> again"], cells)

    def test_a_number_already_in_the_document_is_not_given_again(self):
        # A document that holds a redacted section, and a new address of its own.
        out = exposure.redact("| `<公网地址 1>` | NAT |\n| `<公网地址 2>` | spare |\nlater 198.51.100.7, "
                              "<IPv6 地址 1> and 2001:db8::7", self.ENV)
        self.assertEqual("| `<公网地址 1>` | NAT |\n| `<公网地址 2>` | spare |\nlater <公网地址 3>, "
                         "<IPv6 地址 1> and <IPv6 地址 2>", out)

    def test_a_load_balancers_id_and_generated_name_suffix_become_placeholders(self):
        # The entry's (its name is the environment's), another stack's, one of no generated suffix, a
        # target group's: "<type>/<name>/<ID>", as an ELB network interface's description has it.
        out = exposure.redact("ELB app/Colven-Entry-AbCdEfGhIjKl/0123456789abcdef; ELB net/Other-Inner-ABCDEFGHIJKL/"
                              "fedcba9876543210; app/team-web/89abcdef01234567; "
                              "targetgroup/Colven-Conso-1A2B3C4D5E6F/0a1b2c3d4e5f6a7b", self.ENV)
        self.assertEqual("ELB app/<入口负载均衡器>/<…>; ELB net/Other-Inner-<…>/<…>; app/team-web/<…>; "
                         "targetgroup/Colven-Conso-<…>/<…>", out)
        self.assertEqual(out, exposure.redact(out, self.ENV))

    def test_the_command_names_the_one_address_the_eks_endpoint_admits(self):
        d = tempfile.mkdtemp()
        try:
            def put(name, value):
                with open(os.path.join(d, name), "w") as f:
                    f.write(value if isinstance(value, str) else json.dumps(value))
            os.makedirs(os.path.join(d, "raw"))
            put("raw/env.json", self.ENV)
            put("raw/eks.json", {"resourcesVpcConfig": {"endpointPublicAccess": True, "publicAccessCidrs": ["198.51.100.9/32"]}})
            put("doc.md", "from ['198.51.100.9/32']; 198.51.100.1 spare; egress 198.51.100.2;198.51.100.9\n")
            exposure.main(["exposure.py", "redact", d, os.path.join(d, "doc.md")])
            self.assertEqual("from ['<执行机的出口地址>/32']; <公网地址 1> spare; egress <公网地址 2>;<执行机的出口地址>\n",
                             exposure.read_text(os.path.join(d, "doc.md")))
            # Two addresses (which one is the host's is not known), or a private endpoint: each is numbered.
            put("raw/eks.json", {"resourcesVpcConfig": {"endpointPublicAccess": True,
                                                        "publicAccessCidrs": ["198.51.100.9/32", "198.51.100.8/32"]}})
            self.assertEqual([], exposure.Inventory(d).host_addresses())
            put("raw/eks.json", {"resourcesVpcConfig": {"endpointPublicAccess": False, "publicAccessCidrs": ["198.51.100.9/32"]}})
            self.assertEqual([], exposure.Inventory(d).host_addresses())
        finally:
            shutil.rmtree(d)

    def test_a_cdk_provider_functions_three_part_name_loses_its_suffix(self):
        # The stack, a nested construct's prefix, the logical id, the random suffix; a two-part name.
        out = exposure.redact("ColvennaLakeUatFoundation-aws-OnEventHandler1A2B3C4D-qWeRtYuIoPaS, "
                              "ColvennaLakeUatFoundation-a-ProviderframeworkonEvent-1q2w3e4r5t6y, "
                              "ColvennaLakeUatFoundation-LogRetention0a1b2c3d4e5f6a7b-ZxCvBnMaSdFg, "
                              "Colvenna-Lake-Entry-Certificates", self.ENV)
        self.assertEqual("ColvennaLakeUatFoundation-aws-OnEventHandler1A2B3C4D-<…>, "
                         "ColvennaLakeUatFoundation-a-ProviderframeworkonEvent-<…>, "
                         "ColvennaLakeUatFoundation-LogRetention0a1b2c3d4e5f6a7b-<…>, "
                         "Colvenna-Lake-Entry-Certificates", out)

    def test_what_only_looks_like_a_value_stays(self):
        text = ("v1.2.3.4, at 13:41:42Z, Colvenna-Entry-Certificates, offset 1234567890123, "
                "a cell arn:aws:iam::123456789012:role/x\\| next")
        out = exposure.redact(text, self.ENV)
        for kept in ("v1.2.3.4", "13:41:42Z", "Colvenna-Entry-Certificates", "1234567890123"):
            self.assertIn(kept, out)
        self.assertIn("`<ARN>`\\| next", out)

    def test_a_fenced_block_is_left_as_written(self):
        out = exposure.redact("```text\narn:aws:iam::123456789012:role/x\n```\narn:aws:iam::123456789012:role/y", self.ENV)
        self.assertEqual("```text\n<ARN>\n```\n`<ARN>`", out)

    def test_the_command_refuses_without_the_environments_values(self):
        d = tempfile.mkdtemp()
        try:
            doc = os.path.join(d, "doc.md")
            with open(doc, "w") as f:
                f.write("account 123456789012\n")
            with self.assertRaises(SystemExit):
                exposure.main(["exposure.py", "redact", d, doc])
            with open(doc) as f:
                self.assertEqual("account 123456789012\n", f.read())
        finally:
            shutil.rmtree(d)


class ScrubTest(unittest.TestCase):
    def test_credentials_are_found_and_not_repeated(self):
        d = tempfile.mkdtemp()
        try:
            with open(os.path.join(d, "a.log"), "w") as f:
                f.write("fine line\nkey ASIAABCDEFGHIJKLMNOP here\n")
            with open(os.path.join(d, "b.json"), "w") as f:
                f.write('{"ClientSecret": "s3cr3t"}\n')
            with open(os.path.join(d, "c.txt"), "w") as f:
                f.write("forged eyJhbGciOiJub25lIn0.eyJzdWIiOiJmb3JnZWQifQ. unsigned\n")
            hits = exposure.scrub(d)
            self.assertEqual(sorted(hits), ["a.log:2 AWS access key id", "b.json:1 client secret"])
            self.assertNotIn("ASIAABCDEFGHIJKLMNOP", " ".join(hits))
        finally:
            shutil.rmtree(d)


if __name__ == "__main__":
    unittest.main()

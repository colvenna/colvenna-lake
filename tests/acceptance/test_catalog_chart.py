"""Offline tests of the values Colvenna Lake gives Colvenna Catalog's chart (#295): install-catalog.sh's
catalog_values. Colvenna Lake's own chart, before the catalog became a product of its own, set these
keys; the catalog's chart reads them all, under the same names, and an environment is taken over in
place only if they keep their values: the names, the roles, the database and its Secret, the warehouse,
TLS, the client and monitoring labels, resources and replicas. And the catalog's Helm release is changed
with all of them, every time, by install-catalog.sh alone (install.sh's catalog step, an upgrade too): an
upgrade that reused what the release stored would keep what an environment installed before #295 stored,
which has no client or monitoring label (they were Colvenna Lake's chart's defaults), and the catalog
chart's own defaults would then shut every Colvenna Lake client and Prometheus out.

With COLVENNA_CATALOG_CHART=<the pinned release's chart package> (fetch-catalog.sh keeps it in
deploy/.state/<env>/catalog/) and helm on PATH, the chart is rendered with them too, and what it makes is
checked: the objects Colvenna Lake's chart made, under the same names, configured as they were."""
import os
import re
import shutil
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
SCRIPT = os.path.join(REPO, "deploy", "scripts", "catalog", "install-catalog.sh")
ENV = {"IMAGE": "123456789012.dkr.ecr.us-west-2.amazonaws.com/colvenna-lake-uat/colvenna-catalog@sha256:" + "4" * 64,
       "BUCKET": "colvennalakeuatfoundation-warehouse0123abcd-xyz",
       "DB_ENDPOINT": "colvennalakeuatfoundation-catalogdatabase.example.us-west-2.rds.amazonaws.com",
       "SERVER_ROLE": "arn:aws:iam::123456789012:role/colvenna-lake-uat-catalog",
       "DATA_ROLE": "arn:aws:iam::123456789012:role/colvenna-lake-uat-catalog-data",
       "WRITE_NODES": "2", "SHA": "a" * 40, "CATALOG_CANDIDATE": "v1.0.0-rc.1", "CATALOG_BUILT_FROM": "b" * 40,
       "COLVENNA_ENV": "uat", "COLVENNA_REGION": "us-west-2"}
# Every value Colvenna Lake's chart (deploy/helm/colvenna-catalog, removed in #295) had, and what its
# install gave it.
LAKE_VALUES = {
    "image.repository": ENV["IMAGE"].split("@")[0], "image.digest": "sha256:" + "4" * 64,
    "replicas": "2", "spreadAcrossNodes": "true", "eligibleNodes": "2",
    "nodeSelector.colvenna-lake/role": "write",
    "serviceAccount.name": "colvenna-catalog", "serviceAccount.roleArn": ENV["SERVER_ROLE"],
    "environment": "uat", "region": "us-west-2",
    "warehouse.name": "colvenna", "warehouse.root": f"s3://{ENV['BUCKET']}/warehouse",
    "database.url": f"jdbc:postgresql://{ENV['DB_ENDPOINT']}:5432/colvenna_catalog?sslmode=verify-full"
                    "&sslrootcert=/etc/colvenna-catalog/db-ca/ca.pem",
    "database.poolSize": "16", "database.auxPoolSize": "4",
    "databaseSecret": "colvenna-catalog-db", "databaseCaConfigMap": "colvenna-catalog-db-ca",
    "tlsSecret": "colvenna-catalog-tls",
    "dataTokens.mode": "aws-sts", "dataTokens.roleArn": ENV["DATA_ROLE"], "dataTokens.duration": "PT3H",
    "resources": '{"requests":{"cpu":"500m","memory":"1Gi"},"limits":{"cpu":"2","memory":"2Gi"}}',
    "clientNamespaceLabel.key": "colvenna-lake.io/catalog-client", "clientNamespaceLabel.value": "true",
    "metricsClientLabel": "colvenna-lake.io/monitoring",
    "podAnnotations.colvenna-lake/source-sha": "a" * 40,
}


def function(name):
    with open(SCRIPT) as f:
        text = f.read()
    start = text.index("\n%s() {" % name) + 1
    return text[start:text.index("\n}\n", start) + 3]


def values(**env):
    run = subprocess.run(["bash", "-c", "set -euo pipefail\n%s\ncatalog_values" % function("catalog_values")],
                         env={"PATH": os.environ["PATH"], **ENV, **env}, capture_output=True, text=True, timeout=30)
    assert run.returncode == 0, run.stderr
    return run.stdout.splitlines()


def pairs(args):
    """{key: value} of --set, --set-string and --set-json arguments."""
    out = {}
    for flag, assignment in zip(args[::2], args[1::2]):
        assert flag in ("--set", "--set-string", "--set-json"), flag
        key, _, value = assignment.partition("=")
        out[key] = value
    return out


class CatalogValuesTest(unittest.TestCase):
    def test_every_value_colvenna_lake_s_chart_had_is_given_as_it_was(self):
        given = pairs(values())
        self.assertEqual({k: given.get(k) for k in LAKE_VALUES}, LAKE_VALUES)

    def test_beyond_them_only_the_release_s_names_and_no_load_balancer(self):
        given = pairs(values())
        self.assertEqual({k: v for k, v in given.items() if k not in LAKE_VALUES}, {
            "podAnnotations.colvenna-catalog/release": "v1.0.0-rc.1",
            "podAnnotations.colvenna-catalog/source-sha": "b" * 40,
            "internalLoadBalancer.enabled": "false"})

    def test_a_shorter_token_lifetime_for_the_renewal_test(self):
        self.assertEqual(pairs(values(DATA_TOKEN_DURATION="PT1H30M"))["dataTokens.duration"], "PT1H30M")

    def test_a_write_node_group_of_one(self):
        self.assertEqual(pairs(values(WRITE_NODES="1"))["eligibleNodes"], "1")


# A command that changes the catalog's Helm release: helm_upgrade (lib/helm.sh) or helm upgrade, its first
# argument the release colvenna-catalog.
CHANGES_RELEASE = re.compile(r"\bhelm(?:_upgrade| upgrade(?: --install| -i)?) +[\"']?colvenna-catalog\b")


class UpgradeTest(unittest.TestCase):
    def test_the_release_is_changed_only_with_every_value(self):
        tracked = subprocess.run(["git", "-C", REPO, "ls-files", "*.sh"], check=True, capture_output=True,
                                 text=True).stdout.split()
        calls = []
        for path in tracked:
            with open(os.path.join(REPO, path)) as f:
                # A command continued over lines is one.
                calls += [(path, line) for line in f.read().replace("\\\n", " ").splitlines()
                          if CHANGES_RELEASE.search(line)]
        self.assertEqual([path for path, _ in calls], ["deploy/scripts/catalog/install-catalog.sh"], calls)
        [(_, call)] = calls
        self.assertIn('"${values[@]}"', call)
        self.assertNotRegex(call, r"--(reset-then-)?reuse-values")
        with open(SCRIPT) as f:
            self.assertIn("mapfile -t values < <(catalog_values)\n", f.read())
        # Among them the labels the catalog chart's defaults do not have.
        given = pairs(values())
        self.assertEqual((given["clientNamespaceLabel.key"], given["clientNamespaceLabel.value"],
                          given["metricsClientLabel"]),
                         ("colvenna-lake.io/catalog-client", "true", "colvenna-lake.io/monitoring"))


@unittest.skipUnless(os.environ.get("COLVENNA_CATALOG_CHART") and shutil.which("helm"),
                     "COLVENNA_CATALOG_CHART (the pinned release's chart package) and helm")
class RenderedChartTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import yaml  # with the chart only: rendering needs a YAML reader

        run = subprocess.run(["helm", "template", "colvenna-catalog", os.environ["COLVENNA_CATALOG_CHART"],
                              "--namespace", "colvenna-catalog", *values()], capture_output=True, text=True, timeout=60)
        assert run.returncode == 0, run.stderr
        cls.objects = {(o["kind"], o["metadata"]["name"]): o for o in yaml.safe_load_all(run.stdout) if o}

    def get(self, kind, name):
        self.assertIn((kind, name), self.objects)
        return self.objects[(kind, name)]

    def test_the_objects_colvenna_lake_s_chart_made_and_no_other(self):
        self.assertEqual(sorted(self.objects), [
            ("ConfigMap", "colvenna-catalog-config"), ("Deployment", "colvenna-catalog"),
            ("NetworkPolicy", "colvenna-catalog"), ("PodDisruptionBudget", "colvenna-catalog"),
            ("Service", "colvenna-catalog"), ("ServiceAccount", "colvenna-catalog")])

    def test_the_deployment_is_taken_over_in_place(self):
        d = self.get("Deployment", "colvenna-catalog")
        # The selector cannot change on an existing Deployment: it must be the one Colvenna Lake's had.
        self.assertEqual(d["spec"]["selector"]["matchLabels"],
                         {"app.kubernetes.io/name": "colvenna-catalog", "app.kubernetes.io/instance": "colvenna-catalog"})
        self.assertEqual(d["spec"]["replicas"], 2)
        pod = d["spec"]["template"]
        spec = pod["spec"]
        self.assertEqual(spec["serviceAccountName"], "colvenna-catalog")
        self.assertEqual(spec["nodeSelector"], {"colvenna-lake/role": "write"})
        constraint = spec["topologySpreadConstraints"][0]
        self.assertEqual((constraint["whenUnsatisfiable"], constraint["minDomains"]), ("DoNotSchedule", 2))
        [container] = spec["containers"]
        self.assertEqual(container["image"], ENV["IMAGE"])
        self.assertEqual(container["resources"], {"requests": {"cpu": "500m", "memory": "1Gi"},
                                                  "limits": {"cpu": "2", "memory": "2Gi"}})
        refs = {e["name"]: e["valueFrom"]["secretKeyRef"]["name"] for e in container["env"]}
        self.assertEqual(refs, {"COLVENNA_JDBC_USER": "colvenna-catalog-db", "COLVENNA_JDBC_PASSWORD": "colvenna-catalog-db",
                                "COLVENNA_TLS_KEYSTORE_PASSWORD": "colvenna-catalog-tls"})
        volumes = {v["name"]: v for v in spec["volumes"]}
        self.assertEqual(volumes["tls"]["secret"]["secretName"], "colvenna-catalog-tls")
        self.assertEqual(volumes["db-ca"]["configMap"]["name"], "colvenna-catalog-db-ca")
        notes = pod["metadata"]["annotations"]
        self.assertEqual(notes["colvenna-lake/source-sha"], "a" * 40)
        self.assertEqual(notes["colvenna-catalog/release"], "v1.0.0-rc.1")

    def test_the_server_s_settings(self):
        data = self.get("ConfigMap", "colvenna-catalog-config")["data"]
        expected = {"COLVENNA_WAREHOUSE_NAME": "colvenna", "COLVENNA_WAREHOUSE_ROOT": LAKE_VALUES["warehouse.root"],
                    "COLVENNA_ENVIRONMENT": "uat", "COLVENNA_JDBC_URL": LAKE_VALUES["database.url"],
                    "COLVENNA_JDBC_POOL_SIZE": "16", "COLVENNA_JDBC_AUX_POOL_SIZE": "4", "AWS_REGION": "us-west-2",
                    "COLVENNA_DATA_TOKEN_MODE": "aws-sts", "COLVENNA_DATA_TOKEN_ROLE_ARN": ENV["DATA_ROLE"],
                    "COLVENNA_DATA_TOKEN_DURATION": "PT3H", "COLVENNA_PORT": "8181", "COLVENNA_METRICS_PORT": "9100"}
        self.assertEqual({k: data.get(k) for k in expected}, expected)

    def test_the_identity_and_the_network(self):
        account = self.get("ServiceAccount", "colvenna-catalog")
        self.assertEqual(account["metadata"]["annotations"]["eks.amazonaws.com/role-arn"], ENV["SERVER_ROLE"])
        rules = self.get("NetworkPolicy", "colvenna-catalog")["spec"]["ingress"]
        self.assertEqual([(r["from"][0]["namespaceSelector"]["matchLabels"], r["ports"][0]["port"]) for r in rules],
                         [({"colvenna-lake.io/catalog-client": "true"}, 8181), ({"colvenna-lake.io/monitoring": "true"}, 9100)])
        ports = {p["name"]: p["port"] for p in self.get("Service", "colvenna-catalog")["spec"]["ports"]}
        self.assertEqual(ports, {"https": 8181, "metrics": 9100})
        self.assertEqual(self.get("PodDisruptionBudget", "colvenna-catalog")["spec"]["minAvailable"], 1)


if __name__ == "__main__":
    unittest.main()

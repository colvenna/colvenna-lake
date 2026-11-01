"""Offline tests of the deployment cases' judge, tests/acceptance/deploy/deploy_checks.py."""
import importlib.util
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("deploy_checks", os.path.join(HERE, "deploy", "deploy_checks.py"))
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)

STEPS = """tools           kubectl, Helm and crane
foundation      ColvennaLakeUatFoundation: VPC, EKS
sources         the test sources (--fixtures)
catalog         colvenna-catalog
strimzi         the Strimzi operator (--fixtures)
readers         the Spark Iceberg reader image (--fixtures)
verify          colvenna-catalog's health
"""
ACCESS = "\n".join(f"check {i} expected=401 actual=401" for i in range(14))
VERIFY = f"""colvenna-catalog/colvenna-catalog OK 2/2 ready
catalog-schema OK database at schema version 4, Colvenna Catalog v1.0.0 applies 4
{ACCESS}
PASS
https://console.colvenna-uat.example.com/: 302 to https://colvenna-lake-uat.auth.us-west-2.amazoncognito.com sign-in
https://bi.colvenna-uat.example.com/: 302 to https://colvenna-lake-uat.auth.us-west-2.amazoncognito.com sign-in
"""


def run(*argv):
    out, err = io.StringIO(), io.StringIO()
    checks.results.clear()
    with redirect_stdout(out), redirect_stderr(err):
        status = checks.main(["deploy_checks.py", *argv])
    rows = {line.split("\t")[0]: line.split("\t")[1] for line in out.getvalue().splitlines()}
    return status, rows, out.getvalue()


class Dir:
    """A temporary directory with files written into it."""

    def __init__(self, files):
        self.tmp = tempfile.TemporaryDirectory()
        for name, content in files.items():
            path = os.path.join(self.tmp.name, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w") as f:
                f.write(content if isinstance(content, str) else json.dumps(content))

    def __enter__(self):
        return self.tmp.name

    def __exit__(self, *_):
        self.tmp.cleanup()


def evidence(steps, verify=VERIFY, last="Installed uat at abc; evidence: x"):
    return {"steps.tsv": "".join(f"{s}\t{o}\t10\n" for s, o in steps), "verify.log": verify,
            "install.log": f"environment uat in us-west-2 at abc by me\n2026-10-22T01:00:00Z tools: x\n{last}\n"}


ALL_OK = [(s, "ok") for s in ("tools", "foundation", "sources", "catalog", "strimzi", "readers", "verify")]
PLATFORM = [(s, "skipped (no --fixtures)" if s in ("sources", "strimzi", "readers") else "ok")
            for s in ("tools", "foundation", "sources", "catalog", "strimzi", "readers", "verify")]


def stacks(created="2026-10-22T01:10:00+00:00", names=("ColvennaLakeUatFoundation", "ColvennaLakeUatCatalog")):
    return [{"name": n, "status": "CREATE_COMPLETE", "created": created, "updated": None} for n in names]


def inventory(resources=None, nodes=("i-1", "i-2"), helm=None, workloads=None, volumes=("pv-1",),
              images="colvenna-lake-uat/colvenna-catalog\t3\n", stack_list=None, secrets=None, state="kubeconfig\nregion\n",
              namespaces="colvenna-catalog\ncolvenna-data\n", crds="laketables.colvenna-lake.io\n",
              secret_names="colvenna-catalog/colvenna-catalog-db\ncolvenna-data/orders-reader\n"):
    resources = resources or {"ColvennaLakeUatFoundation": [
        {"logical": "Db", "physical": "db-1", "type": "AWS::RDS::DBInstance", "status": "UPDATE_COMPLETE"},
        {"logical": "Fn", "physical": "fn-1", "type": "AWS::Lambda::Function", "status": "UPDATE_COMPLETE"}]}
    files = {"stacks.json": stack_list or stacks(names=tuple(resources)),
             "nodes.json": {"items": [{"spec": {"providerID": f"aws:///us-west-2a/{n}"}} for n in nodes]},
             "helm.json": helm if helm is not None else [
                 {"name": "colvenna-catalog", "namespace": "colvenna-catalog", "chart": "colvenna-catalog-0.1.0",
                  "app_version": "1", "status": "deployed"}],
             "workloads.json": {"items": workloads if workloads is not None else [workload("abc", "img:1")]},
             "volumes.json": {"items": [{"metadata": {"name": v}} for v in volumes]},
             "images.tsv": images,
             "secrets.json": secrets if secrets is not None else [["colvenna-lake-uat/catalog-db", "ColvennaLakeUatCatalog"]],
             "state.txt": state, "namespaces.txt": namespaces, "crds.txt": crds, "secret-names.txt": secret_names}
    for stack, rows in resources.items():
        files[f"resources-{stack}.json"] = rows
    return files


def workload(commit, image, name="colvenna-catalog"):
    return {"kind": "Deployment", "metadata": {"namespace": "colvenna-catalog", "name": name},
            "spec": {"template": {"metadata": {"annotations": {"colvenna-lake/source-sha": commit}},
                                  "spec": {"containers": [{"image": image}]}}}}


class StepsTest(unittest.TestCase):
    def test_a_full_install_passes(self):
        with Dir({"list.txt": STEPS, **{f"e/{k}": v for k, v in evidence(ALL_OK).items()}}) as d:
            status, rows, _ = run("steps", f"{d}/list.txt", f"{d}/e")
        self.assertEqual(status, 0)
        self.assertEqual(rows, {"every-step-done": "PASS", "verify-passed": "PASS", "install-finished": "PASS"})

    def test_a_failed_or_missing_step_fails(self):
        with Dir({"list.txt": STEPS, **{f"e/{k}": v for k, v in evidence(ALL_OK[:3] + [("catalog", "FAILED")]).items()}}) as d:
            status, rows, out = run("steps", f"{d}/list.txt", f"{d}/e")
        self.assertEqual(status, 1)
        self.assertEqual(rows["every-step-done"], "FAIL")
        self.assertIn("catalog: FAILED", out)
        self.assertIn("verify: missing", out)

    def test_a_resumed_install_is_its_runs_together(self):
        first = evidence(ALL_OK[:3] + [("catalog", "FAILED")], verify="", last="2026-10-22T01:05:00Z catalog: x")
        second = evidence(ALL_OK[3:])
        files = {"list.txt": STEPS, **{f"a/{k}": v for k, v in first.items()}, **{f"b/{k}": v for k, v in second.items()}}
        with Dir(files) as d:
            status, rows, _ = run("steps", f"{d}/list.txt", f"{d}/a", f"{d}/b")
        self.assertEqual(status, 0, rows)

    def test_verify_needs_every_access_check_and_both_hosts(self):
        bad = VERIFY.replace("check 3 expected=401 actual=401", "check 3 expected=401 actual=200")
        with Dir({"list.txt": STEPS, **{f"e/{k}": v for k, v in evidence(ALL_OK, verify=bad).items()}}) as d:
            self.assertEqual(run("steps", f"{d}/list.txt", f"{d}/e")[1]["verify-passed"], "FAIL")
        one_host = "\n".join(line for line in VERIFY.splitlines() if not line.startswith("https://bi."))
        with Dir({"list.txt": STEPS, **{f"e/{k}": v for k, v in evidence(ALL_OK, verify=one_host).items()}}) as d:
            self.assertEqual(run("steps", f"{d}/list.txt", f"{d}/e")[1]["verify-passed"], "FAIL")

    def test_platform_only_needs_the_fixture_steps_skipped(self):
        with Dir({"list.txt": STEPS, **{f"e/{k}": v for k, v in evidence(PLATFORM).items()}}) as d:
            status, rows, _ = run("steps", f"{d}/list.txt", "--platform", f"{d}/e")
            self.assertEqual(status, 0, rows)
            self.assertEqual(rows["fixture-steps-skipped"], "PASS")
            # The same evidence judged as a --fixtures install: the skipped steps are not done.
            self.assertEqual(run("steps", f"{d}/list.txt", f"{d}/e")[1]["every-step-done"], "FAIL")


class FreshAndPlatformTest(unittest.TestCase):
    def test_every_stack_made_after_the_install_began(self):
        files = {**{f"e/{k}": v for k, v in evidence(ALL_OK).items()}, **{f"i/{k}": v for k, v in inventory().items()}}
        with Dir(files) as d:
            self.assertEqual(run("fresh", f"{d}/i", f"{d}/e")[1], {"installed-from-nothing": "PASS"})

    def test_a_stack_older_than_the_install_is_no_install_from_nothing(self):
        older = stacks(created="2026-10-21T00:00:00+00:00", names=("ColvennaLakeUatFoundation",))
        files = {**{f"e/{k}": v for k, v in evidence(ALL_OK).items()},
                 **{f"i/{k}": v for k, v in inventory(stack_list=older).items()}}
        with Dir(files) as d:
            self.assertEqual(run("fresh", f"{d}/i", f"{d}/e")[0], 1)

    def test_platform_only_has_nothing_of_the_fixtures(self):
        with Dir(inventory()) as d:
            self.assertEqual(run("platform", d)[1], {"no-fixture-resources": "PASS"})
        with_sources = stacks(names=("ColvennaLakeUatFoundation", "ColvennaLakeUatTestMysql"))
        strimzi = [{"name": "colvenna-strimzi", "namespace": "colvenna-lake-kafka", "chart": "s", "status": "deployed"}]
        with Dir(inventory(stack_list=with_sources, helm=strimzi)) as d:
            status, _, out = run("platform", d)
        self.assertEqual(status, 1)
        self.assertIn("ColvennaLakeUatTestMysql", out)
        self.assertIn("colvenna-strimzi", out)

    def test_whatever_the_fixtures_leave_behind_is_found(self):
        leftovers = {
            "a test source's secret": {"secrets": [["ColvennaLakeUatTestPostgresCdc-x", "ColvennaLakeUatTestPostgres"]]},
            "a source login Secret": {"secret_names": "colvenna-data/mysql-cdc\n"},
            "the Kafka namespace": {"namespaces": "colvenna-data\ncolvenna-lake-kafka\n"},
            "a Strimzi CRD": {"crds": "kafkas.kafka.strimzi.io\n"},
            "the reader image's record": {"state": "readers-abc.json\nregion\n"},
        }
        for what, fields in leftovers.items():
            with Dir(inventory(**fields)) as d:
                self.assertEqual(run("platform", d)[1], {"no-fixture-resources": "FAIL"}, what)

    def test_an_inventory_without_a_listing_proves_nothing(self):
        files = inventory()
        del files["crds.txt"]
        with Dir(files) as d:
            status, rows, out = run("platform", d)
        self.assertEqual((status, rows), (1, {"no-fixture-resources": "FAIL"}))
        self.assertIn("crds.txt", out)


# An AWS account id: twelve digits standing alone (as in a registry's host), not inside a digest.
ACCOUNT_ID = r"(?<![0-9A-Za-z])[0-9]{12}(?![0-9A-Za-z])"


class ImagesTest(unittest.TestCase):
    """no-upstream-images: what runs is the environment's own, its copies of the third-party images
    too, but AWS's own add-ons and agents; a finished pod does not count."""

    OWN = "111122223333.dkr.ecr.us-west-2.amazonaws.com"
    AWS = "222222222222.dkr.ecr.us-west-2.amazonaws.com"  # an AWS-owned registry (EKS add-ons), synthetic
    COPY = f"{OWN}/colvenna-lake-uat/third-party:marquez-0721c976cff1@sha256:" + "0" * 64

    def files(self, workloads, pods, meta=True):
        files = inventory(workloads=workloads)
        files["pods.json"] = {"items": pods}
        if meta:
            files["meta.json"] = {"env": "uat", "region": "us-west-2", "settings": {"COLVENNA_ACCOUNT": "111122223333"}}
        return files

    def pod(self, namespace, name, image, phase="Running"):
        return {"kind": "Pod", "metadata": {"namespace": namespace, "name": name},
                "spec": {"containers": [{"name": "c", "image": image}]}, "status": {"phase": phase}}

    def daemonset(self, namespace, name, image):
        return {"kind": "DaemonSet", "metadata": {"namespace": namespace, "name": name},
                "spec": {"template": {"spec": {"containers": [{"name": "c", "image": image}],
                                               "initContainers": [{"name": "init", "image": image}]}}}}

    def test_the_environment_s_own_images_and_aws_s_add_ons(self):
        workloads = [workload("abc", f"{self.OWN}/colvenna-lake-uat/colvenna-catalog@sha256:" + "1" * 64),
                     workload("abc", self.COPY, name="marquez"),
                     self.daemonset("kube-system", "aws-node", f"{self.AWS}/amazon-k8s-cni:v1"),
                     self.daemonset("amazon-guardduty", "aws-guardduty-agent", f"{self.AWS}/aws-guardduty-agent:v1")]
        pods = [self.pod("colvenna-lineage", "marquez-1", self.COPY),
                # Finished: it runs nothing and never starts again.
                self.pod("colvenna-test", "probe", "docker.io/library/busybox:1", phase="Succeeded"),
                self.pod("colvenna-test", "probe-2", "docker.io/library/busybox:1", phase="Failed")]
        with Dir(self.files(workloads, pods)) as d:
            status, rows, out = run("images", d)
        self.assertEqual((status, rows), (0, {"no-upstream-images": "PASS"}), out)
        self.assertIn("every image from colvenna-lake-uat/... but AWS's own in 2 of them", out)
        # A verdict names repositories, not a registry: its host carries the account id.
        self.assertNotRegex(out, ACCOUNT_ID)

    def test_an_image_from_upstream_is_found_wherever_it_runs(self):
        cases = {
            "a workload": ([workload("abc", "docker.io/marquezproject/marquez:0.51.1")], []),
            "a running pod": ([], [self.pod("colvenna-lake-kafka", "colvenna-dev-node-0", "quay.io/strimzi/kafka:1.2.0")]),
            "a pod that cannot pull": ([], [self.pod("colvenna-data", "debezium", "quay.io/debezium/server@sha256:"
                                                    + "2" * 64, phase="Pending")]),
            # Another account's ECR outside AWS's namespaces: a release registry, say.
            "another account's registry": ([workload("abc", f"{self.AWS}/colvenna-lake-release/colvenna-catalog:v1")], []),
            # The environment's own account in another region.
            "another region": ([workload("abc", "111122223333.dkr.ecr.us-east-2.amazonaws.com/x:1")], []),
            "an AWS registry in our namespace": ([], [self.pod("colvenna-system", "x", f"{self.AWS}/eks/coredns:v1")]),
            # The environment's own registry, not its repositories: straight from the release
            # registry's copy, or another environment's (whose name begins as this one's does).
            "the release registry": ([workload("abc", f"{self.OWN}/colvenna-lake-release/colvenna-catalog@sha256:" + "3" * 64)],
                                     []),
            "another environment": ([], [self.pod("colvenna-data", "y", f"{self.OWN}/colvenna-lake-uat2/third-party:x@sha256:"
                                                  + "4" * 64)]),
        }
        for what, (workloads, pods) in cases.items():
            with Dir(self.files(workloads + [workload("abc", self.COPY)], pods)) as d:
                status, rows, out = run("images", d)
            self.assertEqual((status, rows), (1, {"no-upstream-images": "FAIL"}), what)
            self.assertNotRegex(out, ACCOUNT_ID, what)
        with Dir(self.files(cases["the release registry"][0], [])) as d:
            out = run("images", d)[2]
        self.assertIn("ecr:us-west-2/colvenna-lake-release/colvenna-catalog@sha256:", out)

    def test_an_inventory_without_its_pods_or_settings_proves_nothing(self):
        files = self.files([workload("abc", self.COPY)], [])
        del files["pods.json"]
        with Dir(files) as d:
            status, rows, out = run("images", d)
        self.assertEqual((status, rows), (1, {"no-upstream-images": "FAIL"}))
        self.assertIn("pods.json", out)
        with Dir(self.files([workload("abc", self.COPY)], [], meta=False)) as d:
            self.assertEqual(run("images", d)[1], {"no-upstream-images": "FAIL"})
        with Dir(self.files([], [])) as d:
            self.assertEqual(run("images", d)[1], {"no-upstream-images": "FAIL"})


class RepeatAndUpgradeTest(unittest.TestCase):
    def test_a_repeat_that_changed_nothing_passes(self):
        files = {**{f"a/{k}": v for k, v in inventory().items()}, **{f"b/{k}": v for k, v in inventory().items()}}
        with Dir(files) as d:
            status, rows, _ = run("repeat", f"{d}/a", f"{d}/b")
        self.assertEqual(status, 0, rows)

    def test_a_repeat_that_replaced_a_resource_or_built_an_image_fails(self):
        replaced = {"ColvennaLakeUatFoundation": [
            {"logical": "Db", "physical": "db-2", "type": "AWS::RDS::DBInstance", "status": "UPDATE_COMPLETE"},
            {"logical": "Fn", "physical": "fn-1", "type": "AWS::Lambda::Function", "status": "UPDATE_COMPLETE"}]}
        files = {**{f"a/{k}": v for k, v in inventory().items()},
                 **{f"b/{k}": v for k, v in inventory(resources=replaced, images="colvenna-lake-uat/colvenna-catalog\t4\n").items()}}
        with Dir(files) as d:
            status, rows, out = run("repeat", f"{d}/a", f"{d}/b")
        self.assertEqual(status, 1)
        self.assertEqual(rows["nothing-made-or-replaced"], "FAIL")
        self.assertEqual(rows["no-image-built"], "FAIL")
        self.assertIn("~Db", out)

    def test_a_repeat_from_the_built_commit_to_its_release_tag_is_the_same_product(self):
        # A release tag's commit differs from the one its images were built from only under release/:
        # the components' commit annotations move from one to the other, nothing else changes.
        after = inventory(workloads=[workload("tag", "img:1")])
        files = {**{f"a/{k}": v for k, v in inventory().items()}, **{f"b/{k}": v for k, v in after.items()}}
        with Dir(files) as d:
            status, rows, _ = run("repeat", f"{d}/a", f"{d}/b", "--same-product", "abc", "tag")
            self.assertEqual(status, 0, rows)
            status, rows, _ = run("repeat", f"{d}/a", f"{d}/b")
            self.assertEqual(rows["same-workloads"], "FAIL")
            status, rows, _ = run("repeat", f"{d}/a", f"{d}/b", "--same-product", "abc", "other")
            self.assertEqual(rows["same-workloads"], "FAIL")
        # A component the second install left annotated with the commit the images were built from.
        files = {**{f"a/{k}": v for k, v in inventory().items()}, **{f"b/{k}": v for k, v in inventory().items()}}
        with Dir(files) as d:
            status, rows, _ = run("repeat", f"{d}/a", f"{d}/b", "--same-product", "abc", "tag")
            self.assertEqual(status, 0, rows)

    def test_an_upgrade_keeps_the_data_and_runs_the_commit(self):
        after = inventory(workloads=[workload("def", "img:2")], images="colvenna-lake-uat/colvenna-catalog\t4\n")
        files = {**{f"a/{k}": v for k, v in inventory().items()}, **{f"b/{k}": v for k, v in after.items()}}
        with Dir(files) as d:
            status, rows, _ = run("upgrade", f"{d}/a", f"{d}/b", "def")
        self.assertEqual(status, 0, rows)

    def test_an_upgrade_that_lost_a_repository_fails(self):
        def foundation(*repositories):
            return {"ColvennaLakeUatFoundation": [
                {"logical": "Db", "physical": "db-1", "type": "AWS::RDS::DBInstance", "status": "UPDATE_COMPLETE"},
                *({"logical": logical, "physical": physical, "type": "AWS::ECR::Repository", "status": "UPDATE_COMPLETE"}
                  for logical, physical in repositories)]}
        before = inventory(resources=foundation(("QueryEngineImageRepository9E8D7C6B", "colvenna-lake-uat/query-engine"),
                                                ("FlinkImageRepository0A1B2C3D", "colvenna-lake-uat/flink-base")))
        gone = inventory(resources=foundation(("QueryEngineImageRepository9E8D7C6B", "colvenna-lake-uat/query-engine")),
                         workloads=[workload("def", "img:2")])
        files = {**{f"a/{k}": v for k, v in before.items()}, **{f"b/{k}": v for k, v in gone.items()}}
        with Dir(files) as d:
            status, rows, out = run("upgrade", f"{d}/a", f"{d}/b", "def")
        self.assertEqual(rows["data-kept"], "FAIL")
        self.assertIn("FlinkImageRepository0A1B2C3D colvenna-lake-uat/flink-base -> gone", out)

    def test_an_upgrade_that_replaced_the_database_or_lost_a_volume_fails(self):
        replaced = {"ColvennaLakeUatFoundation": [
            {"logical": "Db", "physical": "db-2", "type": "AWS::RDS::DBInstance", "status": "UPDATE_COMPLETE"}]}
        after = inventory(resources=replaced, volumes=(), workloads=[workload("abc", "img:1")])
        files = {**{f"a/{k}": v for k, v in inventory().items()}, **{f"b/{k}": v for k, v in after.items()}}
        with Dir(files) as d:
            status, rows, _ = run("upgrade", f"{d}/a", f"{d}/b", "def")
        self.assertEqual(status, 1)
        self.assertEqual(rows["data-kept"], "FAIL")
        self.assertEqual(rows["volumes-kept"], "FAIL")
        self.assertEqual(rows["components-at-the-commit"], "FAIL")
        self.assertEqual(rows["images-changed"], "FAIL")


def account(stacks_west, roles, dns, repositories=("colvenna-lake-release/colvenna-catalog",)):
    return {"account-regions.txt": "us-west-2\n",
            "account-stacks-us-west-2.json": [{"name": n, "status": "UPDATE_COMPLETE", "updated": u}
                                              for n, u in stacks_west],
            "account-repositories-us-west-2.json": list(repositories),
            "account-roles.json": list(roles),
            "account-dns.json": [{"name": n, "type": "A"} for n in dns]}


class OthersTest(unittest.TestCase):
    BEFORE = account([("ColvennaLakeUatFoundation", "t1"), ("ColvennaLakeUat2Foundation", "t2")],
                     ["colvenna-lake-uat-catalog", "colvenna-lake-uat2-catalog", "ColvennaLakeUatFoundation-Role"],
                     ["console.colvenna-uat.example.com.", "console.colvenna-uat2.example.com."],
                     ["colvenna-lake-uat/colvenna-catalog", "colvenna-lake-release/colvenna-catalog"])

    def test_the_environment_gone_and_the_others_as_they_were(self):
        after = account([("ColvennaLakeUat2Foundation", "t2")], ["colvenna-lake-uat2-catalog"],
                        ["console.colvenna-uat2.example.com."])
        files = {**{f"a/{k}": v for k, v in self.BEFORE.items()}, **{f"b/{k}": v for k, v in after.items()}}
        with Dir(files) as d:
            status, rows, _ = run("others", f"{d}/a", f"{d}/b", "uat")
        self.assertEqual(status, 0, rows)

    def test_a_leftover_or_a_touched_neighbour_fails(self):
        after = account([("ColvennaLakeUatEntry", "t9"), ("ColvennaLakeUat2Foundation", "t3")], ["colvenna-lake-uat2-catalog"],
                        [], ["colvenna-lake-uat/colvenna-catalog"])
        files = {**{f"a/{k}": v for k, v in self.BEFORE.items()}, **{f"b/{k}": v for k, v in after.items()}}
        with Dir(files) as d:
            status, rows, out = run("others", f"{d}/a", f"{d}/b", "uat")
        self.assertEqual(status, 1)
        self.assertEqual(rows["nothing-of-it-left"], "FAIL")
        self.assertEqual(rows["others-untouched"], "FAIL")
        self.assertIn("ColvennaLakeUatEntry", out)
        self.assertIn("ColvennaLakeUat2Foundation", out)
        self.assertIn("colvenna-lake-release/colvenna-catalog", out)
        self.assertIn("console.colvenna-uat2", out)


class OthersEverythingTest(unittest.TestCase):
    """Every kind uninstall.sh deletes, the environment's told from a neighbour whose name starts the same."""

    def inventory(self, uat, uate2):
        def pick(*items):
            return [i for i, keep in items if keep]
        return {
            "account-regions.txt": "us-east-2\n",
            "meta.json": {"env": "uat", "region": "us-east-2", "cluster": "Cluster-uat", "issuer": "https://oidc.eks/id/AAA"},
            "account-stacks-us-east-2.json": pick(({"name": "ColvennaLakeUatFoundation", "status": "C", "updated": "t"}, uat),
                                                  ({"name": "ColvennaLakeUate2Foundation", "status": "C", "updated": "t"}, uate2)),
            "account-repositories-us-east-2.json": pick(("colvenna-lake-uat/query-engine", uat),
                                                         ("colvenna-lake-uate2/query-engine", uate2),
                                                         ("colvenna-lake-release/query-engine", True)),
            "account-databases-us-east-2.json": pick(("colvennalakeuatfoundation-db1", uat), ("colvennalakeuate2foundation-db1", uate2)),
            "account-snapshots-us-east-2.json": pick((["final-1", "colvennalakeuatfoundation-db1"], uat),
                                                     (["final-2", "colvennalakeuate2foundation-db1"], uate2)),
            "account-userpools-us-east-2.json": pick(("colvenna-lake-uat", uat), ("colvenna-lake-uate2", uate2)),
            "account-secrets-us-east-2.json": pick((["colvenna-lake-uat/catalog-db", None], uat),
                                                   (["DbSecret-x", "ColvennaLakeUatFoundation"], uat),
                                                   (["colvenna-lake-uate2/catalog-db", None], uate2)),
            "account-loggroups-us-east-2.json": pick(("/aws/eks/Cluster-uat/cluster", uat), ("/colvenna-lake-uat/console-audit", uat),
                                                     ("/aws/rds/instance/colvennalakeuatfoundation-db1/postgresql", uat),
                                                     ("/aws/lambda/ColvennaLakeUatFoundation-Handler", uat),
                                                     ("/aws/eks/Cluster-uate2/cluster", uate2), ("/colvenna-lake-uate2/console-audit", uate2)),
            "account-volumes-us-east-2.json": pick((["vol-1", "Cluster-uat"], uat), (["vol-2", "Cluster-uate2"], uate2)),
            "account-buckets.json": pick(("colvennalakeuatfoundation-warehouse-1", uat), ("colvennalakeuate2foundation-warehouse-1", uate2)),
            "account-oidc.json": pick(("arn:aws:iam::000000000000:oidc-provider/oidc.eks/id/AAA", uat),
                                      ("arn:aws:iam::000000000000:oidc-provider/oidc.eks/id/BBB", uate2)),
            "account-roles.json": pick(("colvenna-lake-uat-catalog", uat), ("colvenna-lake-uate2-catalog", uate2)),
            "account-dns.json": pick(({"name": "console.colvenna-uat.example.com.", "type": "A"}, uat),
                                     ({"name": "console.colvenna-uate2.example.com.", "type": "A"}, uate2)),
        }

    def judge(self, after):
        files = {**{f"a/{k}": v for k, v in self.inventory(True, True).items()},
                 **{f"b/{k}": v for k, v in after.items()}}
        with Dir(files) as d:
            return run("others", f"{d}/a", f"{d}/b", "uat")

    def test_every_kind_gone_and_the_neighbour_whole(self):
        status, rows, out = self.judge(self.inventory(False, True))
        self.assertEqual(status, 0, out)

    def test_a_leftover_of_each_kind_is_found(self):
        after = self.inventory(True, True)
        status, rows, out = self.judge(after)
        self.assertEqual(rows["nothing-of-it-left"], "FAIL")
        for kind in ("stack", "repository", "database", "snapshot", "user-pool", "secret", "log-group", "volume",
                     "bucket", "oidc", "role", "dns"):
            self.assertIn(f"{kind} ", out)

    def test_a_neighbour_deleted_with_it_is_found(self):
        status, rows, out = self.judge(self.inventory(False, False))
        self.assertEqual(rows["others-untouched"], "FAIL")
        self.assertIn("colvenna-lake-uate2", out)


class CutRoleNamesTest(unittest.TestCase):
    """CloudFormation cuts a stack's name short in the IAM role names it generates
    (ColvennaLakePlatFoundatio-...): such roles are the environment's, and a neighbour whose stack
    names start the same is not taken for it."""

    def files(self, before_roles, after_roles, env):
        meta = {"env": env, "region": "us-west-2", "cluster": None, "issuer": None}
        before = {"account-regions.txt": "us-west-2\n", "meta.json": meta, "account-roles.json": before_roles}
        after = {"account-regions.txt": "us-west-2\n", "meta.json": meta, "account-roles.json": after_roles}
        return {**{f"a/{k}": v for k, v in before.items()}, **{f"b/{k}": v for k, v in after.items()}}

    def test_cut_role_names_go_with_their_environment(self):
        before = ["ColvennaLakePlatFoundatio-ClusterCreationRole-AAAA", "ColvennaLakePlatFoundation-Handler-BBBB",
                  "ColvennaLakeUatFoundation-ClusterCreationRole-CCCC"]
        with Dir(self.files(before, ["ColvennaLakeUatFoundation-ClusterCreationRole-CCCC"], "plat")) as d:
            status, rows, out = run("others", f"{d}/a", f"{d}/b", "plat")
        self.assertEqual(status, 0, out)

    def test_a_neighbour_whose_names_start_the_same_is_not_its_own(self):
        # uat's stack names are a prefix of nothing of uate2's, and uate2's cut names are not uat's.
        before = ["ColvennaLakeUate2Foundati-ClusterCreationRole-AAAA", "ColvennaLakeUatFoundation-Role-BBBB"]
        with Dir(self.files(before, ["ColvennaLakeUatFoundation-Role-BBBB"], "uat")) as d:
            status, rows, out = run("others", f"{d}/a", f"{d}/b", "uat")
        self.assertEqual(rows["others-untouched"], "FAIL")
        self.assertIn("ColvennaLakeUate2Foundati", out)
        with Dir(self.files(before, ["ColvennaLakeUatFoundation-Role-BBBB"], "uate2")) as d:
            status, rows, out = run("others", f"{d}/a", f"{d}/b", "uate2")
        self.assertEqual(status, 0, out)


class CutGeneratedNamesTest(unittest.TestCase):
    """RDS instances, their snapshots and log groups, and Lambda log groups take the stack's name cut
    short too (colvennalakeuate2foundatio-...): they are the environment's, and uat's are not uate2's."""

    def inventory(self, uate2):
        def pick(*items):
            return [i for i, keep in items if keep]
        meta = {"env": "uate2", "region": "us-east-2", "cluster": None, "issuer": None}
        return {"account-regions.txt": "us-east-2\n", "meta.json": meta,
                "account-databases-us-east-2.json": pick(
                    ("colvennalakeuate2foundatio-catalogdatabase1-aaaa", uate2),
                    ("colvennalakeuate2testpostgr-postgressource1-bbbb", uate2),
                    ("colvennalakeuatfoundation-catalogdatabase1-cccc", True)),
                "account-snapshots-us-east-2.json": pick(
                    (["final-1", "colvennalakeuate2foundatio-catalogdatabase1-aaaa"], uate2)),
                "account-loggroups-us-east-2.json": pick(
                    ("/aws/rds/instance/colvennalakeuate2foundatio-catalogdatabase1-aaaa/postgresql", uate2),
                    ("/aws/lambda/ColvennaLakeUate2Foundati-Handler-dddd", uate2),
                    ("/aws/rds/instance/colvennalakeuatfoundation-catalogdatabase1-cccc/postgresql", True))}

    def test_cut_generated_names_go_with_their_environment(self):
        files = {**{f"a/{k}": v for k, v in self.inventory(True).items()},
                 **{f"b/{k}": v for k, v in self.inventory(False).items()}}
        with Dir(files) as d:
            status, rows, out = run("others", f"{d}/a", f"{d}/b", "uate2")
        self.assertEqual(status, 0, out)

    def test_a_cut_generated_name_left_is_found(self):
        files = {**{f"a/{k}": v for k, v in self.inventory(True).items()},
                 **{f"b/{k}": v for k, v in self.inventory(True).items()}}
        with Dir(files) as d:
            status, rows, out = run("others", f"{d}/a", f"{d}/b", "uate2")
        self.assertEqual(rows["nothing-of-it-left"], "FAIL")
        self.assertIn("colvennalakeuate2foundatio-catalogdatabase1-aaaa", out)

    def test_a_cut_of_22_characters_is_not_taken_for_the_environment_s(self):
        # Lowercased, abcdef's ColvennaLakeAbcdefOpenLayer and abcdefop's ColvennaLakeAbcdefopEntry
        # share their first 22 characters, colvennalakeabcdefopen: the most two environments' can.
        meta = {"env": "abcdef", "region": "us-east-2", "cluster": None, "issuer": None}
        before = {"account-regions.txt": "us-east-2\n", "meta.json": meta,
                  "account-buckets.json": ["colvennalakeabcdefopenl-bucket-aaaa", "colvennalakeabcdefopen-bucket-bbbb"]}
        after = {**before, "account-buckets.json": ["colvennalakeabcdefopen-bucket-bbbb"]}
        files = {**{f"a/{k}": v for k, v in before.items()}, **{f"b/{k}": v for k, v in after.items()}}
        with Dir(files) as d:
            status, rows, out = run("others", f"{d}/a", f"{d}/b", "abcdef")
        self.assertEqual(status, 0, out)


class CustomEntryDomainTest(unittest.TestCase):
    """An environment's DNS records are those at or under its entry domain (meta.json's settings,
    COLVENNA_ENTRY_DOMAIN), whatever it is called; inventories made before it was recorded fall back to
    <app>.colvenna-<env>."""

    def files(self, uat_records):
        meta = {"env": "uat", "region": "us-west-2", "cluster": None, "issuer": None,
                "settings": {"COLVENNA_ENTRY_DOMAIN": "portal.example.com"}}
        before = {"account-regions.txt": "us-west-2\n", "meta.json": meta,
                  "account-dns.json": [{"name": n, "type": "A"} for n in
                                       ("console.portal.example.com.", "_acme.portal.example.com.",
                                        "console.colvenna-uat.example.com.")]}
        after = {"account-regions.txt": "us-west-2\n", "meta.json": meta,
                 "account-dns.json": [{"name": n, "type": "A"} for n in uat_records]}
        return {**{f"a/{k}": v for k, v in before.items()}, **{f"b/{k}": v for k, v in after.items()}}

    def test_records_under_the_entry_domain_are_its_own_and_one_named_colvenna_uat_is_not(self):
        with Dir(self.files(["console.colvenna-uat.example.com."])) as d:
            status, rows, out = run("others", f"{d}/a", f"{d}/b", "uat")
        self.assertEqual(status, 0, out)

    def test_a_record_left_under_a_custom_entry_domain_is_found(self):
        with Dir(self.files(["_acme.portal.example.com.", "console.colvenna-uat.example.com."])) as d:
            status, rows, out = run("others", f"{d}/a", f"{d}/b", "uat")
        self.assertEqual(rows["nothing-of-it-left"], "FAIL")
        self.assertIn("_acme.portal.example.com", out)


if __name__ == "__main__":
    unittest.main()

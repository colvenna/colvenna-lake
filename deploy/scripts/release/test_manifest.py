"""Offline tests of manifest.py: the release manifest's schema, its names, the third-party images'
list (deploy/images/third-party.tsv), the catalog release the tree pins
(deploy/images/colvenna-catalog.json), the required cases, how make-manifest.sh's gathered files
become a manifest, the containers that run from upstream, and the release notes' draft."""
import copy
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import manifest  # noqa: E402

SHA = "b" * 40
DIGEST = "sha256:" + "0" * 64
GENERATED = {"at": "2026-10-22T08:00:00Z", "environment": "uat", "region": "us-west-2"}
CHARTS = {name: {"path": f"deploy/helm/{name}", "version": "0.1.0", "appVersion": "0.1.0", "tree": "c" * 40}
          for name in manifest.CHARTS}
# The tree's third-party images: {name: (scope, upstream reference)}.
THIRD_PARTY = manifest.third_party_images()
# The catalog release the tree pins.
PIN = manifest.catalog_pin()
# A release promoted from a GO candidate (colvenna-catalog#65) and one of the catalog's candidates, as
# pins: whichever the tree pins, both kinds are checked.
RELEASE_PIN = {
    **{k: v for k, v in PIN.items() if k != "promotion"}, "candidate": "v1.0.0", "tag_commit": "1" * 40,
    "built_from": "2" * 40,
    "promotion": {"candidate": "v1.0.0-rc.4", "tag_commit": "3" * 40, "acceptance": "4" * 40,
                  "chart_digest": "sha256:" + "5" * 64},
    "chart": {**PIN["chart"], "version": "1.0.0", "digest": "sha256:" + "6" * 64}}
CANDIDATE_PIN = {**{k: v for k, v in RELEASE_PIN.items() if k != "promotion"}, "candidate": "v1.0.0-rc.4",
                 "tag_commit": "3" * 40, "chart": {**RELEASE_PIN["chart"], "version": "1.0.0-rc.4",
                                                   "digest": "sha256:" + "5" * 64}}
# What make-manifest.sh copied into the release registry: the product's images at DIGEST, each
# third-party one at the digest the list pins, the catalog's image and chart at the pin's.
DIGESTS = {**{key: DIGEST for key in {**manifest.PRODUCT_IMAGES, **manifest.FIXTURE_IMAGES}},
           **{name: manifest.third_party_digest(upstream) for name, (_, upstream) in THIRD_PARTY.items()},
           "catalog": PIN["image"]["digest"], "catalog-chart": PIN["chart"]["digest"]}


def inputs(**changes):
    """What assemble hands build, for candidate v1.0.0-rc.1."""
    value = {
        "candidate": "v1.0.0-rc.1",
        "built_from": SHA,
        "generated": GENERATED,
        "digests": DIGESTS,
        "cli": {"digest": DIGEST, "binaries": {name: "1" * 64 for name in manifest.CLI_BINARIES}},
        "charts": CHARTS,
        "catalog": PIN,
        "installer": {"install": {"path": "deploy/install.sh", "blob": "d" * 40},
                      "uninstall": {"path": "deploy/uninstall.sh", "blob": "e" * 40}},
        "uat_cases": {"path": "docs/uat/cases.md", "commit": "f" * 40, "blob": "a" * 40},
        "required_cases": ["UAT-CAT-01", "UAT-DEP-01"],
        "regions": ["us-west-2", "us-east-2"],
    }
    value.update(changes)
    return value


class Build(unittest.TestCase):
    def test_a_built_manifest_is_valid_and_names_every_image_by_its_key(self):
        m = manifest.build(inputs())
        self.assertEqual(manifest.problems(m), [])
        self.assertEqual(m["schema"], 3)
        self.assertEqual(m["images"]["jobs-kafka"],
                         {"repository": "colvenna-lake-release/colvenna-jobs", "tag": "v1.0.0-rc.1-kafka", "digest": DIGEST})
        self.assertEqual(m["cli"]["repository"], "colvenna-lake-release/colvenna-cli")
        self.assertEqual(m["images"]["operator"]["tag"], "v1.0.0-rc.1")
        self.assertNotIn("catalog", m["images"])
        self.assertEqual(m["catalog"]["image"], {"repository": "colvenna-lake-release/colvenna-catalog",
                                                 "tag": "v1.0.0-rc.1", "digest": PIN["image"]["digest"],
                                                 "platform": "linux/amd64"})
        self.assertEqual(m["catalog"]["chart"], {"repository": "colvenna-lake-release/charts/colvenna-catalog",
                                                 "tag": "v1.0.0-rc.1", "name": "colvenna-catalog",
                                                 "version": PIN["chart"]["version"],
                                                 "digest": PIN["chart"]["digest"]})
        self.assertEqual({k: m["catalog"][k] for k in ("repository", "candidate", "tag_commit", "built_from",
                                                       "schema_version")},
                         {k: PIN[k] for k in ("repository", "candidate", "tag_commit", "built_from",
                                              "schema_version")})
        self.assertEqual(m["images"]["query-engine-fe"], {"repository": "colvenna-lake-release/query-engine",
                                                          "tag": "v1.0.0-rc.1-fe", "digest": DIGEST})
        self.assertEqual(m["fixtures"]["images"]["readers-spark"]["tag"], "v1.0.0-rc.1-spark")
        self.assertEqual(m["source"]["built_from"], SHA)
        self.assertEqual(m["configurations"], {"install": ["platform", "fixtures"],
                                               "regions": ["us-west-2", "us-east-2"]})
        self.assertEqual(m["performance"], manifest.RELEASE_PERFORMANCE)

    def test_the_third_party_images_are_installable_from_the_release_registry(self):
        m = manifest.build(inputs())
        upstream = THIRD_PARTY["debezium-server"][1]
        self.assertEqual(m["fixtures"]["third_party"]["debezium-server"], {
            "upstream": upstream, "repository": "colvenna-lake-release/third-party",
            "tag": "v1.0.0-rc.1-debezium-server", "digest": upstream.rsplit("@", 1)[1]})
        self.assertEqual(set(m["third_party"]), {n for n, (scope, _) in THIRD_PARTY.items() if scope == "platform"})
        self.assertEqual(set(m["fixtures"]["third_party"]),
                         {n for n, (scope, _) in THIRD_PARTY.items() if scope == "fixtures"})
        self.assertIn("marquez", m["third_party"])
        self.assertIn("strimzi-kafka", m["fixtures"]["third_party"])

    def test_no_registry_account_or_identity_in_the_manifest(self):
        # #248: from rc.2 on a manifest names repositories and digests; the installer's setting
        # COLVENNA_RELEASE_REGISTRY says where they are.
        text = json.dumps(manifest.build(inputs()))
        self.assertNotIn("dkr.ecr", text)
        self.assertNotIn("arn:aws", text)
        self.assertIsNone(re.search(r"(?<![0-9A-Za-z])[0-9]{12}(?![0-9A-Za-z])", text))
        self.assertEqual(set(manifest.build(inputs())["generated"]), {"at", "environment", "region"})

    def test_every_product_image_of_the_issue_is_pinned(self):
        # The catalog is a product of its own (#295): its release's image, not one of ours.
        self.assertEqual(set(manifest.PRODUCT_IMAGES), {
            "flink", "operator", "jobs-mysql", "jobs-postgres", "jobs-mongodb", "jobs-sql",
            "jobs-kafka", "console", "bi", "query-engine-fe", "query-engine-be"})

    def test_the_query_engine_is_named_as_its_own_not_doris(self):
        # A modified build based on Apache Doris 4.1.4, distributed under a name of its own
        # (release/trademarks.md): no key, repository or tag of the product's images names Doris.
        # (The third-party list's doris-operator is the upstream image unmodified, by its digest.)
        rows = manifest.names("v1.0.0-rc.2")
        self.assertIn(("query-engine-fe", "query-engine", "v1.0.0-rc.2-fe"), rows)
        self.assertIn(("query-engine-be", "query-engine", "v1.0.0-rc.2-be"), rows)
        self.assertEqual([row for row in rows if row[1] != manifest.THIRD_PARTY_REPOSITORY
                          and any("doris" in cell for cell in row)], [])
        self.assertNotIn("doris", json.dumps(manifest.build(inputs())["images"]))
        old = manifest.build(inputs())
        old["images"]["doris-fe"] = old["images"].pop("query-engine-fe")
        self.assertIn("unknown field images.doris-fe", manifest.problems(old))

    def test_every_chart_of_the_tree_is_pinned(self):
        helm = os.path.join(HERE, "..", "..", "helm")
        self.assertEqual(set(manifest.CHARTS), {d for d in os.listdir(helm) if os.path.isdir(os.path.join(helm, d))})

    def test_the_performance_tier_the_requester_fixed(self):
        p = manifest.RELEASE_PERFORMANCE
        self.assertEqual((p["write_nodes"], p["query_nodes"], p["cdc_changes_per_second"],
                          p["kafka_events_per_second"], p["dashboard_qps"], p["steady_hours"]),
                         (2, 2, 200, 2000, 10, 2))
        self.assertEqual(p["latency_percentiles"], ["p95", "p99"])
        for doc in (p["latency_targets"], p["method"]):
            self.assertTrue(os.path.exists(os.path.join(HERE, "..", "..", "..", doc.split("#")[0])), doc)

    def test_the_performance_run_defaults_to_the_tier(self):
        # tests/performance/run-release.sh (#207) runs the tier: its defaults must be the manifest's.
        runner = os.path.join(HERE, "..", "..", "..", "tests", "performance", "run-release.sh")
        if not os.path.exists(runner):
            self.skipTest("tests/performance/run-release.sh is not in this tree yet (#207)")
        text = manifest.read(runner)

        def default(name):
            found = re.search(r"\$\{" + name + r":-([0-9]+)\}", text)
            self.assertIsNotNone(found, f"{name}'s default in run-release.sh")
            return int(found[1])
        p = manifest.RELEASE_PERFORMANCE
        self.assertEqual(default("CDC_RATE"), p["cdc_changes_per_second"])
        self.assertEqual(default("KAFKA_RATE"), p["kafka_events_per_second"])
        self.assertEqual(default("MINUTES"), p["steady_hours"] * 60)

    def test_a_missing_digest_fails_the_build(self):
        digests = dict(DIGESTS)
        del digests["bi"]
        with self.assertRaisesRegex(ValueError, "images.bi"):
            manifest.build(inputs(digests=digests))
        del digests["airflow"]
        with self.assertRaisesRegex(ValueError, "fixtures.third_party.airflow: missing"):
            manifest.build(inputs(digests=digests))

    def test_a_third_party_copy_of_another_digest_fails_the_build(self):
        with self.assertRaisesRegex(ValueError, "third_party.postgres.digest"):
            manifest.build(inputs(digests={**DIGESTS, "postgres": DIGEST}))


class Problems(unittest.TestCase):
    def setUp(self):
        self.good = manifest.build(inputs())

    def changed(self, change):
        m = copy.deepcopy(self.good)
        change(m)
        return manifest.problems(m)

    def assertFound(self, change, text):
        found = self.changed(change)
        self.assertTrue(any(text in p for p in found), found)

    def test_an_incomplete_manifest(self):
        self.assertFound(lambda m: m["images"].pop("operator"), "images.operator")
        self.assertFound(lambda m: m["fixtures"]["images"].pop("readers-spark"), "fixtures.images.readers-spark")
        self.assertFound(lambda m: m.pop("required_cases"), "required_cases")
        self.assertFound(lambda m: m.pop("performance"), "performance")
        self.assertFound(lambda m: m["cli"]["binaries"].pop("colvenna-linux-amd64"), "cli.binaries")
        self.assertFound(lambda m: m.update(charts={}), "charts.colvenna-operator: missing")
        self.assertFound(lambda m: m.pop("catalog"), "catalog: missing")
        self.assertFound(lambda m: m["catalog"].pop("chart"), "catalog.chart: missing")
        self.assertFound(lambda m: m["charts"].pop("colvenna-monitoring"), "charts.colvenna-monitoring: missing")

    def test_a_digest_that_is_not_one(self):
        self.assertFound(lambda m: m["images"]["operator"].update(digest="sha256:abc"), "images.operator.digest")
        self.assertFound(lambda m: m["images"]["operator"].update(digest="latest"), "images.operator.digest")

    def test_an_image_under_another_name_tag_or_reference(self):
        self.assertFound(lambda m: m["images"]["operator"].update(repository="colvenna-lake-uat/colvenna-operator"),
                         "images.operator.repository")
        # The catalog's own image is no product image of this tree's any more.
        self.assertFound(lambda m: m["images"].update(catalog=m["images"]["bi"]), "unknown field images.catalog")
        self.assertFound(lambda m: m["images"]["jobs-sql"].update(tag="v1.0.0-rc.1"), "images.jobs-sql.tag")
        # A reference names a registry: no longer a field.
        self.assertFound(lambda m: m["images"]["bi"].update(reference="colvenna-lake-release/colvenna-bi@" + DIGEST),
                         "unknown field images.bi.reference")
        self.assertFound(lambda m: m["cli"].update(reference="elsewhere"), "unknown field cli.reference")

    def test_unknown_or_misspelled_fields(self):
        self.assertFound(lambda m: m.update(imagse={}), "unknown field imagse")
        self.assertFound(lambda m: m["images"].update(extra=m["images"]["bi"]), "images.extra")
        self.assertFound(lambda m: m["charts"].update(extra=m["charts"]["colvenna-bi"]), "unknown field charts.extra")

    def test_the_schema_is_the_number_three(self):
        # 3 since #295: the catalog's release, not an image of ours.
        self.assertFound(lambda m: m.update(schema=2), "schema")
        self.assertFound(lambda m: m.update(schema=True), "schema")
        self.assertFound(lambda m: m.update(schema="3"), "schema")

    def test_the_catalog_is_the_tree_s_pin(self):
        self.assertFound(lambda m: m["catalog"]["image"].update(digest=DIGEST), "catalog.image.digest")
        self.assertFound(lambda m: m["catalog"]["chart"].update(digest=DIGEST), "catalog.chart.digest")
        self.assertFound(lambda m: m["catalog"]["chart"].update(version="1.0.0-rc.9"), "catalog.chart.version")
        self.assertFound(lambda m: m["catalog"].update(built_from=SHA), "catalog.built_from")
        self.assertFound(lambda m: m["catalog"].update(candidate="v1.0.0-rc.9"), "catalog.candidate")
        self.assertFound(lambda m: m["catalog"].update(schema_version=3), "catalog.schema_version")
        self.assertFound(lambda m: m["catalog"].update(schema_version="2"), "catalog.schema_version")
        # A release's promotion, as the pin has it; a candidate's pin has none to record.
        if "promotion" in PIN:
            self.assertFound(lambda m: m["catalog"].pop("promotion"), "catalog.promotion: missing")
            self.assertFound(lambda m: m["catalog"]["promotion"].update(acceptance=SHA), "catalog.promotion.acceptance")
        else:
            self.assertFound(lambda m: m["catalog"].update(promotion={}), "unknown field catalog.promotion")
        # Its copies are the candidate's: in its own registry, under its tag.
        self.assertFound(lambda m: m["catalog"]["image"].update(repository=PIN["image"]["repository"]),
                         "catalog.image.repository")
        self.assertFound(lambda m: m["catalog"]["chart"].update(tag=PIN["chart"]["version"]), "catalog.chart.tag")
        self.assertFound(lambda m: m["catalog"]["image"].update(reference="x"), "unknown field catalog.image.reference")

    def test_an_rc1_manifest_of_schema_one_is_not_this_tree_s(self):
        # v1.0.0-rc.1's manifest (on its tag, with the registry and its account) installs from its
        # own tag's tooling only.
        rc1 = os.path.join(HERE, "..", "..", "..", "release", "v1.0.0-rc.1", "manifest.json")
        if not os.path.exists(rc1):
            self.skipTest("no v1.0.0-rc.1 manifest in this tree")
        found = manifest.problems(manifest.load(rc1))
        self.assertTrue(any("unknown field registry" in p for p in found), found)
        self.assertTrue(any("AWS account ids" in p for p in found), found)

    def test_the_source_is_the_public_repository(self):
        # #300: a candidate is made in colvenna/colvenna-lake, and its manifest names no other repository.
        self.assertEqual(manifest.build(inputs())["source"]["repository"], "https://github.com/colvenna/colvenna-lake")
        self.assertFound(lambda m: m["source"].update(repository="https://github.com/example/colvenna-lake"),
                         "source.repository")

    def test_names_and_ids(self):
        self.assertFound(lambda m: m.update(candidate="1.0.0"), "candidate")
        self.assertFound(lambda m: m.update(candidate="v1.0.0-beta"), "candidate")
        self.assertFound(lambda m: m["source"].update(built_from="main"), "source.built_from")
        self.assertFound(lambda m: m["source"].update(built_from=SHA + "\n"), "source.built_from")
        self.assertFound(lambda m: m["images"]["bi"].update(digest=DIGEST + "\n"), "images.bi.digest")
        self.assertFound(lambda m: m["source"].update(tag="anything"), "source.tag")
        self.assertFound(lambda m: m.update(required_cases=["UAT-CAT-1"]), "required_cases")
        self.assertFound(lambda m: m.update(required_cases=["UAT-CAT-01", "UAT-CAT-01"]), "required_cases")

    def test_where_it_was_made_but_not_by_whom(self):
        self.assertFound(lambda m: m["generated"].update(at="yesterday"), "generated.at")
        self.assertFound(lambda m: m["generated"].update(environment="UAT-1"), "generated.environment")
        self.assertFound(lambda m: m["generated"].update(region="cn-north-1"), "generated.region")
        self.assertFound(lambda m: m["generated"].update(by="arn:aws:sts::123456789012:assumed-role/r/i-0"),
                         "unknown field generated.by")

    def test_no_registry_and_no_account_anywhere(self):
        self.assertFound(lambda m: m.update(registry="123456789012.dkr.ecr.us-west-2.amazonaws.com"),
                         "unknown field registry")
        # An account in any value, however it got there.
        self.assertFound(lambda m: m["generated"].update(environment="123456789012"), "1 AWS account ids")
        self.assertFound(lambda m: m["charts"]["colvenna-bi"].update(version="123456789012.dkr.ecr"), "AWS account ids")
        # Twelve digits inside a digest or a SHA are not an account.
        self.assertEqual(self.changed(lambda m: m["images"]["bi"].update(digest="sha256:" + "1" * 12 + "a" * 52)), [])

    def test_charts(self):
        self.assertFound(lambda m: m["charts"]["colvenna-bi"].update(appVersion=""), "charts.colvenna-bi.appVersion")
        self.assertFound(lambda m: m["charts"]["colvenna-bi"].update(version=1), "charts.colvenna-bi.version")
        self.assertFound(lambda m: m["charts"]["colvenna-bi"].update(tree="HEAD"), "charts.colvenna-bi.tree")
        self.assertFound(lambda m: m["charts"]["colvenna-bi"].update(path="colvenna-bi"), "charts.colvenna-bi.path")

    def test_configurations(self):
        self.assertFound(lambda m: m["configurations"].update(regions=[["us-west-2"], "us-east-2"]),
                         "configurations.regions")
        self.assertFound(lambda m: m["configurations"].update(regions="us-west-2,us-east-2"),
                         "configurations.regions")
        self.assertFound(lambda m: m["configurations"].update(install=[["platform"]]), "configurations.install")
        self.assertFound(lambda m: m["configurations"].update(regions=["us-west-2"]), "configurations.regions")
        self.assertFound(lambda m: m["configurations"].update(regions=["us-west-2", "us-west-2"]),
                         "configurations.regions")
        self.assertFound(lambda m: m["configurations"].update(regions=["us-west-2", "cn-north-1"]),
                         "configurations.regions")
        self.assertFound(lambda m: m["configurations"].update(install=["platform", "dev"]),
                         "configurations.install")

    def test_the_performance_tier(self):
        self.assertFound(lambda m: m["performance"].update(cdc_changes_per_second=300), "not the release's tier")
        self.assertFound(lambda m: m["performance"].update(method="docs/uat/report.md"), "not the release's tier")
        self.assertFound(lambda m: m["performance"].pop("steady_hours"), "performance.steady_hours: missing")
        self.assertFound(lambda m: m["performance"].update(dashboard_qps=True), "performance.dashboard_qps")
        self.assertFound(lambda m: m["performance"].update(write_nodes=0), "performance.write_nodes")
        self.assertFound(lambda m: m["performance"].update(cdc_changes_per_second="200"),
                         "performance.cdc_changes_per_second")
        self.assertFound(lambda m: m["performance"].update(latency_percentiles=["95"]),
                         "performance.latency_percentiles")
        self.assertFound(lambda m: m["performance"].update(method="the wiki"), "performance.method")
        self.assertFound(lambda m: m["performance"].update(tier=""), "performance.tier")

    def test_third_party_entries_are_the_list_s(self):
        self.assertFound(lambda m: m.update(third_party=[]), "third_party: not an object")
        self.assertFound(lambda m: m["third_party"].pop("marquez"), "third_party.marquez: missing")
        self.assertFound(lambda m: m["third_party"].update(redis=m["third_party"]["marquez"]),
                         "unknown field third_party.redis")
        # The fixtures' are under fixtures, not beside the platform's.
        self.assertFound(lambda m: m["third_party"].update(airflow=m["fixtures"]["third_party"]["airflow"]),
                         "unknown field third_party.airflow")
        self.assertFound(lambda m: m["fixtures"]["third_party"].pop("debezium-server"),
                         "fixtures.third_party.debezium-server: missing")
        self.assertFound(lambda m: m["fixtures"].pop("third_party"), "fixtures.third_party: missing")

    def test_a_third_party_entry_as_pinned(self):
        self.assertFound(lambda m: m["third_party"]["postgres"].update(digest=DIGEST),
                         "third_party.postgres.digest: not sha256:7218")
        self.assertFound(lambda m: m["third_party"]["postgres"].update(upstream="docker.io/library/postgres@" + DIGEST),
                         "third_party.postgres.upstream")
        self.assertFound(lambda m: m["third_party"]["postgres"].update(tag="v1.0.0-rc.1"), "third_party.postgres.tag")
        self.assertFound(lambda m: m["third_party"]["postgres"].update(repository="colvenna-lake-release/postgres"),
                         "third_party.postgres.repository")
        self.assertFound(lambda m: m["third_party"]["postgres"].pop("upstream"), "third_party.postgres.upstream: missing")
        self.assertFound(lambda m: m["fixtures"]["third_party"]["airflow"].update(digest=DIGEST),
                         "fixtures.third_party.airflow.digest")

    def test_a_candidate_other_than_the_one_asked_for(self):
        self.assertTrue(any("v1.0.0-rc.2" in p for p in manifest.problems(self.good, "v1.0.0-rc.2")))
        self.assertEqual(manifest.problems(self.good, "v1.0.0-rc.1"), [])

    def test_dev_candidates_for_trials(self):
        self.assertEqual(self.changed(lambda m: None), [])
        m = manifest.build(inputs(candidate="v0.0.0-dev.1"))
        self.assertEqual(manifest.problems(m), [])
        self.assertEqual(m["images"]["jobs-mysql"]["tag"], "v0.0.0-dev.1-mysql")
        self.assertEqual(m["catalog"]["chart"]["tag"], "v0.0.0-dev.1")


class CatalogPin(unittest.TestCase):
    """deploy/images/colvenna-catalog.json: the Colvenna Catalog release the tree runs (#295)."""

    def found(self, change, pin=PIN):
        pin = copy.deepcopy(pin)
        change(pin)
        return manifest.catalog_pin_problems(pin)

    def assertFound(self, change, text, pin=PIN):
        found = self.found(change, pin)
        self.assertTrue(any(text in p for p in found), found)

    def test_the_tree_s_pin(self):
        self.assertEqual(manifest.catalog_pin_problems(PIN), [])
        self.assertRegex(PIN["repository"], r"^https://github\.com/[^/]+/colvenna-catalog$")
        self.assertRegex(PIN["candidate"], r"^v[0-9]+\.[0-9]+\.[0-9]+(-rc\.[0-9]+)?$")
        self.assertEqual(PIN["chart"]["version"], PIN["candidate"][1:])
        # Published in GitHub Container Registry: ghcr.io/colvenna/colvenna-catalog and
        # ghcr.io/colvenna/charts/colvenna-catalog.
        self.assertEqual(PIN["registry"], "ghcr.io/colvenna")
        self.assertEqual(PIN["image"]["repository"], "colvenna-catalog")
        self.assertEqual(PIN["chart"]["repository"], "charts/colvenna-catalog")
        # The catalog's tag adds its release record to the commit the release was built from.
        self.assertNotEqual(PIN["tag_commit"], PIN["built_from"])

    def test_a_pin_of_a_release_or_of_a_candidate(self):
        self.assertEqual(manifest.catalog_pin_problems(RELEASE_PIN), [])
        self.assertEqual(manifest.catalog_pin_problems(CANDIDATE_PIN), [])
        # The tree's pin is one of the two kinds, its promotion as its name says.
        self.assertEqual("promotion" in PIN, "-rc." not in PIN["candidate"])

    def test_what_a_release_s_promotion_is_not(self):
        def assertFound(change, text, pin=RELEASE_PIN):
            self.assertFound(change, text, pin)
        # A release says where it was promoted from; a candidate was promoted from nothing.
        assertFound(lambda p: p.pop("promotion"), "promotion: missing")
        assertFound(lambda p: p.update(promotion=RELEASE_PIN["promotion"]), "'v1.0.0-rc.4' is a candidate",
                    CANDIDATE_PIN)
        self.assertEqual([p for p in self.found(lambda p: p.update(promotion={}), CANDIDATE_PIN) if "promotion" in p],
                         ["promotion: 'v1.0.0-rc.4' is a candidate; only a release promoted from one has a promotion"])
        # A name that is neither: the name is what is wrong, and the promotion is no field of a pin then.
        assertFound(lambda p: p.update(candidate="v1.0"), "candidate: 'v1.0' is not a catalog release")
        assertFound(lambda p: p.update(candidate="v1.0"), "unknown field promotion")
        assertFound(lambda p: p.update(promotion="v1.0.0-rc.4"), "promotion: not an object")
        for field in manifest.CATALOG_PROMOTION:
            assertFound(lambda p, field=field: p["promotion"].pop(field), f"promotion.{field}: missing")
        assertFound(lambda p: p["promotion"].update(manifest="x"), "unknown field promotion.manifest")
        # From one of its own candidates.
        for other in ("v1.0.1-rc.1", "v1.0.0", "v1.0.0-rc.0", "v1.0.0-dev.1", "v1.0.0-rc.4x", 4):
            with self.subTest(candidate=other):
                assertFound(lambda p, other=other: p["promotion"].update(candidate=other), "promotion.candidate")
        # The candidate's tag commit: a commit of its own, below the release's tag.
        assertFound(lambda p: p["promotion"].update(tag_commit="v1.0.0-rc.4"), "promotion.tag_commit: not a full")
        assertFound(lambda p: p["promotion"].update(tag_commit=p["tag_commit"]), "promotion.tag_commit: the release")
        assertFound(lambda p: p["promotion"].update(tag_commit=p["built_from"]), "promotion.tag_commit: the release")
        assertFound(lambda p: p["promotion"].update(acceptance="main"), "promotion.acceptance: not a full")
        # The candidate's chart, repackaged under the release's version: never the release's digest.
        assertFound(lambda p: p["promotion"].update(chart_digest="1.0.0-rc.4"), "promotion.chart_digest: not sha256")
        assertFound(lambda p: p["promotion"].update(chart_digest=p["chart"]["digest"]),
                    "promotion.chart_digest: the release's own")
        # Packaged as the release's version, not the candidate's.
        assertFound(lambda p: p["chart"].update(version="1.0.0-rc.4"), "chart.version")
        # No account id in it either, a release's or one a candidate should not have.
        assertFound(lambda p: p["promotion"].update(candidate="123456789012"), "AWS account id")
        assertFound(lambda p: p.update(promotion={"candidate": "123456789012"}), "AWS account id", CANDIDATE_PIN)

    def test_a_manifest_records_the_release_s_promotion(self):
        # A candidate's manifest keeps the pin's promotion under catalog, as it keeps the rest of it.
        entry = manifest.catalog_entry("v1.0.0-rc.6", RELEASE_PIN)
        self.assertEqual(entry["promotion"], RELEASE_PIN["promotion"])
        self.assertEqual(entry["candidate"], "v1.0.0")
        self.assertEqual(entry["chart"]["version"], "1.0.0")
        self.assertNotIn("promotion", manifest.catalog_entry("v1.0.0-rc.6", CANDIDATE_PIN))
        self.assertEqual(manifest.catalog_entry("v1.0.0-rc.6", PIN).get("promotion"), PIN.get("promotion"))

    def test_what_a_pin_is_not(self):
        self.assertFound(lambda p: p.pop("built_from"), "built_from: missing")
        self.assertFound(lambda p: p.update(extra=1), "unknown field extra")
        self.assertFound(lambda p: p.update(repository="https://github.com/x/colvenna-lake"), "repository")
        self.assertFound(lambda p: p.update(candidate="v1.0.0-dev.1"), "candidate")
        self.assertFound(lambda p: p.update(candidate="latest"), "candidate")
        self.assertFound(lambda p: p.update(tag_commit="v1.0.0-rc.1"), "tag_commit: not a full commit SHA")
        self.assertFound(lambda p: p.update(tag_commit=p["built_from"]), "tag_commit: the same as built_from")
        self.assertFound(lambda p: p["image"].update(digest="latest"), "image.digest")
        self.assertFound(lambda p: p["image"].update(platform="linux/arm64"), "image.platform")
        self.assertFound(lambda p: p["image"].update(repository="colvenna-lake-release/colvenna-catalog"),
                         "image.repository")
        self.assertFound(lambda p: p["image"].update(repository="ghcr.io/colvenna/colvenna-catalog"),
                         "image.repository")
        self.assertFound(lambda p: p.pop("registry"), "registry: missing")
        self.assertFound(lambda p: p.update(registry="ghcr.io/someone"), "registry: 'ghcr.io/someone', not")
        # Never ECR Public: the catalog publishes in GitHub Container Registry only.
        self.assertFound(lambda p: p.update(registry="public.ecr.aws/colvenna"), "registry")
        self.assertFound(lambda p: p["chart"].update(version="0.1.0"), "chart.version")
        self.assertFound(lambda p: p["chart"].update(name="catalog"), "chart.name")
        self.assertFound(lambda p: p["chart"].update(digest=SHA), "chart.digest")
        self.assertFound(lambda p: p["chart"].pop("repository"), "chart.repository: missing")
        self.assertFound(lambda p: p.update(schema_version=0), "schema_version")
        self.assertFound(lambda p: p.update(schema_version=True), "schema_version")
        # Repositories and digests only: never the registry that holds them, nor its account.
        self.assertFound(lambda p: p["image"].update(repository="123456789012.dkr.ecr.us-west-2.amazonaws.com/x"),
                         "AWS account id")
        self.assertFound(lambda p: p.update(registry="123456789012.dkr.ecr.us-west-2.amazonaws.com/x"),
                         "AWS account id")
        self.assertEqual(manifest.catalog_pin_problems([]), ["not an object"])

    def test_where_a_source_install_takes_it_from(self):
        # By default the pin's registry, read anonymously.
        self.assertEqual(manifest.catalog_source(None, PIN), ("ghcr.io/colvenna", None, None))
        self.assertEqual(manifest.catalog_source("", PIN), ("ghcr.io/colvenna", None, None))
        # An ECR registry, with or without a prefix, read with the AWS credentials of its account's region.
        ecr = "210987654321.dkr.ecr.us-east-2.amazonaws.com"
        self.assertEqual(manifest.catalog_source(f"{ecr}/colvenna-catalog-release", PIN),
                         (f"{ecr}/colvenna-catalog-release", "210987654321", "us-east-2"))
        self.assertEqual(manifest.catalog_source(ecr, PIN), (ecr, "210987654321", "us-east-2"))
        # Any other, anonymously: a mirror, or a registry on this host.
        for other in ("registry.example.com/mirror/colvenna", "127.0.0.1:5000/colvenna", "ghcr.io/colvenna"):
            self.assertEqual(manifest.catalog_source(other, PIN), (other, None, None))
        for bad in ("https://ghcr.io/colvenna", "ghcr.io/colvenna/", "ghcr.io//colvenna", "Ghcr.io/colvenna",
                    "ghcr.io/colvenna:v1", "ghcr.io/colvenna@" + PIN["image"]["digest"], " ghcr.io/colvenna",
                    "123456789012.dkr.ecr.cn-north-1.amazonaws.com.cn/x", "12345.dkr.ecr.us-west-2.amazonaws.com/x",
                    "123456789012.dkr.ecr.us-gov-west-1.amazonaws.com/x"):
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, "COLVENNA_CATALOG_REGISTRY="):
                manifest.catalog_source(bad, PIN)

    def test_an_image_or_a_chart_of_the_release(self):
        image = {"os": "linux", "architecture": "amd64",
                 "config": {"Labels": {"org.opencontainers.image.revision": PIN["built_from"]}}}
        chart = {"name": "colvenna-catalog", "version": PIN["chart"]["version"],
                 "annotations": {"colvenna-catalog/source-commit": PIN["built_from"]}}
        self.assertEqual(manifest.catalog_check("image", image, PIN), [])
        self.assertEqual(manifest.catalog_check("chart", chart, PIN), [])

        def problems(kind, value, change):
            value = copy.deepcopy(value)
            change(value)
            return " ".join(manifest.catalog_check(kind, value, PIN))
        self.assertIn("org.opencontainers.image.revision",
                      problems("image", image, lambda v: v["config"]["Labels"].update(
                          {"org.opencontainers.image.revision": SHA})))
        self.assertIn("org.opencontainers.image.revision", problems("image", image, lambda v: v.pop("config")))
        self.assertIn("linux/arm64", problems("image", image, lambda v: v.update(architecture="arm64")))
        self.assertIn("colvenna-catalog/source-commit", problems("chart", chart, lambda v: v.pop("annotations")))
        self.assertIn("version", problems("chart", chart, lambda v: v.update(version="0.1.0")))
        self.assertIn("name", problems("chart", chart, lambda v: v.update(name="other")))
        with self.assertRaises(ValueError):
            manifest.catalog_check("cli", {}, PIN)

    def test_documents_link_the_pinned_release(self):
        # A link into Colvenna Catalog's repository names the release the tree runs (its tag, or one of
        # its two commits), so that a new pin moves every one of them. The development history and the
        # records of earlier candidates keep the links of their time.
        root = os.path.join(HERE, "..", "..", "..")
        tracked = subprocess.run(["git", "-C", root, "ls-files", "--", "*.md"], check=True, capture_output=True,
                                 text=True).stdout.split()
        kept = ("docs/history/", "docs/uat/reports/", "docs/performance/", "docs/research/", "release/v")
        link = re.compile(r"github\.com/[^/\s)]+/colvenna-catalog/(?:blob|tree)/([^/\s)#]+)")
        allowed = {PIN["candidate"], PIN["tag_commit"], PIN["built_from"]}
        found, other = 0, []
        for path in tracked:
            if path.startswith(kept):
                continue
            for number, line in enumerate(manifest.read(os.path.join(root, path)).splitlines(), 1):
                for ref in link.findall(line):
                    found += 1
                    if ref not in allowed:
                        other.append(f"{path}:{number}: {ref}")
        self.assertEqual(other, [])
        self.assertGreater(found, 0)

    def test_the_commands(self):
        def run(*args):
            return subprocess.run([sys.executable, os.path.join(HERE, "manifest.py"), *args],
                                  capture_output=True, text=True)
        self.assertEqual(json.loads(run("catalog").stdout), PIN)
        self.assertEqual(run("catalog", "chart.digest").stdout.strip(), PIN["chart"]["digest"])
        self.assertEqual(run("catalog", "schema_version").stdout.strip(), str(PIN["schema_version"]))
        self.assertEqual(run("catalog", "chart.nothing").returncode, 1)
        with tempfile.TemporaryDirectory() as d:
            config = os.path.join(d, "config.json")
            write(config, json.dumps({"name": "colvenna-catalog", "version": PIN["chart"]["version"],
                                      "annotations": {"colvenna-catalog/source-commit": PIN["built_from"]}}))
            self.assertEqual(run("catalog-check", "chart", config).returncode, 0)
            wrong = run("catalog-check", "image", config)
            self.assertEqual(wrong.returncode, 1)
            self.assertIn("not the pinned catalog release", wrong.stderr)


# An image reference pinned by digest, however short its name: postgres:16-alpine@sha256:... as much
# as quay.io/debezium/server:3.6.3.Final@sha256:...; a bare digest (DORIS_FE_DIGEST=sha256:...) is not.
PINNED = re.compile(r"(?<![\w.:/@-])[a-z0-9][a-z0-9._-]*(:[0-9]+)?(/[a-z0-9._-]+)*(:[A-Za-z0-9._-]+)?"
                    r"@sha256:[0-9a-f]{64}(?![0-9a-f])")


class ThirdPartyList(unittest.TestCase):
    """deploy/images/third-party.tsv: the one list of the third-party images an environment runs."""

    ROOT = os.path.join(HERE, "..", "..", "..")

    def parse(self, *rows):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "third-party.tsv")
            write(path, "# a comment\n\n" + "".join(row + "\n" for row in rows))
            return manifest.third_party_images(path)

    def test_the_tree_s_list(self):
        self.assertIn(("fixtures", "quay.io/debezium/server:3.6.3.Final@" + DIGESTS["debezium-server"]),
                      THIRD_PARTY.values())
        for name in ("flink-kubernetes-operator", "doris-operator", "aws-load-balancer-controller", "prometheus",
                     "grafana", "marquez", "marquez-web", "polaris", "polaris-admin-tool", "postgres", "mysql", "curl",
                     # What the cases that apply to a platform-only install run (test_third_party.py).
                     "aws-cli", "mongo"):
            self.assertEqual(THIRD_PARTY[name][0], "platform", name)
        for name in ("strimzi-operator", "strimzi-kafka", "airflow", "debezium-server", "trino", "busybox"):
            self.assertEqual(THIRD_PARTY[name][0], "fixtures", name)

    def test_a_row_that_is_not_one(self):
        good = "marquez\tplatform\tdocker.io/marquezproject/marquez:0.51.1@" + DIGEST + "\tMarquez"
        self.assertEqual(self.parse(good), {"marquez": ("platform", "docker.io/marquezproject/marquez:0.51.1@" + DIGEST)})
        for bad, says in ((good.replace("\tMarquez", ""), "tab-separated"),
                          (good.replace("platform", "tests"), "scope"),
                          (good.replace("marquez\t", "Marquez\t", 1), "lowercase"),
                          (good.replace("@" + DIGEST, ":0.51.1"), "@sha256"),
                          (good.replace("docker.io/", ""), "@sha256"),
                          (good.replace("\tMarquez", "\t Marquez"), "tab-separated"),
                          (good.replace("marquez\t", "bi\t", 1), "product image")):
            with self.assertRaisesRegex(ValueError, says, msg=bad):
                self.parse(bad)
        with self.assertRaisesRegex(ValueError, "twice"):
            self.parse(good, good)
        with self.assertRaisesRegex(ValueError, "no third-party image"):
            self.parse()

    def test_the_release_s_inventory_lists_every_one(self):
        readme = manifest.read(os.path.join(self.ROOT, "release", "README.md"))
        for name, (scope, upstream) in THIRD_PARTY.items():
            self.assertTrue(re.search(rf"\| `{re.escape(name)}` \| {scope} \|", readme), f"{name}, {scope}")

    def test_the_third_party_notices_list_every_one(self):
        # The licence record of the public repository names each image the release copies, its
        # upstream (repository and tag, without the digest) and its scope.
        notices = manifest.read(os.path.join(self.ROOT, "THIRD_PARTY_NOTICES.md"))
        for name, (scope, upstream) in THIRD_PARTY.items():
            reference = upstream.split("@", 1)[0]
            row = rf"(?m)^\| `{re.escape(name)}` \| `{re.escape(reference)}`[^|]* \| [^|]+ \| {scope} \|"
            self.assertTrue(re.search(row, notices), f"{name}, {reference}, {scope}")

    def test_the_pin_guard_sees_every_way_an_image_is_written(self):
        hex64 = "a" * 64
        for ref in (f"postgres:16-alpine@sha256:{hex64}", f"mysql:8.4@sha256:{hex64}", f"busybox@sha256:{hex64}",
                    f"docker.io/library/mongo@sha256:{hex64}", f"quay.io/debezium/server:3.6.3.Final@sha256:{hex64}",
                    f"public.ecr.aws/eks/aws-load-balancer-controller:v3.6.0@sha256:{hex64}",
                    f"localhost:5000/x@sha256:{hex64}"):
            for line in (ref, f"IMAGE={ref}", f"      image: {ref}", f"  - '{ref}'", f'x="{ref}"'):
                self.assertRegex(line, PINNED, line)
        for line in (f"DORIS_FE_DIGEST=sha256:{hex64}", '"$DORIS_REPOSITORY@$DORIS_FE_DIGEST"', "postgres:16-alpine",
                     f"image@sha256:{'a' * 63}", '"${image#*@}"'):
            self.assertNotRegex(line, PINNED, line)

    def test_no_image_is_pinned_by_digest_outside_the_list(self):
        # What the platform and the fixtures run is pinned in the list alone, and run from the
        # environment's copy; a Dockerfile's FROM is a base, built into an image of ours.
        tracked = subprocess.run(["git", "-C", self.ROOT, "ls-files", "--", "deploy", "tests"], check=True,
                                 capture_output=True, text=True).stdout.split()
        pinned = PINNED
        found = []
        for path in tracked:
            if path == "deploy/images/third-party.tsv" or os.path.basename(path).startswith("test_"):
                continue
            full = os.path.join(self.ROOT, path)
            if not os.path.isfile(full):
                continue
            with open(full, errors="replace") as f:
                for number, line in enumerate(f, 1):
                    if pinned.search(line) and not (path.endswith("Dockerfile") and line.startswith("FROM ")):
                        found.append(f"{path}:{number}: {line.strip()}")
        self.assertEqual(found, [])


CASES = """# UAT

## 7. 必需用例集合

范围外的用例见下文。

<!-- required-cases:start -->

| 用例 | 阶段 |
| --- | --- |
| UAT-CAT-01 | catalog |
| `UAT-CAT-02` | catalog |
| [UAT-DEP-01](#uat-dep-01-从零安装) | deploy |

<!-- required-cases:end -->

| 范围外 | 原因 |
| --- | --- |
| UAT-XYZ-01 | not required |
"""


class RequiredCases(unittest.TestCase):
    def test_the_ids_between_the_markers_in_order(self):
        self.assertEqual(manifest.required_cases(CASES), ["UAT-CAT-01", "UAT-CAT-02", "UAT-DEP-01"])

    def test_no_markers(self):
        with self.assertRaisesRegex(ValueError, "required-cases:start"):
            manifest.required_cases("# UAT\n| UAT-CAT-01 | x |\n")

    def test_markers_in_the_wrong_order_or_twice(self):
        with self.assertRaisesRegex(ValueError, "once each"):
            manifest.required_cases(CASES + "\n<!-- required-cases:start -->\n")
        with self.assertRaisesRegex(ValueError, "start"):
            manifest.required_cases("<!-- required-cases:end -->\n| UAT-CAT-01 |\n<!-- required-cases:start -->\n")

    def test_no_case_between_them(self):
        with self.assertRaisesRegex(ValueError, "no case"):
            manifest.required_cases("<!-- required-cases:start -->\n| 用例 |\n| --- |\n<!-- required-cases:end -->\n")

    def test_a_case_listed_twice(self):
        with self.assertRaisesRegex(ValueError, "twice"):
            manifest.required_cases(CASES.replace("`UAT-CAT-02`", "UAT-CAT-01"))

    def test_a_row_that_names_no_case_is_not_dropped(self):
        for row in ("| UAT-CAT-1 | catalog |", "| 全部 catalog 用例 | catalog |", "| UAT-CAT-03、UAT-CAT-04 | x |",
                    "| | catalog |"):
            with self.assertRaisesRegex(ValueError, "names no case"):
                manifest.required_cases(CASES.replace("| `UAT-CAT-02` | catalog |", row))

    def test_aligned_rules_and_a_second_table(self):
        text = CASES.replace("| --- | --- |", "| :--- | ---: |", 1).replace(
            "<!-- required-cases:end -->", "| 用例 | 说明 |\n| --- | --- |\n| UAT-NODE-01 | x |\n\n<!-- required-cases:end -->")
        self.assertEqual(manifest.required_cases(text), ["UAT-CAT-01", "UAT-CAT-02", "UAT-DEP-01", "UAT-NODE-01"])


def write(path, text):
    with open(path, "w") as f:
        f.write(text)


def gathered(work):
    """A work directory as make-manifest.sh leaves it before manifest.py assemble."""
    os.makedirs(os.path.join(work, "charts"))
    os.makedirs(os.path.join(work, "cli"))
    write(os.path.join(work, "params.json"), json.dumps({
        "candidate": "v1.0.0-rc.1", "built_from": SHA, "generated": GENERATED,
        "installer": inputs()["installer"], "uat_cases": inputs()["uat_cases"],
        "regions": ["us-west-2", "us-east-2"], "cli_digest": DIGEST}))
    with open(os.path.join(work, "digests"), "w") as f:
        f.writelines(f"{key}={digest}\n" for key, digest in DIGESTS.items())
    with open(os.path.join(work, "trees"), "w") as f:
        f.writelines(f"{name}={'c' * 40}\n" for name in manifest.CHARTS)
    write(os.path.join(work, "catalog.json"), json.dumps(PIN))
    for name in manifest.CHARTS:
        with open(os.path.join(work, "charts", name), "w") as f:
            f.write(f'apiVersion: v2\nname: {name}\ntype: application\nversion: 0.1.0\nappVersion: "6.1.0"\n')
    with open(os.path.join(work, "cases.md"), "w") as f:
        f.write(CASES)
    for binary in manifest.CLI_BINARIES:
        with open(os.path.join(work, "cli", binary), "wb") as f:
            f.write(binary.encode())


class Assemble(unittest.TestCase):
    def test_what_make_manifest_gathered_becomes_a_valid_manifest(self):
        with tempfile.TemporaryDirectory() as work:
            gathered(work)
            m = manifest.assemble(work)
        self.assertEqual(manifest.problems(m), [])
        self.assertEqual(m["charts"]["colvenna-bi"], {"path": "deploy/helm/colvenna-bi", "version": "0.1.0",
                                                  "appVersion": "6.1.0", "tree": "c" * 40})
        self.assertEqual(m["cli"]["binaries"]["colvenna-linux-arm64"], hashlib.sha256(b"colvenna-linux-arm64").hexdigest())
        self.assertEqual(m["required_cases"], ["UAT-CAT-01", "UAT-CAT-02", "UAT-DEP-01"])
        self.assertEqual(m["third_party"]["marquez"]["digest"], manifest.third_party_digest(THIRD_PARTY["marquez"][1]))

    def test_a_chart_without_its_versions_or_a_case_list_without_markers(self):
        with tempfile.TemporaryDirectory() as work:
            gathered(work)
            with open(os.path.join(work, "charts", "colvenna-bi"), "w") as f:
                f.write("apiVersion: v2\nname: colvenna-bi\n")
            with self.assertRaisesRegex(ValueError, "charts.colvenna-bi.version"):
                manifest.assemble(work)
        with tempfile.TemporaryDirectory() as work:
            gathered(work)
            with open(os.path.join(work, "cases.md"), "w") as f:
                f.write("# UAT\n")
            with self.assertRaisesRegex(ValueError, "required-cases:start"):
                manifest.assemble(work)


class Notes(unittest.TestCase):
    def test_the_identity_regions_and_performance_tier_come_from_the_manifest(self):
        m = manifest.build(inputs())
        out = manifest.notes(m, "# {{candidate}} of {{built_from_short}} ({{built_from}}), {{date}}\n"
                                "区域：{{regions}}\n{{performance}}\n")
        self.assertIn(f"# v1.0.0-rc.1 of {SHA[:8]} ({SHA}), 2026-10-22", out)
        self.assertIn("区域：`us-west-2`、`us-east-2`", out)
        self.assertIn("CDC 200 变更/秒、Kafka 2,000 条/秒、看板 10 QPS", out)
        self.assertIn("写入节点 2 台、查询节点 2 台", out)
        self.assertIn("稳态 2 小时", out)
        self.assertIn("p95/p99", out)
        self.assertNotIn("{{", out)

    def test_the_template_of_the_tree_has_only_known_placeholders(self):
        template = manifest.read(os.path.join(HERE, "..", "..", "..", "release", "notes-template.md"))
        out = manifest.notes(manifest.build(inputs()), template)
        self.assertNotIn("{{", out)
        self.assertIn("`us-west-2`、`us-east-2`", out)

    def test_the_notes_name_the_apache_projects_and_carry_the_attribution(self):
        # #258: full Apache names, the query engine as a build based on Apache Doris under its own
        # name, the ASF attribution, and the candidate's own release/trademarks.md.
        template = manifest.read(os.path.join(HERE, "..", "..", "..", "release", "notes-template.md"))
        out = manifest.notes(manifest.build(inputs()), template)
        for name, home in (("Paimon", "paimon"), ("Flink", "flink"), ("Doris", "doris")):
            self.assertIn(f"[Apache {name}](https://{home}.apache.org/)", out)
        self.assertIn("基于 Apache Doris 4.1.4 的修改构建", out)
        self.assertIn("`query-engine-fe`", out)
        self.assertIn("No endorsement by The Apache Software Foundation is implied by the use of these marks.", out)
        self.assertIn("https://github.com/colvenna/colvenna-lake/blob/v1.0.0-rc.1/release/trademarks.md", out)
        self.assertTrue(os.path.exists(os.path.join(HERE, "..", "..", "..", "release", "trademarks.md")))

    def test_an_unknown_placeholder(self):
        with self.assertRaises(KeyError):
            manifest.notes(manifest.build(inputs()), "{{nothing}}")


class Command(unittest.TestCase):
    """The command line install.sh, check-release.sh and make-manifest.sh call."""

    def run_cli(self, *args, stdin=None):
        return subprocess.run([sys.executable, os.path.join(HERE, "manifest.py"), *args], input=stdin,
                              capture_output=True, text=True)

    def test_assemble_validate_names_and_images(self):
        with tempfile.TemporaryDirectory() as d:
            work, path = os.path.join(d, "work"), os.path.join(d, "manifest.json")
            gathered(work)
            built = self.run_cli("assemble", work)
            self.assertEqual(built.returncode, 0, built.stderr)
            with open(path, "w") as f:
                f.write(built.stdout)
            self.assertEqual(self.run_cli("validate", path, "--name", "v1.0.0-rc.1").returncode, 0)
            wrong = self.run_cli("validate", path, "--name", "v1.0.0-rc.2")
            self.assertEqual(wrong.returncode, 1)
            self.assertIn("v1.0.0-rc.2", wrong.stderr)
            rows = self.run_cli("images", path, "--fixtures", "--cli").stdout.splitlines()
            self.assertIn(f"jobs-kafka\tcolvenna-jobs\tv1.0.0-rc.1-kafka\t{DIGEST}", rows)
            self.assertIn(f"readers-spark\ttest-readers\tv1.0.0-rc.1-spark\t{DIGEST}", rows)
            self.assertIn(f"cli\tcolvenna-cli\tv1.0.0-rc.1\t{DIGEST}", rows)
            self.assertIn(f"debezium-server\tthird-party\tv1.0.0-rc.1-debezium-server\t{DIGESTS['debezium-server']}", rows)
            platform = [n for n, (scope, _) in THIRD_PARTY.items() if scope == "platform"]
            self.assertEqual(len(rows), len(manifest.PRODUCT_IMAGES) + len(manifest.FIXTURE_IMAGES) + 1 + 2
                             + len(THIRD_PARTY))
            # Without --fixtures: the product's, the catalog release's and the platform's third-party images.
            plain = self.run_cli("images", path).stdout.splitlines()
            self.assertEqual(len(plain), len(manifest.PRODUCT_IMAGES) + 2 + len(platform))
            self.assertIn(f"catalog\tcolvenna-catalog\tv1.0.0-rc.1\t{PIN['image']['digest']}", plain)
            self.assertIn(f"catalog-chart\tcharts/colvenna-catalog\tv1.0.0-rc.1\t{PIN['chart']['digest']}", plain)
            self.assertIn(f"postgres\tthird-party\tv1.0.0-rc.1-postgres\t{DIGESTS['postgres']}", plain)
            self.assertEqual(self.run_cli("get", path, "source.built_from").stdout.strip(), SHA)
            self.assertEqual(self.run_cli("get", path, "registry").returncode, 1)
            self.assertEqual(self.run_cli("get", path, "no.such").returncode, 1)
            notes = self.run_cli("notes", path, stdin="{{candidate}}: {{regions}}\n")
            self.assertEqual(notes.stdout, "v1.0.0-rc.1: `us-west-2`、`us-east-2`\n")
            self.assertEqual(self.run_cli("notes", path, stdin="{{nothing}}").returncode, 1)
            with open(path, "w") as f:
                f.write("{")
            broken = self.run_cli("validate", path)
            self.assertEqual(broken.returncode, 1)
            self.assertIn("not JSON", broken.stderr)
        names = self.run_cli("names", "v1.0.0-rc.1").stdout.splitlines()
        self.assertIn("query-engine-be\tquery-engine\tv1.0.0-rc.1-be", names)
        self.assertIn("cli\tcolvenna-cli\tv1.0.0-rc.1", names)
        self.assertIn("catalog\tcolvenna-catalog\tv1.0.0-rc.1", names)
        self.assertIn("catalog-chart\tcharts/colvenna-catalog\tv1.0.0-rc.1", names)
        self.assertEqual(self.run_cli("names", "latest").returncode, 1)
        self.assertEqual(self.run_cli("binaries").stdout.split(), list(manifest.CLI_BINARIES))
        self.assertEqual(self.run_cli("stack").stdout.strip(), "ColvennaLakeRelease")
        self.assertEqual(self.run_cli("regions", "us-west-2,us-east-2").stdout.split(), ["us-west-2", "us-east-2"])
        for bad in ("us-west-2", "us-west-2,us-west-2", "us-west-2,cn-north-1", "us-west-2,"):
            self.assertEqual(self.run_cli("regions", bad).returncode, 1, bad)
        # Every repository names gives, the third-party one too, is a release registry's (release-stack.ts).
        self.assertIn("trino\tthird-party\tv1.0.0-rc.1-trino", names)

    def test_the_release_registry_setting(self):
        self.assertEqual(self.run_cli("registry", "123456789012.dkr.ecr.eu-central-1.amazonaws.com").stdout,
                         "123456789012\teu-central-1\n")
        for bad in ("", "123.dkr.ecr.us-west-2.amazonaws.com", "123456789012.dkr.ecr.cn-north-1.amazonaws.com",
                    "123456789012.dkr.ecr.us-gov-west-1.amazonaws.com", "123456789012.dkr.ecr.us-west-2.amazonaws.com/x",
                    "public.ecr.aws"):
            out = self.run_cli("registry", bad)
            self.assertEqual(out.returncode, 1, bad)
            self.assertIn("COLVENNA_RELEASE_REGISTRY", out.stderr)

    def test_the_third_party_list_for_the_copies(self):
        rows = [row.split("\t") for row in self.run_cli("third-party").stdout.splitlines()]
        every = [row.split("\t") for row in self.run_cli("third-party", "--fixtures").stdout.splitlines()]
        self.assertEqual([r[0] for r in rows], [n for n, (scope, _) in THIRD_PARTY.items() if scope == "platform"])
        self.assertEqual([r[0] for r in every], list(THIRD_PARTY))
        name, upstream, digest, tag = every[[r[0] for r in every].index("debezium-server")]
        self.assertEqual((upstream, digest), (THIRD_PARTY[name][1], upstream.rsplit("@", 1)[1]))
        self.assertEqual(tag, "debezium-server-" + digest[7:19])
        self.assertEqual(self.run_cli("third-party", "--all").returncode, 1)

    def test_running_names_what_is_not_the_environment_s_own(self):
        own = "111111111111.dkr.ecr.us-west-2.amazonaws.com"
        aws = "222222222222.dkr.ecr.us-west-2.amazonaws.com"  # an AWS-owned registry (EKS add-ons), synthetic

        def workload(ns, kind, name, *images, init=()):
            spec = {"containers": [{"name": f"c{i}", "image": image} for i, image in enumerate(images)],
                    "initContainers": [{"name": f"i{i}", "image": image} for i, image in enumerate(init)]}
            return {"kind": kind, "metadata": {"namespace": ns, "name": name},
                    "spec": spec if kind == "Pod" else {"template": {"spec": spec}}}
        items = [
            workload("colvenna-catalog", "Deployment", "colvenna-catalog", f"{own}/colvenna-lake-uat/colvenna-catalog@{DIGEST}"),
            workload("colvenna-lineage", "Deployment", "marquez", f"{own}/colvenna-lake-uat/third-party:marquez-0@{DIGEST}"),
            workload("kube-system", "DaemonSet", "aws-node", f"{aws}/amazon-k8s-cni:v1",
                     init=(f"{aws}/amazon-k8s-cni-init:v1",)),
            workload("amazon-guardduty", "DaemonSet", "aws-guardduty-agent", f"{aws}/aws-guardduty-agent:v1"),
            # Upstream: from a public registry; another account's ECR outside AWS's namespaces (a
            # release registry, say); the environment's own account in another region.
            workload("colvenna-lake-kafka", "Pod", "colvenna-dev-node-0", "quay.io/strimzi/kafka:1.2.0-kafka-4.3.1"),
            workload("colvenna-lineage", "Deployment", "web", f"{aws}/x:v1"),
            workload("kube-system", "Deployment", "aws-load-balancer-controller",
                     f"public.ecr.aws/eks/aws-load-balancer-controller:v3.6.0@{DIGEST}"),
            workload("colvenna-test", "Pod", "probe", f"111111111111.dkr.ecr.us-east-2.amazonaws.com/x@{DIGEST}"),
            # The environment's own registry, but not its repositories: the release registry's,
            # another environment's (whose name begins as this one's does).
            workload("colvenna-catalog", "Deployment", "from-release", f"{own}/colvenna-lake-release/colvenna-catalog@{DIGEST}"),
            workload("colvenna-catalog", "Deployment", "from-uat2", f"{own}/colvenna-lake-uat2/colvenna-catalog@{DIGEST}"),
            # Finished: not counted.
            {**workload("colvenna-test", "Pod", "done", f"docker.io/library/busybox@{DIGEST}"), "status": {"phase": "Succeeded"}},
        ]
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "workloads.json")
            write(path, json.dumps({"items": items}))
            rows = [row.split("\t") for row in self.run_cli("running", path, f"{own}/colvenna-lake-uat").stdout.splitlines()]
        self.assertEqual(sorted((r[0], r[1], r[3]) for r in rows), sorted([
            ("kube-system/DaemonSet/aws-node", "c0", "aws"), ("kube-system/DaemonSet/aws-node", "i0", "aws"),
            ("amazon-guardduty/DaemonSet/aws-guardduty-agent", "c0", "aws"),
            ("colvenna-lake-kafka/Pod/colvenna-dev-node-0", "c0", "upstream"), ("colvenna-lineage/Deployment/web", "c0", "upstream"),
            ("kube-system/Deployment/aws-load-balancer-controller", "c0", "upstream"),
            ("colvenna-test/Pod/probe", "c0", "upstream"), ("colvenna-catalog/Deployment/from-release", "c0", "upstream"),
            ("colvenna-catalog/Deployment/from-uat2", "c0", "upstream")]))

    def test_required_cases_of_a_file(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "cases.md")
            with open(path, "w") as f:
                f.write(CASES)
            self.assertEqual(self.run_cli("required-cases", path).stdout.split(),
                             ["UAT-CAT-01", "UAT-CAT-02", "UAT-DEP-01"])
            with open(path, "w") as f:
                f.write("# UAT\n")
            missing = self.run_cli("required-cases", path)
            self.assertEqual(missing.returncode, 1)
            self.assertIn("required-cases:start", missing.stderr)


class DorisSource(unittest.TestCase):
    """Where a source install copies the query engine's images from by default (doris/copy-images.sh):
    in the release registry (COLVENNA_RELEASE_REGISTRY), the repository of the newest manifest in
    release/ whose query-engine-fe and query-engine-be are the digests lib/common.sh pins."""

    FE, BE = "sha256:" + "f" * 64, "sha256:" + "e" * 64

    @staticmethod
    def put(release, candidate, m):
        os.makedirs(os.path.join(release, candidate))
        with open(os.path.join(release, candidate, "manifest.json"), "w") as f:
            json.dump(m, f)

    def write(self, release, candidate, at, fe=FE, be=BE):
        self.put(release, candidate, manifest.build(inputs(
            candidate=candidate, generated=dict(GENERATED, at=at),
            digests={**DIGESTS, "query-engine-fe": fe, "query-engine-be": be})))

    def test_the_newest_of_several_manifests_names_the_repository(self):
        with tempfile.TemporaryDirectory() as release:
            self.write(release, "v1.0.0-rc.2", "2026-10-25T00:00:00Z")
            self.write(release, "v1.0.0-rc.1", "2026-10-23T00:00:00Z", be=DIGEST)
            self.assertEqual(manifest.doris_source(release, self.FE, DIGEST), "colvenna-lake-release/query-engine")
            self.assertEqual(manifest.doris_source(release, self.FE, self.BE), "colvenna-lake-release/query-engine")

    def test_a_manifest_pinning_other_doris_digests_is_passed_over(self):
        with tempfile.TemporaryDirectory() as release:
            self.write(release, "v1.0.0-rc.1", "2026-10-23T00:00:00Z")
            self.write(release, "v1.0.0-rc.2", "2026-10-25T00:00:00Z", be=DIGEST)
            self.assertEqual(manifest.doris_source(release, self.FE, self.BE), "colvenna-lake-release/query-engine")

    def test_a_schema_1_manifest_is_passed_over(self):
        # As v1.0.0-rc.1's, on its tag: schema 1, the keys doris-fe and doris-be in a doris
        # repository. Even pinning these digests, and newer, it names no source; a schema-2 manifest
        # beside it does.
        with tempfile.TemporaryDirectory() as release:
            self.put(release, "v1.0.0-rc.1", {
                "schema": 1, "candidate": "v1.0.0-rc.1", "generated": {"at": "2026-10-30T00:00:00Z"},
                "images": {f"doris-{part}": {"repository": "colvenna-lake-release/doris", "tag": f"v1.0.0-rc.1-{part}",
                                             "digest": digest} for part, digest in (("fe", self.FE), ("be", self.BE))}})
            self.assertIsNone(manifest.doris_source(release, self.FE, self.BE))
            self.write(release, "v1.0.0-rc.2", "2026-10-25T00:00:00Z")
            self.assertEqual(manifest.doris_source(release, self.FE, self.BE), "colvenna-lake-release/query-engine")

    def test_the_tree_s_own_rc_1_manifest_is_not_taken_as_a_source(self):
        # The tagged v1.0.0-rc.1 manifest pins the very digests lib/common.sh pins, but in its old
        # repository and under its old keys; it is passed over (an install gives DORIS_IMAGE_SOURCE
        # until a schema-2 manifest pins them).
        rc1 = os.path.join(HERE, "..", "..", "..", "release", "v1.0.0-rc.1", "manifest.json")
        if not os.path.exists(rc1):
            self.skipTest("no v1.0.0-rc.1 manifest in this tree")
        m = manifest.load(rc1)
        with tempfile.TemporaryDirectory() as release:
            self.put(release, "v1.0.0-rc.1", m)
            self.assertIsNone(manifest.doris_source(release, m["images"]["doris-fe"]["digest"],
                                                    m["images"]["doris-be"]["digest"]))

    def test_the_rc_2_manifest_is_not_a_source(self):
        # v1.0.0-rc.2's manifest pins these very digests too, in the release registry of the former
        # names (#271): a source install would copy from a repository the new registry does not have.
        rc2 = os.path.join(HERE, "..", "..", "..", "release", "v1.0.0-rc.2", "manifest.json")
        if not os.path.exists(rc2):
            self.skipTest("no v1.0.0-rc.2 manifest in this tree")
        m = manifest.load(rc2)
        fe, be = (m["images"][key]["digest"] for key in manifest.QUERY_ENGINE)
        with tempfile.TemporaryDirectory() as release:
            self.put(release, "v1.0.0-rc.2", m)
            self.assertIsNone(manifest.doris_source(release, fe, be))
            # A manifest of the current names with the same digests is the source.
            self.write(release, "v1.0.0-rc.3", "2026-10-26T00:00:00Z", fe=fe, be=be)
            self.assertEqual(manifest.doris_source(release, fe, be), "colvenna-lake-release/query-engine")

    def test_none_without_a_matching_manifest(self):
        with tempfile.TemporaryDirectory() as release:
            self.assertIsNone(manifest.doris_source(release, self.FE, self.BE))
            self.assertIsNone(manifest.doris_source(os.path.join(release, "missing"), self.FE, self.BE))
            self.write(release, "v1.0.0-rc.1", "2026-10-23T00:00:00Z", fe=DIGEST)
            self.assertIsNone(manifest.doris_source(release, self.FE, self.BE))
            cli = subprocess.run([sys.executable, os.path.join(HERE, "manifest.py"), "doris-source", release,
                                  self.FE, self.BE], capture_output=True, text=True)
            self.assertEqual((cli.returncode, cli.stdout), (0, ""))


if __name__ == "__main__":
    unittest.main()

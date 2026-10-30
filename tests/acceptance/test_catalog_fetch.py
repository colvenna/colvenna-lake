"""Offline tests of deploy/scripts/catalog/fetch-catalog.sh (#295): the Colvenna Catalog release the tree
pins (deploy/images/colvenna-catalog.json), its image copied by digest into the environment's own
repository and its chart pulled by digest, each checked first against the commit the release was built
from. From the catalog's own release repositories for a source install, from a Colvenna Lake
candidate's copies with --release (install-images.sh <candidate> catalog). And make-manifest.sh, which
copies the release into a candidate's release registry, finds every repository it copies into there
before it checks anything. Stub aws, crane and helm (test_third_party.py's) stand for the registries."""
import json
import os
import shutil
import unittest

from test_third_party import OWN, RELEASE, REPO, InstallImages, Scratch, manifest

PIN = manifest.catalog_pin()
IMAGE, CHART = PIN["image"]["digest"], PIN["chart"]["digest"]
OWN_REPOSITORY = f"{OWN}/colvenna-lake-t234/colvenna-catalog"
SCRIPT = "deploy/scripts/catalog/fetch-catalog.sh"


def image_config(revision=PIN["built_from"], architecture="amd64"):
    return {"os": "linux", "architecture": architecture,
            "config": {"Labels": {"org.opencontainers.image.revision": revision}}}


def chart_config(commit=PIN["built_from"], version=PIN["chart"]["version"]):
    return {"name": "colvenna-catalog", "version": version,
            "annotations": {"colvenna-catalog/source-commit": commit}}


def package(commit=PIN["built_from"], version=PIN["chart"]["version"]):
    return (f"annotations:\n  colvenna-catalog/source-commit: {commit}\napiVersion: v2\nname: colvenna-catalog\n"
            f"version: {version}\n")


class FetchCatalogTest(unittest.TestCase):
    def scratch(self, image=None, chart=None, packaged=None, where="colvenna-catalog-release"):
        scratch = Scratch(self)
        scratch.state["outputs"]["CatalogRepositoryUri"] = OWN_REPOSITORY
        scratch.state["digests"] += [f"{RELEASE}/{where}/colvenna-catalog@{IMAGE}",
                                     f"{RELEASE}/{where}/charts/colvenna-catalog@{CHART}"]
        scratch.state["configs"] = {IMAGE: image or image_config(), CHART: chart or chart_config()}
        scratch.state["packages"] = {CHART: packaged or package()}
        return scratch

    def record(self, scratch):
        path = os.path.join(scratch.root, "deploy", ".state", "t234", "catalog-release.json")
        if not os.path.exists(path):
            return None
        with open(path) as f:
            return json.load(f)

    def test_the_release_is_copied_and_pulled_by_digest_and_recorded(self):
        scratch = self.scratch()
        out = scratch.run(SCRIPT, COLVENNA_RELEASE_REGISTRY=RELEASE)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.copies(), [(f"{RELEASE}/colvenna-catalog-release/colvenna-catalog@{IMAGE}",
                                             f"{OWN_REPOSITORY}:{PIN['candidate']}")])
        self.assertIn(f"helm pull oci://{RELEASE}/colvenna-catalog-release/charts/colvenna-catalog@{CHART}",
                      " ".join(scratch.calls()))
        record = self.record(scratch)
        self.assertEqual({k: record[k] for k in ("candidate", "built_from", "image", "chart_digest", "chart")},
                         {"candidate": PIN["candidate"], "built_from": PIN["built_from"],
                          "image": f"{OWN_REPOSITORY}@{IMAGE}", "chart_digest": CHART,
                          "chart": f"catalog/colvenna-catalog-{PIN['chart']['version']}.tgz"})
        kept = os.path.join(scratch.root, "deploy", ".state", "t234", record["chart"])
        with open(kept) as f:
            self.assertEqual(f.read(), package())
        # The registry's credentials stay in files of the run: no login outside them.
        self.assertIn("helm registry login", " ".join(scratch.calls()))

    def test_a_second_run_copies_nothing(self):
        scratch = self.scratch()
        self.assertEqual(scratch.run(SCRIPT, COLVENNA_RELEASE_REGISTRY=RELEASE).returncode, 0)
        out = scratch.run(SCRIPT, COLVENNA_RELEASE_REGISTRY=RELEASE)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(len(scratch.copies()), 1)

    def test_a_tag_holding_another_digest_is_never_moved(self):
        scratch = self.scratch()
        scratch.state["tags"][f"{OWN_REPOSITORY}:{PIN['candidate']}"] = "sha256:" + "9" * 64
        out = scratch.run(SCRIPT, COLVENNA_RELEASE_REGISTRY=RELEASE)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("is already sha256:9999", out.stderr)
        self.assertEqual(scratch.copies(), [])
        self.assertIsNone(self.record(scratch))

    def test_an_image_of_another_commit_or_platform_is_refused_before_any_copy(self):
        for config, said in ((image_config(revision="a" * 40), "org.opencontainers.image.revision"),
                             (image_config(architecture="arm64"), "linux/arm64")):
            with self.subTest(said=said):
                scratch = self.scratch(image=config)
                out = scratch.run(SCRIPT, COLVENNA_RELEASE_REGISTRY=RELEASE)
                self.assertNotEqual(out.returncode, 0)
                self.assertIn(said, out.stderr)
                self.assertEqual(scratch.copies(), [])
                self.assertIsNone(self.record(scratch))

    def test_a_chart_of_another_commit_or_version_is_refused(self):
        for kwargs, said in (({"chart": chart_config(commit="a" * 40)}, "colvenna-catalog/source-commit"),
                             ({"chart": chart_config(version="0.1.0")}, "version"),
                             ({"packaged": package(commit="a" * 40)}, "not annotated"),
                             ({"packaged": package(version="0.1.0")}, "not version")):
            with self.subTest(said=said):
                scratch = self.scratch(**kwargs)
                out = scratch.run(SCRIPT, COLVENNA_RELEASE_REGISTRY=RELEASE)
                self.assertNotEqual(out.returncode, 0)
                self.assertIn(said, out.stderr)
                self.assertIsNone(self.record(scratch))

    def test_without_the_release_registry_nothing_is_fetched(self):
        scratch = self.scratch()
        out = scratch.run(SCRIPT)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("COLVENNA_RELEASE_REGISTRY", out.stderr)
        # Only who the caller is: no registry read, no login.
        self.assertEqual([c for c in scratch.calls() if not c.startswith("aws sts get-caller-identity")], [])

    def test_a_registry_without_the_release_stops_it(self):
        scratch = self.scratch(where="elsewhere")
        out = scratch.run(SCRIPT, COLVENNA_RELEASE_REGISTRY=RELEASE)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("MANIFEST_UNKNOWN", out.stderr)
        self.assertEqual(scratch.copies(), [])


class InstallImagesCatalogTest(InstallImages):
    """A release install's catalog step: the candidate's copies of the pinned release."""

    def test_the_candidate_s_copies_are_fetched_and_checked_as_the_release(self):
        scratch = Scratch(self)
        self.release(scratch)
        scratch.state["outputs"]["CatalogRepositoryUri"] = OWN_REPOSITORY
        scratch.state["digests"] += [f"{RELEASE}/colvenna-lake-release/colvenna-catalog@{IMAGE}",
                                     f"{RELEASE}/colvenna-lake-release/charts/colvenna-catalog@{CHART}"]
        scratch.state["configs"] = {IMAGE: image_config(), CHART: chart_config()}
        scratch.state["packages"] = {CHART: package()}
        out = scratch.run(self.script, self.candidate, "catalog", COLVENNA_RELEASE_REGISTRY=RELEASE)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.copies(), [(f"{RELEASE}/colvenna-lake-release/colvenna-catalog@{IMAGE}",
                                             f"{OWN_REPOSITORY}:{PIN['candidate']}")])
        self.assertIn(f"Colvenna Lake's release {self.candidate}", out.stdout)
        # Never from the catalog's own release repositories: the candidate is installed from its own.
        self.assertNotIn("colvenna-catalog-release/", " ".join(scratch.calls()))


class MakeManifestRegistryTest(unittest.TestCase):
    """make-manifest.sh copies a candidate into the release registry's repositories, those
    deploy/aws/src/release-stack.ts makes: one the stack has added since the registry was deployed is not
    there until deploy-registry.sh deploys it again. charts/colvenna-catalog, the catalog release's chart's
    (#295), is not in a registry deployed before. So a run finds every one first, before it checks the
    environment, let alone copies."""

    candidate = "v0.0.0-dev.9"

    def run_it(self, missing=()):
        scratch = Scratch(self)
        # The required cases, read from the commit.
        os.makedirs(os.path.join(scratch.root, "docs", "uat"))
        shutil.copy(os.path.join(REPO, "docs", "uat", "cases.md"), os.path.join(scratch.root, "docs", "uat"))
        scratch.commit("the cases")
        scratch.state["stacks"] = [manifest.STACK]
        scratch.state["repositories"] = sorted({f"{manifest.PREFIX}/{repository}"
                                                for _, repository, _ in manifest.names(self.candidate)
                                                if repository not in missing} | {f"{manifest.PREFIX}/doris"})
        out = scratch.run("deploy/scripts/release/make-manifest.sh", self.candidate, COLVENNA_RELEASE_REGISTRY=RELEASE)
        # What the run asked of the environment's stacks, past the release registry's.
        stacks = [c for c in scratch.calls() if c.startswith("aws cloudformation describe-stacks")
                  and manifest.STACK not in c]
        return scratch, out, stacks

    def test_a_registry_deployed_before_the_catalog_s_chart_stops_it_before_anything(self):
        scratch, out, stacks = self.run_it(missing=("charts/colvenna-catalog",))
        self.assertEqual(out.returncode, 1)
        self.assertIn(f"has no {manifest.PREFIX}/charts/colvenna-catalog", out.stderr)
        self.assertIn("deploy/scripts/release/deploy-registry.sh", out.stderr)
        self.assertEqual(stacks, [])
        self.assertEqual([c for c in scratch.calls() if c.startswith(("crane", "helm"))], [])

    def test_every_one_missing_is_named(self):
        _, out, _ = self.run_it(missing=("charts/colvenna-catalog", "colvenna-cli"))
        self.assertEqual(out.returncode, 1)
        self.assertIn(f"has no {manifest.PREFIX}/charts/colvenna-catalog, {manifest.PREFIX}/colvenna-cli:",
                      out.stderr)

    def test_a_registry_with_every_one_goes_on_to_the_environment(self):
        _, out, stacks = self.run_it()
        self.assertNotIn("has no", out.stderr)
        self.assertIn("release registry: every repository", out.stdout)
        # On to the first check of the environment, its stacks' commit, which the stub cannot answer.
        self.assertGreater(len(stacks), 0)
        self.assertIn("is of nothing", out.stderr)


if __name__ == "__main__":
    unittest.main()

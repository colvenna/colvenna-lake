"""Offline tests of deploy/scripts/catalog/fetch-catalog.sh (#295): the Colvenna Catalog release the tree
pins (deploy/images/colvenna-catalog.json), its image copied by digest into the environment's own
repository and its chart pulled by digest, each checked first against the commit the release was built
from. For a source install, from where the catalog publishes it, GitHub Container Registry, read
anonymously; or from the registry COLVENNA_CATALOG_REGISTRY names instead, holding the same digests
(an ECR one, the maintainers' private release registry, read with the AWS credentials). From a Colvenna
Lake candidate's copies with --release (install-images.sh <candidate> catalog). And make-manifest.sh,
which copies the release into a candidate's release registry, finds every repository it copies into
there before it checks anything. Stub aws, crane and helm (test_third_party.py's) stand for the
registries. lib/preflight.sh's assert_catalog_source, which reads an anonymous source before an install
makes anything, reads a stub OCI registry served here over HTTP (the distribution API's manifest HEAD
and its Bearer token challenge)."""
import http.server
import json
import os
import shutil
import threading
import unittest

from test_third_party import OWN, RELEASE, REPO, InstallImages, Scratch, manifest

PIN = manifest.catalog_pin()
IMAGE, CHART = PIN["image"]["digest"], PIN["chart"]["digest"]
OWN_REPOSITORY = f"{OWN}/colvenna-lake-t234/colvenna-catalog"
SCRIPT = "deploy/scripts/catalog/fetch-catalog.sh"
# Where the catalog publishes its releases, and the maintainers' private release registry: an ECR
# registry of another account, in another region, with the catalog's release repositories under a prefix.
GHCR = PIN["registry"]
PRIVATE = f"{RELEASE}/colvenna-catalog-release"
OTHER = "sha256:" + "9" * 64


def image_config(revision=PIN["built_from"], architecture="amd64"):
    return {"os": "linux", "architecture": architecture,
            "config": {"Labels": {"org.opencontainers.image.revision": revision}}}


def chart_config(commit=PIN["built_from"], version=PIN["chart"]["version"]):
    return {"name": "colvenna-catalog", "version": version,
            "annotations": {"colvenna-catalog/source-commit": commit}}


def package(commit=PIN["built_from"], version=PIN["chart"]["version"]):
    return (f"annotations:\n  colvenna-catalog/source-commit: {commit}\napiVersion: v2\nname: colvenna-catalog\n"
            f"version: {version}\n")


def registries(scratch):
    """GitHub Container Registry holds only what it is given, and the ECR release registries read
    only to who logged in to them."""
    scratch.state["listed"] = [f"{GHCR}/"]
    scratch.state["private"] = [RELEASE]


class FetchCatalogTest(unittest.TestCase):
    def scratch(self, image=None, chart=None, packaged=None, where=GHCR, held=(IMAGE, CHART)):
        """The release's image and chart in the registry where, by the digests held (the pin's, unless
        the registry holds another release under the candidate's name)."""
        scratch = Scratch(self)
        registries(scratch)
        scratch.state["outputs"]["CatalogRepositoryUri"] = OWN_REPOSITORY
        image_held, chart_held = held
        scratch.state["digests"] += [f"{where}/colvenna-catalog@{image_held}",
                                     f"{where}/charts/colvenna-catalog@{chart_held}"]
        scratch.state["tags"].update({f"{where}/colvenna-catalog:{PIN['candidate']}": image_held,
                                      f"{where}/charts/colvenna-catalog:{PIN['chart']['version']}": chart_held})
        scratch.state["configs"] = {IMAGE: image or image_config(), CHART: chart or chart_config()}
        scratch.state["packages"] = {CHART: packaged or package()}
        return scratch

    def record(self, scratch):
        path = os.path.join(scratch.root, "deploy", ".state", "t234", "catalog-release.json")
        if not os.path.exists(path):
            return None
        with open(path) as f:
            return json.load(f)

    def logins(self, scratch):
        """The logins and the tokens they took, sorted: the two ends of a pipe log in either order."""
        return sorted(c for c in scratch.calls() if c.startswith(("crane auth login", "helm registry login",
                                                                   "aws ecr get-login-password")))

    def assert_nothing_fetched(self, scratch):
        self.assertEqual(scratch.copies(), [])
        self.assertIsNone(self.record(scratch))
        self.assertFalse(os.path.exists(os.path.join(scratch.root, "deploy", ".state", "t234", "catalog")))

    def test_by_default_the_release_is_pulled_anonymously_from_ghcr_and_copied_by_digest(self):
        scratch = self.scratch()
        # No release registry, no catalog registry: a source install needs neither now.
        out = scratch.run(SCRIPT)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.copies(), [(f"ghcr.io/colvenna/colvenna-catalog@{IMAGE}",
                                             f"{OWN_REPOSITORY}:{PIN['candidate']}")])
        calls = " ".join(scratch.calls())
        self.assertIn(f"crane config ghcr.io/colvenna/colvenna-catalog@{IMAGE}", calls)
        self.assertIn(f"helm pull oci://ghcr.io/colvenna/charts/colvenna-catalog@{CHART}", calls)
        record = self.record(scratch)
        self.assertEqual({k: record[k] for k in ("candidate", "built_from", "image", "chart_digest", "chart")},
                         {"candidate": PIN["candidate"], "built_from": PIN["built_from"],
                          "image": f"{OWN_REPOSITORY}@{IMAGE}", "chart_digest": CHART,
                          "chart": f"catalog/colvenna-catalog-{PIN['chart']['version']}.tgz"})
        self.assertIn("ghcr.io/colvenna", record["from"])
        kept = os.path.join(scratch.root, "deploy", ".state", "t234", record["chart"])
        with open(kept) as f:
            self.assertEqual(f.read(), package())
        # Anonymously: the one login is to the environment's own registry, with its own region's token.
        self.assertEqual(self.logins(scratch), ["aws ecr get-login-password",
                                                f"crane auth login {OWN} -u AWS --password-stdin"])

    def test_a_second_run_copies_nothing(self):
        scratch = self.scratch()
        self.assertEqual(scratch.run(SCRIPT).returncode, 0)
        out = scratch.run(SCRIPT)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(len(scratch.copies()), 1)

    def test_a_tag_holding_another_digest_is_never_moved(self):
        scratch = self.scratch()
        scratch.state["tags"][f"{OWN_REPOSITORY}:{PIN['candidate']}"] = OTHER
        out = scratch.run(SCRIPT)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("is already sha256:9999", out.stderr)
        self.assertEqual(scratch.copies(), [])
        self.assertIsNone(self.record(scratch))

    def test_a_copy_of_another_digest_stops_it(self):
        scratch = self.scratch()
        scratch.state["corrupt"][f"{OWN_REPOSITORY}:{PIN['candidate']}"] = OTHER
        out = scratch.run(SCRIPT)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn(f"is not {IMAGE} after the copy", out.stderr)
        self.assertIsNone(self.record(scratch))

    def test_a_registry_with_other_digests_under_the_release_s_names_gives_nothing(self):
        # The candidate's tags there hold another image or chart than the pin's digests: read by digest,
        # neither is taken, from the default or from the override.
        for where, env in ((GHCR, {}), (PRIVATE, {"COLVENNA_CATALOG_REGISTRY": PRIVATE})):
            for held, what in (((OTHER, CHART), "colvenna-catalog@"), ((IMAGE, OTHER), "charts/colvenna-catalog@")):
                with self.subTest(where=where, what=what):
                    scratch = self.scratch(where=where, held=held)
                    out = scratch.run(SCRIPT, **env)
                    self.assertNotEqual(out.returncode, 0)
                    self.assertIn("MANIFEST_UNKNOWN", out.stderr)
                    self.assertIn(f"Cannot read {where}/{what}", out.stderr)
                    self.assert_nothing_fetched(scratch)
                    # Until the release is public there, the default says what to give instead.
                    self.assertEqual("COLVENNA_CATALOG_REGISTRY=<registry>[/<prefix>]" in out.stderr, where == GHCR)

    def test_an_image_of_another_commit_or_platform_is_refused_before_any_copy(self):
        for config, said in ((image_config(revision="a" * 40), "org.opencontainers.image.revision"),
                             (image_config(architecture="arm64"), "linux/arm64")):
            with self.subTest(said=said):
                scratch = self.scratch(image=config)
                out = scratch.run(SCRIPT)
                self.assertNotEqual(out.returncode, 0)
                self.assertIn(said, out.stderr)
                self.assert_nothing_fetched(scratch)

    def test_a_chart_of_another_commit_or_version_is_refused_before_any_copy(self):
        for kwargs, said in (({"chart": chart_config(commit="a" * 40)}, "colvenna-catalog/source-commit"),
                             ({"chart": chart_config(version="0.1.0")}, "version"),
                             ({"packaged": package(commit="a" * 40)}, "not annotated"),
                             ({"packaged": package(version="0.1.0")}, "not version")):
            with self.subTest(said=said):
                scratch = self.scratch(**kwargs)
                out = scratch.run(SCRIPT)
                self.assertNotEqual(out.returncode, 0)
                self.assertIn(said, out.stderr)
                self.assert_nothing_fetched(scratch)

    def test_the_override_is_read_with_the_aws_credentials_and_the_same_digests(self):
        # The maintainers' private release registry: an ECR registry that reads only to who logged in.
        scratch = self.scratch(where=PRIVATE)
        out = scratch.run(SCRIPT, COLVENNA_CATALOG_REGISTRY=PRIVATE)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.copies(), [(f"{PRIVATE}/colvenna-catalog@{IMAGE}",
                                             f"{OWN_REPOSITORY}:{PIN['candidate']}")])
        self.assertIn(f"helm pull oci://{PRIVATE}/charts/colvenna-catalog@{CHART}", " ".join(scratch.calls()))
        self.assertEqual(self.logins(scratch), sorted([
            "aws ecr get-login-password", f"crane auth login {OWN} -u AWS --password-stdin",
            "aws ecr get-login-password --region us-east-2", f"crane auth login {RELEASE} -u AWS --password-stdin",
            f"helm registry login {RELEASE} --username AWS --password-stdin"]))
        self.assertNotIn("ghcr.io", " ".join(scratch.calls()))
        self.assertEqual(self.record(scratch)["chart_digest"], CHART)

    def test_another_registry_as_the_override_is_read_anonymously(self):
        mirror = "registry.example.com/mirror/colvenna"
        scratch = self.scratch(where=mirror)
        scratch.state["listed"].append(f"{mirror}/")
        out = scratch.run(SCRIPT, COLVENNA_CATALOG_REGISTRY=mirror)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.copies()[0][0], f"{mirror}/colvenna-catalog@{IMAGE}")
        self.assertEqual(self.logins(scratch), ["aws ecr get-login-password",
                                                f"crane auth login {OWN} -u AWS --password-stdin"])

    def test_a_malformed_override_is_refused_before_any_read(self):
        for bad in ("https://ghcr.io/colvenna", "ghcr.io/colvenna/", "GHCR.io/colvenna", "ghcr.io/colvenna:v1",
                    f"{PRIVATE}@{IMAGE}", "123456789012.dkr.ecr.cn-north-1.amazonaws.com.cn/colvenna-catalog-release",
                    "123.dkr.ecr.us-west-2.amazonaws.com/colvenna-catalog-release"):
            with self.subTest(bad=bad):
                scratch = self.scratch()
                out = scratch.run(SCRIPT, COLVENNA_CATALOG_REGISTRY=bad)
                self.assertEqual(out.returncode, 1)
                self.assertIn(f"COLVENNA_CATALOG_REGISTRY={bad!r}", out.stderr)
                # Only who the caller is: no registry read, no login.
                self.assertEqual([c for c in scratch.calls() if not c.startswith("aws sts get-caller-identity")], [])

    def test_a_release_s_copies_want_the_release_registry(self):
        scratch = self.scratch()
        out = scratch.run(SCRIPT, "--release", "v0.0.0-dev.9")
        self.assertEqual(out.returncode, 1)
        self.assertIn("COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com", out.stderr)
        self.assertEqual([c for c in scratch.calls() if not c.startswith("aws sts get-caller-identity")], [])


class InstallImagesCatalogTest(InstallImages):
    """A release install's catalog step: the candidate's copies of the pinned release."""

    def test_the_candidate_s_copies_are_fetched_and_checked_as_the_release(self):
        scratch = Scratch(self)
        self.release(scratch)
        registries(scratch)
        scratch.state["outputs"]["CatalogRepositoryUri"] = OWN_REPOSITORY
        scratch.state["digests"] += [f"{RELEASE}/colvenna-lake-release/colvenna-catalog@{IMAGE}",
                                     f"{RELEASE}/colvenna-lake-release/charts/colvenna-catalog@{CHART}"]
        scratch.state["configs"] = {IMAGE: image_config(), CHART: chart_config()}
        scratch.state["packages"] = {CHART: package()}
        # A catalog registry given beside it is not read: the candidate is installed from its own copies.
        out = scratch.run(self.script, self.candidate, "catalog", COLVENNA_RELEASE_REGISTRY=RELEASE,
                          COLVENNA_CATALOG_REGISTRY=PRIVATE)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.copies(), [(f"{RELEASE}/colvenna-lake-release/colvenna-catalog@{IMAGE}",
                                             f"{OWN_REPOSITORY}:{PIN['candidate']}")])
        self.assertIn(f"Colvenna Lake's release {self.candidate}", out.stdout)
        # Never from the catalog's own registries, public or private.
        self.assertNotIn("colvenna-catalog-release/", " ".join(scratch.calls()))
        self.assertNotIn("ghcr.io", " ".join(scratch.calls()))


class StubRegistry(http.server.BaseHTTPRequestHandler):
    """An OCI registry as GitHub Container Registry answers an anonymous reader: a manifest's HEAD
    without a token gets 401 and a Bearer challenge naming the token endpoint; the endpoint gives an
    anonymous token to pull a public repository and refuses one (403 DENIED) for a private one, or one
    that is not there; with the token, the HEAD answers 200 and the digest, or 404."""

    held = set()        # (repository, digest) the registry holds
    public = set()      # repositories whose token it gives
    seen = []           # (method, path, authorized) of each request

    def log_message(self, *args):
        pass

    def reply(self, code, headers=None, body=b""):
        self.send_response(code)
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def do_GET(self):
        self.answer()

    def do_HEAD(self):
        self.answer()

    def answer(self):
        authorization = self.headers.get("Authorization", "")
        self.seen.append((self.command, self.path, authorization))
        if self.path.startswith("/token?"):
            query = dict(p.split("=", 1) for p in self.path.split("?", 1)[1].split("&"))
            repository = query["scope"].split("%3A")[1].replace("%2F", "/")
            if repository in self.public:
                return self.reply(200, {"Content-Type": "application/json"},
                                  json.dumps({"token": f"anonymous-{repository}"}).encode())
            return self.reply(403, {"Content-Type": "application/json"},
                              b'{"errors":[{"code":"DENIED","message":"requested access to the resource is denied"}]}')
        if self.path.startswith("/v2/") and "/manifests/" in self.path:
            repository, digest = self.path[len("/v2/"):].split("/manifests/")
            if authorization != f"Bearer anonymous-{repository}":
                host = self.headers["Host"]
                return self.reply(401, {"WWW-Authenticate": f'Bearer realm="http://{host}/token",service="stub",'
                                                            f'scope="repository:{repository}:pull"'})
            if (repository, digest) in self.held:
                return self.reply(200, {"Docker-Content-Digest": digest,
                                        "Content-Type": "application/vnd.oci.image.manifest.v1+json"})
            return self.reply(404)
        self.reply(404)


class CatalogSourcePreflightTest(unittest.TestCase):
    """install.sh's preflight (lib/preflight.sh assert_catalog_source), before anything is made: the
    catalog release's source gives its image and chart by digest, an anonymous one to anyone."""

    @classmethod
    def setUpClass(cls):
        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), StubRegistry)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.registry = f"127.0.0.1:{cls.server.server_address[1]}/colvenna"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def serve(self, held=(IMAGE, CHART), public=True):
        StubRegistry.held = {("colvenna/colvenna-catalog", held[0]), ("colvenna/charts/colvenna-catalog", held[1])}
        StubRegistry.public = {"colvenna/colvenna-catalog", "colvenna/charts/colvenna-catalog"} if public else set()
        StubRegistry.seen = []

    def assert_source(self, registry, scratch=None):
        """assert_catalog_source as install.sh runs it, deploy/env.sh and lib/preflight.sh sourced, in a
        scratch tree whose stub aws answers for an ECR source."""
        scratch = scratch or Scratch(self)
        return scratch.run_bash(f'set -euo pipefail; source "{scratch.root}/deploy/env.sh"; '
                                f'source "{scratch.root}/deploy/scripts/lib/preflight.sh"; assert_catalog_source',
                                COLVENNA_CATALOG_REGISTRY=registry)

    def test_an_anonymous_source_holding_both_digests(self):
        self.serve()
        out = self.assert_source(self.registry)
        self.assertEqual(out.returncode, 0, out.stderr)
        # Each manifest read by its digest, with the anonymous token the challenge led to.
        heads = [(path, authorization) for method, path, authorization in StubRegistry.seen if method == "HEAD"]
        self.assertIn((f"/v2/colvenna/colvenna-catalog/manifests/{IMAGE}",
                       "Bearer anonymous-colvenna/colvenna-catalog"), heads)
        self.assertIn((f"/v2/colvenna/charts/colvenna-catalog/manifests/{CHART}",
                       "Bearer anonymous-colvenna/charts/colvenna-catalog"), heads)

    def test_a_digest_it_does_not_hold_is_refused(self):
        self.serve(held=(IMAGE, OTHER))
        out = self.assert_source(self.registry)
        self.assertEqual(out.returncode, 1)
        self.assertIn(f"{self.registry}/charts/colvenna-catalog@{CHART}, the chart: HTTP 404", out.stderr)
        self.assertNotIn("the image", out.stderr)

    def test_a_release_not_public_there_is_refused(self):
        self.serve(public=False)
        out = self.assert_source(self.registry)
        self.assertEqual(out.returncode, 1)
        self.assertIn("for an anonymous reader", out.stderr)
        self.assertIn("HTTP 403 DENIED: refused to an anonymous reader", out.stderr)

    def test_an_ecr_source_is_read_with_the_aws_credentials(self):
        scratch = Scratch(self)
        scratch.state["digests"] += [f"{PRIVATE}/colvenna-catalog@{IMAGE}",
                                     f"{PRIVATE}/charts/colvenna-catalog@{CHART}"]
        out = self.assert_source(PRIVATE, scratch)
        self.assertEqual(out.returncode, 0, out.stderr)
        reads = [c for c in scratch.calls() if c.startswith("aws ecr batch-get-image")]
        self.assertEqual(len(reads), 2)
        self.assertTrue(all("--region us-east-2 --registry-id 210987654321" in c for c in reads), reads)
        self.assertIn("--repository-name colvenna-catalog-release/charts/colvenna-catalog", reads[1])
        # Another digest there: refused, naming the setting.
        scratch.state["digests"] = [f"{PRIVATE}/colvenna-catalog@{IMAGE}"]
        out = self.assert_source(PRIVATE, scratch)
        self.assertEqual(out.returncode, 1)
        self.assertIn(f"COLVENNA_CATALOG_REGISTRY: {RELEASE} holds no "
                      f"colvenna-catalog-release/charts/colvenna-catalog@{CHART}", out.stderr)

    def test_a_malformed_one_is_refused(self):
        out = self.assert_source("https://ghcr.io/colvenna")
        self.assertEqual(out.returncode, 1)
        self.assertIn("COLVENNA_CATALOG_REGISTRY='https://ghcr.io/colvenna'", out.stderr)


class MakeManifestRegistryTest(unittest.TestCase):
    """make-manifest.sh copies a candidate into the release registry's repositories, those
    deploy/aws/src/release-stack.ts makes: one the stack has added since the registry was deployed is not
    there until deploy-registry.sh deploys it again. charts/colvenna-catalog, the catalog release's chart's
    (#295), is not in a registry deployed before. So a run finds every one first, before it checks the
    environment, let alone copies. The catalog's chart comes from where a source install fetches it,
    GitHub Container Registry or COLVENNA_CATALOG_REGISTRY: no release registry setting is needed."""

    candidate = "v0.0.0-dev.9"

    def run_it(self, missing=(), **env):
        scratch = Scratch(self)
        # The required cases, read from the commit.
        os.makedirs(os.path.join(scratch.root, "docs", "uat"))
        shutil.copy(os.path.join(REPO, "docs", "uat", "cases.md"), os.path.join(scratch.root, "docs", "uat"))
        scratch.commit("the cases")
        scratch.state["stacks"] = [manifest.STACK]
        scratch.state["repositories"] = sorted({f"{manifest.PREFIX}/{repository}"
                                                for _, repository, _ in manifest.names(self.candidate)
                                                if repository not in missing} | {f"{manifest.PREFIX}/doris"})
        out = scratch.run("deploy/scripts/release/make-manifest.sh", self.candidate, **env)
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
        for env in ({}, {"COLVENNA_CATALOG_REGISTRY": PRIVATE}):
            with self.subTest(env=env):
                _, out, stacks = self.run_it(**env)
                self.assertNotIn("has no", out.stderr)
                self.assertIn("release registry: every repository", out.stdout)
                # On to the first check of the environment, its stacks' commit, which the stub cannot answer.
                self.assertGreater(len(stacks), 0)
                self.assertIn("is of nothing", out.stderr)

    def test_a_malformed_catalog_registry_stops_it_before_anything(self):
        scratch, out, stacks = self.run_it(COLVENNA_CATALOG_REGISTRY="ghcr.io/colvenna/")
        self.assertEqual(out.returncode, 1)
        self.assertIn("COLVENNA_CATALOG_REGISTRY='ghcr.io/colvenna/'", out.stderr)
        self.assertEqual(stacks, [])
        self.assertEqual([c for c in scratch.calls() if c.startswith(("crane", "helm", "aws ecr"))], [])


if __name__ == "__main__":
    unittest.main()

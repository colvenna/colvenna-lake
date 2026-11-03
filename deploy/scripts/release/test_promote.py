"""Offline tests of promote.sh, end to end, as Colvenna Catalog's test_promote_publish.py: each runs in a
clone of its own, a scratch history B (the product), C (release/<candidate>/ added, tagged <candidate>)
and on top of it R, whose release/<candidate>/notes.md records the verdict; against a stub OCI registry
(registry_stub.py) on 127.0.0.1 holding the candidate's images, with the pinned crane
(deploy/scripts/foundation/install-crane.sh) and a stub aws that answers nothing, so that nothing
reaches AWS or a real registry.

The images are tagged by digest, never copied or rebuilt; release/<release>/ alone is committed on C and
tagged; a candidate not judged GO, a tree that is not the tag's, and a registry that does not hold the
candidate as its manifest says, or whose release tag holds another digest, are refused before anything
is written. And the release's tag passes the checks deploy/install.sh --release makes of its checkout
(lib/release.sh's release_checkout, which check-release.sh runs), a tampered one does not."""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, HERE)
import manifest  # noqa: E402
import promotion  # noqa: E402
import registry_stub  # noqa: E402

CANDIDATE, RELEASE = "v1.0.0-rc.7", "v1.0.0"
PREFIX = manifest.PREFIX
# What a clone of its own needs of this one: the scripts, and what the manifest pins or is checked against.
COPIED = ("deploy/scripts/release/promote.sh", "deploy/scripts/release/manifest.py",
          "deploy/scripts/release/promotion.py", "deploy/scripts/lib/git.sh", "deploy/scripts/lib/release.sh",
          "release/notes-template.md", "docs/uat/cases.md", ".gitignore",
          *(f"deploy/helm/{chart}/Chart.yaml" for chart in manifest.CHARTS))
# The clone's own third-party list: one image of each scope, pinned by the digests the stub gives them.
THIRD_PARTY = {"postgres": ("platform", "docker.io/library/postgres"), "busybox": ("fixtures", "docker.io/library/busybox")}
VERDICT_ROW = "| 发布结论 | <GO / NO-GO> | <发布检查报告的链接> |"
BASIS = "发布检查 [#2](https://github.com/colvenna/colvenna-lake/issues/2)"
GO = "**GO**。必需用例全部 PASS，性能与安全都通过"


def crane():
    """The pinned crane: deploy/.tools/crane, or one on PATH."""
    for path in (os.path.join(REPO, "deploy", ".tools", "crane"), shutil.which("crane")):
        if path and os.access(path, os.X_OK):
            return path
    return None


def setUpModule():
    if crane() is None:
        message = "crane is required: bash deploy/scripts/foundation/install-crane.sh"
        if os.environ.get("CI"):
            raise RuntimeError(message)
        raise unittest.SkipTest(message)


def git(repo, *args):
    return subprocess.run(["git", "-C", repo, *args], check=True, capture_output=True, text=True).stdout.strip()


def scratch():
    path = tempfile.mkdtemp()
    return path


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


# Builds a manifest with the clone's manifest.py, which reads the clone's third-party list and catalog pin.
CLONE_TOOL = r'''
import json, sys
sys.path.insert(0, sys.argv[1])
import manifest
command = sys.argv[2]
if command == "build":
    print(json.dumps(manifest.build(json.load(sys.stdin)), indent=2, ensure_ascii=False))
elif command == "notes":
    m, template = json.load(open(sys.argv[3])), open(sys.argv[4]).read()
    sys.stdout.write(manifest.notes(m, template))
elif command == "required-cases":
    print(json.dumps(manifest.required_cases(open(sys.argv[3]).read())))
'''


class World:
    """A clone with B, the candidate's tag commit C (release/<candidate>/ added to B, the tag annotated) and on
    top of it R, whose release/<candidate>/notes.md records the verdict (result, basis); HEAD at C, detached.
    The release registry is a stub with immutable tags, holding every image of the candidate under its tag.
    tag_change(repo) alters C beyond release/<candidate>/."""

    def __init__(self, result=GO, basis=BASIS, tag_change=None):
        self.repo = repo = scratch()
        git(repo, "init", "-q")
        git(repo, "config", "user.email", "test@example.com")
        git(repo, "config", "user.name", "test")
        git(repo, "config", "commit.gpgsign", "false")
        git(repo, "config", "tag.gpgsign", "false")
        for path in COPIED:
            os.makedirs(os.path.dirname(os.path.join(repo, path)), exist_ok=True)
            shutil.copy2(os.path.join(REPO, path), os.path.join(repo, path))
        for script in ("install", "uninstall"):
            write(os.path.join(repo, f"deploy/{script}.sh"), f"#!/usr/bin/env bash\n# {script}\n")
        self.registry = registry_stub.Registry(immutable=True)
        # Every image of the candidate in the stub, under its tag, each of its own bytes.
        self.digests = {}
        images = {**manifest.PRODUCT_IMAGES, **manifest.FIXTURE_IMAGES, "cli": manifest.CLI}
        seeded = [(key, repository, manifest.tag(CANDIDATE, variant)) for key, (repository, variant) in images.items()]
        seeded += [(key, repository, CANDIDATE) for key, repository in manifest.CATALOG_IMAGES.items()]
        seeded += [(name, manifest.THIRD_PARTY_REPOSITORY, manifest.tag(CANDIDATE, name)) for name in THIRD_PARTY]
        self.images = {}
        for key, repository, tag in seeded:
            self.digests[key] = self.registry.seed_image(f"{PREFIX}/{repository}", tag, layer=key.encode())
            self.images[key] = (repository, tag)
        # The clone's pins: the third-party list and the catalog release, by the stub's digests.
        write(os.path.join(repo, "deploy/images/third-party.tsv"), "".join(
            f"{name}\t{scope}\t{upstream}@{self.digests[name]}\tthe offline tests\n"
            for name, (scope, upstream) in THIRD_PARTY.items()))
        pin = manifest.catalog_pin()
        pin["image"]["digest"], pin["chart"]["digest"] = self.digests["catalog"], self.digests["catalog-chart"]
        write(os.path.join(repo, "deploy/images/colvenna-catalog.json"), json.dumps(pin, indent=2) + "\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "B")
        self.built_from = b = git(repo, "rev-parse", "HEAD")
        self.manifest = self.tool("build", stdin=json.dumps({
            "candidate": CANDIDATE, "built_from": b,
            "generated": {"at": "2026-11-02T06:00:00Z", "environment": "uat", "region": "us-west-2"},
            "digests": self.digests,
            "cli": {"digest": self.digests["cli"], "binaries": {name: "1" * 64 for name in manifest.CLI_BINARIES}},
            "charts": {chart: {"path": f"deploy/helm/{chart}", "version": "0.1.0", "appVersion": "0.1.0",
                               "tree": git(repo, "rev-parse", f"{b}:deploy/helm/{chart}")} for chart in manifest.CHARTS},
            "catalog": pin,
            "installer": {script: {"path": f"deploy/{script}.sh", "blob": git(repo, "rev-parse", f"{b}:deploy/{script}.sh")}
                          for script in ("install", "uninstall")},
            "uat_cases": {"path": "docs/uat/cases.md", "commit": git(repo, "log", "-1", "--format=%H", b, "--",
                                                                       "docs/uat/cases.md"),
                          "blob": git(repo, "rev-parse", f"{b}:docs/uat/cases.md")},
            "required_cases": json.loads(self.tool("required-cases", os.path.join(repo, "docs/uat/cases.md"))),
            "regions": ["us-west-2", "us-east-2"]}))
        directory = os.path.join(repo, "release", CANDIDATE)
        write(os.path.join(directory, "manifest.json"), self.manifest)
        self.manifest = json.loads(self.manifest)
        draft = self.tool("notes", os.path.join(directory, "manifest.json"),
                          os.path.join(repo, "release/notes-template.md"))
        assert VERDICT_ROW in draft
        write(os.path.join(directory, "notes.md"), draft)
        if tag_change:
            tag_change(repo)
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", f"release: {CANDIDATE}")
        self.tag_commit = git(repo, "rev-parse", "HEAD")
        git(repo, "tag", "-a", CANDIDATE, "-m", CANDIDATE)
        write(os.path.join(directory, "notes.md"), draft.replace(VERDICT_ROW, f"| 发布结论 | {result} | {basis} |"))
        git(repo, "commit", "-q", "-am", "the verdict")
        self.record = git(repo, "rev-parse", "HEAD")
        git(repo, "checkout", "-q", "--detach", self.tag_commit)
        self.registry.requests.clear()
        self.evidence = scratch()
        self.bin = scratch()
        self.aws_log = os.path.join(self.bin, "aws.log")
        write(os.path.join(self.bin, "aws"), f'#!/bin/sh\necho "aws $*" >>"{self.aws_log}"\nexit 97\n')
        os.chmod(os.path.join(self.bin, "aws"), 0o755)

    def tool(self, *args, stdin=None):
        out = subprocess.run([sys.executable, "-I", "-c", CLONE_TOOL, os.path.join(self.repo, "deploy/scripts/release"),
                              *args], capture_output=True, text=True, input=stdin)
        assert out.returncode == 0, out.stderr
        return out.stdout

    def environment(self, **settings):
        clean = {k: v for k, v in os.environ.items() if not k.startswith(("COLVENNA_", "RELEASE_", "AWS_"))}
        return {**clean, "PATH": f"{self.bin}:{os.path.dirname(crane())}:{clean['PATH']}", **settings}

    def promote(self, *args, **settings):
        settings = {"COLVENNA_RELEASE_REGISTRY": self.registry.host, "RELEASE_RECORD": self.record,
                    "RELEASE_EVIDENCE": self.evidence, **settings}
        return subprocess.run([os.path.join(self.repo, "deploy/scripts/release/promote.sh"), *(args or (CANDIDATE, RELEASE))],
                              capture_output=True, text=True, env=self.environment(**settings))

    def aws_calls(self):
        if not os.path.exists(self.aws_log):
            return []
        with open(self.aws_log) as f:
            return f.read().splitlines()

    def checkout(self, name):
        """release_checkout <name> from the clone as it is (lib/release.sh): what check-release.sh, and so
        deploy/install.sh --release, checks of the checkout before it reads the registry."""
        script = ('set -euo pipefail; REPO_DIR="$1"; source "$1/deploy/scripts/lib/git.sh"; '
                  'source "$1/deploy/scripts/lib/release.sh"; release_checkout "$2"; echo "$RELEASE_BUILT_FROM"')
        return subprocess.run(["bash", "-c", script, "-", self.repo, name], capture_output=True, text=True,
                              env=self.environment())

    def unchanged(self, test):
        """Nothing written: no request to the registry but reads, no tag, branch or release directory."""
        test.assertEqual(self.registry.writes(), [])
        test.assertEqual(git(self.repo, "tag", "--list", RELEASE), "")
        test.assertEqual(git(self.repo, "branch", "--list", f"release-{RELEASE}"), "")
        test.assertFalse(os.path.exists(os.path.join(self.repo, "release", RELEASE)))
        test.assertEqual(self.aws_calls(), [])


class Promote(unittest.TestCase):
    """A candidate judged GO, promoted."""

    @classmethod
    def setUpClass(cls):
        cls.world = World()
        cls.out = cls.world.promote()
        cls.release = json.loads(git(cls.world.repo, "show", f"{RELEASE}:release/{RELEASE}/manifest.json"))

    @classmethod
    def tearDownClass(cls):
        cls.world.registry.close()

    def test_it_succeeds_and_asks_nothing_of_aws(self):
        self.assertEqual(self.out.returncode, 0, self.out.stdout + self.out.stderr)
        self.assertEqual(self.world.aws_calls(), [])

    def test_the_tag_adds_only_release_v1_0_0_to_the_candidate_s_tag_commit(self):
        repo, tagged = self.world.repo, git(self.world.repo, "rev-parse", f"{RELEASE}^{{commit}}")
        self.assertEqual(git(repo, "cat-file", "-t", f"refs/tags/{RELEASE}"), "tag")
        self.assertEqual(git(repo, "rev-parse", f"{tagged}^"), self.world.tag_commit)
        self.assertEqual(git(repo, "diff", "--name-only", self.world.tag_commit, tagged).splitlines(),
                         [f"release/{RELEASE}/manifest.json", f"release/{RELEASE}/notes.md"])
        self.assertEqual(git(repo, "rev-parse", f"release-{RELEASE}"), tagged)
        self.assertEqual(git(repo, "status", "--porcelain"), "")

    def test_the_manifest_is_the_candidate_s_under_the_release_s_names(self):
        m, candidate = self.release, self.world.manifest
        out = subprocess.run([sys.executable, os.path.join(self.world.repo, "deploy/scripts/release/promotion.py"),
                              "validate", os.path.join(self.world.repo, f"release/{RELEASE}/manifest.json"),
                              "--release", RELEASE], capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(promotion.candidate_view(m), candidate)
        self.assertEqual({e["digest"] for e in promotion.image_entries(m)}, set(self.world.digests.values()))
        self.assertEqual(m["images"]["jobs-kafka"]["tag"], "v1.0.0-kafka")
        self.assertEqual(m["third_party"]["postgres"]["tag"], "v1.0.0-postgres")
        self.assertEqual(m["promotion"]["candidate"], CANDIDATE)
        self.assertEqual(m["promotion"]["tag_commit"], self.world.tag_commit)
        self.assertEqual(m["promotion"]["manifest"]["blob"],
                         git(self.world.repo, "rev-parse", f"{self.world.tag_commit}:release/{CANDIDATE}/manifest.json"))
        self.assertEqual(m["promotion"]["acceptance"], {
            "path": f"release/{CANDIDATE}/notes.md", "commit": self.world.record, "verdict": "GO",
            "blob": git(self.world.repo, "rev-parse", f"{self.world.record}:release/{CANDIDATE}/notes.md")})
        self.assertEqual(m["charts"], candidate["charts"])
        with open(os.path.join(self.world.evidence, "manifest.json")) as f:
            self.assertEqual(json.load(f), m)

    def test_every_image_is_tagged_by_digest_never_copied_or_rebuilt(self):
        registry = self.world.registry
        for key, (repository, tag) in self.world.images.items():
            release_tag = RELEASE + tag[len(CANDIDATE):]
            with self.subTest(key):
                self.assertEqual(registry.tag(f"{PREFIX}/{repository}", release_tag), self.world.digests[key])
                self.assertEqual(registry.tag(f"{PREFIX}/{repository}", tag), self.world.digests[key])
        writes = registry.writes()
        self.assertEqual(len(writes), len(self.world.images))
        for method, path in writes:
            self.assertEqual(method, "PUT")
            self.assertRegex(path, rf"^/v2/{PREFIX}/.+/manifests/v1\.0\.0(-[a-z0-9-]+)?$")

    def test_the_notes_are_the_candidate_s_under_the_release_s_name(self):
        notes = git(self.world.repo, "show", f"{RELEASE}:release/{RELEASE}/notes.md")
        accepted = git(self.world.repo, "show", f"{self.world.record}:release/{CANDIDATE}/notes.md")
        self.assertIn(f"# Colvenna Lake {RELEASE} 发布说明", notes)
        self.assertIn(f"| 发布结论 | {GO} | {BASIS} |", notes)
        self.assertIn(f"blob/{self.world.record}/release/{CANDIDATE}/notes.md", notes)
        self.assertEqual(notes, promotion.notes(self.release, accepted + "\n").rstrip("\n"))

    def test_the_release_s_tag_passes_install_sh_release_s_checks(self):
        repo = self.world.repo
        self.assertEqual(git(repo, "rev-parse", "HEAD"), git(repo, "rev-parse", f"{RELEASE}^{{commit}}"))
        out = self.world.checkout(RELEASE)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(out.stdout, f"{self.world.built_from}\n")
        # The release's checkout is not the candidate's: it adds release/v1.0.0/ to what the candidate's tag adds.
        out = self.world.checkout(CANDIDATE)
        self.assertEqual(out.returncode, 1)
        self.assertIn(f"outside release/{CANDIDATE}/ (what its tag adds)", out.stderr)

    def test_a_rehearsal_s_commit_and_tag_say_so(self):
        message = git(self.world.repo, "for-each-ref", "--format=%(contents)", f"refs/tags/{RELEASE}")
        self.assertIn(f"a rehearsal against the loopback registry {self.world.registry.host}, not a release: never"
                      " push it", message)
        self.assertIn("rehearsal", git(self.world.repo, "log", "-1", "--format=%s", RELEASE))
        self.assertIn(f"git tag -d {RELEASE}", self.out.stdout)
        self.assertNotIn("next: git push", self.out.stdout)

    def test_a_release_is_promoted_once(self):
        git(self.world.repo, "checkout", "-q", "--detach", self.world.tag_commit)
        try:
            out = self.world.promote()
            self.assertEqual(out.returncode, 2, out.stderr)
            self.assertIn(f"The tag {RELEASE} exists already", out.stderr)
        finally:
            git(self.world.repo, "checkout", "-q", f"release-{RELEASE}")


class ReleaseCheckout(unittest.TestCase):
    """What install.sh --release v1.0.0 refuses of a checkout that is not the release's tag as promote.sh made
    it: each a commit on the candidate's tag commit in place of the release's."""

    @classmethod
    def setUpClass(cls):
        cls.world = World()
        out = cls.world.promote()
        assert out.returncode == 0, out.stdout + out.stderr
        cls.tagged = git(cls.world.repo, "rev-parse", f"{RELEASE}^{{commit}}")
        cls.release = json.loads(git(cls.world.repo, "show", f"{RELEASE}:release/{RELEASE}/manifest.json"))

    @classmethod
    def tearDownClass(cls):
        cls.world.registry.close()

    def tearDown(self):
        git(self.world.repo, "checkout", "-q", "-f", "--detach", self.tagged)
        git(self.world.repo, "clean", "-qfdx")

    def commit(self, m=None, extra=None):
        """A commit like the release's on C: its manifest m (default the release's), and the file extra too."""
        repo = self.world.repo
        git(repo, "checkout", "-q", "--detach", self.tagged)
        if m is not None:
            write(os.path.join(repo, f"release/{RELEASE}/manifest.json"), json.dumps(m, indent=2) + "\n")
        if extra:
            write(os.path.join(repo, extra), "changed\n")
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "--amend", "-m", "tampered")

    def assertRefused(self, *needles):
        out = self.world.checkout(RELEASE)
        self.assertEqual(out.returncode, 1, out.stdout)
        for needle in needles:
            self.assertIn(needle, out.stderr)

    def test_the_tag_itself_passes(self):
        self.assertEqual(self.world.checkout(RELEASE).returncode, 0)

    def test_a_change_outside_what_the_tags_add(self):
        self.commit(extra="deploy/install.sh")
        self.assertRefused(f"outside release/{CANDIDATE}/ release/{RELEASE}/ (what its tag adds)", "deploy/install.sh")

    def test_a_manifest_whose_digest_is_not_the_candidate_s(self):
        m = json.loads(json.dumps(self.release))
        m["images"]["operator"]["digest"] = m["images"]["console"]["digest"]
        self.commit(m)
        self.assertRefused(f"is not {CANDIDATE}'s under the release's names", "images differ",
                           "does not describe this checkout")

    def test_an_acceptance_record_that_is_not_go(self):
        # The candidate's tag commit's own notes, the template's draft: no verdict.
        m = json.loads(json.dumps(self.release))
        m["promotion"]["acceptance"].update(commit=self.world.tag_commit, blob=git(
            self.world.repo, "rev-parse", f"{self.world.tag_commit}:release/{CANDIDATE}/notes.md"))
        self.commit(m)
        self.assertRefused("<GO / NO-GO>, not GO")

    def test_a_promotion_from_another_commit(self):
        m = json.loads(json.dumps(self.release))
        m["promotion"]["tag_commit"] = self.world.built_from
        self.commit(m)
        self.assertRefused(f"outside release/{RELEASE}/", "promotion.manifest")

    def test_a_dirty_checkout(self):
        write(os.path.join(self.world.repo, "deploy/install.sh"), "edited\n")
        self.assertRefused("Commit or remove local changes first")


class CandidateCheckout(unittest.TestCase):
    """What install.sh --release v1.0.0-rc.7 checks of a candidate's checkout: its tag passes; a commit on it
    that changes what the manifest pins, or adds to release/ beside release/<candidate>/, does not."""

    @classmethod
    def setUpClass(cls):
        cls.world = World()

    @classmethod
    def tearDownClass(cls):
        cls.world.registry.close()

    def tearDown(self):
        git(self.world.repo, "checkout", "-q", "-f", "--detach", self.world.tag_commit)
        git(self.world.repo, "clean", "-qfdx")

    def commit(self, path, text):
        write(os.path.join(self.world.repo, path), text)
        git(self.world.repo, "add", "-A")
        git(self.world.repo, "commit", "-q", "-m", "changed")

    def test_the_tag_passes(self):
        out = self.world.checkout(CANDIDATE)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(out.stdout, f"{self.world.built_from}\n")

    def test_a_manifest_that_pins_another_chart_directory(self):
        m = json.loads(json.dumps(self.world.manifest))
        m["charts"]["colvenna-bi"]["tree"] = self.world.built_from
        self.commit(f"release/{CANDIDATE}/manifest.json", json.dumps(m, indent=2) + "\n")
        out = self.world.checkout(CANDIDATE)
        self.assertEqual(out.returncode, 1)
        self.assertIn("charts.colvenna-bi.tree: deploy/helm/colvenna-bi is", out.stderr)
        self.assertIn("does not describe this checkout", out.stderr)

    def test_another_directory_of_release(self):
        self.commit("release/v0.0.0-dev.1/notes.md", "a trial's\n")
        out = self.world.checkout(CANDIDATE)
        self.assertEqual(out.returncode, 1)
        self.assertIn(f"outside release/{CANDIDATE}/ (what its tag adds)", out.stderr)
        self.assertIn("release/v0.0.0-dev.1/notes.md", out.stderr)


class PromoteAgain(unittest.TestCase):
    def test_a_run_that_stopped_keeps_the_release_tags_it_wrote(self):
        world = World()
        self.addCleanup(world.registry.close)
        held = [key for key in world.images if key.startswith("jobs-")]
        for key in held:
            repository, tag = world.images[key]
            world.registry.tags[f"{PREFIX}/{repository}"][RELEASE + tag[len(CANDIDATE):]] = world.digests[key]
        out = world.promote()
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        self.assertEqual(len(world.registry.writes()), len(world.images) - len(held))
        self.assertIn(f"{len(held)} release tags there already with those digests", out.stdout)


class PromoteRefuses(unittest.TestCase):
    def world(self, **options):
        world = World(**options)
        self.addCleanup(world.registry.close)
        return world

    def assertRefused(self, out, status, *needles):
        self.assertEqual(out.returncode, status, out.stdout + out.stderr)
        for needle in needles:
            self.assertIn(needle, out.stderr)

    def test_a_candidate_judged_no_go(self):
        world = self.world(result="**NO-GO**。阻塞项是产品缺陷 #22")
        self.assertRefused(world.promote(), 2, "not GO", f"{CANDIDATE} is not judged GO")
        world.unchanged(self)
        self.assertEqual(world.registry.requests, [])

    def test_the_tag_s_draft_notes_record_no_verdict(self):
        world = self.world()
        self.assertRefused(world.promote(RELEASE_RECORD=world.tag_commit), 2, "<GO / NO-GO>, not GO")
        world.unchanged(self)
        self.assertEqual(world.registry.requests, [])

    def test_a_verdict_whose_basis_links_nothing(self):
        world = self.world(basis="发布检查 #2")
        self.assertRefused(world.promote(), 2, "basis is 发布检查 #2")
        world.unchanged(self)

    def test_a_record_that_is_not_after_the_candidate_s_tag(self):
        world = self.world()
        self.assertRefused(world.promote(RELEASE_RECORD=world.built_from), 2, "does not have")
        world.unchanged(self)

    def test_a_record_whose_manifest_is_not_the_tag_s(self):
        world = self.world()
        git(world.repo, "checkout", "-q", world.record)
        edited = dict(world.manifest, generated={**world.manifest["generated"], "at": "2026-11-03T00:00:00Z"})
        write(os.path.join(world.repo, f"release/{CANDIDATE}/manifest.json"), json.dumps(edited))
        git(world.repo, "commit", "-q", "-am", "changed")
        record = git(world.repo, "rev-parse", "HEAD")
        git(world.repo, "checkout", "-q", "--detach", world.tag_commit)
        self.assertRefused(world.promote(RELEASE_RECORD=record), 2, "is not the one")
        world.unchanged(self)

    def test_head_that_is_not_the_candidate_s_tag_commit(self):
        world = self.world()
        git(world.repo, "checkout", "-q", "--detach", world.record)
        self.assertRefused(world.promote(), 2, f"not {CANDIDATE}'s tag commit", f"git checkout {CANDIDATE}")
        world.unchanged(self)

    def test_a_dirty_tree(self):
        world = self.world()
        write(os.path.join(world.repo, "deploy/install.sh"), "edited\n")
        self.assertRefused(world.promote(), 2, "Commit first")
        world.unchanged(self)

    def test_an_existing_tag_or_branch_of_the_release(self):
        for ref, needle in ((("tag", RELEASE), f"The tag {RELEASE} exists already"),
                            (("branch", f"release-{RELEASE}"), f"The branch release-{RELEASE} exists already")):
            with self.subTest(ref):
                world = self.world()
                git(world.repo, *ref, world.built_from)
                self.assertRefused(world.promote(), 2, needle)
                self.assertEqual(world.registry.requests, [])
                self.assertFalse(os.path.exists(os.path.join(world.repo, "release", RELEASE)))

    def test_a_tag_commit_that_is_not_b_with_release_candidate_added(self):
        world = self.world(tag_change=lambda repo: write(os.path.join(repo, "deploy/install.sh"), "changed\n"))
        self.assertRefused(world.promote(), 2, f"differs from {world.built_from} outside release/{CANDIDATE}/",
                           "deploy/install.sh")
        world.unchanged(self)

    def test_a_tag_commit_that_has_release_v1_0_0_already(self):
        world = self.world(tag_change=lambda repo: write(os.path.join(repo, f"release/{RELEASE}/notes.md"), "x\n"))
        self.assertRefused(world.promote(), 2, f"release/{RELEASE} is already at")
        self.assertEqual(world.registry.requests, [])
        self.assertEqual(git(world.repo, "tag", "--list", RELEASE), "")

    def test_names_that_are_not_a_candidate_and_its_release(self):
        world = self.world()
        for args in ((CANDIDATE,), (CANDIDATE, "v1.0.1"), (CANDIDATE, "v1.0.0-rc.8"), (RELEASE, RELEASE),
                     ("v1.0.0-dev.1", RELEASE), (CANDIDATE, RELEASE, "extra")):
            with self.subTest(args):
                self.assertEqual(world.promote(*args).returncode, 2)
                world.unchanged(self)
        self.assertEqual(world.registry.requests, [])

    def test_aws_ecr_public_as_the_release_registry(self):
        world = self.world()
        self.assertRefused(world.promote(COLVENNA_RELEASE_REGISTRY="public.ecr.aws"), 2,
                           "AWS ECR Public is never a target")
        world.unchanged(self)

    def test_a_release_tag_that_holds_another_digest(self):
        world = self.world()
        other = world.registry.seed_image(f"{PREFIX}/colvenna-jobs", "v1.0.0-kafka", layer=b"other")
        world.registry.requests.clear()
        self.assertRefused(world.promote(), 3, f"{PREFIX}/colvenna-jobs:v1.0.0-kafka is already {other}",
                           "tags never move")
        self.assertEqual(world.registry.tag(f"{PREFIX}/colvenna-jobs", "v1.0.0-kafka"), other)
        world.unchanged(self)

    def test_a_registry_that_does_not_hold_the_candidate_as_its_manifest_says(self):
        world = self.world()
        del world.registry.tags[f"{PREFIX}/colvenna-cli"][CANDIDATE]
        other = world.registry.seed_image(f"{PREFIX}/colvenna-console", CANDIDATE, layer=b"other")
        world.registry.requests.clear()
        self.assertRefused(world.promote(), 3, f"cli: {PREFIX}/colvenna-cli:{CANDIDATE} is not there",
                           f"console: {PREFIX}/colvenna-console:{CANDIDATE} is {other}, not the manifest's"
                           f" {world.digests['console']}", "(2 of")
        world.unchanged(self)

    def test_a_registry_that_cannot_be_read_is_a_failure_not_a_mismatch(self):
        world = self.world()
        world.registry.denied = True
        out = world.promote()
        self.assertRefused(out, 1, "Cannot read", "DENIED")
        self.assertNotIn("is not there", out.stderr)
        world.unchanged(self)


if __name__ == "__main__":
    unittest.main()

"""Offline tests of promotion.py, and of manifest.py reading a release's manifest: a release's names, the GO
verdict it is promoted on (the release notes' 发布结论 row), the release's manifest and its validation,
the release notes, and the registries promote.sh accepts (never AWS ECR Public). Where a release was
promoted from (check-tree) is tested on a scratch git history in test_promote.py."""
import copy
import glob
import json
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
sys.path.insert(0, HERE)
import manifest  # noqa: E402
import promotion  # noqa: E402
import test_manifest  # noqa: E402  (its inputs(): what make-manifest.sh gathers, synthetic)

CANDIDATE, RELEASE = "v1.0.0-rc.1", "v1.0.0"
TAG_COMMIT, RECORD = "1" * 40, "3" * 40
# The template's verdict row, and a basis that links the release check, as rc.6's does.
VERDICT_ROW = "| 发布结论 | <GO / NO-GO> | <发布检查报告的链接> |"
BASIS = "发布检查 [#2](https://github.com/colvenna/colvenna-lake/issues/2)"


def candidate_manifest():
    return manifest.build(test_manifest.inputs())


def params(**changes):
    return {"release": RELEASE, "at": "2026-11-03T06:00:00Z", "tag_commit": TAG_COMMIT, "manifest_blob": "2" * 40,
            "acceptance": {"commit": RECORD, "blob": "4" * 40}, **changes}


def release_manifest():
    return promotion.build(candidate_manifest(), params())


def notes_with(result, basis=BASIS):
    """The candidate's notes (the template's draft) with the release verdict and its basis filled in."""
    with open(os.path.join(REPO, "release", "notes-template.md")) as f:
        text = manifest.notes(candidate_manifest(), f.read())
    assert VERDICT_ROW in text
    return text.replace(VERDICT_ROW, f"| 发布结论 | {result} | {basis} |")


def run(tool, *args, stdin=None):
    return subprocess.run([sys.executable, os.path.join(HERE, tool), *args], capture_output=True, text=True,
                          input=stdin)


class Names(unittest.TestCase):
    def test_a_release_s_tags_are_its_candidate_s_under_its_own_name(self):
        rows = {row[0]: row[1:] for row in promotion.names("v1.0.0-rc.7", RELEASE)}
        self.assertEqual(rows["operator"], ("colvenna-operator", "v1.0.0-rc.7", "v1.0.0"))
        self.assertEqual(rows["jobs-kafka"], ("colvenna-jobs", "v1.0.0-rc.7-kafka", "v1.0.0-kafka"))
        self.assertEqual(rows["query-engine-fe"], ("query-engine", "v1.0.0-rc.7-fe", "v1.0.0-fe"))
        self.assertEqual(rows["cli"], ("colvenna-cli", "v1.0.0-rc.7", "v1.0.0"))
        self.assertEqual(rows["catalog"], ("colvenna-catalog", "v1.0.0-rc.7", "v1.0.0"))
        self.assertEqual(rows["catalog-chart"], ("charts/colvenna-catalog", "v1.0.0-rc.7", "v1.0.0"))
        self.assertEqual(rows["debezium-server"], ("third-party", "v1.0.0-rc.7-debezium-server", "v1.0.0-debezium-server"))
        self.assertEqual(set(rows), {key for key, _, _ in manifest.names("v1.0.0-rc.7")})

    def test_a_candidate_promotes_to_its_own_version_only(self):
        for candidate, release in (("v1.0.0-rc.2", "v1.0.1"), ("v1.0.0-rc.2", "v1.0.0-rc.3"), ("v1.0.0", "v1.0.0"),
                                   ("v1.0.0-rc.2", "1.0.0"), ("v1.0.0-rc.0", "v1.0.0"), ("v1.1.0-rc.1", "v1.0.0"),
                                   ("v1.0.0-dev.1", "v1.0.0"), ("v1.0.0-rc.01", "v1.0.0")):
            with self.subTest(candidate=candidate, release=release):
                with self.assertRaises(ValueError):
                    promotion.names(candidate, release)

    def test_the_names_install_sh_release_takes(self):
        self.assertEqual(manifest.kind("v1.0.0-rc.7"), "candidate")
        self.assertEqual(manifest.kind("v0.0.0-dev.1"), "candidate")
        self.assertEqual(manifest.kind("v1.0.0"), "release")
        for name in ("1.0.0", "v1.0", "v1.0.0-rc.0", "v1.0.0-beta.1", "latest", ""):
            with self.subTest(name):
                with self.assertRaises(ValueError):
                    manifest.kind(name)
        self.assertEqual(run("manifest.py", "kind", "v1.0.0").stdout, "release\n")
        self.assertEqual(run("manifest.py", "names", "v1.0.0").returncode, 0)
        self.assertEqual(run("manifest.py", "names", "v1.0").returncode, 1)

    def test_a_candidate_s_manifest_never_has_a_release_s_name(self):
        # make-manifest.sh makes candidates only: a manifest under a release's name is not a candidate's.
        m = candidate_manifest()
        m["candidate"] = RELEASE
        self.assertTrue(any(p.startswith("candidate: 'v1.0.0' is not") for p in manifest.problems(m)))


class Verdict(unittest.TestCase):
    def test_go_plain_bold_or_as_code_alone_or_with_what_it_rests_on(self):
        for result in ("GO", "**GO**", "`GO`", "**GO**。必需用例全部 PASS，性能与安全都通过", "GO：见发布检查"):
            with self.subTest(result):
                self.assertEqual(promotion.verdict(notes_with(result)), "GO")

    def test_anything_but_go_is_refused(self):
        for result in ("NO-GO", "**NO-GO**", "**NO-GO**。阻塞项是产品缺陷 #22", "<GO / NO-GO>", "GO (pending)",
                       "GO（待定）", "", "go", "GOOD", "未定"):
            with self.subTest(result):
                with self.assertRaises(ValueError) as caught:
                    promotion.verdict(notes_with(result))
                self.assertIn("not GO", str(caught.exception))

    def test_the_basis_links_the_release_check(self):
        for basis in ("<发布检查报告的链接>", "发布检查 #2", "", "[#2]"):
            with self.subTest(basis):
                with self.assertRaises(ValueError) as caught:
                    promotion.verdict(notes_with("**GO**", basis))
                self.assertIn("basis", str(caught.exception))

    def test_one_verdict_row(self):
        text = notes_with("**GO**")
        for changed in (text.replace(f"| 发布结论 | **GO** | {BASIS} |", ""),
                        text + f"\n| 发布结论 | **GO** | {BASIS} |\n"):
            with self.subTest(len(changed)):
                with self.assertRaises(ValueError) as caught:
                    promotion.verdict(changed)
                self.assertIn("'发布结论' rows, not one", str(caught.exception))

    def test_the_template_s_draft_is_no_verdict(self):
        with open(os.path.join(REPO, "release", "notes-template.md")) as f:
            draft = manifest.notes(candidate_manifest(), f.read())
        with self.assertRaises(ValueError) as caught:
            promotion.verdict(draft)
        self.assertIn("<GO / NO-GO>, not GO", str(caught.exception))

    def test_every_candidate_on_record_is_no_go(self):
        # rc.2 to rc.6 were judged NO-GO, in the notes' own words: none of them promotes.
        paths = sorted(glob.glob(os.path.join(REPO, "release", "v1.0.0-rc.*", "notes.md")))
        self.assertGreaterEqual(len(paths), 5)
        for path in paths:
            with self.subTest(os.path.relpath(path, REPO)):
                with open(path) as f:
                    text = f.read()
                with self.assertRaises(ValueError) as caught:
                    promotion.verdict(text)
                self.assertIn("NO-GO", str(caught.exception))
        self.assertEqual(run("promotion.py", "verdict", paths[-1]).returncode, 1)


class Manifest(unittest.TestCase):
    def test_the_candidate_s_under_the_release_s_names_with_the_same_digests(self):
        candidate, m = candidate_manifest(), release_manifest()
        self.assertEqual(promotion.problems(m, RELEASE), [])
        self.assertNotIn("candidate", m)
        self.assertEqual(list(m)[:3], ["schema", "release", "promotion"])
        self.assertEqual(m["schema"], manifest.SCHEMA)
        self.assertEqual(m["source"], {**candidate["source"], "tag": "v1.0.0 points at v1.0.0-rc.1's tag commit with"
                                                                    " release/v1.0.0/ added, nothing else"})
        self.assertEqual(m["images"]["jobs-kafka"], {**candidate["images"]["jobs-kafka"], "tag": "v1.0.0-kafka"})
        self.assertEqual(m["images"]["operator"]["tag"], "v1.0.0")
        self.assertEqual(m["fixtures"]["third_party"]["debezium-server"]["tag"], "v1.0.0-debezium-server")
        self.assertEqual(m["third_party"]["postgres"]["tag"], "v1.0.0-postgres")
        self.assertEqual(m["cli"], {**candidate["cli"], "tag": "v1.0.0"})
        self.assertEqual(m["catalog"]["image"], {**candidate["catalog"]["image"], "tag": "v1.0.0"})
        self.assertEqual(m["catalog"]["chart"], {**candidate["catalog"]["chart"], "tag": "v1.0.0"})
        self.assertEqual([e["digest"] for e in promotion.image_entries(m)],
                         [e["digest"] for e in promotion.image_entries(candidate)])
        for key in ("generated", "charts", "installer", "uat_cases", "required_cases", "configurations",
                    "performance"):
            self.assertEqual(m[key], candidate[key], key)
        self.assertEqual(m["promotion"], {
            "candidate": CANDIDATE, "tag_commit": TAG_COMMIT, "at": "2026-11-03T06:00:00Z",
            "manifest": {"path": f"release/{CANDIDATE}/manifest.json", "blob": "2" * 40},
            "acceptance": {"path": f"release/{CANDIDATE}/notes.md", "commit": RECORD, "blob": "4" * 40,
                           "verdict": "GO"}})

    def test_its_candidate_view_is_the_candidate_s_manifest(self):
        self.assertEqual(promotion.candidate_view(release_manifest()), candidate_manifest())

    def test_manifest_py_reads_a_release_s_as_install_sh_release_does(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "manifest.json")
            with open(path, "w") as f:
                json.dump(release_manifest(), f)
            self.assertEqual(manifest.valid(path, RELEASE)["release"], RELEASE)
            with self.assertRaises(ValueError):
                manifest.valid(path, CANDIDATE)
            self.assertEqual(run("manifest.py", "validate", path, "--name", RELEASE).returncode, 0)
            self.assertIn("not v1.0.0-rc.1's", run("manifest.py", "validate", path, "--name", CANDIDATE).stderr)
            self.assertEqual(run("manifest.py", "added", path).stdout, f"release/{CANDIDATE}\nrelease/{RELEASE}\n")
            rows = [line.split("\t") for line in run("manifest.py", "images", path, "--fixtures", "--cli").stdout.splitlines()]
            self.assertIn(["jobs-kafka", "colvenna-jobs", "v1.0.0-kafka", test_manifest.DIGEST], rows)
            self.assertIn(["catalog-chart", "charts/colvenna-catalog", "v1.0.0", manifest.catalog_pin()["chart"]["digest"]],
                          rows)
            self.assertEqual({r[2] for r in rows if not r[2].startswith("v1.0.0-")}, {"v1.0.0"})
            self.assertEqual(run("manifest.py", "get", path, "source.built_from").stdout, test_manifest.SHA + "\n")
            # A release's notes are its candidate's rewritten (promotion.py notes), never the template's draft.
            self.assertIn("a release's", run("manifest.py", "notes", path, stdin="").stderr)
            self.assertEqual(run("promotion.py", "validate", path, "--release", RELEASE).returncode, 0)

    def test_a_candidate_s_manifest_still_names_its_own(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "manifest.json")
            with open(path, "w") as f:
                json.dump(candidate_manifest(), f)
            self.assertEqual(run("manifest.py", "added", path).stdout, f"release/{CANDIDATE}\n")
            self.assertEqual(run("manifest.py", "validate", path, "--name", CANDIDATE).returncode, 0)
            self.assertNotEqual(run("promotion.py", "validate", path).returncode, 0)

    def assertFound(self, change, needle):
        m = release_manifest()
        change(m)
        found = promotion.problems(m, RELEASE)
        self.assertTrue(any(needle in p for p in found), found)

    def test_what_a_release_s_manifest_is_not(self):
        self.assertFound(lambda m: m.update(release="v1.0.1"), "not v1.0.0's")
        self.assertFound(lambda m: m.update(release="v1.0.0-rc.1"), "release: 'v1.0.0-rc.1' is not")
        self.assertFound(lambda m: m.update(candidate=CANDIDATE), "unknown field candidate")
        self.assertFound(lambda m: m.pop("promotion"), "promotion: missing")
        self.assertFound(lambda m: m["promotion"].pop("at"), "promotion: not an object of")
        self.assertFound(lambda m: m["promotion"].update(candidate="v1.0.1-rc.1"), "not a candidate of v1.0.0")
        self.assertFound(lambda m: m["promotion"].update(candidate="v1.0.0-dev.1"), "promotion.candidate:")
        self.assertFound(lambda m: m["promotion"].update(tag_commit="abc"), "promotion.tag_commit")
        self.assertFound(lambda m: m["promotion"].update(at="2026-11-03"), "promotion.at")
        self.assertFound(lambda m: m["promotion"]["acceptance"].update(verdict="NO-GO"), "verdict: 'NO-GO', not GO")
        self.assertFound(lambda m: m["promotion"]["acceptance"].update(path="release/v1.0.0-rc.2/notes.md"),
                         "promotion.acceptance.path")
        self.assertFound(lambda m: m["promotion"]["manifest"].update(blob="x"), "promotion.manifest.blob")
        self.assertFound(lambda m: m["source"].update(tag="v1.0.0"), "source.tag")
        # A tag left as the candidate's, or not the release's form: the candidate's view shows it.
        self.assertFound(lambda m: m["images"]["operator"].update(tag=CANDIDATE), "images.operator.tag")
        self.assertFound(lambda m: m["images"]["jobs-kafka"].update(tag="v1.0.0"), "images.jobs-kafka.tag")
        self.assertFound(lambda m: m["third_party"]["postgres"].update(tag="latest"), "third_party.postgres.tag")
        self.assertFound(lambda m: m["catalog"]["chart"].update(tag="v1.0.0-rc.1"), "catalog.chart.tag")
        # What the candidate's manifest may not be, a release's may not either.
        self.assertFound(lambda m: m["third_party"]["postgres"].update(digest=test_manifest.DIGEST),
                         "third_party.postgres.digest")
        self.assertFound(lambda m: m["images"].pop("bi"), "images.bi: missing")

    def test_build_takes_a_valid_candidate_s_and_its_own_release(self):
        broken = copy.deepcopy(candidate_manifest())
        broken["images"].pop("bi")
        with self.assertRaises(ValueError):
            promotion.build(broken, params())
        with self.assertRaises(ValueError):
            promotion.build(candidate_manifest(), params(release="v1.0.1"))
        with self.assertRaises(ValueError):
            promotion.build(candidate_manifest(), params(tag_commit="x"))

    def test_assemble_reads_what_promote_sh_gathered(self):
        with tempfile.TemporaryDirectory() as d:
            for name, value in (("candidate.json", candidate_manifest()), ("params.json", params())):
                with open(os.path.join(d, name), "w") as f:
                    json.dump(value, f)
            out = run("promotion.py", "assemble", d)
            self.assertEqual(out.returncode, 0, out.stderr)
            self.assertEqual(json.loads(out.stdout), release_manifest())


class Notes(unittest.TestCase):
    def test_the_candidate_s_as_accepted_under_the_release_s_name(self):
        accepted = notes_with("**GO**。必需用例全部 PASS")
        text = promotion.notes(release_manifest(), accepted)
        self.assertIn('title: "Colvenna Lake v1.0.0 发布说明"\n', text)
        self.assertIn("date: 2026-11-03\n", text)
        self.assertIn("status: final\n", text)
        self.assertIn("# Colvenna Lake v1.0.0 发布说明\n\n## 1. 正式版\n", text)
        self.assertNotIn("# Colvenna Lake v1.0.0-rc.1 发布说明", text)
        self.assertNotIn("## 1. 候选", text)
        self.assertNotIn("发布说明的模板", text)
        self.assertIn(f"promote.sh 把 {CANDIDATE} 晋升为 {RELEASE}", text)
        self.assertIn(f"https://github.com/colvenna/colvenna-lake/blob/{RECORD}/release/{CANDIDATE}/notes.md", text)
        self.assertIn(f"`{CANDIDATE}` 的 tag 提交 `{TAG_COMMIT}` 上只多了 `release/{RELEASE}/`", text)
        self.assertIn("deploy/install.sh --release v1.0.0 [--fixtures]", text)
        self.assertIn("`colvenna-bi` 0.1.0", text)
        count = len(promotion.image_entries(release_manifest()))
        self.assertIn(f"`{CANDIDATE}` 的 {count} 个镜像", text)
        # Every section after the first is the candidate's, unchanged: its verdict among them.
        self.assertEqual(text[text.index("## 2. "):], accepted[accepted.index("## 2. "):])
        self.assertIn(f"| 发布结论 | **GO**。必需用例全部 PASS | {BASIS} |", text)

    def test_the_command_reads_the_notes_on_stdin(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "manifest.json")
            with open(path, "w") as f:
                json.dump(release_manifest(), f)
            out = run("promotion.py", "notes", path, stdin=notes_with("**GO**"))
            self.assertEqual(out.returncode, 0, out.stderr)
            self.assertEqual(out.stdout, promotion.notes(release_manifest(), notes_with("**GO**")))

    def test_notes_that_are_not_the_candidate_s(self):
        accepted = notes_with("**GO**")
        for text in (accepted.replace(f"# Colvenna Lake {CANDIDATE} 发布说明", "# Colvenna Lake v1.0.0-rc.2 发布说明"),
                     accepted.replace("## 1. 候选", "## 1. 身份"), accepted[:accepted.index("## 2. ")]):
            with self.subTest(len(text)):
                with self.assertRaises(ValueError):
                    promotion.notes(release_manifest(), text)


class Registry(unittest.TestCase):
    def test_the_release_registry_or_a_loopback_one(self):
        self.assertEqual(promotion.registry_kind("123456789012.dkr.ecr.us-west-2.amazonaws.com"), "ecr")
        self.assertEqual(promotion.registry_kind("127.0.0.1:5000"), "loopback")
        self.assertEqual(promotion.registry_kind("localhost:5000"), "loopback")

    def test_never_aws_ecr_public_or_another_registry(self):
        for registry in ("public.ecr.aws", "public.ecr.aws/colvenna", "ghcr.io", "123456789012.dkr.ecr.cn-north-1"
                         ".amazonaws.com.cn", "192.0.2.1:5000", "example.com"):
            with self.subTest(registry):
                with self.assertRaises(ValueError) as caught:
                    promotion.registry_kind(registry)
                if "public.ecr.aws" in registry:
                    self.assertIn("AWS ECR Public is never a target", str(caught.exception))


if __name__ == "__main__":
    unittest.main()

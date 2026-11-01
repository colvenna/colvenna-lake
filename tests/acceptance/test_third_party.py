"""Offline tests of how an environment gets and runs its third-party images (#234): lib/common.sh's
third_party_image, foundation/copy-third-party.sh (a source install's copies), release/install-images.sh
third-party (a release install's) and the release registry setting check-release.sh requires (#248);
and of the query engine's images, tagged by their digest as the third-party copies are (#278):
lib/common.sh's doris_tag, doris/copy-images.sh and release/install-images.sh doris.
The scripts run in a scratch copy of deploy/ (a git repository of its own, as committed_sha wants)
against stub aws and crane commands that keep the registries in a JSON file and log every call."""
import csv
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, os.path.join(REPO, "deploy", "scripts", "release"))
import manifest  # noqa: E402

ACCOUNT = "123456789012"
OWN = f"{ACCOUNT}.dkr.ecr.us-west-2.amazonaws.com"
# A release registry of another account, in another region.
RELEASE = "210987654321.dkr.ecr.us-east-2.amazonaws.com"
REPOSITORY = f"{OWN}/colvenna-lake-t234/third-party"
SETTINGS = {"COLVENNA_ENV": "t234", "COLVENNA_REGION": "us-west-2", "COLVENNA_ACCOUNT": ACCOUNT,
            "COLVENNA_HOSTED_ZONE_ID": "Z0EXAMPLE", "COLVENNA_HOSTED_ZONE_NAME": "example.com"}
# The query engine's repository in the environment, and what lib/common.sh pins: its FE and BE by
# digest, under tags that begin with DORIS_TAG.
QUERY_ENGINE = f"{OWN}/colvenna-lake-t234/query-engine"
with open(os.path.join(REPO, "deploy", "scripts", "lib", "common.sh")) as f:
    COMMON = f.read()


def pinned(key):
    return re.search(rf"^{key}=(\S+)$", COMMON, re.M)[1]


FE, BE, DORIS_TAG = pinned("DORIS_FE_DIGEST"), pinned("DORIS_BE_DIGEST"), pinned("DORIS_TAG")
THIRD_PARTY = manifest.third_party_images()
PLATFORM = [n for n, (scope, _) in THIRD_PARTY.items() if scope == "platform"]
EVERY = list(THIRD_PARTY)


def digest(name):
    return manifest.third_party_digest(THIRD_PARTY[name][1])


def tag(name):
    return manifest.third_party_tag(name, THIRD_PARTY[name][1])


# The stub: aws and crane both, by the name it is called by. Calls go to a log of their own, a line
# each: the two ends of a pipe (aws ecr get-login-password | crane auth login) run at once, and only
# crane copy and crane tag, never piped, change the state. The environment's repositories are
# IMMUTABLE: a tag holding one digest is never given another. An ECR registry, and a repository
# under a prefix the state lists ("listed", e.g. the catalog's in GitHub Container Registry), holds
# what the state gives it; any other upstream, whatever is not gone. A registry the state calls
# private gives nothing to a reader not logged in to it in the run's own credentials (crane's
# $DOCKER_CONFIG/config.json, Helm's $HELM_REGISTRY_CONFIG), where the stub's logins write, as theirs.
STUB = r'''#!/usr/bin/env python3
import json, os, re, sys
path = os.environ["STUB_STATE"]
state = json.load(open(path))
tool, args = os.path.basename(sys.argv[0]), sys.argv[1:]
with open(path + ".calls", "a") as log:
    log.write(" ".join([tool] + args) + "\n")
changed = False
def save():
    if changed:
        json.dump(state, open(path, "w"), indent=1)
def fail(text):
    save()
    print(text, file=sys.stderr)
    sys.exit(1)
def held(ref):
    """The digest a registry holds under the reference, or None; upstream holds what is not gone."""
    if ref in state["gone"]:
        return None
    if "@" in ref:
        repo, digest = ref.rsplit("@", 1)
        repo = re.sub(r":[^/]+$", "", repo)
        listed = re.match(r"^[0-9]{12}\.dkr\.ecr\.", repo) or repo.startswith(tuple(state.get("listed", [])))
        present = (not listed) or any(t.rsplit(":", 1)[0] == repo and d == digest for t, d in state["tags"].items()) \
            or ref in state["digests"]
        return digest if present else None
    return state["tags"].get(ref)
def logins(variable):
    """The registries the run logged in to, in the credentials file the variable names."""
    path = os.environ.get(variable, "")
    path = os.path.join(path, "config.json") if variable == "DOCKER_CONFIG" else path
    return json.load(open(path)).get("auths", {}) if path and os.path.exists(path) else {}
def login(variable, registry):
    sys.stdin.read()
    path = os.environ.get(variable, "")
    if path:
        path = os.path.join(path, "config.json") if variable == "DOCKER_CONFIG" else path
        auths = logins(variable)
        auths[registry] = {"auth": "stub"}
        json.dump({"auths": auths}, open(path, "w"))
def readable(ref, variable):
    """A private registry's reader must have logged in to it."""
    registry = ref.removeprefix("oci://").split("/", 1)[0]
    if registry in state.get("private", []) and registry not in logins(variable):
        fail(f"UNAUTHORIZED: authentication required: {ref}")
def option(name):
    return args[args.index(name) + 1] if name in args else None
def put(ref, digest):
    """The digest under the reference; an environment's tag holding another digest is refused, as an
    IMMUTABLE repository refuses it."""
    global changed
    if ref.startswith(own + "/") and state["tags"].get(ref, digest) != digest:
        fail(f"TAG_INVALID: {ref} already exists and cannot be overwritten: the repository is immutable")
    state["tags"][ref] = digest
    changed = True
own = f'{state["account"]}.dkr.ecr.{os.environ.get("AWS_REGION", "")}.amazonaws.com'
if tool == "aws":
    if args[:2] == ["sts", "get-caller-identity"]:
        print(state["account"] if "Account" in args else f'arn:aws:sts::{state["account"]}:assumed-role/r/i-0')
    elif args[:2] == ["cloudformation", "describe-stacks"]:
        if option("--query") is None:
            # Whether the stack is there (make-manifest.sh asks so of the release registry's).
            if option("--stack-name") not in state.get("stacks", []):
                fail(f'An error occurred (ValidationError): Stack with id {option("--stack-name")} does not exist')
        elif "OutputKey==" in option("--query"):
            key = re.search(r"OutputKey=='(\w+)'", option("--query"))[1]
            print(state["outputs"].get(key, "None"))
        else:
            fail("stub aws: " + " ".join(args))
    elif args[:2] == ["ecr", "get-login-password"]:
        print("token")
    elif args[:2] == ["ecr", "describe-images"]:
        kind, value = option("--image-ids").split("=", 1)
        found = held(f'{own}/{option("--repository-name")}{"@" if kind == "imageDigest" else ":"}{value}')
        if found is None:
            fail("An error occurred (ImageNotFoundException)")
        print(found)
    elif args[:2] == ["ecr", "describe-repositories"]:
        # The release registry's repositories, by name, as --output text gives them (make-manifest.sh's check).
        print("\t".join(state.get("repositories", [])))
    elif args[:2] == ["ecr", "batch-get-image"]:
        # The digests of the repository it holds: their digests or the images (lib/preflight.sh's reads).
        # Read by digest, ECR answers an image for each tag the digest has (once when it has none).
        registry = (f'{option("--registry-id") or state["account"]}.dkr.ecr.'
                    f'{option("--region") or os.environ.get("AWS_REGION", "")}.amazonaws.com')
        repository = f'{registry}/{option("--repository-name")}'
        ids = args[args.index("--image-ids") + 1:]
        ids = [i.split("=", 1)[1] for i in ids[:next((n for n, a in enumerate(ids) if a.startswith("--")), len(ids))]]

        def tags_of(digest):
            return [ref.rsplit(":", 1)[1] for ref, d in state["tags"].items()
                    if ref.rsplit(":", 1)[0] == repository and d == digest] or [None]
        images = [{"imageId": {"imageDigest": d, **({"imageTag": tag} if tag else {})}}
                  for d in ids if held(f"{repository}@{d}") for tag in tags_of(d)]
        if option("--query") == "images[].imageId.imageDigest":
            print("\t".join(i["imageId"]["imageDigest"] for i in images))
        else:
            print(json.dumps({"images": images}))
    else:
        fail("stub aws: " + " ".join(args))
elif tool == "crane":
    if args[:2] == ["auth", "login"]:
        login("DOCKER_CONFIG", args[2])
    elif args[0] == "digest":
        readable(args[1], "DOCKER_CONFIG")
        found = held(args[1])
        if found is None:
            fail(f"MANIFEST_UNKNOWN: {args[1]}")
        print(found)
    elif args[0] == "copy":
        readable(args[1], "DOCKER_CONFIG")
        found = held(args[1])
        if found is None:
            fail(f"MANIFEST_UNKNOWN: {args[1]}")
        put(args[2], state["corrupt"].get(args[2], found))
    elif args[0] == "tag":
        # crane tag <repository>@<digest> <tag>: the same manifest, under another tag of its repository.
        found = held(args[1])
        if found is None:
            fail(f"MANIFEST_UNKNOWN: {args[1]}")
        put(f'{re.sub(r"[:@][^/]+$", "", args[1])}:{args[2]}', found)
    elif args[0] == "config":
        # crane config <reference>: the image's or the chart's config, as the state gives it by digest.
        readable(args[1], "DOCKER_CONFIG")
        found = held(args[1])
        if found is None:
            fail(f"MANIFEST_UNKNOWN: {args[1]}")
        print(json.dumps(state["configs"][found]))
    else:
        fail("stub crane: " + " ".join(args))
elif tool == "helm":
    if args[:2] == ["registry", "login"]:
        login("HELM_REGISTRY_CONFIG", args[2])
    elif args[0] == "pull":
        # helm pull oci://<repository>@<digest> --destination <dir>: the package, <name>@sha256-<hex>.tgz,
        # here the text of its Chart.yaml.
        readable(args[1], "HELM_REGISTRY_CONFIG")
        ref = args[1].removeprefix("oci://")
        found = held(ref)
        if found is None:
            fail(f"Error: {ref}: not found")
        name = ref.rsplit("/", 1)[1].split("@")[0]
        with open(os.path.join(option("--destination"), f"{name}@{found.replace(':', '-')}.tgz"), "w") as f:
            f.write(state["packages"][found])
    elif args[:2] == ["show", "chart"]:
        print(open(args[2]).read(), end="")
    else:
        fail("stub helm: " + " ".join(args))
save()
'''


class Scratch:
    """A copy of deploy/ (and .gitignore) committed in a git repository of its own, stub aws and crane
    first on PATH, and the registries' state."""

    def __init__(self, test):
        self.tmp = tempfile.TemporaryDirectory()
        test.addCleanup(self.tmp.cleanup)
        self.root = os.path.join(self.tmp.name, "tree")
        os.makedirs(os.path.join(self.root, "deploy"))
        shutil.copy(os.path.join(REPO, ".gitignore"), self.root)
        shutil.copy(os.path.join(REPO, "deploy", "env.sh"), os.path.join(self.root, "deploy"))
        for directory in ("scripts", "images"):
            shutil.copytree(os.path.join(REPO, "deploy", directory), os.path.join(self.root, "deploy", directory),
                            ignore=shutil.ignore_patterns("__pycache__"))
        # The foundation's spec, which lib/preflight.sh (doris/copy-images.sh's) names on loading.
        os.makedirs(os.path.join(self.root, "deploy", "aws", "src"))
        shutil.copy(os.path.join(REPO, "deploy", "aws", "src", "foundation-spec.json"),
                    os.path.join(self.root, "deploy", "aws", "src"))
        self.git("init", "-q")
        self.commit("the product")
        self.bin = os.path.join(self.tmp.name, "bin")
        os.makedirs(self.bin)
        for tool in ("aws", "crane", "helm"):
            with open(os.path.join(self.bin, tool), "w") as f:
                f.write(STUB)
            os.chmod(os.path.join(self.bin, tool), 0o700)
        self.state_file = os.path.join(self.tmp.name, "state.json")
        self.state = {"account": ACCOUNT, "outputs": {"ThirdPartyImageRepositoryUri": REPOSITORY},
                      "tags": {}, "digests": [], "gone": [], "corrupt": {}, "configs": {}, "packages": {}}

    def git(self, *args):
        return subprocess.run(["git", "-C", self.root, "-c", "user.name=t", "-c", "user.email=t@example.com", *args],
                              check=True, capture_output=True, text=True).stdout.strip()

    def commit(self, message):
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def run(self, script, *args, **env):
        return self.bash([os.path.join(self.root, script), *args], env)

    def run_bash(self, commands, **env):
        """The commands, as bash -c runs them, in the scratch's environment."""
        return self.bash(["-c", commands], env)

    def bash(self, arguments, env):
        with open(self.state_file, "w") as f:
            json.dump(self.state, f)
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(("COLVENNA_", "AWS_", "DORIS_"))}
        environment.update(SETTINGS, PATH=self.bin + os.pathsep + os.environ["PATH"], STUB_STATE=self.state_file,
                           **env)
        result = subprocess.run(["bash", *arguments], env=environment, capture_output=True, text=True, timeout=120)
        with open(self.state_file) as f:
            self.state = json.load(f)
        return result

    def calls(self):
        """Every call of every run so far, in order."""
        if not os.path.exists(self.state_file + ".calls"):
            return []
        with open(self.state_file + ".calls") as f:
            return f.read().splitlines()

    def copies(self):
        """(source, destination) of each crane copy, in order."""
        return [tuple(call.split()[2:4]) for call in self.calls() if call.startswith("crane copy ")]

    def tags(self):
        """(image, tag) of each crane tag, in order."""
        return [tuple(call.split()[2:4]) for call in self.calls() if call.startswith("crane tag ")]


class ThirdPartyImageTest(unittest.TestCase):
    def test_every_image_is_the_environment_s_copy_by_its_pinned_digest(self):
        scratch = Scratch(self)
        lines = "\n".join(f'echo "{n} $(third_party_image {n})"' for n in EVERY)
        script = os.path.join(scratch.root, "deploy", "print.sh")
        with open(script, "w") as f:
            f.write(f'source "$(dirname "$0")/scripts/lib/common.sh"\n{lines}\n'
                    'echo "POSTGRES $POSTGRES_IMAGE"\necho "CURL $CURL_IMAGE"\necho "MYSQL $MYSQL_CLIENT_IMAGE"\n'
                    'source "$(dirname "$0")/scripts/lib/marquez.sh"\necho "MARQUEZ $MARQUEZ_IMAGE"\n'
                    'third_party_image redis || echo "redis refused"\n')
        out = scratch.run("deploy/print.sh")
        self.assertEqual(out.returncode, 0, out.stderr)
        rows = dict(line.split(" ", 1) for line in out.stdout.splitlines())
        for name in EVERY:
            # The tag manifest.py gives the copies, the same whichever install made them.
            self.assertEqual(rows[name], f"{REPOSITORY}:{tag(name)}@{digest(name)}", name)
            self.assertRegex(tag(name), rf"^{re.escape(name)}-[0-9a-f]{{12}}$")
        self.assertEqual(rows["POSTGRES"], rows["postgres"])
        self.assertEqual(rows["CURL"], rows["curl"])
        self.assertEqual(rows["MYSQL"], rows["mysql"])
        self.assertEqual(rows["MARQUEZ"], rows["marquez"])
        self.assertIn("redis refused", out.stdout)
        self.assertIn("No third-party image redis", out.stderr)

    def test_a_malformed_row_stops_whatever_sources_the_list(self):
        good = "\t".join(("marquez", "platform", THIRD_PARTY["marquez"][1], "Marquez"))
        for row, why in ((good.replace("\tMarquez", ""), "three columns"),
                         (good.replace("platform", "tests"), "an unknown scope"),
                         (good.replace("@" + digest("marquez"), ""), "no digest"),
                         (good.replace("docker.io/", ""), "no registry"),
                         (good.replace("\tplatform", "\t\tplatform"), "an empty column"),
                         (good + "\t", "a trailing tab"),
                         (good.replace("marquez\t", "Marquez\t", 1), "an upper-case name"),
                         (good.replace("marquez\t", "postgres\t", 1), "a name twice")):
            scratch = Scratch(self)
            with open(os.path.join(scratch.root, "deploy", "images", "third-party.tsv"), "a") as f:
                f.write(row + "\n")
            script = os.path.join(scratch.root, "deploy", "print.sh")
            with open(script, "w") as f:
                f.write('source "$(dirname "$0")/scripts/lib/common.sh"\necho "named $POSTGRES_IMAGE"\n')
            out = scratch.run("deploy/print.sh")
            self.assertEqual(out.returncode, 1, why)
            self.assertNotIn("named", out.stdout, why)
            self.assertRegex(out.stderr, r"third-party\.tsv:[0-9]+: not a new name", why)
            # manifest.py, which the copies and check-rules.sh read the list through, refuses it too.
            listed = subprocess.run([sys.executable, os.path.join(scratch.root, "deploy", "scripts", "release",
                                                                  "manifest.py"), "third-party"],
                                    capture_output=True, text=True)
            self.assertEqual(listed.returncode, 1, why)

    def test_the_tests_images_are_the_environment_s_copies(self):
        # tests/acceptance/common.sh and the libraries name their images through the list alone.
        for path in ("tests/acceptance/common.sh", "tests/acceptance/trino/trino.sh", "tests/performance/lib.sh",
                     "deploy/scripts/lib/marquez.sh", "deploy/scripts/lib/polaris-admin.sh",
                     "deploy/scripts/lib/common.sh"):
            with open(os.path.join(REPO, path)) as f:
                for line in f:
                    if re.match(r"^[A-Z_]*IMAGE=", line) and "FLINK_IMAGE" not in line and "DORIS_" not in line \
                            and "FOUNDATION_IMAGE" not in line:
                        self.assertIn("$(third_party_image ", line, f"{path}: {line.strip()}")


# What a case or an install step runs, read from the scripts themselves: each script's references to
# other scripts ("$REPO_DIR/...", "$(dirname "$0")/...", a path variable it assigns), followed until
# none is new, and the third-party images those scripts name.
GLOBAL_DIRS = {"REPO_DIR": "", "REPO": "", "DEPLOY_DIR": "deploy", "DEPLOY": "deploy", "ACCEPTANCE_DIR": "tests/acceptance",
               "FIXTURES_DIR": "tests/fixtures", "TESTS_DIR": "tests", "TOOLS_DIR": "deploy/.tools",
               # run-release-refusals.sh's scratch worktree: a checkout of this tree.
               "scratch": ""}
# Paths inside a pod or a scratch directory, and a function's argument: no script of the tree.
NOT_THE_TREE = {"bin", "work", "1"}
DIRNAME = r'\$\(dirname "\$(?:0|\{BASH_SOURCE\[0\]\})"\)'
# name="<dir><path>", or name="$(cd "<dir><path>" && pwd)".
ASSIGN = re.compile(r'\b(\w+)="(?:\$\(cd ")?(' + DIRNAME + r'|\$\{?\w+\}?)((?:/[\w.-]+)*)"(?: && pwd\)")?')
SCRIPT_REF = re.compile(r'(' + DIRNAME + r'|\$\{?\w+\}?)((?:/[\w.-]+)*/[\w.-]+\.sh)\b')
IMAGE_DEF = re.compile(r'^\s*([A-Z_]+_IMAGE)="\$\(third_party_image ([\w-]+)\)"', re.M)
IMAGE_CALL = re.compile(r"third_party_(?:image|digest|tag) ([a-z][\w-]*)")
# The deployment cases the operator runs, outside run-uat.sh (docs/uat/cases.md).
OPERATOR_RUN = {"install": "deploy/install.sh", "install-platform": "deploy/install.sh",
                "uninstall": "deploy/uninstall.sh", "metadata-restore": "tests/acceptance/deploy/run-metadata-restore.sh"}


class Scripts:
    def __init__(self):
        out = subprocess.run(["git", "-C", REPO, "ls-files"], check=True, capture_output=True, text=True).stdout
        self.tracked = set(out.split())
        self.unresolved = []

    def text(self, path):
        with open(os.path.join(REPO, path)) as f:
            return f.read()

    def base(self, expr, path, local):
        if expr.startswith("$(dirname"):
            return os.path.dirname(path)
        name = expr.strip("${}")
        return local.get(name, GLOBAL_DIRS.get(name))

    def refs(self, path, text=None):
        """The scripts of the tree that this one (or the text given, of it) runs or sources."""
        text = self.text(path) if text is None else text
        local = {}
        for name, expr, rest in ASSIGN.findall(self.text(path)):
            base = self.base(expr, path, local)
            if base is not None:
                local[name] = os.path.normpath(os.path.join(base, rest.lstrip("/"))) if rest else (base or ".")
        found = set()
        for expr, rest in SCRIPT_REF.findall(text):
            base = self.base(expr, path, local)
            target = None if base is None else os.path.normpath(os.path.join(base, rest.lstrip("/")))
            if target in self.tracked:
                found.add(target)
            elif expr.strip("${}") not in NOT_THE_TREE:
                self.unresolved.append(f"{path}: {expr}{rest}")
        return found

    def install_platform(self):
        """What install.sh runs without --fixtures: every step's scripts but the fixture steps'."""
        text = self.text("deploy/install.sh")
        fixture = [step for step, flag in re.findall(r'^\s*"([a-z-]+)\|(yes|no)\|', text, re.M) if flag == "yes"]
        kept = "\n".join(line for line in text.splitlines()
                         if not re.match(r"^\s*(" + "|".join(map(re.escape, fixture)) + r")\)", line))
        return self.refs("deploy/install.sh", kept), fixture

    def closure(self, roots):
        seen, todo = set(), list(roots)
        while todo:
            path = todo.pop()
            if path in seen:
                continue
            seen.add(path)
            # install.sh's own steps are a root of their own (install_platform), by mode.
            if path != "deploy/install.sh":
                todo.extend(self.refs(path))
        return seen

    def images(self, paths):
        """{third-party name: the scripts that run it} of these scripts."""
        names = {}
        for path in self.tracked:
            if path.endswith(".sh"):
                names.update(dict(IMAGE_DEF.findall(self.text(path))))
        used = {}
        for path in paths:
            text = IMAGE_DEF.sub("", self.text(path))
            for variable, name in names.items():
                if re.search(r"\$\{?" + variable + r"\b", text):
                    used.setdefault(name, set()).add(path)
            for name in IMAGE_CALL.findall(text):
                used.setdefault(name, set()).add(path)
        return used


class ScopeTest(unittest.TestCase):
    """Every image a platform-only install runs, or a case that applies to one, is copied on a
    platform-only install: its scope is platform."""

    def platform_roots(self, scripts):
        with open(os.path.join(HERE, "uat-cases.tsv"), newline="") as f:
            rows = list(csv.DictReader(f, delimiter="\t"))
        runner = scripts.text("tests/acceptance/run-uat.sh").replace("\\\n", " ")
        roots = {}
        for row in rows:
            if row["configs"] not in ("platform", "both"):
                continue
            for label in row["labels"].split(","):
                line = re.search(r"^\s*run " + re.escape(label) + r" (.*)$", runner, re.M)
                found = scripts.refs("tests/acceptance/run-uat.sh", line[1]) if line else set()
                if label in OPERATOR_RUN:
                    found = {OPERATOR_RUN[label]}
                self.assertEqual(len(found), 1, f"{row['case']}'s {label}: which script runs it ({found})")
                roots[f"{row['case']} {label}"] = found.pop()
        return roots

    def test_what_a_platform_only_install_and_its_cases_run_is_platform_scoped(self):
        scripts = Scripts()
        roots = self.platform_roots(scripts)
        install, fixture_steps = scripts.install_platform()
        self.assertEqual(set(fixture_steps), {"sources", "strimzi", "source-logins", "readers"})
        paths = scripts.closure(set(roots.values()) | install)
        self.assertEqual(scripts.unresolved, [], "references the scan cannot follow")
        # The scan reaches what it must: the platform's steps and the cases' stand-ins.
        for expected in ("deploy/scripts/foundation/setup-operators.sh", "deploy/scripts/entry/install-lbc.sh",
                         "tests/fixtures/mongo/dev-mongo.sh", "tests/acceptance/catalog/verify-data-tokens.sh"):
            self.assertIn(expected, paths)
        self.assertNotIn("tests/fixtures/kafka/install-strimzi.sh", paths)
        used = scripts.images(paths)
        self.assertTrue({"mongo", "aws-cli", "postgres", "flink-kubernetes-operator"} <= set(used), sorted(used))
        wrong = {name: sorted(where) for name, where in used.items() if THIRD_PARTY[name][0] != "platform"}
        self.assertEqual(wrong, {}, "run on a platform-only install, but copied only with --fixtures")

    def test_every_listed_image_is_run_by_something(self):
        scripts = Scripts()
        used = scripts.images([p for p in scripts.tracked if p.endswith(".sh")])
        self.assertEqual(sorted(set(THIRD_PARTY) - set(used)), [])
        self.assertEqual(sorted(set(used) - set(THIRD_PARTY)), [])


class CopyThirdPartyTest(unittest.TestCase):
    """A source install's third-party step."""

    script = "deploy/scripts/foundation/copy-third-party.sh"

    def test_the_platform_s_from_upstream_by_digest(self):
        scratch = Scratch(self)
        out = scratch.run(self.script)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.copies(), [(THIRD_PARTY[n][1], f"{REPOSITORY}:{tag(n)}") for n in PLATFORM])
        self.assertIn(f"postgres: {REPOSITORY}:{tag('postgres')}@{digest('postgres')} from {THIRD_PARTY['postgres'][1]}",
                      out.stdout)
        self.assertNotIn("debezium-server", out.stdout)

    def test_the_fixtures_too_and_what_is_there_already_is_left(self):
        scratch = Scratch(self)
        scratch.state["tags"][f"{REPOSITORY}:{tag('postgres')}"] = digest("postgres")
        out = scratch.run(self.script, "--fixtures")
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual([d for _, d in scratch.copies()], [f"{REPOSITORY}:{tag(n)}" for n in EVERY if n != "postgres"])
        self.assertIn(f"postgres: {REPOSITORY}:{tag('postgres')} (already there)", out.stdout)
        self.assertEqual(scratch.state["tags"][f"{REPOSITORY}:{tag('debezium-server')}"], digest("debezium-server"))
        self.assertEqual(scratch.run(self.script, "--all").returncode, 1)

    def test_from_the_release_registry_when_it_holds_the_digest_an_upstream_dropped(self):
        scratch = Scratch(self)
        scratch.state["gone"].append(THIRD_PARTY["debezium-server"][1])
        scratch.state["digests"].append(f"{RELEASE}/colvenna-lake-release/third-party@{digest('debezium-server')}")
        out = scratch.run(self.script, "--fixtures", COLVENNA_RELEASE_REGISTRY=RELEASE)
        self.assertEqual(out.returncode, 0, out.stderr)
        sources = dict((d, s) for s, d in scratch.copies())
        self.assertEqual(sources[f"{REPOSITORY}:{tag('debezium-server')}"],
                         f"{RELEASE}/colvenna-lake-release/third-party@{digest('debezium-server')}")
        self.assertEqual(sources[f"{REPOSITORY}:{tag('mongo')}"], THIRD_PARTY["mongo"][1])
        # The release registry's region's token.
        self.assertIn("aws ecr get-login-password --region us-east-2", scratch.calls())
        # Without it, the dropped digest stops the step.
        scratch = Scratch(self)
        scratch.state["gone"].append(THIRD_PARTY["debezium-server"][1])
        out = scratch.run(self.script, "--fixtures")
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("MANIFEST_UNKNOWN", out.stderr)

    def test_a_copy_of_another_digest_or_a_tag_holding_one_stops_it(self):
        scratch = Scratch(self)
        scratch.state["corrupt"][f"{REPOSITORY}:{tag('marquez')}"] = "sha256:" + "9" * 64
        out = scratch.run(self.script)
        self.assertEqual(out.returncode, 1)
        self.assertIn(f"marquez: the copy from {THIRD_PARTY['marquez'][1]} has digest sha256:{'9' * 64}", out.stderr)
        scratch = Scratch(self)
        scratch.state["tags"][f"{REPOSITORY}:{tag('grafana')}"] = "sha256:" + "8" * 64
        out = scratch.run(self.script)
        self.assertEqual(out.returncode, 1)
        self.assertIn(f"grafana: colvenna-lake-t234/third-party:{tag('grafana')} is sha256:{'8' * 64}", out.stderr)

    def test_without_the_foundation_s_repository_nothing_is_copied(self):
        scratch = Scratch(self)
        scratch.state["outputs"] = {}
        out = scratch.run(self.script)
        self.assertEqual(out.returncode, 1)
        self.assertIn("no repository", out.stderr)
        self.assertEqual(scratch.copies(), [])

    def test_a_bad_release_registry_setting_is_refused(self):
        scratch = Scratch(self)
        out = scratch.run(self.script, COLVENNA_RELEASE_REGISTRY="registry.example.com")
        self.assertEqual(out.returncode, 1)
        self.assertIn("COLVENNA_RELEASE_REGISTRY", out.stderr)
        self.assertEqual(scratch.copies(), [])


class InstallImages(unittest.TestCase):
    """A release install's copies, release/install-images.sh: from COLVENNA_RELEASE_REGISTRY, by the
    manifest's digests."""

    script = "deploy/scripts/release/install-images.sh"
    candidate = "v0.0.0-dev.9"

    def release(self, scratch):
        """The candidate's manifest committed on top of the product, its third-party images in the
        release registry; its query engine's FE and BE are the digests lib/common.sh pins, as
        check-release.sh requires."""
        sha = scratch.git("rev-parse", "HEAD")
        digests = {**{key: "sha256:" + "0" * 64 for key in {**manifest.PRODUCT_IMAGES, **manifest.FIXTURE_IMAGES}},
                   **{name: digest(name) for name in EVERY}, "query-engine-fe": FE, "query-engine-be": BE}
        m = manifest.build({
            "candidate": self.candidate, "built_from": sha,
            "generated": {"at": "2026-10-24T00:00:00Z", "environment": "uat", "region": "us-west-2"},
            "digests": digests, "cli": {"digest": "sha256:" + "0" * 64,
                                        "binaries": {b: "1" * 64 for b in manifest.CLI_BINARIES}},
            "charts": {c: {"path": f"deploy/helm/{c}", "version": "0.1.0", "appVersion": "0.1.0", "tree": "c" * 40}
                       for c in manifest.CHARTS},
            "catalog": manifest.catalog_pin(),
            "installer": {"install": {"path": "deploy/install.sh", "blob": "d" * 40},
                          "uninstall": {"path": "deploy/uninstall.sh", "blob": "e" * 40}},
            "uat_cases": {"path": "docs/uat/cases.md", "commit": "f" * 40, "blob": "a" * 40},
            "required_cases": ["UAT-DEP-01"], "regions": ["us-west-2", "us-east-2"]})
        os.makedirs(os.path.join(scratch.root, "release", self.candidate))
        with open(os.path.join(scratch.root, "release", self.candidate, "manifest.json"), "w") as f:
            json.dump(m, f, indent=2)
        scratch.commit("release")
        scratch.state["digests"] += [f"{RELEASE}/colvenna-lake-release/third-party@{digest(n)}" for n in EVERY]


class InstallImagesThirdPartyTest(InstallImages):
    """A release install's third-party step."""

    def test_the_platform_s_and_with_fixtures_the_fixtures_from_the_release_registry(self):
        scratch = Scratch(self)
        self.release(scratch)
        scratch.state["tags"][f"{REPOSITORY}:{tag('curl')}"] = digest("curl")
        out = scratch.run(self.script, self.candidate, "third-party", COLVENNA_RELEASE_REGISTRY=RELEASE)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.copies(), [(f"{RELEASE}/colvenna-lake-release/third-party@{digest(n)}",
                                             f"{REPOSITORY}:{tag(n)}") for n in PLATFORM if n != "curl"])
        self.assertIn(f"polaris {REPOSITORY}:{tag('polaris')}@{digest('polaris')}", out.stdout)
        out = scratch.run(self.script, self.candidate, "third-party", "--fixtures", COLVENNA_RELEASE_REGISTRY=RELEASE)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.state["tags"][f"{REPOSITORY}:{tag('debezium-server')}"], digest("debezium-server"))
        self.assertIn("aws ecr get-login-password --region us-east-2", scratch.calls())

    def test_no_release_registry_no_copy(self):
        scratch = Scratch(self)
        self.release(scratch)
        out = scratch.run(self.script, self.candidate, "third-party")
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("COLVENNA_RELEASE_REGISTRY", out.stderr)
        self.assertEqual(scratch.copies(), [])

    def test_a_registry_without_the_candidate_s_image_stops_it(self):
        scratch = Scratch(self)
        self.release(scratch)
        scratch.state["digests"].remove(f"{RELEASE}/colvenna-lake-release/third-party@{digest('grafana')}")
        out = scratch.run(self.script, self.candidate, "third-party", COLVENNA_RELEASE_REGISTRY=RELEASE)
        self.assertNotEqual(out.returncode, 0)
        self.assertIn("MANIFEST_UNKNOWN", out.stderr)


# What an environment installed before the query engine's digests were pinned anew (#275) holds: the
# digests of that time under the tags of that time, <DORIS_TAG>-fe and -be, which cannot be rewritten.
OLD_FE, OLD_BE = "sha256:" + "4" * 64, "sha256:" + "9" * 64
OLD_TAGS = {f"{QUERY_ENGINE}:{DORIS_TAG}-fe": OLD_FE, f"{QUERY_ENGINE}:{DORIS_TAG}-be": OLD_BE}
# What doris/build-images.sh pushed of the digests pinned now: rebuilds, tagged build-<sha>-fe and -be.
BUILDS = {f"{QUERY_ENGINE}:build-{'b' * 40}-fe": FE, f"{QUERY_ENGINE}:build-{'b' * 40}-be": BE}
PINNED = (("fe", FE), ("be", BE))


def engine_tag(component, pinned_digest):
    """The query engine's tag in an environment's repository: DORIS_TAG, the component and the digest's
    first 12 hex digits, as a third-party copy's (manifest.third_party_tag)."""
    return f"{DORIS_TAG}-{component}-{pinned_digest[7:19]}"


# The digests pinned now, each under its own tag.
TAGGED = {f"{QUERY_ENGINE}:{engine_tag(c, d)}": d for c, d in PINNED}


class QueryEngineTagTest(InstallImages):
    """The query engine's FE and BE in an environment's repository, each under a tag carrying its
    digest (lib/common.sh's doris_tag, #278), from a source install (doris/copy-images.sh) and a
    release install (install-images.sh doris) alike: a digest pinned anew gets a tag of its own beside
    the old one's."""

    copy = "deploy/scripts/doris/copy-images.sh"

    def scratch(self, held):
        """The candidate's manifest committed, its query engine in the release registry, the environment's
        repository holding <held>."""
        scratch = Scratch(self)
        self.release(scratch)
        scratch.state["outputs"]["QueryEngineImageRepositoryUri"] = QUERY_ENGINE
        scratch.state["digests"] += [f"{RELEASE}/colvenna-lake-release/query-engine@{d}" for _, d in PINNED]
        scratch.state["tags"].update(held)
        return scratch

    def install(self, scratch):
        return scratch.run(self.script, self.candidate, "doris", COLVENNA_RELEASE_REGISTRY=RELEASE)

    def test_the_tag_carries_the_digest(self):
        scratch = Scratch(self)
        script = os.path.join(scratch.root, "deploy", "print.sh")
        with open(script, "w") as f:
            f.write('source "$(dirname "$0")/scripts/lib/common.sh"\n'
                    'echo "fe $(doris_tag fe "$DORIS_FE_DIGEST")"\necho "be $(doris_tag be "$DORIS_BE_DIGEST")"\n'
                    'doris_tag ui "$DORIS_FE_DIGEST" || echo "ui refused"\n'
                    'doris_tag fe sha256:0123456789ab || echo "short refused"\n')
        out = scratch.run("deploy/print.sh")
        self.assertEqual(out.returncode, 0, out.stderr)
        rows = dict(line.split(" ", 1) for line in out.stdout.splitlines())
        for component, d in PINNED:
            self.assertEqual(rows[component], engine_tag(component, d))
            # As long a digest prefix as a third-party copy's tag.
            self.assertEqual(len(rows[component].rsplit("-", 1)[1]), len(tag("postgres").rsplit("-", 1)[1]))
        self.assertEqual((rows["ui"], rows["short"]), ("refused", "refused"))

    def test_a_source_install_tags_the_pinned_digests_it_holds_under_build_tags_only(self):
        # The digests pinned now are in the repository already, rebuilds under build-* tags: nothing to
        # copy, but each gets its pinned tag, by digest (the same manifest, no new digest).
        scratch = self.scratch({**OLD_TAGS, **BUILDS})
        out = scratch.run(self.copy)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.copies(), [])
        self.assertEqual(scratch.tags(), [(f"{QUERY_ENGINE}@{d}", engine_tag(c, d)) for c, d in PINNED])
        self.assertEqual(scratch.state["tags"], {**OLD_TAGS, **BUILDS, **TAGGED})
        for component, d in PINNED:
            self.assertIn(f"{component}: {QUERY_ENGINE}:{engine_tag(component, d)}@{d}", out.stdout)
        # Again: each under its tag already, so nothing is tagged and no registry is logged in to.
        before = len(scratch.calls())
        out = scratch.run(self.copy)
        self.assertEqual(out.returncode, 0, out.stderr)
        for component, d in PINNED:
            self.assertIn(f"{component}: {QUERY_ENGINE}:{engine_tag(component, d)}@{d} (already there)", out.stdout)
        self.assertEqual([c for c in scratch.calls()[before:] if c.startswith(("crane", "aws ecr get-login"))], [])

    def test_a_release_install_puts_the_new_digest_beside_the_old_one(self):
        scratch = self.scratch(OLD_TAGS)
        out = self.install(scratch)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertEqual(scratch.copies(), [(f"{RELEASE}/colvenna-lake-release/query-engine@{d}",
                                             f"{QUERY_ENGINE}:{engine_tag(c, d)}") for c, d in PINNED])
        self.assertEqual(scratch.state["tags"], {**OLD_TAGS, **TAGGED})
        self.assertIn(f"fe: {QUERY_ENGINE}@{FE}\nbe: {QUERY_ENGINE}@{BE}\n", out.stdout)

    def test_the_upgrade_across_a_re_pinned_digest_goes_through(self):
        # #278's upgrade: an environment installed at the old digests, upgraded from source after the
        # new ones were built there (build-*), then installed from a candidate pinning them.
        scratch = self.scratch({**OLD_TAGS, **BUILDS})
        self.assertEqual(scratch.run(self.copy).returncode, 0)
        out = self.install(scratch)
        self.assertEqual(out.returncode, 0, out.stderr)
        self.assertNotIn(f"query-engine-fe: colvenna-lake-t234/query-engine:{DORIS_TAG}-fe is already {OLD_FE},"
                         f" not the release's {FE}", out.stderr)
        self.assertNotIn("is already", out.stderr)
        # The source install's tags are the release install's: nothing to copy.
        self.assertEqual(scratch.copies(), [])

    def test_a_repeat_release_install_is_a_no_op(self):
        scratch = self.scratch(OLD_TAGS)
        first = self.install(scratch)
        self.assertEqual(first.returncode, 0, first.stderr)
        held, before = dict(scratch.state["tags"]), len(scratch.calls())
        again = self.install(scratch)
        self.assertEqual(again.returncode, 0, again.stderr)
        self.assertEqual(again.stdout, first.stdout)
        self.assertEqual(scratch.state["tags"], held)
        self.assertEqual([c for c in scratch.calls()[before:] if c.startswith(("crane copy", "crane tag"))], [])


class ReleaseRegistryPreflightTest(unittest.TestCase):
    """install.sh's preflight refuses a malformed COLVENNA_RELEASE_REGISTRY before anything is made."""

    def assert_registry(self, value):
        preflight = os.path.join(REPO, "deploy", "scripts", "lib", "preflight.sh")
        environment = {key: v for key, v in os.environ.items() if not key.startswith("COLVENNA_")}
        if value is not None:
            environment["COLVENNA_RELEASE_REGISTRY"] = value
        return subprocess.run(["bash", "-c", f'source "{preflight}" && assert_release_registry'], env=environment,
                              capture_output=True, text=True, timeout=60)

    def test_none_a_good_one_or_a_bad_one(self):
        self.assertEqual(self.assert_registry(None).returncode, 0)
        self.assertEqual(self.assert_registry(RELEASE).returncode, 0)
        for bad in ("example.com", "123.dkr.ecr.us-west-2.amazonaws.com", f"{RELEASE}/colvenna-lake-release"):
            out = self.assert_registry(bad)
            self.assertEqual(out.returncode, 1, bad)
            self.assertIn("COLVENNA_RELEASE_REGISTRY", out.stderr)

    def test_install_sh_checks_it_with_the_settings_and_says_it_to_resume(self):
        with open(os.path.join(REPO, "deploy", "install.sh")) as f:
            text = f.read()
        self.assertLess(text.index("assert_release_registry"), text.index("record_settings\n"))
        self.assertIn("${COLVENNA_RELEASE_REGISTRY:+COLVENNA_RELEASE_REGISTRY=$COLVENNA_RELEASE_REGISTRY }", text)


class CheckReleaseRegistryTest(unittest.TestCase):
    """check-release.sh wants the release registry from its setting before it reads anything."""

    def run_check(self, **env):
        environment = {key: value for key, value in os.environ.items() if not key.startswith(("COLVENNA_", "AWS_"))}
        environment.update(env)
        return subprocess.run(["bash", os.path.join(REPO, "deploy", "scripts", "release", "check-release.sh"),
                               "v1.0.0-rc.1"], env=environment, capture_output=True, text=True, timeout=60)

    def test_no_or_a_bad_setting(self):
        out = self.run_check()
        self.assertEqual(out.returncode, 1)
        self.assertIn("COLVENNA_RELEASE_REGISTRY=<account>.dkr.ecr.<region>.amazonaws.com", out.stderr)
        for bad in ("example.com", "123456789012.dkr.ecr.cn-north-1.amazonaws.com"):
            out = self.run_check(COLVENNA_RELEASE_REGISTRY=bad)
            self.assertEqual(out.returncode, 1, bad)
            self.assertIn(f"COLVENNA_RELEASE_REGISTRY={bad!r}", out.stderr)


if __name__ == "__main__":
    unittest.main()

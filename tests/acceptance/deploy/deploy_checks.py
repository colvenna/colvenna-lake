#!/usr/bin/env python3
"""Judges the deployment cases (UAT-DEP-*) from install evidence and inventories
(tests/acceptance/deploy/inventory.sh). Each check prints one line, <check> TAB PASS|FAIL TAB
<detail>; the exit status is 1 when any check fails.

  deploy_checks.py steps <list-file> [--platform] <install-evidence>...
      every step of `install.sh --list` (the list file) done, the fixtures' skipped when
      --platform; verify passed; the install finished. Several evidence directories are one
      install interrupted and resumed with --from: a later directory's row of a step wins
  deploy_checks.py fresh <inventory> <install-evidence>...
      every stack of the environment was created after the first of these installs began
  deploy_checks.py platform <inventory>
      nothing of the fixtures: no test source stack nor its secrets, no Strimzi (release, namespace,
      CRDs, Kafka workloads), no reader image nor its record, no source login Secret
  deploy_checks.py images <inventory>
      nothing runs from upstream: every container of every workload and every pod not finished
      runs an image of the environment's own repositories, colvenna-lake-<env>/..., but AWS's own in
      its add-ons' and agents' namespaces (deploy/scripts/release/manifest.py running)
  deploy_checks.py repeat <before> <after> [--same-product <installed> <this>]
      a second install at the same commit made and replaced nothing; with --same-product, the
      environment ran <installed>, whose tree differs from <this> commit only under release/ (a
      release tag and the commit its images were built from): a component's commit annotation may
      be either of the two, before and after
  deploy_checks.py upgrade <before> <after> <commit>
      an install at a new commit kept the stacks' data and the volumes, and runs the commit
  deploy_checks.py others <before> <after> <env>
      the account after removing <env>: nothing of it, every other environment as it was
"""
import json
import os
import re
import sys
from datetime import datetime, timezone

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "deploy", "scripts",
                                "release"))
import manifest  # noqa: E402  (manifest.py's running: what is not the environment's own)

# The steps only --fixtures installs (deploy/install.sh), and the stacks they make.
FIXTURE_STEPS = ("sources", "strimzi", "source-logins", "readers")
# What an environment's data lives in: kept by an upgrade, never replaced.
STATEFUL_TYPES = ("AWS::RDS::DBInstance", "AWS::S3::Bucket", "AWS::Cognito::UserPool",
                  "AWS::ECR::Repository", "AWS::SecretsManager::Secret", "AWS::EC2::VPC",
                  "Custom::AWSCDK-EKS-Cluster", "AWS::EKS::Cluster")
# The Secrets tests/fixtures/sources/sources.sh writes into a managed namespace for the flows.
SOURCE_LOGINS = ("mysql-cdc", "postgres-cdc", "mysql-inspect", "mysql-beat")
# What judge_platform reads of an inventory: all of it written, or the judgement is not made.
PLATFORM_INVENTORY = ("stacks.json", "secrets.json", "state.txt", "images.tsv", "helm.json",
                      "workloads.json", "namespaces.txt", "crds.txt", "secret-names.txt")
# The namespaces whose pods carry the commit they were installed at.
COMMIT_ANNOTATIONS = ("colvenna-lake/source-sha", "colvenna-lake.io/source-sha")

results = []


def check(name, ok, detail):
    results.append((name, "PASS" if ok else "FAIL", " ".join(str(detail).split())[:400]))


def load(directory, name, default=None):
    path = os.path.join(directory, name)
    if not os.path.exists(path):
        return default
    with open(path) as f:
        return json.load(f)


def when(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone.utc)


# ------------------------------------------------------------------------------- install steps
def list_steps(list_file):
    """[(step, fixture-only)] from `install.sh --list`."""
    steps = []
    for line in open(list_file):
        if line.strip():
            steps.append((line.split()[0], line.rstrip().endswith("(--fixtures)")))
    return steps


def outcomes(evidences):
    """{step: (outcome, seconds)} from their steps.tsv, in order; a later row of a step wins."""
    rows = {}
    for evidence in evidences:
        path = os.path.join(evidence, "steps.tsv")
        for line in open(path) if os.path.exists(path) else []:
            parts = line.rstrip("\n").split("\t")
            if len(parts) >= 3:
                rows[parts[0]] = (parts[1], int(parts[2]) if parts[2].isdigit() else 0)
    return rows


def judge_steps(list_file, platform, evidences):
    steps, done = list_steps(list_file), outcomes(evidences)
    evidence = evidences[-1]
    wrong = []
    for step, fixture in steps:
        want = "skipped (no --fixtures)" if platform and fixture else "ok"
        got = done.get(step, ("missing", 0))[0]
        if got != want:
            wrong.append(f"{step}: {got}")
    seconds = sum(s for _, s in done.values())
    check("every-step-done", not wrong,
          "; ".join(wrong) if wrong else f"{len(steps)} steps as expected, {seconds} s in all")
    if platform:
        skipped = [s for s, fixture in steps if fixture]
        check("fixture-steps-skipped",
              all(done.get(s, ("",))[0] == "skipped (no --fixtures)" for s in skipped),
              ", ".join(f"{s}: {done.get(s, ('missing',))[0]}" for s in skipped))
    logs = [os.path.join(e, "verify.log") for e in evidences if os.path.exists(os.path.join(e, "verify.log"))]
    text = open(logs[-1]).read() if logs else ""
    access = re.findall(r"^(.+) expected=(\S+) actual=(\S+)$", text, re.M)
    wrong_access = [f"{c}: {a} not {e}" for c, e, a in access if e != a]
    health_failed = re.findall(r"^(\S+) FAIL ", text, re.M)
    entries = dict(re.findall(r"^https://(console|bi)\.\S+/: 302 to (\S+) sign-in$", text, re.M))
    check("verify-passed",
          done.get("verify", ("",))[0] == "ok" and not health_failed and len(access) == 14
          and not wrong_access and set(entries) == {"console", "bi"},
          f"verify {done.get('verify', ('missing',))[0]}; health failures: {', '.join(health_failed) or 'none'}; "
          f"{len(access)} access checks, wrong: {'; '.join(wrong_access) or 'none'}; "
          f"refusing the unsigned: {', '.join(f'{h} -> {u}' for h, u in sorted(entries.items())) or 'none'}")
    installed = open(os.path.join(evidence, "install.log")).read().strip().splitlines()
    check("install-finished", bool(installed) and installed[-1].startswith("Installed "),
          installed[-1] if installed else "no install.log")


def install_started(evidence):
    """The first timestamp install.log records: when the first step began."""
    for line in open(os.path.join(evidence, "install.log")):
        m = re.match(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ) ", line)
        if m:
            return when(m.group(1))
    return None


def judge_fresh(inventory, evidences):
    began = install_started(evidences[0])
    stacks = load(inventory, "stacks.json", [])
    older = [s["name"] for s in stacks if began is None or when(s["created"]) < began]
    check("installed-from-nothing", bool(stacks) and not older,
          f"{len(stacks)} stacks, all created after {began:%Y-%m-%dT%H:%M:%SZ}" if stacks and not older
          else f"created before the install began: {', '.join(older) or 'no stacks'}")


def lines(inventory, name):
    with open(os.path.join(inventory, name)) as f:
        return [line.strip() for line in f if line.strip()]


def judge_platform(inventory):
    absent = [name for name in PLATFORM_INVENTORY if not os.path.exists(os.path.join(inventory, name))]
    if absent:
        # A listing that was never taken proves nothing absent.
        check("no-fixture-resources", False, f"the inventory lacks {', '.join(absent)}")
        return
    test_stacks = ("TestMysql", "TestPostgres")
    stacks = [s["name"] for s in load(inventory, "stacks.json", [])]
    sources = [s for s in stacks if s.endswith(test_stacks)]
    sources += [f"secret {name}" for name, stack in load(inventory, "secrets.json", [])
                if (stack or "").endswith(test_stacks)]
    sources += [f"Secret {s}" for s in lines(inventory, "secret-names.txt") if s.rsplit("/", 1)[-1] in SOURCE_LOGINS]
    sources += [f"namespace {n}" for n in lines(inventory, "namespaces.txt") if n == "colvenna-lake-kafka"]
    sources += [f"CRD {c}" for c in lines(inventory, "crds.txt") if c.endswith(".kafka.strimzi.io")]
    sources += [f"record {f}" for f in lines(inventory, "state.txt") if re.fullmatch(r"readers-.*\.json", f)]
    releases = [r["name"] for r in load(inventory, "helm.json", [])]
    workloads = [w["metadata"]["namespace"] + "/" + w["metadata"]["name"]
                 for w in (load(inventory, "workloads.json", {}) or {}).get("items", [])
                 if w["metadata"]["namespace"] == "colvenna-lake-kafka"]
    readers = 0
    path = os.path.join(inventory, "images.tsv")
    for line in open(path) if os.path.exists(path) else []:
        repository, _, count = line.rstrip("\n").partition("\t")
        if repository.endswith("/readers") or "reader" in repository.rsplit("/", 1)[-1]:
            readers += int(count or 0)
    found = sources + [f"release {r}" for r in releases if "strimzi" in r] + workloads
    if readers:
        found.append(f"{readers} reader images")
    check("no-fixture-resources", not found,
          "no test source stack nor its secrets, no source login, no Strimzi, no Kafka, no reader image nor record"
          if not found else "; ".join(found))


def judge_images(inventory):
    """Every image that runs is the environment's own, from its repositories colvenna-lake-<env>/...
    (its copies of the third-party ones too), or AWS's own in its add-ons' and agents' namespaces,
    which the detail counts. The detail names repositories, never a registry: an ECR registry's
    host carries its account id, which a verdict does not."""
    meta = load(inventory, "meta.json")
    absent = [name for name in ("meta.json", "workloads.json", "pods.json")
              if not os.path.exists(os.path.join(inventory, name))]
    if absent:
        # A listing that was never taken proves nothing absent.
        check("no-upstream-images", False, f"the inventory lacks {', '.join(absent)}")
        return
    prefix = f"colvenna-lake-{meta['env']}"
    own = f"{meta['settings']['COLVENNA_ACCOUNT']}.dkr.ecr.{meta['region']}.amazonaws.com/{prefix}"
    items = load(inventory, "workloads.json")["items"] + load(inventory, "pods.json")["items"]
    rows = manifest.running({"items": items}, own)

    def path(image):
        """The image without an ECR registry's host: ecr:<region>/<repository>..."""
        found = re.match(r"^[0-9]{12}\.dkr\.ecr\.([a-z0-9-]+)\.amazonaws\.com/(.*)$", image)
        return f"ecr:{found[1]}/{found[2]}" if found else image
    upstream = [f"{where} {container}: {path(image)}" for where, container, image, kind in rows if kind == "upstream"]
    aws = {where for where, _, _, kind in rows if kind == "aws"}
    check("no-upstream-images", bool(items) and not upstream,
          f"{len(items)} workloads and pods, every image from {prefix}/... but AWS's own in {len(aws)} of them"
          if items and not upstream else "; ".join(upstream[:12]) or "no workload or pod listed")


# ----------------------------------------------------------------------------- inventories
def resources(inventory):
    """{stack: {logical id: (type, physical id)}}."""
    out = {}
    for stack in load(inventory, "stacks.json", []):
        rows = load(inventory, f"resources-{stack['name']}.json", [])
        out[stack["name"]] = {r["logical"]: (r["type"], r.get("physical")) for r in rows}
    return out


def nodes(inventory):
    return sorted(n["spec"].get("providerID", "").rsplit("/", 1)[-1]
                  for n in (load(inventory, "nodes.json", {}) or {}).get("items", []))


def releases(inventory):
    return {(r["namespace"], r["name"]): (r["chart"], r.get("app_version"), r["status"])
            for r in load(inventory, "helm.json", []) or []}


def workloads(inventory):
    """{namespace/kind/name: (images, commit annotation)}."""
    out = {}
    for w in (load(inventory, "workloads.json", {}) or {}).get("items", []):
        spec = w["spec"]["template"]
        images = tuple(sorted(c["image"] for c in spec["spec"].get("containers", [])))
        notes = (spec.get("metadata") or {}).get("annotations") or {}
        commit = next((notes[k] for k in COMMIT_ANNOTATIONS if k in notes), None)
        out[f"{w['metadata']['namespace']}/{w['kind']}/{w['metadata']['name']}"] = (images, commit)
    return out


def volumes(inventory):
    return sorted(v["metadata"]["name"] for v in (load(inventory, "volumes.json", {}) or {}).get("items", []))


def images(inventory):
    path = os.path.join(inventory, "images.tsv")
    counts = {}
    for line in open(path) if os.path.exists(path) else []:
        repository, _, count = line.rstrip("\n").partition("\t")
        counts[repository] = int(count or 0)
    return counts


def differences(before, after):
    """Keys added, removed or changed between two dicts, briefly."""
    out = [f"+{k}" for k in sorted(set(after) - set(before))]
    out += [f"-{k}" for k in sorted(set(before) - set(after))]
    out += [f"~{k}" for k in sorted(set(before) & set(after)) if before[k] != after[k]]
    return out


def judge_repeat(before, after, same_product=None):
    rb, ra = resources(before), resources(after)
    diff = differences({s: True for s in rb}, {s: True for s in ra})
    for stack in set(rb) & set(ra):
        diff += [f"{stack}:{d}" for d in differences(rb[stack], ra[stack])]
    check("nothing-made-or-replaced", bool(ra) and not diff,
          f"{len(ra)} stacks, {sum(len(r) for r in ra.values())} resources, the same physical ids"
          if bool(ra) and not diff else "; ".join(diff[:12]) or "no stacks")
    nb, na = nodes(before), nodes(after)
    check("same-nodes", nb == na and bool(na), f"{len(na)} nodes, the same instances" if nb == na else f"{nb} -> {na}")
    hb, ha = releases(before), releases(after)
    changed = differences(hb, ha)
    undeployed = [f"{n}/{r}: {v[2]}" for (n, r), v in ha.items() if v[2] != "deployed"]
    check("releases-converged", not changed and not undeployed and bool(ha),
          f"{len(ha)} releases deployed, the same charts" if not changed and not undeployed
          else "; ".join(["/".join(c) if isinstance(c, tuple) else str(c) for c in changed] + undeployed))
    wb, wa = workloads(before), workloads(after)
    if same_product:
        installed, this = same_product
        wb, wa = ({k: (images, this if commit == installed else commit) for k, (images, commit) in w.items()}
                  for w in (wb, wa))
    check("same-workloads", wb == wa and bool(wa),
          f"{len(wa)} workloads, the same images and commits" if wb == wa else "; ".join(differences(wb, wa)[:12]))
    vb, va = volumes(before), volumes(after)
    check("same-volumes", vb == va, f"{len(va)} volumes" if vb == va else f"{vb} -> {va}")
    ib, ia = images(before), images(after)
    check("no-image-built", ib == ia and bool(ia),
          f"{sum(ia.values())} images in {len(ia)} repositories, none added" if ib == ia else "; ".join(
              f"{r}: {ib.get(r)} -> {ia.get(r)}" for r in sorted(set(ib) | set(ia)) if ib.get(r) != ia.get(r)))


def judge_upgrade(before, after, commit):
    rb, ra = resources(before), resources(after)
    check("same-stacks", sorted(rb) == sorted(ra) and bool(ra),
          f"{len(ra)} stacks updated in place" if sorted(rb) == sorted(ra) else f"{sorted(rb)} -> {sorted(ra)}")
    replaced = []
    kept = 0
    for stack in set(rb) & set(ra):
        for logical, (kind, physical) in rb[stack].items():
            if kind in STATEFUL_TYPES:
                now = ra[stack].get(logical)
                if now is None or now[1] != physical:
                    replaced.append(f"{stack}:{logical} {physical} -> {now[1] if now else 'gone'}")
                else:
                    kept += 1
    check("data-kept", not replaced and kept > 0,
          f"{kept} stateful resources (database, buckets, user pool, repositories, secrets, VPC, cluster) kept"
          if not replaced else "; ".join(replaced[:8]))
    vb, va = volumes(before), volumes(after)
    lost = [v for v in vb if v not in va]
    check("volumes-kept", not lost, f"{len(vb)} volumes kept" if not lost else f"gone: {', '.join(lost)}")
    wa, wb = workloads(after), workloads(before)
    marked = {k: v[1] for k, v in wa.items() if v[1]}
    stale = [f"{k} at {c}" for k, c in marked.items() if c != commit]
    check("components-at-the-commit", bool(marked) and not stale,
          f"{len(marked)} components annotated with {commit[:12]}" if marked and not stale else "; ".join(stale[:8]) or "none annotated")
    changed = [k for k in marked if k in wb and wb[k][0] != wa[k][0]]
    check("images-changed", bool(changed),
          f"{len(changed)} components run new images: {', '.join(sorted(changed)[:6])}" if changed else "no component's image changed")


# The environment's stacks, as deploy/env.sh names them: ColvennaLake<Env><Component>.
COMPONENTS = ("Foundation", "Catalog", "Operator", "OpenLayer", "Entry", "TestMysql", "TestPostgres")


def ownership(env, meta):
    """mine(kind, name): whether an account item of that kind is the environment's, by the names its
    stacks give (CloudFormation names generated resources <stack>-..., lower-cased where the
    service wants), its own name prefixes, its cluster and its OIDC issuer."""
    stacks = [f"ColvennaLake{env[:1].upper()}{env[1:]}{c}" for c in COMPONENTS]
    lower = [s.lower() for s in stacks]
    cluster, issuer = meta.get("cluster"), meta.get("issuer")
    # Its DNS records: at or under its entry domain (deploy/env.sh's COLVENNA_ENTRY_DOMAIN), as uninstall.sh
    # deletes them; an inventory made before the entry domain was recorded: <app>.colvenna-<env>.
    domain = (meta.get("settings", {}).get("COLVENNA_ENTRY_DOMAIN") or "").rstrip(".").lower()

    def of_stack(name, names):
        return any(name == s or name.startswith(s + "-") for s in names)

    # CloudFormation cuts the stack's name short in the names it generates (to 25 characters or more as
    # seen: IAM roles ColvennaLakePlatFoundatio-..., Lambda functions ColvennaLakeUate2Foundati-..., RDS
    # instances colvennalakeuate2foundatio-... and their log groups), so a generated name is also the
    # stack's when its first part is a cut of the stack's name at least 23 characters long: two
    # environments' stack names (ColvennaLake<Env><Part>, env names at most 8 characters) never share
    # more than their first 22, lowercased. deploy/uninstall.sh's starting goes by the same rule.
    def of_cut_stack(name, names):
        head = name.split("-", 1)[0]
        return len(head) >= 23 and any(len(s) > len(head) and s.startswith(head) for s in names)

    def of_stack_or_cut(name, names):
        return of_stack(name, names) or of_cut_stack(name, names)

    def mine(kind, item):
        if kind == "stack":
            return of_stack(item, stacks)
        if kind == "role":
            return item.startswith(f"colvenna-lake-{env}-") or of_stack_or_cut(item, stacks)
        if kind == "repository":
            return item.startswith(f"colvenna-lake-{env}/")
        if kind == "dns":
            name = item.rstrip(".").lower()
            return (name == domain or name.endswith("." + domain)) if domain else f".colvenna-{env}." in "." + item
        if kind in ("bucket", "database"):
            return of_stack_or_cut(item, lower)
        if kind == "snapshot":
            return of_stack_or_cut(item[1], lower)
        if kind == "user-pool":
            return item == f"colvenna-lake-{env}" or item.startswith(f"colvenna-lake-{env}-")
        if kind == "secret":
            return item[0].startswith(f"colvenna-lake-{env}/") or item[1] in stacks
        if kind == "log-group":
            return (f"/colvenna-lake-{env}/" in item
                    or item.startswith("/aws/lambda/") and of_stack_or_cut(item.removeprefix("/aws/lambda/"), stacks)
                    or bool(cluster) and item.startswith(f"/aws/eks/{cluster}/")
                    or item.startswith("/aws/rds/instance/")
                    and of_stack_or_cut(item.removeprefix("/aws/rds/instance/"), lower))
        if kind == "oidc":
            return bool(issuer) and item.endswith("/" + issuer.removeprefix("https://"))
        if kind == "volume":
            return bool(cluster) and item[1] == cluster
        raise ValueError(kind)
    return mine


def account_items(directory, regions):
    """{(kind, region or None, key): detail}: what an account inventory holds."""
    items = {}
    for region in regions:
        for s in load(directory, f"account-stacks-{region}.json", []):
            items[("stack", region, s["name"])] = (s["status"], s.get("updated"))
        for kind, file, key in (("repository", "repositories", None), ("database", "databases", None),
                                ("user-pool", "userpools", None), ("log-group", "loggroups", None),
                                ("snapshot", "snapshots", tuple), ("secret", "secrets", tuple),
                                ("volume", "volumes", tuple)):
            for item in load(directory, f"account-{file}-{region}.json", []) or []:
                items[(kind, region, tuple(item) if key else item)] = None
    for kind, file in (("role", "roles"), ("bucket", "buckets"), ("oidc", "oidc")):
        for item in load(directory, f"account-{file}.json", []) or []:
            items[(kind, None, item)] = None
    for d in load(directory, "account-dns.json", []) or []:
        items[("dns", None, (d["name"], d["type"]))] = None
    return items


def judge_others(before, after, env):
    """Nothing of env left; every other environment's (and the release registry's) items as they were."""
    regions = [line.strip() for line in open(os.path.join(before, "account-regions.txt")) if line.strip()]
    mine = ownership(env, load(before, "meta.json", {}) or {})

    def is_mine(key):
        kind, _, item = key
        return mine(kind, item[0] if kind == "dns" else item)

    def show(key):
        kind, region, item = key
        name = item[0] if isinstance(item, tuple) else item
        return f"{kind} {name}" + (f" ({region})" if region else "")

    def listing(keys):
        # How many of each kind, then the first few by name.
        counts = {}
        for k in keys:
            counts[k[0]] = counts.get(k[0], 0) + 1
        return (", ".join(f"{kind} {n}" for kind, n in sorted(counts.items())) + ": "
                + "; ".join(sorted(show(k) for k in keys)[:6]))

    ib, ia = account_items(before, regions), account_items(after, regions)
    left = [k for k in ia if is_mine(k)]
    moved = [k for k in set(ib) | set(ia) if not is_mine(k) and ib.get(k, "gone") != ia.get(k, "gone")]
    kinds = sorted({k[0] for k in ib})
    check("nothing-of-it-left", not left, f"no {', '.join(kinds)} of {env}" if not left else listing(left))
    others = [k for k in ia if not is_mine(k)]
    check("others-untouched", not moved,
          f"{len(others)} items of other environments and the release registry in {', '.join(regions)} as before"
          if not moved else listing(moved))


def main(argv):
    command = argv[1] if len(argv) > 1 else ""
    if command == "steps" and len(argv) >= 4:
        platform = argv[3] == "--platform"
        evidences = argv[4:] if platform else argv[3:]
        if not evidences:
            print(__doc__, file=sys.stderr)
            return 2
        judge_steps(argv[2], platform, evidences)
    elif command == "fresh" and len(argv) >= 4:
        judge_fresh(argv[2], argv[3:])
    elif command == "platform" and len(argv) == 3:
        judge_platform(argv[2])
    elif command == "images" and len(argv) == 3:
        judge_images(argv[2])
    elif command == "repeat" and len(argv) == 4:
        judge_repeat(argv[2], argv[3])
    elif command == "repeat" and len(argv) == 7 and argv[4] == "--same-product":
        judge_repeat(argv[2], argv[3], (argv[5], argv[6]))
    elif command == "upgrade" and len(argv) == 5:
        judge_upgrade(argv[2], argv[3], argv[4])
    elif command == "others" and len(argv) == 5:
        judge_others(argv[2], argv[3], argv[4])
    else:
        print(__doc__, file=sys.stderr)
        return 2
    for name, outcome, detail in results:
        print(f"{name}\t{outcome}\t{detail}")
    return 0 if all(o == "PASS" for _, o, _ in results) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))

#!/usr/bin/env python3
"""A release promoted from a GO candidate, and its manifest, release/<release>/manifest.json.

A release, v<major>.<minor>.<patch>, is one of its candidates (v<major>.<minor>.<patch>-rc.<n>, see
manifest.py) judged GO and promoted without rebuilding anything (release/README.md, "晋升"), as
Colvenna Catalog promotes its own. promote.sh makes it on top of the candidate's tag commit, C:

- every image the candidate's manifest names (the product's, the fixtures', the third-party ones, the
  colvenna CLI's, and the copies of the Colvenna Catalog release's image and chart) is the candidate's,
  by the same digest, tagged with the release's name as well in the release registry: <release>,
  <release>-<variant>, a third-party one <release>-<name> (crane tag: nothing is copied or built);
- no Helm chart changes: this repository's four are installed from the tree and pinned by their
  directories' git trees, which the release's tag holds unchanged, so their versions stay as they are
  (release/README.md, "Helm chart 的版本"); the catalog's is Colvenna Catalog's release, pinned by digest,
  and never repackaged here;
- the manifest is the candidate's under the release's names (release in place of candidate,
  source.tag and every image's tag), and promotion says where it came from: the candidate, its tag
  commit and its manifest there, when it was promoted, and the commit whose
  release/<candidate>/notes.md records its GO verdict (the acceptance);
- the release notes are the candidate's as accepted, under the release's name, their first section
  the release's identity.

Where Colvenna Catalog's differs: a release of Colvenna Lake repackages no chart, so its promotion
records no chart and its tag adds no chart comparison; and its generated stays the candidate's (the
environment and region its images were gathered from), the promotion's time being promotion.at.

A candidate is judged GO when its release notes' verdict row (section 5's 发布结论) says GO, with a
basis that links the release check (verdict).

deploy/install.sh --release <release> and check-release.sh <release> take a release from a checkout of
its tag (manifest.py reads its manifest with this module): check-tree holds the checkout to the
manifest's promotion as well, as promote.sh made it (provenance_problems).

usage:
  promotion.py names <candidate> <release>    key, repository, the candidate's tag and the release's,
                                              tab-separated, of every image
  promotion.py verdict <notes.md>             GO, if the notes' release verdict is GO
  promotion.py assemble <work>                the release's manifest (see assemble), on stdout
  promotion.py validate <manifest> [--release <release>]
  promotion.py check-tree <manifest> <repo> <commit>
                                              the manifest's git objects are the commit's, and the commit
                                              is where the manifest says the release was promoted from
  promotion.py notes <manifest> < notes       the release notes, from the candidate's as accepted
  promotion.py registry <registry>            the release registry's kind: ecr (an ECR registry of a
                                              standard region) or loopback (a rehearsal's)
Problems go to stderr, with exit status 1.
"""
import copy
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import manifest  # noqa: E402

RELEASE = manifest.RELEASE
SOURCE = manifest.SOURCE
# A registry on this host, for a rehearsal or the offline tests: read and written over plain HTTP.
LOOPBACK = re.compile(r"^(?:localhost|127\.0\.0\.1):[0-9]{1,5}$")
# AWS ECR Public, which nothing of this project is ever published to: its registry and its API.
ECR_PUBLIC = re.compile(r"(?:^|[./])public\.ecr\.aws(?:$|[/:])|ecr-public", re.IGNORECASE)
# A release's manifest: a candidate's fields, release in place of candidate, and promotion.
TOP = ("schema", "release", "promotion") + tuple(f for f in manifest.FIELDS if f not in ("schema", "candidate"))
PROMOTION = ("candidate", "tag_commit", "at", "manifest", "acceptance")


def read(path):
    with open(path) as f:
        return f.read()


def release_problems(name):
    if isinstance(name, str) and RELEASE.fullmatch(name):
        return []
    return [f"release: {name!r} is not v<major>.<minor>.<patch>"]


def candidate_of_problems(candidate, release):
    """Whether the candidate is one of the release's: <release>-rc.<n> (a trial, -dev.<n>, is never one)."""
    found = release_problems(release)
    named = manifest.CANDIDATE.fullmatch(candidate) if isinstance(candidate, str) else None
    if named is None or named[4] != "rc":
        found.append(f"candidate: {candidate!r} is not v<major>.<minor>.<patch>-rc.<n> (n from 1)")
    elif not found and not candidate.startswith(f"{release}-rc."):
        found.append(f"{candidate} is not a candidate of {release}: it promotes to {candidate.split('-rc.')[0]}")
    return found


def names(candidate, release):
    """(key, repository, the candidate's tag, the release's tag) of every image (manifest.names)."""
    found = candidate_of_problems(candidate, release)
    if found:
        raise ValueError("; ".join(found))
    tags = {key: tag for key, _, tag in manifest.names(release)}
    return [(key, repository, tag, tags[key]) for key, repository, tag in manifest.names(candidate)]


def source_tag(release, candidate):
    return f"{release} points at {candidate}'s tag commit with release/{release}/ added, nothing else"


def image_entries(m):
    """The objects of a manifest that each name an image in the release registry by repository, tag and
    digest: the product's, the fixtures', the third-party ones, the CLI's and the catalog's copies.
    Whatever is not an object is passed over (problems says so)."""
    def values(value):
        return list(value.values()) if isinstance(value, dict) else []

    def part(value, key):
        return value.get(key) if isinstance(value, dict) else None

    fixtures, catalog = part(m, "fixtures"), part(m, "catalog")
    entries = [*values(part(m, "images")), *values(part(fixtures, "images")), *values(part(fixtures, "third_party")),
               *values(part(m, "third_party")), part(m, "cli"), part(catalog, "image"), part(catalog, "chart")]
    return [entry for entry in entries if isinstance(entry, dict) and isinstance(entry.get("tag"), str)]


def retagged(m, old, new):
    """A copy of the manifest whose image tags of old's (old itself, or old-<variant or name>) are new's."""
    m = copy.deepcopy(m)
    for entry in image_entries(m):
        tag = entry["tag"]
        if tag == old or tag.startswith(f"{old}-"):
            entry["tag"] = new + tag[len(old):]
    return m


def as_release(candidate, release, promotion):
    """The candidate's manifest under the release's names, with promotion."""
    name = candidate["candidate"]
    m = retagged(candidate, name, release)
    rest = {key: value for key, value in m.items() if key not in ("schema", "candidate")}
    m = {"schema": m["schema"], "release": release, "promotion": promotion, **rest}
    m["source"] = {**m["source"], "tag": source_tag(release, name)}
    return m


def candidate_view(m):
    """The candidate's manifest as the release's records it (the candidate's names): manifest.py checks it,
    and it is the candidate's manifest at its tag commit, field for field."""
    name = m["promotion"]["candidate"]
    view = retagged({key: value for key, value in m.items() if key not in ("release", "promotion")}, m["release"], name)
    view = {"schema": view.get("schema"), "candidate": name, **{k: v for k, v in view.items() if k != "schema"}}
    if isinstance(view.get("source"), dict):
        view["source"]["tag"] = manifest.source_tag(name)
    return view


def build(candidate, params):
    """The release's manifest from its candidate's (valid) and what promote.sh did:
      release, at                 the release, and when it was promoted (UTC, ...Z)
      tag_commit, manifest_blob   the candidate's tag commit C, and its manifest's blob there
      acceptance                  {commit, blob}: the commit whose release/<candidate>/notes.md records
                                  the verdict GO, and that file's blob
    ValueError if it is not a valid one."""
    found = manifest.problems(candidate)
    if found:
        raise ValueError("the candidate's manifest: " + "; ".join(found))
    name, release = candidate["candidate"], params["release"]
    m = as_release(candidate, release, {
        "candidate": name,
        "tag_commit": params["tag_commit"],
        "at": params["at"],
        "manifest": {"path": f"release/{name}/manifest.json", "blob": params["manifest_blob"]},
        "acceptance": {"path": f"release/{name}/notes.md", "commit": params["acceptance"]["commit"],
                       "blob": params["acceptance"]["blob"], "verdict": "GO"},
    })
    found = problems(m)
    if found:
        raise ValueError("; ".join(found))
    return m


def assemble(work):
    """The release's manifest from what promote.sh gathered in the directory work: candidate.json (the
    candidate's manifest at its tag commit) and params.json (build's params)."""
    return build(json.loads(read(os.path.join(work, "candidate.json"))),
                 json.loads(read(os.path.join(work, "params.json"))))


def problems(m, release=None):
    """What is wrong with a release's manifest: an empty list when nothing is."""
    found = []

    def need(ok, what):
        if not ok:
            found.append(what)

    if not isinstance(m, dict):
        return ["the manifest: not an object"]
    for key in TOP:
        need(key in m, f"{key}: missing")
    for key in m:
        need(key in TOP, f"unknown field {key}")
    if found:
        return found
    name = m["release"]
    found.extend(release_problems(name))
    if release is not None:
        need(name == release, f"release: the manifest is {name!r}'s, not {release}'s")
    promotion = m["promotion"]
    if not isinstance(promotion, dict) or set(promotion) != set(PROMOTION):
        return found + [f"promotion: not an object of {', '.join(PROMOTION)}"]
    candidate = promotion["candidate"]
    if not release_problems(name):
        found.extend(f"promotion.{p}" for p in candidate_of_problems(candidate, name))
    sha = manifest.SHA.fullmatch
    need(isinstance(promotion["tag_commit"], str) and sha(promotion["tag_commit"]) is not None,
         "promotion.tag_commit: not a full commit SHA")
    need(isinstance(promotion["at"], str) and manifest.UTC.fullmatch(promotion["at"]) is not None,
         "promotion.at: not a UTC time, <date>T<time>Z")
    files_of = {"manifest": ("path", "blob"), "acceptance": ("path", "commit", "blob", "verdict")}
    for key, fields in files_of.items():
        value = promotion[key]
        if not isinstance(value, dict) or set(value) != set(fields):
            found.append(f"promotion.{key}: not an object of {', '.join(fields)}")
            continue
        for part in ("blob", "commit"):
            if part in fields:
                need(isinstance(value[part], str) and sha(value[part]) is not None,
                     f"promotion.{key}.{part}: not a full git object id")
    if found:
        return found
    need(promotion["manifest"]["path"] == f"release/{candidate}/manifest.json",
         f"promotion.manifest.path: not release/{candidate}/manifest.json")
    acceptance = promotion["acceptance"]
    need(acceptance["path"] == f"release/{candidate}/notes.md", f"promotion.acceptance.path: not release/{candidate}/notes.md")
    need(acceptance["verdict"] == "GO", f"promotion.acceptance.verdict: {acceptance['verdict']!r}, not GO")
    if isinstance(m["source"], dict):
        need(m["source"].get("tag") == source_tag(name, candidate), f"source.tag: not {source_tag(name, candidate)!r}")
    # The rest is the candidate's under the release's names: its view, with the candidate's tags, is a valid
    # candidate's manifest. A tag that is not <release>[-<variant or name>] keeps its form there, and fails.
    found.extend(manifest.problems(candidate_view(m), candidate))
    return found


def changed_outside(repo, commit, other, directory):
    """The paths in which the two commits differ outside the directory, or None if git cannot compare them.
    Without rename detection, a file moved into the directory is changed where it was taken from too."""
    changed = manifest.git(repo, "diff", "--no-renames", "--name-only", commit, other)
    if changed is None:
        return None
    return [path for path in changed.splitlines() if not path.startswith(f"{directory}/")]


def provenance_problems(m, repo, commit):
    """Where the commit is not what a valid release's manifest says the release was promoted from, as
    promote.sh made it: the candidate's tag commit (promotion.tag_commit) with release/<release>/ added,
    nothing else; that commit's release/<candidate>/manifest.json the recorded one (promotion.manifest),
    of which the release's manifest is the candidate's under the release's names (candidate_view); and the
    acceptance record as acceptance_problems says."""
    release, tag_commit = m["release"], m["promotion"]["tag_commit"]
    candidate = m["promotion"]["candidate"]
    if manifest.git(repo, "rev-parse", "-q", "--verify", f"{tag_commit}^{{commit}}") is None:
        return [f"promotion.tag_commit: this clone lacks {tag_commit}, {candidate}'s tag commit: git fetch origin --tags"]
    found = []
    changed = changed_outside(repo, tag_commit, commit, f"release/{release}")
    if changed is None:
        found.append(f"{commit} cannot be compared with {tag_commit}, {candidate}'s tag commit")
    elif changed:
        found.append(f"{commit} differs from {tag_commit}, {candidate}'s tag commit, outside release/{release}/:"
                     f" {' '.join(changed[:20])}")
    recorded = m["promotion"]["manifest"]
    blob = manifest.git(repo, "rev-parse", "-q", "--verify", f"{tag_commit}:{recorded['path']}")
    if blob != recorded["blob"]:
        return found + [f"promotion.manifest: {recorded['path']} is {blob or 'not there'} at {tag_commit}, not the"
                        f" recorded {recorded['blob']}"]
    try:
        held = json.loads(manifest.git(repo, "cat-file", "blob", blob))
    except json.JSONDecodeError as error:
        return found + [f"promotion.manifest: {recorded['path']} at {tag_commit} is not JSON ({error})"]
    view = candidate_view(m)
    if view != held:
        differing = sorted(key for key in set(view) | set(held) if view.get(key) != held.get(key)) \
            if isinstance(held, dict) else ["the manifest"]
        found.append(f"the release's manifest is not {candidate}'s under the release's names (at {tag_commit}):"
                     f" {', '.join(differing)} differ")
    return found + acceptance_problems(m, repo)


def acceptance_problems(m, repo):
    """Where the acceptance record is not what promote.sh promoted on: a commit that has the candidate's tag
    commit, whose release/<candidate>/notes.md is the recorded blob and gives the release verdict GO (verdict),
    beside the same release/<candidate>/manifest.json as that tag commit's."""
    p = m["promotion"]
    acceptance, tag_commit, candidate = p["acceptance"], p["tag_commit"], p["candidate"]
    record = acceptance["commit"]
    if manifest.git(repo, "rev-parse", "-q", "--verify", f"{record}^{{commit}}") is None:
        return [f"promotion.acceptance: this clone lacks {record}, the commit whose {acceptance['path']} judged"
                f" {candidate} GO: git fetch origin"]
    if manifest.git(repo, "merge-base", "--is-ancestor", tag_commit, record) is None:
        return [f"promotion.acceptance: {record} does not have {tag_commit}, {candidate}'s tag commit"]
    blob = manifest.git(repo, "rev-parse", "-q", "--verify", f"{record}:{acceptance['path']}")
    if blob != acceptance["blob"]:
        return [f"promotion.acceptance: {acceptance['path']} is {blob or 'not there'} at {record}, not the recorded"
                f" {acceptance['blob']}"]
    found = []
    try:
        verdict(manifest.git(repo, "cat-file", "blob", blob))
    except ValueError as error:
        found.append(f"promotion.acceptance: {acceptance['path']} at {record}: {error}")
    held = manifest.git(repo, "rev-parse", "-q", "--verify", f"{record}:{p['manifest']['path']}")
    if held != p["manifest"]["blob"]:
        found.append(f"promotion.acceptance: {p['manifest']['path']} is {held or 'not there'} at {record}, not the"
                     f" one {candidate}'s tag commit holds ({p['manifest']['blob']})")
    return found


def tree_problems(m, repo, commit):
    """Where the commit is not what the release's manifest describes: the git objects it pins, and where it
    was promoted from (provenance_problems)."""
    return manifest.tree_problems(candidate_view(m), repo, commit) + provenance_problems(m, repo, commit)


def valid(path, release=None):
    m = manifest.load(path)
    found = problems(m, release)
    if found:
        raise ValueError(f"{path}:\n  " + "\n  ".join(found))
    return m


# The release notes' verdict row (release/notes-template.md, section 5): | 发布结论 | <result> | <basis> |
VERDICT_ROW = re.compile(r"^\s*\|\s*发布结论\s*\|")
# The result GO, plain, bold or as code, alone or followed by what it rests on after 。 or ：.
GO = re.compile(r"(?:GO|\*\*GO\*\*|`GO`)(?:\s*[。：:].*)?")


def verdict(text):
    """GO when the notes' release verdict is GO and its basis links the release check; ValueError saying what
    it is otherwise: NO-GO, the template's placeholder, a basis that is still the template's or links
    nothing, no verdict row, or more than one."""
    rows = [line for line in text.splitlines() if VERDICT_ROW.match(line)]
    if len(rows) != 1:
        raise ValueError(f"the release notes have {len(rows)} '发布结论' rows, not one: the verdict is recorded in"
                         " section 5's table")
    cells = [cell.strip() for cell in rows[0].strip().strip("|").split("|")]
    result = cells[1] if len(cells) > 1 else ""
    basis = cells[2] if len(cells) > 2 else ""
    if not GO.fullmatch(result):
        raise ValueError(f"the release verdict is {result[:80] or 'empty'}, not GO: only a candidate judged GO is"
                         " promoted")
    if re.fullmatch(r"<.*>", basis) or not re.search(r"\]\([^)\s]+\)", basis):
        raise ValueError(f"the release verdict's basis is {basis or 'empty'}: it links the release check that"
                         " judged the candidate GO")
    return "GO"


SECTION = re.compile(r"^## ", re.MULTILINE)


def identity(m):
    """The release notes' first section: the release, from its manifest."""
    p, built_from, catalog = m["promotion"], m["source"]["built_from"], m["catalog"]
    candidate, release, acceptance = p["candidate"], m["release"], p["acceptance"]
    count = len(image_entries(m))
    charts = "、".join(f"`{chart}` {value['version']}" for chart, value in m["charts"].items())
    return f"""## 1. 正式版

| 项 | 内容 |
| --- | --- |
| 正式版 | `{release}`（annotated tag） |
| 晋升自 | `{candidate}`，判为 GO：它验收时的发布说明，即提交 `{acceptance['commit']}` 上的 [`{acceptance['path']}`]({SOURCE}/blob/{acceptance['commit']}/{acceptance['path']})。没有重新构建任何东西；本节之后的各节就是那份说明，原样保留 |
| 镜像与安装所用的源码 | 提交 [`{built_from[:8]}`]({SOURCE}/commit/{built_from})（`{built_from}`），与 `{candidate}` 的相同。tag `{release}` 指向的提交在 `{candidate}` 的 tag 提交 `{p['tag_commit']}` 上只多了 `release/{release}/` |
| 发布清单 | [`release/{release}/manifest.json`]({SOURCE}/blob/{release}/release/{release}/manifest.json)：`{candidate}` 的清单换成正式版的名字，digest 全都不变；`promotion` 记下晋升自哪个候选、它的 tag 提交与清单，以及记下 GO 结论的提交与文件 |
| 镜像 | 发布仓库里 `{candidate}` 的 {count} 个镜像（产品、测试夹具与第三方镜像，colvenna CLI，以及 Colvenna Catalog 发布版的镜像与 chart 的副本），以同一个 digest 另打上正式版的 tag：`{release}`、`{release}-<变体>`，第三方镜像 `{release}-<名字>`。候选的 tag 照旧 |
| Helm chart | 本仓库的四个 chart 没有改动，版本号与候选的相同（{charts}）：安装时取自 tag 的工作区，以清单里各 chart 目录的 git tree 固定（`charts.<chart>.tree`）。colvenna-catalog 的 chart 是 Colvenna Catalog `{catalog['candidate']}` 的发布物，按 digest 固定（`catalog.chart`，版本 `{catalog['chart']['version']}`），本仓库不重新打包 |
| 晋升日期 | {p['at'][:10]} |

按正式版安装，同样不构建镜像（[部署指南]({SOURCE}/blob/{release}/docs/manual/install.md)）：

```bash
git fetch origin --tags                                    # 晋升记录里的提交都要在本地
git checkout {release}
export AWS_PROFILE=<目标账号的 profile>
export COLVENNA_RELEASE_REGISTRY=<发布仓库的账号>.dkr.ecr.<发布仓库的区域>.amazonaws.com
COLVENNA_ENV=<环境> COLVENNA_REGION=<区域> COLVENNA_ACCOUNT=<账号> \\
  COLVENNA_HOSTED_ZONE_ID=<托管区 ID> COLVENNA_HOSTED_ZONE_NAME=<托管区名> [COLVENNA_ENTRY_DOMAIN=<入口域名>] \\
  deploy/install.sh --release {release} [--fixtures]
```

"""


def notes(m, text):
    """The release notes from the candidate's as accepted (text): the title and heading under the release's
    name, the date the promotion's, the status final (nothing is left to fill in: the verdict is GO), the
    template's comment replaced by one saying where the notes come from, and section 1 the release's
    identity; every other section unchanged."""
    candidate, release = m["promotion"]["candidate"], m["release"]
    title = f"Colvenna Lake {candidate} 发布说明"
    heading = re.search(rf"^# {re.escape(title)}\n", text, re.MULTILINE)
    first = re.search(r"^## 1\. 候选\n", text, re.MULTILINE)
    if heading is None or first is None:
        raise ValueError(f"the candidate's notes need the heading '# {title}' and a section '## 1. 候选'")
    after = [s.start() for s in SECTION.finditer(text) if s.start() > first.start()]
    if not after:
        raise ValueError("the candidate's notes have no section after '## 1. 候选'")
    head = text[:heading.start()]
    head = re.sub(rf'^title: "?{re.escape(title)}"?$', f'title: "Colvenna Lake {release} 发布说明"', head,
                  count=1, flags=re.MULTILINE)
    head = re.sub(r"^date: .*$", f"date: {m['promotion']['at'][:10]}", head, count=1, flags=re.MULTILINE)
    head = re.sub(r"^status: .*$", "status: final", head, count=1, flags=re.MULTILINE)
    comment = (f"<!--\ndeploy/scripts/release/promote.sh 把 {candidate} 晋升为 {release} 时写下这份说明：第 1 节取自\n"
               f"release/{release}/manifest.json；其余各节是 {candidate} 验收时的发布说明（提交\n"
               f"{m['promotion']['acceptance']['commit']} 上的 release/{candidate}/notes.md），原样保留。\n-->\n")
    head = re.sub(r"<!--.*?-->\n", lambda _: comment, head, count=1, flags=re.DOTALL)
    if comment not in head:
        head += "\n" + comment
    return (head + f"# Colvenna Lake {release} 发布说明\n" + text[heading.end():first.start()]
            + identity(m) + text[after[0]:])


def registry_kind(registry):
    """ecr or loopback: the release registry, an ECR registry of a standard region (as
    COLVENNA_RELEASE_REGISTRY), or a loopback one standing in for it in a rehearsal. Never AWS ECR Public."""
    if ECR_PUBLIC.search(registry):
        raise ValueError(f"COLVENNA_RELEASE_REGISTRY={registry!r}: AWS ECR Public is never a target, and the"
                         " release registry is the private one")
    if LOOPBACK.fullmatch(registry):
        return "loopback"
    manifest.registry_parts(registry)
    return "ecr"


def main(argv):
    command, args = (argv[0], argv[1:]) if argv else ("", [])
    if command == "names" and len(args) == 2:
        for row in names(*args):
            print("\t".join(row))
    elif command == "verdict" and len(args) == 1:
        print(verdict(read(args[0])))
    elif command == "assemble" and len(args) == 1:
        print(json.dumps(assemble(args[0]), indent=2, ensure_ascii=False))
    elif command == "validate" and len(args) in (1, 3) and (len(args) == 1 or args[1] == "--release"):
        valid(args[0], args[2] if len(args) == 3 else None)
    elif command == "check-tree" and len(args) == 3:
        found = tree_problems(valid(args[0]), args[1], args[2])
        if found:
            raise ValueError(f"{args[0]}:\n  " + "\n  ".join(found))
    elif command == "notes" and len(args) == 1:
        print(notes(valid(args[0]), sys.stdin.read()), end="")
    elif command == "registry" and len(args) == 1:
        print(registry_kind(args[0]))
    else:
        raise ValueError(__doc__.split("usage:\n", 1)[1].split("Problems", 1)[0].rstrip())


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (ValueError, OSError, KeyError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)

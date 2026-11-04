#!/usr/bin/env python3
"""A release candidate's manifest, release/<candidate>/manifest.json: what the candidate pins.

make-manifest.sh writes it from an environment installed from source at the candidate's commit;
deploy/install.sh --release installs from it (check-release.sh validates it first). The one place
that names the candidate's images: each key's repository in the release registry
(colvenna-lake-release/<repository>, an environment's own being colvenna-lake-<env>/<repository>), tag
(<candidate>, or <candidate>-<variant>) and digest. The third-party images the platform and the
fixtures run (deploy/images/third-party.tsv) are kept there too, in colvenna-lake-release/third-party,
tagged <candidate>-<name>, with the upstream reference each was pinned by. A manifest names no
registry and no AWS account (#248): the release registry, <account>.dkr.ecr.<region>.amazonaws.com,
is the installer's setting COLVENNA_RELEASE_REGISTRY, whichever account and region hold a copy.

Colvenna Catalog is a product of its own (#295), and this tree runs the release of it that
deploy/images/colvenna-catalog.json pins: the catalog's repository and the release's name
(candidate: one of the catalog's candidates, v<major>.<minor>.<patch>-rc.<n>, or a release promoted
from a candidate judged GO, v<major>.<minor>.<patch>, with promotion saying which: that candidate,
its tag's commit, the commit that records its GO verdict, and its chart's digest), the commit its tag
points at, the commit its image and chart were built from (built_from), the registry the catalog
publishes them in (registry: GitHub Container Registry, ghcr.io/colvenna, read anonymously) and both
by their repository there and digest (ghcr.io/colvenna/colvenna-catalog and
ghcr.io/colvenna/charts/colvenna-catalog), the chart with its version. Its schema_version is the
catalog schema that release's server applies (its migrations V1..Vn at built_from). The setting
COLVENNA_CATALOG_REGISTRY=<registry>[/<prefix>] puts another registry in place of the pin's, holding
the same digests under the same repository names (catalog-source): a mirror, or the maintainers' private
release registry, which holds the catalog's candidates (GHCR has its releases only), read with the AWS
credentials.
A candidate keeps a copy of both, the same digests, in its own release registry
(colvenna-lake-release/colvenna-catalog and colvenna-lake-release/charts/colvenna-catalog, tagged
<candidate>), and records them, the pin's fields beside them, under catalog (schema 3).

A release, v<major>.<minor>.<patch>, is never made here: one of its candidates judged GO is promoted
to it, nothing rebuilt (promote.sh; its manifest, release/<release>/manifest.json, is promotion.py's:
the candidate's under the release's names, with the same digests). deploy/install.sh --release and
check-release.sh take either: validate, check-tree, kind, added, names and images read both,
and hold a release's to promotion.py.

usage:
  manifest.py assemble <work>                 the manifest, from what make-manifest.sh gathered in
                                              <work> (see assemble), on stdout
  manifest.py validate <manifest> [--name <name>]
                                              a candidate's or a release's manifest (of that name)
  manifest.py check-tree <manifest> <repo> <commit>
                                              the git objects the manifest pins are the commit's (a
                                              release's: and where it was promoted from, promotion.py)
  manifest.py kind <name>                     candidate or release: the names install.sh --release
                                              takes (make-manifest.sh makes candidates only)
  manifest.py added <manifest>                the directories its tag adds to the commit it was built
                                              from, one per line
  manifest.py names <name>                    key, repository, tag of every image, the third-party
                                              ones too, tab-separated, of a candidate or a release
  manifest.py regions <region>,<region>...    the regions, one per line, if they are two or more
                                              distinct standard ones
  manifest.py registry <registry>             its account and region, tab-separated, if it is an ECR
                                              registry in a standard region (COLVENNA_RELEASE_REGISTRY)
  manifest.py binaries                        the colvenna CLI's binaries, one per line
  manifest.py stack                           the release registry's stack
  manifest.py third-party [--fixtures]        name, upstream reference, digest, tag in an
                                              environment's repository, of the platform's
                                              third-party images (and the fixtures'), tab-separated
  manifest.py images <manifest> [--fixtures] [--cli]
                                              key, repository, tag, digest, tab-separated: the
                                              product's images and the platform's third-party ones,
                                              the fixtures' of both kinds, the CLI's
  manifest.py running <workloads.json>... <registry>/colvenna-lake-<env>
                                              workload, container, image, and aws or upstream, of
                                              every container whose image is not in the
                                              environment's own repositories, of workloads and pods
                                              not finished
  manifest.py get <manifest> <field.field...> one value
  manifest.py doris-source <release-dir> <fe-digest> <be-digest>
                                              the query engine's repository
                                              (colvenna-lake-release/query-engine) of the newest manifest
                                              there that pins those digests, or nothing
                                              (lib/preflight.sh's doris_source)
  manifest.py required-cases <cases.md>       the required cases, one per line
  manifest.py notes <manifest> < template     the release notes' draft, from the template
  manifest.py catalog [<field.field...>]      the catalog release this tree pins
                                              (deploy/images/colvenna-catalog.json), checked; or one
                                              of its values
  manifest.py catalog-check image|chart <config.json>
                                              whether an image's config (crane config) or a chart's
                                              (its OCI config, Chart.yaml as JSON) is the pinned
                                              catalog release's: built from built_from, for
                                              linux/amd64, the chart's name and version
  manifest.py catalog-source [<registry>[/<prefix>]]
                                              where a source install takes the pinned catalog
                                              release from: the given one (COLVENNA_CATALOG_REGISTRY),
                                              else the pin's registry; and, tab-separated after it,
                                              the account and region of an ECR one, which is read
                                              with the AWS credentials (nothing: read anonymously)
  manifest.py catalog-held <registry>[/<prefix>]
                                              whether that registry gives the pinned image and chart
                                              by digest to an anonymous reader (the OCI
                                              distribution API)
Problems go to stderr, with exit status 1.
"""
import glob
import hashlib
import http.client
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

SCHEMA = 3
# The release registry's stack (deploy/aws/src/release-stack.ts's RELEASE_STACK) and its ECR
# registry, <account>.dkr.ecr.<region>.amazonaws.com in a standard region: the installer's setting
# COLVENNA_RELEASE_REGISTRY, never written into a manifest.
STACK = "ColvennaLakeRelease"
REGISTRY = re.compile(r"^([0-9]{12})\.dkr\.ecr\.((?!cn-)[a-z]{2}-[a-z]+-[1-9])\.amazonaws\.com$")
PREFIX = "colvenna-lake-release"
# The repository a candidate is made in, which its manifest and notes name: colvenna/colvenna-lake,
# where the repository moves before v1.0 (#271, #300). The manifests of v1.0.0-rc.2 to rc.5 record
# the early private repository they were made in; they are schema 2, records this tree's tools no
# longer read (schema 3, #295), so only the candidates made from here on are checked against it.
SOURCE = "https://github.com/colvenna/colvenna-lake"
# key: (repository, tag variant): the product's images, what deploy/install.sh puts in every
# environment...
PRODUCT_IMAGES = {
    "flink": ("flink-base", ""),
    "operator": ("colvenna-operator", ""),
    "jobs-mysql": ("colvenna-jobs", "mysql"),
    "jobs-postgres": ("colvenna-jobs", "postgres"),
    "jobs-mongodb": ("colvenna-jobs", "mongodb"),
    "jobs-sql": ("colvenna-jobs", "sql"),
    "jobs-kafka": ("colvenna-jobs", "kafka"),
    "console": ("colvenna-console", ""),
    "bi": ("colvenna-bi", ""),
    # The query engine's FE and BE: a modified build based on Apache Doris 4.1.4
    # (deploy/images/doris), distributed under a name of its own, not Doris's (release/trademarks.md).
    "query-engine-fe": ("query-engine", "fe"),
    "query-engine-be": ("query-engine", "be"),
}
QUERY_ENGINE = ("query-engine-fe", "query-engine-be")
# ...what it adds with --fixtures...
FIXTURE_IMAGES = {"readers-spark": ("test-readers", "spark")}
# ...and the colvenna CLI's binaries (colvenna-cli/check.sh build), as the one layer of an image.
CLI = ("colvenna-cli", "")
CLI_BINARIES = ("colvenna-linux-amd64", "colvenna-linux-arm64", "colvenna-darwin-arm64")
# The third-party images (deploy/images/third-party.tsv), all in one repository of the release
# registry and of each environment.
THIRD_PARTY_LIST = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "images", "third-party.tsv")
THIRD_PARTY_REPOSITORY = "third-party"
THIRD_PARTY_SCOPES = ("platform", "fixtures")
# The Helm charts, deploy/helm/<chart>: each pinned by its directory's git tree (their versions do
# not move between candidates). The catalog's is its release's (CATALOG_*).
CHARTS = ("colvenna-bi", "colvenna-console", "colvenna-monitoring", "colvenna-operator")
# Colvenna Catalog's release (#295), pinned in deploy/images/colvenna-catalog.json: its server image
# and Helm chart, which the catalog publishes in GitHub Container Registry (CATALOG_REGISTRY, public:
# read anonymously) under these repository names, and the label and annotation that say which commit
# they were built from. Another registry may stand in for it (COLVENNA_CATALOG_REGISTRY, catalog-source)
# with the same names under its prefix. A candidate copies both into its own release registry (key:
# repository under PREFIX), tagged <candidate> as the product's images are.
CATALOG_PIN = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "images", "colvenna-catalog.json")
CATALOG_REGISTRY = "ghcr.io/colvenna"
CATALOG_REPOSITORIES = {"image": "colvenna-catalog", "chart": "charts/colvenna-catalog"}
# A registry and prefix to read the release from: a host (lowercase, a port allowed) and repository
# path segments as OCI names them; an ECR registry among them is one in a standard region.
CATALOG_SOURCE_REGISTRY = re.compile(r"^(?P<host>[a-z0-9]([a-z0-9.-]*[a-z0-9])?(:[0-9]{1,5})?)"
                                     r"(?P<path>(/[a-z0-9]+([._-][a-z0-9]+)*)*)$")
ECR_HOST = re.compile(r"\.dkr\.ecr\.[^.]+\.amazonaws\.com(\.cn)?$")
# The manifest types the release's image (an image or an index) and chart (an OCI image manifest) may
# have, as lib/preflight.sh's MANIFEST_TYPES.
MANIFEST_TYPES = ("application/vnd.oci.image.index.v1+json", "application/vnd.oci.image.manifest.v1+json",
                  "application/vnd.docker.distribution.manifest.list.v2+json",
                  "application/vnd.docker.distribution.manifest.v2+json")
CATALOG_IMAGES = {"catalog": "colvenna-catalog", "catalog-chart": "charts/colvenna-catalog"}
CATALOG_CHART = "colvenna-catalog"
CATALOG_PLATFORM = "linux/amd64"
CATALOG_REVISION_LABEL = "org.opencontainers.image.revision"
CATALOG_SOURCE_ANNOTATION = "colvenna-catalog/source-commit"
CATALOG_SOURCE = re.compile(r"^https://github\.com/[A-Za-z0-9][A-Za-z0-9-]*/colvenna-catalog$")
# A catalog release lake may pin: a candidate or a release, never a trial (-dev.<n>). A release is
# one of its candidates judged GO and promoted without rebuilding anything (colvenna-catalog#65): the
# same image digest, the candidate's chart repackaged as the release's version, so a new chart digest,
# and the release's tag a commit of its own on top of the candidate's tag commit (release/<release>/
# added). The pin's promotion records where it came from (CATALOG_PROMOTION).
CATALOG_CANDIDATE = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.[1-9][0-9]*)?$")
CATALOG_PROMOTION = ("candidate", "tag_commit", "acceptance", "chart_digest")
INSTALL_MODES = ("platform", "fixtures")
# The candidate's performance tier, fixed before it is measured (#207; the requester's decision of
# 2026-10-22): the default scale, the load it carries for how long, and where the latency targets
# and the method are written. The one source: assemble writes it, validate requires it as it is,
# and the tests check tests/performance/run-release.sh's defaults against it.
RELEASE_PERFORMANCE = {
    "tier": "default scale",
    "write_nodes": 2,
    "query_nodes": 2,
    "cdc_changes_per_second": 200,
    "kafka_events_per_second": 2000,
    "dashboard_qps": 10,
    "steady_hours": 2,
    "latency_percentiles": ["p95", "p99"],
    "latency_targets": "docs/design/acceptance-contract.md#21-测量协议",
    "method": "docs/uat/performance.md",
}

# A candidate, v<major>.<minor>.<patch>-rc.<n> (-dev.<n>: a trial of the tools); a release, its version
# alone, is one of its candidates promoted (promotion.py), never made.
CANDIDATE = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)-(?P<kind>rc|dev)\.[1-9][0-9]*$")
RELEASE = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
# A manifest's fields (a release's has release in place of candidate, and promotion).
FIELDS = ("schema", "candidate", "source", "generated", "images", "fixtures", "cli", "charts", "catalog",
          "installer", "uat_cases", "required_cases", "configurations", "performance", "third_party")
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
CASE = re.compile(r"UAT-[A-Z]+-[0-9]{2}")
# A standard AWS region and an environment's name, as deploy/env.sh says.
REGION = re.compile(r"^(?!cn-)[a-z]{2}-[a-z]+-[1-9]$")
ENVIRONMENT = re.compile(r"^[a-z][a-z0-9]{0,7}$")
# An AWS account id: twelve digits standing alone, as in a registry or an ARN (inside a digest or a
# SHA they run on into other hex digits).
ACCOUNT_ID = re.compile(r"(?<![0-9A-Za-z])[0-9]{12}(?![0-9A-Za-z])")
# A third-party image's name (a tag's part) and its upstream reference, pinned by digest.
NAME = re.compile(r"^[a-z][a-z0-9-]*[a-z0-9]$")
UPSTREAM = re.compile(r"^[a-z0-9.-]+\.[a-z]+/[a-z0-9._/-]+(:[A-Za-z0-9._-]+)?@sha256:[0-9a-f]{64}$")
UTC = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$")
START, END = "<!-- required-cases:start -->", "<!-- required-cases:end -->"


def read(path):
    with open(path) as f:
        return f.read()


def tag(candidate, variant):
    return f"{candidate}-{variant}" if variant else candidate


def third_party_images(path=THIRD_PARTY_LIST):
    """{name: (scope, upstream reference)} of the list deploy/images/third-party.tsv: name, scope,
    upstream reference and what runs it, tab-separated, a line each; # begins a comment. ValueError
    for a row that is not one, or a name twice or a product image's."""
    found = {}
    for number, line in enumerate(read(path).splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        row = line.split("\t")
        where = f"{os.path.basename(path)}:{number}"
        if len(row) != 4 or not all(cell.strip() == cell and cell for cell in row):
            raise ValueError(f"{where}: not name, scope, upstream reference and what runs it, tab-separated")
        name, scope, upstream, _ = row
        if NAME.fullmatch(name) is None or len(name) > 40:
            raise ValueError(f"{where}: {name!r} is not a name of lowercase letters, digits and dashes")
        if scope not in THIRD_PARTY_SCOPES:
            raise ValueError(f"{where}: {name}'s scope {scope!r} is not one of {', '.join(THIRD_PARTY_SCOPES)}")
        if UPSTREAM.fullmatch(upstream) is None:
            raise ValueError(f"{where}: {name}'s {upstream!r} is not <registry>/<repository>[:<tag>]@sha256:<digest>")
        if name in found or name in {**PRODUCT_IMAGES, **FIXTURE_IMAGES, **CATALOG_IMAGES, "cli": CLI}:
            raise ValueError(f"{where}: {name} is named twice, or is a product image's key")
        found[name] = (scope, upstream)
    if not found:
        raise ValueError(f"{path}: no third-party image")
    return found


def third_party_digest(upstream):
    return upstream.rsplit("@", 1)[1]


def third_party_tag(name, upstream):
    """Its tag in an environment's repository: the name and the digest's first 12 hex digits, the same
    whether a source install copied it from upstream or a release install from the release registry."""
    return f"{name}-{third_party_digest(upstream)[7:19]}"


def third_party_scope(scope, images=None):
    """{name: upstream reference} of the third-party images of that scope."""
    return {name: upstream for name, (s, upstream) in (images or third_party_images()).items() if s == scope}


def kind(name):
    """candidate or release: what install.sh --release installs under that name; ValueError if neither."""
    if isinstance(name, str) and CANDIDATE.fullmatch(name):
        return "candidate"
    if isinstance(name, str) and RELEASE.fullmatch(name):
        return "release"
    raise ValueError(f"{name!r} is neither a candidate, v<major>.<minor>.<patch>-rc.<n> (-dev.<n> for a trial),"
                     " nor a release, v<major>.<minor>.<patch>")


def images(m, fixtures=False, cli=False):
    """{key: entry} of the images a manifest names in the release registry, each by repository, tag and
    digest: the product's, the catalog release's copies and the platform's third-party ones; with fixtures
    the fixtures' of both kinds too, with cli the colvenna CLI's. The images command lists them, and a
    release's tags are these entries' (promotion.py). What is not an object is passed over (problems
    says so)."""
    def section(*path):
        value = m
        for key in path:
            value = value.get(key) if isinstance(value, dict) else None
        return value if isinstance(value, dict) else {}

    found = {**section("images"), "catalog": section("catalog").get("image"),
             "catalog-chart": section("catalog").get("chart"), **section("third_party")}
    if fixtures:
        found.update({**section("fixtures", "images"), **section("fixtures", "third_party")})
    if cli:
        found["cli"] = m.get("cli") if isinstance(m, dict) else None
    return {key: entry for key, entry in found.items() if isinstance(entry, dict)}


def names(candidate):
    """Every image's key, repository (under the prefix) and tag, the third-party ones' and the
    catalog release's copies too: a candidate's, or a release's under its own name."""
    every = {**PRODUCT_IMAGES, **FIXTURE_IMAGES, "cli": CLI}
    return ([(key, repository, tag(candidate, variant)) for key, (repository, variant) in every.items()]
            + [(key, repository, candidate) for key, repository in CATALOG_IMAGES.items()]
            + [(name, THIRD_PARTY_REPOSITORY, tag(candidate, name)) for name in third_party_images()])


def catalog_pin_problems(pin):
    """What is wrong with a catalog pin (deploy/images/colvenna-catalog.json): an empty list when
    nothing is."""
    found = []
    if not isinstance(pin, dict):
        return ["not an object"]

    def fields(value, where, expected):
        if not isinstance(value, dict):
            found.append(f"{where}: not an object")
            return False
        found.extend(f"{where}{'.' if where else ''}{name}: missing" for name in expected if name not in value)
        found.extend(f"unknown field {where}{'.' if where else ''}{name}" for name in value if name not in expected)
        return all(name in value for name in expected)

    def need(ok, what):
        if not ok:
            found.append(what)

    candidate = pin.get("candidate")
    name = CATALOG_CANDIDATE.fullmatch(candidate) if isinstance(candidate, str) else None
    # A release (no -rc.<n>) records its promotion; a candidate has none.
    release = name is not None and name[4] is None
    expected = ("repository", "candidate", "tag_commit", "built_from", "registry", "image", "chart", "schema_version")
    if release:
        expected += ("promotion",)
    elif name is not None and "promotion" in pin:
        # Said once, as what it is, rather than as an unknown field.
        found.append(f"promotion: {candidate!r} is a candidate; only a release promoted from one has a promotion")
        expected += ("promotion",)
    if not fields(pin, "", expected):
        return found
    need(isinstance(pin["repository"], str) and CATALOG_SOURCE.fullmatch(pin["repository"]) is not None,
         f"repository: {pin['repository']!r} is not https://github.com/<owner>/colvenna-catalog")
    need(name is not None, f"candidate: {candidate!r} is not a catalog release, v<major>.<minor>.<patch>[-rc.<n>]")
    for key in ("tag_commit", "built_from"):
        need(isinstance(pin[key], str) and SHA.fullmatch(pin[key]) is not None, f"{key}: not a full commit SHA")
    # The catalog's tag is a commit of its own on top of built_from (release/<candidate>/ added), a
    # release's on top of its candidate's tag commit (release/<release>/ added).
    need(pin["tag_commit"] != pin["built_from"], "tag_commit: the same as built_from, not the tag's commit")
    if release and fields(pin["promotion"], "promotion", CATALOG_PROMOTION):
        promotion = pin["promotion"]
        # Promoted from one of its own candidates: v1.0.0 from v1.0.0-rc.<n>.
        need(isinstance(promotion["candidate"], str) and promotion["candidate"].startswith(f"{candidate}-rc.")
             and CATALOG_CANDIDATE.fullmatch(promotion["candidate"]) is not None,
             f"promotion.candidate: {promotion['candidate']!r} is not one of {candidate}'s candidates,"
             f" {candidate}-rc.<n>")
        for key in ("tag_commit", "acceptance"):
            need(isinstance(promotion[key], str) and SHA.fullmatch(promotion[key]) is not None,
                 f"promotion.{key}: not a full commit SHA")
        need(promotion["tag_commit"] not in (pin["tag_commit"], pin["built_from"]),
             "promotion.tag_commit: the release's tag commit or built_from, not the candidate's tag commit")
        # The candidate's chart, which the release repackaged under its own version: another digest.
        need(isinstance(promotion["chart_digest"], str) and DIGEST.fullmatch(promotion["chart_digest"]) is not None,
             "promotion.chart_digest: not sha256:<64 hex>")
        need(not isinstance(pin["chart"], dict) or promotion["chart_digest"] != pin["chart"].get("digest"),
             "promotion.chart_digest: the release's own chart digest, not its candidate's")
    need(pin["registry"] == CATALOG_REGISTRY, f"registry: {pin['registry']!r}, not {CATALOG_REGISTRY}")
    if fields(pin["image"], "image", ("repository", "digest", "platform")):
        need(pin["image"]["repository"] == CATALOG_REPOSITORIES["image"],
             f"image.repository: {pin['image']['repository']!r}, not {CATALOG_REPOSITORIES['image']}")
        need(isinstance(pin["image"]["digest"], str) and DIGEST.fullmatch(pin["image"]["digest"]) is not None,
             "image.digest: not sha256:<64 hex>")
        need(pin["image"]["platform"] == CATALOG_PLATFORM, f"image.platform: not {CATALOG_PLATFORM}")
    if fields(pin["chart"], "chart", ("repository", "name", "version", "digest")):
        need(pin["chart"]["repository"] == CATALOG_REPOSITORIES["chart"],
             f"chart.repository: {pin['chart']['repository']!r}, not {CATALOG_REPOSITORIES['chart']}")
        need(pin["chart"]["name"] == CATALOG_CHART, f"chart.name: not {CATALOG_CHART}")
        # A catalog release packages its chart as its name without the v (a promoted release as its own).
        need(isinstance(candidate, str) and pin["chart"]["version"] == candidate[1:],
             f"chart.version: {pin['chart']['version']!r}, not the release's {str(candidate)[1:]!r}")
        need(isinstance(pin["chart"]["digest"], str) and DIGEST.fullmatch(pin["chart"]["digest"]) is not None,
             "chart.digest: not sha256:<64 hex>")
    need(type(pin["schema_version"]) is int and pin["schema_version"] > 0,
         f"schema_version: {pin['schema_version']!r} is not a positive integer")
    need(not ACCOUNT_ID.findall(json.dumps(pin)), "an AWS account id: a pin names repositories and digests only")
    return found


def catalog_pin(path=None):
    """The catalog release this tree pins (deploy/images/colvenna-catalog.json); ValueError if it is
    not a valid pin."""
    path = path or CATALOG_PIN
    pin = load(path)
    found = catalog_pin_problems(pin)
    if found:
        raise ValueError(f"{os.path.relpath(path)}: " + "; ".join(found))
    return pin


def catalog_entry(candidate, pin, image=None, chart=None):
    """The manifest's catalog: the pin (a release's promotion too), with the copies of its image and
    chart that the candidate's release registry holds, under the candidate's tag (by default at the
    pin's digests)."""
    promotion = {"promotion": pin["promotion"]} if "promotion" in pin else {}
    return {
        "repository": pin["repository"], "candidate": pin["candidate"], "tag_commit": pin["tag_commit"],
        "built_from": pin["built_from"], **promotion,
        "image": {"repository": f"{PREFIX}/{CATALOG_IMAGES['catalog']}", "tag": candidate,
                  "digest": image or pin["image"]["digest"], "platform": pin["image"]["platform"]},
        "chart": {"repository": f"{PREFIX}/{CATALOG_IMAGES['catalog-chart']}", "tag": candidate,
                  "name": pin["chart"]["name"], "version": pin["chart"]["version"],
                  "digest": chart or pin["chart"]["digest"]},
        "schema_version": pin["schema_version"],
    }


def catalog_check(kind, config, pin):
    """What is not the pinned catalog release's in an image's config (crane config's JSON: its
    platform and labels) or a chart's (Helm's OCI config, Chart.yaml as JSON): built from
    built_from, as its label org.opencontainers.image.revision or its annotation
    colvenna-catalog/source-commit says; the image for linux/amd64, the chart of the pin's name and
    version. An empty list when nothing is."""
    found = []
    if not isinstance(config, dict):
        return [f"the {kind}'s config is not an object"]
    built_from = pin["built_from"]
    if kind == "image":
        platform = f"{config.get('os')}/{config.get('architecture')}"
        if platform != pin["image"]["platform"]:
            found.append(f"the image is for {platform}, not {pin['image']['platform']}")
        labels = (config.get("config") or {}).get("Labels") or {}
        revision = labels.get(CATALOG_REVISION_LABEL)
        if revision != built_from:
            found.append(f"the image's label {CATALOG_REVISION_LABEL} is {revision!r}, not built_from {built_from}")
    elif kind == "chart":
        for key in ("name", "version"):
            if config.get(key) != pin["chart"][key]:
                found.append(f"the chart's {key} is {config.get(key)!r}, not {pin['chart'][key]!r}")
        commit = (config.get("annotations") or {}).get(CATALOG_SOURCE_ANNOTATION)
        if commit != built_from:
            found.append(f"the chart's annotation {CATALOG_SOURCE_ANNOTATION} is {commit!r}, not built_from {built_from}")
    else:
        raise ValueError(f"catalog-check: image or chart, not {kind!r}")
    return found


def catalog_source(registry=None, pin=None):
    """Where a source install takes the pinned catalog release from: (prefix, account, region). The
    prefix is the registry and path its repository names (the pin's image.repository and
    chart.repository) follow: COLVENNA_CATALOG_REGISTRY when given, else the pin's registry. An ECR
    registry's account and region say that it is read with the AWS credentials, as the maintainers'
    private release registry is; any other is read anonymously (account and region None). ValueError if
    the setting is not a registry and prefix, or is an ECR registry outside a standard region."""
    if not registry:
        return (pin or catalog_pin())["registry"], None, None
    found = CATALOG_SOURCE_REGISTRY.fullmatch(registry)
    if found is None:
        raise ValueError(f"COLVENNA_CATALOG_REGISTRY={registry!r}: a registry and prefix the catalog release's"
                         " repositories follow, <registry>[/<prefix>], without a scheme, tag or digest, e.g."
                         " <account>.dkr.ecr.<region>.amazonaws.com/colvenna-catalog-release")
    if ECR_HOST.search(found["host"]):
        ecr = REGISTRY.fullmatch(found["host"])
        if ecr is None:
            raise ValueError(f"COLVENNA_CATALOG_REGISTRY={registry!r}: an ECR registry is"
                             " <account>.dkr.ecr.<region>.amazonaws.com in a standard region")
        return registry, ecr[1], ecr[2]
    return registry, None, None


def catalog_anonymous_problems(prefix, pin=None, timeout=30):
    """What keeps an anonymous reader from the pinned release's image and chart, by digest, in the
    registry and prefix: an empty list when it gets both. Read as a client without credentials reads
    a registry over the OCI distribution API (its HTTPS endpoint): the manifest's HEAD, after the
    registry's Bearer token challenge when it makes one. GitHub Container Registry gives public
    images to anonymous readers ("You can also access public container images anonymously",
    docs.github.com, Working with the Container registry)."""
    pin = pin or catalog_pin()
    host, _, namespace = catalog_source(prefix, pin)[0].partition("/")
    found = []
    for kind in ("image", "chart"):
        repository, digest = pin[kind]["repository"], pin[kind]["digest"]
        problem = anonymous_manifest_problem(f"https://{host}", "/".join(p for p in (namespace, repository) if p),
                                             digest, timeout)
        if problem:
            found.append(f"{prefix}/{repository}@{digest}, the {kind}: {problem}")
    return found


def anonymous_manifest_problem(base, repository, digest, timeout):
    """None if the registry at base gives an anonymous reader the manifest repository@digest, else
    what it answered."""
    url = f"{base}/v2/{repository}/manifests/{digest}"
    accept = {"Accept": ", ".join(MANIFEST_TYPES)}

    def head(headers):
        return urllib.request.urlopen(urllib.request.Request(url, headers=headers, method="HEAD"), timeout=timeout)

    def refused(error):
        try:
            code = json.loads(error.read() or b"{}")["errors"][0]["code"]
        except (ValueError, KeyError, IndexError, TypeError):
            code = error.reason
        said = {401: "refused to an anonymous reader", 403: "refused to an anonymous reader",
                404: "not there"}.get(error.code, "")
        return f"HTTP {error.code} {code}" + (f": {said}" if said else "")

    try:
        try:
            response = head(accept)
        except urllib.error.HTTPError as error:
            challenge = error.headers.get("WWW-Authenticate", "")
            if error.code != 401 or not challenge.lower().startswith("bearer "):
                raise
            # The registry's anonymous token for pulling the repository, as its challenge says.
            params = dict(re.findall(r'(\w+)="([^"]*)"', challenge))
            realm = params.get("realm", "")
            if urllib.parse.urlsplit(realm).scheme != "https":
                return f"HTTP 401: a token challenge without an HTTPS realm ({challenge})"
            query = {key: params[key] for key in ("service", "scope") if key in params}
            query.setdefault("scope", f"repository:{repository}:pull")
            token_url = f"{realm}{'&' if '?' in realm else '?'}{urllib.parse.urlencode(query)}"
            with urllib.request.urlopen(token_url, timeout=timeout) as answer:
                body = json.load(answer)
            token = (body.get("token") or body.get("access_token")) if isinstance(body, dict) else None
            if not token:
                return "the registry's token endpoint gave no token"
            response = head({**accept, "Authorization": f"Bearer {token}"})
    except urllib.error.HTTPError as error:
        return refused(error)
    except (urllib.error.URLError, http.client.HTTPException, OSError, ValueError) as error:
        return f"cannot read it: {getattr(error, 'reason', None) or error!r}"
    with response:
        answered = response.headers.get("Docker-Content-Digest")
    if answered and answered != digest:
        return f"it answers {answered}"
    return None


def source_tag(candidate):
    return f"{candidate} points at built_from with release/{candidate}/ added, nothing else"


def build(inputs):
    """The manifest from assemble's inputs; ValueError if it is not a valid one."""
    candidate, digests = inputs["candidate"], inputs["digests"]

    def image(repository, variant, digest):
        return {"repository": f"{PREFIX}/{repository}", "tag": tag(candidate, variant), "digest": digest}

    def pinned(images):
        return {key: image(repository, variant, digests[key])
                for key, (repository, variant) in images.items() if key in digests}

    def third_party(scope):
        """Each as the release registry holds it (digests: what make-manifest.sh copied), beside the
        upstream reference it was pinned by."""
        return {name: {"upstream": upstream, **image(THIRD_PARTY_REPOSITORY, name, digests[name])}
                for name, upstream in third_party_scope(scope).items() if name in digests}

    manifest = {
        "schema": SCHEMA,
        "candidate": candidate,
        "source": {"repository": SOURCE, "built_from": inputs["built_from"], "tag": source_tag(candidate)},
        "generated": inputs["generated"],
        "images": pinned(PRODUCT_IMAGES),
        "fixtures": {"images": pinned(FIXTURE_IMAGES), "third_party": third_party("fixtures")},
        "cli": {**image(*CLI, inputs["cli"]["digest"]), "binaries": inputs["cli"]["binaries"]},
        "charts": inputs["charts"],
        "catalog": catalog_entry(candidate, inputs["catalog"], digests.get("catalog"), digests.get("catalog-chart")),
        "installer": inputs["installer"],
        "uat_cases": inputs["uat_cases"],
        "required_cases": inputs["required_cases"],
        "configurations": {"install": list(INSTALL_MODES), "regions": inputs["regions"]},
        "performance": RELEASE_PERFORMANCE,
        "third_party": third_party("platform"),
    }
    found = problems(manifest)
    if found:
        raise ValueError("; ".join(found))
    return manifest


def assemble(work):
    """The manifest from what make-manifest.sh gathered in the directory work, at the candidate's
    commit:
      params.json     candidate, built_from, generated, installer, uat_cases, regions, cli_digest
      digests         <key>=<digest> per image in the release registry, the third-party ones too
      charts/<chart>  each chart's Chart.yaml, and trees: <chart>=<git tree>
      cases.md        docs/uat/cases.md, whose required cases the manifest lists
      catalog.json    deploy/images/colvenna-catalog.json, the catalog release the commit pins
      cli/<binary>    the colvenna CLI's binaries the release registry holds
    """
    def path(*parts):
        return os.path.join(work, *parts)

    def lines(name):
        return read(path(name)).splitlines()

    params = json.loads(read(path("params.json")))

    def chart(name, tree):
        text = read(path("charts", name))

        def field(key):
            found = re.search(rf"^{key}:\s*\"?([^\"\n]+?)\"?\s*$", text, re.M)
            return found[1] if found else ""
        return {"path": f"deploy/helm/{name}", "version": field("version"), "appVersion": field("appVersion"),
                "tree": tree}

    return build({
        "candidate": params["candidate"],
        "built_from": params["built_from"],
        "generated": params["generated"],
        "digests": dict(line.split("=", 1) for line in lines("digests")),
        "cli": {"digest": params["cli_digest"],
                "binaries": {b: sha256(path("cli", b)) for b in CLI_BINARIES}},
        "charts": {name: chart(name, tree) for name, tree in (line.split("=", 1) for line in lines("trees"))},
        "catalog": load(path("catalog.json")),
        "installer": params["installer"],
        "uat_cases": params["uat_cases"],
        "required_cases": required_cases(read(path("cases.md"))),
        "regions": params["regions"],
    })


def problems(m, candidate=None):
    """What is wrong with a manifest: an empty list when nothing is."""
    found = []

    def need(ok, what):
        if not ok:
            found.append(what)

    def fields(value, where, expected):
        """Whether value is an object with exactly those fields; each one missing or extra is a problem."""
        if not isinstance(value, dict):
            found.append(f"{where or 'the manifest'}: not an object")
            return False
        prefix = f"{where}." if where else ""
        for name in expected:
            need(name in value, f"{prefix}{name}: missing")
        for name in value:
            need(name in expected, f"unknown field {prefix}{name}")
        return all(name in value for name in expected)

    def text(value, where, pattern=None):
        """A non-empty string, matching the pattern if one is given."""
        need(isinstance(value, str) and value != "" and (pattern is None or pattern.fullmatch(value) is not None),
             f"{where}: {value!r} is not {'a non-empty string' if pattern is None else pattern.pattern}")

    def sha(value, where):
        need(isinstance(value, str) and SHA.fullmatch(value) is not None, f"{where}: not a full commit SHA")

    def count(value, where):
        """A positive integer (not a boolean)."""
        need(type(value) is int and value > 0, f"{where}: {value!r} is not a positive integer")

    def accounts():
        ids = sorted(set(ACCOUNT_ID.findall(json.dumps(m))))
        need(not ids, f"{len(ids)} AWS account ids: a manifest names repositories and digests, and the"
             " release registry is the installer's COLVENNA_RELEASE_REGISTRY")

    if not fields(m, "", FIELDS):
        if isinstance(m, dict):
            accounts()
        return found
    need(type(m["schema"]) is int and m["schema"] == SCHEMA, f"schema: {m['schema']!r}, not {SCHEMA}")
    name = m["candidate"]
    need(isinstance(name, str) and CANDIDATE.fullmatch(name) is not None,
         f"candidate: {name!r} is not v<major>.<minor>.<patch>-rc.<n> (or -dev.<n>)")
    if candidate is not None:
        need(name == candidate, f"candidate: the manifest is {name!r}'s, not {candidate}'s")
    if fields(m["source"], "source", ("repository", "built_from", "tag")):
        need(m["source"]["repository"] == SOURCE, f"source.repository: not {SOURCE}")
        sha(m["source"]["built_from"], "source.built_from")
        need(m["source"]["tag"] == source_tag(name), f"source.tag: not {source_tag(name)!r}")
    # Who made it, and where, is the evidence's (make-manifest.sh's), not the manifest's.
    if fields(m["generated"], "generated", ("at", "environment", "region")):
        text(m["generated"]["at"], "generated.at", UTC)
        text(m["generated"]["environment"], "generated.environment", ENVIRONMENT)
        text(m["generated"]["region"], "generated.region", REGION)

    def image(value, where, repository, variant, extra=()):
        if not fields(value, where, ("repository", "tag", "digest", *extra)):
            return False
        need(value["repository"] == f"{PREFIX}/{repository}", f"{where}.repository: not {PREFIX}/{repository}")
        need(value["tag"] == tag(name, variant), f"{where}.tag: not {tag(name, variant)}")
        need(isinstance(value["digest"], str) and DIGEST.fullmatch(value["digest"]) is not None,
             f"{where}.digest: not sha256:<64 hex>")
        return True

    def images(value, where, expected):
        if fields(value, where, tuple(expected)):
            for key, (repository, variant) in expected.items():
                image(value[key], f"{where}.{key}", repository, variant)

    def third_party(value, where, expected):
        """The list's images of a scope, each as pinned there: its upstream reference, and that digest."""
        if fields(value, where, tuple(expected)):
            for key, upstream in expected.items():
                if image(value[key], f"{where}.{key}", THIRD_PARTY_REPOSITORY, key, extra=("upstream",)):
                    need(value[key]["upstream"] == upstream,
                         f"{where}.{key}.upstream: not {upstream}, as deploy/images/third-party.tsv pins it")
                    need(value[key]["digest"] == third_party_digest(upstream),
                         f"{where}.{key}.digest: not {third_party_digest(upstream)}, the digest"
                         " deploy/images/third-party.tsv pins")

    pinned = third_party_images()
    images(m["images"], "images", PRODUCT_IMAGES)
    third_party(m["third_party"], "third_party", third_party_scope("platform", pinned))
    if fields(m["fixtures"], "fixtures", ("images", "third_party")):
        images(m["fixtures"]["images"], "fixtures.images", FIXTURE_IMAGES)
        third_party(m["fixtures"]["third_party"], "fixtures.third_party", third_party_scope("fixtures", pinned))
    if image(m["cli"], "cli", *CLI, extra=("binaries",)):
        binaries = m["cli"]["binaries"]
        if fields(binaries, "cli.binaries", CLI_BINARIES):
            for binary in CLI_BINARIES:
                text(binaries[binary], f"cli.binaries.{binary}", SHA256)
    if fields(m["charts"], "charts", CHARTS):
        for chart in CHARTS:
            value = m["charts"][chart]
            if fields(value, f"charts.{chart}", ("path", "version", "appVersion", "tree")):
                need(value["path"] == f"deploy/helm/{chart}", f"charts.{chart}.path: not deploy/helm/{chart}")
                text(value["version"], f"charts.{chart}.version")
                text(value["appVersion"], f"charts.{chart}.appVersion")
                sha(value["tree"], f"charts.{chart}.tree")
    # The catalog release: the tree's pin, its copies in the release registry under the candidate's tag.
    try:
        pin = catalog_pin()
    except (ValueError, OSError) as error:
        found.append(f"catalog: the tree's pin: {error}")
    else:
        def same(value, expected, where):
            if isinstance(expected, dict):
                if fields(value, where, tuple(expected)):
                    for key in expected:
                        same(value[key], expected[key], f"{where}.{key}")
            else:
                need(value == expected and type(value) is type(expected),
                     f"{where}: {value!r}, not {expected!r} (deploy/images/colvenna-catalog.json, the candidate)")
        same(m["catalog"], catalog_entry(name, pin), "catalog")
    if fields(m["installer"], "installer", ("install", "uninstall")):
        for script in ("install", "uninstall"):
            if fields(m["installer"][script], f"installer.{script}", ("path", "blob")):
                need(m["installer"][script]["path"] == f"deploy/{script}.sh",
                     f"installer.{script}.path: not deploy/{script}.sh")
                sha(m["installer"][script]["blob"], f"installer.{script}.blob")
    if fields(m["uat_cases"], "uat_cases", ("path", "commit", "blob")):
        need(m["uat_cases"]["path"] == "docs/uat/cases.md", "uat_cases.path: not docs/uat/cases.md")
        sha(m["uat_cases"]["commit"], "uat_cases.commit")
        sha(m["uat_cases"]["blob"], "uat_cases.blob")
    cases = m["required_cases"]
    need(isinstance(cases, list) and len(cases) > 0
         and all(isinstance(c, str) and CASE.fullmatch(c) for c in cases)
         and len(set(cases)) == len(cases),
         "required_cases: not a non-empty list of distinct UAT-<AREA>-<nn> ids")
    if fields(m["configurations"], "configurations", ("install", "regions")):
        modes = m["configurations"]["install"]
        need(isinstance(modes, list) and len(modes) > 0 and all(isinstance(mode, str) for mode in modes)
             and set(modes) <= set(INSTALL_MODES),
             f"configurations.install: not some of {', '.join(INSTALL_MODES)}")
        found.extend(f"configurations.{p}" for p in region_problems(m["configurations"]["regions"]))
    performance = m["performance"]
    if fields(performance, "performance", tuple(RELEASE_PERFORMANCE)):
        text(performance["tier"], "performance.tier")
        for key in ("write_nodes", "query_nodes", "cdc_changes_per_second", "kafka_events_per_second",
                    "dashboard_qps", "steady_hours"):
            count(performance[key], f"performance.{key}")
        need(isinstance(performance["latency_percentiles"], list) and len(performance["latency_percentiles"]) > 0
             and all(isinstance(p, str) and re.fullmatch(r"p[0-9]{2}", p) for p in performance["latency_percentiles"]),
             "performance.latency_percentiles: not a list of p<nn>")
        for key in ("latency_targets", "method"):
            text(performance[key], f"performance.{key}", re.compile(r"docs/[^\s#]+\.md(#\S+)?"))
        need(performance == RELEASE_PERFORMANCE,
             f"performance: not the release's tier, {json.dumps(RELEASE_PERFORMANCE, ensure_ascii=False)}")
    accounts()
    return found


def region_problems(regions):
    """What is wrong with the regions a candidate is accepted in: two or more distinct standard ones."""
    if not (isinstance(regions, list) and all(isinstance(r, str) for r in regions)):
        return [f"regions: {regions!r} is not a list of region names"]
    bad = [r for r in regions if REGION.fullmatch(r) is None]
    if bad:
        return [f"regions: not standard AWS regions: {', '.join(bad)}"]
    if len(set(regions)) != len(regions) or len(regions) < 2:
        return [f"regions: {', '.join(regions) or 'none'}: not two or more distinct regions"]
    return []


def required_cases(text):
    """The case IDs in the first column of the table between the markers, in order. Every row
    there but the table's header and its rule must begin with a case ID: no row is skipped."""
    if text.count(START) != 1 or text.count(END) != 1:
        raise ValueError(f"the cases need {START} and {END}, once each, around the required cases' table")
    begin, end = text.index(START), text.index(END)
    if end < begin:
        raise ValueError(f"{END} comes before {START}")
    # A row's first cell: UAT-CAT-01, `UAT-CAT-01` or [UAT-CAT-01](#...).
    first = re.compile(r"\[?`?(" + CASE.pattern + r")`?\]?(\([^)]*\))?")
    rows = [line.strip() for line in text[begin + len(START):end].splitlines() if line.strip().startswith("|")]
    cells = [row.strip("|").split("|") for row in rows]
    rule = [all(re.fullmatch(r"\s*:?-{3,}:?\s*", cell) for cell in row) for row in cells]
    cases = []
    for i, row in enumerate(cells):
        if rule[i] or (i + 1 < len(cells) and rule[i + 1]):
            continue
        found = first.fullmatch(row[0].strip())
        if not found:
            raise ValueError(f"between the required-cases markers, a row that names no case: {rows[i]}")
        if found[1] in cases:
            raise ValueError(f"{found[1]} is listed twice among the required cases")
        cases.append(found[1])
    if not cases:
        raise ValueError(f"no case between {START} and {END}")
    return cases


def notes(m, template):
    """The release notes' draft: the template with the candidate's identity, its regions and its
    performance tier filled in from the manifest."""
    regions = "、".join(f"`{r}`" for r in m["configurations"]["regions"])
    p = m["performance"]
    performance = "\n".join([
        "| 项 | 值 |",
        "| --- | --- |",
        f"| 规模 | {p['tier']}：写入节点 {p['write_nodes']} 台、查询节点 {p['query_nodes']} 台 |",
        f"| 负载 | CDC {p['cdc_changes_per_second']:,} 变更/秒、Kafka {p['kafka_events_per_second']:,} 条/秒、"
        f"看板 {p['dashboard_qps']} QPS |",
        f"| 持续 | 稳态 {p['steady_hours']} 小时 |",
        f"| 目标 | 各链路的 {'/'.join(p['latency_percentiles'])}，见 [`{p['latency_targets'].split('#')[0]}`]"
        f"({SOURCE}/blob/{m['candidate']}/{p['latency_targets']}) |",
        f"| 方法 | [`{p['method']}`]({SOURCE}/blob/{m['candidate']}/{p['method']}) |",
    ])
    values = {"candidate": m["candidate"], "built_from": m["source"]["built_from"],
              "built_from_short": m["source"]["built_from"][:8], "date": m["generated"]["at"][:10],
              "regions": regions, "performance": performance}
    return re.sub(r"\{\{(\w+)\}\}", lambda found: values[found[1]], template)


def sha256(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def load(path):
    try:
        return json.loads(read(path))
    except json.JSONDecodeError as error:
        raise ValueError(f"{path}: not JSON ({error})") from None


def git(repo, *args):
    """git's output, or None when it fails (an object that is not there)."""
    out = subprocess.run(["git", "-C", repo, *args], capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else None


def tree_problems(m, repo, commit):
    """Where the commit does not hold the git objects a valid candidate's manifest pins: each chart's
    directory (charts.<chart>.tree), deploy/install.sh and deploy/uninstall.sh (installer), and the UAT
    cases with the commit they last changed in (uat_cases). install.sh --release and promote.sh run from
    a checkout of the candidate's tag, which must hold them."""
    pinned = [(f"charts.{chart}.tree", value["path"], value["tree"]) for chart, value in m["charts"].items()]
    pinned += [(f"installer.{script}.blob", value["path"], value["blob"]) for script, value in m["installer"].items()]
    pinned.append(("uat_cases.blob", m["uat_cases"]["path"], m["uat_cases"]["blob"]))
    found = []
    for where, path, want in pinned:
        have = git(repo, "rev-parse", "-q", "--verify", f"{commit}:{path}")
        if have != want:
            found.append(f"{where}: {path} is {have or 'not there'} at {commit}, not the manifest's {want}")
    cases = m["uat_cases"]
    last = git(repo, "log", "-1", "--format=%H", commit, "--", cases["path"])
    if last != cases["commit"]:
        found.append(f"uat_cases.commit: {cases['path']} last changed in {last or 'no commit'} up to {commit}, not"
                     f" the manifest's {cases['commit']}")
    return found


def promotion_module():
    """promotion.py, which defines a release's manifest. It imports this module, so it is imported here only
    when a release's manifest is read."""
    import promotion
    return promotion


def is_release(m):
    """Whether the manifest is a release's (promotion.py: it has release where a candidate's has candidate)."""
    return isinstance(m, dict) and "release" in m


def added(m):
    """The directories a valid manifest's tag adds to the commit it was built from: release/<candidate>; a
    release's tag adds release/<release> to its candidate's."""
    if is_release(m):
        return [f"release/{m['promotion']['candidate']}", f"release/{m['release']}"]
    return [f"release/{m['candidate']}"]


def installed_tree_problems(m, repo, commit):
    """Where the commit is not what a valid manifest, a candidate's or a release's, describes."""
    return promotion_module().tree_problems(m, repo, commit) if is_release(m) else tree_problems(m, repo, commit)


def valid(path, name=None):
    """The manifest at path, a candidate's or a release's (of that name, if given); ValueError if it is not
    valid."""
    m = load(path)
    found = promotion_module().problems(m, name) if is_release(m) else problems(m, name)
    if found:
        raise ValueError(f"{path}:\n  " + "\n  ".join(found))
    return m


def doris_source(release, fe, be):
    """The query engine's repository a source install copies from by default (doris/copy-images.sh),
    the release registry's: that of the newest manifest under <release> (by when it was made) whose
    query engine FE and BE are these digests in colvenna-lake-release/query-engine (the registry is
    COLVENNA_RELEASE_REGISTRY); None when no manifest pins them. The manifests of the product's former
    names are passed over, their own tags' tools read them: v1.0.0-rc.1's (schema 1) names its FE
    and BE doris-fe and doris-be, and v1.0.0-rc.2's, with the same digests, puts them in a
    repository of the former prefix (release/trademarks.md, section 7)."""
    fe_key, be_key = QUERY_ENGINE
    repository = f"{PREFIX}/{PRODUCT_IMAGES[fe_key][0]}"
    found = []
    for path in glob.glob(os.path.join(release, "*", "manifest.json")):
        m = load(path)
        images = m.get("images", {})
        if all(images.get(key, {}).get("digest") == digest and images.get(key, {}).get("repository") == repository
               for key, digest in ((fe_key, fe), (be_key, be))):
            found.append((m.get("generated", {}).get("at", ""), m.get("candidate") or m.get("release"), repository))
    return max(found)[2] if found else None


def registry_parts(registry):
    """(account, region) of an ECR registry in a standard region; ValueError if it is not one."""
    found = REGISTRY.fullmatch(registry)
    if found is None:
        raise ValueError(f"COLVENNA_RELEASE_REGISTRY={registry!r}: the release registry,"
                         " <account>.dkr.ecr.<region>.amazonaws.com in a standard region")
    return found[1], found[2]


# Where an environment's managed add-ons and agents run, whose images AWS gives from its own ECR
# registries (EKS's VPC CNI, kube-proxy, CoreDNS, the EBS CSI driver; GuardDuty's runtime agent).
AWS_NAMESPACES = ("kube-system", "amazon-guardduty")


def running(workloads, own):
    """(workload, container, image, aws|upstream) of every container of the workloads and pods
    (kubectl get ... -o json) whose image is not in the environment's own repositories, own being
    <registry>/colvenna-lake-<env>: aws for an image of an ECR registry of another account in one of
    AWS's namespaces, upstream for any other, another repository of the same registry included (the
    release registry's colvenna-lake-release/..., another environment's): what the environment runs is
    its own copy, which its uninstall removes with it and nothing else does. A pod
    that has finished (Succeeded or Failed) is not counted: it runs nothing and is never started
    again (a Job's or a test's next run is a new pod, of the images its scripts name then), while
    what can still start a container (a workload's template, a pod pending or running) is."""
    own = own.rstrip("/")
    account = own.split(".", 1)[0]
    rows = set()
    for item in workloads["items"]:
        meta = item["metadata"]
        if item["kind"] == "Pod" and item.get("status", {}).get("phase") in ("Succeeded", "Failed"):
            continue
        spec = item["spec"] if item["kind"] == "Pod" else item["spec"]["template"]["spec"]
        for c in spec.get("initContainers", []) + spec["containers"]:
            if c["image"].startswith(own + "/"):
                continue
            ecr = REGISTRY.fullmatch(c["image"].split("/", 1)[0])
            aws = ecr is not None and ecr[1] != account and meta["namespace"] in AWS_NAMESPACES
            rows.add((f"{meta['namespace']}/{item['kind']}/{meta['name']}", c.get("name", ""), c["image"],
                      "aws" if aws else "upstream"))
    return sorted(rows)


def main(argv):
    command, args = (argv[0], argv[1:]) if argv else ("", [])
    if command == "assemble" and len(args) == 1:
        print(json.dumps(assemble(args[0]), indent=2, ensure_ascii=False))
    elif command == "validate" and len(args) in (1, 3) and (len(args) == 1 or args[1] == "--name"):
        valid(args[0], args[2] if len(args) == 3 else None)
    elif command == "check-tree" and len(args) == 3:
        found = installed_tree_problems(valid(args[0]), args[1], args[2])
        if found:
            raise ValueError(f"{args[0]}:\n  " + "\n  ".join(found))
    elif command == "kind" and len(args) == 1:
        print(kind(args[0]))
    elif command == "added" and len(args) == 1:
        print("\n".join(added(valid(args[0]))))
    elif command == "names" and len(args) == 1:
        kind(args[0])
        for row in names(args[0]):
            print("\t".join(row))
    elif command == "regions" and len(args) == 1:
        found = region_problems(args[0].split(","))
        if found:
            raise ValueError("; ".join(found))
        print("\n".join(args[0].split(",")))
    elif command == "registry" and len(args) == 1:
        print("\t".join(registry_parts(args[0])))
    elif command == "binaries" and not args:
        print("\n".join(CLI_BINARIES))
    elif command == "stack" and not args:
        print(STACK)
    elif command == "third-party" and args in ([], ["--fixtures"]):
        for name, (scope, upstream) in third_party_images().items():
            if scope == "platform" or args:
                print("\t".join((name, upstream, third_party_digest(upstream), third_party_tag(name, upstream))))
    elif command == "images" and args and set(args[1:]) <= {"--fixtures", "--cli"}:
        for key, image in images(valid(args[0]), "--fixtures" in args, "--cli" in args).items():
            print("\t".join((key, image["repository"].removeprefix(f"{PREFIX}/"), image["tag"], image["digest"])))
    elif command == "running" and len(args) >= 2:
        items = [item for path in args[:-1] for item in load(path)["items"]]
        for row in running({"items": items}, args[-1]):
            print("\t".join(row))
    elif command == "get" and len(args) == 2:
        value = load(args[0])
        for field in args[1].split("."):
            if not isinstance(value, dict) or field not in value:
                raise ValueError(f"{args[0]}: no {args[1]}")
            value = value[field]
        print(value if isinstance(value, str) else json.dumps(value))
    elif command == "doris-source" and len(args) == 3:
        source = doris_source(*args)
        if source:
            print(source)
    elif command == "required-cases" and len(args) == 1:
        print("\n".join(required_cases(read(args[0]))))
    elif command == "notes" and len(args) == 1:
        m = valid(args[0])
        if is_release(m):
            raise ValueError(f"{args[0]} is a release's: its notes are its candidate's, which promote.sh rewrites"
                             " (promotion.py notes)")
        print(notes(m, sys.stdin.read()), end="")
    elif command == "catalog" and len(args) <= 1:
        value = catalog_pin()
        for field in args[0].split(".") if args else []:
            if not isinstance(value, dict) or field not in value:
                raise ValueError(f"deploy/images/colvenna-catalog.json: no {args[0]}")
            value = value[field]
        print(value if isinstance(value, str) else json.dumps(value, indent=None if args else 2))
    elif command == "catalog-source" and len(args) <= 1:
        prefix, account, region = catalog_source(args[0] if args else None)
        print("\t".join([prefix] + ([account, region] if account else [])))
    elif command == "catalog-held" and len(args) == 1:
        found = catalog_anonymous_problems(args[0])
        if found:
            raise ValueError("; ".join(found))
    elif command == "catalog-check" and len(args) == 2:
        found = catalog_check(args[0], load(args[1]), catalog_pin())
        if found:
            raise ValueError("not the pinned catalog release " + catalog_pin()["candidate"] + "'s: " + "; ".join(found))
    else:
        raise ValueError(__doc__.split("usage:\n", 1)[1].split("Problems", 1)[0].rstrip())


if __name__ == "__main__":
    try:
        main(sys.argv[1:])
    except (ValueError, OSError, KeyError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)

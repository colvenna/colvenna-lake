#!/usr/bin/env bash
# Promotes release candidate <candidate>, judged GO, to the release <release>, its version without
# -rc.<n> (v1.0.0-rc.7 to v1.0.0), rebuilding nothing (release/README.md, "晋升"), as Colvenna Catalog's
# promote.sh does:
#   0. checks, before it reads anything from a registry: HEAD is the candidate's tag commit, C, and is
#      the tag's checkout as install.sh --release checks one (lib/release.sh's release_checkout): clean,
#      its manifest valid and pinning C's git objects, C differing from the commit the candidate was
#      built from, B (source.built_from), under release/<candidate>/ alone; the candidate's release
#      notes at RELEASE_RECORD (a commit that has C, default origin/main) give the release verdict GO,
#      beside the same manifest as C's (promotion.py verdict); and neither the tag <release>, the
#      branch release-<release> nor release/<release>/ exists;
#   1. checks what the release registry holds, before it writes anything there: every image the
#      manifest names (the product's, the fixtures', the third-party ones, the colvenna CLI's and the
#      copies of the Colvenna Catalog release's image and chart) is under its candidate's tag with the
#      manifest's digest, and its release tag (<release>, <release>-<variant>, a third-party one
#      <release>-<name>) holds nothing or that same digest: tags never move. Then it makes, in its
#      scratch directory, release/<release>/manifest.json (promotion.py assemble: the candidate's
#      manifest under the release's names, the same digests, and promotion, where it came from) and
#      notes.md (the candidate's release notes as accepted, under the release's name);
#   2. tags each image with its release tag (crane tag: the same manifest under a second tag; nothing is
#      copied or built), then reads every release tag back;
#   3. commits the manifest and the notes alone on C on the branch release-<release>, and tags that
#      commit <release> (annotated). It pushes nothing to git.
# No Helm chart is re-versioned or repackaged: this repository's four are installed from the tree and
# pinned by their directories' git trees, which the release's tag keeps as C's; the catalog's is
# Colvenna Catalog's release, pinned by digest (release/README.md, "Helm chart 的版本").
# The release registry is COLVENNA_RELEASE_REGISTRY or, unset, the caller's account's in
# COLVENNA_RELEASE_REGION (default us-west-2), as make-manifest.sh's; never AWS ECR Public. A loopback
# registry (127.0.0.1:<port> or localhost:<port>) stands in for it in a rehearsal, as in the offline
# tests: the commit and the tag then say they are a rehearsal's, never to be pushed; rehearse in a clone
# of its own. A run that stopped can run again: a release tag that already holds the candidate's digest
# is kept. A run that stopped after it wrote release/<release>/ into the tree says how to remove it.
#
# Usage: [COLVENNA_RELEASE_REGISTRY=<registry>] [RELEASE_RECORD=<commit>] \
#          deploy/scripts/release/promote.sh <candidate> <release>
#   RELEASE_RECORD    the commit whose release/<candidate>/notes.md records the verdict (default
#                     origin/main: git fetch origin first)
#   RELEASE_EVIDENCE  where the evidence goes (default deploy/.state/release/<release>/, git-ignored)
# Exit status: 0 promoted; 1 a step failed; 2 the arguments, a setting, the tree or the candidate is
# wrong (a candidate not judged GO among them); 3 a tool is missing, or what the release registry holds
# is not the candidate's.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../../.." && pwd)"
REPO_DIR="$REPO"
# shellcheck source-path=SCRIPTDIR source=../lib/git.sh
source "$REPO/deploy/scripts/lib/git.sh"
# shellcheck source-path=SCRIPTDIR source=../lib/release.sh
source "$REPO/deploy/scripts/lib/release.sh"
manifest_py="$REPO/deploy/scripts/release/manifest.py"
promotion_py="$REPO/deploy/scripts/release/promotion.py"
# The release registry's repositories (manifest.py's PREFIX): the manifest names each one under it.
prefix=colvenna-lake-release
# How to remove what step 3 has written into the tree, once it has: fail says so.
undo=""

fail() {
  echo "promote.sh: $1" >&2
  [[ -z "$undo" ]] || echo "promote.sh: remove what this run wrote into the tree: $undo" >&2
  exit "${2:-1}"
}

[[ $# -eq 2 ]] || fail "usage: [COLVENNA_RELEASE_REGISTRY=<registry>] [RELEASE_RECORD=<commit>] $0 <candidate> <release>" 2
candidate="$1" release="$2"
names="$(python3 "$promotion_py" names "$candidate" "$release")" || exit 2
if [[ -n "${COLVENNA_RELEASE_REGISTRY:-}" ]]; then
  registry_kind="$(python3 "$promotion_py" registry "$COLVENNA_RELEASE_REGISTRY")" || exit 2
else
  registry_kind=ecr
  release_region="${COLVENNA_RELEASE_REGION:-us-west-2}"
  # A standard region, as deploy/env.sh's standard_region says (env.sh needs an environment).
  [[ "$release_region" =~ ^[a-z]{2}-[a-z]+-[1-9]$ && "$release_region" != cn-* ]] \
    || fail "COLVENNA_RELEASE_REGION: a standard AWS region, e.g. us-west-2, not '$release_region'" 2
fi

# 0. The tree, the candidate and its verdict.
head="$(git -C "$REPO" rev-parse HEAD)"
tag_commit="$(git -C "$REPO" rev-parse -q --verify "refs/tags/$candidate^{commit}")" \
  || fail "No tag $candidate in this clone: git fetch origin --tags" 2
[[ "$head" == "$tag_commit" ]] || fail "HEAD is $head, not $candidate's tag commit $tag_commit: git checkout $candidate" 2
! git -C "$REPO" rev-parse -q --verify "refs/tags/$release" >/dev/null \
  || fail "The tag $release exists already: a release is promoted once" 2
! git -C "$REPO" rev-parse -q --verify "refs/heads/release-$release" >/dev/null \
  || fail "The branch release-$release exists already: remove it, or finish the promotion it holds" 2
! git -C "$REPO" cat-file -e "HEAD:release/$release" 2>/dev/null || fail "release/$release is already at $head" 2
release_checkout "$candidate" || fail "$candidate's tag commit is not the checkout its manifest describes" 2
manifest="$RELEASE_MANIFEST" built_from="$RELEASE_BUILT_FROM"
record_ref="${RELEASE_RECORD:-origin/main}"
record="$(git -C "$REPO" rev-parse -q --verify "$record_ref^{commit}")" \
  || fail "RELEASE_RECORD: no commit $record_ref in this clone (git fetch origin)" 2
git -C "$REPO" merge-base --is-ancestor "$head" "$record" \
  || fail "RELEASE_RECORD $record_ref ($record) does not have $candidate's tag commit $head" 2
manifest_blob="$(git -C "$REPO" rev-parse "HEAD:release/$candidate/manifest.json")"
[[ "$(git -C "$REPO" rev-parse -q --verify "$record:release/$candidate/manifest.json" || true)" == "$manifest_blob" ]] \
  || fail "release/$candidate/manifest.json at $record_ref is not the one $candidate's tag holds" 2
notes_blob="$(git -C "$REPO" rev-parse -q --verify "$record:release/$candidate/notes.md")" \
  || fail "No release/$candidate/notes.md at $record_ref" 2

work="$(mktemp -d)"
chmod 700 "$work"
# This run's own registry credentials, removed with it.
export DOCKER_CONFIG="$work/docker"
trap 'rm -rf "$work"' EXIT
mkdir -p "$DOCKER_CONFIG"
git -C "$REPO" show "$record:release/$candidate/notes.md" >"$work/candidate-notes.md"
python3 "$promotion_py" verdict "$work/candidate-notes.md" >/dev/null \
  || fail "$candidate is not judged GO in release/$candidate/notes.md at $record_ref ($record)" 2

export PATH="$REPO/deploy/.tools:$PATH"
command -v crane >/dev/null || fail "crane is required: deploy/scripts/foundation/install-crane.sh puts it in deploy/.tools" 3
[[ "$registry_kind" != ecr ]] || command -v aws >/dev/null || fail "The AWS CLI (aws) is required for an ECR registry" 3
evidence="${RELEASE_EVIDENCE:-$REPO/deploy/.state/release/$release}"
mkdir -p "$evidence"
log() {
  echo "$*" | tee -a "$evidence/promote.log"
}
if [[ -n "${COLVENNA_RELEASE_REGISTRY:-}" ]]; then
  registry="$COLVENNA_RELEASE_REGISTRY"
else
  account="$(aws sts get-caller-identity --query Account --output text)" || fail "Cannot read the caller's AWS account"
  registry="$account.dkr.ecr.$release_region.amazonaws.com"
fi
if [[ "$registry_kind" == ecr ]]; then
  caller="$(aws sts get-caller-identity --query Arn --output text)" || fail "Cannot read the caller's AWS identity"
  log "by $caller"
  parts="$(python3 "$manifest_py" registry "$registry")" || fail "$registry is not an ECR registry of a standard region" 2
  aws ecr get-login-password --region "${parts#*$'\t'}" \
    | crane auth login "$registry" -u AWS --password-stdin >>"$evidence/login.log" 2>&1 \
    || fail "Cannot log in to $registry ($evidence/login.log)"
fi
rehearsal=""
[[ "$registry_kind" != loopback ]] \
  || rehearsal="a rehearsal against the loopback registry $registry, not a release: never push it"
log "$(date -u +%FT%TZ) $candidate ($tag_commit, built from $built_from) to $release in $registry;" \
  "verdict GO in release/$candidate/notes.md at $record${rehearsal:+; $rehearsal}"

# still_this_commit: the tree is still C's, clean.
still_this_commit() {
  [[ -z "$(git -C "$REPO" status --porcelain)" && "$(git -C "$REPO" rev-parse HEAD)" == "$head" ]] \
    || fail "The tree moved from $head during the run: promote again"
}

# registry_held <reference>: the digest the registry holds under the reference's tag, or nothing when it
# holds none (the distribution API's MANIFEST_UNKNOWN or NAME_UNKNOWN, or status 404); status 1, saying
# why on stderr, when it cannot tell (credentials, the network, the registry itself). Only crane's
# output is the answer: what it says on stderr is not.
registry_held() {
  local out
  if out="$(crane digest "$1" 2>"$work/err")"; then
    echo "$out"
  elif ! grep -qE 'MANIFEST_UNKNOWN|NAME_UNKNOWN|status code 404' "$work/err"; then
    echo "cannot read $1: $(grep . "$work/err" | tail -1)" >&2
    return 1
  fi
}

# 1. What the release registry holds, before anything is written into it: every image under its
# candidate's tag by the manifest's digest, its release tag free or holding the same.
declare -A release_tag repository digest
while IFS=$'\t' read -r key _ _ tag; do
  release_tag[$key]="$tag"
done <<<"$names"
images="$(python3 "$manifest_py" images "$manifest" --fixtures --cli)"
problems=() to_tag=() count=0
while IFS=$'\t' read -r key repo tag value; do
  count=$((count + 1))
  [[ -n "${release_tag[$key]:-}" ]] || fail "$key: an image of $candidate's manifest that its names do not list" 2
  repository[$key]="$repo" digest[$key]="$value"
  held="$(registry_held "$registry/$prefix/$repo:$tag")" || fail "Cannot read the release registry $registry"
  if [[ "$held" != "$value" ]]; then
    problems+=("$key: $prefix/$repo:$tag is ${held:-not there}, not the manifest's $value")
    continue
  fi
  held="$(registry_held "$registry/$prefix/$repo:${release_tag[$key]}")" || fail "Cannot read the release registry $registry"
  if [[ -z "$held" ]]; then
    to_tag+=("$key")
  elif [[ "$held" != "$value" ]]; then
    problems+=("$key: $prefix/$repo:${release_tag[$key]} is already $held, not $candidate's $value: tags never move")
  fi
done <<<"$images"
((count == ${#release_tag[@]})) || fail "$candidate's manifest names $count images, its names ${#release_tag[@]}" 2
if ((${#problems[@]} > 0)); then
  printf '  %s\n' "${problems[@]}" >&2
  fail "The release registry $registry does not hold $candidate as its manifest says (${#problems[@]} of $count images)" 3
fi
log "registry: the $count images of $candidate under its tags by its manifest's digests;" \
  "$((count - ${#to_tag[@]})) release tags there already with those digests"

# The release's manifest and notes, made before anything is written anywhere.
cp "$manifest" "$work/candidate.json"
python3 -c 'import json, sys
path, release, at, tag_commit, manifest_blob, record, notes_blob = sys.argv[1:]
with open(path, "w") as f:
    json.dump({"release": release, "at": at, "tag_commit": tag_commit, "manifest_blob": manifest_blob,
               "acceptance": {"commit": record, "blob": notes_blob}}, f)' \
  "$work/params.json" "$release" "$(date -u +%FT%TZ)" "$tag_commit" "$manifest_blob" "$record" "$notes_blob"
python3 "$promotion_py" assemble "$work" >"$work/manifest.json"
python3 "$manifest_py" validate "$work/manifest.json" --name "$release"
python3 "$manifest_py" check-tree "$work/manifest.json" "$REPO" HEAD
python3 "$promotion_py" notes "$work/manifest.json" <"$work/candidate-notes.md" >"$work/notes.md"

# 2. The release's tags, by digest: the same manifests under a second tag.
still_this_commit
for key in "${to_tag[@]}"; do
  crane tag "$registry/$prefix/${repository[$key]}@${digest[$key]}" "${release_tag[$key]}" >>"$evidence/tag.log" 2>&1 \
    || { tail -5 "$evidence/tag.log" >&2; fail "crane tag failed for $key ($evidence/tag.log)"; }
  echo "tagged $key: $prefix/${repository[$key]}@${digest[$key]} ${release_tag[$key]}" >>"$evidence/promote.log"
done
for key in "${!repository[@]}"; do
  held="$(registry_held "$registry/$prefix/${repository[$key]}:${release_tag[$key]}")" \
    || fail "Cannot read the release registry $registry"
  [[ "$held" == "${digest[$key]}" ]] \
    || fail "$key: $prefix/${repository[$key]}:${release_tag[$key]} is ${held:-nothing}, not ${digest[$key]}, after the tag" 3
done
log "registry: tagged ${#to_tag[@]}; all $count release tags hold $candidate's digests"

# 3. The commit and the tag.
written=(manifest.json notes.md)
(cd "$work" && cp "${written[@]}" "$evidence/")
still_this_commit
undo="git -C $REPO switch -q -f --detach $head; git -C $REPO branch -D release-$release; rm -rf $REPO/release/$release"
mkdir -p "$REPO/release/$release"
(cd "$work" && cp "${written[@]}" "$REPO/release/$release/")
git -C "$REPO" switch -q -c "release-$release" || fail "git switch -c release-$release failed"
git -C "$REPO" add "release/$release" || fail "git add release/$release failed"
git -C "$REPO" commit -q -m "release: $release, promoted from $candidate${rehearsal:+ ($rehearsal)}" \
  -m "$candidate (built from $built_from) judged GO in release/$candidate/notes.md at $record; the same images by digest, tagged $release." \
  || fail "git commit failed (a hook of this clone, or no git identity?)"
commit="$(git -C "$REPO" rev-parse HEAD)"
[[ "$(git -C "$REPO" diff --name-only "$head" "$commit" | tr '\n' ' ')" \
  == "release/$release/manifest.json release/$release/notes.md " ]] \
  || fail "The commit $commit holds more than release/$release/"
python3 "$manifest_py" check-tree "$REPO/release/$release/manifest.json" "$REPO" HEAD \
  || fail "The commit $commit is not what its manifest describes"
git -C "$REPO" tag -a "$release" -m "$release: $candidate promoted, built from $built_from${rehearsal:+ ($rehearsal)}" "$commit" \
  || fail "git tag $release failed"
undo=""
log "committed release/$release/ on $candidate's tag commit as $commit (branch release-$release), tagged $release"
if [[ -n "$rehearsal" ]]; then
  log "$rehearsal; remove it: git tag -d $release; git switch --detach $head; git branch -D release-$release"
else
  log "next: git push origin $release release-$release, then the pull request release-$release (merged with a merge commit, never rebased)"
fi

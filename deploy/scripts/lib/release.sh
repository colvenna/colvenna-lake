#!/usr/bin/env bash
# release_checkout, the checkout's half of check-release.sh (which deploy/install.sh --release runs
# before it touches anything): whether this checkout installs the release candidate, or the release
# promoted from one, of that name. Sourced; needs REPO_DIR and lib/git.sh's has_commit.

# release_checkout <name>: this checkout installs <name>, or the check stops (exit 1), saying why: its
# manifest, release/<name>/manifest.json, is here and valid (manifest.py validate; a release's is
# promotion.py's); the tree is clean and differs from the commit the images were built from
# (source.built_from) under the directories its tag adds alone (manifest.py added: release/<candidate>/,
# and for a release release/<release>/ too); and the manifest pins this checkout's git objects (its
# charts' directories, deploy/install.sh, deploy/uninstall.sh, the UAT cases). A release's checkout is
# also where its manifest says it was promoted from: its candidate's tag commit with release/<release>/
# added, its manifest the candidate's under the release's names, and the candidate's release notes at
# the recorded commit judging it GO (manifest.py check-tree). Sets RELEASE_MANIFEST and RELEASE_BUILT_FROM.
release_checkout() {
  local name="$1" tool="$REPO_DIR/deploy/scripts/release/manifest.py" manifest built_from listed changed
  local -a added
  manifest="$REPO_DIR/release/$name/manifest.json"
  if [[ ! -f "$manifest" ]]; then
    echo "No release/$name/manifest.json in this checkout: check out its tag (git checkout $name)" >&2
    exit 1
  fi
  python3 "$tool" validate "$manifest" --name "$name"
  built_from="$(python3 "$tool" get "$manifest" source.built_from)"
  if [[ -n "$(git -C "$REPO_DIR" status --porcelain)" ]]; then
    echo "Commit or remove local changes first; a release installs from its tag's tree" >&2
    exit 1
  fi
  if ! has_commit "$built_from"; then
    echo "This clone lacks $built_from, the commit $name's images were built from: git fetch origin --tags" >&2
    exit 1
  fi
  listed="$(python3 "$tool" added "$manifest")"
  mapfile -t added <<<"$listed"
  changed="$(git -C "$REPO_DIR" diff --no-renames --name-only "$built_from" HEAD -- . "${added[@]/#/:(exclude)}")"
  if [[ -n "$changed" ]]; then
    echo "This tree differs from $built_from, the commit $name's images were built from, outside" \
      "$(printf '%s/ ' "${added[@]}")(what its tag adds): check out the tag $name. Differing:" >&2
    head -20 <<<"$changed" | sed 's/^/  /' >&2
    exit 1
  fi
  if ! python3 "$tool" check-tree "$manifest" "$REPO_DIR" HEAD; then
    echo "The manifest of $name does not describe this checkout: check out the tag $name (git fetch origin --tags)" >&2
    exit 1
  fi
  # shellcheck disable=SC2034 # the scripts that source this read them
  RELEASE_MANIFEST="$manifest" RELEASE_BUILT_FROM="$built_from"
}

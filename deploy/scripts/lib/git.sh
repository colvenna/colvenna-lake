#!/usr/bin/env bash
# committed_sha and the release candidates' tree rule, for every deploy script: lib/common.sh sources
# it, and the scripts that belong to no environment (deploy/scripts/release/deploy-registry.sh,
# check-release.sh) source it alone. Needs REPO_DIR. check-release.sh holds a tag's checkout to the
# directories its manifest says the tag adds (lib/release.sh); same_product is the coarser rule the
# acceptance tests judge a repeated install by.

# Refuses to build or deploy anything but a clean, committed tree; prints its SHA.
committed_sha() {
  if [[ -n "$(git -C "$REPO_DIR" status --porcelain)" ]]; then
    echo "Commit or remove local changes first; deployments are traceable to a SHA" >&2
    exit 1
  fi
  git -C "$REPO_DIR" rev-parse HEAD
}

# has_commit <sha>: whether this clone holds the commit.
has_commit() {
  git -C "$REPO_DIR" cat-file -e "$1^{commit}" 2>/dev/null
}

# The product: the whole tree but release/, which is all a release candidate's tag adds to the
# commit its images were built from (release/README.md).
PRODUCT_PATHSPEC=(. ':(exclude)release')

# same_product <commit> <commit>: whether the two trees differ only under release/.
same_product() {
  git -C "$REPO_DIR" diff --quiet "$1" "$2" -- "${PRODUCT_PATHSPEC[@]}"
}

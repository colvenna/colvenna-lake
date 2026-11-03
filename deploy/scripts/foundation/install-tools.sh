#!/usr/bin/env bash
set -euo pipefail
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"

case "$(uname -m)" in
  aarch64) arch=arm64 ;;
  x86_64) arch=amd64 ;;
  *) echo "The tools installer supports arm64 and amd64" >&2; exit 1 ;;
esac
tools_dir="$TOOLS_DIR"
mkdir -p "$tools_dir"

kubectl_version=v1.36.5
curl -fsSL "https://dl.k8s.io/release/$kubectl_version/bin/linux/$arch/kubectl" -o "$tools_dir/kubectl"
expected="$(curl -fsSL "https://dl.k8s.io/release/$kubectl_version/bin/linux/$arch/kubectl.sha256")"
printf '%s  %s\n' "$expected" "$tools_dir/kubectl" | sha256sum --check
chmod 0755 "$tools_dir/kubectl"

helm_version=v3.19.0
archive="helm-$helm_version-linux-$arch.tar.gz"
curl -fsSL "https://get.helm.sh/$archive" -o "$tools_dir/$archive"
expected="$(curl -fsSL "https://get.helm.sh/$archive.sha256sum" | awk '{print $1}')"
printf '%s  %s\n' "$expected" "$tools_dir/$archive" | sha256sum --check
tar -xzf "$tools_dir/$archive" -C "$tools_dir" "linux-$arch/helm"
mv "$tools_dir/linux-$arch/helm" "$tools_dir/helm"
chmod 0755 "$tools_dir/helm"
rm -r "$tools_dir/linux-$arch"

# crane copies images between repositories by digest (deploy/scripts/doris/copy-images.sh).
bash "$(dirname "$0")/install-crane.sh" "$tools_dir"

echo "kubectl, Helm and crane are in $tools_dir"

#!/usr/bin/env bash
# Writes the update manifests for one release APK into <out-dir> and prints the APK's SHA-256.
#
#   latest.json         Read by current builds from the GitHub release
#                       (releases/latest/download/latest.json); "apk" is the release asset's https URL.
#   legacy-latest.json  Read by builds from before bring-your-own-R2, which can only see the old "portland" R2
#                       bucket; "apk" is the object key the APK is uploaded under next to it.
#
# Both carry the same versionCode, versionName and sha256, which the app checks before it installs anything.
#
# Usage: release-manifest.sh <apk> <versionCode> <versionName> <owner/repo> <out-dir> [notes]
set -euo pipefail

if [ "$#" -lt 5 ]; then
  echo "usage: $0 <apk> <versionCode> <versionName> <owner/repo> <out-dir> [notes]" >&2
  exit 2
fi
apk=$1
code=$2
name=$3
repo=$4
out=$5
notes=${6:-}

test -s "$apk" || { echo "no APK at $apk" >&2; exit 1; }
[[ $code =~ ^[0-9]+$ ]] || { echo "versionCode must be a whole number, got '$code'" >&2; exit 1; }
[[ $repo =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || { echo "repo must be owner/name, got '$repo'" >&2; exit 1; }

sha=$(sha256sum < "$apk" | cut -d' ' -f1)
mkdir -p "$out"

manifest() {
  jq -n --argjson code "$code" --arg name "$name" --arg apk "$1" --arg sha "$sha" --arg notes "$notes" \
    '{versionCode: $code, versionName: $name, apk: $apk, sha256: $sha, notes: $notes}'
}
manifest "https://github.com/$repo/releases/download/v$name/app-release.apk" > "$out/latest.json"
manifest "releases/portland-$code.apk" > "$out/legacy-latest.json"
echo "$sha"

#!/usr/bin/env bash
# Writes latest.json, the update manifest for one release APK, into <out-dir> and prints the APK's SHA-256. The app
# reads it from the GitHub release (releases/latest/download/latest.json); "apk" is the release asset's https URL,
# and the app checks the SHA-256 before it installs anything.
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
jq -n --argjson code "$code" --arg name "$name" --arg sha "$sha" --arg notes "$notes" \
  --arg apk "https://github.com/$repo/releases/download/v$name/app-release.apk" \
  '{versionCode: $code, versionName: $name, apk: $apk, sha256: $sha, notes: $notes}' > "$out/latest.json"
echo "$sha"

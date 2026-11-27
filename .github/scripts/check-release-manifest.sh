#!/usr/bin/env bash
# Fails unless the manifests in <dir> are exactly what the app's Release.parse accepts and they describe <apk>:
# a numeric versionCode, the APK's SHA-256, the GitHub asset URL of the release tagged v<versionName> for current
# builds, and a releases/portland-<code>.apk key for the legacy R2 bridge. Runs on every PR and before publishing.
#
# Usage: check-release-manifest.sh <apk> <dir>
set -euo pipefail

apk=$1
dir=$2
sha=$(sha256sum < "$apk" | cut -d' ' -f1)

fail() {
  echo "$1 is not a valid update manifest:" >&2
  cat "$dir/$1" >&2
  exit 1
}

# The tag in the asset URL must be the versionName, or the link 404s once the release is published.
jq -e --arg sha "$sha" '
  (.versionCode | type == "number" and . > 0) and
  (.versionName | type == "string" and length > 0) and
  (.sha256 == $sha) and
  (.apk | test("^https://github\\.com/[^/]+/[^/]+/releases/download/v[^/]+/app-release\\.apk$")) and
  (. as $m | $m.apk | endswith("/v" + $m.versionName + "/app-release.apk"))
' "$dir/latest.json" > /dev/null || fail latest.json

jq -e --arg sha "$sha" --slurpfile gh "$dir/latest.json" '
  (.versionCode == $gh[0].versionCode) and
  (.versionName == $gh[0].versionName) and
  (.sha256 == $sha) and
  (.apk == "releases/portland-" + (.versionCode | tostring) + ".apk")
' "$dir/legacy-latest.json" > /dev/null || fail legacy-latest.json

echo "manifests ok: $(jq -r '.versionName + " (" + (.versionCode | tostring) + ")"' "$dir/latest.json"), sha256 $sha"

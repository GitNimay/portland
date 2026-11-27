#!/usr/bin/env bash
# Fails unless <dir>/latest.json is exactly what the app's Release.parse accepts and it describes <apk>: a numeric
# versionCode, the APK's SHA-256 and the GitHub asset URL of the release tagged v<versionName>. Runs on every PR and
# again before publishing.
#
# Usage: check-release-manifest.sh <apk> <dir>
set -euo pipefail

apk=$1
dir=$2
sha=$(sha256sum < "$apk" | cut -d' ' -f1)

# The tag in the asset URL must be the versionName, or the link 404s once the release is published.
jq -e --arg sha "$sha" '
  (.versionCode | type == "number" and . > 0) and
  (.versionName | type == "string" and length > 0) and
  (.sha256 == $sha) and
  (.apk | test("^https://github\\.com/[^/]+/[^/]+/releases/download/v[^/]+/app-release\\.apk$")) and
  (. as $m | $m.apk | endswith("/v" + $m.versionName + "/app-release.apk"))
' "$dir/latest.json" > /dev/null || {
  echo "latest.json is not a valid update manifest:" >&2
  cat "$dir/latest.json" >&2
  exit 1
}

echo "manifest ok: $(jq -r '.versionName + " (" + (.versionCode | tostring) + ")"' "$dir/latest.json"), sha256 $sha"

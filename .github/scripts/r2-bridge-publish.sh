#!/usr/bin/env bash
# The legacy update bridge. Builds from before bring-your-own-R2 carry a key that only reaches the "portland" R2
# bucket, and they look for updates at releases/latest.json there. Publishing each release there too lets every one
# of those installs update itself to a current build, which then updates from GitHub. Keeps only the newest APK.
#
# Retire it once no old builds are left: delete the R2_* repository secrets (this step then skips itself), the
# "portland" bucket and the old API token, then this script and its step in android.yml.
#
# Env: R2_ACCOUNT_ID R2_BUCKET R2_ACCESS_KEY R2_SECRET_KEY
# Usage: r2-bridge-publish.sh <apk> <legacy-latest.json>
set -euo pipefail

apk=$1
manifest=$2

if [ -z "${R2_ACCESS_KEY:-}" ] || [ -z "${R2_SECRET_KEY:-}" ] || [ -z "${R2_ACCOUNT_ID:-}" ]; then
  echo "::notice::Legacy R2 bridge secrets are not set; old builds will not be offered this release."
  exit 0
fi

bucket="https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com/${R2_BUCKET:-portland}"
key=$(jq -r .apk "$manifest")

s3() {
  curl -fsS --retry 3 --retry-all-errors --connect-timeout 15 --max-time 300 \
    --aws-sigv4 "aws:amz:auto:s3" --user "$R2_ACCESS_KEY:$R2_SECRET_KEY" "$@"
}

s3 -H "Content-Type: application/vnd.android.package-archive" -T "$apk" "$bucket/$key" > /dev/null
# The pointer goes last, so installed apps only ever see a fully uploaded build.
s3 -H "Content-Type: application/json" -T "$manifest" "$bucket/releases/latest.json" > /dev/null
echo "bridge: releases/latest.json -> $key"

# Older APKs are never read again (the pointer names one key); drop them to keep the bucket small.
s3 "$bucket?list-type=2&prefix=releases/portland-" | grep -o '<Key>[^<]*</Key>' | sed -e 's/<Key>//' -e 's/<\/Key>//' |
  while read -r old; do
    if [ "$old" != "$key" ]; then
      s3 -X DELETE "$bucket/$old" > /dev/null
      echo "bridge: removed $old"
    fi
  done

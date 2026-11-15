#!/usr/bin/env bash
# Restore the large prebuilt binaries that are intentionally not committed
# (see the "Large binary assets" section in .gitignore).
#
# Usage:  scripts/fetch-large-assets.sh [<upstream-sha>]
# Requires: curl (or wget), tar.  Downloads one tarball from codeload.github.com
# and copies only the asset directories back into the tree.
set -euo pipefail

UPSTREAM_REPO="${UPSTREAM_REPO:-utkarshdalal/GameNative}"
UPSTREAM_SHA="${1:-${UPSTREAM_SHA:-449decb3a51343e3ccd4aebfda8d5caf20cc84b5}}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

URL="https://codeload.github.com/${UPSTREAM_REPO}/tar.gz/${UPSTREAM_SHA}"
echo ">> downloading ${URL}"
if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 3 -o "$TMP/src.tar.gz" "$URL"
else
  wget -O "$TMP/src.tar.gz" "$URL"
fi

echo ">> extracting"
mkdir -p "$TMP/src"
tar -xzf "$TMP/src.tar.gz" -C "$TMP/src" --strip-components=1

PATHS=(
  "app/src/main/assets"
  "app/src/legacy/assets"
  "app/src/modern/assets"
  "app/src/modernXr/assets"
  "app/src/legacyXr/assets"
  "app/src/androidTest/assets"
  "app/src/main/jniLibs"
  "app/src/legacy/jniLibs"
  "app/src/modern/jniLibs"
  "app/src/modernXr/jniLibs"
  "app/src/legacyXr/jniLibs"
  "app/src/main/res/drawable/ic_launcher_gold_background.xml"
  "tools/bundletool-all-1.17.2.jar"
)
for p in "${PATHS[@]}"; do
  if [ -e "$TMP/src/$p" ]; then
    mkdir -p "$ROOT/$(dirname "$p")"
    cp -a "$TMP/src/$p" "$ROOT/$(dirname "$p")/"
    echo "   restored $p"
  fi
done
echo ">> done"

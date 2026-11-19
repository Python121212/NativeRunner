#!/usr/bin/env bash
# Restore the large prebuilt binaries that are intentionally not committed
# (see the "Large binary assets" section in .gitignore).
#
# Usage:  scripts/fetch-large-assets.sh [<upstream-sha>]
# Requires: curl (or wget), tar.  Downloads one tarball from codeload.github.com
# and copies only the asset directories back into the tree.
#
# The default upstream commit is recorded in scripts/upstream-assets-sha. That is
# the commit the vendored binaries were taken from, NOT the commit this tree is
# built from: core_drivers_download.json, container_files_download.json and
# friends pick their files by version at runtime, so a newer upstream tree can
# still legitimately ship an older set of bundled blobs. Pinning the sha here is
# what guarantees the APK carries exactly the binaries upstream ships.
set -euo pipefail

UPSTREAM_REPO="${UPSTREAM_REPO:-utkarshdalal/GameNative}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PINNED_SHA="$(tr -d '[:space:]' < "$ROOT/scripts/upstream-assets-sha")"
UPSTREAM_SHA="${1:-${UPSTREAM_SHA:-$PINNED_SHA}}"
if [ ${#UPSTREAM_SHA} -ne 40 ]; then
  echo "!! upstream sha '$UPSTREAM_SHA' does not look like a commit sha" >&2
  exit 1
fi
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

URL="https://codeload.github.com/${UPSTREAM_REPO}/tar.gz/${UPSTREAM_SHA}"
echo ">> asset source: ${UPSTREAM_REPO}@${UPSTREAM_SHA}"
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
missing=()
for p in "${PATHS[@]}"; do
  if [ -e "$TMP/src/$p" ]; then
    mkdir -p "$ROOT/$(dirname "$p")"
    cp -a "$TMP/src/$p" "$ROOT/$(dirname "$p")/"
    echo "   restored $p"
  else
    # tarballs are the app's own prebuilt blobs: a path that upstream no longer
    # carries is a signal that the pinned sha needs revisiting, not something to
    # skip silently.
    missing+=("$p")
  fi
done
if [ ${#missing[@]} -gt 0 ]; then
  echo "!! ${#missing[@]} path(s) missing from ${UPSTREAM_REPO}@${UPSTREAM_SHA}:" >&2
  printf '   %s\n' "${missing[@]}" >&2
  exit 1
fi
echo ">> done (${#PATHS[@]} paths from ${UPSTREAM_SHA})"

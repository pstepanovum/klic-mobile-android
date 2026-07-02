#!/usr/bin/env bash
# Renames the app's Kotlin package (directory tree, package/import lines,
# Gradle namespace/applicationId) from OLD_PKG to NEW_PKG.
set -euo pipefail

OLD_PKG="com.klicmobile.app"
NEW_PKG="com.klic.mobile.app"
OLD_PATH="app/src/main/java/com/klicmobile/app"
NEW_PARENT="app/src/main/java/com/klic/mobile"
NEW_PATH="$NEW_PARENT/app"

cd "$(dirname "$0")/.."

if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: working tree is not clean; commit or stash before running this script" >&2
  exit 1
fi

if [[ ! -d "$OLD_PATH" ]]; then
  echo "error: $OLD_PATH not found (already renamed?)" >&2
  exit 1
fi

echo "Moving $OLD_PATH -> $NEW_PATH"
mkdir -p "$NEW_PARENT"
git mv "$OLD_PATH" "$NEW_PATH"
rmdir app/src/main/java/com/klicmobile 2>/dev/null || true

echo "Rewriting package/import lines in *.kt files"
find app/src -name "*.kt" -print0 | xargs -0 sed -i '' "s/${OLD_PKG//./\\.}/${NEW_PKG}/g"

echo "Rewriting namespace/applicationId in app/build.gradle.kts"
sed -i '' "s/${OLD_PKG//./\\.}/${NEW_PKG}/g" app/build.gradle.kts

echo "Done. Remaining references to $OLD_PKG (expected: none in app/src, none in build.gradle.kts):"
grep -rn "$OLD_PKG" app/src app/build.gradle.kts 2>/dev/null || echo "  (none found)"

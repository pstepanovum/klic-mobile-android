#!/usr/bin/env bash
#
# Cut a new Klic release: bump version (Android + iOS kept in sync), build the APK,
# tag it, and publish a GitHub release with the APK attached. Android users get this
# via the in-app updater (Settings → App updates). iOS version is bumped only to stay
# in sync; iOS distribution is separate (TestFlight).
#
# Usage:
#   ./release.sh            # auto-bump the patch version (0.1.1 -> 0.1.2)
#   ./release.sh 0.2.0      # set an explicit version
#
set -euo pipefail
cd "$(dirname "$0")"

GRADLE="app/build.gradle.kts"
IOS_PROJECT="../klic-mobile-ios/project.yml"

cur_name="$(grep -E 'versionName = ' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
cur_code="$(grep -E 'versionCode = ' "$GRADLE" | head -1 | sed -E 's/.*= *([0-9]+).*/\1/')"

new_name="${1:-}"
if [ -z "$new_name" ]; then
  IFS=. read -r MA MI PA <<< "$cur_name"
  new_name="${MA}.${MI}.$((PA + 1))"
fi
new_code=$((cur_code + 1))
tag="v${new_name}"

echo "Releasing ${cur_name} (code ${cur_code}) -> ${new_name} (code ${new_code})  tag=${tag}"

# --- bump versions ---
sed -i '' -E "s/versionName = \"[^\"]+\"/versionName = \"${new_name}\"/" "$GRADLE"
sed -i '' -E "s/versionCode = [0-9]+/versionCode = ${new_code}/" "$GRADLE"

if [ -f "$IOS_PROJECT" ]; then
  sed -i '' -E "s/MARKETING_VERSION: \"[^\"]+\"/MARKETING_VERSION: \"${new_name}\"/" "$IOS_PROJECT"
  sed -i '' -E "s/CURRENT_PROJECT_VERSION: \"[^\"]+\"/CURRENT_PROJECT_VERSION: \"${new_code}\"/" "$IOS_PROJECT"
  echo "iOS version synced in $IOS_PROJECT (commit it in that repo separately)."
fi

# --- build ---
./gradlew assembleDebug
apk="klic-${new_name}.apk"
cp "app/build/outputs/apk/debug/app-debug.apk" "$apk"

# --- tag + publish ---
branch="$(git rev-parse --abbrev-ref HEAD)"
git add "$GRADLE"
git commit -m "Release ${tag}" || echo "(nothing to commit)"
git tag "$tag"
git push origin "$branch" --tags

gh release create "$tag" "${apk}#Klic ${new_name} (Android APK)" \
  --title "Klic ${new_name}" \
  --notes "Android debug build. Install via the in-app updater (Settings → App updates) or by downloading the APK below. iOS is distributed via TestFlight."

echo "Released ${tag}. APK: ${apk}"

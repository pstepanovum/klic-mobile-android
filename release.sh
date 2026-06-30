#!/usr/bin/env bash
#
# Cut a new Klic release: bump version (Android + iOS kept in sync), build the APK
# and an unsigned iOS IPA, tag both repos, and publish GitHub releases with assets
# attached. Android users update via the in-app updater; iOS IPA is for AltStore/
# sideloading (unsigned — AltStore re-signs with the user's personal cert).
#
# Usage:
#   ./release.sh            # auto-bump the patch version (0.3.7 -> 0.3.8)
#   ./release.sh 0.4.0      # set an explicit version
#
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_DIR="$(pwd)"
IOS_DIR="$(cd ../klic-mobile-ios && pwd)"
GRADLE="app/build.gradle.kts"
IOS_PROJECT="${IOS_DIR}/project.yml"

cur_name="$(grep -E 'versionName = ' "$GRADLE" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
cur_code="$(grep -E 'versionCode = ' "$GRADLE" | head -1 | sed -E 's/.*= *([0-9]+).*/\1/')"

new_name="${1:-}"
if [ -z "$new_name" ]; then
  IFS=. read -r MA MI PA <<< "$cur_name"
  new_name="${MA}.${MI}.$((PA + 1))"
fi
new_code=$((cur_code + 1))
tag="v${new_name}"

echo "==========================================="
echo "Releasing ${cur_name} (code ${cur_code})"
echo "      ->  ${new_name} (code ${new_code})  tag=${tag}"
echo "==========================================="

# ── 1. Bump versions ──────────────────────────────────────────────────────────
sed -i '' -E "s/versionName = \"[^\"]+\"/versionName = \"${new_name}\"/" "$GRADLE"
sed -i '' -E "s/versionCode = [0-9]+/versionCode = ${new_code}/" "$GRADLE"

if [ -f "$IOS_PROJECT" ]; then
  sed -i '' -E "s/MARKETING_VERSION: \"[^\"]+\"/MARKETING_VERSION: \"${new_name}\"/" "$IOS_PROJECT"
  sed -i '' -E "s/CURRENT_PROJECT_VERSION: \"[^\"]+\"/CURRENT_PROJECT_VERSION: \"${new_code}\"/" "$IOS_PROJECT"
  echo "iOS version synced → ${new_name} (build ${new_code})"
fi

# ── 2. Build Android APK ──────────────────────────────────────────────────────
echo "Building Android APK..."
cd "$ANDROID_DIR"
./gradlew assembleDebug
apk="${ANDROID_DIR}/klic-${new_name}.apk"
cp "app/build/outputs/apk/debug/app-debug.apk" "$apk"
echo "APK built: $apk"

# ── 3. Build iOS unsigned IPA ─────────────────────────────────────────────────
echo "Building iOS IPA..."
cd "$IOS_DIR"

# Regenerate xcodeproj from project.yml so all source files are included.
if command -v xcodegen &>/dev/null; then
  xcodegen generate --quiet
else
  echo "xcodegen not found — skipping project regeneration (xcodeproj must already be up to date)"
fi

ARCHIVE_PATH="/tmp/klic-${new_name}.xcarchive"
IPA_NAME="klic-ios-${new_name}.ipa"
IPA_PATH="${IOS_DIR}/${IPA_NAME}"
PAYLOAD_DIR="/tmp/klic-ipa-payload-${new_name}"

xcodebuild archive \
  -scheme Klic \
  -sdk iphoneos \
  -configuration Release \
  -archivePath "$ARCHIVE_PATH" \
  CODE_SIGN_IDENTITY="" \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGNING_ALLOWED=NO \
  DEVELOPMENT_TEAM="" \
  -quiet

# Package .app → IPA manually (no provisioning profile needed for unsigned)
rm -rf "$PAYLOAD_DIR"
mkdir -p "${PAYLOAD_DIR}/Payload"
cp -r "${ARCHIVE_PATH}/Products/Applications/Klic.app" "${PAYLOAD_DIR}/Payload/"
(cd "$PAYLOAD_DIR" && zip -qr "$IPA_PATH" Payload)
rm -rf "$PAYLOAD_DIR" "$ARCHIVE_PATH"
echo "IPA built: $IPA_PATH"

# ── 4. Commit + tag + push iOS repo ──────────────────────────────────────────
echo "Committing iOS changes..."
cd "$IOS_DIR"
ios_branch="$(git rev-parse --abbrev-ref HEAD)"
# git add -A lets git resolve paths via its own index rather than the filesystem,
# which avoids the macOS case-insensitive mismatch (Sources/Features vs Sources/features).
git add -A Sources/ Resources/ project.yml
git commit -m "Release ${tag}" || echo "(nothing to commit in iOS)"
git tag "$tag"
git push origin "$ios_branch" --tags
echo "iOS repo tagged ${tag} and pushed."

# Create iOS GitHub release with the IPA
gh release create "$tag" "${IPA_PATH}#Klic ${new_name} (iOS IPA — unsigned)" \
  --repo pstepanovum/klic-mobile-ios \
  --title "Klic ${new_name}" \
  --notes "Unsigned IPA for sideloading via AltStore or similar tools. AltStore will re-sign it with your personal certificate. iOS ${new_name} (build ${new_code})."

# ── 5. Commit + tag + push Android repo ──────────────────────────────────────
echo "Committing Android changes..."
cd "$ANDROID_DIR"
android_branch="$(git rev-parse --abbrev-ref HEAD)"
git add "$GRADLE"
git commit -m "Release ${tag}" || echo "(nothing to commit in Android)"
git tag "$tag"
git push origin "$android_branch" --tags

gh release create "$tag" "${apk}#Klic ${new_name} (Android APK)" \
  --repo pstepanovum/klic-mobile-android \
  --title "Klic ${new_name}" \
  --notes "Android debug build. Install via the in-app updater (Settings → App updates) or by downloading the APK. iOS is distributed via the iOS release on pstepanovum/klic-mobile-ios."

echo ""
echo "==========================================="
echo "Released ${tag}"
echo "  Android APK : klic-${new_name}.apk"
echo "  iOS IPA     : klic-ios-${new_name}.ipa"
echo "==========================================="

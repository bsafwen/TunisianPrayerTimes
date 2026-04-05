#!/usr/bin/env bash
set -euo pipefail

# ──────────────────────────────────────────────
# release.sh — Bump version, commit, tag, build Android + Desktop, GitHub release
# Usage:  ./release.sh "Short description of changes"
#
# Desktop native installers can only be built for the current OS.
# For cross-platform releases, use the GitHub Actions release workflow
# which builds on macOS, Windows, and Linux runners in parallel.
# ──────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$SCRIPT_DIR/android-app"
MULTI_DIR="$SCRIPT_DIR/multiplatform"
GRADLE_FILE="$APP_DIR/app/build.gradle.kts"

# ── Require a release message ────────────────
if [[ $# -lt 1 ]]; then
  echo "Usage: $0 \"Release description\""
  exit 1
fi
RELEASE_MSG="$1"

# ── Read current version from build.gradle.kts ──
CURRENT_CODE=$(grep -m1 'versionCode' "$GRADLE_FILE" | sed 's/[^0-9]//g')
CURRENT_NAME=$(grep -m1 'versionName' "$GRADLE_FILE" | sed 's/.*"\(.*\)".*/\1/')

# ── Compute next version ─────────────────────
# Increment minor: 2.7 → 2.8, 2.9 → 2.10, etc.
MAJOR="${CURRENT_NAME%%.*}"
MINOR="${CURRENT_NAME##*.}"
NEXT_MINOR=$((MINOR + 1))
NEXT_NAME="${MAJOR}.${NEXT_MINOR}"
NEXT_CODE=$((CURRENT_CODE + 1))
TAG="v${NEXT_NAME}"

echo "╔════════════════════════════════════════╗"
echo "║  Current : v${CURRENT_NAME}  (code ${CURRENT_CODE})"
echo "║  Next    : ${TAG}  (code ${NEXT_CODE})"
echo "╚════════════════════════════════════════╝"
echo ""

# ── Bump version in build.gradle.kts ─────────
sed -i '' "s/versionCode = ${CURRENT_CODE}/versionCode = ${NEXT_CODE}/" "$GRADLE_FILE"
sed -i '' "s/versionName = \"${CURRENT_NAME}\"/versionName = \"${NEXT_NAME}\"/" "$GRADLE_FILE"
echo "✓ Bumped version in build.gradle.kts"

# ── Build release AAB (App Bundle for Play Store) ──
echo "Building release AAB…"
cd "$APP_DIR"
./gradlew bundleRelease --no-daemon -q
AAB_PATH="$APP_DIR/app/build/outputs/bundle/release/app-release.aab"
if [[ ! -f "$AAB_PATH" ]]; then
  echo "✗ AAB not found at $AAB_PATH"
  exit 1
fi
echo "✓ AAB built: $AAB_PATH"

# ── Also build APK for GitHub release / sideloading ──
echo "Building release APK…"
./gradlew assembleRelease --no-daemon -q
APK_PATH="$APP_DIR/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK_PATH" ]]; then
  echo "✗ APK not found at $APK_PATH"
  exit 1
fi
echo "✓ APK built: $APK_PATH"

# ── Build desktop uber JAR (cross-platform, needs Java) ──
echo "Building desktop uber JAR…"
cd "$MULTI_DIR"
./gradlew :desktopApp:packageReleaseUberJarForCurrentOS -PappVersion="${NEXT_NAME}" --no-daemon -q
JAR_PATH=$(find "$MULTI_DIR/desktopApp/build/compose/jars" -name "*.jar" | head -1)
if [[ -z "$JAR_PATH" || ! -f "$JAR_PATH" ]]; then
  echo "✗ Uber JAR not found"
  exit 1
fi
echo "✓ Uber JAR built: $JAR_PATH"

# ── Build desktop native installer for current OS ──
echo "Building desktop native installer for current OS…"
./gradlew :desktopApp:packageReleaseDistributionForCurrentOS -PappVersion="${NEXT_NAME}" --no-daemon -q
OS_NAME="$(uname -s)"
NATIVE_ARTIFACTS=()
case "$OS_NAME" in
  Darwin)
    for f in "$MULTI_DIR"/desktopApp/build/compose/binaries/main-release/dmg/*.dmg; do
      [[ -f "$f" ]] && NATIVE_ARTIFACTS+=("$f")
    done
    ;;
  Linux)
    for f in "$MULTI_DIR"/desktopApp/build/compose/binaries/main-release/deb/*.deb; do
      [[ -f "$f" ]] && NATIVE_ARTIFACTS+=("$f")
    done
    for f in "$MULTI_DIR"/desktopApp/build/compose/binaries/main-release/rpm/*.rpm; do
      [[ -f "$f" ]] && NATIVE_ARTIFACTS+=("$f")
    done
    ;;
  MINGW*|MSYS*|CYGWIN*)
    for f in "$MULTI_DIR"/desktopApp/build/compose/binaries/main-release/msi/*.msi; do
      [[ -f "$f" ]] && NATIVE_ARTIFACTS+=("$f")
    done
    for f in "$MULTI_DIR"/desktopApp/build/compose/binaries/main-release/exe/*.exe; do
      [[ -f "$f" ]] && NATIVE_ARTIFACTS+=("$f")
    done
    ;;
esac
echo "✓ Native installer(s): ${NATIVE_ARTIFACTS[*]:-none}"

# ── Copy artifacts to repo root with versioned names ──
cd "$SCRIPT_DIR"
AAB_OUT="TunisianPrayerTimes-${TAG}.aab"
APK_OUT="TunisianPrayerTimes-${TAG}.apk"
JAR_OUT="TunisianPrayerTimes-${TAG}-desktop.jar"
cp "$AAB_PATH" "$AAB_OUT"
cp "$APK_PATH" "$APK_OUT"
cp "$JAR_PATH" "$JAR_OUT"

RELEASE_FILES=("$APK_OUT" "$JAR_OUT")
for f in "${NATIVE_ARTIFACTS[@]}"; do
  EXT="${f##*.}"
  OUT="TunisianPrayerTimes-${TAG}.${EXT}"
  cp "$f" "$OUT"
  RELEASE_FILES+=("$OUT")
done
echo "✓ Copied artifacts: $AAB_OUT, ${RELEASE_FILES[*]}"

# ── Git: stage, commit, tag ──────────────────
git add -A
git reset HEAD -- "$AAB_OUT" "$JAR_OUT" "${RELEASE_FILES[@]}"
git commit -m "${TAG}: ${RELEASE_MSG}"
git tag -a "$TAG" -m "${TAG}: ${RELEASE_MSG}"
echo "✓ Committed and tagged $TAG"

# ── Push to origin ────────────────────────────
git push origin main
git push origin "$TAG"
echo "✓ Pushed to origin"

# ── Create GitHub release with all artifacts ──
gh release create "$TAG" \
  "${RELEASE_FILES[@]}" \
  --title "Tunisian Prayer Times ${NEXT_NAME}" \
  --notes "**${TAG}: ${RELEASE_MSG}**

### Downloads
- **Android APK** — install directly on your device
- **Desktop JAR** — cross-platform, requires Java 17+ (\`java -jar TunisianPrayerTimes-${TAG}-desktop.jar\`)
- **Native installer** — platform-specific (DMG/MSI/DEB depending on build OS)

The AAB (App Bundle) for Play Store upload is available as a build artifact."

echo "✓ GitHub release created"

# ── Clean up local copies of artifacts ────────
rm -f "$AAB_OUT" "$JAR_OUT"
for f in "${RELEASE_FILES[@]}"; do
  rm -f "$f"
done

echo ""
echo "══════════════════════════════════════════"
echo "  Release $TAG complete!"
echo "  AAB for Play Store: $AAB_PATH"
echo "══════════════════════════════════════════"

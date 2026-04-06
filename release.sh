#!/usr/bin/env bash
set -euo pipefail

# ──────────────────────────────────────────────
# release.sh — Bump version, commit, tag, push → CI builds & publishes
# Usage:  ./release.sh "Short description of changes"
#
# This script bumps the Android version, commits, tags, and pushes.
# The GitHub Actions release workflow (triggered by the v* tag) handles
# building all artifacts and creating the GitHub release.
# ──────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
APP_DIR="$SCRIPT_DIR/android-app"
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

# ── Build signed AAB locally (only if android-app changed) ──
LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
ANDROID_CHANGED=false
if [[ -z "$LAST_TAG" ]]; then
    ANDROID_CHANGED=true
elif git diff --name-only "$LAST_TAG" HEAD -- android-app/ | grep -q .; then
    ANDROID_CHANGED=true
elif git diff --name-only HEAD -- android-app/ | grep -q .; then
    ANDROID_CHANGED=true
fi

if [[ "$ANDROID_CHANGED" == "false" ]]; then
    echo ""
    echo "⏭ No android-app changes since $LAST_TAG — skipping AAB build."
else
    echo ""
    echo "Building signed release AAB..."
    cd "$APP_DIR"
    ./gradlew clean bundleRelease --no-daemon

    AAB="app/build/outputs/bundle/release/app-release.aab"
    if [[ ! -f "$AAB" ]]; then
        echo "✗ AAB not found after build — aborting release." >&2
        exit 1
    fi
    echo "✓ Signed AAB: $APP_DIR/$AAB"
    echo "  Size: $(du -h "$AAB" | cut -f1)"
    cd "$SCRIPT_DIR"
fi

# ── Git: stage, commit, tag ──────────────────
git add -A
git commit -m "${TAG}: ${RELEASE_MSG}"
git tag -a "$TAG" -m "${TAG}: ${RELEASE_MSG}"
echo "✓ Committed and tagged $TAG"

# ── Push to origin (tag triggers CI release) ──
git push origin main
git push origin "$TAG"
echo "✓ Pushed to origin"

echo ""
echo "══════════════════════════════════════════"
echo "  Tag $TAG pushed — CI will build & release."
echo "  AAB ready at: android-app/$AAB"
echo "  Track progress: gh run watch"
echo "══════════════════════════════════════════"

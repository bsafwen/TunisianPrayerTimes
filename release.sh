#!/usr/bin/env bash
set -euo pipefail

# ──────────────────────────────────────────────
# release.sh — Bump version, commit, tag, push → CI builds & publishes
# Usage:  ./release.sh [--skip-local-build] "Short description of changes"
#
# This script bumps the Android version, commits, tags, and pushes.
# The GitHub Actions release workflow (triggered by push to main with
# a commit message starting with 'v') handles building all artifacts
# and creating the GitHub release.
# ──────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
APP_DIR="$SCRIPT_DIR/android-app"
GRADLE_FILE="$APP_DIR/app/build.gradle.kts"

# ── Allow CI to build and sign when no local signing setup is available ──
SKIP_LOCAL_BUILD=false
if [[ "${1:-}" == "--skip-local-build" ]]; then
  SKIP_LOCAL_BUILD=true
  shift
fi

# ── Require a release message (English only) ─
if [[ $# -lt 1 ]]; then
  echo "Usage: $0 [--skip-local-build] \"Release description (English only)\""
  exit 1
fi
RELEASE_MSG="$1"
if [[ "$RELEASE_MSG" =~ [^[:ascii:]] ]]; then
  echo "✗ Release message must be in English (ASCII only)." >&2
  exit 1
fi

# ── Refuse to start if `git add -A` below would commit Quran media or huge files ──
# The recitations and page scans ship through Play Asset Delivery and the quran-cdn
# Worker; GitHub also rejects any file over 100 MB. Checked before anything is modified.
TOO_BIG=()
QURAN_MEDIA=()
while IFS= read -r -d '' entry; do
  status="${entry:0:2}"
  path="${entry:3}"
  # A rename or copy is followed by its original path as a separate record.
  if [[ "$status" == R* || "$status" == C* ]]; then
    IFS= read -r -d '' _ || true
  fi
  [[ -f "$path" ]] || continue
  if [[ "$path" =~ ^android-app/(app/src/main/assets/quran|quran-packs)/.*\.(mp3|webp)$ ]]; then
    QURAN_MEDIA+=("$path")
  elif (( $(wc -c < "$path") > 50 * 1024 * 1024 )); then
    TOO_BIG+=("$path")
  fi
done < <(git status --porcelain=v1 -z --untracked-files=all)
if (( ${#QURAN_MEDIA[@]} > 0 )); then
  echo "✗ Quran media would be committed (${#QURAN_MEDIA[@]} files, e.g. ${QURAN_MEDIA[0]})." >&2
  echo "  Move it out with: python3 scripts/quran_assets.py stage --from-dir <folder>" >&2
  exit 1
fi
if (( ${#TOO_BIG[@]} > 0 )); then
  echo "✗ Files over 50 MB would be committed: ${TOO_BIG[*]}" >&2
  exit 1
fi

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
sed -e "s/versionCode = ${CURRENT_CODE}/versionCode = ${NEXT_CODE}/" \
    -e "s/versionName = \"${CURRENT_NAME}\"/versionName = \"${NEXT_NAME}\"/" \
    "$GRADLE_FILE" > "$GRADLE_FILE.tmp"
mv "$GRADLE_FILE.tmp" "$GRADLE_FILE"
echo "✓ Bumped version in build.gradle.kts"

# ── Build signed AAB locally (only if android-app source changed, not just version bump) ──
LAST_TAG=$(git describe --tags --abbrev=0 2>/dev/null || echo "")
ANDROID_CHANGED=false
if [[ -z "$LAST_TAG" ]]; then
    ANDROID_CHANGED=true
elif git diff --name-only "$LAST_TAG" HEAD -- android-app/ ':!android-app/app/build.gradle.kts' | grep -q .; then
    ANDROID_CHANGED=true
elif git diff --name-only HEAD -- android-app/ ':!android-app/app/build.gradle.kts' | grep -q .; then
    ANDROID_CHANGED=true
fi

if [[ "$SKIP_LOCAL_BUILD" == "true" ]]; then
    echo ""
    echo "Skipping the local AAB build: this release has no bundle for Google Play."
    echo "GitHub Actions still builds and publishes the APK and desktop apps."
elif [[ "$ANDROID_CHANGED" == "false" ]]; then
    echo ""
    echo "⏭ No android-app changes since $LAST_TAG — skipping AAB build."
else
    echo ""
    echo "Building signed release AAB..."
    # The bundle carries the Quran packs; fetch any that are not staged yet (cached after the first time).
    if [[ -f "$APP_DIR/quran-assets/manifest.tsv" ]]; then
        PYTHON=$(command -v python3 || command -v python) || { echo "✗ Python 3 is needed to stage the Quran packs." >&2; exit 1; }
        "$PYTHON" "$SCRIPT_DIR/scripts/quran_assets.py" stage
    fi
    cd "$APP_DIR"
    ./gradlew clean bundleRelease --no-daemon

    AAB="app/build/outputs/bundle/release/app-release.aab"
    if [[ ! -f "$AAB" ]]; then
        echo "✗ AAB not found after build — aborting release." >&2
        exit 1
    fi
    echo "✓ Signed AAB: $APP_DIR/$AAB"
    echo "  Size: $(du -h "$AAB" | cut -f1)"
    echo "  Upload it to the Google Play Console yourself; GitHub Releases cannot hold it."
    cd "$SCRIPT_DIR"
fi

# ── Git: stage, commit, tag ──────────────────
git add -A
git commit -m "${TAG}: ${RELEASE_MSG}"
git tag -a "$TAG" -m "${TAG}: ${RELEASE_MSG}"
echo "✓ Committed and tagged $TAG"

# ── Push to origin (main push triggers CI; tag must exist first) ──
git push origin "$TAG"
git push origin main
echo "✓ Pushed to origin"

echo ""
echo "══════════════════════════════════════════"
echo "  $TAG pushed — CI will build & release."
echo "  Track progress: gh run watch"
echo "══════════════════════════════════════════"

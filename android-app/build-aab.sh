#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

UNSIGNED_RELEASE=false
SIGNING_LABEL="Signed"
if [[ "${1:-}" == "--unsigned" ]]; then
    UNSIGNED_RELEASE=true
    SIGNING_LABEL="Unsigned"
    shift
fi
if [[ $# -ne 0 ]]; then
    echo "Usage: $0 [--unsigned]" >&2
    exit 1
fi

# The bundle carries the Quran packs; fetch any that are not staged yet (cached after the first time).
if [[ -f quran-assets/manifest.tsv ]]; then
    PYTHON=$(command -v python3 || command -v python) || { echo "✗ Python 3 is needed to stage the Quran packs." >&2; exit 1; }
    "$PYTHON" ../scripts/quran_assets.py stage
fi

echo "Building release AAB ($SIGNING_LABEL)..."
./gradlew clean bundleRelease --no-daemon "-PunsignedRelease=$UNSIGNED_RELEASE"

AAB="app/build/outputs/bundle/release/app-release.aab"
if [[ -f "$AAB" ]]; then
    echo ""
    echo "✓ $SIGNING_LABEL AAB: $(pwd)/$AAB"
    echo "  Size: $(du -h "$AAB" | cut -f1)"
else
    echo "✗ Build finished but AAB not found." >&2
    exit 1
fi

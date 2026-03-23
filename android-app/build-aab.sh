#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

echo "Building signed release AAB..."
./gradlew clean bundleRelease --no-daemon

AAB="app/build/outputs/bundle/release/app-release.aab"
if [[ -f "$AAB" ]]; then
    echo ""
    echo "✓ Signed AAB: $(pwd)/$AAB"
    echo "  Size: $(du -h "$AAB" | cut -f1)"
else
    echo "✗ Build finished but AAB not found." >&2
    exit 1
fi

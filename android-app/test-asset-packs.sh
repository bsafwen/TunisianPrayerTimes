#!/usr/bin/env bash
set -euo pipefail

# Installs the app on a connected device the way Google Play would, with every Quran pack
# served from the device itself (bundletool --local-testing), to try the downloads without
# uploading anything to Play. Network errors and the Wi-Fi wait cannot be simulated this way;
# use internal app sharing for those.
#
# Usage: ./test-asset-packs.sh [--release]
#   default    the debug app (com.tunisianprayertimes.dev), installed next to the Play app
#   --release  the minified release app; uninstall the Play version first
# Needs bundletool on PATH, or BUNDLETOOL=/path/to/bundletool.jar.

cd "$(dirname "${BASH_SOURCE[0]}")"

VARIANT=debug
if [[ "${1:-}" == "--release" ]]; then
    VARIANT=release
    shift
fi
if [[ $# -ne 0 ]]; then
    echo "Usage: $0 [--release]" >&2
    exit 1
fi

BUNDLETOOL="${BUNDLETOOL:-bundletool}"
if [[ "$BUNDLETOOL" == *.jar ]]; then
    bundletool() { java -jar "$BUNDLETOOL" "$@"; }
elif ! command -v "$BUNDLETOOL" >/dev/null; then
    echo "✗ bundletool not found: install it from https://github.com/google/bundletool/releases" >&2
    echo "  or set BUNDLETOOL=/path/to/bundletool.jar" >&2
    exit 1
else
    # "command" runs the program on PATH, not this function.
    bundletool() { command "$BUNDLETOOL" "$@"; }
fi

source ../scripts/find-python.sh
find_python || exit 1
"${PYTHON[@]}" ../scripts/quran_assets.py stage

if [[ "$VARIANT" == release ]]; then TASK=bundleRelease; else TASK=bundleDebug; fi
./gradlew "$TASK" --no-daemon
AAB="app/build/outputs/bundle/$VARIANT/app-$VARIANT.aab"
APKS="build/quran-local-testing-$VARIANT.apks"
mkdir -p build
# Without --ks, bundletool signs with ~/.android/debug.keystore.
bundletool build-apks --bundle="$AAB" --output="$APKS" --local-testing --overwrite
bundletool install-apks --apks="$APKS"
echo "✓ Installed with local Play Asset Delivery. Open the Quran tab to download pages and recitations."

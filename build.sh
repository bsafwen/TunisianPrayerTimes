#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

usage() {
    echo "Usage: $0 [android|desktop|all]"
    echo ""
    echo "  android   Build Android APK + AAB"
    echo "  desktop   Build Desktop native installer + uber JAR"
    echo "  all       Build both (default)"
    exit 1
}

TARGET="${1:-all}"

build_android() {
    echo "══════════════════════════════════════════"
    echo "  Building Android APK + AAB"
    echo "══════════════════════════════════════════"
    cd "$SCRIPT_DIR/android-app"
    ./gradlew assembleRelease bundleRelease --no-daemon

    APK="app/build/outputs/apk/release/app-release.apk"
    AAB="app/build/outputs/bundle/release/app-release.aab"

    echo ""
    if [[ -f "$APK" ]]; then
        echo "✓ APK: $(pwd)/$APK  ($(du -h "$APK" | cut -f1))"
    else
        echo "✗ APK not found" >&2
    fi
    if [[ -f "$AAB" ]]; then
        echo "✓ AAB: $(pwd)/$AAB  ($(du -h "$AAB" | cut -f1))"
    else
        echo "✗ AAB not found" >&2
    fi
}

build_desktop() {
    echo "══════════════════════════════════════════"
    echo "  Building Desktop native installer + uber JAR"
    echo "══════════════════════════════════════════"
    cd "$SCRIPT_DIR/multiplatform"

    # Pick the right native packaging task for the current OS
    case "$(uname -s)" in
        Darwin*)  PACKAGE_TASK="packageReleaseDmg" ;;
        Linux*)   PACKAGE_TASK="packageReleaseDeb" ;;
        MINGW*|MSYS*|CYGWIN*) PACKAGE_TASK="packageReleaseMsi" ;;
        *)        echo "Unknown OS, skipping native installer"; PACKAGE_TASK="" ;;
    esac

    if [[ -n "$PACKAGE_TASK" ]]; then
        ./gradlew ":desktopApp:$PACKAGE_TASK" --no-daemon
    fi

    ./gradlew :desktopApp:packageReleaseUberJarForCurrentOS --no-daemon

    echo ""
    echo "✓ Desktop build complete. Artifacts:"
    find desktopApp/build/compose/binaries -type f \( -name "*.dmg" -o -name "*.msi" -o -name "*.deb" -o -name "*.rpm" \) 2>/dev/null | while read -r f; do
        echo "  $(du -h "$f" | cut -f1)  $f"
    done
    find desktopApp/build/compose/jars -type f -name "*.jar" 2>/dev/null | while read -r f; do
        echo "  $(du -h "$f" | cut -f1)  $f"
    done
}

case "$TARGET" in
    android)  build_android ;;
    desktop)  build_desktop ;;
    all)
        build_android
        echo ""
        build_desktop
        ;;
    *)  usage ;;
esac

echo ""
echo "══════════════════════════════════════════"
echo "  Done."
echo "══════════════════════════════════════════"

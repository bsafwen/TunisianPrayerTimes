#!/bin/bash
# Copies gouvernorats.json into the TV app assets folder.
# Prayer times are computed on the device from data/prayer-formula, which the
# Gradle build bundles automatically; no CSV tables are shipped.
set -e
cd "$(dirname "${BASH_SOURCE[0]}")"

ASSETS_DIR="app/src/main/assets"
SOURCE_DIR="../docs"

mkdir -p "$ASSETS_DIR"

# Copy gouvernorats.json
cp "$SOURCE_DIR/gouvernorats.json" "$ASSETS_DIR/gouvernorats.json"
echo "Copied gouvernorats.json"

echo ""
echo "Assets ready. To build:"
echo "  ./gradlew :app:assembleDebug"

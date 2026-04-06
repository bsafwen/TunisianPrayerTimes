#!/bin/bash
# Copies prayer time CSV data and gouvernorats.json into the TV app assets folder.
set -e
cd "$(dirname "${BASH_SOURCE[0]}")"

ASSETS_DIR="app/src/main/assets"
SOURCE_DIR="../docs"

mkdir -p "$ASSETS_DIR"

# Copy gouvernorats.json
cp "$SOURCE_DIR/gouvernorats.json" "$ASSETS_DIR/gouvernorats.json"
echo "Copied gouvernorats.json"

# Copy CSV data
if [ -d "$ASSETS_DIR/csv" ]; then
    rm -rf "$ASSETS_DIR/csv"
fi
cp -r "$SOURCE_DIR/csv" "$ASSETS_DIR/csv"
echo "Copied csv/ directory"

echo ""
echo "Assets ready. To build:"
echo "  ./gradlew :app:assembleDebug"

#!/bin/bash
# Links the docs/ data directory so the desktop app can find CSV and JSON data at runtime.

set -e
cd "$(dirname "${BASH_SOURCE[0]}")"

DATA_DIR="desktopApp/data"
SOURCE_DIR="../docs"

if [ -L "$DATA_DIR" ]; then
    echo "Symlink already exists: $DATA_DIR"
elif [ -d "$DATA_DIR" ]; then
    echo "Data directory already exists: $DATA_DIR"
else
    ln -s "$(cd "$SOURCE_DIR" && pwd)" "$DATA_DIR"
    echo "Created symlink: $DATA_DIR -> $SOURCE_DIR"
fi

echo ""
echo "To run the desktop app:"
echo "  ./gradlew :desktopApp:run"

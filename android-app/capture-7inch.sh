#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

SERIAL="${SERIAL:-emulator-5558}"
PREFIX="${PREFIX:-tablet-7inch}"

CAPTURE_PHONE=0 \
CAPTURE_TABLET=1 \
TABLET_SERIAL="$SERIAL" \
TABLET_PREFIX="$PREFIX" \
"$SCRIPT_DIR/capture-screenshots.sh"

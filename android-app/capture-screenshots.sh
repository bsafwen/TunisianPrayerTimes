#!/usr/bin/env bash
#
# Capture Play Store screenshots from the Android app.
# Captures all 6 onboarding steps + main screen on both phone and tablet.
#
# Prerequisites:
#   - Two emulators running (phone on 5554, tablet on 5556)
#   - App installed on both (debug or release)
#
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

OUT_DIR="$(cd "$(dirname "$0")" && pwd)/screenshots"
mkdir -p "$OUT_DIR"

# ── Detect which package is installed ──────────────────────────────────────
detect_package() {
    local serial="$1"
    if adb -s "$serial" shell pm list packages 2>/dev/null | grep -q "^package:com.tunisianprayertimes$"; then
        echo "com.tunisianprayertimes"
    elif adb -s "$serial" shell pm list packages 2>/dev/null | grep -q "^package:com.tunisianprayertimes.dev$"; then
        echo "com.tunisianprayertimes.dev"
    else
        echo "ERROR: App not installed on $serial" >&2
        return 1
    fi
}

# ── Take a screenshot and pull it ─────────────────────────────────────────
capture() {
    local serial="$1" name="$2"
    local remote="/sdcard/screenshot.png"
    adb -s "$serial" shell screencap -p "$remote"
    adb -s "$serial" pull "$remote" "$OUT_DIR/$name" >/dev/null 2>&1
    adb -s "$serial" shell rm "$remote"
    echo "  ✓ $name"
}

# ── Wait for UI to stabilise ──────────────────────────────────────────────
wait_ui() {
    sleep 2
}

# ── Grant DND, alarm and battery permissions programmatically ─────────────
grant_permissions() {
    local serial="$1" pkg="$2"
    # Grant DND access (notification policy)
    adb -s "$serial" shell cmd notification allow_dnd "$pkg" 2>/dev/null || true
    # Grant exact alarm permission (Android 12+)
    adb -s "$serial" shell appops set "$pkg" SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
    # Whitelist from battery optimization
    adb -s "$serial" shell dumpsys deviceidle whitelist +"$pkg" 2>/dev/null || true
    # Grant READ_PHONE_STATE
    adb -s "$serial" shell pm grant "$pkg" android.permission.READ_PHONE_STATE 2>/dev/null || true
}

# ── Tap the "Next" / "Start" button using uiautomator ────────────────────
# Finds the button by its test tag (content-desc) or text, then taps its center.
tap_button() {
    local serial="$1" tag="$2"
    local dump="/sdcard/window_dump.xml"

    # Dump UI hierarchy
    adb -s "$serial" shell uiautomator dump "$dump" 2>/dev/null
    local xml
    xml=$(adb -s "$serial" shell cat "$dump" 2>/dev/null)
    adb -s "$serial" shell rm "$dump" 2>/dev/null || true

    # Convert XML to one-node-per-line for easier parsing
    local nodes
    nodes=$(echo "$xml" | sed 's/></>\n</g')

    # Try to find node with matching content-desc or text
    local bounds_raw
    bounds_raw=$(echo "$nodes" | grep "content-desc=\"[^\"]*${tag}" | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' || true)

    if [ -z "$bounds_raw" ]; then
        bounds_raw=$(echo "$nodes" | grep "text=\"${tag}\"" | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' || true)
    fi

    if [ -z "$bounds_raw" ]; then
        echo "  ⚠ Could not find button '$tag' – skipping tap"
        return 1
    fi

    # Parse bounds and compute center
    local x1 y1 x2 y2
    read -r x1 y1 x2 y2 <<< "$bounds_raw"

    local cx=$(( (x1 + x2) / 2 ))
    local cy=$(( (y1 + y2) / 2 ))

    adb -s "$serial" shell input tap "$cx" "$cy"
}

# ── Navigate onboarding using text-based button search ────────────────────
tap_next_button() {
    local serial="$1"
    tap_button "$serial" "التالي" || tap_button "$serial" "onboarding_next"
}

tap_start_button() {
    local serial="$1"
    # Start button replaces Next button at the same position (left side, bottom row)
    # Use same coordinates — falls back to direct tap if text search fails
    tap_button "$serial" "ابدأ" || tap_button "$serial" "onboarding_start" || {
        echo "  → Tapping Start button position directly"
        adb -s "$serial" shell input tap 308 2032
    }
}

# ── Capture all screens for one device ────────────────────────────────────
capture_device() {
    local serial="$1" prefix="$2"
    local pkg
    pkg=$(detect_package "$serial")

    echo ""
    echo "━━━ Capturing on $serial ($prefix) — package: $pkg ━━━"

    # 1. Clear app data to trigger fresh onboarding
    echo "  Clearing app data..."
    adb -s "$serial" shell pm clear "$pkg" >/dev/null 2>&1

    # 2. Launch the app first (onboarding appears)
    echo "  Launching app..."
    adb -s "$serial" shell am start -n "$pkg/com.tunisianprayertimes.MainActivity" >/dev/null 2>&1
    wait_ui

    # 3. Grant permissions while app is running
    echo "  Granting permissions..."
    grant_permissions "$serial" "$pkg"

    # 4. Capture onboarding steps 0..4 (welcome through jomoaa)
    local step_names=("welcome" "duration" "delay" "fixed-time" "jomoaa")
    for i in 0 1 2 3 4; do
        wait_ui
        capture "$serial" "${prefix}-onboarding-${i}-${step_names[$i]}.png"
        tap_next_button "$serial"
        sleep 1
    done

    # 5. Permissions auto-advances when granted — home+resume triggers refresh → Ready
    adb -s "$serial" shell input keyevent KEYCODE_HOME
    sleep 1
    adb -s "$serial" shell am start -n "$pkg/com.tunisianprayertimes.MainActivity" >/dev/null 2>&1
    sleep 3

    # 6. Capture Ready step
    wait_ui
    capture "$serial" "${prefix}-onboarding-5-ready.png"

    # 7. Tap "Start" → main screen
    tap_start_button "$serial"
    wait_ui
    wait_ui

    # 8o "  Done with $prefix!"
}

# ── Main ──────────────────────────────────────────────────────────────────
echo "╔══════════════════════════════════════════════╗"
echo "║  Tunisian Prayer Times – Screenshot Capture  ║"
echo "╚══════════════════════════════════════════════╝"

# Check connected devices
DEVICES=$(adb devices | grep -c "device$" || true)
if [ "$DEVICES" -lt 1 ]; then
    echo "ERROR: No emulators connected. Start your emulators first."
    exit 1
fi

echo "Output directory: $OUT_DIR"
echo "Connected devices: $DEVICES"

# Capture phone (emulator-5554)
if adb devices | grep -q "emulator-5554"; then
    capture_device "emulator-5554" "phone"
else
    echo "⚠ Phone emulator (5554) not found, skipping"
fi

# Capture tablet (emulator-5556)
if adb devices | grep -q "emulator-5556"; then
    capture_device "emulator-5556" "tablet"
else
    echo "⚠ Tablet emulator (5556) not found, skipping"
fi

echo ""
echo "━━━ All screenshots saved to: $OUT_DIR ━━━"
ls -la "$OUT_DIR"/*.png 2>/dev/null || echo "(no files)"

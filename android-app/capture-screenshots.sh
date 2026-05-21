#!/usr/bin/env bash
#
# Capture Play Store screenshots from the Android app.
# Captures onboarding, main, and qibla screens on both phone and tablet.
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

PHONE_SERIAL="${PHONE_SERIAL:-emulator-5554}"
PHONE_PREFIX="${PHONE_PREFIX:-phone}"
TABLET_SERIAL="${TABLET_SERIAL:-emulator-5556}"
TABLET_PREFIX="${TABLET_PREFIX:-tablet}"
CAPTURE_PHONE="${CAPTURE_PHONE:-1}"
CAPTURE_TABLET="${CAPTURE_TABLET:-1}"

# ── Detect which package is installed ──────────────────────────────────────
detect_package() {
    local serial="$1"

    if [ -n "${SCREENSHOT_PACKAGE:-}" ]; then
        if adb -s "$serial" shell pm list packages 2>/dev/null | grep -q "^package:${SCREENSHOT_PACKAGE}$"; then
            echo "$SCREENSHOT_PACKAGE"
            return 0
        fi
        echo "ERROR: Requested package '$SCREENSHOT_PACKAGE' is not installed on $serial" >&2
        return 1
    fi

    if adb -s "$serial" shell pm list packages 2>/dev/null | grep -q "^package:com.tunisianprayertimes.dev$"; then
        echo "com.tunisianprayertimes.dev"
    elif adb -s "$serial" shell pm list packages 2>/dev/null | grep -q "^package:com.tunisianprayertimes$"; then
        echo "com.tunisianprayertimes"
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

ui_contains_text() {
    local serial="$1" text="$2"
    local dump="/sdcard/window_dump.xml"

    adb -s "$serial" shell uiautomator dump "$dump" >/dev/null 2>&1 || return 1
    local xml
    xml=$(adb -s "$serial" shell cat "$dump" 2>/dev/null)
    adb -s "$serial" shell rm "$dump" 2>/dev/null || true

    echo "$xml" | grep -q "$text"
}

wait_for_text() {
    local serial="$1" text="$2" attempts="${3:-12}"

    for _ in $(seq 1 "$attempts"); do
        if ui_contains_text "$serial" "$text"; then
            return 0
        fi
        sleep 1
    done

    return 1
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
    # Grant location so the qibla screenshot can compute the compass state directly
    adb -s "$serial" shell pm grant "$pkg" android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
    adb -s "$serial" shell pm grant "$pkg" android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
}

# ── Seed a stable Tunis location for qibla screenshots ───────────────────
prepare_qibla_location() {
    local serial="$1"
    adb -s "$serial" shell cmd location set-location-enabled true 2>/dev/null || true
    adb -s "$serial" shell appops set com.android.shell android:mock_location allow 2>/dev/null || \
        adb -s "$serial" shell appops set com.android.shell MOCK_LOCATION allow 2>/dev/null || true

    for provider in gps network; do
        adb -s "$serial" shell cmd location providers add-test-provider "$provider" >/dev/null 2>&1 || \
            adb -s "$serial" shell cmd location providers add-test-provider "$provider" android.hardware.location.gps true true true false 1 1 >/dev/null 2>&1 || true
        adb -s "$serial" shell cmd location providers set-test-provider-enabled "$provider" true >/dev/null 2>&1 || true
        adb -s "$serial" shell cmd location providers set-test-provider-location "$provider" --location 36.8065,10.1815 >/dev/null 2>&1 || true
    done

    # adb emu geo fix expects longitude latitude altitude.
    adb -s "$serial" emu geo fix 10.1815 36.8065 0 >/dev/null 2>&1 || true
}

mark_onboarding_finished() {
    local serial="$1" pkg="$2"
    adb -s "$serial" shell "run-as $pkg sh -c 'cd /data/data/$pkg && mkdir -p shared_prefs && printf \"%s\\n\" \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?>\" \"<map>\" \"    <boolean name=\\\"first_launch_done\\\" value=\\\"true\\\" />\" \"</map>\" > shared_prefs/prayer_silence_prefs.xml'" >/dev/null 2>&1
}

activity_is_onboarding() {
    local serial="$1" pkg="$2"
    adb -s "$serial" shell dumpsys activity top 2>/dev/null | grep -q "$pkg/com.tunisianprayertimes.OnboardingActivity"
}

open_main_screen_after_onboarding() {
    local serial="$1" pkg="$2"

    tap_start_button "$serial"
    wait_ui
    wait_ui

    if activity_is_onboarding "$serial" "$pkg"; then
        echo "  → Start tap did not finish onboarding; marking first launch complete"
        if mark_onboarding_finished "$serial" "$pkg"; then
            adb -s "$serial" shell am force-stop "$pkg" >/dev/null 2>&1 || true
            adb -s "$serial" shell am start -n "$pkg/com.tunisianprayertimes.MainActivity" >/dev/null 2>&1
            wait_for_text "$serial" "مواقيت الصلاة" 15 || wait_ui
        else
            echo "  ⚠ Could not mark onboarding complete with run-as; screenshots may remain on onboarding"
        fi
    fi
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
    if tap_button "$serial" "ابدأ" || tap_button "$serial" "onboarding_start"; then
        return 0
    fi

    # RTL layout puts the primary Start button on the left half.
    local w h
    w=$(adb -s "$serial" shell wm size | sed 's/.*: //' | cut -dx -f1)
    h=$(adb -s "$serial" shell wm size | sed 's/.*: //' | cut -dx -f2)
    echo "  → Tapping Start button by coordinate fallback"
    adb -s "$serial" shell input tap $(( w / 3 )) $(( h * 5 / 6 ))
}

tap_qibla_tab() {
    local serial="$1"
    if tap_button "$serial" "القبلة"; then
        return 0
    fi

    local w h
    w=$(adb -s "$serial" shell wm size | sed 's/.*: //' | cut -dx -f1)
    h=$(adb -s "$serial" shell wm size | sed 's/.*: //' | cut -dx -f2)
    echo "  → Tapping Qibla tab by coordinate fallback"
    adb -s "$serial" shell input tap $(( w / 6 )) $(( h - 80 ))
}

tap_qibla_compute_button() {
    local serial="$1"
    if tap_button "$serial" "إحسب اتجاه القبلة"; then
        return 0
    fi

    local dump="/sdcard/window_dump.xml"
    adb -s "$serial" shell uiautomator dump "$dump" 2>/dev/null || true
    local xml nodes bounds_raw
    xml=$(adb -s "$serial" shell cat "$dump" 2>/dev/null || true)
    adb -s "$serial" shell rm "$dump" 2>/dev/null || true
    nodes=$(echo "$xml" | sed 's/></>\n</g')
    bounds_raw=$(echo "$nodes" | grep 'clickable="true"' | head -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' || true)
    if [ -n "$bounds_raw" ]; then
        local x1 y1 x2 y2
        read -r x1 y1 x2 y2 <<< "$bounds_raw"
        echo "  → Tapping Qibla compute button at detected bounds"
        adb -s "$serial" shell input tap $(( (x1 + x2) / 2 )) $(( (y1 + y2) / 2 ))
        return 0
    fi

    echo "  → Tapping Qibla compute button by coordinate fallback"
    local w h
    w=$(adb -s "$serial" shell wm size | sed 's/.*: //' | cut -dx -f1)
    h=$(adb -s "$serial" shell wm size | sed 's/.*: //' | cut -dx -f2)
    adb -s "$serial" shell input tap $(( w / 2 )) $(( h * 4 / 5 ))
}

capture_qibla_screen() {
    local serial="$1" prefix="$2"

    echo "  Opening Qibla screen..."
    prepare_qibla_location "$serial"
    tap_qibla_tab "$serial"
    wait_for_text "$serial" "اتجاه القبلة" 10 || wait_ui

    echo "  Computing Qibla direction..."
    tap_qibla_compute_button "$serial"
    wait_for_text "$serial" "حسب موقعك الحالي" 30 || sleep 5

    capture "$serial" "${prefix}-qibla.png"
}

# ── Capture all screens for one device ────────────────────────────────────
capture_device() {
    local serial="$1" prefix="$2"
    local pkg
    pkg=$(detect_package "$serial")

    echo ""
    echo "━━━ Capturing on $serial ($prefix) — package: $pkg ━━━"
    rm -f \
        "$OUT_DIR/${prefix}-main-screen.png" \
        "$OUT_DIR/${prefix}-qibla.png" \
        "$OUT_DIR/${prefix}-onboarding-"*.png

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

    # 4. Capture onboarding steps 0..5 (welcome through wake alarm)
    local step_names=("welcome" "duration" "delay" "fixed-time" "jomoaa" "wake-alarm")
    for i in 0 1 2 3 4 5; do
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
    capture "$serial" "${prefix}-onboarding-6-ready.png"

    # 7. Tap "Start" → main screen
    open_main_screen_after_onboarding "$serial" "$pkg"
    wait_for_text "$serial" "مواقيت الصلاة" 15 || wait_ui

    # 8. Capture main screen
    capture "$serial" "${prefix}-main-screen.png"

    # 9. Capture qibla after computing direction so the compass is active
    capture_qibla_screen "$serial" "$prefix"

    echo "  Done with $prefix!"
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

# Capture phone and tablet in parallel
PIDS=()

if [ "$CAPTURE_PHONE" != "0" ] && adb devices | grep -q "^${PHONE_SERIAL}[[:space:]]"; then
    capture_device "$PHONE_SERIAL" "$PHONE_PREFIX" &
    PIDS+=($!)
else
    echo "⚠ Phone emulator ($PHONE_SERIAL) not found or disabled, skipping"
fi

if [ "$CAPTURE_TABLET" != "0" ] && adb devices | grep -q "^${TABLET_SERIAL}[[:space:]]"; then
    capture_device "$TABLET_SERIAL" "$TABLET_PREFIX" &
    PIDS+=($!)
else
    echo "⚠ Tablet emulator ($TABLET_SERIAL) not found or disabled, skipping"
fi

# Wait for all captures to finish
FAILED=false
for pid in "${PIDS[@]}"; do
    if ! wait "$pid"; then
        FAILED=true
    fi
done

echo ""
echo "━━━ All screenshots saved to: $OUT_DIR ━━━"
ls -la "$OUT_DIR"/*.png 2>/dev/null || echo "(no files)"

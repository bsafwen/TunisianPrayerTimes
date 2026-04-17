#!/usr/bin/env bash
set -euo pipefail

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

SERIAL="emulator-5558"
PREFIX="tablet-7inch"
PKG="com.tunisianprayertimes"
OUT="$(cd "$(dirname "$0")" && pwd)/screenshots"

capture() {
    adb -s "$SERIAL" shell screencap -p /sdcard/ss.png
    adb -s "$SERIAL" pull /sdcard/ss.png "$OUT/${1}" >/dev/null 2>&1
    adb -s "$SERIAL" shell rm /sdcard/ss.png
    echo "  ✓ $1"
}

tap_last_clickable() {
    local dump="/sdcard/wd.xml"
    timeout 10 adb -s "$SERIAL" shell uiautomator dump "$dump" 2>/dev/null || {
        echo "  ⚠ uiautomator dump timed out, using coordinate fallback"
        # 800x1280 screen: Next button is bottom-left area (RTL layout)
        adb -s "$SERIAL" shell input tap 200 1200
        return
    }
    local xml
    xml=$(adb -s "$SERIAL" shell cat "$dump" 2>/dev/null)
    adb -s "$SERIAL" shell rm "$dump" 2>/dev/null || true
    local bounds
    bounds=$(echo "$xml" | sed 's/></>\n</g' | grep 'clickable="true"' | tail -1 | sed -n 's/.*bounds="\[\([0-9]*\),\([0-9]*\)\]\[\([0-9]*\),\([0-9]*\)\]".*/\1 \2 \3 \4/p' || true)
    if [ -n "$bounds" ]; then
        local x1 y1 x2 y2
        read -r x1 y1 x2 y2 <<< "$bounds"
        local cx=$(( (x1 + x2) / 2 ))
        local cy=$(( (y1 + y2) / 2 ))
        echo "  → Tapping ($cx, $cy)"
        adb -s "$SERIAL" shell input tap "$cx" "$cy"
    else
        echo "  ⚠ No clickable element found, using coordinate fallback"
        adb -s "$SERIAL" shell input tap 200 1200
    fi
}

echo "=== Capturing on $SERIAL ($PREFIX) ==="

# Clear and launch
adb -s "$SERIAL" shell pm clear "$PKG" >/dev/null 2>&1
echo "  Launching app..."
adb -s "$SERIAL" shell am start -n "$PKG/com.tunisianprayertimes.MainActivity" >/dev/null 2>&1
sleep 2

# Grant permissions
echo "  Granting permissions..."
adb -s "$SERIAL" shell cmd notification allow_dnd "$PKG" 2>/dev/null || true
adb -s "$SERIAL" shell appops set "$PKG" SCHEDULE_EXACT_ALARM allow 2>/dev/null || true
adb -s "$SERIAL" shell dumpsys deviceidle whitelist +"$PKG" 2>/dev/null || true
adb -s "$SERIAL" shell pm grant "$PKG" android.permission.READ_PHONE_STATE 2>/dev/null || true

# Capture onboarding steps 0-4
STEP_NAMES=("welcome" "duration" "delay" "fixed-time" "jomoaa")
for i in 0 1 2 3 4; do
    sleep 2
    capture "${PREFIX}-onboarding-${i}-${STEP_NAMES[$i]}.png"
    tap_last_clickable
    sleep 1
done

# Home+resume to trigger permissions auto-advance → Ready
echo "  Home+resume for permissions auto-advance..."
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME
sleep 1
adb -s "$SERIAL" shell am start -n "$PKG/com.tunisianprayertimes.MainActivity" >/dev/null 2>&1
sleep 5

# Capture Ready step
capture "${PREFIX}-onboarding-5-ready.png"

# Tap Start
echo "  Tapping Start..."
tap_last_clickable
sleep 4

# Capture main screen
capture "${PREFIX}-main-screen.png"

echo "=== Done! ==="
ls -la "$OUT/${PREFIX}"*.png

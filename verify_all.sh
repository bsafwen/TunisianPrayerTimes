#!/bin/bash
ADB=~/Library/Android/sdk/platform-tools/adb
DEVICE=19031FDF6005C6
DIR=/tmp/delegation_screenshots
mkdir -p "$DIR"

TOTAL=$(wc -l < /tmp/all_delegations.txt | tr -d ' ')

for i in $(seq 1 $TOTAL); do
    LINE=$(sed -n "${i}p" /tmp/all_delegations.txt)
    gov=$(echo "$LINE" | cut -d'|' -f1)
    name=$(echo "$LINE" | cut -d'|' -f2)
    lat=$(echo "$LINE" | cut -d'|' -f3)
    lng=$(echo "$LINE" | cut -d'|' -f4)

    LABEL=$(echo "${name} - ${gov}" | sed 's/ /+/g' | sed "s/'//g")

    $ADB -s "$DEVICE" shell "am start -a android.intent.action.VIEW -d 'geo:0,0?q=${lat},${lng}(${LABEL})' com.google.android.apps.maps" </dev/null >/dev/null 2>&1

    sleep 3

    FILENAME=$(printf "%03d_%s_%s.png" "$i" "$gov" "$name" | tr ' ' '_' | tr "'" '_' | tr -cd 'A-Za-z0-9._-')
    $ADB -s "$DEVICE" shell screencap -p /sdcard/screen.png </dev/null
    $ADB -s "$DEVICE" pull /sdcard/screen.png "${DIR}/${FILENAME}" </dev/null >/dev/null 2>&1

    echo "[$i/$TOTAL] $gov / $name ($lat, $lng)"
done
echo "DONE - all $TOTAL delegations verified"

#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
TARGET_PACKAGE="${1:-com.openlauncher.pipprobe}"
ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB+=( -s "$SERIAL" )
fi

"${ADB[@]}" wait-for-device
"${ADB[@]}" shell am force-stop "$TARGET_PACKAGE"
"${ADB[@]}" shell am force-stop com.openlauncher.app
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am start \
    -n com.openlauncher.app/.debug.PipPlacementProbeActivity \
    --es target_package "$TARGET_PACKAGE"

printf 'Cold placement probe started for %s.\n' "$TARGET_PACKAGE"
printf 'Watching launch and placement logs; press Ctrl-C after the result appears.\n'

"${ADB[@]}" logcat -v threadtime \
    TaskEmbedder:D PipPlacementProbe:I ActivityTaskManager:I ActivityManager:I '*:S'

#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
LEFT_PACKAGE="${1:-com.openlauncher.pipprobe}"
RIGHT_PACKAGE="${2:-app.morphe.android.apps.maps}"
ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB+=( -s "$SERIAL" )
fi

"${ADB[@]}" wait-for-device
"${ADB[@]}" shell am force-stop "$LEFT_PACKAGE"
"${ADB[@]}" shell am force-stop "$RIGHT_PACKAGE"
"${ADB[@]}" shell am force-stop com.openlauncher.app
"${ADB[@]}" logcat -c
"${ADB[@]}" shell am start \
    -n com.openlauncher.app/.debug.PipDividerProbeActivity \
    --es left_package "$LEFT_PACKAGE" \
    --es right_package "$RIGHT_PACKAGE"

printf 'Dual-pane probe started: %s | %s\n' "$LEFT_PACKAGE" "$RIGHT_PACKAGE"
printf '%s\n' 'Drag the center divider and watch resize/input logs; press Ctrl-C when done.'
"${ADB[@]}" logcat -v threadtime TaskEmbedder:D PipPlacementProbe:I '*:S'

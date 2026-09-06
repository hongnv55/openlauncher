#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
TARGET_PACKAGE="${1:-com.openlauncher.pipprobe}"
ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB+=( -s "$SERIAL" )
fi

"${ADB[@]}" wait-for-device
printf '%s\n' '=== DISPLAY ==='
"${ADB[@]}" shell dumpsys display
printf '%s\n' '=== ACTIVITY STACKS ==='
"${ADB[@]}" shell dumpsys activity activities
printf '%s\n' '=== PENDING INTENTS ==='
"${ADB[@]}" shell dumpsys activity intents
printf '%s\n' "Inspect entries containing: $TARGET_PACKAGE"

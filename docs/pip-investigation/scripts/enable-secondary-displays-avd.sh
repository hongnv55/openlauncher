#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADB=(adb)
if [[ -n "$SERIAL" ]]; then
    ADB+=( -s "$SERIAL" )
fi

"${ADB[@]}" wait-for-device
if [[ "$("${ADB[@]}" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
    printf '%s\n' 'Refusing to modify /system: the connected device is not an emulator.' >&2
    exit 1
fi

"${ADB[@]}" root
"${ADB[@]}" wait-for-device
"${ADB[@]}" remount
"${ADB[@]}" push \
    "$SCRIPT_DIR/secondary-displays-feature.xml" \
    /system/etc/permissions/openlauncher-secondary-displays.xml
"${ADB[@]}" reboot
"${ADB[@]}" wait-for-device

while [[ "$("${ADB[@]}" shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; do
    sleep 1
done

if ! "${ADB[@]}" shell pm list features | tr -d '\r' | \
    grep -q '^feature:android.software.activities_on_secondary_displays$'; then
    printf '%s\n' 'Feature was not registered after reboot.' >&2
    exit 1
fi

printf '%s\n' 'Secondary-display activity support is enabled on this AVD.'

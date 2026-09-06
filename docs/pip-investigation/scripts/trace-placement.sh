#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FRIDA="${FRIDA_BIN:-frida}"
ARGS=( -U -n system_server -l "$SCRIPT_DIR/trace-placement.js" )

if [[ -n "$SERIAL" ]]; then
    ARGS=( -D "$SERIAL" -n system_server -l "$SCRIPT_DIR/trace-placement.js" )
fi

exec "$FRIDA" "${ARGS[@]}"

#!/usr/bin/env bash
# Builds app/libs/framework-stubs.jar from framework-stubs/src.
#
# These are compile-time-only declarations for @hide framework classes that are
# missing from the public API-28 android.jar but present at runtime on the target
# image (the PIP embedding device/emulator). The jar is wired in as `compileOnly`
# in app/build.gradle.kts, so nothing here lands in the APK — at runtime, Kotlin's
# `object : android.app.TaskStackListener() { ... }` resolves against the real
# hidden class shipped in the device's boot image instead.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

find_sdk() {
  if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT" ]]; then
    printf '%s\n' "$ANDROID_SDK_ROOT"; return
  fi
  if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
    printf '%s\n' "$ANDROID_HOME"; return
  fi
  local candidate
  for candidate in "$HOME/android-sdk" "$HOME/Android/Sdk" "$HOME/Android/sdk" "/opt/android-sdk" "/usr/lib/android-sdk"; do
    if [[ -d "$candidate" ]]; then printf '%s\n' "$candidate"; return; fi
  done
  echo "Android SDK not found. Export ANDROID_SDK_ROOT." >&2
  exit 2
}

SDK="$(find_sdk)"
ANDROID_JAR="$SDK/platforms/android-28/android.jar"
[[ -f "$ANDROID_JAR" ]] || { echo "Missing $ANDROID_JAR" >&2; exit 2; }

SRC_DIR="$PROJECT_ROOT/framework-stubs/src"
OUT_JAR="$PROJECT_ROOT/app/libs/framework-stubs.jar"
BUILD_DIR="$PROJECT_ROOT/framework-stubs/build/classes"

JAVAC="${STUBS_JAVAC:-javac}"
JAR="${STUBS_JAR:-jar}"
command -v "$JAVAC" >/dev/null || { echo "javac not found; set STUBS_JAVAC." >&2; exit 2; }
command -v "$JAR" >/dev/null || { echo "jar not found; set STUBS_JAR." >&2; exit 2; }

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR" "$(dirname "$OUT_JAR")"

mapfile -t SOURCES < <(find "$SRC_DIR" -name '*.java')
[[ ${#SOURCES[@]} -gt 0 ]] || { echo "No stub sources under $SRC_DIR" >&2; exit 2; }

# -source/-target 8: matches these @hide class signatures to the Pie-era API
# they were captured from, independent of the app module's own Kotlin/JVM target.
"$JAVAC" \
  -source 8 -target 8 \
  -bootclasspath "$ANDROID_JAR" \
  -d "$BUILD_DIR" \
  "${SOURCES[@]}"

"$JAR" cf "$OUT_JAR" -C "$BUILD_DIR" .

echo "Stub jar: $OUT_JAR"
"$JAR" tf "$OUT_JAR"

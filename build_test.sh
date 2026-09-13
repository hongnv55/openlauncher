# cd "/media/hongnguyen/data/car/bingo/update-0120-E260-V4.00.23-extracted/tools/openlauncher" && ./gradlew :app:assembleAospDebug --console=plain 2>&1 | tail -10 && \
# APKSIGNER="$HOME/android-sdk/build-tools/30.0.3/apksigner" "/media/hongnguyen/data/car/bingo/update-0120-E260-V4.00.23-extracted/.claude/keys/sign.sh" app/build/outputs/apk/aospDebug/OpenLauncher-v0.0.9.apk app/build/outputs/apk/aospDebug/OpenLauncher-v0.0.9-platform-signed.apk && \
# export PATH="$HOME/android-sdk/platform-tools:$PATH" && \
# adb install -r app/build/outputs/apk/aospDebug/OpenLauncher-v0.0.9-platform-signed.apk && \
# adb shell am force-stop com.openlauncher.app && \
# adb shell am start -n com.openlauncher.app/com.openlauncher.app.MainActivity



# cd "/media/hongnguyen/data/car/bingo/update-0120-E260-V4.00.23-extracted/tools/openlauncher" && ./gradlew :app:assembleRelease --console=plain 2>&1 | tail -10 && \
APKSIGNER="$HOME/android-sdk/build-tools/30.0.3/apksigner" "/media/hongnguyen/data/car/bingo/update-0120-E260-V4.00.23-extracted/.claude/keys/sign.sh" app/build/outputs/apk/release/OpenLauncher-v1.0.0-beta.apk app/build/outputs/apk/release/OpenLauncher-v1.0.0-beta-platform-signed.apk && \
export PATH="$HOME/android-sdk/platform-tools:$PATH" && \
adb install -r app/build/outputs/apk/release/OpenLauncher-v1.0.0-beta-platform-signed.apk && \
adb shell am force-stop com.openlauncher.app && \
adb shell am start -n com.openlauncher.app/com.openlauncher.app.MainActivity
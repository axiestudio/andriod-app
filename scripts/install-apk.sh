#!/usr/bin/env bash
# install-apk.sh — build (if needed) and install the debug APK on the attached device.
# Usage: ./scripts/install-apk.sh   (phone must have USB debugging authorized)
set -euo pipefail

MOBILE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$SDK_DIR/platform-tools/adb"
APK="$MOBILE_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [ ! -f "$APK" ]; then
  echo "APK not found — building…"
  "$MOBILE_DIR/gradlew" -p "$MOBILE_DIR" assembleDebug --console=plain
fi

"$ADB" devices
"$ADB" install -r "$APK"
echo "installed: $APK"
echo "launch: $ADB shell am start -n com.axie.remote/.MainActivity"

#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PROJECT_DIR=$(dirname -- "$SCRIPT_DIR")
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
ADB=${ADB:-"${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platform-tools/adb"}

if [ ! -f "$APK" ]; then
  echo "Debug APK is missing. Run ./scripts/build-android.sh first." >&2
  exit 1
fi

"$ADB" install -r "$APK"

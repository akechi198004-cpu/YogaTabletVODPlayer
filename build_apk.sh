#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/android_app"

if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle is required. Run the Gradle build from a machine with the Android SDK configured." >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "Set ANDROID_HOME or ANDROID_SDK_ROOT to the installed Android SDK." >&2
  exit 1
fi

cd "$PROJECT_DIR"
gradle --offline :app:assembleDebug --console=plain --no-daemon --max-workers=2
echo "TV debug APK: $PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"

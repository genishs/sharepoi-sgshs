#!/usr/bin/env bash
# Build a signed release AAB for Google Play. Run on a machine with the Android SDK.
# Keystore password: read from the file $SHAREPOI_KEYSTORE_PW_FILE if it exists,
# otherwise prompted interactively. The password is never echoed or written to disk by this script.
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export SHAREPOI_KEYSTORE="${SHAREPOI_KEYSTORE:-$HOME/sharepoi-upload.jks}"
export SHAREPOI_KEY_ALIAS="${SHAREPOI_KEY_ALIAS:-upload}"
SHAREPOI_KEYSTORE_PW_FILE="${SHAREPOI_KEYSTORE_PW_FILE:-$HOME/sharepoi-upload.pass}"
if [ -f "$SHAREPOI_KEYSTORE_PW_FILE" ]; then
  # first line only, trailing CR/LF removed
  IFS= read -r SHAREPOI_KEYSTORE_PW < "$SHAREPOI_KEYSTORE_PW_FILE" || true
  SHAREPOI_KEYSTORE_PW="${SHAREPOI_KEYSTORE_PW%$'\r'}"
  if [ -z "$SHAREPOI_KEYSTORE_PW" ]; then echo "Keystore password file is empty: $SHAREPOI_KEYSTORE_PW_FILE" >&2; exit 1; fi
else
  read -r -s -p "Keystore password ($SHAREPOI_KEYSTORE): " SHAREPOI_KEYSTORE_PW; echo
fi
export SHAREPOI_KEYSTORE_PW
./gradlew -q bundleRelease
ls -la app/build/outputs/bundle/release/app-release.aab

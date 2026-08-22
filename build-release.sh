#!/usr/bin/env bash
# Build a signed release AAB for Google Play. Run on a machine with the Android SDK.
# Prompts for the upload keystore password; nothing is written to disk.
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export SHAREPOI_KEYSTORE="${SHAREPOI_KEYSTORE:-$HOME/sharepoi-upload.jks}"
export SHAREPOI_KEY_ALIAS="${SHAREPOI_KEY_ALIAS:-upload}"
read -r -s -p "Keystore password ($SHAREPOI_KEYSTORE): " SHAREPOI_KEYSTORE_PW; echo
export SHAREPOI_KEYSTORE_PW
./gradlew -q bundleRelease
ls -la app/build/outputs/bundle/release/app-release.aab

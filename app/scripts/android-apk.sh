#!/bin/sh
# Build the debug Android APK (aarch64) for dougaka Studio.
# Requires: Android SDK + NDK 27, JDK 21, Rust target aarch64-linux-android,
# and $HOME/.cargo/bin first on PATH (Homebrew rustc lacks the android target).
set -eu
root=$(cd "$(dirname "$0")/.." && pwd)
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
if [ -z "${NDK_HOME:-}" ]; then NDK_HOME=$(ls -d "$ANDROID_HOME"/ndk/* | sort | tail -1); fi
export ANDROID_HOME NDK_HOME
export PATH="$HOME/.cargo/bin:$PATH"
cd "$root"
test -x node_modules/.bin/tauri || npm ci
npm run build
if [ ! -d "$root/src-tauri/gen/android" ]; then
  node_modules/.bin/tauri android init --ci
fi
# AGP packages incrementally into the old APK and keeps the space of replaced entries; start from none.
rm -rf "$root/src-tauri/gen/android/app/build/outputs/apk" "$root/src-tauri/gen/android/app/build/intermediates/apk"
node_modules/.bin/tauri android build --debug --apk --target aarch64 --ci
apk="$root/src-tauri/gen/android/app/build/outputs/apk/universal/debug/app-universal-debug.apk"
test -f "$apk"
shasum -a 256 "$apk"

#!/bin/sh
# Build an Android APK for dougaka Studio.
set -eu

if [ "$#" -gt 2 ]; then
  echo "Usage: $0 [aarch64|x86_64] [debug|release]" >&2
  exit 2
fi
target=${1:-aarch64}
variant=${2:-debug}
case "$target" in
  aarch64) abi=arm64-v8a ;;
  x86_64) abi=x86_64 ;;
  *) echo "Unsupported Android target: $target" >&2; exit 2 ;;
esac
case "$variant" in
  debug) set -- --debug ;;
  release) set -- ;;
  *) echo "Unsupported Android build variant: $variant" >&2; exit 2 ;;
esac

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
node_modules/.bin/tauri android build --apk --target "$target" --ci "$@"

set -- "$root/src-tauri/gen/android/app/build/outputs/apk/universal/$variant/"*.apk
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
  echo "Expected exactly one $variant universal APK for $target" >&2
  exit 1
fi
apk=$1
if ! entries=$(unzip -Z1 "$apk"); then
  echo "Unable to read APK entries for $target" >&2
  exit 1
fi
if ! printf '%s\n' "$entries" | grep -Fxq "lib/$abi/libdougaka_app_lib.so"; then
  echo "APK missing the dougaka native library for $abi" >&2
  exit 1
fi
shasum -a 256 "$apk"

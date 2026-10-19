#!/usr/bin/env bash
#
# Build the xraycli Go helper for Android as a PIE and place it where the app
# extracts it at runtime. The output is named libxraycore.so because Android
# only extracts files from nativeLibraryDir that look like shared libraries;
# the file itself is a PIE with an INTERP segment, so it runs as an executable.
#
# Usage:
#   scripts/build-xraycli.sh
#
# Environment:
#   ANDROID_NDK_HOME  NDK to build with
#                     (default: C:/Users/edmon/AppData/Local/Android/Sdk/ndk/23.1.7779620)
#   LIBXRAY_DIR       libXray checkout (default: <source root>/libXray;
#                     cloned at v26.10.17 when missing)
#
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ROOT_DIR="$(cd "$APP_DIR/.." && pwd)"

LIBXRAY_DIR="${LIBXRAY_DIR:-$ROOT_DIR/libXray}"
LIBXRAY_TAG="v26.10.17"
LIBXRAY_URL="https://github.com/xtls/libXray.git"
NDK_DIR="${ANDROID_NDK_HOME:-C:/Users/edmon/AppData/Local/Android/Sdk/ndk/23.1.7779620}"
TOOLCHAIN_BIN="$NDK_DIR/toolchains/llvm/prebuilt/windows-x86_64/bin"
READELF="$TOOLCHAIN_BIN/llvm-readelf.exe"
JNI_DIR="$APP_DIR/app/src/main/jniLibs"

die() { echo "ERROR: $*" >&2; exit 1; }

if [ ! -d "$LIBXRAY_DIR" ]; then
  echo "libXray not found at $LIBXRAY_DIR; cloning $LIBXRAY_TAG"
  git clone --depth 1 --branch "$LIBXRAY_TAG" "$LIBXRAY_URL" "$LIBXRAY_DIR"
fi
[ -f "$LIBXRAY_DIR/go.mod" ] || die "not a libXray checkout: $LIBXRAY_DIR"
[ -d "$NDK_DIR" ] || die "NDK not found: $NDK_DIR (set ANDROID_NDK_HOME)"
[ -x "$READELF" ] || die "llvm-readelf.exe not found: $READELF"

# Android refuses to exec a file that is not a PIE with an INTERP segment, and
# devices with 16 KB pages need every LOAD segment aligned to 0x4000.
check_elf() {
  local file="$1"
  local program aligns bad

  "$READELF" -h "$file" | grep -Eq 'Type:[[:space:]]+DYN' \
    || die "$file is not a PIE (ELF type is not DYN)"

  program="$("$READELF" -l "$file")"
  printf '%s\n' "$program" | grep -Eq '^[[:space:]]*INTERP[[:space:]]' \
    || die "$file has no INTERP segment; it cannot run from nativeLibraryDir"

  aligns="$(printf '%s\n' "$program" | awk '$1 == "LOAD" { print $NF }')"
  [ -n "$aligns" ] || die "$file has no LOAD segments"
  bad="$(printf '%s\n' "$aligns" | awk '$1 != "0x4000" { print; exit }')"
  [ -z "$bad" ] || die "$file has a LOAD segment aligned $bad; expected 0x4000 for 16 KB pages"
}

build_abi() {
  local abi="$1" goarch="$2" goarm="$3" cc_name="$4" out="$5"
  local cc="$TOOLCHAIN_BIN/$cc_name"
  local env_args=(CGO_ENABLED=1 GOOS=android GOARCH="$goarch" "CC=$cc" GOTOOLCHAIN=go1.27.2)

  [ -f "$cc" ] || die "C compiler not found: $cc"
  if [ -n "$goarm" ]; then
    env_args+=("GOARM=$goarm")
  fi

  mkdir -p "$(dirname "$out")"
  echo "== building $abi =="
  env "${env_args[@]}" go -C "$APP_DIR/tools/xraycli" build \
    -buildmode=pie -trimpath \
    -ldflags="-checklinkname=0 -s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384" \
    -o "$out" .
  check_elf "$out"
}

build_abi arm64-v8a arm64 "" aarch64-linux-android21-clang.cmd \
  "$JNI_DIR/arm64-v8a/libxraycore.so"
build_abi armeabi-v7a arm 7 armv7a-linux-androideabi21-clang.cmd \
  "$JNI_DIR/armeabi-v7a/libxraycore.so"

echo "built:"
for so in "$JNI_DIR/arm64-v8a/libxraycore.so" "$JNI_DIR/armeabi-v7a/libxraycore.so"; do
  echo "  $so ($(stat -c %s "$so") bytes)"
done

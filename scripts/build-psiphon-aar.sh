#!/usr/bin/env bash
# Build ca.psiphon.aar from the shirokhorshid/psiphon-tunnel-core fork.
# Windows/Git Bash port of the build-aar job in .github/workflows/build.yml.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$(cd "$HERE/.." && pwd)"
ROOT_DIR="$(cd "$APP_DIR/.." && pwd)"

CORE_DIR="${CORE_DIR:-$ROOT_DIR/core}"
GOPATH_DIR="${GOPATH_DIR:-C:/gopath}"
SDK_DIR="${ANDROID_HOME:-C:/Users/edmon/AppData/Local/Android/Sdk}"
NDK_DIR="${ANDROID_NDK_HOME:-$SDK_DIR/ndk/23.1.7779620}"
GO_TOOLCHAIN="${GO_TOOLCHAIN:-go1.26.3}"
# GOTOOLCHAIN switching is ignored in GOPATH mode (GO111MODULE=off), so the
# pinned toolchain must lead PATH. A newer Go here crashes tunnel-core at
# runtime with "tls: ConnectionState ... struct field count mismatch".
GO_TOOLCHAIN_DIR="${GO_TOOLCHAIN_DIR:-$HOME/go/pkg/mod/golang.org/toolchain@v0.0.1-$GO_TOOLCHAIN.windows-amd64}"
BUILD_DIR="${BUILD_DIR:-$ROOT_DIR/.build-aar}"
OUT_AAR="${OUT_AAR:-$APP_DIR/app/libs/ca.psiphon.aar}"
ANDROID_JAR="$SDK_DIR/platforms/android-30/android.jar"
GOPATH_PKG="$GOPATH_DIR/src/github.com/Psiphon-Labs/psiphon-tunnel-core"

die() { echo "ERROR: $*" >&2; exit 1; }

[ -d "$CORE_DIR" ] || die "core repo not found: $CORE_DIR"
[ -d "$NDK_DIR" ] || die "NDK not found: $NDK_DIR"
[ -f "$ANDROID_JAR" ] || die "android-30 platform not found: $ANDROID_JAR"
[ -d "$GOPATH_PKG/MobileLibrary" ] || die "gopath junction missing: $GOPATH_PKG"
[ -x "$GOPATH_DIR/bin/gomobile.exe" ] || die "gomobile.exe not installed in $GOPATH_DIR/bin"
[ -x "$GO_TOOLCHAIN_DIR/bin/go.exe" ] || die "Go toolchain not found: $GO_TOOLCHAIN_DIR"

mkdir -p "$BUILD_DIR"
LOG_DIR="$BUILD_DIR/logs"
mkdir -p "$LOG_DIR"

export GO111MODULE=off
export GOPATH="$GOPATH_DIR"
export GOTOOLCHAIN=local
export ANDROID_HOME="$SDK_DIR"
export ANDROID_NDK_HOME="$NDK_DIR"
export GOCACHE="$BUILD_DIR/go-cache"
export CGO_LDFLAGS="-Wl,-z,max-page-size=16384,-z,common-page-size=16384"
export PATH="$GO_TOOLCHAIN_DIR/bin:$GOPATH_DIR/bin:$PATH"

BUILDDATE="$(date --iso-8601=seconds)"
BUILDREPO="$(cd "$CORE_DIR" && git remote get-url origin)"
BUILDREV="$(cd "$CORE_DIR" && git rev-parse --short HEAD)"
GOVER="$(go version | sed -E 's/.*go([0-9][^ ]*).*/\1/')"

echo "core:      $CORE_DIR ($BUILDREV)"
echo "ndk:       $NDK_DIR"
echo "go:        $(go version)"
echo "javac:     $(javac -version 2>&1)"
echo "output:    $OUT_AAR"

LDFLAGS="-checklinkname=0 -s -w"
LDFLAGS="$LDFLAGS -X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.buildDate=$BUILDDATE"
LDFLAGS="$LDFLAGS -X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.buildRepo=$BUILDREPO"
LDFLAGS="$LDFLAGS -X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.buildRev=$BUILDREV"
LDFLAGS="$LDFLAGS -X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.goVersion=$GOVER"
LDFLAGS="$LDFLAGS -X github.com/Psiphon-Labs/psiphon-tunnel-core/psiphon/common/buildinfo.gomobileVersion=vendored"
LDFLAGS="$LDFLAGS -extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384"

BUILD_TAGS="PSIPHON_ENABLE_INPROXY"

cd "$GOPATH_PKG/MobileLibrary/Android"
rm -f psi.aar ca.psiphon.aar

echo "== gomobile bind (arm, arm64) =="
gomobile bind -v \
  -target=android/arm,android/arm64 \
  -tags="$BUILD_TAGS" \
  -ldflags="$LDFLAGS" \
  -o psi.aar \
  github.com/Psiphon-Labs/psiphon-tunnel-core/MobileLibrary/psi \
  2>&1 | tee "$LOG_DIR/gomobile-bind.log"

[ -f psi.aar ] || die "gomobile did not produce psi.aar"

echo "== post-process AAR =="
python "$HERE/aar_postprocess.py" \
  --aar psi.aar \
  --workdir "$BUILD_DIR/aar_tmp" \
  --manifest "$GOPATH_PKG/MobileLibrary/Android/PsiphonTunnel/AndroidManifest.xml" \
  --backup-rules "$GOPATH_PKG/MobileLibrary/Android/PsiphonTunnel/ca_psiphon_psiphontunnel_backup_rules.xml" \
  --java-src "$GOPATH_PKG/MobileLibrary/Android/PsiphonTunnel/PsiphonTunnel.java" \
  --android-jar "$ANDROID_JAR" \
  --javac "$(command -v javac)" \
  --out "$BUILD_DIR/ca.psiphon.aar" \
  2>&1 | tee "$LOG_DIR/postprocess.log"

cp "$BUILD_DIR/ca.psiphon.aar" "$OUT_AAR"
rm -f psi.aar
rm -rf "$BUILD_DIR/aar_tmp"

echo "== done =="
ls -l "$OUT_AAR"
sha256sum "$OUT_AAR"

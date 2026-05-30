#!/usr/bin/env bash
# Build libbox.aar (the sing-box mobile core) from the pinned tag and drop it
# into app/libs/. Mirrors sing-box's own `make lib_android`.
#
# Requirements:
#   - Go 1.24.x  (the toolchain matching sing-box v1.13.12 go.mod)
#   - Android NDK (set ANDROID_NDK_HOME) + SDK (ANDROID_HOME)
#   - git
#
# Usage:  bash build-libbox.sh [SING_BOX_TAG]
set -euo pipefail

SB_TAG="${1:-v1.13.12}"
HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="${TMPDIR:-/tmp}/razban-libbox-build"
GOMOBILE_VER="v0.1.12"

echo "==> libbox build for sing-box ${SB_TAG}"

command -v go >/dev/null || { echo "ERROR: Go not found (need 1.24.x)"; exit 1; }
[ -n "${ANDROID_NDK_HOME:-}" ] || { echo "ERROR: set ANDROID_NDK_HOME"; exit 1; }

rm -rf "$WORK"; mkdir -p "$WORK"
git clone --depth 1 --branch "$SB_TAG" https://github.com/SagerNet/sing-box "$WORK/sing-box"
cd "$WORK/sing-box"

echo "==> installing SagerNet gomobile fork ${GOMOBILE_VER}"
go install "github.com/sagernet/gomobile/cmd/gomobile@${GOMOBILE_VER}"
go install "github.com/sagernet/gomobile/cmd/gobind@${GOMOBILE_VER}"
export PATH="$PATH:$(go env GOPATH)/bin"

mkdir -p "$HERE/app/libs"   # not tracked in git (empty dir); gomobile -o needs it
TAGS="with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_low_memory"
# Target ABIs are overridable for fast local iteration: an x86_64 emulator only
# needs android/amd64, which builds ~3x faster than the full 3-ABI release set.
# CI leaves LIBBOX_TARGETS unset → ships all three (arm64-v8a, armeabi-v7a, x86_64).
TARGETS="${LIBBOX_TARGETS:-android/arm64,android/arm,android/amd64}"
echo "==> gomobile bind (tags: $TAGS) targets: $TARGETS"
gomobile bind -v \
  -target "$TARGETS" \
  -androidapi 24 \
  -javapkg=io.nekohasekai \
  -libname=box \
  -trimpath -buildvcs=false \
  -ldflags "-X github.com/sagernet/sing-box/constant.Version=${SB_TAG#v} -s -w -checklinkname=0" \
  -tags "$TAGS" \
  -o "$HERE/app/libs/libbox.aar" \
  ./experimental/libbox

echo "==> done: $HERE/app/libs/libbox.aar"
ls -la "$HERE/app/libs/"

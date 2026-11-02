#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BRIDGE_DIR="${ROOT_DIR}/tsnet-bridge"
OUT_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ANDROID_NDK_HOME="$(ls -d "${ANDROID_SDK}/ndk/"* 2>/dev/null | sort -V | tail -n 1)"
fi

if [[ ! -d "${ANDROID_NDK_HOME}" ]]; then
    echo "Error: Android NDK not found under ${ANDROID_SDK}/ndk" >&2
    exit 1
fi

HOST_TAG="darwin-x86_64"
if [[ "$(uname -s)" == "Linux" ]]; then
    HOST_TAG="linux-x86_64"
fi

TOOLCHAIN_BIN="${ANDROID_NDK_HOME}/toolchains/llvm/prebuilt/${HOST_TAG}/bin"
CC="${TOOLCHAIN_BIN}/aarch64-linux-android23-clang"

if [[ ! -x "${CC}" ]]; then
    CC="${TOOLCHAIN_BIN}/aarch64-linux-android26-clang"
fi

mkdir -p "${OUT_DIR}"

echo "Building libattache_tsnet.so using ${CC}..."
cd "${BRIDGE_DIR}"
go mod tidy

CGO_ENABLED=1 \
GOOS=android \
GOARCH=arm64 \
CC="${CC}" \
CGO_LDFLAGS="-Wl,-z,max-page-size=16384" \
go build \
    -trimpath \
    -buildmode=c-shared \
    -ldflags="-s -w -checklinkname=0" \
    -o "${OUT_DIR}/libattache_tsnet.so" \
    .

rm -f "${OUT_DIR}/libattache_tsnet.h"
ls -lh "${OUT_DIR}/libattache_tsnet.so"

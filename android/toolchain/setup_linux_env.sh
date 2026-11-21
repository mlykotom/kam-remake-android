#!/bin/bash
# Idempotent, non-interactive Linux setup script for cloud VMs and CI.
# Installs host Free Pascal 3.2.2, Android NDK 29.0.13599879, CMake 3.22.1,
# persists SDK/NDK env vars in ~/.bashrc, and builds the aarch64-android toolchain.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/../.." && pwd)"
SDK="${ANDROID_HOME:-/opt/android-sdk}"
NDK_VER="29.0.13599879"
CMAKE_VER="3.22.1"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/$NDK_VER}"

# 1. Host tools (Free Pascal 3.2.2, patch, bzip2)
if ! command -v fpc >/dev/null 2>&1 || ! command -v patch >/dev/null 2>&1 || ! command -v bzip2 >/dev/null 2>&1; then
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq fpc patch bzip2
fi

# 2. Android SDK packages (NDK + CMake)
SDKMANAGER="$(command -v sdkmanager 2>/dev/null || echo "$SDK/cmdline-tools/latest/bin/sdkmanager")"
if [ ! -d "$NDK" ] || [ ! -d "$SDK/cmake/$CMAKE_VER" ]; then
  if [ ! -x "$SDKMANAGER" ]; then
    echo "sdkmanager not found at $SDKMANAGER" >&2
    exit 1
  fi
  (yes || true) | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null 2>&1 || true
  PKGS=()
  [ -d "$NDK" ] || PKGS+=("ndk;$NDK_VER")
  [ -d "$SDK/cmake/$CMAKE_VER" ] || PKGS+=("cmake;$CMAKE_VER")
  if [ "${#PKGS[@]}" -gt 0 ]; then
    "$SDKMANAGER" --sdk_root="$SDK" "${PKGS[@]}"
  fi
fi

# 3. Persist environment exports for non-interactive shells
if [ -f "$HOME/.bashrc" ]; then
  sed -i '/case \$- in/,/esac/d' "$HOME/.bashrc" 2>/dev/null || true
fi
touch "$HOME/.bashrc"
grep -qxF "export ANDROID_HOME=$SDK" "$HOME/.bashrc" || echo "export ANDROID_HOME=$SDK" >> "$HOME/.bashrc"
grep -qxF "export ANDROID_NDK_HOME=$NDK" "$HOME/.bashrc" || echo "export ANDROID_NDK_HOME=$NDK" >> "$HOME/.bashrc"
export ANDROID_HOME="$SDK"
export ANDROID_NDK_HOME="$NDK"

# 4. Build FPC aarch64-android cross-compiler, LazUtils, gl4es, audio libs, and innoextract JNI
"$REPO/android/toolchain/setup_toolchain.sh"

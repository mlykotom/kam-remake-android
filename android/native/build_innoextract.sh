#!/bin/bash
# Builds libinnoextract_jni.so (embedded Inno Setup .exe extractor) for Android arm64
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$DIR/../.." && pwd)"
TC="${KAM_TOOLCHAIN:-$REPO/toolchain}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/29.0.13599879}"
CM_BIN="$(ls -d "$SDK"/cmake/*/bin | sort -V | tail -1)"
case "$(uname -s)" in
  Darwin) HOST_TAG=darwin-x86_64 ;;
  *)      HOST_TAG=linux-x86_64 ;;
esac
LLVM="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CC="$LLVM/clang"
CXX="$LLVM/clang++"
AR="$LLVM/llvm-ar"
LIBS="$TC/android-libs"
JOBS="$(sysctl -n hw.ncpu 2>/dev/null || nproc)"

XZ_TAG=v5.6.3
BZIP2_TAG=bzip2-1.0.8
BOOST_VER=1.86.0
BOOST_DIR_VER=1_86_0
INNOEXTRACT_COMMIT=6e9e34ed0876014fdb46e684103ef8c3605e382e

mkdir -p "$TC" "$LIBS/lib" "$LIBS/include"

# 1. liblzma (XZ Utils) static library
if [ ! -f "$LIBS/lib/liblzma.a" ]; then
  echo "== xz ($XZ_TAG)"
  [ -d "$TC/xz" ] || git clone -q --depth 1 --branch "$XZ_TAG" https://github.com/tukaani-project/xz.git "$TC/xz"
  rm -rf "$TC/build-xz"
  "$CM_BIN/cmake" -Wno-dev -S "$TC/xz" -B "$TC/build-xz" -G Ninja -DCMAKE_MAKE_PROGRAM="$CM_BIN/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=26 \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX="$LIBS" -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DBUILD_SHARED_LIBS=OFF -DXZ_UTILS=OFF -DXZ_DOC=OFF -DXZ_NLS=OFF -DBUILD_TESTING=OFF > /dev/null
  "$CM_BIN/ninja" -C "$TC/build-xz" install > /dev/null
  rm -rf "$TC/build-xz"
fi

# 2. libbz2 static library
if [ ! -f "$LIBS/lib/libbz2.a" ]; then
  echo "== bzip2 ($BZIP2_TAG)"
  [ -d "$TC/bzip2" ] || git clone -q --depth 1 --branch "$BZIP2_TAG" https://gitlab.com/bzip2/bzip2.git "$TC/bzip2"
  rm -rf "$TC/build-bzip2"
  mkdir -p "$TC/build-bzip2"
  for c in blocksort huffman crctable randtable compress decompress bzlib; do
    "$CC" --target=aarch64-linux-android26 -O2 -fPIC -c "$TC/bzip2/$c.c" -o "$TC/build-bzip2/$c.o"
  done
  "$AR" rcs "$LIBS/lib/libbz2.a" "$TC/build-bzip2"/*.o
  cp "$TC/bzip2/bzlib.h" "$LIBS/include/"
  rm -rf "$TC/build-bzip2"
fi

# 3. Boost subset (headers + static filesystem, iostreams, date_time)
if [ ! -f "$LIBS/lib/libboost_inno.a" ] || [ ! -d "$TC/boost/boost" ]; then
  echo "== Boost $BOOST_VER (innoextract subset)"
  if [ ! -d "$TC/boost/boost" ]; then
    rm -rf "$TC/boost" "$TC/boost_${BOOST_DIR_VER}"
    curl -fsSL "https://archives.boost.io/release/${BOOST_VER}/source/boost_${BOOST_DIR_VER}.tar.bz2" | tar -xj -C "$TC"
    mv "$TC/boost_${BOOST_DIR_VER}" "$TC/boost"
  fi
  rm -rf "$TC/build-boost-inno"
  mkdir -p "$TC/build-boost-inno"
  BOOST_SRCS=(
    "$TC/boost/libs/iostreams/src/file_descriptor.cpp"
    "$TC/boost/libs/iostreams/src/mapped_file.cpp"
    "$TC/boost/libs/iostreams/src/zlib.cpp"
    "$TC/boost/libs/iostreams/src/bzip2.cpp"
    "$TC/boost/libs/date_time/src/gregorian/greg_month.cpp"
  )
  for f in "$TC/boost/libs/filesystem/src"/*.cpp; do
    BOOST_SRCS+=("$f")
  done
  active=0
  for src in "${BOOST_SRCS[@]}"; do
    obj="$TC/build-boost-inno/$(basename "${src%.cpp}").o"
    "$CXX" --target=aarch64-linux-android26 -std=c++17 -O2 -fPIC -fvisibility=hidden \
      -DBOOST_ALL_NO_LIB=1 -DBOOST_SYSTEM_NO_DEPRECATED=1 -DBOOST_FILESYSTEM_NO_CXX20_ATOMIC_REF=1 \
      -I"$TC/boost" -I"$LIBS/include" -c "$src" -o "$obj" &
    active=$((active + 1))
    if [ "$active" -ge "$JOBS" ]; then
      wait
      active=0
    fi
  done
  wait
  "$AR" rcs "$LIBS/lib/libboost_inno.a" "$TC/build-boost-inno"/*.o
  rm -rf "$TC/build-boost-inno"
fi

# 4. innoextract sources
if [ ! -d "$TC/innoextract/src" ]; then
  echo "== innoextract ($INNOEXTRACT_COMMIT)"
  rm -rf "$TC/innoextract"
  git clone -q https://github.com/dscharrer/innoextract.git "$TC/innoextract"
  git -C "$TC/innoextract" checkout -q "$INNOEXTRACT_COMMIT"
fi

# 5. Compile innoextract + JNI bridge -> libinnoextract_jni.so
if [ "${KAM_FORCE_INNOEXTRACT:-0}" != 1 ] && [ -f "$LIBS/lib/libinnoextract_jni.so" ] && [ "$LIBS/lib/libinnoextract_jni.so" -nt "$DIR/innoextract_jni.cpp" ]; then
  echo "up to date: $LIBS/lib/libinnoextract_jni.so"
  exit 0
fi
BUILD_INNO="$TC/build-innoextract"
rm -rf "$BUILD_INNO"
mkdir -p "$BUILD_INNO/gen"

cat > "$BUILD_INNO/gen/configure.hpp" <<'EOF'
#ifndef INNOEXTRACT_CONFIGURE_HPP
#define INNOEXTRACT_CONFIGURE_HPP
#define INNOEXTRACT_HAVE_ISATTY 1
#define INNOEXTRACT_HAVE_IOCTL 1
#define INNOEXTRACT_HAVE_TIMEGM 1
#define INNOEXTRACT_HAVE_GMTIME_R 1
#define INNOEXTRACT_HAVE_UTIMENSAT 1
#define INNOEXTRACT_HAVE_DYNAMIC_UTIMENSAT 0
#define INNOEXTRACT_HAVE_AT_FDCWD 1
#define INNOEXTRACT_HAVE_UTIMES 1
#define INNOEXTRACT_HAVE_DLSYM 1
#define INNOEXTRACT_HAVE_BUILTIN_BSWAP16 1
#define INNOEXTRACT_HAVE_BUILTIN_BSWAP32 1
#define INNOEXTRACT_HAVE_BUILTIN_BSWAP64 1
#define INNOEXTRACT_HAVE_BSWAP_16 0
#define INNOEXTRACT_HAVE_BSWAP_32 0
#define INNOEXTRACT_HAVE_BSWAP_64 0
#define INNOEXTRACT_HAVE_ALIGNOF 1
#define INNOEXTRACT_HAVE_STD_CODECVT_UTF8_UTF16 0
#define INNOEXTRACT_HAVE_STD_UNIQUE_PTR 1
#define INNOEXTRACT_HAVE_DECRYPTION 1
#define INNOEXTRACT_HAVE_LZMA 1
#define INNOEXTRACT_HAVE_ICONV 0
#define INNOEXTRACT_HAVE_WIN32_CONV 0
#define INNOEXTRACT_HAVE_BUILTIN_CONV 1
#define INNOEXTRACT_HAVE_POSIX_SPAWNP 0
#define INNOEXTRACT_HAVE_UNISTD_ENVIRON 1
#define INNOEXTRACT_HAVE_FORK 1
#define INNOEXTRACT_HAVE_EXECVP 1
#define INNOEXTRACT_HAVE_WAITPID 1
#endif
EOF

cat > "$BUILD_INNO/gen/release.cpp" <<'EOF'
#include "release.hpp"
const char innoextract_name[] = "innoextract";
const char innoextract_version[] = "1.9-android";
const char innosetup_versions[] = "1.2.10 to 6.3.0";
const char innoextract_bugs[] = "https://innoextract.constexpr.org/";
const char innoextract_copyright[] = "Copyright (C) 2011-2024 Daniel Scharrer";
const char innoextract_license[] = "zlib/libpng";
EOF

INNO_SRCS=(
  "$BUILD_INNO/gen/release.cpp"
  "$DIR/innoextract_jni.cpp"
)
while IFS= read -r -d '' f; do
  case "$f" in
    */cli/main.cpp|*/cli/debug.cpp|*/util/windows.cpp|*/util/test.cpp) continue ;;
  esac
  INNO_SRCS+=("$f")
done < <(find "$TC/innoextract/src" -name '*.cpp' -print0)

idx=0
active=0
for src in "${INNO_SRCS[@]}"; do
  idx=$((idx + 1))
  obj="$BUILD_INNO/obj_${idx}.o"
  "$CXX" --target=aarch64-linux-android26 -std=c++17 -O2 -fPIC -fvisibility=hidden -fvisibility-inlines-hidden \
    -DBOOST_ALL_NO_LIB=1 -DBOOST_SYSTEM_NO_DEPRECATED=1 -DBOOST_SYSTEM_DISABLE_THREADS=1 -DLZMA_API_STATIC=1 \
    -I"$BUILD_INNO/gen" -I"$TC/innoextract/src" -I"$TC/boost" -I"$LIBS/include" \
    -c "$src" -o "$obj" &
  active=$((active + 1))
  if [ "$active" -ge "$JOBS" ]; then
    wait
    active=0
  fi
done
wait

"$CXX" --target=aarch64-linux-android26 -shared -static-libstdc++ \
  -Wl,--gc-sections -Wl,-soname,libinnoextract_jni.so -Wl,-z,max-page-size=16384 \
  -o "$LIBS/lib/libinnoextract_jni.so" \
  "$BUILD_INNO"/obj_*.o \
  "$LIBS/lib/libboost_inno.a" "$LIBS/lib/liblzma.a" "$LIBS/lib/libbz2.a" \
  -lz -llog -ldl -lm

"$LLVM/llvm-strip" --strip-unneeded "$LIBS/lib/libinnoextract_jni.so"
rm -rf "$BUILD_INNO"
echo "built $LIBS/lib/libinnoextract_jni.so ($(du -h "$LIBS/lib/libinnoextract_jni.so" | cut -f1))"

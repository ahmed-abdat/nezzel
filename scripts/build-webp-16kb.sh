#!/bin/sh
# Build a candidate; vendoring/build integration waits for the actual verified output.
# Prerequisites: sdkmanager-installed NDK r27d, make, Python 3, curl, shasum, gpgv.
# Usage: WEBP_GPG_KEYRING=/path/to/trusted-webp-release-keys.gpg \
#        scripts/build-webp-16kb.sh /path/to/ffmpeg-0.18.1.aar
# WEBP_SOURCE_TARBALL may point at an existing official tarball (+ adjacent .asc).
set -eu
if [ "$#" -ne 1 ]; then
    echo "Usage: WEBP_GPG_KEYRING=<trusted keyring> $0 <ffmpeg-0.18.1.aar>" >&2
    exit 1
fi
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
aar=$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).resolve())' "$1")
aar_sha=0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49
source_sha=e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564
source_url=https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz
ndk_version=27.3.13750724
sdk=${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}
ndk=$sdk/ndk/$ndk_version
build=$root/build/webp-16kb

test "$(shasum -a 256 "$aar" | cut -d ' ' -f 1)" = "$aar_sha" || {
    echo "ERROR: pinned FFmpeg AAR SHA-256 mismatch" >&2; exit 1;
}
if [ ! -f "$ndk/source.properties" ]; then
    echo "BLOCKED: NDK $ndk_version missing. Owner-approved setup:" >&2
    echo "sdkmanager --sdk_root=$sdk 'ndk;$ndk_version'" >&2
    exit 1
fi
test "$(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties" | tr -d '\r')" = "$ndk_version"
case $(uname -s) in
    Darwin) host=darwin-x86_64 ;;
    Linux) host=linux-x86_64 ;;
    *) echo "ERROR: unsupported NDK host" >&2; exit 1 ;;
esac
toolchain=$ndk/toolchains/llvm/prebuilt/$host/bin
export READELF=$toolchain/llvm-readelf
test -x "$READELF"
: "${WEBP_GPG_KEYRING:?Set WEBP_GPG_KEYRING to a trusted WebP release signing keyring}"
test -f "$WEBP_GPG_KEYRING"
command -v gpgv >/dev/null
command -v make >/dev/null
mkdir -p "$build"
tarball=${WEBP_SOURCE_TARBALL:-$build/libwebp-1.6.0.tar.gz}
if [ ! -f "$tarball" ]; then
    curl --fail --location --proto '=https' --tlsv1.2 --connect-timeout 15 \
        --max-time 180 --output "$tarball.tmp" "$source_url"
    mv "$tarball.tmp" "$tarball"
fi
test "$(shasum -a 256 "$tarball" | cut -d ' ' -f 1)" = "$source_sha" || {
    echo "ERROR: official WebP source SHA-256 mismatch" >&2; exit 1;
}
if [ ! -f "$tarball.asc" ]; then
    curl --fail --location --proto '=https' --tlsv1.2 --connect-timeout 15 \
        --max-time 30 --output "$tarball.asc.tmp" "$source_url.asc"
    mv "$tarball.asc.tmp" "$tarball.asc"
fi
gpgv --keyring "$WEBP_GPG_KEYRING" "$tarball.asc" "$tarball"

# Recreate only this script's generated source/build tree, never an SDK or cache.
rm -rf "$build/source" "$build/compile" "$build/libraries"
mkdir -p "$build/source" "$build/compile" "$build/libraries"
tar -xzf "$tarball" -C "$build/source" --strip-components=1
# Upstream libtool defaults use versioned SONAMEs; Termux's payload uses .so.
# Patch generated Makefiles only, so no autotools installation is required.
python3 - "$build/source" <<'PY'
from pathlib import Path
import re, sys
root = Path(sys.argv[1])
count = 0
for name in ('src/Makefile.in', 'src/demux/Makefile.in',
             'src/mux/Makefile.in', 'sharpyuv/Makefile.in'):
    path = root / name
    text, changed = re.subn(r'-version-info [0-9]+:[0-9]+:[0-9]+',
                           '-avoid-version', path.read_text())
    path.write_text(text)
    count += changed
if count != 5:
    raise SystemExit('ERROR: expected exactly five SONAME build rules')
PY
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=1752101700
export CC=$toolchain/aarch64-linux-android29-clang
export AR=$toolchain/llvm-ar RANLIB=$toolchain/llvm-ranlib
export NM=$toolchain/llvm-nm STRIP=$toolchain/llvm-strip
export CFLAGS="-O2 -g0 -ffile-prefix-map=$build=. -fdebug-prefix-map=$build=."
export LDFLAGS='-Wl,--as-needed -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--build-id=sha1'
export PKG_CONFIG_LIBDIR=/nonexistent
unset CXXFLAGS CPPFLAGS LIBS MAKEFLAGS MFLAGS
cd "$build/compile"
"$build/source/configure" --host=aarch64-linux-android \
    --prefix=/data/data/com.termux/files/usr --enable-option-checking=fatal \
    --enable-shared --disable-static --enable-libwebpdecoder \
    --enable-libwebpmux --enable-libwebpdemux --disable-libwebpextras \
    --disable-png --disable-jpeg --disable-tiff --disable-gif \
    --disable-gl --disable-sdl --disable-wic
# Build the libraries only, never the host/image utilities.
make -j2 -C sharpyuv
make -j2 -C src
for lib in libsharpyuv libwebp libwebpdecoder libwebpdemux libwebpmux; do
    case $lib in
        libsharpyuv) path=sharpyuv/.libs ;;
        libwebpdemux) path=src/demux/.libs ;;
        libwebpmux) path=src/mux/.libs ;;
        *) path=src/.libs ;;
    esac
    test -f "$path/$lib.so"
    "$STRIP" --strip-unneeded -o "$build/libraries/$lib.so" "$path/$lib.so"
done
python3 "$root/scripts/native_16kb.py" repack "$aar" "$build/libraries" \
    "$build/candidate/arm64-v8a/libffmpeg.zip.so" > "$build/artifact-sha256.txt.tmp"
mv "$build/artifact-sha256.txt.tmp" "$build/artifact-sha256.txt"
mkdir -p "$build/notices"
cp "$build/source/COPYING" "$build/source/PATENTS" "$build/source/AUTHORS" "$build/notices/"
echo "$source_sha  libwebp-1.6.0.tar.gz"
echo "NDK $ndk_version; Android API 29; arm64-v8a; max/common page size 16384"
python3 -c 'import platform,zlib; print("Python",platform.python_version(),"zlib",zlib.ZLIB_RUNTIME_VERSION)'
cat "$build/artifact-sha256.txt"
echo "Candidate: $build/candidate/arm64-v8a/libffmpeg.zip.so"
echo "Next: vendor verified candidate + notices, wire deterministic packaging, run APK gate."

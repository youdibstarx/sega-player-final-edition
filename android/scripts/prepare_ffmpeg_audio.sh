#!/usr/bin/env bash
# Compile the upstream LGPL Media3 FFmpeg audio extension for Android 7+.
# The Maven Central upstream AAR requires Android 8 (API 26). We must NOT
# silently raise SEGA PLAYER's minSdk=24 or override the manifest.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORK="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/sega-ffmpeg-audio.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

UPSTREAM="https://github.com/putdotio/media3-ffmpeg-decoder.git"
PINNED_COMMIT="1d9e66a310e8cac6d4cfa6f637ffa75fba7a3063"
RELEASE_TAG="v1.11.1+1"
OUTPUT="$REPO_ROOT/android/app/libs/media3-ffmpeg-decoder-api24.aar"

echo "Cloning open-source Media3 FFmpeg renderer at pinned release $RELEASE_TAG"
git clone --depth 1 --branch "$RELEASE_TAG" --recurse-submodules --shallow-submodules "$UPSTREAM" "$WORK/source"
test "$(git -C "$WORK/source" rev-parse HEAD)" = "$PINNED_COMMIT" || {
  echo "Upstream tag no longer matches audited commit" >&2
  exit 1
}

# Requires JDK 21, SDK 36, NDK 28.2.13676358 and CMake >=3.21.
# Build FFmpeg and JNI specifically for the app's minSdkVersion 24.
(
  cd "$WORK/source"
  ./gradlew --no-daemon assembleRelease -PffmpegMinSdk=24
)
AAR="$WORK/source/build/outputs/aar/media3-ffmpeg-decoder-release.aar"
test -s "$AAR"
for ABI in arm64-v8a armeabi-v7a; do
  unzip -Z1 "$AAR" | grep -Fxq "jni/$ABI/libffmpegJNI.so" || {
    echo "Missing FFmpeg library for $ABI" >&2
    exit 1
  }
done
mkdir -p "$(dirname "$OUTPUT")"
cp "$AAR" "$OUTPUT"
echo "FFmpeg AAR rebuilt for API24, source pinned at $PINNED_COMMIT"
sha256sum "$OUTPUT"

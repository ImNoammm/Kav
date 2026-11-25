#!/usr/bin/env bash
# Builds libkavhook.so for Kav's two ABIs into app/src/main/jniLibs, from the sources CMakeLists.txt names, at their
# commits. Needs git, CMake 3.28 or newer, Ninja and the Android NDK r29 or newer (ANDROID_NDK_HOME; r28's compiler
# can't build LSPlant). Kav's own build doesn't run this: it packs the libraries it made, so building Kav needs no NDK.
#   ANDROID_NDK_HOME=<ndk> android/hook/build.sh
set -euo pipefail
NDK=${ANDROID_NDK_HOME:?ANDROID_NDK_HOME=<the Android NDK>}
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$(mktemp -d)}
fetch() {
  [ -d "$WORK/$1" ] || git clone -q "$2" "$WORK/$1"
  # Fetched by its hash when a clone doesn't have it: LSPlant's master was rewritten after 7a768e3, which is on no branch.
  git -C "$WORK/$1" cat-file -e "$3^{commit}" 2>/dev/null || git -C "$WORK/$1" fetch -q origin "$3"
  git -C "$WORK/$1" checkout -q "$3"
  if [ $# -gt 3 ]; then git -C "$WORK/$1" submodule update -q --init --recursive "$4"; fi
}
fetch lsplant https://github.com/JingMatrix/LSPlant.git 7a768e31ffb2852797631b5140b54f9702a78743 lsplant/src/main/jni/external/dex_builder
# Load generated hook classes with Android's public in-memory class loader (API 26+).
PATCH="$HERE/lsplant-in-memory-loader.patch"
if ! git -C "$WORK/lsplant" apply --reverse --check "$PATCH" 2>/dev/null; then
  git -C "$WORK/lsplant" apply "$PATCH"
fi
fetch dobby https://github.com/JingMatrix/Dobby.git 05a09ac6807a6bb1726350e40ea4b127c1c79809
fetch xz-embedded https://github.com/tukaani-project/xz-embedded.git ae63ae3a36ed01724674e8f3d750dc47bf125410
for abi in arm64-v8a x86_64; do
  cmake -G Ninja -S "$HERE" -B "$WORK/build-$abi" -DDEPS="$WORK" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI=$abi -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON >/dev/null
  cmake --build "$WORK/build-$abi" --target kavhook
  mkdir -p "$HERE/../app/src/main/jniLibs/$abi"
  "$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded -o "$HERE/../app/src/main/jniLibs/$abi/libkavhook.so" "$WORK/build-$abi/libkavhook.so"
done

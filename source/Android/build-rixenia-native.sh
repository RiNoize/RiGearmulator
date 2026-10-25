#!/usr/bin/env bash
# Build the standalone Xenia engine and stage its Android libraries for Gradle.
# Prerequisites: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake and Ninja.
set -euo pipefail

rixenia_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
rixenia_repo="$(cd -- "${rixenia_script_dir}/../.." && pwd)"
rixenia_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "${rixenia_sdk}" ]]; then
    echo 'Set ANDROID_SDK_ROOT or ANDROID_HOME to the installed Android SDK.' >&2
    exit 1
fi
rixenia_ndk_version="${RIXENIA_NDK_VERSION:-27.2.12479018}"
rixenia_ndk="${rixenia_sdk}/ndk/${rixenia_ndk_version}"
rixenia_build="${RIXENIA_BUILD_DIR:-${rixenia_repo}/build/android-xenia-release}"
rixenia_jobs="${RIXENIA_BUILD_JOBS:-2}"
rixenia_cmake="${RIXENIA_CMAKE:-${rixenia_sdk}/cmake/3.22.1/bin/cmake}"
rixenia_ninja="${RIXENIA_NINJA:-${rixenia_sdk}/cmake/3.22.1/bin/ninja}"
if [[ ! -x "${rixenia_cmake}" ]]; then rixenia_cmake="$(command -v cmake)"; fi
if [[ ! -x "${rixenia_ninja}" ]]; then rixenia_ninja="$(command -v ninja)"; fi
test -f "${rixenia_ndk}/build/cmake/android.toolchain.cmake"

"${rixenia_cmake}" -S "${rixenia_repo}" -B "${rixenia_build}" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="${rixenia_ninja}" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
    -DCMAKE_TOOLCHAIN_FILE="${rixenia_ndk}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DBUILD_TESTING=OFF -Dgearmulator_BUILD_JUCEPLUGIN=OFF \
    -Dgearmulator_SYNTH_OSIRUS=OFF -Dgearmulator_SYNTH_OSTIRUS=OFF \
    -Dgearmulator_SYNTH_VAVRA=OFF -Dgearmulator_SYNTH_XENIA=ON \
    -Dgearmulator_SYNTH_NODALRED2X=OFF -Dgearmulator_SYNTH_JE8086=OFF \
    -Dgearmulator_SYNTH_88EMU=OFF -Dgearmulator_COMPONENT_DSPBRIDGE=OFF
"${rixenia_cmake}" --build "${rixenia_build}" --target rixenia_jni --parallel "${rixenia_jobs}"

rixenia_jni="${rixenia_repo}/source/Android/RiXeniaApp/app/src/main/jniLibs/arm64-v8a"
mkdir -p "${rixenia_jni}"
cp "${rixenia_build}/source/Android/rixeniaNative/librixenia.so" "${rixenia_jni}/"
case "$(uname -s)" in
    Linux) rixenia_host=linux-x86_64 ;;
    Darwin) rixenia_host=darwin-x86_64 ;;
    *) echo 'This build script supports Linux and macOS hosts.' >&2; exit 1 ;;
esac
cp "${rixenia_ndk}/toolchains/llvm/prebuilt/${rixenia_host}/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "${rixenia_jni}/"
echo "RiXenia ARM64 libraries staged in ${rixenia_jni}"

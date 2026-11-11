#!/usr/bin/env bash
# Build the OsTIrus (Virus TI) engine and stage its Android libraries for Gradle.
# Prerequisites: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake and Ninja.
set -euo pipefail

riostirus_script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
riostirus_repo="$(cd -- "${riostirus_script_dir}/../.." && pwd)"
riostirus_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "${riostirus_sdk}" ]]; then
    echo 'Set ANDROID_SDK_ROOT or ANDROID_HOME to the installed Android SDK.' >&2
    exit 1
fi
riostirus_ndk_version="${RIOSTIRUS_NDK_VERSION:-27.2.12479018}"
riostirus_ndk="${riostirus_sdk}/ndk/${riostirus_ndk_version}"
riostirus_build="${RIOSTIRUS_BUILD_DIR:-${riostirus_repo}/build/android-ostirus-release}"
riostirus_jobs="${RIOSTIRUS_BUILD_JOBS:-2}"
riostirus_cmake="${RIOSTIRUS_CMAKE:-${riostirus_sdk}/cmake/3.22.1/bin/cmake}"
riostirus_ninja="${RIOSTIRUS_NINJA:-${riostirus_sdk}/cmake/3.22.1/bin/ninja}"
if [[ ! -x "${riostirus_cmake}" ]]; then riostirus_cmake="$(command -v cmake)"; fi
if [[ ! -x "${riostirus_ninja}" ]]; then riostirus_ninja="$(command -v ninja)"; fi
test -f "${riostirus_ndk}/build/cmake/android.toolchain.cmake"

"${riostirus_cmake}" -S "${riostirus_repo}" -B "${riostirus_build}" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="${riostirus_ninja}" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_EXPORT_COMPILE_COMMANDS=ON \
    -DCMAKE_TOOLCHAIN_FILE="${riostirus_ndk}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared \
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DBUILD_TESTING=OFF -Dgearmulator_BUILD_JUCEPLUGIN=OFF \
    -Dgearmulator_SYNTH_OSIRUS=OFF -Dgearmulator_SYNTH_OSTIRUS=ON \
    -Dgearmulator_SYNTH_VAVRA=OFF -Dgearmulator_SYNTH_XENIA=OFF \
    -Dgearmulator_SYNTH_NODALRED2X=OFF -Dgearmulator_SYNTH_JE8086=OFF \
    -Dgearmulator_SYNTH_88EMU=OFF -Dgearmulator_COMPONENT_DSPBRIDGE=OFF
"${riostirus_cmake}" --build "${riostirus_build}" --target riostirus_jni --parallel "${riostirus_jobs}"

riostirus_jni="${riostirus_repo}/source/Android/RiOsTIrusApp/app/src/main/jniLibs/arm64-v8a"
mkdir -p "${riostirus_jni}"
cp "${riostirus_build}/source/Android/riostirusNative/libriostirus.so" "${riostirus_jni}/"
case "$(uname -s)" in
    Linux) riostirus_host=linux-x86_64 ;;
    Darwin) riostirus_host=darwin-x86_64 ;;
    *) echo 'This build script supports Linux and macOS hosts.' >&2; exit 1 ;;
esac
cp "${riostirus_ndk}/toolchains/llvm/prebuilt/${riostirus_host}/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" "${riostirus_jni}/"
echo "RiOsTIrus ARM64 libraries staged in ${riostirus_jni}"

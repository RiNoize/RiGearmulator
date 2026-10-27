#!/usr/bin/env bash
set -euo pipefail
SDKMANAGER="$(find "${ANDROID_HOME}/cmdline-tools" -type f -name sdkmanager -print -quit)"
AVDMANAGER="$(dirname "$SDKMANAGER")/avdmanager"
"$SDKMANAGER" 'platform-tools' 'emulator' 'system-images;android-35;default;x86_64'
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
# CI can define Android's user home differently from HOME. Both tools must use
# the same explicit AVD directory; never rely on their incompatible defaults.
export ANDROID_USER_HOME="${RUNNER_TEMP:-/tmp}/rixenia-android-user"
export ANDROID_EMULATOR_HOME="$ANDROID_USER_HOME"
export ANDROID_AVD_HOME="$ANDROID_USER_HOME/avd"
mkdir -p "$ANDROID_AVD_HOME" artifacts/ui
if [ -e /dev/kvm ]; then sudo chmod 666 /dev/kvm; fi
printf 'no\n' | "$AVDMANAGER" create avd --force --name rixenia-ui --path "$ANDROID_AVD_HOME/rixenia-ui.avd" --package 'system-images;android-35;default;x86_64' --device pixel_2
emulator -list-avds | tee artifacts/ui/avds.txt
grep -qx rixenia-ui artifacts/ui/avds.txt
adb start-server
emulator -avd rixenia-ui -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -no-snapshot -camera-back none -camera-front none -cores 2 -memory 2048 > artifacts/ui/emulator.log 2>&1 &
EMU_PID=$!
cleanup() {
  adb logcat -d -s AndroidRuntime > artifacts/ui/runtime.txt 2>&1 || true
  adb emu kill >/dev/null 2>&1 || kill "$EMU_PID" 2>/dev/null || true
}
trap cleanup EXIT
for i in $(seq 1 120); do
  kill -0 "$EMU_PID" 2>/dev/null || { cat artifacts/ui/emulator.log; exit 1; }
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; then break; fi
  sleep 2
done
test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell input keyevent 82
adb shell wm size 800x1340
APP=source/Android/RiXeniaApp/app/build/outputs/apk
adb install -r "$APP/debug/app-debug.apk"
adb install -r "$APP/androidTest/debug/app-debug-androidTest.apk"
for entry in tablet:240 compact:320; do
  label=${entry%:*}; density=${entry#*:}
  adb shell wm density "$density"
  adb shell am force-stop com.rinoize.rixenia.v2
  timeout 120 adb shell am instrument -w -e label "$label" com.rinoize.rixenia.v2.test/com.rinoize.rixenia.UiSmokeInstrumentation | tee "artifacts/ui/$label.txt"
  grep -q 'UI_SMOKE_OK' "artifacts/ui/$label.txt"
  for img in "editor-$label.png" "config-$label.png"; do
    adb exec-out run-as com.rinoize.rixenia.v2 cat "files/$img" > "artifacts/ui/$img"
  done
done
adb exec-out run-as com.rinoize.rixenia.v2 cat files/editor-compact-bottom.png > artifacts/ui/editor-compact-bottom.png

#!/usr/bin/env bash
# Agent-VScreen build: reuses the Minis VScreen toolchain and Gradle cache; outputs go to /srv/avs-build.
set -euo pipefail
set -f
BASE=/data/workspace/minis-vscreen
export GRADLE_USER_HOME="$BASE/cache/gradle" TMPDIR=/srv/avs-build/tmp
export ANDROID_HOME="$BASE/toolchains/android-sdk" ANDROID_SDK_ROOT="$BASE/toolchains/android-sdk"
mkdir -p "$TMPDIR" /srv/avs-build/logs
cd "$(dirname "$0")"
TASK="${1:-:app:assembleRelease}"
# 1.4.0-beta2: `./build.sh regen` rewrites the files generated from the tables (README ability blocks, REFERENCE.md,
# Operit advice), then runs the tests. Tests never write the source tree otherwise.
if [ "$TASK" = "regen" ]; then export AVS_REGEN=1; TASK=":app:testDebugUnitTest"; fi
# A release is built only when the disclosure, codes and picture checks pass (no CI: this is the gate).
if [ "$TASK" = ":app:assembleRelease" ]; then TASK=":app:testDebugUnitTest --tests *AbilitiesTest --tests *CodesUsedTest --tests *PicturesTest --tests *V120Test :app:assembleRelease"; fi
# Rebuild the built-in Tailscale node when it is missing or older than its sources (it is not in git).
SO=app/src/main/jniLibs/arm64-v8a/libtsbridge.so
if [ ! -f "$SO" ] || [ -n "$(find tsbridge -newer "$SO" -type f \( -name '*.go' -o -name 'go.mod' -o -name 'go.sum' \) | head -1)" ]; then
  ./tsbridge/build.sh >> /srv/avs-build/logs/tsb.log 2>&1
fi
# 1.4.0-beta2: the OCR models must build and read on the app's own onnxruntime version (beta1 did not on the phone).
ORT=$(grep -o 'onnxruntime-android:[0-9.]*' app/build.gradle.kts | cut -d: -f2)
/srv/avs-build/ocrbench/venv120/bin/python tools/ocr-bench/check_assets.py app/src/main/assets/vscreen-ocr "$ORT" > /srv/avs-build/logs/ocrcheck.log 2>&1 || { cat /srv/avs-build/logs/ocrcheck.log; echo AVS_BUILD_FAILED_OCR; exit 1; }
./gradlew --no-daemon --max-workers=2 -Dorg.gradle.jvmargs='-Xmx3g -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process $TASK > /srv/avs-build/logs/gradle.log 2>&1
echo AVS_BUILD_SUCCESS

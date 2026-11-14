#!/bin/sh
# Builds the built-in Tailscale node for the APK (1.3.0): a shared library loaded into the app's own process through
# JNI, so it runs exactly when the app runs (a child process could be frozen by the system on its own).
set -e
cd "$(dirname "$0")"
[ -f /srv/avs-build/goenv.sh ] && . /srv/avs-build/goenv.sh
OUT=../app/src/main/jniLibs/arm64-v8a/libtsnode.so
rm -f ../app/src/main/jniLibs/arm64-v8a/libtsbridge.so
NDK="${ANDROID_NDK_HOME:-/data/workspace/minis-vscreen/toolchains/android-sdk/ndk/28.0.13004108}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android30-clang"
mkdir -p "$(dirname "$OUT")"
TAGS="ts_omit_aws,ts_omit_bird,ts_omit_tap,ts_omit_kube,ts_omit_completion,ts_omit_ssh,ts_omit_wakeonlan,ts_omit_capture,ts_omit_relayserver,ts_omit_taildrop,ts_omit_tpm,ts_omit_syspolicy,ts_omit_debugeventbus,ts_omit_webclient,ts_omit_drive,ts_omit_systray,ts_omit_portmapper,ts_omit_appconnectors,ts_omit_clientupdate,ts_omit_advertiseroutes,ts_omit_useroutes,ts_omit_outboundproxy,ts_omit_netstack_stats"
CGO_ENABLED=1 CC="$CC" GOOS=android GOARCH=arm64 go build -buildmode=c-shared -trimpath -tags "$TAGS" -ldflags "-s -w -buildid=" -o "$OUT" .
rm -f "${OUT%.so}.h"
ls -l "$OUT"

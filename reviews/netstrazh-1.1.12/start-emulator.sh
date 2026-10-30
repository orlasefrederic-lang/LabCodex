#!/usr/bin/env bash
set -euo pipefail
source /workspace/netstrazh-tools/sdk19-env.sh
mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
avd_name=netstrazh-api29
if [[ ! -f "$ANDROID_AVD_HOME/$avd_name.avd/config.ini" ]]; then
  printf 'no\n' | avdmanager create avd \
    --name "$avd_name" \
    --package 'system-images;android-29;default;x86' \
    --device 'Nexus S'
fi
exec emulator -avd "$avd_name" -port 5554 \
  -no-window -no-audio -no-boot-anim -no-snapshot \
  -accel off -gpu swangle -feature -Vulkan \
  -memory 2048 -cores 2 -camera-back none -camera-front none

#!/bin/zsh
# Pleos Connect 에뮬레이터 기동 → 부팅 대기 → Virtual Console 포트 포워딩.
# 사용: tools/emu_up.sh   (이미 떠 있으면 포워딩만 다시)
set -e
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
ADB=$SDK/platform-tools/adb

if ! $ADB devices | grep -q "emulator-.*device$"; then
  nohup $SDK/emulator/emulator -avd PleosConnect -no-snapshot -no-boot-anim >/tmp/pleos_emulator.log 2>&1 &
  echo "에뮬레이터 기동 중… (로그 /tmp/pleos_emulator.log)"
  $ADB wait-for-device
  until [ "$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 1; done
fi
$ADB forward tcp:8088 tcp:8088 >/dev/null
echo "부팅 완료 · Virtual Console = http://127.0.0.1:8088"

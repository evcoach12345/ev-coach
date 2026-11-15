#!/bin/zsh
# 에뮬레이터 화면 녹화 → mp4 저장. 시연영상 원본용.
# 사용: tools/record.sh 이름 [초=60]   → out/recordings/이름.mp4
# (adb screenrecord 1회 최대 180초 · 더 길면 여러 번 찍어 ffmpeg로 이어 붙인다)
# 주행 스크립트와 동시에 찍을 땐 `nohup tools/record.sh 이름 초 &` — 그냥 & 로 띄우면 셸 종료 때 녹화가 잘려 mp4가 깨진다.
set -e
NAME=${1:?이름 필요}; SEC=${2:-60}
OUT=$(dirname $0)/../out/recordings; mkdir -p $OUT
adb shell screenrecord --time-limit $SEC --bit-rate 8000000 /sdcard/$NAME.mp4
adb pull /sdcard/$NAME.mp4 $OUT/$NAME.mp4 >/dev/null && adb shell rm /sdcard/$NAME.mp4
echo "저장: $OUT/$NAME.mp4"

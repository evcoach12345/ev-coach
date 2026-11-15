#!/usr/bin/env python3
"""Virtual Console 시나리오 주행기 — 에뮬레이터 차를 스크립트로 몬다(표준 라이브러리만).

사용:
  tools/vc_drive.py list                      # 내장 시나리오 목록
  tools/vc_drive.py run hard_accel            # 내장 시나리오 실행
  tools/vc_drive.py run cycle:path/to.csv     # 속도표 CSV(초,km/h[,고도m]) 재생
  tools/vc_drive.py run hill --hz 5           # 업데이트 주기 바꾸기(기본 5Hz)

동작:
  - 콘솔은 브라우저 heartbeat(POST /browser/session)가 1초마다 와야 차가 «켜짐» → 백그라운드 스레드로 보낸다.
  - 속도·페달은 PUT /vehicle/controls(속도는 정수 km/h만 받음), 기어는 PUT /vehicle/gear.
  - 고도(경사)는 `adb emu geo fix 경도 위도 고도`로 GPS에 주입(진행 방향 동쪽 직선).
"""
import argparse
import csv
import math
import subprocess
import threading
import time
import urllib.request

BASE = "http://127.0.0.1:8088"
ADB = "adb"
LAT0, LON0 = 37.4979, 127.0270  # 강남역 부근 · 동쪽으로 직진
M_PER_DEG_LON = 111_320 * math.cos(math.radians(LAT0))


def http(method, path):
    req = urllib.request.Request(BASE + path, method=method)
    with urllib.request.urlopen(req, timeout=3) as r:
        return r.read()


def heartbeat(stop):
    while not stop.is_set():
        try:
            http("POST", "/browser/session?client=vc_drive")
        except OSError:
            pass
        stop.wait(1.0)


# ── 내장 시나리오: (지속초, 목표 km/h, 고도 변화 m) 구간 목록. 구간 안에서 선형 보간 ──
SCENARIOS = {
    "hard_accel": [(3, 0, 0), (5, 80, 0), (10, 80, 0), (6, 0, 0)],          # 급가속 → 정속 → 급정지
    "gentle_accel": [(3, 0, 0), (15, 80, 0), (10, 80, 0), (20, 0, 0)],      # 부드러운 가감속(대조군)
    "highway_110": [(3, 0, 0), (20, 110, 0), (40, 110, 0), (20, 0, 0)],     # 고속 정속
    "highway_90": [(3, 0, 0), (18, 90, 0), (40, 90, 0), (18, 0, 0)],        # 같은 길 90km/h
    "stop_and_go": [(3, 0, 0)] + [(6, 40, 0), (4, 0, 0)] * 5,               # 정체 반복
    "pace_demo": [(3, 0, 0), (30, 110, 0), (25, 110, 0), (12, 95, 0), (110, 95, 0), (30, 0, 0)],  # 게임 피드백 시연: 부드러운 가속 → 과속 안내 → 권장 유지 절약 → 부드러운 감속
    "grade_demo": [(3, 0, 0), (25, 95, 0), (20, 95, 0), (40, 95, 40), (20, 95, 0), (40, 95, -40), (15, 0, 0)],  # v0.7 경사 시연: 95km/h로 약 1km 오르막 +40m(≈4%) → 평지 → 내리막 −40m
    "hill": [(3, 0, 0), (10, 60, 0), (30, 60, 60), (30, 60, -60), (10, 0, 0)],  # 오르막 2%·내리막
}


def expand(segments, hz):
    """구간 목록 → (t, km/h, 고도) 시계열."""
    out, v, alt, t = [], 0.0, 0.0, 0.0
    for dur, v1, dalt in segments:
        n = max(1, int(dur * hz))
        v0, a0 = v, alt
        for i in range(1, n + 1):
            f = i / n
            out.append((t + i / hz, v0 + (v1 - v0) * f, a0 + dalt * f))
        v, alt, t = v1, a0 + dalt, t + dur
    return out


def load_cycle(path, hz):
    rows = []
    with open(path, newline="") as f:
        for r in csv.reader(f):
            if not r or not r[0].replace(".", "", 1).isdigit():
                continue
            rows.append((float(r[0]), float(r[1]), float(r[2]) if len(r) > 2 else 0.0))
    out, j = [], 0
    t_end = rows[-1][0]
    for k in range(int(t_end * hz) + 1):
        t = k / hz
        while j + 1 < len(rows) and rows[j + 1][0] < t:
            j += 1
        a, b = rows[j], rows[min(j + 1, len(rows) - 1)]
        f = 0 if b[0] == a[0] else (t - a[0]) / (b[0] - a[0])
        out.append((t, a[1] + (b[1] - a[1]) * f, a[2] + (b[2] - a[2]) * f))
    return out


def drive(series, hz, geo=True):
    stop = threading.Event()
    threading.Thread(target=heartbeat, args=(stop,), daemon=True).start()
    time.sleep(1.5)
    http("PUT", "/vehicle/gear?gear=Drive")
    dist, prev_v, t0 = 0.0, 0.0, time.monotonic()
    try:
        for k, (t, v, alt) in enumerate(series):
            dv = v - prev_v
            acc = max(0, min(100, int(dv * hz * 8))) if dv > 0 else (10 if v > 0 else 0)
            brk = max(0, min(100, int(-dv * hz * 8))) if dv < 0 else 0
            http("PUT", f"/vehicle/controls?speed={round(v)}&accelerator={acc}&brake={brk}&steering=0")
            dist += v / 3.6 / hz
            if geo and k % hz == 0:  # GPS는 1Hz
                lon = LON0 + dist / M_PER_DEG_LON
                subprocess.run([ADB, "emu", "geo", "fix", f"{lon:.6f}", f"{LAT0:.6f}", f"{5 + alt:.1f}"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            prev_v = v
            sleep = t0 + t - time.monotonic()
            if sleep > 0:
                time.sleep(sleep)
    finally:
        http("PUT", "/vehicle/controls?speed=0&accelerator=0&brake=0&steering=0")
        http("PUT", "/vehicle/gear?gear=Park")
        stop.set()
    print(f"완료 · {series[-1][0]:.0f}초 · {dist / 1000:.2f}km")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["list", "run"])
    ap.add_argument("name", nargs="?")
    ap.add_argument("--hz", type=int, default=5)
    ap.add_argument("--no-geo", action="store_true")
    a = ap.parse_args()
    if a.cmd == "list":
        for k, v in SCENARIOS.items():
            print(f"{k:14s} {sum(s[0] for s in v):4d}초")
        return
    if a.name.startswith("cycle:"):
        series = load_cycle(a.name[6:], a.hz)
    else:
        series = expand(SCENARIOS[a.name], a.hz)
    drive(series, a.hz, geo=not a.no_geo)


if __name__ == "__main__":
    main()

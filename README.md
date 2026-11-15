# 전비코치 (EV Coach) — 유한동력

**전기차 운전을 «온실가스 줄이는 게임»으로.** 가속·감속·속도 한 번 한 번을 차량 신호와 물리 모델로 계산해, 그 자리에서 «부드러운 감속! 온실가스 13그램 감축!»처럼 온실가스(g)와 충전비(원)로 알려 주는 Pleos Connect 전용 전비 코치 앱입니다. (현재 v0.7)

## 1. 앱 이름 · 한 줄 소개
- 앱 이름: 전비코치 (EV Coach) · 패키지 `app.evcoach`
- 한 줄 소개: 10Hz 차속·외기온·탑승·공조·도로 경사로 «지금 이 운전 = 온실가스 몇 g · 충전비 몇 원»을 계산해 권장 속도·감축 카드·음성·운행 리포트·가상 리그 순위로 보여 줍니다.

주요 기능(v0.7 · 에뮬레이터에서 동작)
- 권장 속도 + 100km당 감축량 — 항상 제한속도 이하 · 흐름 하한(제한의 85%) 이상 · 제한속도를 모르면 지금보다 빠른 속도는 권하지 않음
- 가감속 채점(A~F) · 부드러운 가감속/권장 속도 유지 «감축» 카드(1.5초 정적) + 효과음 + 짧은 음성 · 콤보 · 오늘 누적
- 도로 경사 반영 — GPS 고도 ÷ 차속 적분 거리(약 150m 구간 · ±12%) → 비용·권장·채점·리포트에 반영, 화면에 «오르막 4%»
- 정차 2초 → 운행 리포트(온실가스 감축·소나무 환산·에너지 내역·회생·가감속 기록) · 같은 길 가상 운전자 100명 중 순위(시뮬레이션 기준)

## 2. 실행 방법
**APK로 바로 실행(권장)**
```sh
adb install -r -g apk/유한동력_전비코치_release.apk
adb shell monkey -p app.evcoach -c android.intent.category.LAUNCHER 1
```
- `-g` = 차속·외기온·위치 권한을 설치 때 허용(안 주면 첫 실행 때 묻습니다 · 위치를 거부해도 평지 가정으로 동작).
- 에뮬레이터의 Virtual Console(차량 콘솔)에서 기어 D·속도를 올리면 주행 화면, 2초 정차하면 운행 리포트가 나옵니다.

**소스로 빌드**
```sh
cd coach
export JAVA_HOME=<JDK 17 경로>
./gradlew testReleaseUnitTest assembleDebug   # 단위 테스트 20개 + 설치용 빌드
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```
- release 서명키는 저장소에 없습니다(제출용 서명 APK는 `apk/`에 있습니다).
- Pleos SDK는 `settings.gradle.kts`의 Pleos Playground Maven 저장소에서 받습니다.

**시나리오 주행(선택 · 같은 장면 재현)**
```sh
python3 tools/vc_drive.py list              # 내장 시나리오
python3 tools/vc_drive.py run pace_demo     # 부드러운 가속 → 과속 안내 → 권장 유지 → 부드러운 감속
python3 tools/vc_drive.py run grade_demo    # 약 1km +40m 오르막 → 평지 → 내리막(경사 표시·비용 변화)
```
Virtual Console HTTP(127.0.0.1:8088)로 속도를 넣고, `adb emu geo fix`로 GPS 고도를 넣습니다(파이썬 표준 라이브러리만 사용).

## 3. 테스트 환경 및 필요한 설정
- Pleos Connect Emulator **v2.1.0** (Android Automotive · API 34) · 화면 **2560×1440**
- 빌드: JDK 17 · Android SDK 35 · Kotlin 2.0 · Jetpack Compose
- 권한: `android.car.permission.CAR_SPEED`, `CAR_EXTERIOR_ENVIRONMENT`, Pleos 차량(좌석·공조)·TTS·내비 권한, `ACCESS_FINE_LOCATION`(경사) — 매니페스트에 선언, `adb install -g`로 일괄 허용
- 인터넷 권한 없음 · 위치·주행 기록을 기기 밖으로 보내지 않습니다.
- 가정값(화면에 표기): 충전 단가 300원/kWh · 전력 배출계수 0.417kg/kWh(2023) · 소나무 1그루 연 6.6kg CO₂(산림청)

## 4. APK 파일 위치
- [`apk/유한동력_전비코치_release.apk`](apk/) — v0.7.0 release (서명 CN=EV Coach)

## 폴더
| 폴더 | 내용 |
|---|---|
| `coach/` | 본 앱 — `physics/` 주행저항·모터 효율·회생 모델 · `coach/` 코칭 엔진·가감속 채점·경사 추정·가상 리그·소나무 환산 · `ui/` 주행·정차 화면 · `Speaker`(Pleos TTS) |
| `probe/` | 신호 실측용 보조 앱(logcat) |
| `tools/` | `emu_up.sh` 에뮬레이터 기동 · `vc_drive.py` 시나리오 주행 · `record.sh` 화면 녹화 · `cycles/` 주행 사이클 CSV |
| `apk/` | 제출용 release APK |

## 오픈소스 고지
| 라이브러리 | 라이선스 |
|---|---|
| AndroidX (Activity, Lifecycle) · Jetpack Compose (UI, Material3) | Apache License 2.0 |
| Kotlin 표준 라이브러리 | Apache License 2.0 |
| JNA 5.13.0 (`net.java.dev.jna:jna`, Pleos SDK 경유) | Apache License 2.0 / LGPL 2.1 이중 라이선스 (Apache 2.0 적용) |
| JUnit 4.13.2 (테스트 전용) | Eclipse Public License 1.0 |
| Gradle Wrapper | Apache License 2.0 |
| Pleos Playground SDK (Vehicle 2.3.2 · TextToSpeech 3.3.1 · NaviHelper 3.0.5) | Pleos Playground 이용 약관에 따름 (저장소에 SDK 바이너리 미포함 · 빌드 때 Maven으로 받음) |

# 장기 교육 — Android (Kotlin · Jetpack Compose · NDK)

온디바이스 Fairy-Stockfish로 **모든 합법수의 예상 승률**을 보여주는 장기 분석/대국 앱.
서버·WebView·인터넷 권한 없음. 엔진은 앱 프로세스 안의 네이티브 스레드에서 돌아간다.

```
android/
├── app/        Compose UI, ViewModel, 설정/기보 저장, 벤치마크        (com.example.janggiai)
├── engine/     JanggiEngine API + Fairy-Stockfish(C++, 고정 커밋) + JNI  (com.example.janggiai.engine)
├── game/       규칙엔진 순수 Kotlin 포팅: 수 생성·규칙·표기·UCI 매핑·승률 모델 (JVM 단위 테스트)
├── tools/desktop-jni-test/   개발 PC에서 실제 네이티브 엔진으로 JNI·API 검증 (Android SDK 불필요)
├── licenses/   GPL-3.0.txt, Apache-2.0.txt
└── THIRD_PARTY_NOTICES.md
```

## 1. 아키텍처

```
Compose UI (ui/*)            상태만 그린다. 탭 → ViewModel
   │
ViewModel (GameViewModel / AnalysisViewModel)
   │  GameSession(불변 기보 상태) + AnalysisController(analysisId·디바운스·상태키 캐시)
   ▼
JanggiEngine (engine/api)    analyze(): Flow<AnalysisSnapshot>  ← 분석 모드 MultiPV = 합법수 개수
                             bestMove(): EngineMove              ← 대국 모드 MultiPV = 1
   │
FairyStockfishEngine (Kotlin, engine/fsf)   UCI 텍스트 프로토콜, 전용 I/O 스레드, 탐색 직렬화(Mutex)
   │  JniUciTransport → FairyStockfishNative (@JvmStatic external)
   ▼
libjanggi_engine.so          engine_host.cpp: 수정 없는 Fairy-Stockfish의 UCI::loop()을
                             메모리 std::cin/std::cout으로 감싼 인프로세스 호스트 + JNI
```

### 왜 인프로세스 JNI인가 (서브프로세스 대신)

* Android는 앱 데이터 디렉터리의 실행 파일 실행을 제한하고(W^X, targetSdk 29+), 자식 프로세스는
  앱과 수명이 다르며 죽어도 알기 어렵다.
* 인프로세스로 두면 `stop`이 `Threads.stop` 플래그를 직접 세워 **1 ms 안에** 반응하고, 프로세스가
  없어 관리할 파이프·좀비가 없다.
* Fairy-Stockfish 소스는 **한 줄도 수정하지 않았다**. `main.cpp`만 빌드에서 제외하고 그 역할을
  `engine_host.cpp`가 대신한다(변형·비트보드·옵션 초기화는 프로세스당 1회, 스레드 풀은 시작/종료 반복 가능).

### 규칙의 단일 진실

* 합법수는 Kotlin `game` 모듈이 만든다(Python 규칙엔진의 1:1 포팅). 엔진은 점수만 준다.
* 검증: perft(3) 30,353(한수쉼 제외) / perft(3) 33,000 · perft(4) **1,065,277** · 중반 42,026 ·
  janggicasual 33,316 — 모두 Fairy-Stockfish `go perft`와 **정확히 일치**. 추가로
  `DesktopEngineApiTest`/`FairyStockfishInstrumentedTest`가 세 국면에서 Kotlin 합법수 집합 ==
  엔진 `go perft 1` divide 목록임을 확인한다.
* 규칙 → 엔진 변형: 빅장 없음(기본, 카카오장기) → `janggicasual`, 전통 빅장 → `janggi`.

### 상태 정확성(원본 Python의 세 버그를 모두 반영)

| 문제 | 안드로이드에서의 처리 |
|---|---|
| TT 키가 보드 해시만 사용 | Python 측 수정(v0.4.0). 앱은 엔진 TT를 쓰지 않고 FSF에 **수순 이력을 포함한 `position fen … moves …`**를 보내 반복·한수쉼 상태를 엔진이 직접 본다. |
| 분석 캐시가 FEN만 키 | `Position.stateKey()` = 보드 해시·차례·한수쉼 대기·빅장·직전 빅장·규칙·잡은 이후 반복 컨텍스트. `AnalysisController`의 LRU 캐시와 UI의 "현재 국면 스냅샷" 판정이 모두 이 키를 쓴다. |
| 대국이 MultiPV=N으로 탐색 | `bestMove()`는 `setoption name MultiPV value 1`, `analyze()`만 `MultiPV = 합법수`. 명령 기록(`sentCommands`)으로 단위/계측 테스트가 검증. |

### 승률

`WinProbabilityModel` 계층: 기본은 로지스틱 `P = 1/(1+exp(-cp/300))` (Python `uci_engine.py`와 동일한
K=300, FSF janggi cp 척도). 임의 공식/난수 없음. `app/src/main/assets/calibration.json`(Python
`Calibrator.to_json()` 형식)을 넣으면 보정 모델로 교체되고 UI가 "승률"로 표시한다(없으면 "예상 승률").
mate/점수판정 종료는 100%/0%.

### 안정성

* 새 분석 요청마다 단조 증가 `analysisId`; 스냅샷에 id가 들어 있어 늦게 도착한 옛 결과는 버린다.
* 요청 간 150 ms 디바운스, 이전 탐색은 `stop` 후 `bestmove`를 받을 때까지 Mutex를 쥔다(동시 `go` 불가).
* 백그라운드(`onStop`) 시 탐색 중단, 복귀 시 재개(`AppForeground`).
* 화면 회전/재생성: `SavedStateHandle`에 startFen·수순·viewIndex·모드·난이도 저장.
* 엔진 스레드는 데몬 + 8 MB 스택(`USE_PTHREADS`, 업스트림 NDK 빌드와 동일).

### 대국 설정·판 방향·훈수 (v0.4.1)

* **초반 배치**: 새 대국 대화상자에서 내 진영(초/한/랜덤), 내 배치·AI 배치(마상상마·상마마상·마상마상·상마상마·랜덤)를 따로 고른다.
  배치 이름은 규칙엔진 `Position.SETUPS`(자기 진영 왼쪽→오른쪽)가 유일한 정의이며 UI는 `SetupChoice`로 그 키를 가리키기만 한다.
  랜덤은 시작 시 한 번 네 표준 배치 중 하나로 확정되고, 배치는 `startFen`에 담겨 SavedState·기보·undo와 그대로 호환된다.
* **선공**: 규칙엔진 정책(초 선공)을 그대로 쓴다. 사용자가 한이면 시작 직후 AI(초)가 먼저 둔다(`GameViewModel.maybeStartAi`는 "차례 ≠ humanSide"일 때만 탐색).
* **판 방향**: `game/BoardOrientation.kt` 한 곳에서만 engine↔display 매핑(뒤집기 = `89 − square`). `JanggiBoard`의 그리기와 터치 판정이
  모두 이 객체를 쓰므로 기물·합법수·배지·PV·훈수 표시가 같은 좌표계를 따른다. 수 표기·FEN·UCI는 항상 canonical 좌표. 사람 vs AI는
  사용자 진영이 아래(기본), 상단 [판 뒤집기]로 수동 전환; 분석 화면은 [초 시점/한 시점] 토글.
* **모든 합법수 표시**: 분석 UI 모델 `game/AnalysisCandidates.kt`는 절단하지 않는다(31합법수 → 31항목). 리스트 기본 `전체`(순위·착수·승률·평가·깊이),
  장기판은 설정 `전체/Top 5`(기본 전체; 상위 5는 승률 배지, 나머지는 순위 배지, 기물 선택 시 그 기물의 후보만). 엔진 MultiPV = 합법수 개수는 변함없음.
* **AI 훈수**(`coach/`): 사용자가 둔 직전 국면과 실제 수를 `CoachRequest`로 기억 → AI가 먼저 응답(MultiPV=1) → AI 착수 후 같은 엔진으로
  직전 국면을 MultiPV=N 분석 → `CoachEvaluator`가 사용자 진영 기준 승률로 Δ = best − played를 계산하고 `CoachThresholds`(상수 한 곳)로 등급
  (최선·훌륭함·좋음·무난·부정확·실수·큰 실수, mate 별도) → `CoachExplanationGenerator`가 보드·PV에서 검증 가능한 사실만 설명(포획·놓친 포획·장군·
  PV상 기물 손익·궁성 진입; 특정 불가 시 숫자 중심 문장). `CoachController`는 analysisId + "현재 기보에 그 수가 여전히 있는가"로 stale 결과를 버리고,
  undo·새 대국·빠른 다음 수에 분석을 취소한다. 훈수 예산(빠름 300/보통 800/정밀 1500 ms)은 AI 사고 시간과 별개. 훈수는 SavedState와 기보(optional
  `coach`, `humanSide` 키)에 저장되고, 기보에서 사용자 수를 탭하면 다시 볼 수 있다. AI의 수는 평가하지 않는다.

## 2. 빌드

### 툴체인 (2026-09 기준, Play 요구사항 반영)

| 구성 | 버전 | 이유 |
|---|---|---|
| Android Gradle Plugin | **8.13.2** (`gradle/libs.versions.toml`) | 8.x 마지막 안정 라인. API 36(36.1까지) 공식 지원, 최소 Gradle 8.13, JDK 17. compileSdk 36은 AGP ≥ 8.9.1이 필요하고 AGP 9.x로의 대규모 이전은 피함 |
| Gradle wrapper | **8.13** (`distributionSha256Sum` 고정) | AGP 8.13이 요구하는 최소·기본 버전. wrapper jar·스크립트는 공식 8.13 배포판(SHA-256 검증)으로 생성 |
| Kotlin / Compose 컴파일러 플러그인 | 2.0.21 | 이 저장소의 모든 Kotlin 검증에 쓴 컴파일러. Kotlin 공식 표의 "완전 지원" Gradle 범위(≤ 8.8) 밖이라 **경고**가 나올 수 있음(오류 아님). 오류가 되면 `kotlin = "2.3.21"`로 올리는 것이 다음 단계 |
| Compose BOM / Material3 | 2024.09.03 / 1.3.0 | 화면 코드가 이 API에 맞춰 검토됨. 필요 없으면 올리지 말 것 |
| compileSdk / targetSdk | **36 / 36** (app), 36 (engine) | Play: 2026-08-31부터 신규 앱·업데이트는 API 36 이상 |
| JDK | 17 (Studio 내장 JBR 사용) | AGP 8.x 최소 |
| NDK / CMake | **27.1.12297006** / 3.22.1 | 데스크톱 검증과 같은 소스·플래그. r27이므로 16 KB 정렬 링커 플래그를 명시 |
| minSdk | 26 | |

요구: Android Studio **Narwhal 3 Feature Drop(2025.1.3) 이상**, SDK Platform 36, NDK 27.1.12297006, CMake 3.22.1.
Windows에서 처음 빌드하는 절차는 **`../docs/FIRST_ANDROID_BUILD.md`** 를 따른다(짧은 경로, `core.longpaths`,
명령 순서, 실패 시 보낼 로그 범위).

```bash
cd android
./gradlew :game:test :engine:testDebugUnitTest :app:testDebugUnitTest   # JVM 단위 테스트 38개
./gradlew assembleDebug                                                 # app/build/outputs/apk/debug/app-debug.apk
./gradlew :engine:connectedDebugAndroidTest :app:connectedDebugAndroidTest   # 기기/에뮬레이터
./gradlew assembleRelease                                               # app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease                                                 # app/build/outputs/bundle/release/app-release.aab
```

* 릴리스 서명: `keystore.properties.example`을 참고해 `keystore.properties`(git 제외)를 작성하고,
  별도로 보관한 release keystore를 사용한다. `keystore.properties`가 없으면 release 빌드에 debug signing key를
  fallback으로 사용하지 않는다. 실제 서명 키와 `keystore.properties`는 저장소에 포함하지 않는다.
* ABI: `gradle.properties`의 `janggi.abis`(기본 `arm64-v8a,x86_64`). AAB는 ABI 분할 사용. 릴리스 AAB에는 네이티브
  심볼 테이블이 포함된다(`debugSymbolLevel = SYMBOL_TABLE`, APK 크기 영향 없음).
* 네이티브는 debug/release 모두 `-O3` Release 빌드(느린 디버그 엔진은 의미가 없음).

### 16 KB 페이지 크기 (Play 필수, 2025-11-01부터)

대응: `engine/src/main/cpp/CMakeLists.txt`의 `-Wl,-z,max-page-size=16384`(NDK r27은 기본이 4 KB), `app`·`engine`의
`packaging.jniLibs.useLegacyPackaging = false`(비압축 .so를 AGP 8.5.1+가 16 KB 경계로 정렬). 빌드 후 실제 산출물로
확인한다:

```bat
:: 1) ZIP 정렬 (build-tools 35+)
%ANDROID_HOME%\build-tools\35.0.0\zipalign.exe -c -P 16 -v 4 app\build\outputs\apk\release\app-release.apk
:: 2) ELF LOAD 세그먼트 정렬 — APK를 풀어 lib/arm64-v8a/libjanggi_engine.so 를 꺼낸 뒤
%ANDROID_HOME%\ndk\27.1.12297006\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe -l libjanggi_engine.so
::    -> 모든 LOAD 줄의 Align 이 0x4000 이어야 함
```

Linux/macOS에서는 Google의 `check_elf_alignment.sh APK` 스크립트(모든 arm64-v8a .so에 ALIGNED 출력) 또는
Android Studio의 `Build → Analyze APK…`(미정렬 라이브러리에 경고)로도 확인할 수 있다.

### 실제 Android / release 검증 상태

2026-09-20 기준 Windows + Android Studio 환경과 실제 Samsung SM-A346N 기기에서 다음을 확인했다.

* Android toolchain:
  * compileSdk / targetSdk 36
  * AGP 8.13.2
  * Gradle 8.13
  * Kotlin 2.0.21
  * JDK/JVM target 17
  * NDK 27.1.12297006
  * CMake 3.22.1

* 실제 기기 connected tests:
  * `:engine:connectedDebugAndroidTest` — **6/6 PASS**
  * `:app:connectedDebugAndroidTest` — **8/8 PASS**
  * 총 **14/14 PASS**

* Android 빌드:
  * `:app:compileDebugKotlin` — PASS
  * `:app:assembleDebug` — PASS
  * `:app:assembleRelease` — PASS
  * `:app:bundleRelease` — PASS
  * release APK 및 Play 제출용 AAB 생성 확인

* release signing:
  * 별도 release keystore 사용
  * release signing config 적용 확인
  * debug signing fallback 제거
  * 실제 keystore 및 `keystore.properties`는 Git에 포함하지 않음

* release 실기기 검증:
  * 앱 설치 및 실행 정상
  * 사람 vs AI 대국 정상
  * Fairy-Stockfish JNI/native engine 정상 로드
  * AI 응수 정상
  * 분석 모드 및 모든 후보수 표시 정상
  * 후보 리스트 스크롤 정상
  * 분석 모드 초/한 독립 마·상 배치 선택 정상
  * 분석 실행 정상
  * 앱 이름 및 런처 아이콘 정상

* R8 / resource shrinking:
  * release에서 `isMinifyEnabled = true`
  * `isShrinkResources = true`
  * 해당 설정으로 만든 release APK를 실기기에서 검증 완료

* native ABI packaging:
  * `arm64-v8a`
  * `x86_64`
  * 양 ABI에 `libjanggi_engine.so` 포함 확인

* 16 KB page-size 대응:
  * release APK 내 모든 native `.so`의 ELF LOAD segment가 `align 2**14`
  * `libjanggi_engine.so` arm64-v8a / x86_64 모두 확인
  * `libandroidx.graphics.path.so` arm64-v8a / x86_64 모두 확인
  * `zipalign -c -P 16 -v 4 app-release.apk` → **Verification successful**

현재 출시 application ID는 `com.jangedu.janggiai`, 표시 버전은 `1.0.0`이다.

## 3. 개발 PC에서 검증 (Android 없이)

### 3-0. 모듈 경계 컴파일 + 단위 테스트

```bash
KOTLINC=/path/to/kotlinc/bin/kotlinc JUNIT_CP=junit-4.13.2.jar:hamcrest-core-1.3.jar bash tools/module-check/check.sh
```

`:game` → `:engine` → `:app` 로직을 Gradle처럼 **모듈별로 이전 모듈의 jar에 대해** 컴파일하고 JUnit 4.13.2로 단위 테스트를
돌린다(`MODULE CHECK PASSED`). 코드 규칙: **다른 모듈에서 온 객체의 nullable 프로퍼티는 로컬 `val`에 담은 뒤 null 검사**한다
(`val t = limits.timeMs; if (t != null) …`). 같은 파일·같은 모듈에서는 통과하는 smart cast가 모듈 경계에서는 컴파일 오류가 된다.

### 3-1. 네이티브 엔진

```bash
# 1) 호스트용 .so 빌드 (g++ ≥ 9, JDK 헤더 — JRE만 있으면 JNI_INCLUDE로 헤더 위치 지정)
bash tools/desktop-jni-test/build.sh
# 2) JNI 스모크 테스트: uci, 변형, perft 33000/33316/42026, MultiPV 32, MultiPV 1, stop, 재시작
bash tools/desktop-jni-test/run.sh
# 3) Kotlin JanggiEngine API + 실제 엔진 (kotlinc + kotlinx-coroutines jar 필요)
kotlinc game/src/main/kotlin/com/example/janggiai/game/*.kt $(find engine/src/main/kotlin -name '*.kt') \
        tools/desktop-jni-test/DesktopEngineApiTest.kt -cp kotlinx-coroutines-core-jvm.jar -include-runtime -d api-test.jar
java -Djava.library.path=tools/desktop-jni-test/build -cp api-test.jar:kotlinx-coroutines-core-jvm.jar DesktopEngineApiTestKt
```

## 4. 패키지 이름 변경

* 현재 Play 패키지(applicationId)는 `com.jangedu.janggiai`이며,
  `gradle.properties`의 `janggi.applicationId`에서 관리한다.
* Kotlin 네임스페이스 `com.example.janggiai`는 그대로 두는 것을 권장: JNI 심볼
  `Java_com_example_janggiai_engine_fsf_FairyStockfishNative_*`가 이 패키지에 묶여 있다. 꼭 바꾸려면
  `engine/src/main/cpp/janggi_engine_jni.cpp`의 함수 이름과 `FairyStockfishNative.kt`의 패키지를 함께 바꾸고
  `tools/desktop-jni-test/run.sh`로 확인한다.
* 앱 이름: `app/src/main/res/values/strings.xml`의 `app_name`.

## 5. Fairy-Stockfish 갱신

`engine/fsf.properties`의 태그·커밋·SHA-256을 바꾸고 `../scripts/fetch_fairy_stockfish.sh`를 실행하면
소스를 다시 벤더링하며 해시가 다르면 실패한다. `FairyStockfishBuildInfo.kt`도 같이 갱신해야
`FairyStockfishBuildInfoTest`가 통과한다. 변경 후 반드시 3절의 데스크톱 검증을 다시 돌린다.

## 6. 알려진 한계 / TODO

* 라이선스: 앱 전체 소스는 GPL-3.0-or-later 조건으로 공개한다. 전체 corresponding source는
  https://github.com/Hingguripongpong/jangedu-android 에서 제공하며, 저장소 루트 `LICENSE`에 GPLv3 전문이 포함되어 있다.
* 출시 식별자: application ID는 `com.jangedu.janggiai`, 앱 이름은 `장기 교육`, 출시용 런처 아이콘 적용 완료.
  실제 release keystore와 `keystore.properties`는 보안상 저장소에 포함하지 않는다.
* 개인정보처리방침 초안은 `../docs/PRIVACY_POLICY.md`(공개 URL로 게시 필요; 앱 내 표시는 데이터 미수집 앱에는 필수가
  아니라 추가하지 않았다 — 아동 대상 앱으로 선언하면 필수).

* 기물 이미지: 기본은 한자 텍스트 렌더러(`TextPieceRenderer`). `PieceRenderer` 구현체를 바꿔 에셋 사용.
* 국면 편집기는 FEN 불러오기로 대체(V1 범위).
* 승률 모델은 미보정 prior(K=300). 실전 기보로 `calibration.json`을 만들어 넣는 절차는 Python
  `neural/README.md` §3.
* 연장군 금지 규칙 미구현(Python 원본과 동일).
* 벤치마크는 기기별 수치를 보여줄 뿐 CPU 사용률·발열은 측정하지 않는다.

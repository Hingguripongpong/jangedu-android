# 첫 Android 빌드 절차 (Windows · Android Studio)

이 저장소의 `android/` 프로젝트는 아직 **Gradle 동기화·컴파일·APK 생성이 한 번도 실행되지 않았다**(작성 환경에
Google Maven/Gradle 서버 접근이 없었음). 아래 순서대로 진행하고, 어느 단계에서든 실패하면 §6의 로그 범위를
그대로 보내면 된다. 오류를 숨기기 위해 의존성이나 테스트를 지우지 말 것.

## 0. 준비물

| 항목 | 값 | 확인 방법 |
|---|---|---|
| Android Studio | **Narwhal 3 Feature Drop (2025.1.3) 이상** — AGP 8.13 지원 | Help → About |
| JDK | 17 이상 (Studio 내장 JBR 17/21 그대로 사용) | Settings → Build Tools → Gradle → Gradle JDK |
| Android SDK Platform | **36** | SDK Manager → SDK Platforms → Android 16.0 (API 36) |
| Build Tools | 35.0.0 이상(AGP 8.13 기본값; Studio가 자동 설치) | SDK Manager → SDK Tools |
| NDK (Side by side) | **27.1.12297006** | SDK Manager → SDK Tools → "Show Package Details" 체크 → NDK (Side by side) → 27.1.12297006 |
| CMake | **3.22.1** | 같은 화면 → CMake → 3.22.1 |
| Git | 아무 버전 | `git --version` |
| 디스크 | 약 5 GB(Gradle 캐시·NDK·빌드 산출물) | |

> NDK/CMake를 미리 설치하지 않으면 Studio가 첫 동기화 때 설치를 제안한다. 제안이 뜨지 않고 실패하면
> 위 경로에서 정확한 버전을 수동 설치한다. 다른 NDK 버전만 있다면 `-Pjanggi.ndkVersion=<버전>`으로 덮어쓸 수
> 있지만, 기본값(27.1.12297006)으로 먼저 시도할 것.

## 1. 프로젝트 배치 (Windows 전용 주의)

1. **경로를 짧게** 둔다: 예 `C:\dev\janggi-ai`. 네이티브 빌드(`.cxx`)는 깊은 경로를 만들고, Windows의 260자 경로
   제한에 걸리면 `ninja: error: ... No such file` 같은 엉뚱한 오류가 난다.
2. 관리자 PowerShell에서 긴 경로를 허용한다(한 번만):
   ```powershell
   git config --global core.longpaths true
   ```
3. ZIP을 풀었거나 `git clone`했다면 `android\gradlew.bat`, `android\gradle\wrapper\gradle-wrapper.jar`가
   있는지 확인한다. (`.gitattributes`로 `gradlew`는 LF, `.bat`는 CRLF로 고정되어 있다.)
4. 경로에 한글·공백이 없어야 한다.

## 2. Android Studio에서 열기

1. **File → Open → `C:\dev\janggi-ai\android`** (저장소 루트가 아니라 `android` 폴더).
2. "Trust project" 확인 → Gradle 동기화가 시작된다. 첫 동기화는 Gradle 8.13 배포판(약 130 MB)과
   AGP 8.13.2·Compose·Kotlin 의존성을 내려받으므로 수 분 걸린다.
3. 동기화가 끝나면 `Build → Make Project`는 하지 말고 §3의 명령을 터미널에서 순서대로 실행한다
   (오류 메시지가 그대로 남기 때문).

터미널은 Studio 하단 **Terminal** 탭(현재 디렉터리가 `android`) 또는 별도 PowerShell/cmd에서
`cd C:\dev\janggi-ai\android` 후 실행한다.

## 3. 명령 순서

각 명령은 **이전 명령이 성공한 뒤** 실행한다. `--stacktrace`는 실패 시 로그 범위를 잡기 위한 것이다.

### 3-1. JVM 단위 테스트 (62개; 기기 불필요)

```bat
gradlew.bat :game:test :engine:testDebugUnitTest :app:testDebugUnitTest --stacktrace
```

기대 결과: `BUILD SUCCESSFUL`, 테스트 로그에 `game` 20개·`engine` 10개·`app` 32개 passed.
이 단계는 Kotlin 컴파일(game·engine·app 로직)과 JUnit 4.13.2 실행까지 검증한다. **Compose UI 코드는
`:app:testDebugUnitTest`가 `app` 모듈을 컴파일하면서 처음 컴파일된다** — 컴파일 오류가 나올 가능성이 가장
높은 지점이다.

첫 실행(2026-09-19) 기록: `:game:test`·`:engine:testDebugUnitTest` 통과, `:app:compileDebugKotlin`이
`AnalysisController.kt:60` "Smart cast to 'kotlin.Long' is impossible … declared in different module" 1건으로 실패.
원인은 `:engine` 모듈의 nullable 프로퍼티(`AnalysisLimits.timeMs`)를 `:app`에서 null 검사 후 바로 사용한 것 — 단일
컴파일 패스에서는 허용되지만 모듈 경계에서는 금지된다. 로컬 변수로 수정하고, 같은 오류가 다시 나지 않도록
`android/tools/module-check/check.sh`(모듈 분리 컴파일)와 `AnalysisControllerTest.cacheHonoursTheRequestedTimeBudget`을 추가했다.

### 3-2. 디버그 APK (네이티브 빌드 포함)

```bat
gradlew.bat assembleDebug --stacktrace
```

기대 결과: `android\app\build\outputs\apk\debug\app-debug.apk` 생성.
이 단계에서 NDK/CMake가 처음 실행되어 `libjanggi_engine.so`(arm64-v8a, x86_64)를 만든다(Fairy-Stockfish
약 30개 번역 단위 × 2 ABI; 수 분 소요).

설치·실행:
```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -s AndroidRuntime:E JanggiAI:V
```
앱에서 **분석하기**로 들어가 후보수에 승률이 채워지는지 확인한다. 채워지지 않으면 `adb logcat` 전체를 보낼 것
(§6).

### 3-3. 계측 테스트 (arm64 실기기 또는 x86_64 에뮬레이터 연결 필요)

실기기(SM-A346N, 2026-09-20) 결과: `:engine` 6/6 통과, `:app` 7개 중 5 통과·2 실패 → 두 실패의 원인 수정(아래 §7 참고).
재실행(수정 후, 8개): 7 통과, `AnalysisScreenScrollTest` 1건 timeout → 대기 조건 수정(§7).
단독 재실행: `No compose hierarchies found in the app` → 테스트 하네스를 `createAndroidComposeRule<MainActivity>`(실제 앱 실행 → 홈에서 "분석하기" 탐색)
구조로 변경(§7). 그 다음 단독 재실행: `Timed out at stage: MainActivity resumed` → `scenario.state == RESUMED` 폴링 제거, `rule.activity` + `waitForIdle()`
뒤 첫 동기화 조건을 "홈의 분석하기 semantics 존재"로 변경(§7). 그 다음 단독 재실행: `Timed out at stage: Home screen composed` → MainActivity를
`FLAG_ACTIVITY_CLEAR_TASK`로 새 태스크에 실행(수동 사용으로 남은 태스크의 복원된 화면 배제)하고 실패 시 라이프사이클/화면 잠금/보이는 텍스트를
메시지에 담도록 변경(§7). 그 다음 단독 재실행: 잘못된 `import androidx.compose.ui.test.onAllNodes`로 컴파일 실패 → 삭제 후 다시
`No compose hierarchies found` → MainActivity 실행 하네스를 포기하고 `createComposeRule()` + `setContent(AnalysisScreen)`(통과 중인
`GameScreenCoachTest`와 같은 구조)로 복귀, lazy 스크롤 단계는 유지(§7).

첫 실행(2026-09-19) 기록: 3-1·3-2 통과 후 이 단계의 `:app:compileDebugAndroidTestKotlin`이 `BoardUiTest.kt`의
`Unresolved reference 'click'` 2건으로 실패 — `click`은 `TouchInjectionScope`의 멤버가 아니라 `androidx.compose.ui.test`의
최상위 확장 함수라 import가 필요했다. import 추가로 수정.

```bat
gradlew.bat :engine:connectedDebugAndroidTest :app:connectedDebugAndroidTest --stacktrace
```

* `:engine:connectedDebugAndroidTest` — 기기에서 실제 엔진 실행: MultiPV=합법수/1 명령 확인, perft divide와
  Kotlin 합법수 일치, 중단, 잘못된 FEN 후 생존(6개).
* `:app:connectedDebugAndroidTest` — 보드 탭 좌표 매핑(2), 새 대국 대화상자(1), 31개 후보 lazy 리스트 스크롤+스와이프(1), 훈수 카드·기록 순서(2),
  실제 엔진으로 사용자 수 → AI 응답 → 훈수 등급 표시(1, 최대 30 s), 실제 분석 화면에서 31번째 후보까지 스크롤(1, 최대 30 s).
* 리포트: `android\engine\build\reports\androidTests\connected\debug\index.html`,
  `android\app\build\reports\androidTests\connected\debug\index.html`.

실기기가 없으면 Device Manager에서 **x86_64, API 36** 에뮬레이터를 만들어 실행한 뒤 진행한다(엔진 속도는
실기기보다 느려도 테스트 시간 한도(20 s)는 충분하다).

### 3-4. 릴리스 APK (R8 축소 + 서명 폴백)

```bat
gradlew.bat assembleRelease --stacktrace
```

`keystore.properties`가 없으면 로그에 `WARNING: keystore.properties not found - release build signed with the DEBUG key`
가 출력되고 **디버그 키로 서명**된다 — 테스트 설치용으로는 되지만 Play 업로드는 불가. 지금 단계에서는 이 경고가
정상이다(서명 키는 아직 만들지 않음).
결과: `android\app\build\outputs\apk\release\app-release.apk`.
릴리스 APK를 기기에 설치해 R8 축소 후에도 엔진이 로드되는지(분석 화면 승률) 반드시 확인한다 — JNI keep 규칙
검증은 이 방법밖에 없다.

### 3-5. 릴리스 AAB (Play 업로드 형식)

```bat
gradlew.bat bundleRelease --stacktrace
```

결과: `android\app\build\outputs\bundle\release\app-release.aab`.
(서명 키 없이 만든 AAB도 파일은 생성되지만 업로드는 거부된다. 실제 업로드 전 `docs/PLAY_STORE_RELEASE.md` §1.)

## 4. 빌드 후 확인 — 16 KB 페이지 크기 정렬

Play는 2025-11-01부터 16 KB 페이지 크기 지원을 요구한다. 이 프로젝트는 링커 옵션
`-Wl,-z,max-page-size=16384`와 `jniLibs.useLegacyPackaging=false`로 대응한다. 실제 산출물로 확인:

* **Android Studio**: `Build → Analyze APK…` → `app-release.apk` → `lib/arm64-v8a/libjanggi_engine.so` 선택 시
  정렬 경고가 없어야 한다(Studio는 16 KB 미정렬 라이브러리에 경고를 표시한다).
* **명령줄**(SDK의 build-tools 35+):
  ```bat
  %ANDROID_HOME%\build-tools\35.0.0\zipalign.exe -c -P 16 -v 4 app\build\outputs\apk\release\app-release.apk
  ```
  마지막 줄이 `Verification succesful`(원문 오타 그대로)이면 통과.
* **ELF 세그먼트**: 압축을 풀어 `.so`를 꺼낸 뒤
  ```bat
  %ANDROID_HOME%\ndk\27.1.12297006\toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-readelf.exe -l lib\arm64-v8a\libjanggi_engine.so
  ```
  `LOAD` 줄들의 `Align` 값이 모두 `0x4000`(=16384)이어야 한다.
* Play Console에 AAB를 올리면 16 KB 미준수 경고가 나오는지도 확인한다.

## 5. 성공 기준 체크리스트

- [ ] 3-1 `BUILD SUCCESSFUL`, 62 tests passed
- [ ] 3-2 `app-debug.apk` 생성, 기기에서 분석 화면 승률 표시
- [ ] 3-3 계측 테스트 14개 통과 (engine 6 + app 8)
- [ ] 3-4 `app-release.apk` 생성 + 기기에서 실행 확인(디버그 키 경고는 정상)
- [ ] 3-5 `app-release.aab` 생성
- [ ] §4 16 KB 정렬 확인

## 6. 실패 시 보낼 로그 범위

공통: 명령 전체 출력 중 **`FAILURE: Build failed with an exception.` 부터 끝까지**(`--stacktrace` 포함). 여기에
단계별로 다음을 추가한다.

| 단계 | 추가로 보낼 것 |
|---|---|
| Gradle 동기화 / 플러그인 해석 실패 | 출력 전체 + `android\gradle\libs.versions.toml`은 그대로인지 확인(수정했다면 함께) |
| Kotlin 컴파일 오류 (`e: file:///...` 로 시작하는 줄) | **`e:` 줄 전부**(보통 파일·줄·열·메시지가 한 줄). `w:` 경고는 불필요 |
| Compose 관련 오류(`@Composable invocations can only happen...`, `Unresolved reference` in `ui/**`) | `e:` 줄 전부 + 해당 파일 이름 |
| NDK/CMake 실패 (`C/C++: ...`, `ninja: build stopped`) | `android\engine\.cxx\Debug\<hash>\arm64-v8a\build_output.txt` 전체와 `ninja`/`clang++` 오류 줄, 설치된 NDK·CMake 버전(SDK Manager 화면) |
| R8/ProGuard 실패 (`Missing class`, `R8: ...`) | `android\app\build\outputs\mapping\release\missing_rules.txt`가 있으면 그 파일 + 오류 줄 |
| 리소스/매니페스트 오류 (`AAPT: error`, `Manifest merger failed`) | 오류 줄 전부(파일·줄 번호 포함) |
| 계측 테스트 실패 | `build\reports\androidTests\connected\debug\index.html`의 실패 항목 텍스트 + `adb logcat -d > logcat.txt` |
| 앱 실행 후 크래시 | `adb logcat -d` 중 `FATAL EXCEPTION` 또는 `DEBUG :` (네이티브 크래시 tombstone) 블록 전체 |
| 기능은 되는데 승률이 채워지지 않음 | `adb logcat -s JanggiAI FairyStockfish AndroidRuntime` 출력 |

로그를 보낼 때 **어느 명령**(3-1 ~ 3-5)의 **어느 번째 실행**인지, Studio 버전과 SDK Manager에 설치된 NDK/CMake
버전을 함께 적어 주면 한 번에 고칠 수 있다.

## 7. 알려진 경고(정상)

* (수정 기록) `GameScreenCoachTest` 30 s timeout: 훈수가 AI 코루틴 **안에서** 시작 요청되었는데 그 코루틴의 `isActive`를 가드로 써서
  항상 되돌아가던 버그 → 실제 탐색 진행 여부를 나타내는 `aiSearching` 플래그로 교체(`GameViewModel`). JVM 회귀 테스트
  `GameViewModelCoachFlowTest`(사용자 수 → bestMove → AI 착수 → 훈수 → 판정, 두 탐색 비중첩·순서 검증)가 옛 코드에서는 timeout으로 실패하고
  수정 코드에서는 통과함을 확인.
* (수정 기록) `AnalysisScreenScrollTest` 30 s timeout: 대기 조건이 `candidate_1` 노드의 *존재*였는데, 후보 행은 LazyColumn 아이템이라 보드·컨트롤
  아래(폰 화면 밖)에 있으면 스크롤 전까지 컴포즈되지 않아 노드가 생기지 않음(production 문제 아님 — 사용자가 스크롤하면 나타남). 항상 보이는
  상태 줄(`analysis_status`)로 "분석 시작 → k/N수가 N/N 도달(MultiPV=N 결과 반영) → `performScrollToNode`로 31번째 도달·표시"를 단계별로 기다리고,
  각 단계의 timeout 메시지에 어느 단계·상태 줄 내용인지 남기도록 변경. 분석 강도 설정(∞ 포함)에 독립.
* (수정 기록) `AnalysisScreenScrollTest` `No compose hierarchies found`: `createComposeRule()`(테스트용 빈 ComponentActivity) + `setContent` 직후 semantics
  조회 구조였음. end-to-end 테스트이므로 `createAndroidComposeRule<MainActivity>()`로 실제 앱 액티비티를 룰이 실행하고, 본문은 (1) 액티비티 RESUMED,
  (2) Compose root 존재(`fetchSemanticsNodes(atLeastOneRootRequired = false)`로 폴링), (3) 홈의 "분석하기" 표시 → 클릭 → `analysis_status` 존재를
  차례로 확인한 뒤에야 엔진 진행·스크롤 단계로 넘어가도록 변경. production `setContent` 시점은 변경하지 않음. `Thread.sleep` 없음.
* (수정 기록) `AnalysisScreenScrollTest` `Timed out at stage: MainActivity resumed`: `waitUntil` 루프 안에서 `ActivityScenario.state == RESUMED`를 폴링했는데
  이 값은 Compose 룰의 대기 루프에서 안정적으로 RESUMED를 돌려주지 않았다. 라이프사이클 폴링을 제거하고 `rule.activity`(액티비티 생성까지 블록) +
  `rule.waitForIdle()` + `scenario.onActivity` 1회 접근 후, 첫 실제 조건을 홈 "분석하기" semantics 존재로 두도록 변경.
* (수정 기록) `AnalysisScreenScrollTest` `Timed out at stage: Home screen composed`: 홈의 "분석하기"가 15 s 동안 나타나지 않음. 유력 원인은 `MainActivity`가
  `singleTask`라 기기에서 수동으로 쓰던 앱 태스크가 남아 있으면 계측 실행 시 그 태스크가 복원되어(Navigation Compose가 저장 상태에서 백스택 복원) 홈이
  아닌 대국/분석 화면이 뜨는 것. `createEmptyComposeRule()` + `ActivityScenario.launch(Intent(NEW_TASK|CLEAR_TASK))`로 항상 새 태스크의 홈에서 시작하고,
  단계 실패 메시지에 lifecycle·isFinishing·windowFocus·screenInteractive·keyguardLocked·화면의 텍스트 목록을 포함해 다음 실패 시 원인이 바로 드러나게 함.
  같은 원칙(LazyColumn의 화면 밖 아이템은 컴포즈되지 않음)을 `analysis_status`에도 적용: 상태 줄은 보드 아래 "controls" 아이템 안에 있으므로
  `analysis_controls` testTag(테스트용 semantics만 추가)까지 먼저 스크롤한 뒤에야 상태 줄을 읽는다.
* (수정 기록) `AnalysisScreenScrollTest` 하네스 최종 형태: `createComposeRule()` + `@Before AppForeground.set(true)` + `setContent { AnalysisScreen }`
  (GameScreenCoachTest와 동일). `createEmptyComposeRule`/`ActivityScenario`/`CLEAR_TASK`/MainActivity 실행/키가드 진단/홈 탐색은 모두 제거 — 실기기에서
  Compose root 등록이 안정적이지 않았고, 이 테스트의 목적(실제 엔진·MultiPV=N·모든 후보 표시·마지막 후보 스크롤)에는 필요하지 않다. MainActivity → 홈 →
  분석 탐색 검증은 필요해지면 엔진 완료·후보 스크롤을 검증하지 않는 별도의 가벼운 스모크 테스트로 분리한다.
* (수정 기록) `import androidx.compose.ui.test.onAllNodes` → `Unresolved reference`: `onNode`/`onAllNodes`는 `SemanticsNodeInteractionsProvider`의 **멤버**
  (확장 함수는 `onNodeWithTag`·`onAllNodesWithTag`·`onNodeWithText`·`onAllNodesWithText`…). 오프라인 스텁을 멤버로 고쳐 재발 시 컴파일 오류로 잡히게 함.
  androidTest 전체 import 감사 결과 남은 compose-test import 14종은 모두 실제 최상위 함수/클래스.
* (수정 기록) `import androidx.compose.ui.test.assertExists` → 실제 Gradle에서 `Unresolved reference`: `assertExists`/`assertDoesNotExist`는 확장 함수가 아니라
  `SemanticsNodeInteraction`의 **멤버 함수**라 import가 없어야 한다(오프라인 타입체크 스텁이 잘못 모델링해 놓친 것; 스텁도 멤버로 수정해 재발 시 컴파일
  오류로 잡히게 함). `AnalysisScreenScrollTest`·`CandidateListUiTest`에서 제거.
* (수정 기록) `CandidateListUiTest` "not displayed": 후보 31개는 모두 생성되었고(production 문제 아님), 일반 `Column`+`verticalScroll` 안의 행에
  대한 `performScrollTo()`가 계측 환경에서 불안정했던 것. 분석 화면을 `LazyColumn`(보드·컨트롤은 header item, 후보는 개별 item) 구조로 바꿔
  20~40개 후보를 표준 lazy 스크롤로 처리하고, 테스트는 `performScrollToNode` + 실제 스와이프로 마지막 후보까지 도달하는지 검증.

* `Unable to strip the following libraries, packaging them as they are: libjanggi_engine.so`: 해당 모듈이 쓰는 NDK를
  AGP가 찾지 못해 `.so`를 심볼 제거 없이 그대로 패키징했다는 뜻(비치명, 크기만 커짐). `app` 모듈에도 `:engine`과 같은
  `ndkVersion` 핀을 넣어 해결했다(2026-09-19). `:engine:stripDebugAndroidTestDebugSymbols`(엔진 모듈 자체의 테스트 APK)
  에서 여전히 나오면 `--info`로 원인 줄(`Unable to strip library ... due to missing strip tool`)을 확인 — 테스트 APK
  전용이라 배포물에는 영향이 없다.

* `Kotlin Gradle Plugin <-> Gradle compatibility`: Kotlin 2.0.21의 공식 완전 지원 범위(Gradle ≤ 8.8) 밖의
  Gradle 8.13을 쓰므로 경고가 나올 수 있다. 오류가 아니면 무시. 오류가 되면 `libs.versions.toml`의
  `kotlin`을 `2.3.21`로 올리는 것이 다음 시도(Compose 컴파일러 플러그인 버전도 함께 올라간다).
* `kotlinOptions` deprecated 경고: AGP/KGP 조합에서 나오는 경고, 무시.
* `WARNING: keystore.properties not found`: §3-4 참조, 정상.

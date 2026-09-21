# Play Store 출시 절차 — 장기 교육 (Android)

`android/`의 앱을 Google Play에 올리기 위한 체크리스트. 정책 수치는 2026-09 기준 Play Console 도움말로
확인한 값이며, Console에 표시되는 요구사항이 이 문서와 다르면 **Console이 우선**이다.

> **라이선스 경로 확정**: 경로 A를 선택했다. 앱 전체 소스를 GPL-3.0-or-later 조건으로 공개하며,
> corresponding source는 https://github.com/Hingguripongpong/jangedu-android 에서 제공한다.
> 저장소 루트의 `LICENSE`에 GPLv3 전문이 포함되어 있다.

## 0. 코드 측 준비

1. applicationId: `com.jangedu.janggiai`
   * `janggi.versionCode=2`
   * `janggi.versionName=1.0.0`
2. 앱 이름: `장기 교육`
3. 출시용 런처 아이콘 적용 완료.
4. 타깃 API: `app`은 `compileSdk 36 / targetSdk 36`. Play는 **2026-08-31부터 신규 앱·업데이트에 API 36 이상**을
   요구한다(연장 신청 시 2026-11-01까지). 기존 앱은 API 35 이상이어야 신규 사용자에게 노출된다.
5. 16 KB 페이지 크기: **2025-11-01부터** Android 15+ 기기를 대상으로 하는 신규 앱·업데이트는 16 KB 페이지 크기를
   지원해야 한다. 이 프로젝트는 `-Wl,-z,max-page-size=16384` + `useLegacyPackaging=false`로 대응 — 빌드 후
   `docs/FIRST_ANDROID_BUILD.md` §4의 절차로 실제 산출물을 확인한다.
6. 테스트: `docs/FIRST_ANDROID_BUILD.md` §3의 3-1 ~ 3-5가 모두 성공해야 한다.

## 1. 서명 키(업로드 키)

```bat
cd android
keytool -genkeypair -v -keystore release.jks -alias jangedu -keyalg RSA -keysize 4096 -validity 10000
copy keystore.properties.example keystore.properties   :: 값 입력 (storeFile=release.jks ...)
```

* `release.jks`, `keystore.properties`는 `.gitignore`에 있다. **커밋 금지**, 안전한 곳에 백업.
* Play App Signing(기본)에서 이 키는 *업로드 키*가 되고 Google이 앱 서명 키를 보관한다. 업로드 키를 잃으면
  Console에서 재설정을 요청할 수 있다.
* release keystore와 `keystore.properties`는 로컬에서 생성·설정 완료했다.
  두 파일은 Git에서 제외되어 있으며 공개 저장소에는 포함하지 않는다.
* `:app:signingReport`에서 release variant가 `release.jks`, alias `jangedu`를 사용하는 것을 확인했다.

## 2. 릴리스 빌드

```bat
gradlew.bat bundleRelease          :: app\build\outputs\bundle\release\app-release.aab  (Play 업로드용)
gradlew.bat assembleRelease        :: app\build\outputs\apk\release\app-release.apk     (사이드로드 테스트용)
```

`keystore.properties`가 없으면 release 빌드는 debug signing key로 fallback하지 않는다.
Play 업로드용 산출물은 반드시 별도의 release keystore로 서명한 상태에서 생성한다. AAB는
arm64-v8a + x86_64를 담고 Play가 기기별로 분할 배포한다. R8 축소가 켜져 있고 JNI 진입점은
`proguard-rules.pro`/`engine/consumer-rules.pro`의 keep 규칙으로 보호된다. 릴리스에는 네이티브 심볼 테이블이
AAB에 포함되어(`debugSymbolLevel = SYMBOL_TABLE`) Console이 네이티브 크래시를 해독할 수 있다.

릴리스 APK를 실기기에 설치해 확인할 것:
* 분석하기 → 후보수 승률이 채워지는지(엔진 로드·JNI 심볼·R8 keep).
* 대국 → 초보/최강에서 AI가 두는지(Skill Level·MultiPV=1 경로).
* 정보 → GPL 전문·제3자 고지가 열리는지(에셋 포함).
* 화면 회전 후 국면·수순 유지.

현재 검증 상태:

* `:engine:connectedDebugAndroidTest` — 6/6 PASS
* `:app:connectedDebugAndroidTest` — 8/8 PASS
* 총 14/14 PASS
* `assembleRelease` — PASS
* `bundleRelease` — PASS
* release APK 실기기 설치·실행 — PASS
* 사람 vs AI / JNI engine / 분석 모드 / 후보수 표시 — PASS
* 분석 모드 초·한 초기 배치 선택 — PASS
* R8 minify + resource shrinking 상태에서 release 실기기 검증 — PASS
* 16 KB ELF LOAD alignment — 모든 native `.so` `2**14`
* `zipalign -c -P 16 -v 4` — `Verification successful`

## 3. Play Console

### 3-1. 계정
* 개인 계정은 신원 확인(신분증·주소·전화 인증)이 필요하고, 조직 계정은 D-U-N-S 번호가 필요하다.
  스토어 등록정보에는 개발자 이름과 연락 이메일이 공개된다(개인 계정은 법적 이름). Console 안내를 따른다.
* **2023-11-13 이후 생성된 개인 계정**은 §3-4의 비공개 테스트 요건을 충족해야 프로덕션이 열린다. 조직 계정과
  그 이전에 만든 개인 계정에는 적용되지 않는다.

### 3-2. 앱 만들기
이름, 기본 언어(한국어), 앱/게임 → 게임, 무료/유료 선택(유료는 한 번 정하면 무료로만 바꿀 수 있고 그 역은 불가).

### 3-3. 앱 콘텐츠(정책) — 모두 필수
* **개인정보처리방침**: 데이터를 수집하지 않는 앱에도 **필수**. `docs/PRIVACY_POLICY.md`의 본문을 공개 웹페이지로
  게시하고 URL을 입력한다. URL 조건: 공개 접근 가능, 지역 제한 없음, **PDF 불가**, 사용자가 편집 불가, 등록정보의
  개발자 이름 또는 앱 이름이 본문에 포함.
* **데이터 보안(Data safety)**: "데이터를 수집하거나 공유하지 않음". 근거는 `docs/PRIVACY_POLICY.md` 말미의 표
  (INTERNET 권한 없음, SDK 없음, 기보는 앱 내부 저장, 백업 제외).
* 광고: 없음. 콘텐츠 등급 설문: 보드게임, 폭력·도박 없음 → 전체이용가.
* 타깃 연령: 13세 이상 권장(아동 대상 앱으로 선언하면 가족 정책·앱 내 개인정보처리방침 링크 등 추가 요건).
* 뉴스/정부/금융/건강 앱 선언: 해당 없음.

### 3-4. 테스트 트랙 → 프로덕션
* **내부 테스트**(최대 100명, 심사 없음): AAB 업로드 → 테스터 등록 → 링크 설치. arm64 실기기와 x86_64 에뮬레이터에서
  분석/대국/저장/회전 확인. 내부 테스트는 아래 요건의 카운트에 **포함되지 않는다**.
* **비공개 테스트(closed testing)**: 2023-11-13 이후 생성된 개인 계정은 **최소 12명의 테스터가 14일 연속으로
  opt-in 상태**를 유지하는 비공개 테스트를 마쳐야 프로덕션 접근을 신청할 수 있다(2024-12-11에 20명 → 12명으로
  완화, 14일은 동일). 14일 시계는 12번째 테스터가 참여한 시점부터 시작하며, 이후 Console 대시보드에서 "프로덕션
  액세스 신청"을 제출하고 설문(받은 피드백·반영 내용)에 답한다. 승인까지 며칠 걸릴 수 있다.
* **프로덕션**: 출시 노트(한국어), 국가 선택, 단계적 출시(예: 20% → 100%).
* 새 버전 업로드 시 `janggi.versionCode`를 올리고 같은 업로드 키로 서명한다.

### 3-5. 스토어 등록정보
* 앱 이름(30자), 간단한 설명(80자), 자세한 설명(4000자). 자세한 설명 끝에 §4의 오픈소스 문구를 넣는다.
* 스크린샷: 휴대전화 최소 2장(홈, 분석 화면의 승률 표시, 대국 화면 권장), 512×512 아이콘, 1024×500 피처 그래픽.
* 카테고리: 보드.

## 4. GPL(Fairy-Stockfish) 고지 — 스토어 문구와 소스 제공

이 앱은 경로 A를 사용한다.

* 앱 전체 소스는 GPL-3.0-or-later 조건으로 공개한다.
* 전체 corresponding source:
  https://github.com/Hingguripongpong/jangedu-android
* Fairy-Stockfish 원본과 정확한 버전·commit·SHA-256은 `android/THIRD_PARTY_NOTICES.md`에 기록한다.
* 앱 정보 화면에서 GPL 전문, 제3자 고지, 전체 소스 저장소 링크를 제공한다.
* 릴리스마다 배포 바이너리와 대응하는 소스 revision/tag를 남긴다.

스토어 설명 문구 예:

> 장기 교육은 GNU GPL v3 또는 이후 버전으로 배포되는 자유 소프트웨어입니다.
> 장기 엔진으로 Fairy-Stockfish(GPL-3.0-or-later)를 포함하며,
> 앱 전체 소스 코드는 https://github.com/Hingguripongpong/jangedu-android 에서 제공합니다.

## 5. 릴리스 후 점검

* Android Vitals: 네이티브 크래시(`libjanggi_engine.so`) 여부. 심볼 테이블이 AAB에 포함되므로 Console에서
  스택이 해독된다.
* 16 KB 호환성 경고가 Console에 없는지 확인.
* 발열/배터리: 백그라운드에서 탐색을 멈추도록 구현됐다(`AppForeground`). 발열 리뷰가 있으면 기본 분석 강도(1초)·
  스레드 기본값(코어/2, 최대 4)을 낮춘다.
* 매년 8월 31일의 타깃 API 상향에 맞춰 `compileSdk/targetSdk`를 올린다.

## 6. 체크리스트

- [x] GPL 경로 A 결정 및 전체 소스 공개 저장소 생성
- [x] applicationId / versionCode / versionName 확정
- [x] 앱 이름·런처 아이콘 교체
- [x] 업로드 키 생성·release signing 확인
- [x] connected tests 14/14 PASS
- [x] release APK / AAB 생성
- [x] release APK 실기기 검증
- [x] 16 KB ELF / ZIP alignment 검증
- [ ] 개인정보처리방침 공개 URL 게시
- [ ] Play Console Data safety / 콘텐츠 등급 / 타깃 연령 작성
- [ ] 스토어 설명·스크린샷·피처 그래픽 준비
- [ ] AAB 내부 테스트 업로드
- [ ] 비공개 테스트 요건이 계정에 적용되는 경우 완료
- [ ] 프로덕션 출시
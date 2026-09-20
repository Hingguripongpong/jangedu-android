# 벤치마크 — Android 포팅

## 측정 환경 (실제로 실행한 것)

* 개발 컨테이너: 1 vCPU x86-64, 3 GB RAM, g++ 13 `-O2`, `libjanggi_engine.so` 리눅스 호스트 빌드
  (Android CMake와 같은 소스·정의: LARGEBOARDS, PRECOMPUTED_MAGICS, NNUE_EMBEDDING_OFF, USE_PTHREADS, IS_64BIT)
* 엔진: Fairy-Stockfish 14 LB (fairy_sf_14, f3e6969d), 스레드 1, Hash 32 MB, 변형 janggi

| 모드 | 예산 | 결과 |
|---|---|---|
| 분석 MultiPV=32 (시작 국면) | 1500 ms | 32수 전부 점수, 완료 깊이 7 (최대 8), 209,479 nodes, **139k nps**, 15 스냅샷 |
| 대국 MultiPV=1 (시작 국면) | 800 ms | 깊이 13, 최선수 `車 08→87` |
| 대국 Skill 3 | 300 ms | 정상 응답 (`Skill Level` 옵션 적용 확인) |
| `go infinite` → 취소 | — | 엔진 해제까지 **251 ms** (JNI 직접 `stop`은 1 ms) |
| perft(3) 시작/중반/casual | — | 33,000 / 42,026 / 33,316 (Python·Kotlin·FSF 일치) |
| Kotlin perft(4) 시작(빅장 forced+한수쉼) | — | 1,065,277, JIT 후 약 0.2 s |

세션 성공 판정: 위 항목 모두 `DesktopEngineApiTest`/`DesktopJniSmokeTest`에서 검사로 통과.

## 기기 수치 (미측정)

이 저장소 작성 환경에는 Android 기기/에뮬레이터가 없어 **arm64 실기기 수치는 없다**. 앱의
설정 → 엔진 벤치마크 화면이 300 ms / 1 s / 3 s에서 분석(MultiPV=N)·대국(MultiPV=1)의 깊이·노드·nps·
실제 소요 시간과 네이티브 힙 사용량을 표로 보여주므로 첫 실기기 빌드 후 이 표를 채워 넣을 것:

| 기기 | 스레드 | 분석 1 s 깊이 | 분석 1 s nps | 대국 1 s 깊이 | 네이티브 힙 |
|---|---|---|---|---|---|
| (예) Pixel 8 | 2 | | | | |

참고: 현대 arm64 폰의 단일 코어는 이 컨테이너보다 빠르며, Stockfish 계열은 스레드 2개에서 nps가 거의
2배가 된다. 기본 스레드 수는 `코어/2`(1..4)로 잡아 UI 스레드와 발열 여유를 남겼다.

## 메모리

* Hash 32 MB 기본(설정 16/32/64/128). FSF 정적 테이블(비트보드·매직·변형)은 수 MB.
* Kotlin 측 분석 캐시 64국면(스냅샷당 수십 KB).
* 16 KB 페이지 정렬 링크(`-Wl,-z,max-page-size=16384`).

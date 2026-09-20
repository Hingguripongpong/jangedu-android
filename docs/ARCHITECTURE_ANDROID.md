# Android 아키텍처 노트

(요약은 `android/README.md` §1. 여기서는 설계 결정의 근거만 남긴다.)

## 계층과 의존 방향

`ui → ViewModel → (GameSession, AnalysisController) → JanggiEngine 인터페이스 → FairyStockfishEngine → UciTransport → JNI → C++`

* `game` 모듈은 Android에 의존하지 않는다. 규칙의 모든 테스트가 JVM에서 밀리초 단위로 돈다.
* `engine` 모듈은 `game`에만 의존한다. `UciTransport`를 가짜로 바꾸면 네이티브 없이 프로토콜을 테스트한다
  (`FairyStockfishEngineTest`), 실제 .so를 쓰면 계측 테스트가 된다(`FairyStockfishInstrumentedTest`).
* `app`은 두 모듈을 조합한다. 엔진 인스턴스는 프로세스당 1개(`EngineProvider`), 분석/대국이 같은 엔진을
  Mutex로 번갈아 쓴다. 두 화면이 동시에 살아 있지 않으므로(NavHost 단일 목적지) 충돌은 없고, 만약 겹쳐도
  나중 요청이 `stop` → `bestmove` 대기 후 시작한다.

## 분석 스트림

```
go movetime T (MultiPV=N)
  info depth d multipv 1 … pv …        ─┐
  info depth d multipv 2 … pv …         │ 라운드: multipv == N 이면 스냅샷 emit (+100 ms 스로틀)
  …                                     │
  info depth d multipv N … pv …        ─┘
  bestmove …                            → final 스냅샷
```

스냅샷은 `moves`를 점수 내림차순(mate는 ±(100000−n))으로 정렬하고, 아직 점수 없는 수는 뒤에 붙여
UI가 "n/N수 분석됨"을 표시할 수 있다. `depth`는 모든 수가 점수를 가진 뒤의 최소 깊이(= 완료 깊이),
`maxDepth`는 최대 깊이다.

## 왜 엔진에 이력을 보내는가

`Uci.positionCommand()`는 `position fen <시작> moves …`로 수순 전체를 보낸다. FSF는 이 이력으로 동형 반복을
판정하므로 앱의 `RepetitionRule`과 결과가 일관된다(Python 서버 캐시가 FEN만 보고 틀렸던 것과 같은 실수를
피한다). 시작 FEN이 있는 국면(FEN 불러오기)은 그 시점부터의 이력만 있다.

## 대국 난이도

| 단계 | Skill Level | 시간 | 깊이 |
|---|---|---|---|
| 초보 | 2 | 200 ms | 4 |
| 중수 | 8 | 400 ms | 8 |
| 고수 | 15 | 800 ms | — |
| 최강 | 20 | 설정(기본 2 s) | — |

FSF의 Skill Level은 상위 후보 중에서 확률적으로 고르는 방식이라 "약하지만 자연스러운" 수가 나온다. 최강만
MultiPV=1 전용 경로의 이점을 온전히 쓴다(나머지도 MultiPV=1로 탐색하되 시간이 짧다).

## Compose 상태

* `GameSession`은 불변 데이터 클래스. 수를 두면 새 인스턴스(수순 배열 복사). 국면 재계산은 `lazy`.
* ViewModel `StateFlow` 하나로 화면 전체를 그린다. 보드는 `BoardOverlay`만 받는 순수 함수.
* 뒤로 가기/회전: `SavedStateHandle`. 프로세스 종료 후 복원도 같은 경로.

## 훈수(Coach)와 엔진 공유

한 프로세스에 UCI 루프는 하나이고 `FairyStockfishEngine`이 `searchMutex`로 탐색을 직렬화한다. 훈수는 그 위에서:

```
사용자 착수  →  CoachRequest(직전 세션, 수) 기억, 진행 중 훈수 취소
           →  AI bestMove (MultiPV=1, 난이도 예산)      ← 항상 먼저
           →  AI 착수 적용
           →  CoachController.evaluate(직전 국면, MultiPV=합법수, 훈수 예산)
           →  결과가 도착했을 때도 그 수가 현재 기보에 그대로 있을 때만 게시
```

`aiJob`이 살아 있으면 훈수를 시작하지 않고 AI 완료 콜백에서 시작하므로, 두 `go`가 한 UCI 스트림에서 섞이지 않는다.
undo/새 대국/다음 수는 `coach.cancel()`(stop → bestmove 대기)로 기존 분석을 끊는다.

## 판 방향

게임 상태는 canonical 좌표(row 0 = 한 진영 상단). `BoardOrientation`이 유일한 변환 지점이며 `JanggiBoard.Layout`은 그리기·터치 모두 이를 통해서만
좌표를 얻는다. 뒤집기는 90칸 인덱스에서 `89 − sq`(row→9−row, col→8−col)이고 그 자체가 역함수다.

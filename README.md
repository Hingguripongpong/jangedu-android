# 장기 교육 — Android 장기 AI 분석 및 학습 앱

**장기 교육**은 Android에서 완전히 오프라인으로 동작하는 장기 AI 분석·학습 앱입니다.

Fairy-Stockfish 장기 엔진을 앱 내부 native library로 포함하며, 사용자가 둘 수 있는 모든 합법수를 분석해 엔진 평가와 예상 승률을 보여줍니다.

현재 Android 앱의 주요 기능:

- 사람 vs AI 장기
- 모든 합법수 분석 및 후보 전체 표시
- 각 후보수의 엔진 평가 및 예상 승률 표시
- AI 훈수
  - 사용자가 둔 수와 최선수 비교
  - 승률 손실
  - 등급 및 설명
  - PV 표시
- 초/한 선택 및 랜덤 진영 선택
- 초/한 각각 독립적인 마·상 초기 배치 선택
- 분석 모드 초기 배치 선택
- 판 뒤집기
- 무르기 / 한수쉼
- 상태 저장 및 복원
- 완전 오프라인 동작

Android 앱은 Kotlin, Jetpack Compose, Android NDK/CMake/JNI와 Fairy-Stockfish를 사용합니다.

## 라이선스

이 애플리케이션의 소스 코드는 **GNU General Public License version 3 or later (GPL-3.0-or-later)** 조건으로 제공됩니다.

전체 소스 코드:

https://github.com/Hingguripongpong/jangedu-android

Fairy-Stockfish 역시 GPL-3.0-or-later에 따라 배포되며, 사용한 정확한 버전·commit·원본 source 정보는 `android/THIRD_PARTY_NOTICES.md`에서 확인할 수 있습니다.

전체 GPLv3 전문은 저장소 루트의 `LICENSE`에 포함되어 있습니다.

## Android 앱

Android 프로젝트는 `android/` 디렉터리에 있습니다.

주요 기술 스택:

- Kotlin
- Jetpack Compose
- Material 3
- Coroutines / StateFlow / ViewModel
- Android NDK / CMake / JNI
- Fairy-Stockfish
- 완전 오프라인 실행

출시용 application ID:

`com.jangedu.janggiai`

현재 출시 버전:

`1.0.0`

## Legacy / 개발 이력

이 저장소에는 Android 앱 개발 이전에 사용했던 Python 분석 엔진, FastAPI 서버, Web UI, CLI 코드도 함께 남아 있습니다.

아래 내용은 해당 초기 구현과 개발 이력을 설명합니다.

## 실행

```bash
pip install -r requirements.txt
python scripts/get_fairy_stockfish.py     # (선택) Fairy-Stockfish 다운로드 -> engines/
uvicorn server.app:app --reload          # http://127.0.0.1:8000  (Windows: python -m uvicorn ...)
python -m pytest                          # 99 tests (Fairy-Stockfish 없으면 4개 skip)
python cli.py analyze --time 3000         # 터미널 분석
python scripts/bench.py --ablation        # 벤치마크
```

## UI

- 10×9 장기판(SVG). 기물을 클릭하면 이동 가능한 칸에 **그 수의 예상 승률**이 표시된다. 색은 최선수 대비 손실(파랑 → 주황), 숫자는 절대 승률.
- 표에는 모든 합법수(또는 상위 3/5/10)가 승률 막대·평가·깊이·노드와 함께 정렬되어 있다. 행을 클릭하면 PV와 화살표, `두기`로 착수.
- 분석 ON/OFF, 시간(100ms · 500ms · 1s · 5s · 10s · 무제한), 표시 수(상위 N / 모든 수), 중지 버튼.
- 반복 심화 중간 결과가 WebSocket으로 실시간 갱신된다(약 100ms 간격). 한 수의 탐색이 길어지면 2초마다 heartbeat가 와서 "완료 깊이 → 탐색 중 (k/32)"와 노드 수가 갱신되고, 12초 이상 무응답이면 클라이언트가 스스로 재연결해 분석을 다시 요청한다.
- 무제한 분석 중에도 `AI 착수`·REST `/analyze`는 기다리지 않는다: 서버가 스트리밍 분석을 먼저 중단(final, aborted)시키고 요청을 처리하며, 클라이언트는 다음 위치에서 분석을 다시 시작한다.
- 새 게임(초/한 포진 4종 선택), 무르기(←), 한수쉼, AI 착수, 판 뒤집기, FEN 불러오기, 기보 클릭으로 되돌아가기.
- **왜 좋은 수인가 (상위 5)**: 분석이 끝나면 상위 5수마다 이유가 붙는다 — 잡는 기물과 점수, 되잡힘 여부, 장군, 만들어지는 위협(정적 교환 평가), 공격받던 기물 회피·보호, 면포·열린 열·전개 같은 위치 요소와 평가 항목 변화, 예상 진행과 그 기물 손익. 항목을 클릭하면 판에 화살표와 PV가 표시된다.
- **내 수 브리핑**: 내가 수를 두면 직전 위치의 분석으로 그 수를 평가한다 — 전체 몇 위인지, 최선수와 승률 차이(1%p 미만 "거의 같음", 4%p 미만 "더 나은 수 있음", 12%p 미만 "실수", 그 이상 "큰 실수"), 좋은 점과 위험, 상대의 예상 응수, 엔진이 권하는 수와 그 이유. 서버는 클라이언트가 보던 분석(또는 캐시)을 그대로 쓰므로 추가 탐색이 없다.
- **AI 착수**는 현재 위치의 분석이 끝나 있으면(또는 깊이 4 이상이면) 그 최선수를 **즉시** 둔다 — 분석이 이미 모든 수를 탐색했으므로 다시 탐색하지 않는다. 분석이 꺼져 있을 때만 별도 탐색(`/api/bestmove`).
- **AI 자동 응수**(한/초): 그 색의 차례에 분석이 끝나는 즉시 최선수를 둔다. 사람 대 엔진 대국에서 한 수의 대기 시간은 ‘시간’ 설정 한 번이다.
- 장군 / 외통 / 빅장 / 무승부 사유가 상단에 표시된다. 빅장이 걸리면 "궁을 옮기거나 사이를 막지 않으면 …"으로 다음 수의 결과를 미리 알려 준다.
- **엔진 선택**(분석 패널): 내장(Python) / Fairy-Stockfish. Fairy-Stockfish를 고르면 분석·AI 착수 모두 그 엔진이 담당한다.
- **빅장 규칙**(판 아래) — 기본은 **없음**(카카오장기 등 현대 온라인 장기처럼 두 궁이 마주봐도 계속 둔다).
  | 선택 | 뜻 | Fairy-Stockfish |
  |---|---|---|
  | 없음 (기본) | 빅장 규칙 없음. 연속 한수쉼만 무승부 | `janggicasual` |
  | 전통 | 빅장을 받은 쪽은 푸는 수(궁 이동·차단)만 둘 수 있고, 한수쉼으로 받아들이면 기물 점수(한 +1.5)로 승패 | `janggi` |
  | 풀지 않으면 무승부 | 아무 수나 둘 수 있으나 풀지 않으면 무승부 | 내장 엔진만 |
  | 풀지 않으면 점수 판정 | 풀지 않으면 기물 점수로 승패 | 내장 엔진만 |

  전통 규칙에서는 빅장을 받았을 때 둘 수 없는 기물이 흐리게 표시되고, 클릭하면 이유가 상단에 나온다. 동형 반복 = 3회 무승부(기본) · 제한 없음. 엔진의 탐색 값도 같은 규칙으로 계산된다.
- **승률 기준**(분석 패널): 둘 차례(기본) · 초 · 한. 엔진의 모든 수치는 둘 차례인 쪽 기준이므로 매 수마다 관점이 바뀐다; 한쪽으로 고정하면 숫자와 평가 부호가 그 쪽 기준으로 변환된다(최선수 색상 강조는 그대로).

## 구조

```
janggi-ai/
├─ engine/
│  ├─ uci_engine.py  Fairy-Stockfish UCI 어댑터 (동일한 분석 결과 스키마, MultiPV = 합법수 수, 스트리밍/중단)
│  ├─ geometry.py    좌표(sq = row*9+col, row 0 = 한 진영), 궁성 대각선, 마/상 길막 테이블, 역방향 공격표
│  ├─ hashing.py     Zobrist 키
│  ├─ board.py       Position: FEN, make/unmake(증분 해시), 공격 판정(포대·길막·궁성 대각), 빅장/반복 상태
│  ├─ movegen.py     pseudo/legal move 생성, gives_check, perft
│  ├─ rules.py       외통·빅장·연속 한수쉼·동형반복·수 제한 판정, 점수 집계, 기보 재생
│  ├─ evaluation.py  기물가 + 위치표 + 졸 구조 + 궁 안전 + 기동력 + 선수, 항목별 breakdown
│  ├─ search.py      Negamax, Alpha-Beta, 반복 심화, TT, MVV-LVA, killer/history, PVS, quiescence, aspiration, check extension
│  ├─ calibration.py 평가값 → 승률 (logistic K, ordered-logit W/D/L, MLE fit, JSON 저장)
│  ├─ analysis.py    모든 루트 수 개별 aspiration 탐색 → 수별 score/winrate/depth/nodes/pv, 스트리밍 콜백
│  └─ explain.py     수 설명·브리핑: 정적 교환 평가(SEE), 위협/무방비 기물, PV 기물 손익, 평가 항목 변화 → 한국어 문장
├─ neural/           Stage 3–5 계약: 입력 평면·policy 인덱스 인코딩(torch-free), 로드맵 README
├─ server/app.py     FastAPI REST + WebSocket
├─ ui/index.html     단일 파일 UI (의존성 없음)
├─ tests/            91개 테스트
├─ scripts/          bench.py, perft.py, get_fairy_stockfish.py
├─ engines/          Fairy-Stockfish 실행 파일 위치 (없으면 내장 엔진만)
├─ cli.py            show / moves / eval / analyze / bestmove / perft / hash / play / fit-calibration
└─ data/             calibration.json(있으면 서버가 로드), 벤치마크 결과
```

## 규칙 구현

- 기물: 궁·사(궁성 내 직선/대각 1칸), 차(직선, 궁성 대각), 포(정확히 하나의 포대를 넘어 이동/잡기, **포는 넘을 수도 잡을 수도 없음**, 궁성 대각 점프), 마(직선 1 + 대각 1, 첫 칸 막힘 확인), 상(직선 1 + 대각 2, 두 칸 막힘 확인), 졸/병(전진·좌우, 궁성 내 대각 전진).
- 궁성 대각선은 모서리↔중앙만 존재하며 `geometry.diag_step_ok`가 단일 진실원이다.
- 장군: 수를 둔 뒤 자기 궁이 공격받으면 불법. 외통 = 장군인데 합법수 없음.
- 한수쉼: 장군이 아니면 항상 가능(스테일메이트 없음). 양측 연속 한수쉼 → 무승부.
- 빅장: 두 궁이 같은 열에서 마주보고 둘 차례가 장군이 아니면 표시. `RuleConfig.bikjang`으로 처리 방식을 고른다.
  - `off` (UI 기본): 빅장 규칙 없음(현대 온라인 장기 방식). Fairy-Stockfish `janggicasual`과 같다.
  - `forced` (전통): 빅장을 받은 쪽의 합법수 = 빅장을 푸는 수 + 한수쉼. 한수쉼은 빅장을 받아들이는 것이며 기물 점수(한 +1.5)로 승패를 정한다. 양측 연속 한수쉼도 점수로 판정. **Fairy-Stockfish janggi 변형과 동일**하며, 이 규칙에서 우리 perft는 FSF와 정확히 일치한다: 시작 위치 깊이 3 = 33,000 · 깊이 4 = 1,065,277, 중반 위치 42,026, 무작위 24개 위치 깊이 3 전부 일치.
  - `draw`(라이브러리 기본값): 아무 수나 둘 수 있고, 풀지 않으면 무승부. `points`: 풀지 않으면 점수 판정.
  - 장군이 우선한다(장군을 받는 중이면 빅장 의무 없음). FSF는 이 부분이 다르다(장군 중에도 궁을 열에서 벗어나게 해야 함).
- 동형반복 3회 → 무승부(`RuleConfig.repetition = "draw"`, 기본; 탐색 내부는 2회로 처리해 반복 회피 유도) 또는 무시(`"off"`). 400수 제한.
- 점수: 차13 포7 마5 상3 사3 졸2, 한 +1.5. `material_points`로 노출되며 `points` 규칙의 판정 근거다.
- 포진: 마상상마(기본), 상마상마, 마상마상, 상마마상 — 각 진영 **자기 왼쪽 기준**.
- 표기: 칸 `RC`(R=(row+1)%10, C=col+1; 초궁 95, 한궁 25), 수 `95-73`, `pass`. FEN은 Fairy-Stockfish janggi 규약과 호환(대문자 = 초, `w` = 초 차례).
- 미구현(로드맵): 연장군 금지, 시간 초과·기권 등 대회 운영 규칙.

## API

| 엔드포인트 | 설명 |
|---|---|
| `GET /` | UI |
| `GET /api/new_game?cho=마상상마&han=상마상마` | 새 위치, 합법수, 평가 |
| `POST /api/state` `{start_fen?, moves[]}` 또는 `{fen}` | 위치·상태·합법수(장군/잡기 플래그)·평가 breakdown |
| 모든 요청의 선택 필드 `rules: {bikjang: draw\|points\|off, repetition: draw\|off}` | 규칙 선택(생략 시 기본 규칙). `new_game`은 쿼리 `bikjang=`, `repetition=` |
| `POST /api/analyze` = `POST /analyze` `{position|fen|start_fen+moves, time_limit_ms, max_depth}` | 모든 합법수 분석 |
| `POST /api/bestmove` | 단일 최선수 탐색(AI 착수) |
| `POST /api/briefing` `{start_fen?, moves[], engine?, rules?, prev_analysis?}` | `moves`의 마지막 수 평가: 순위·최선수와 차이·좋은 점·위험·예상 응수·대안. `prev_analysis`(클라이언트가 가진 직전 위치 분석)가 있으면 재탐색 없음 |
| 분석 결과의 `explanations` (final에만) | 상위 5수의 `head/good/risk/plan/facts` |
| `POST /api/eval`, `GET /api/perft?depth=3` | 디버그 |
| `WS /ws/analyze` | `{type:"analyze", id, ..., time_limit_ms}` → `update`* / `heartbeat`* → `final`; `{type:"stop"}`. 새 요청은 진행 중 분석을 대체. 엔진 예외 시에도 `final`(`error` 필드)이 반드시 온다 |
| 모든 요청의 선택 필드 `engine: "python"\|"fsf"` | 엔진 선택(기본 python). `GET /api/engines`가 가용 여부와 설치 안내를 준다. fsf는 빅장 `off`/`forced`만 지원하며 `draw`/`points`는 `forced`로 바꿔 응답 `rules`에 반영 |

분석 응답의 각 수:

```json
{"rank":1,"move":"02-83","notation":"馬 02→83","from":"02","to":"83","from_sq":82,"to_sq":65,
 "piece":"馬","capture":null,"gives_check":false,"score_cp":10,"score":0.1,"mate":null,
 "estimated_winrate":0.505,"wdl":[0.505,0.0,0.495],"depth":4,"nodes":3116,"time_ms":41.2,
 "pv":["02-83","41-42","79-78","12-33"],"pv_pretty":["馬 02→83","兵 41→42","卒 79→78","馬 12→33"]}
```

응답 상단의 `calibration` 객체가 변환 모델(`k`, `calibrated`, `label`)을 명시하고, `rules`가 적용 규칙을 돌려준다.
`decisive`는 그 수의 강제 결말(`"mate"`, `"points"`, `null`)이다. 상태 객체의 `status`에는 `bikjang_points`(빅장 점수 판정, `winner`·`points` 포함)가 추가됐다.

## 탐색 설계

모든 수에 정확한 점수를 주기 위해 루트 수마다 **개별 aspiration 탐색**을 수행한다(이전 깊이 점수 ±40, 실패 시 4배 확장).
TT는 루트 수 사이에 공유되어 뒤쪽 수가 앞쪽 수의 작업을 재사용한다. 깊이마다 best-first로 순회하며 수가 끝날 때마다 콜백으로
`score/depth/nodes/pv`를 내보내므로 UI는 심화 중에도 갱신된다. 시간 초과로 깊이 N이 중단되면 완료된 수는 깊이 N, 나머지는 N−1 결과를 유지한다.

`SearchConfig`의 모든 최적화(`alpha_beta, tt, pvs, killers, history, qsearch, check_extension, aspiration, null_move, lmr`)는 개별 토글이며
`tests/test_search.py`가 토글 ON/OFF의 루트 점수 동일성(휴리스틱인 qsearch/null_move/lmr 제외)을 검증한다.
null-move는 장기에서 한수쉼이 실제 합법수이므로 "실제 수를 감축 깊이로 탐색"하는 것이고, LMR은 4번째 이후의 조용한 수를 1 감축한다.
둘을 켜면 같은 시간에 깊이가 약 2 늘어난다(깊이 4 고정 노드 11,096 → 5,969). 250ms 6판 자체 대국은 3승 2패 1무로 표본이 작아 기력 차는 미확정.

## 벤치마크 (1 vCPU, Python 3.12, 3초/위치, v0.2.0 null-move+LMR 포함)

| 위치 | 단일 최선수: depth / nodes / nps / TT hit | 전수 분석: depth / nps | Fairy-Stockfish 1 thread, 2초 |
|---|---|---|---|
| 시작 위치 | 7 / 144k / 52k / 43% | 5 / 55k | depth 9–10, 250k nps |
| 중반 | 6 / 85k / 56k / 40% | 4 / 53k | |
| 열린 열 | 7 / 94k / 58k / 44% | 5 / 60k | |
| 전술 | 9 / 154k / 76k / 42% | 8 / 74k | |

perft(3) = 30,353 nodes(한수쉼 제외), 86 ms. 절제 실험(중반, 고정 깊이 4): alpha-beta OFF 2,102,548 nodes → ON 5,969; TT 약 16%, PVS 27% 절감,
killer/history/aspiration 1% 미만; qsearch OFF는 점수가 달라진다(수평선 효과). Fairy-Stockfish의 nps는 이 느린 컨테이너 기준이며 일반 PC에서는 스레드당 수 배 빠르다.

## 테스트 (115개, Fairy-Stockfish 없으면 108 + 7 skip)

- 규칙: 기물 이동·길막·포대·궁성 대각선, 장군/외통/핀, 빅장·한수쉼·동형반복, 포진 4종, FEN 왕복, make/unmake 완전 복원과 증분 해시 검증(무작위 플레이아웃), perft 회귀와 deepcopy 기반 독립 구현 대조.
- 규칙 옵션: 빅장 draw/points/forced/off 각각의 종료 판정, forced 합법수 집합(풀기 + 한수쉼)과 장군 우선, 한 +1.5 동점 처리, 반복 무시, **엔진 평가가 규칙을 따르는지**, Fairy-Stockfish perft 기준값 일치.
- 설명/브리핑: 한국어 조사 처리, SEE(자유 기물·방어된 기물·졸로 잡기), 무방비 기물 탐지, 잡기·위협·회피 사실, 종단간 설명·브리핑 판정(최선수/실수), PV 기물 손익, `/api/briefing`(캐시·클라이언트 분석 재사용·오류).
- Fairy-Stockfish 백엔드: UCI 좌표/한수쉼 표기 왕복(항상), 엔진 기동·32수 전부 분석·정렬·PV, 무제한+중단, 기보 이력 전달, 외통/빅장 점수 판정 일치, 서버 엔진 선택(REST/WS)과 규칙 강제(바이너리 있을 때).
- 탐색: 하늘차 잡기, 큰 기물 우선, 걸린 기물 회피, mate-in-1/2, alpha-beta = minimax 점수, TT/PVS 무영향, 시간/노드/중단 제한, PV 합법성.
- 평가: `evaluate == breakdown.total`, 색 반전 대칭, 기물가 지배, 설정 토글.
- 캘리브레이션: 합성 데이터에서 K 복원(±10%), WDL 합 1, JSON 왕복.
- 분석: 32수 전부 점수, 정렬·단조성, 단일 탐색과 점수 일치, 스트리밍 깊이 단조, mate 100%, 종료 위치.
- 서버: REST 전부 + WebSocket 스트리밍/중지/대체/오류, 규칙 옵션 전달.
- 신경망 인코딩: 평면 형태, 한 시점 반전, policy 인덱스 왕복, 좌우 대칭 일관성.

## 알려진 한계

- 순수 Python: 3초에 깊이 4–7. 규칙엔진의 함수 경계를 유지한 채 C++/Rust로 이전하도록 설계했다(`neural/README.md` §5).
- 연장군 금지 규칙 미구현. 빅장의 공식 처리(무승부/점수 판정/걸 수 있는 쪽 제한)는 규정마다 다르므로 옵션으로 두었다.
- Fairy-Stockfish는 점수 판정으로 끝나는 수를 mate 점수로 보고하므로(한수쉼 → −M1 등) 표에 "−M1"처럼 표시된다. 의미는 "점수로 패".
- Fairy-Stockfish 결과의 승률도 K=300 로지스틱 사전값이며(FSF 자체 WDL 모델은 체스용이라 `engine_wdl`로만 노출), 보정 절차는 동일하다.
- TT는 100만 항목(약 210 MB)에서 오래된 1/4을 비우는 방식으로 상한을 지킨다(`SearchConfig.tt_max_entries`). 무제한 분석을 장시간 켜 두어도 메모리가 늘어나지 않는다.
- K=500(200cp ≈ 60%)은 미보정 prior다. 캘리브레이션 절차는 `neural/README.md` §3.

## 변경 이력

- **v0.4.0** — 안드로이드 이식 준비 과정에서 찾은 세 가지 상태 버그 수정. (1) **TT 키**: 트랜스포지션 테이블이 보드 해시만 키로 써서 한수쉼 대기·빅장·남은 수 제한이 다른 국면을 같은 항목으로 취급했다(재현: passes=0에서 −1132, 같은 보드 passes=1은 0이어야 하는데 TT가 −1132를 돌려줌). `Searcher.tt_key()`가 한수쉼 대기·빅장(규칙이 켜진 경우)·남은 수 한도를 키에 섞는다(`engine/hashing.py`의 새 Zobrist 상수). (2) **분석 캐시**: 서버 캐시가 FEN만 키로 써서 반복 이력·한수쉼 상태가 다른 국면의 결과를 재사용했다. `Position.state_key()`(보드 해시·수순 상태·잡은 이후 반복 컨텍스트·규칙·엔진 ID)로 교체. (3) **Fairy-Stockfish 대국 모드**: `bestmove()`가 `analyze()`를 호출해 MultiPV=합법수 개수로 탐색했다. 이제 대국 모드는 MultiPV=1로 별도 `go`를 보내고(스킬/시간/깊이 옵션), 분석 모드만 MultiPV=N을 쓴다. 명령 수준 테스트(`tests/test_uci_engine.py`)로 두 모드를 구분 검증. 안드로이드 앱(`android/`, Kotlin·Compose·NDK, 온디바이스 Fairy-Stockfish)은 `android/README.md` 참고.
- **v0.3.0** — 수 설명 기능: 상위 5수의 이유(`engine/explain.py`, 규칙 기반·엔진 사실 기반, LLM 없음)와 직전 내 수 브리핑(`/api/briefing`, UI 섹션, `cli.py explain`). 분석 결과 캐시로 브리핑에 추가 탐색 없음.
- **v0.2.2** — 빅장 기본값을 "없음"(카카오장기 방식)으로 변경. Fairy-Stockfish에 빅장 규칙별 변형 매핑(없음 → janggicasual, 전통 → janggi) 추가로 두 규칙 모두에서 사용 가능. 전통 규칙에서 둘 수 없는 기물을 흐리게 표시하고 클릭 시 이유 안내("빅장 중이라 푸는 수만 가능") — 후반에 차가 안 움직이는 것처럼 보이던 혼란의 해결.
- **v0.2.1** — 대국 속도: AI 착수가 끝난 분석의 최선수를 즉시 두고(중복 탐색 제거), AI 자동 응수 옵션 추가. Fairy-Stockfish를 낮은 우선순위·코어 절반(최대 8, `JANGGI_FSF_THREADS`)·Hash 128MB로 실행해 브라우저가 느려지지 않게 함; 페이지 로드 시 엔진 프로세스를 미리 띄우지 않음. 반복 검사가 마지막 잡은 수 이후만 보도록 바꿔 긴 대국 후반의 내장 엔진 속도 저하 제거.
- **v0.2.0** — Fairy-Stockfish를 두 번째 엔진으로 통합(`engine/uci_engine.py`, UI 엔진 선택, `scripts/get_fairy_stockfish.py`). 빅장 `forced` 규칙 추가(FSF janggi 변형과 동일, UI 기본) — 그 규칙에서 perft가 FSF와 깊이 4까지 완전 일치함을 확인해 규칙엔진의 정확성을 외부 기준으로 검증. 내장 탐색에 null-move 가지치기·LMR 추가(같은 시간에 깊이 +2). FSF의 janggi cp 척도(차 ≈ 800)에 맞춰 승률 변환 K=300 적용.
- **v0.1.2** — 무제한 분석 중 `AI 착수`가 엔진 락 뒤에서 무한 대기하던 문제 수정(REST 요청이 스트리밍 분석을 선점). 2초 heartbeat와 12초 무응답 자동 재연결, 엔진 예외 시 final 보장. 단일 탐색 중 TT가 무한히 자라던 문제 수정(상한 + 오래된 항목 제거). 재현: 무제한 분석 + AI 착수 → 8초 이상 무응답 → 수정 후 0.7초.
- **v0.1.1** — 규칙 옵션(빅장 무승부/점수 판정/없음, 동형 반복 무승부/없음)을 규칙엔진·탐색·분석·API·UI·CLI 전 계층에 추가. 승률 표시 기준 고정(초/한) 옵션. 빅장 경고 문구 구체화, 반복 횟수 표시. 자가 대국에서 점수가 크게 뒤진 쪽이 빅장으로 무승부를 만드는 사례(22 : 36.5)가 발견된 데 따른 조치.
- **v0.1** — MVP.

## 다음 단계

1. 자가 대국 데이터 생성 → `python cli.py fit-calibration` → 보정된 승률 표시.
2. Null-move pruning, LMR, 이력 기반 다중 킬러 등 추가 최적화(토글로 추가, 절제표로 검증).
3. Stage 3–5: PyTorch policy/value, self-play, PUCT MCTS — `neural/encoding.py` 계약 위에 구현.

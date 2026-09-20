# Android migration plan (janggi-ai v0.3.0 → Janggi AI Android V1)

## 1. What the existing project is (analysis result)

| Area | File | Status |
|---|---|---|
| Board representation | `engine/board.py` | `board[90]` ints, `piece = type \| colour<<3`, `move = from*90+to`, `PASS_MOVE = 8100`; incremental Zobrist; undo stack keeps `(move, captured, hash, passes, bikjang, irrev)` |
| Geometry tables | `engine/geometry.py` | rays, palace steps, horse/elephant leg tables, pawn tables, reverse attack tables |
| Legal moves | `engine/movegen.py` | pseudo → make/unmake filter; 한수쉼 legal when not in check; forced-빅장 filter |
| Rules / endings | `engine/rules.py` | `RuleConfig(bikjang = off/forced/draw/points, repetition = draw/off, max_plies=400)`; points 차13 포7 마5 상3 사3 졸2, 한 +1.5 |
| FEN | `board.py` | Fairy-Stockfish compatible (upper = 초 = `w`) |
| Notation | `engine/notation.py` | `RC` squares (초궁 95, 한궁 25), `95-73`, `pass` |
| Search | `engine/search.py` | negamax + TT keyed **only by `pos.hash`** (bug), null-move = real pass, LMR |
| All-moves analysis | `engine/analysis.py` | per-root-move aspiration search, streaming callback |
| FSF adapter | `engine/uci_engine.py` | `bestmove()` **calls `analyze()` → MultiPV = #legal moves** (bug) |
| Win probability | `engine/calibration.py` | logistic `K` (500 python / 300 FSF), MLE fit, JSON |
| Server | `server/app.py` | FastAPI REST + WS; analysis cache keyed by **FEN only** (bug) |
| Tests | `tests/` | 102 passed / 6 skipped without FSF, 108 with FSF |
| Perft references | tests | no-pass: 31 / 961 / 30,353; FSF-janggi w/ pass: 33,000 (d3), 1,065,277 (d4), midgame 42,026 |

## 2. Decisions

1. **Rules engine on Android = faithful Kotlin port of the Python rules engine** (`android/game`).
   The Python code cannot run on Android; a WebView/Python-server is excluded by the brief. The port keeps the
   same encodings (squares, pieces, moves, PASS_MOVE, FEN, RC notation, UCI mapping) and is validated against
   the same test vectors *and* the same perft numbers, which in turn are validated against Fairy-Stockfish
   (`go perft`). Legal moves shown in the UI therefore agree with the engine by construction, and an
   instrumented test compares them against the engine's `go perft 1` divide at runtime.
2. **Fairy-Stockfish runs in-process through JNI** (`android/engine/src/main/cpp`). The unmodified engine
   sources (pinned `fairy_sf_14`, commit `f3e6969d…`) are compiled into `libjanggi_engine.so`; `main.cpp` is
   left out and replaced by an engine host that runs `UCI::loop` on a dedicated thread with `std::cin`/
   `std::cout` redirected to in-memory stream buffers. The text protocol is kept because it is the
   engine's stable, tested interface; no `fork/exec` of a binary (Android exec restrictions, process
   lifetime problems) and zero patches to the engine.
3. **Two engine modes at command level**: `analyze()` → `setoption name MultiPV value <legal move count>`;
   `bestMove()` → `setoption name MultiPV value 1`. Unit tests assert the exact commands.
4. **Win rate**: `WinProbabilityModel` interface + logistic default (K = 300 for FSF centipawns, taken from
   `engine/calibration.py`); a calibration JSON with the same schema as `Calibrator.to_json()` can be
   dropped into `app/src/main/assets/calibration.json`.
5. **No network, no accounts, no analytics, no ads**. `INTERNET` permission is not declared.
6. **State-aware keys everywhere**: search TT key = board hash ⊕ pass state ⊕ bikjang state ⊕ move-limit
   proximity; analysis caches (Python server and Android in-memory) are keyed by position *state*
   (FEN + passes + bikjang + repetition context + rules + engine id).

## 3. Work packages

| # | Package | Verification |
|---|---|---|
| A | Python fixes: TT key, `bestmove()` MultiPV=1, state-aware server cache | pytest (108 + new tests) |
| B | Pin Fairy-Stockfish, build on Linux, verify janggi/perft/MultiPV | `go perft`, pytest FSF tests |
| C | JNI engine host (C++) | desktop build against JDK JNI headers, Java harness driving the real engine |
| D | `android/game` Kotlin rules port | kotlinc + JUnit on JVM: perft 30,353 / 33,000 / 42,026 / 1,065,277, rule tests |
| E | `android/engine` Kotlin engine API + FSF implementation | JVM tests with fake transport (MultiPV commands, stale ids, stop); JVM run against the desktop `.so` |
| F | `android/app` Compose UI (Home/Game/Analysis/Records/Settings/About) | code review; Gradle build requires Android SDK (see README) |
| G | Release plumbing: signing template, R8 rules, AAB, Play Store guide, GPL notices | files present |


## 진행 상태 (2026-09-18)

계획의 코드 부분은 `android/`에 구현됨. 검증 현황과 이 환경에서 실행하지 못한 항목(Gradle/NDK/APK/AAB/에뮬레이터)은 `android/README.md` §2 참고. 출시 절차는 `docs/PLAY_STORE_RELEASE.md`.

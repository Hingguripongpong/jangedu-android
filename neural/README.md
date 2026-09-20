# neural/ — Stage 3–5 계획 (Policy/Value 네트워크, Self-play, PUCT MCTS)

이 디렉토리는 v0.1 MVP에서는 **인터페이스만** 고정한다. `encoding.py`는 torch 없이 동작하며
테스트(`tests/test_neural_encoding.py`)로 계약을 보호한다. 학습 코드는 이 계약 위에 올린다.

## 1. 입력/출력 계약 (`encoding.py`, 확정)

| 항목 | 값 |
|---|---|
| 입력 텐서 | `NUM_PLANES(19) × 10 × 9` float — `encode_position(pos)`가 평탄화 리스트로 반환 |
| 시점 | 항상 **둘 차례 기준**. 한이 둘 차례면 판을 상하 반전(`MIRROR_SQ`)해 자기 진영이 아래로 오게 함 (`Position.mirrored`, 평가 PST와 동일 규약) |
| 평면 0–6 / 7–13 | 내 기물 7종 / 상대 기물 7종 (궁 사 차 포 마 상 졸) one-hot |
| 평면 14–18 | 장군 여부, 빅장 여부, 연속 한수쉼(/2), 동형반복 횟수(/2), 둘 차례가 초인지 |
| Policy 크기 | `POLICY_SIZE = 90×90 + 1 = 8101` (from×to + 한수쉼). `legal_policy_mask(pos)`로 불법수 마스킹 |
| Value | 스칼라 `tanh` — +1 둘 차례 승, 0 무승부, −1 패. `evaluate()`와 같은 부호 규약 |
| 승률 변환 | `(v + 1) / 2`. 학습된 value가 있으면 `Analyzer`의 `Calibrator.win_probability`를 이것으로 교체 → UI 라벨이 “Win Rate”로 바뀜 |

## 2. Stage 3 — 네트워크 (PyTorch)

- `neural/model.py`: ResNet 스타일 (예: 6 블록 × 64 채널로 시작, 이후 확대). 입력 19 평면, policy head 8101 logits, value head tanh.
- `neural/dataset.py`: self-play 기록(JSONL 또는 npz) → `(planes, policy_target, value_target)`.
- `neural/train.py`: 손실 = CE(policy, MCTS 방문분포) + MSE(value, 결과) + L2. 체크포인트를 `data/models/`에 저장.
- 초기 데이터가 없을 때는 알파베타 분석 결과(`Analyzer.analyze`)의 root 점수를 soft target으로 쓰는 **부트스트랩**을 허용한다. 이는 CPU만 있는 환경에서도 v0.1 엔진으로 첫 데이터를 만들 수 있게 한다.

## 3. Stage 4 — Self-play & 캘리브레이션

- `neural/selfplay.py`: 엔진(초기에는 알파베타, 이후 MCTS) 대 엔진. 매 수의 `(fen, 둘 차례, score_cp 또는 MCTS 값, 방문분포)`를 기록하고 게임 종료 시 결과를 역전파해 라벨링.
- 규칙 종료 조건은 `engine.rules.game_status`가 단일 진실원. 빅장 무승부, 연속 한수쉼, 동형반복, 400수 제한 포함.
- **캘리브레이션**: 기록된 `(score_cp, result)` 쌍으로 `python cli.py fit-calibration data/results.jsonl` → `data/calibration.json` 생성. 서버는 기동 시 이 파일을 읽어 `Calibrator`를 교체하고, 응답의 `calibration.calibrated`가 `true`, 라벨이 “Win Rate”로 바뀐다. `draw_margin`을 함께 학습하면 W/D/L 3분류(ordered-logit)로 확장된다. 기본 K=500은 **미보정 prior**임을 UI가 항상 명시한다.

## 4. Stage 5 — PUCT MCTS

- `neural/mcts.py`: 노드 = (Position 해시, N, W, Q, P[child]). 선택은 `Q + c_puct · P · sqrt(N_parent)/(1+N)`; 확장 시 네트워크 평가; 종료 위치는 `game_status`로 값 확정 (승 +1 / 무 0).
- 루트에서 모든 합법수의 `N`, `Q`를 반환 → `Analyzer`와 같은 결과 스키마(`moves[{move, winrate:=(Q+1)/2, visits, pv}]`)로 감싸 UI에 그대로 표시. 즉 UI/서버는 그대로 두고 백엔드만 교체한다.
- 알파베타와 MCTS를 동시에 돌려 비교하는 “dual” 모드를 `/api/analyze?engine=mcts|ab`로 노출할 계획.

## 5. 성능 경계

순수 Python 탐색은 약 6–9만 nodes/s다. Stage 3 이후 병목은 규칙엔진의 수 생성/합법성 검사이므로,
`engine/board.py`·`engine/movegen.py`의 함수 시그니처(정수 인코딩 수, `make_move/unmake_move`)를 유지한 채
C++/Rust 확장으로 교체하는 것을 전제로 설계했다. 테스트 스위트(perft, make/unmake 복원, 규칙 테스트)가 그 교체의 회귀 기준이다.

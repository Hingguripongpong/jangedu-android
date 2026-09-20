"""Evaluation tests: breakdown consistency, colour symmetry, material sanity, config knobs."""
import random

from engine.board import CHARIOT, CHO, HAN, PASS_MOVE, Position, make_piece
from engine.evaluation import DEFAULT_CONFIG, EvalConfig, evaluate, evaluate_breakdown, format_breakdown
from engine.geometry import sq
from engine.movegen import generate_legal_moves
from engine.rules import material_points


def random_positions(n=25, plies=30, seed=11):
    rng = random.Random(seed)
    out = []
    for _ in range(n):
        pos = Position.initial()
        for _ in range(plies):
            legal = [m for m in generate_legal_moves(pos) if m != PASS_MOVE]
            if not legal:
                break
            pos.make_move(rng.choice(legal))
        out.append(pos)
    return out


def test_fast_eval_equals_breakdown_total():
    for pos in [Position.initial()] + random_positions():
        bd = evaluate_breakdown(pos)
        assert evaluate(pos) == bd["total"]
        assert bd["total"] == sum(v for k, v in bd.items() if k != "total")


def test_start_position_is_symmetric_except_tempo():
    pos = Position.initial()
    bd = evaluate_breakdown(pos)
    assert bd["material"] == 0 and bd["pst"] == 0 and bd["pawn_structure"] == 0
    assert bd["king_safety"] == 0 and bd["mobility"] == 0
    assert bd["tempo"] == DEFAULT_CONFIG.tempo
    assert evaluate(pos) == DEFAULT_CONFIG.tempo


def test_colour_flip_symmetry():
    """Mirroring the board (Cho <-> Han) must not change the side-to-move evaluation."""
    for pos in random_positions(n=30, plies=24, seed=5):
        assert evaluate(pos) == evaluate(pos.mirrored())


def test_material_dominates():
    pos = Position.initial()
    base = evaluate(pos)
    # remove a Han chariot: Cho (to move) should be ~13 points better
    pos.board[sq(0, 0)] = 0
    pos._finish_setup()
    gain = evaluate(pos) - base
    assert 1200 <= gain <= 1500
    mp = material_points(pos)
    assert mp["cho"] - mp["han"] == 13 - 1.5  # Han's 1.5 bonus is exposed but never applied in eval


def test_eval_config_is_tunable():
    cfg = EvalConfig()
    cfg.material = dict(cfg.material)
    cfg.material[CHARIOT] = 2000
    pos = Position.initial()
    pos.board[sq(0, 0)] = 0
    pos._finish_setup()
    assert evaluate(pos, cfg) - evaluate(pos) >= 650


def test_mobility_toggle_only_changes_mobility_term():
    pos = random_positions(n=1, plies=20, seed=2)[0]
    on = evaluate_breakdown(pos, EvalConfig(mobility=True))
    off = evaluate_breakdown(pos, EvalConfig(mobility=False))
    assert off["mobility"] == 0
    for k in ("material", "pst", "pawn_structure", "king_safety", "tempo"):
        assert on[k] == off[k]


def test_format_breakdown_lists_all_terms():
    text = format_breakdown(evaluate_breakdown(Position.initial()))
    for name in ("Material", "Mobility", "King safety", "Pawn structure", "Total"):
        assert name in text

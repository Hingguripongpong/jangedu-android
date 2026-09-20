"""Selectable rule endings (RuleConfig): 빅장 draw / points / off, repetition draw / off."""
import pytest

from engine.analysis import Analyzer
from engine.board import CHARIOT, CHO, HAN, KING, PAWN, Position, encode_move, make_piece
from engine.geometry import sq
from engine.rules import (
    BIKJANG_POINTS, DRAW_BIKJANG, DRAW_REPETITION, ONGOING, RuleConfig, game_status, material_points, replay,
)
from engine.search import POINTS_WIN, SearchConfig, Searcher, is_points_score


def build(pieces, side=CHO):
    pos = Position()
    for (r, c), p in pieces.items():
        pos.board[sq(r, c)] = p
    pos.side = side
    pos._finish_setup()
    return pos


def cho(t):
    return make_piece(CHO, t)


def han(t):
    return make_piece(HAN, t)


def bikjang_position():
    """Cho (to move) has just been handed a bikjang; Cho is a chariot up."""
    pos = build({(8, 4): cho(KING), (1, 4): han(KING), (9, 0): cho(CHARIOT), (3, 8): han(PAWN), (6, 0): cho(PAWN)})
    assert pos.bikjang
    return pos


def test_rule_config_validation():
    with pytest.raises(ValueError):
        RuleConfig(bikjang="maybe")
    with pytest.raises(ValueError):
        RuleConfig(repetition="sometimes")
    assert RuleConfig.from_dict({"bikjang": "points"}).to_dict()["bikjang"] == "points"
    assert RuleConfig.from_dict(None) == RuleConfig()


def test_unresolved_bikjang_under_each_rule():
    ignore = encode_move(sq(6, 0), sq(5, 0))  # pawn move, kings keep facing
    for mode, expected in (("draw", DRAW_BIKJANG), ("points", BIKJANG_POINTS), ("off", ONGOING)):
        pos = bikjang_position()
        pos.make_move(ignore)
        st = game_status(pos, rules=RuleConfig(bikjang=mode))
        assert st.status == expected, mode
        if mode == "points":
            assert st.winner == CHO  # 13+2 vs 1.5+2
            assert st.points["cho"] > st.points["han"]
        else:
            assert st.winner is None
    # resolving it keeps the game going under every rule
    for mode in ("draw", "points", "off"):
        pos = bikjang_position()
        pos.make_move(encode_move(sq(8, 4), sq(8, 3)))
        assert game_status(pos, rules=RuleConfig(bikjang=mode)).status == ONGOING


def test_points_rule_han_bonus_breaks_ties():
    # equal material: Han's +1.5 decides
    pos = build({(8, 4): cho(KING), (1, 4): han(KING), (6, 0): cho(PAWN), (3, 8): han(PAWN)})
    pos.make_move(encode_move(sq(6, 0), sq(5, 0)))
    st = game_status(pos, rules=RuleConfig(bikjang="points"))
    assert st.status == BIKJANG_POINTS and st.winner == HAN
    assert material_points(pos) == {"cho": 2.0, "han": 3.5}


def test_engine_does_not_take_bikjang_draw_when_ahead_under_points_rule():
    """Under the classic rule a chariot-up side that ignores a bikjang only draws; under the
    points rule the same move *wins*, so the engine's evaluation of ignoring it flips."""
    pos = bikjang_position()
    ignore = encode_move(sq(6, 0), sq(5, 0))
    draw_cfg = SearchConfig(rules=RuleConfig(bikjang="draw"))
    pts_cfg = SearchConfig(rules=RuleConfig(bikjang="points"))
    for cfg, expect_points in ((draw_cfg, False), (pts_cfg, True)):
        s = Searcher(cfg)
        s.new_search(None, None)
        pos.make_move(ignore)
        score = -s.negamax(pos, 2, -10**6, 10**6, 1)
        pos.unmake_move()
        if expect_points:
            assert score >= POINTS_WIN - 1000 and is_points_score(score)
        else:
            assert score == 0


def test_engine_behind_prefers_bikjang_draw_only_under_draw_rule():
    """Han (behind) can pass and leave the kings facing: draw under 'draw', loss under 'points'."""
    pos = build({(8, 4): cho(KING), (1, 4): han(KING), (9, 0): cho(CHARIOT), (3, 8): han(PAWN), (6, 0): cho(PAWN)},
                side=HAN)
    assert pos.bikjang
    res_draw = Searcher(SearchConfig(rules=RuleConfig(bikjang="draw"))).search(pos, max_depth=3)
    res_pts = Searcher(SearchConfig(rules=RuleConfig(bikjang="points"))).search(pos, max_depth=3)
    assert res_draw.score == 0                     # Han happily takes the draw
    assert res_pts.score < -POINTS_WIN + 1000 or res_pts.score < -800   # no escape: loses on points or material


def test_repetition_off_keeps_game_going():
    cycle = ["02-83", "18-37", "83-02", "37-18"] * 2
    pos = replay(None, cycle)
    assert pos.repetition_count() == 3
    assert game_status(pos).status == DRAW_REPETITION
    st = game_status(pos, rules=RuleConfig(repetition="off"))
    assert st.status == ONGOING and st.repetition_count == 3


def test_analysis_reports_points_result():
    pos = bikjang_position()
    res = Analyzer(SearchConfig(rules=RuleConfig(bikjang="points"))).analyze(pos, time_limit_ms=None, max_depth=2)
    assert res["rules"]["bikjang"] == "points"
    by_move = {m["move"]: m for m in res["moves"]}
    assert by_move["71-61"]["decisive"] == "points" and by_move["71-61"]["estimated_winrate"] == 1.0
    assert by_move["71-61"]["mate"] is None


# ------------------------------------------------------------------ forced 빅장 (Fairy-Stockfish rule)
from engine.movegen import generate_legal_moves, perft
from engine.notation import move_to_str


def test_forced_bikjang_restricts_moves_to_resolving_ones_plus_pass():
    rules = RuleConfig(bikjang="forced")
    pos = replay(None, ["95-94", "25-24"], rules)   # 楚 K e2->d2, 漢 K e9->d9: kings face on file d
    assert pos.bikjang and pos.forced_bikjang
    legal = {move_to_str(m) for m in generate_legal_moves(pos)}
    assert legal == {"pass", "94-95", "75-74", "73-74"}   # FSF: d2d2 d2e2 e4d4 c4d4
    with pytest.raises(ValueError):
        replay(None, ["95-94", "25-24", "71-61"], rules)  # ignoring the bikjang is illegal here
    # accepting via pass ends the game, decided on material points (equal material: Han's +1.5 wins)
    pos.make_move(PASS)
    st = game_status(pos, rules=rules)
    assert st.status == BIKJANG_POINTS and st.winner == HAN
    # two consecutive passes are decided the same way under this rule
    pp = replay(None, ["pass", "pass"], rules)
    st2 = game_status(pp, rules=rules)
    assert st2.status == "passes_points" and st2.winner == HAN
    assert game_status(replay(None, ["pass", "pass"])).status == "draw_passes"
    # the classic rule allows the same move (and then draws)
    classic = replay(None, ["95-94", "25-24", "71-61"])
    assert game_status(classic).status == DRAW_BIKJANG


def test_forced_bikjang_check_takes_precedence():
    # Cho king e2 in check from a Han chariot on a2 while the kings face on file e:
    # the check must be answered; leaving the file is only required when not in check.
    rules = RuleConfig(bikjang="forced")
    pos = rules.apply(Position.from_fen("4k4/9/9/9/9/9/9/9/r3K4/9 w - - 0 1"))
    assert pos.in_check() and not pos.bikjang
    legal = {move_to_str(m) for m in generate_legal_moves(pos)}
    assert "pass" not in legal
    assert legal >= {"95-84", "95-86", "95-04", "95-06"}


def test_perft_matches_fairy_stockfish_reference_counts():
    """Fairy-Stockfish 14 `go perft` (variant janggi) from the same FENs, pass moves included."""
    cases = [
        ("rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w - - 0 1", 3, 33000),
        ("r1ba1ab1r/4k1n2/1c2n2c1/p3p1p1p/2p6/6P2/P1P1P3P/1C2NC3/4K4/RNBA1AB1R w - - 0 1", 3, 42026),
    ]
    for fen, depth, expected in cases:
        pos = RuleConfig(bikjang="forced").apply(Position.from_fen(fen))
        assert perft(pos, depth, include_pass=True, stop_at_game_end=True) == expected


PASS = 8100

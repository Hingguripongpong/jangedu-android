"""All-legal-moves analysis tests."""
import threading

from engine.analysis import Analyzer, format_analysis
from engine.board import CHARIOT, CHO, HAN, KING, PAWN, PASS_MOVE, Position, encode_move, make_piece
from engine.geometry import sq
from engine.movegen import generate_legal_moves
from engine.notation import move_to_str, str_to_move
from engine.rules import CHECKMATE, replay
from engine.search import SearchConfig


def build(pieces, side=CHO):
    pos = Position()
    for (r, c), p in pieces.items():
        pos.board[sq(r, c)] = p
    pos.side = side
    pos._finish_setup()
    return pos


def test_every_legal_move_is_analysed_with_a_real_score():
    pos = Position.initial()
    legal = generate_legal_moves(pos)
    res = Analyzer().analyze(pos, time_limit_ms=None, max_depth=2)
    assert res["final"] is True and res["aborted"] is False
    assert len(res["moves"]) == len(legal) == 32
    assert {m["move"] for m in res["moves"]} == {move_to_str(m) for m in legal}
    assert "pass" in {m["move"] for m in res["moves"]}
    for i, md in enumerate(res["moves"]):
        assert md["rank"] == i + 1
        assert md["depth"] == 2
        assert md["score_cp"] is not None
        assert 0.0 <= md["estimated_winrate"] <= 1.0
        assert md["nodes"] > 0
        assert md["pv"] and md["pv"][0] == md["move"]
        assert abs(sum(md["wdl"]) - 1.0) < 1e-6
    # sorted best first, winrate monotone in score
    scores = [m["score_cp"] for m in res["moves"]]
    assert scores == sorted(scores, reverse=True)
    wrs = [m["estimated_winrate"] for m in res["moves"]]
    assert wrs == sorted(wrs, reverse=True)
    assert res["calibration"]["label"] == "Estimated Win Rate"
    assert res["nodes"] >= sum(m["nodes"] for m in res["moves"])
    assert pos.to_fen() == Position.initial().to_fen()  # position untouched


def test_scores_match_single_best_search_at_same_depth():
    """The best root move's analysis score equals what the plain search reports."""
    from engine.search import Searcher
    pos = Position.from_fen("r1ba1ab1r/4k1n2/1c2n2c1/p3p1p1p/2p6/6P2/P1P1P3P/1C2NC3/4K4/RNBA1AB1R w - - 0 1")
    cfg = SearchConfig(aspiration=False)
    res = Analyzer(cfg).analyze(pos, time_limit_ms=None, max_depth=3)
    single = Searcher(SearchConfig(aspiration=False)).search(pos, max_depth=3)
    assert res["moves"][0]["score_cp"] == single.score


def test_pv_lines_are_legal_and_replayable():
    pos = Position.initial()
    res = Analyzer().analyze(pos, time_limit_ms=None, max_depth=3)
    for md in res["moves"][:10]:
        end = replay(pos.to_fen(), md["pv"])
        assert end is not None
        assert len(md["pv_pretty"]) == len(md["pv"])


def test_streaming_progress_updates_deepen_monotonically():
    pos = Position.initial()
    snapshots = []

    def progress(r, final):
        snapshots.append((r["depth"], final, len(r["moves"])))

    res = Analyzer().analyze(pos, time_limit_ms=None, max_depth=3, progress=progress,
                             progress_interval_ms=0)
    assert snapshots[-1][1] is True
    depths = [d for d, _, _ in snapshots]
    assert depths == sorted(depths)
    assert depths[-1] == 3 == res["depth"]
    assert all(n == 32 for _, _, n in snapshots)


def test_time_limit_and_stop_event():
    pos = Position.initial()
    res = Analyzer().analyze(pos, time_limit_ms=400)
    assert res["time_ms"] < 1500 and res["depth"] >= 1
    assert all(m["score_cp"] is not None for m in res["moves"])

    ev = threading.Event()
    threading.Timer(0.2, ev.set).start()
    res2 = Analyzer().analyze(pos, time_limit_ms=None, max_depth=64, stop_event=ev)
    assert res2["final"] and res2["aborted"]
    assert pos.stack_depth() == 0


def test_mate_in_one_is_ranked_first_with_100_percent():
    pos = build({(8, 4): make_piece(CHO, KING), (0, 3): make_piece(HAN, KING), (5, 4): make_piece(CHO, CHARIOT),
                 (2, 3): make_piece(CHO, PAWN), (3, 8): make_piece(CHO, CHARIOT)})
    res = Analyzer().analyze(pos, time_limit_ms=None, max_depth=3)
    top = res["moves"][0]
    assert top["mate"] == 1 and top["estimated_winrate"] == 1.0 and top["wdl"] == [1.0, 0.0, 0.0]
    mates = [m for m in res["moves"] if m["mate"] == 1]
    assert {m["move"] for m in mates} == {"49-19", "34-25", "65-25"}  # R, P, R mates
    pos.make_move(str_to_move(top["move"]))
    from engine.rules import game_status
    assert game_status(pos).status == CHECKMATE


def test_terminal_position_has_no_moves_but_valid_status():
    pos = build({(8, 4): make_piece(CHO, KING), (0, 3): make_piece(HAN, KING), (5, 4): make_piece(CHO, CHARIOT),
                 (2, 3): make_piece(CHO, PAWN), (0, 8): make_piece(CHO, CHARIOT)}, side=HAN)
    res = Analyzer().analyze(pos, time_limit_ms=None, max_depth=3)
    assert res["moves"] == []
    assert res["status"]["status"] == "checkmate" and res["status"]["winner"] == "cho"


def test_format_analysis_text():
    res = Analyzer().analyze(Position.initial(), time_limit_ms=None, max_depth=1)
    text = format_analysis(res, top_n=3)
    assert "Estimated Win Rate" in text and "PV:" in text
    assert text.count("\n") >= 3

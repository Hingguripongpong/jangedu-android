"""Move explanations and last-move briefing: facts must be true for constructed positions."""
from engine.analysis import Analyzer
from engine.board import CANNON, CHARIOT, CHO, HAN, HORSE, KING, PAWN, Position, encode_move, make_piece
from engine.explain import (
    attackers_of, briefing, explain_move, explain_top, hanging_pieces, josa, move_facts, obj, pv_material, see_gain,
    subj, threats,
)
from engine.geometry import sq
from engine.notation import move_to_str
from engine.rules import RuleConfig, replay
from engine.search import SearchConfig


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


KINGS = {(8, 4): cho(KING), (1, 3): han(KING)}


def test_particles():
    assert obj("馬(마)") == "馬(마)를" and obj("卒(졸)") == "卒(졸)을"
    assert subj("車(차)") == "車(차)가" and subj("象(상)") == "象(상)이"
    assert josa("馬(마)", ("은", "는")) == "馬(마)는"
    assert obj("馬 02→83") == "馬 02→83을(를)"      # no hangul -> both


def test_see_and_hanging():
    pos = build({**KINGS, (9, 0): cho(CHARIOT), (4, 0): han(HORSE)})
    assert attackers_of(pos, sq(4, 0), CHO) == [sq(9, 0)]
    assert see_gain(pos, sq(4, 0), CHO) == 500
    assert [(s, g) for s, _, g in threats(pos, CHO)] == [(sq(4, 0), 500)]
    defended = build({**KINGS, (9, 0): cho(CHARIOT), (4, 0): han(HORSE), (3, 0): han(PAWN)})
    assert see_gain(defended, sq(4, 0), CHO) < 0          # R takes N, P retakes R
    assert threats(defended, CHO) == []
    pawn_attack = build({**KINGS, (5, 0): cho(PAWN), (4, 0): han(HORSE), (3, 0): han(PAWN)})
    assert see_gain(pawn_attack, sq(4, 0), CHO) == 300    # P takes N (500), P retakes P (200)
    # our hanging chariot attacked by a horse through an empty square
    hang = build({**KINGS, (5, 4): cho(CHARIOT), (3, 3): han(HORSE)})
    assert [(s, g) for s, _, g in hanging_pieces(hang, CHO)] == [(sq(5, 4), 1300)]


def test_move_facts_capture_threat_escape():
    pos = build({**KINGS, (9, 0): cho(CHARIOT), (4, 0): han(HORSE), (4, 6): han(CANNON)})
    f = move_facts(pos, encode_move(sq(9, 0), sq(4, 0)))
    assert f["capture"] == "馬(마)" and f["capture_points"] == 5 and not f["loses_piece"]
    # after Rxa5 the chariot on rank 5 also attacks the undefended cannon on the same rank
    assert any("包" in p for _, p, _ in f["new_threats"])
    hang = build({**KINGS, (5, 4): cho(CHARIOT), (3, 3): han(HORSE)})
    esc = move_facts(hang, encode_move(sq(5, 4), sq(5, 8)))
    assert esc["escapes_attack"] and not esc["loses_piece"]
    bad = move_facts(hang, encode_move(sq(5, 4), sq(5, 3)))   # still attacked? (3,3)->(4,3)->(5,4)/(5,2): (5,3) is safe from that horse
    assert bad["escapes_attack"]


def test_explanations_and_briefing_end_to_end():
    rules = RuleConfig(bikjang="off")
    pos = rules.apply(build({**KINGS, (9, 0): cho(CHARIOT), (4, 0): han(HORSE), (2, 8): han(PAWN), (6, 8): cho(PAWN)}))
    res = Analyzer(SearchConfig(rules=rules)).analyze(pos, time_limit_ms=None, max_depth=3)
    ex = explain_top(pos, res, 3)
    assert ex[0]["move"] == "01-51" and "(최선수)" in ex[0]["head"]
    assert any("馬(마)를 잡습니다(5점)" in g for g in ex[0]["good"])
    assert all(e["plan"] == "" or e["plan"].startswith("예상 진행") for e in ex)
    # briefing: the human played a quiet king move instead of taking the horse
    b = briefing(pos, "95-94", res)
    assert b["rank"] > 1 and b["best"]["move"] == "01-51" and b["best"]["gap_pct"] > 4
    assert "실수" in b["verdict"] and b["better"] and "車 01x51" in b["better"][0]
    top = briefing(pos, "01-51", res)
    assert top["rank"] == 1 and "최선수" in top["verdict"] and top["best"] is None
    missing = briefing(pos, "pass", {"moves": []})
    assert "없습니다" in missing["verdict"]


def test_pv_material_accounting():
    rules = RuleConfig(bikjang="off")
    pos = rules.apply(build({**KINGS, (9, 0): cho(CHARIOT), (4, 0): han(HORSE), (2, 8): han(PAWN), (6, 8): cho(PAWN)}))
    info = pv_material(pos, ["01-51", "39-49"])
    assert info["net_points"] == 5 and info["captures"] == [(0, "馬(마)")] and info["losses"] == []
    assert pv_material(pos, ["99-99"])["net_points"] == 0   # illegal text stops the simulation


def test_server_briefing_endpoint():
    from fastapi.testclient import TestClient
    from server.app import app
    with TestClient(app) as client:
        r = client.post("/api/analyze", json={"moves": ["79-78"], "time_limit_ms": 300, "max_depth": 3,
                                              "rules": {"bikjang": "off"}})
        d = r.json()
        assert d["explanations"] and len(d["explanations"]) <= 5
        assert d["explanations"][0]["rank"] == 1 and d["explanations"][0]["good"]
        # briefing of 79-78 using the cached/computed analysis of the start position
        b = client.post("/api/briefing", json={"moves": ["79-78"], "rules": {"bikjang": "off"}, "time_limit_ms": 300}).json()
        assert b["move"] == "79-78" and b["rank"] >= 1 and b["total"] == 32 and b["verdict"]
        assert b["source"] in ("cache", "search")
        # client-supplied analysis is used when it matches the position
        fake = {"fen": b["fen_before"], "moves": [{"move": "79-78", "notation": "卒 79→78", "rank": 1, "score": 0.1,
                                                   "score_cp": 10, "mate": None, "decisive": None,
                                                   "estimated_winrate": 0.505, "pv": ["79-78"], "pv_pretty": ["卒 79→78"]}]}
        b2 = client.post("/api/briefing", json={"moves": ["79-78"], "rules": {"bikjang": "off"}, "prev_analysis": fake}).json()
        assert b2["source"] == "client" and b2["rank"] == 1
        assert client.post("/api/briefing", json={"moves": []}).status_code == 400
        assert client.post("/api/briefing", json={"moves": ["01-99"]}).status_code == 400

"""Fairy-Stockfish backend: notation mapping always; engine tests only when a binary is available
(engines/ folder or $JANGGI_FSF_PATH)."""
import os
import threading

import pytest

from engine.board import PASS_MOVE, Position
from engine.notation import move_to_str, str_to_move
from engine.rules import RuleConfig, replay
from engine.uci_engine import UciEngine, find_binary, move_to_uci, moves_to_uci, sq_to_uci, uci_to_move, uci_to_sq

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BINARY = find_binary(search_dirs=[os.path.join(ROOT, "engines")])
needs_engine = pytest.mark.skipif(BINARY is None, reason="no Fairy-Stockfish binary (engines/ or JANGGI_FSF_PATH)")
START = "rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w - - 0 1"


def test_uci_square_and_move_mapping():
    assert sq_to_uci(0) == "a10" and sq_to_uci(89) == "i1" and sq_to_uci(76) == "e2"
    for s in range(90):
        assert uci_to_sq(sq_to_uci(s)) == s
    pos = Position.initial()
    assert move_to_uci(pos, str_to_move("02-83")) == "b1c3" and move_to_str(uci_to_move("b1c3")) == "02-83"
    assert move_to_uci(pos, PASS_MOVE) == "e2e2" and uci_to_move("e2e2") == PASS_MOVE
    assert uci_to_move("b10c8") == str_to_move("12-33") and uci_to_move("a10a9") == str_to_move("11-21")
    pos.make_move(str_to_move("02-83"))
    assert move_to_uci(pos, PASS_MOVE) == "e9e9"           # Han's king square
    assert moves_to_uci(Position.initial(), [str_to_move("02-83"), PASS_MOVE]) == ["b1c3", "e9e9"]


@pytest.fixture(scope="module")
def engine():
    eng = UciEngine(BINARY, threads=1, hash_mb=32)
    eng.start()
    yield eng
    eng.close()


@needs_engine
def test_engine_starts_and_analyses_all_moves(engine):
    rules = RuleConfig(bikjang="forced")
    pos = rules.apply(Position.initial())
    updates = []
    res = engine.analyze(pos, time_limit_ms=600, progress=lambda r, f: updates.append(f), rules=rules)
    assert "Fairy-Stockfish" in res["engine"]["name"] and res["engine"]["backend"] == "uci"
    assert res["final"] and not res["aborted"] and len(res["moves"]) == 32
    assert res["depth"] >= 4 and res["nodes"] > 1000
    assert all(m["score_cp"] is not None and m["depth"] >= 1 for m in res["moves"])
    scores = [m["score_cp"] for m in res["moves"]]
    assert scores == sorted(scores, reverse=True)
    top = res["moves"][0]
    assert top["pv"][0] == top["move"] and len(top["pv_pretty"]) == len(top["pv"])
    # 한수쉼 at the start loses on points under Fairy-Stockfish's janggi rule
    pas = [m for m in res["moves"] if m["move"] == "pass"][0]
    assert pas["mate"] is not None and pas["mate"] < 0 and pas["estimated_winrate"] == 0.0
    assert updates and updates[-1] is True
    assert res["rules"]["bikjang"] == "forced"


@needs_engine
def test_engine_stop_event_and_history(engine):
    rules = RuleConfig(bikjang="forced")
    pos = replay(None, ["02-83", "12-33", "83-02", "33-12"], rules)
    ev = threading.Event()
    threading.Timer(0.3, ev.set).start()
    res = engine.analyze(pos, time_limit_ms=None, stop_event=ev, rules=rules)
    assert res["aborted"] and res["time_ms"] < 3000 and len(res["moves"]) == 32
    assert pos.stack_depth() == 4  # untouched


@needs_engine
def test_engine_mate_and_bikjang_points(engine):
    rules = RuleConfig(bikjang="forced")
    mate = rules.apply(Position.from_fen("3k5/9/9/9/9/9/9/9/4K4/R8 w - - 0 1"))
    res = engine.analyze(mate, time_limit_ms=500, rules=rules)
    assert res["moves"][0]["mate"] is not None and res["moves"][0]["mate"] > 0
    assert res["moves"][0]["estimated_winrate"] == 1.0
    # Cho a chariot up facing a bikjang: accepting it (pass) wins on points -> engine agrees with our rules
    pos = rules.apply(Position.from_fen("9/4k4/9/8p/9/9/P8/9/4K4/R8 w - - 0 1"))
    res2 = engine.analyze(pos, time_limit_ms=400, rules=rules)
    pas = [m for m in res2["moves"] if m["move"] == "pass"][0]
    assert pas["mate"] == 1


@needs_engine
def test_server_engine_selection():
    from fastapi.testclient import TestClient
    from server.app import app
    with TestClient(app) as client:
        engines = client.get("/api/engines").json()["engines"]
        fsf = [e for e in engines if e["id"] == "fsf"][0]
        assert fsf["available"] is True
        r = client.post("/api/analyze", json={"fen": START, "time_limit_ms": 400, "engine": "fsf",
                                              "rules": {"bikjang": "draw"}})
        d = r.json()
        assert r.status_code == 200 and d["engine"]["backend"] == "uci" and d["rules"]["bikjang"] == "forced"
        assert len(d["moves"]) == 32 and d["depth"] >= 3
        b = client.post("/api/bestmove", json={"fen": START, "time_limit_ms": 300, "engine": "fsf"}).json()
        assert b["move"] and b["engine"]["backend"] == "uci"
        assert client.post("/api/analyze", json={"fen": START, "engine": "nope"}).status_code == 400
        with client.websocket_connect("/ws/analyze") as ws:
            ws.send_json({"type": "analyze", "id": 21, "fen": START, "time_limit_ms": 0, "engine": "fsf"})
            first = ws.receive_json()
            assert first["id"] == 21 and first["type"] in ("update", "heartbeat")
            ws.send_json({"type": "stop"})
            while True:
                msg = ws.receive_json()
                if msg["type"] == "final":
                    assert msg["aborted"] and msg["engine"]["backend"] == "uci" and len(msg["moves"]) == 32
                    break


def test_variant_mapping_for_rules():
    from engine.uci_engine import variant_for_rules
    assert variant_for_rules(RuleConfig(bikjang="forced")) == "janggi"
    assert variant_for_rules(RuleConfig(bikjang="off")) == "janggicasual"
    with pytest.raises(ValueError):
        variant_for_rules(RuleConfig(bikjang="draw"))


@needs_engine
def test_engine_switches_variant_with_rules(engine):
    fen = "9/4k4/9/8p/9/9/P8/9/4K4/R8 w - - 0 1"       # kings facing, Cho a chariot up
    forced = RuleConfig(bikjang="forced")
    res = engine.analyze(forced.apply(Position.from_fen(fen)), time_limit_ms=300, rules=forced)
    assert res["engine"]["variant"] == "janggi"
    assert [m for m in res["moves"] if m["move"] == "pass"][0]["mate"] == 1      # accepting wins on points
    assert len(res["moves"]) == 7                                                 # 6 king moves off the file + pass
    off = RuleConfig(bikjang="off")
    res2 = engine.analyze(off.apply(Position.from_fen(fen)), time_limit_ms=300, rules=off)
    assert res2["engine"]["variant"] == "janggicasual"
    pas = [m for m in res2["moves"] if m["move"] == "pass"][0]
    assert pas["mate"] is None                                                    # no bikjang ending here
    assert len(res2["moves"]) > 7                                                 # every move is legal again
    # and back
    res3 = engine.analyze(forced.apply(Position.from_fen(fen)), time_limit_ms=200, rules=forced)
    assert res3["engine"]["variant"] == "janggi"


@needs_engine
def test_server_fsf_rule_coercion():
    from fastapi.testclient import TestClient
    from server.app import app
    with TestClient(app) as client:
        d = client.post("/api/analyze", json={"fen": START, "time_limit_ms": 200, "engine": "fsf",
                                              "rules": {"bikjang": "off"}}).json()
        assert d["rules"]["bikjang"] == "off" and d["engine"]["variant"] == "janggicasual"
        d2 = client.post("/api/analyze", json={"fen": START, "time_limit_ms": 200, "engine": "fsf",
                                               "rules": {"bikjang": "points"}}).json()
        assert d2["rules"]["bikjang"] == "forced" and d2["engine"]["variant"] == "janggi"
        st = client.post("/api/state", json={"fen": START, "rules": {"bikjang": "off"}}).json()
        assert st["rules"]["bikjang"] == "off"


# ---------------------------------------------------------------- mode separation at command level
class _ScriptedEngine(UciEngine):
    """UciEngine whose process is replaced by a script: records commands, answers like FSF would."""

    def __init__(self, best="b1c3", cp=17):
        super().__init__("/fake/fairy-stockfish", threads=1, hash_mb=16)
        self.best, self.cp = best, cp
        self.id_name = "Fairy-Stockfish (scripted)"

    def start(self):
        import queue as _q
        if self.proc is None:
            self.proc = object()          # "running"
            self.lines = _q.Queue()
            self._active_variant = self.variant
            self._multipv = None

    def close(self):
        self.proc = None

    def _send(self, cmd):
        self.sent.append(cmd)
        if cmd == "isready":
            self.lines.put("readyok")
        elif cmd.startswith("go"):
            n = self._multipv or 1
            for i in range(1, n + 1):
                self.lines.put(f"info depth 7 seldepth 9 multipv {i} score cp {self.cp - i} nodes 1000 nps 50000 "
                               f"time 20 pv {self.best if i == 1 else 'a1a2'} e9e9")
            self.lines.put(f"bestmove {self.best}")


def test_bestmove_uses_multipv_1_and_analysis_uses_all_moves():
    eng = _ScriptedEngine()
    pos = RuleConfig(bikjang="off").apply(Position.initial())
    res = eng.bestmove(pos, time_limit_ms=100, rules=RuleConfig(bikjang="off"))
    assert "setoption name MultiPV value 1" in eng.sent
    assert not any(c.startswith("setoption name MultiPV value 32") for c in eng.sent)
    assert any(c.startswith("go movetime 100") for c in eng.sent)
    assert res["move"] == "02-83" and res["engine"]["mode"] == "play" and res["engine"]["multipv"] == 1
    assert res["score_cp"] == 16 and res["depth"] == 7 and res["pv"][0] == "02-83"
    eng.sent.clear()
    res2 = eng.analyze(pos, time_limit_ms=100, rules=RuleConfig(bikjang="off"))
    assert "setoption name MultiPV value 32" in eng.sent
    assert res2["engine"]["mode"] == "analysis" and res2["engine"]["multipv"] == 32
    assert len(res2["moves"]) == 32
    # switching back to play mode re-sends MultiPV 1 (only when it changes)
    eng.sent.clear()
    eng.bestmove(pos, time_limit_ms=50, rules=RuleConfig(bikjang="off"))
    assert eng.sent.count("setoption name MultiPV value 1") == 1
    eng.sent.clear()
    eng.bestmove(pos, time_limit_ms=50, rules=RuleConfig(bikjang="off"))
    assert "setoption name MultiPV value 1" not in eng.sent   # unchanged -> not re-sent


def test_bestmove_returns_none_when_game_over():
    eng = _ScriptedEngine()
    rules = RuleConfig(bikjang="forced")
    pos = replay(None, ["pass", "pass"], rules)
    res = eng.bestmove(pos, time_limit_ms=100, rules=rules)
    assert res["move"] is None and res["status"]["is_over"]
    assert not any(c.startswith("go") for c in eng.sent)


@needs_engine
def test_real_engine_bestmove_is_single_pv(engine):
    rules = RuleConfig(bikjang="forced")
    pos = rules.apply(Position.initial())
    engine.sent.clear()
    res = engine.bestmove(pos, time_limit_ms=300, rules=rules)
    assert res["move"] in {move_to_str(m) for m in __import__("engine.movegen", fromlist=["x"]).generate_legal_moves(pos)}
    assert res["depth"] >= 3 and res["nodes"] > 0 and res["engine"]["mode"] == "play"
    assert "setoption name MultiPV value 1" in engine.sent
    assert res["pv"] and res["pv"][0] == res["move"]
    engine.sent.clear()
    res2 = engine.analyze(pos, time_limit_ms=300, rules=rules)
    assert "setoption name MultiPV value 32" in engine.sent and len(res2["moves"]) == 32

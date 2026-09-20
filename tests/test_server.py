"""API tests: state, analyze (REST alias too), bestmove, eval, perft, websocket streaming/stop."""
import pytest
from fastapi.testclient import TestClient

from engine.board import Position
from server.app import app

START = "rnba1abnr/4k4/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/4K4/RNBA1ABNR w - - 0 1"


@pytest.fixture(scope="module")
def client():
    with TestClient(app) as c:
        yield c


def test_index_serves_ui(client):
    r = client.get("/")
    assert r.status_code == 200
    assert "<html" in r.text.lower() and "Estimated Win Rate" in r.text


def test_new_game_and_setups(client):
    r = client.get("/api/new_game")
    assert r.status_code == 200
    d = r.json()
    assert d["fen"] == START and d["side"] == "cho" and len(d["legal_moves"]) == 32
    assert d["status"]["status"] == "ongoing"
    assert len(d["board"]) == 90 and d["board"][76]["hanja"] == "楚"
    r2 = client.get("/api/new_game", params={"cho": "상마상마", "han": "마상마상"})
    assert r2.status_code == 200 and r2.json()["fen"] != START
    assert client.get("/api/new_game", params={"cho": "xyz"}).status_code == 400
    assert set(client.get("/api/setups").json()["setups"]) >= {"마상상마", "상마상마"}


def test_state_from_moves_and_errors(client):
    r = client.post("/api/state", json={"moves": ["09-99"]})   # 楚車 (9,8) -> (8,8)
    assert r.status_code == 200
    d = r.json()
    assert d["side"] == "han" and d["ply"] == 1 and d["last_move"] == "09-99"
    bad = client.post("/api/state", json={"moves": ["01-99"]})
    assert bad.status_code == 400 and "illegal" in bad.json()["detail"]
    bad2 = client.post("/api/state", json={"fen": "not a fen"})
    assert bad2.status_code == 400


def test_eval_and_perft(client):
    r = client.post("/api/eval", json={"fen": START})
    assert r.status_code == 200
    ev = r.json()["eval"]
    assert ev["total"] == sum(v for k, v in ev.items() if k != "total")
    p = client.get("/api/perft", params={"depth": 2}).json()
    assert p["nodes"] == 961
    assert client.get("/api/perft", params={"depth": 9}).status_code == 400


def test_analyze_rest_and_alias(client):
    body = {"position": START, "time_limit_ms": 300}
    r = client.post("/analyze", json=body)
    assert r.status_code == 200
    d = r.json()
    assert d["final"] and len(d["moves"]) == 32
    top = d["moves"][0]
    for key in ("from", "to", "score", "estimated_winrate", "depth", "nodes", "pv"):
        assert key in top
    assert d["calibration"]["label"] == "Estimated Win Rate"
    r2 = client.post("/api/analyze", json={"moves": ["09-99"], "time_limit_ms": 200, "max_depth": 2})
    assert r2.status_code == 200 and r2.json()["side"] == "han"
    assert all(m["depth"] <= 2 for m in r2.json()["moves"])


def test_bestmove(client):
    r = client.post("/api/bestmove", json={"fen": START, "time_limit_ms": 200})
    assert r.status_code == 200
    d = r.json()
    assert d["move"] and d["depth"] >= 1 and d["nodes"] > 0 and d["pv"][0] == d["move"]
    mate_fen = "3k4R/9/3P5/9/9/4R4/9/9/4K4/9 b - - 0 1"
    assert client.post("/api/bestmove", json={"fen": mate_fen}).status_code == 400  # game over


def test_websocket_streaming_and_stop(client):
    with client.websocket_connect("/ws/analyze") as ws:
        ws.send_json({"type": "analyze", "id": 1, "fen": START, "time_limit_ms": 0, "max_depth": 3})
        seen_update = False
        while True:
            msg = ws.receive_json()
            assert msg["id"] == 1
            if msg["type"] == "update":
                seen_update = True
                assert len(msg["moves"]) == 32
            elif msg["type"] == "final":
                assert msg["depth"] == 3 and len(msg["moves"]) == 32
                break
        assert seen_update
        # unlimited search, then stop: must still receive a final for id 2
        ws.send_json({"type": "analyze", "id": 2, "moves": ["09-99"], "time_limit_ms": 0})
        first = ws.receive_json()
        assert first["id"] == 2
        ws.send_json({"type": "stop"})
        while True:
            msg = ws.receive_json()
            if msg["type"] == "final":
                assert msg["id"] == 2 and msg["aborted"] is True
                break
        # a new request supersedes a running one
        ws.send_json({"type": "analyze", "id": 3, "fen": START, "time_limit_ms": 0})
        ws.send_json({"type": "analyze", "id": 4, "fen": START, "time_limit_ms": 150})
        finals = set()
        while len(finals) < 2:
            msg = ws.receive_json()
            if msg["type"] == "final":
                finals.add(msg["id"])
        assert finals == {3, 4}
        ws.send_json({"type": "analyze", "id": 5, "fen": "garbage"})
        err = ws.receive_json()
        assert err["type"] == "error" and err["id"] == 5


BIKJANG_FEN = "9/4k4/9/8p/9/9/P8/9/4K4/R8 w - - 0 1"   # Cho to move, kings facing, Cho a chariot up


def test_rules_in_state_bestmove_and_analyze(client):
    r = client.post("/api/state", json={"fen": BIKJANG_FEN, "moves": ["71-61"]})
    assert r.json()["status"]["status"] == "draw_bikjang"
    r = client.post("/api/state", json={"fen": BIKJANG_FEN, "moves": ["71-61"], "rules": {"bikjang": "points"}})
    d = r.json()
    assert d["status"]["status"] == "bikjang_points" and d["status"]["winner"] == "cho"
    assert d["status"]["points"] == {"cho": 15.0, "han": 3.5} and d["rules"]["bikjang"] == "points"
    r = client.post("/api/state", json={"fen": BIKJANG_FEN, "moves": ["71-61"], "rules": {"bikjang": "off"}})
    assert r.json()["status"]["status"] == "ongoing"
    assert client.post("/api/state", json={"fen": BIKJANG_FEN, "rules": {"bikjang": "sometimes"}}).status_code == 400
    # under the points rule the engine sees that ignoring the bikjang wins on points
    a = client.post("/api/analyze", json={"fen": BIKJANG_FEN, "max_depth": 2, "time_limit_ms": 0,
                                          "rules": {"bikjang": "points"}}).json()
    top = a["moves"][0]
    assert top["decisive"] == "points" and top["estimated_winrate"] == 1.0 and a["rules"]["bikjang"] == "points"
    assert client.get("/api/new_game", params={"bikjang": "points", "repetition": "off"}).json()["rules"] == {
        "bikjang": "points", "repetition": "off", "max_plies": 400}
    assert client.get("/api/new_game", params={"bikjang": "x"}).status_code == 400


def test_websocket_rules(client):
    with client.websocket_connect("/ws/analyze") as ws:
        ws.send_json({"type": "analyze", "id": 7, "fen": BIKJANG_FEN, "time_limit_ms": 0, "max_depth": 2,
                      "rules": {"bikjang": "points"}})
        while True:
            msg = ws.receive_json()
            if msg["type"] == "final":
                assert msg["rules"]["bikjang"] == "points" and msg["moves"][0]["decisive"] == "points"
                break
        ws.send_json({"type": "analyze", "id": 8, "fen": BIKJANG_FEN, "time_limit_ms": 0, "max_depth": 2})
        while True:
            msg = ws.receive_json()
            if msg["type"] == "final":
                assert msg["rules"]["bikjang"] == "draw" and msg["moves"][0]["decisive"] is None
                break
        ws.send_json({"type": "analyze", "id": 9, "fen": BIKJANG_FEN, "rules": {"bikjang": "nope"}})
        err = ws.receive_json()
        assert err["type"] == "error" and err["id"] == 9


def test_rest_bestmove_preempts_unlimited_websocket_analysis(client):
    """AI 착수 while an unlimited streaming analysis runs must not wait for it (it stops it)."""
    import time as _t
    with client.websocket_connect("/ws/analyze") as ws:
        ws.send_json({"type": "analyze", "id": 11, "fen": START, "time_limit_ms": 0})
        first = ws.receive_json()
        assert first["id"] == 11 and first["type"] in ("update", "heartbeat")
        t0 = _t.perf_counter()
        r = client.post("/api/bestmove", json={"fen": START, "time_limit_ms": 300})
        assert r.status_code == 200 and r.json()["move"]
        assert _t.perf_counter() - t0 < 5.0
        while True:
            msg = ws.receive_json()
            if msg["type"] == "final":
                assert msg["id"] == 11 and msg["aborted"] is True
                break


def test_websocket_heartbeat_while_waiting_for_engine(client, monkeypatch):
    import server.app as appmod
    monkeypatch.setattr(appmod, "HEARTBEAT_S", 0.05)
    appmod.ENGINE_LOCK.acquire()          # simulate a busy engine
    try:
        with client.websocket_connect("/ws/analyze") as ws:
            ws.send_json({"type": "analyze", "id": 12, "fen": START, "time_limit_ms": 0, "max_depth": 2})
            hb = ws.receive_json()
            assert hb["type"] == "heartbeat" and hb["id"] == 12 and hb["waiting_for_engine"] is True
            appmod.ENGINE_LOCK.release()
            while True:
                msg = ws.receive_json()
                if msg["type"] == "final":
                    assert msg["depth"] == 2 and len(msg["moves"]) == 32
                    break
    finally:
        if appmod.ENGINE_LOCK.locked():
            appmod.ENGINE_LOCK.release()


def test_websocket_worker_exception_still_sends_final(client, monkeypatch):
    import server.app as appmod
    from engine.analysis import Analyzer

    def boom(self, *a, **k):
        raise RuntimeError("synthetic failure")

    monkeypatch.setattr(Analyzer, "analyze", boom)
    with client.websocket_connect("/ws/analyze") as ws:
        ws.send_json({"type": "analyze", "id": 13, "fen": START, "time_limit_ms": 0})
        msg = ws.receive_json()
        assert msg["type"] == "final" and msg["id"] == 13 and "synthetic failure" in msg["error"]
        # the connection is still usable afterwards
        monkeypatch.undo()
        ws.send_json({"type": "analyze", "id": 14, "fen": START, "time_limit_ms": 0, "max_depth": 1})
        while True:
            m = ws.receive_json()
            if m["type"] == "final":
                assert m["id"] == 14 and len(m["moves"]) == 32
                break


# ---------------------------------------------------------------- state-aware analysis cache
def test_cache_key_is_state_aware():
    from engine.board import Position
    from engine.notation import str_to_move
    from engine.rules import RuleConfig
    from server.app import cache_get, cache_key, cache_put
    rules = RuleConfig(bikjang="off")
    a = rules.apply(Position.initial())
    b = rules.apply(Position.initial())
    for t in ("02-83", "18-37", "83-02", "37-18"):
        b.make_move(str_to_move(t))
    assert a.to_fen().split()[0] == b.to_fen().split()[0]          # same board ...
    assert cache_key(a, rules, "fsf") != cache_key(b, rules, "fsf")  # ... different repetition context
    c = rules.apply(Position.initial())
    c.passes = 1
    assert cache_key(a, rules, "fsf") != cache_key(c, rules, "fsf")  # pending pass
    assert cache_key(a, rules, "fsf") != cache_key(a, RuleConfig(bikjang="forced"), "fsf")  # rules
    assert cache_key(a, rules, "fsf") != cache_key(a, rules, "python")                       # engine
    fake = {"final": True, "aborted": False, "depth": 5, "fen": a.to_fen(), "moves": [{"move": "02-83"}]}
    cache_put(fake, a, rules, "fsf")
    assert cache_get(a, rules, "fsf") is fake
    assert cache_get(c, rules, "fsf") is None
    assert cache_get(b, rules, "fsf") is None
    assert cache_get(rules.apply(Position.initial()), rules, "fsf") is fake  # identical state hits

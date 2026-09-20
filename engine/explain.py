"""Why is this move good?  Rule-based commentary derived from engine facts.

Nothing here is guessed by a language model: every sentence is backed by a
concrete fact the rules engine or the search produced — a capture, a
threat measured by static exchange evaluation, a piece left undefended, a
check, the material outcome along the principal variation, or a change in
one component of the evaluation.  The text templates are Korean.

Two entry points:

* ``explain_move(pos, move_dict, best_dict)`` — facts + sentences for one
  candidate move of the current position (``move_dict`` is an entry of
  ``Analyzer.analyze()["moves"]``, ``best_dict`` the top entry).
* ``briefing(prev_pos, played, analysis)`` — for the move that was just
  played: rank among all legal moves, gap to the best move, what it does
  well, what it risks, and what the engine preferred instead.
"""
from __future__ import annotations

from .board import (
    CANNON, CHARIOT, CHO, ELEPHANT, EMPTY, GUARD, HORSE, KING, NUM_SQUARES, PASS_MOVE, PAWN, Position,
    make_piece, move_from, move_to, piece_color, piece_type,
)
from .evaluation import DEFAULT_CONFIG, evaluate_breakdown
from .geometry import IN_PALACE, ROW_OF, COL_OF
from .movegen import generate_legal_moves, generate_pseudo_moves, gives_check
from .notation import KOREAN, SIDE_KOREAN, describe_move, move_to_str, piece_hanja, square_to_str, str_to_move
from .rules import RuleConfig, game_status

VALUE = {t: DEFAULT_CONFIG.material[t] for t in DEFAULT_CONFIG.material}   # centipawns
VALUE[KING] = 10_000
POINTS = {KING: 0, GUARD: 3, CHARIOT: 13, CANNON: 7, HORSE: 5, ELEPHANT: 3, PAWN: 2}


def pname(p: int) -> str:
    """'車(차)' style name."""
    return f"{piece_hanja(p)}({KOREAN[piece_color(p)][piece_type(p)]})"


def _last_hangul(text: str) -> str | None:
    for ch in reversed(text):
        if "가" <= ch <= "힣":
            return ch
    return None


def josa(word: str, pair: tuple[str, str]) -> str:
    """Attach the right Korean particle: pair = (with final consonant, without), e.g. ("을", "를")."""
    ch = _last_hangul(word)
    if ch is None:
        return f"{word}{pair[0]}({pair[1]})"
    has_final = (ord(ch) - 0xAC00) % 28 != 0
    return word + (pair[0] if has_final else pair[1])


def obj(word: str) -> str:
    return josa(word, ("을", "를"))


def subj(word: str) -> str:
    return josa(word, ("이", "가"))


def sqn(s: int) -> str:
    return square_to_str(s)


# --------------------------------------------------------------------------- attack helpers
def attackers_of(pos: Position, target: int, color: int) -> list[int]:
    """Squares of ``color``'s pieces that could capture on ``target`` right now.

    Implemented by temporarily putting an enemy dummy pawn on the square (when
    empty) and reading ``color``'s pseudo-legal captures onto it; this reuses
    the real movement rules (cannon screens, horse/elephant blocking, palace
    diagonals) instead of re-implementing them.
    """
    saved_side, saved_piece = pos.side, pos.board[target]
    if saved_piece == EMPTY or piece_color(saved_piece) == color:
        pos.board[target] = make_piece(color ^ 1, PAWN)
    pos.side = color
    try:
        return sorted({move_from(m) for m in generate_pseudo_moves(pos) if m != PASS_MOVE and move_to(m) == target})
    finally:
        pos.side, pos.board[target] = saved_side, saved_piece


def defenders_of(pos: Position, target: int, color: int) -> list[int]:
    """``color``'s pieces that could recapture on ``target`` (which holds a ``color`` piece)."""
    return attackers_of(pos, target, color)


def see_gain(pos: Position, target: int, attacker_color: int) -> int:
    """Static exchange evaluation: material ``attacker_color`` nets by capturing on ``target``.

    Standard swap list with attackers taken cheapest first, x-rays ignored.  A
    king may capture only when the square is no longer attacked (otherwise the
    capture would be illegal).  A result <= 0 means the capture does not pay.
    """
    victim = pos.board[target]
    if victim == EMPTY:
        return 0

    def val(s: int) -> int:
        return VALUE[piece_type(pos.board[s])]

    lists = {c: sorted(attackers_of(pos, target, c), key=val) for c in (attacker_color, attacker_color ^ 1)}
    side = attacker_color
    gains = [VALUE[piece_type(victim)]]
    on_square = None
    while lists[side]:
        nxt = lists[side][0]
        if piece_type(pos.board[nxt]) == KING and lists[side ^ 1]:
            break                       # the king cannot capture into an attacked square
        lists[side].pop(0)
        if on_square is not None:
            gains.append(on_square - gains[-1])
        on_square = val(nxt)
        side ^= 1
    if len(gains) == 1 and on_square is None:
        return 0                        # nobody could legally capture
    for i in range(len(gains) - 2, -1, -1):
        gains[i] = -max(-gains[i], gains[i + 1])
    return gains[0]


def hanging_pieces(pos: Position, color: int) -> list[tuple[int, int, int]]:
    """(square, piece, opponent's SEE gain) for ``color``'s pieces the opponent can profitably capture."""
    out = []
    for s in range(NUM_SQUARES):
        p = pos.board[s]
        if p == EMPTY or piece_color(p) != color or piece_type(p) == KING:
            continue
        g = see_gain(pos, s, color ^ 1)
        if g > 0:
            out.append((s, p, g))
    out.sort(key=lambda x: -x[2])
    return out


def threats(pos: Position, color: int) -> list[tuple[int, int, int]]:
    """Enemy pieces that ``color`` could capture profitably right now: (square, piece, gain)."""
    return hanging_pieces(pos, color ^ 1)


# --------------------------------------------------------------------------- material along the PV
def pv_material(pos: Position, pv: list[str]) -> dict:
    """Simulate the PV; return captures by each side and the net material for the side to move."""
    cur = pos.copy()
    me = cur.side
    mine, theirs = [], []
    for i, text in enumerate(pv):
        try:
            m = str_to_move(text)
        except ValueError:
            break
        if m not in generate_legal_moves(cur):
            break
        if m != PASS_MOVE:
            cap = cur.board[move_to(m)]
            if cap:
                (mine if cur.side == me else theirs).append((i, cap))
        cur.make_move(m)
    gain = sum(POINTS[piece_type(p)] for _, p in mine) - sum(POINTS[piece_type(p)] for _, p in theirs)
    return {"captures": [(i, pname(p)) for i, p in mine], "losses": [(i, pname(p)) for i, p in theirs],
            "net_points": gain, "end": cur}


# --------------------------------------------------------------------------- per-move facts
def move_facts(pos: Position, m: int) -> dict:
    """Everything the templates need about one move, computed by playing it."""
    side = pos.side
    facts: dict = {"move": move_to_str(m), "notation": describe_move(pos.board, m), "pass": m == PASS_MOVE}
    if m == PASS_MOVE:
        facts.update(capture=None, check=False)
        before_hang = hanging_pieces(pos, side)
        facts["hanging_before"] = [(sqn(s), pname(p), g) for s, p, g in before_hang]
        return facts
    frm, to = divmod(m, NUM_SQUARES)
    piece = pos.board[frm]
    victim = pos.board[to]
    facts["piece"] = pname(piece)
    facts["ptype"] = piece_type(piece)
    facts["from"], facts["to"] = sqn(frm), sqn(to)
    facts["capture"] = pname(victim) if victim else None
    facts["capture_points"] = POINTS[piece_type(victim)] if victim else 0
    facts["check"] = gives_check(pos, m)

    # before the move: was the piece under profitable attack?  what did we threaten?
    before_hanging = {s: g for s, _, g in hanging_pieces(pos, side)}
    facts["escapes_attack"] = before_hanging.get(frm, 0) > 0
    before_threats = {s for s, _, _ in threats(pos, side)}
    bd_before = evaluate_breakdown(pos)

    pos.make_move(m)
    try:
        # the moved piece's safety on its new square
        atk = attackers_of(pos, to, side ^ 1)
        dfd = defenders_of(pos, to, side)
        gain_for_opp = see_gain(pos, to, side ^ 1)
        facts["attacked_after"] = bool(atk)
        facts["attackers_after"] = [pname(pos.board[s]) for s in atk][:3]
        facts["defended_after"] = bool(dfd)
        facts["loses_piece"] = gain_for_opp > 0
        facts["exchange_value"] = -gain_for_opp   # from our perspective, if the opponent captures
        # new threats we created (excluding the square we just captured on)
        after_threats = threats(pos, side)  # note: it is the opponent's turn; these are what we threaten next
        facts["new_threats"] = [(sqn(s), pname(p), g) for s, p, g in after_threats if s not in before_threats][:3]
        # own pieces left hanging (other than the moved piece)
        after_hanging = hanging_pieces(pos, side)
        facts["left_hanging"] = [(sqn(s), pname(p), g) for s, p, g in after_hanging if s != to and before_hanging.get(s, 0) <= 0][:3]
        facts["still_hanging"] = [(sqn(s), pname(p), g) for s, p, g in after_hanging if s != to and before_hanging.get(s, 0) > 0][:3]
        # evaluation components from our perspective (breakdown is side-to-move, i.e. the opponent now)
        bd_after = evaluate_breakdown(pos)
        facts["eval_delta"] = {k: -bd_after[k] - bd_before[k] for k in ("pst", "pawn_structure", "king_safety", "mobility")}
        facts["opp_in_check"] = pos.in_check()
        facts["bikjang_after"] = pos.bikjang
        st = game_status(pos)
        facts["mates"] = st.status == "checkmate"
        # own pieces that were hanging before and are safe now (the move defends them)
        after_h = {s for s, _, _ in after_hanging}
        facts["defends"] = [(sqn(s), pname(pos.board[s])) for s, g in before_hanging.items()
                            if g > 0 and s != frm and s not in after_h and pos.board[s] != EMPTY][:2]
        # geometry of the destination
        facts["to_palace"] = IN_PALACE[to]
        facts["enters_enemy_half"] = (ROW_OF[to] <= 4) if side == CHO else (ROW_OF[to] >= 5)
        facts["from_back_rank"] = (ROW_OF[frm] == 9) if side == CHO else (ROW_OF[frm] == 0)
        facts["center_file"] = COL_OF[to] in (3, 4, 5)
        facts["face_cannon"] = piece_type(piece) == CANNON and to == (7 * 9 + 4 if side == CHO else 2 * 9 + 4)
        col = COL_OF[to]
        facts["open_file"] = piece_type(piece) == CHARIOT and not any(
            pos.board[r * 9 + col] != EMPTY and piece_type(pos.board[r * 9 + col]) == PAWN for r in range(10))
        facts["pawn_advance"] = piece_type(piece) == PAWN and ((ROW_OF[to] < ROW_OF[frm]) if side == CHO else (ROW_OF[to] > ROW_OF[frm]))
        facts["pawn_sideways"] = piece_type(piece) == PAWN and ROW_OF[to] == ROW_OF[frm]
        facts["king_or_guard"] = piece_type(piece) in (KING, GUARD)
    finally:
        pos.unmake_move()
    return facts


# --------------------------------------------------------------------------- text
def _pct(w) -> str:
    return "—" if w is None else f"{w * 100:.1f}%"


def _score_txt(md: dict) -> str:
    if md.get("mate"):
        return f"외통 {abs(md['mate'])}수" if md["mate"] > 0 else f"외통 당함 {abs(md['mate'])}수"
    if md.get("decisive") == "points":
        return "점수승" if (md.get("score_cp") or 0) > 0 else "점수패"
    if md.get("score") is None:
        return "—"
    return f"{md['score']:+.2f}"


def sentences_for(facts: dict, md: dict | None, pv_info: dict | None) -> tuple[list[str], list[str]]:
    """(good points, risks) as short Korean sentences, most important first."""
    good: list[str] = []
    risk: list[str] = []
    if facts.get("pass"):
        good.append("한수쉼: 현재 위치를 그대로 두고 상대에게 차례를 넘깁니다.")
        if facts.get("hanging_before"):
            s, p, g = facts["hanging_before"][0]
            risk.append(f"{subj(p + ' ' + s)} 공격받고 있어 그대로 두면 잃을 수 있습니다.")
        return good, risk

    if md and md.get("mate") and md["mate"] > 0:
        good.append(f"{md['mate']}수 안에 외통을 강제합니다.")
    if facts.get("mates"):
        good.append("이 수로 바로 외통입니다.")
    if facts.get("capture"):
        pts = facts["capture_points"]
        if facts.get("loses_piece"):
            good.append(f"{obj(facts['capture'])} 잡습니다({pts}점). 단, 이동한 {josa(facts['piece'], ('은', '는'))} 되잡힐 수 있습니다.")
        else:
            good.append(f"{obj(facts['capture'])} 잡습니다({pts}점). 되잡히지 않습니다.")
    if facts.get("check") and not facts.get("mates"):
        good.append("장군을 부릅니다.")
    if facts.get("escapes_attack"):
        good.append(f"공격받던 {obj(facts['piece'])} 안전한 곳으로 피합니다.")
    if facts.get("new_threats"):
        s, p, g = facts["new_threats"][0]
        good.append(f"다음 수에 {obj(p + ' ' + s)} 잡는 위협을 만듭니다.")
    if facts.get("still_hanging") == [] and facts.get("left_hanging") == [] and facts.get("capture") is None and not facts.get("check"):
        pass
    if facts.get("defends"):
        s, p = facts["defends"][0]
        good.append(f"공격받던 {obj(p + ' ' + s)} 지킵니다.")
    if facts.get("face_cannon"):
        good.append("면포를 세워 궁 앞을 두텁게 합니다.")
    if facts.get("open_file"):
        good.append(f"{obj(facts['piece'])} 졸이 없는 열린 열에 놓아 활동 폭을 넓힙니다.")
    # positional components
    ed = facts.get("eval_delta") or {}
    for k, v in sorted(ed.items(), key=lambda kv: -abs(kv[1])):
        if v >= 15:
            if k == "pst":
                if facts.get("from_back_rank"):
                    good.append(f"{obj(facts['piece'])} 전개해 위치가 좋아집니다(+{v / 100:.2f}).")
                elif facts.get("enters_enemy_half"):
                    good.append(f"{subj(facts['piece'])} 상대 진영으로 진출합니다(위치 +{v / 100:.2f}).")
                else:
                    good.append(f"{facts['piece']} 위치가 좋아집니다(+{v / 100:.2f}).")
            elif k == "king_safety":
                good.append(f"궁 주변이 더 안전해집니다(+{v / 100:.2f}).")
            elif k == "pawn_structure":
                good.append(f"졸이 서로 지켜 구조가 좋아집니다(+{v / 100:.2f}).")
            elif k == "mobility":
                good.append(f"기물들의 활동 범위가 넓어집니다(+{v / 100:.2f}).")
            break
    for k, v in sorted(ed.items(), key=lambda kv: kv[1]):
        if v <= -15:
            if k == "king_safety":
                risk.append(f"궁 주변이 약해집니다({v / 100:+.2f}).")
            elif k == "pst":
                risk.append(f"{facts['piece']}의 위치가 나빠집니다({v / 100:+.2f}).")
            elif k == "pawn_structure":
                risk.append(f"졸 구조가 약해집니다({v / 100:+.2f}).")
            elif k == "mobility":
                risk.append(f"기물 활동 범위가 줄어듭니다({v / 100:+.2f}).")
            break
    # risks
    if facts.get("loses_piece") and not facts.get("capture"):
        atk = ", ".join(facts.get("attackers_after") or [])
        risk.append(f"이동한 {subj(facts['piece'])} {atk}의 공격을 받아 잃을 수 있습니다.")
    elif facts.get("attacked_after") and facts.get("defended_after") and not facts.get("capture"):
        atk = ", ".join(facts.get("attackers_after") or [])
        good.append(f"{subj(atk)} 노리지만 아군이 지키고 있어 안전합니다.")
    if facts.get("left_hanging"):
        s, p, g = facts["left_hanging"][0]
        risk.append(f"{subj(p + ' ' + s)} 무방비로 남습니다({g / 100:.1f}점 손실 위험).")
    if facts.get("still_hanging"):
        s, p, g = facts["still_hanging"][0]
        risk.append(f"공격받는 {obj(p + ' ' + s)} 그대로 둡니다.")
    if facts.get("bikjang_after"):
        good.append("두 궁을 마주 세워 빅장을 만듭니다.")
    if pv_info:
        if pv_info["losses"] and pv_info["net_points"] < 0:
            i, p = pv_info["losses"][0]
            risk.append(f"예상 진행에서 {obj(p)} 잃어 기물이 {pv_info['net_points']}점 불리해집니다.")
        elif pv_info["captures"] and pv_info["net_points"] > 0 and not facts.get("capture"):
            i, p = pv_info["captures"][0]
            good.append(f"예상 진행에서 {i // 2 + 1}수 뒤 {obj(p)} 잡아 {pv_info['net_points']}점을 얻습니다.")
        elif pv_info["captures"] and pv_info["losses"] and pv_info["net_points"] == 0 and not facts.get("capture"):
            good.append("예상 진행은 기물 교환으로 이어지며 손익은 없습니다.")
    if not good:
        # quiet move: describe what kind of move it is and the small positional gain, if any
        ed_pos = max(ed.items(), key=lambda kv: kv[1]) if ed else None
        if facts.get("pawn_advance"):
            good.append("졸을 한 칸 전진해 공간을 넓힙니다.")
        elif facts.get("pawn_sideways"):
            good.append("졸을 옆으로 옮겨 진형을 다듬습니다." if (ed.get("pawn_structure", 0) <= 0) else "졸을 옆으로 옮겨 졸들이 서로 지키게 합니다.")
        elif facts.get("king_or_guard"):
            good.append(f"{obj(facts['piece'])} 옮겨 궁성 배치를 정비합니다.")
        elif facts.get("from_back_rank"):
            good.append(f"{obj(facts['piece'])} 전개합니다.")
        else:
            good.append(f"{obj(facts['piece'])} 재배치하는 조용한 수입니다.")
        if ed_pos and ed_pos[1] >= 6:
            names = {"pst": "기물 위치", "pawn_structure": "졸 구조", "king_safety": "궁 안전", "mobility": "기동력"}
            good[-1] += f" {names[ed_pos[0]]} 평가가 조금 좋아집니다(+{ed_pos[1] / 100:.2f})."
    return good, risk


def explain_move(pos: Position, md: dict, best: dict | None = None) -> dict:
    """Facts and sentences for one analysed move of ``pos``."""
    m = str_to_move(md["move"])
    facts = move_facts(pos, m)
    pv_info = pv_material(pos, md.get("pv") or []) if md.get("pv") else None
    good, risk = sentences_for(facts, md, pv_info)
    head = f"{md['notation']} — 승률 {_pct(md.get('estimated_winrate'))}, 평가 {_score_txt(md)}"
    if best is not None and best["move"] != md["move"] and best.get("estimated_winrate") is not None \
            and md.get("estimated_winrate") is not None:
        gap = (best["estimated_winrate"] - md["estimated_winrate"]) * 100
        head += " (최선수와 차이 없음)" if gap < 0.05 else f" (최선수보다 {gap:.1f}%p 낮음)"
    elif best is not None and best["move"] == md["move"]:
        head += " (최선수)"
    if pv_info:
        pv_info = {k: v for k, v in pv_info.items() if k != "end"}
    plan = ""
    pretty = md.get("pv_pretty") or []
    if len(pretty) > 1:
        plan = "예상 진행: " + " ".join(pretty[1:5])
        if pv_info and pv_info.get("net_points"):
            plan += f" (기물 {pv_info['net_points']:+d}점)"
    return {"move": md["move"], "notation": md["notation"], "rank": md.get("rank"), "head": head,
            "good": good, "risk": risk, "plan": plan, "facts": {k: v for k, v in facts.items() if k not in ("ptype",)},
            "pv_material": pv_info}


def explain_top(pos: Position, analysis: dict, top_n: int = 5) -> list[dict]:
    moves = analysis.get("moves") or []
    best = moves[0] if moves else None
    return [explain_move(pos, md, best) for md in moves[:top_n] if md.get("score_cp") is not None]


# --------------------------------------------------------------------------- briefing of the played move
def briefing(prev_pos: Position, played: str, analysis: dict) -> dict:
    """Assess the move that was played from ``prev_pos`` using the analysis of that position."""
    moves = analysis.get("moves") or []
    by = {md["move"]: md for md in moves}
    md = by.get(played)
    best = moves[0] if moves else None
    mover = SIDE_KOREAN[prev_pos.side]
    out: dict = {"move": played, "side": mover, "notation": describe_move(prev_pos.board, str_to_move(played)),
                 "rank": md.get("rank") if md else None, "total": len(moves),
                 "winrate": md.get("estimated_winrate") if md else None,
                 "score": md.get("score") if md else None,
                 "best": None, "verdict": "", "good": [], "risk": [], "better": []}
    if md is None:
        out["verdict"] = "이 수에 대한 분석 결과가 없습니다."
        return out
    ex = explain_move(prev_pos, md, best)
    out["good"], out["risk"], out["facts"], out["plan"] = ex["good"], ex["risk"], ex["facts"], ex["plan"]
    pretty = md.get("pv_pretty") or []
    if len(pretty) > 1:
        out["expected_reply"] = pretty[1]
    if best is not None and best["move"] != played:
        gap = None
        if best.get("estimated_winrate") is not None and md.get("estimated_winrate") is not None:
            gap = (best["estimated_winrate"] - md["estimated_winrate"]) * 100
        bx = explain_move(prev_pos, best, best)
        out["best"] = {"move": best["move"], "notation": best["notation"], "winrate": best.get("estimated_winrate"),
                       "score": best.get("score"), "gap_pct": gap, "good": bx["good"], "pv": best.get("pv", [])[:6]}
        if gap is None:
            out["verdict"] = f"{len(moves)}개 중 {md['rank']}위 수입니다."
        elif gap < 1.0:
            out["verdict"] = f"최선수와 거의 같은 수입니다({len(moves)}개 중 {md['rank']}위, 차이 {gap:.1f}%p)."
        elif gap < 4.0:
            out["verdict"] = f"괜찮은 수지만 더 나은 수가 있었습니다({md['rank']}위, 최선수와 {gap:.1f}%p 차이)."
        elif gap < 12.0:
            out["verdict"] = f"실수입니다: 최선수보다 승률이 {gap:.1f}%p 낮습니다({md['rank']}위)."
        else:
            out["verdict"] = f"큰 실수입니다: 최선수보다 승률이 {gap:.1f}%p 낮습니다({md['rank']}위)."
        out["better"] = [f"엔진은 {obj(best['notation'])} 권합니다: " + " ".join(bx["good"][:2])]
        # second alternative when it is close to the best
        if len(moves) > 1 and moves[1]["move"] != played and moves[1].get("estimated_winrate") is not None \
                and best.get("estimated_winrate") is not None \
                and best["estimated_winrate"] - moves[1]["estimated_winrate"] < 0.02:
            out["better"].append(f"{moves[1]['notation']}도 비슷하게 좋습니다.")
    else:
        out["verdict"] = f"최선수입니다({len(moves)}개 중 1위)."
    return out

#!/usr/bin/env python3
"""Janggi AI command line.

    python cli.py show      [--fen FEN | --moves 79-78 41-42 ...]
    python cli.py moves     [...]                 legal moves (with 장군/capture flags)
    python cli.py eval      [...]                 evaluation breakdown
    python cli.py analyze   [...] [--time 2000] [--depth N] [--top 10]   all legal moves + est. win rate
    python cli.py explain   [...] [--time 1500] [--top 5]  상위 수의 이유 + (--moves 시) 마지막 수 브리핑
    python cli.py bestmove  [...] [--time 2000] [--depth N]
    python cli.py perft     [...] --depth 3
    python cli.py hash      [...]                 Zobrist key, incremental vs recomputed
    python cli.py play      [--time 1000] [--plies 60]   engine self-play from the position
    python cli.py fit-calibration data/results.jsonl     fit K from (score_cp, result) samples
"""
from __future__ import annotations

import argparse
import json
import sys
import time

from engine.analysis import Analyzer, format_analysis
from engine.board import PASS_MOVE, Position
from engine.calibration import Calibrator
from engine.evaluation import evaluate_breakdown, format_breakdown
from engine.explain import briefing, explain_top
from engine.hashing import compute_hash
from engine.movegen import generate_legal_moves, gives_check, perft
from engine.notation import SIDE_KOREAN, describe_move, move_to_str
from engine.rules import RuleConfig, game_status, material_points, replay
from engine.search import SearchConfig, Searcher, mate_in


def position_from_args(args) -> Position:
    rules = rules_from_args(args)
    if getattr(args, "moves", None):
        return replay(args.fen, list(args.moves), rules)
    if args.fen:
        return rules.apply(Position.from_fen(args.fen))
    return rules.apply(Position.initial(args.cho, args.han))


def rules_from_args(args) -> RuleConfig:
    return RuleConfig(bikjang=getattr(args, "bikjang", "draw"), repetition=getattr(args, "repetition", "draw"))


def header(pos: Position, rules: RuleConfig = RuleConfig()) -> str:
    st = game_status(pos, rules=rules)
    mp = material_points(pos)
    lines = [pos.ascii(), "",
             f"차례: {SIDE_KOREAN[pos.side]}   ply {pos.ply}   상태: {st.status}"
             + (f"  승자 {st.winner}" if st.winner is not None else "")
             + ("  장군" if st.in_check else "") + ("  빅장" if st.bikjang else ""),
             f"기물 점수  초 {mp['cho']}  한 {mp['han']}   FEN: {pos.to_fen()}"]
    return "\n".join(lines)


def cmd_show(args):
    print(header(position_from_args(args), rules_from_args(args)))


def cmd_moves(args):
    pos = position_from_args(args)
    print(header(pos))
    legal = generate_legal_moves(pos)
    print(f"\n합법수 {len(legal)}개:")
    for m in legal:
        flags = []
        if m != PASS_MOVE and pos.board[m % 90]:
            flags.append("잡음")
        if gives_check(pos, m):
            flags.append("장군")
        print(f"  {move_to_str(m):<7} {describe_move(pos.board, m):<12} {' '.join(flags)}")


def cmd_eval(args):
    pos = position_from_args(args)
    print(header(pos))
    print(f"\n평가 ({SIDE_KOREAN[pos.side]} 기준, 단위 점):")
    print(format_breakdown(evaluate_breakdown(pos)))


def cmd_analyze(args):
    pos = position_from_args(args)
    print(header(pos))
    print()
    analyzer = Analyzer(SearchConfig(rules=rules_from_args(args)), calibrator=load_calibrator(args))
    last = [0.0]

    def progress(result, final):
        if final or args.verbose:
            return
        now = time.perf_counter()
        if now - last[0] > 0.5:
            last[0] = now
            top = result["moves"][0] if result["moves"] else None
            if top and top["score_cp"] is not None:
                print(f"  ... depth {result['depth']}  nodes {result['nodes']:,}  best {top['notation']} "
                      f"{top['estimated_winrate'] * 100:.1f}%", file=sys.stderr)

    res = analyzer.analyze(pos, time_limit_ms=args.time or None, max_depth=args.depth,
                           progress=progress if not args.json else None)
    if args.json:
        print(json.dumps(res, ensure_ascii=False, indent=1))
        return
    print(format_analysis(res, top_n=args.top or None))
    tt = res["tt"]
    print(f"\nTT: {tt['entries']:,} entries, {tt['probes']:,} probes, hit rate {tt['hit_rate'] * 100:.1f}%,"
          f" {tt['cutoffs']:,} cutoffs   qnodes {res['qnodes']:,}   seldepth {res['seldepth']}")
    print(f"승률 모델: {res['calibration']['source']}  (label: {res['calibration']['label']})")


def cmd_explain(args):
    """Top-N moves of the position with reasons, and (with --moves) a briefing of the last move."""
    rules = rules_from_args(args)
    pos = position_from_args(args)
    print(header(pos, rules))
    analyzer = Analyzer(SearchConfig(rules=rules), calibrator=load_calibrator(args))
    res = analyzer.analyze(pos, time_limit_ms=args.time or None, max_depth=args.depth)
    print(f"\n왜 좋은 수인가 — 상위 {args.top} ({SIDE_KOREAN[pos.side]} 차례, depth {res['depth']})")
    for ex in explain_top(pos, res, args.top):
        print(f"\n{ex['rank']}. {ex['head']}")
        for g in ex["good"]:
            print(f"   + {g}")
        for r in ex["risk"]:
            print(f"   - {r}")
        if ex["plan"]:
            print(f"   → {ex['plan']}")
    if getattr(args, "moves", None):
        prev_args = argparse.Namespace(**vars(args))
        prev_args.moves = list(args.moves[:-1])
        prev = position_from_args(prev_args)
        played = args.moves[-1]
        prev_res = analyzer.analyze(prev, time_limit_ms=args.time or None, max_depth=args.depth)
        b = briefing(prev, played, prev_res)
        print(f"\n브리핑 — {b['side']} {b['notation']}: {b['verdict']}")
        for g in b["good"]:
            print(f"   + {g}")
        for r in b["risk"]:
            print(f"   - {r}")
        if b.get("expected_reply"):
            print(f"   → 상대의 예상 응수: {b['expected_reply']}")
        for t in b["better"]:
            print(f"   → {t}")


def cmd_bestmove(args):
    pos = position_from_args(args)
    s = Searcher(SearchConfig(rules=rules_from_args(args)))

    def on_iter(depth, score, move, pv, nodes):
        m = mate_in(score)
        sc = f"M{m}" if m else f"{score / 100:+.2f}"
        print(f"depth {depth:>2}  {sc:>7}  nodes {nodes:>9,}  pv {' '.join(move_to_str(x) for x in pv[:8])}")

    res = s.search(pos, max_depth=args.depth, time_limit_ms=args.time or None, on_iteration=on_iter)
    print(f"\nbestmove {move_to_str(res.best_move)}  ({describe_move(pos.board, res.best_move)})  "
          f"{res.nodes:,} nodes in {res.time_ms:.0f} ms = {res.nps:,.0f} nps")


def cmd_perft(args):
    pos = position_from_args(args)
    for d in range(1, args.depth + 1):
        t0 = time.perf_counter()
        n = perft(pos, d, include_pass=args.include_pass)
        dt = time.perf_counter() - t0
        print(f"perft({d}) = {n:>12,}   {dt * 1000:8.1f} ms   {n / dt if dt else 0:12,.0f} nodes/s")


def cmd_hash(args):
    pos = position_from_args(args)
    full = compute_hash(pos.board, pos.side)
    print(f"incremental : {pos.hash:016x}")
    print(f"recomputed  : {full:016x}   {'OK' if full == pos.hash else 'MISMATCH'}")
    print(f"history len : {len(pos.history)}   repetitions of current: {pos.repetition_count()}")


def cmd_play(args):
    pos = position_from_args(args)
    rules = rules_from_args(args)
    s = Searcher(SearchConfig(rules=rules))
    moves = []
    for _ in range(args.plies):
        st = game_status(pos, rules=rules)
        if st.is_over:
            break
        res = s.search(pos, max_depth=args.depth, time_limit_ms=args.time or None)
        if res.best_move == 0:
            break
        txt = describe_move(pos.board, res.best_move)
        pos.make_move(res.best_move)
        moves.append(move_to_str(res.best_move))
        print(f"{len(moves):>3}. {txt:<12} {res.score / 100:+7.2f}  d{res.depth} {res.nodes:>8,}n")
    print(header(pos, rules))
    print("moves:", " ".join(moves))


def cmd_fit(args):
    samples = []
    with open(args.file, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            samples.append((float(d["score_cp"]), float(d["result"])))
    c = Calibrator()
    k = c.fit(samples)
    c.save(args.out)
    print(f"fitted K = {k:.1f} from {len(samples)} samples -> {args.out}")


def load_calibrator(args) -> Calibrator:
    if getattr(args, "calibration", None):
        return Calibrator.load(args.calibration)
    return Calibrator()


def main(argv=None):
    p = argparse.ArgumentParser(description="Janggi AI CLI")
    sub = p.add_subparsers(dest="cmd", required=True)

    def common(sp, with_moves=True):
        sp.add_argument("--fen", default=None)
        sp.add_argument("--cho", default="마상상마", help="초 포진 (마상상마|상마상마|마상마상|상마마상)")
        sp.add_argument("--han", default="마상상마", help="한 포진")
        sp.add_argument("--bikjang", default="draw", choices=["draw", "points", "forced", "off"],
                        help="빅장 처리: 무승부 | 점수 판정 | 반드시 풀기(Fairy-Stockfish 방식) | 없음")
        sp.add_argument("--repetition", default="draw", choices=["draw", "off"], help="동형 반복 3회: 무승부 | 무시")
        if with_moves:
            sp.add_argument("--moves", nargs="*", default=None, help="시작 위치부터 둔 수 (예: 79-78 41-42)")
        return sp

    common(sub.add_parser("show")).set_defaults(fn=cmd_show)
    common(sub.add_parser("moves")).set_defaults(fn=cmd_moves)
    common(sub.add_parser("eval")).set_defaults(fn=cmd_eval)
    hp = common(sub.add_parser("hash"))
    hp.set_defaults(fn=cmd_hash)

    ap = common(sub.add_parser("analyze"))
    ap.add_argument("--time", type=float, default=2000, help="ms (0 = 무제한, --depth로 제한)")
    ap.add_argument("--depth", type=int, default=64)
    ap.add_argument("--top", type=int, default=0, help="표시할 수 (0 = 전부)")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--verbose", action="store_true")
    ap.add_argument("--calibration", default=None, help="calibration json path")
    ap.set_defaults(fn=cmd_analyze)

    xp = common(sub.add_parser("explain"))
    xp.add_argument("--time", type=float, default=1500)
    xp.add_argument("--depth", type=int, default=64)
    xp.add_argument("--top", type=int, default=5)
    xp.add_argument("--calibration", default=None)
    xp.set_defaults(fn=cmd_explain)

    bp = common(sub.add_parser("bestmove"))
    bp.add_argument("--time", type=float, default=2000)
    bp.add_argument("--depth", type=int, default=64)
    bp.set_defaults(fn=cmd_bestmove)

    pp = common(sub.add_parser("perft"))
    pp.add_argument("--depth", type=int, default=3)
    pp.add_argument("--include-pass", action="store_true")
    pp.set_defaults(fn=cmd_perft)

    gp = common(sub.add_parser("play"))
    gp.add_argument("--time", type=float, default=500)
    gp.add_argument("--depth", type=int, default=64)
    gp.add_argument("--plies", type=int, default=40)
    gp.set_defaults(fn=cmd_play)

    fp = sub.add_parser("fit-calibration")
    fp.add_argument("file", help="jsonl with {\"score_cp\": .., \"result\": 1|0.5|0}")
    fp.add_argument("--out", default="data/calibration.json")
    fp.set_defaults(fn=cmd_fit)

    args = p.parse_args(argv)
    args.fn(args)


if __name__ == "__main__":
    main()

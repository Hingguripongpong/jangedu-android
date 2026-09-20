#!/usr/bin/env python3
"""Search benchmark.

    python scripts/bench.py                # 3 s per position, default config
    python scripts/bench.py --time 5000 --ablation

Reports nodes/sec, completed depth, seldepth and TT hit rate per position, plus
an ablation table (each optimisation switched off on its own) with --ablation.
Results are also written as JSON to data/bench_<timestamp>.json.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from dataclasses import replace

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from engine.analysis import Analyzer  # noqa: E402
from engine.board import Position  # noqa: E402
from engine.movegen import perft  # noqa: E402
from engine.search import SearchConfig, Searcher  # noqa: E402

POSITIONS = {
    "start": Position.initial().to_fen(),
    "start_상마상마": Position.initial("상마상마", "상마상마").to_fen(),
    "middlegame": "r1ba1ab1r/4k1n2/1c2n2c1/p3p1p1p/2p6/6P2/P1P1P3P/1C2NC3/4K4/RNBA1AB1R w - - 0 1",
    "open_files": "rnba1abnr/4k4/1c5c1/p1p3p1p/4p4/4P4/P1P3P1P/1C5C1/4K4/RNBA1ABNR w - - 0 5",
    "tactical": "3ka4/4a4/4b4/p8/2R1n4/4C4/P8/4B4/3KA4/1c2r4 w - - 0 1",
}


def bench_search(fen: str, cfg: SearchConfig, time_ms: float) -> dict:
    pos = Position.from_fen(fen)
    s = Searcher(cfg)
    res = s.search(pos, max_depth=64, time_limit_ms=time_ms)
    tt = s.tt_stats()
    return {"depth": res.depth, "seldepth": s.seldepth, "nodes": res.nodes, "time_ms": round(res.time_ms),
            "nps": round(res.nps), "tt_hit_rate": round(tt["hit_rate"], 3), "score": res.score}


def bench_analysis(fen: str, time_ms: float) -> dict:
    pos = Position.from_fen(fen)
    res = Analyzer().analyze(pos, time_limit_ms=time_ms)
    return {"depth": res["depth"], "seldepth": res["seldepth"], "nodes": res["nodes"], "nps": res["nps"],
            "moves": len(res["moves"]), "tt_hit_rate": round(res["tt"]["hit_rate"], 3),
            "time_ms": round(res["time_ms"])}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--time", type=float, default=3000, help="ms per position")
    ap.add_argument("--ablation", action="store_true")
    ap.add_argument("--ablation-depth", type=int, default=4)
    ap.add_argument("--perft", action="store_true", help="also time perft(3) from the start position")
    args = ap.parse_args()

    out: dict = {"time_ms": args.time, "python": sys.version.split()[0], "results": {}}
    print(f"{'position':<16}{'depth':>6}{'sel':>5}{'nodes':>12}{'nps':>10}{'TT hit':>8}   analyze: depth nodes nps")
    for name, fen in POSITIONS.items():
        r = bench_search(fen, SearchConfig(), args.time)
        a = bench_analysis(fen, args.time)
        out["results"][name] = {"search": r, "analysis": a}
        print(f"{name:<16}{r['depth']:>6}{r['seldepth']:>5}{r['nodes']:>12,}{r['nps']:>10,}{r['tt_hit_rate'] * 100:>7.1f}%"
              f"   {a['depth']:>5} {a['nodes']:>9,} {a['nps']:>7,}")

    if args.perft:
        t0 = time.perf_counter()
        n = perft(Position.initial(), 3)
        dt = time.perf_counter() - t0
        out["perft3"] = {"nodes": n, "time_ms": round(dt * 1000), "nps": round(n / dt)}
        print(f"\nperft(3) = {n:,} in {dt * 1000:.0f} ms ({n / dt:,.0f} nodes/s)")

    if args.ablation:
        fen = POSITIONS["middlegame"]
        base = SearchConfig()
        print(f"\nablation on 'middlegame' to fixed depth {args.ablation_depth}: nodes / time / nps"
              f" (score must agree with the baseline except for qsearch)")
        rows = {"baseline": base}
        for flag in ("alpha_beta", "tt", "pvs", "killers", "history", "qsearch", "check_extension", "aspiration",
                     "null_move", "lmr"):
            rows[f"no_{flag}"] = replace(base, **{flag: False})
        out["ablation"] = {}
        for name, cfg in rows.items():
            pos = Position.from_fen(fen)
            s = Searcher(cfg)
            res = s.search(pos, max_depth=args.ablation_depth)
            r = {"nodes": res.nodes, "time_ms": round(res.time_ms), "nps": round(res.nps), "score": res.score,
                 "tt_hit_rate": round(s.tt_stats()["hit_rate"], 3)}
            out["ablation"][name] = r
            print(f"  {name:<20} {r['nodes']:>10,} nodes {r['time_ms']:>7,} ms {r['nps']:>8,} nps  score {r['score'] / 100:+.2f}")

    os.makedirs("data", exist_ok=True)
    path = os.path.join("data", f"bench_{time.strftime('%Y%m%d_%H%M%S')}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, indent=1, ensure_ascii=False)
    print(f"\nsaved {path}")


if __name__ == "__main__":
    main()

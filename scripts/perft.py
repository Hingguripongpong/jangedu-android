#!/usr/bin/env python3
"""Perft / divide for the move generator.

    python scripts/perft.py --depth 3                 counts from the start position
    python scripts/perft.py --depth 3 --divide        per-root-move counts (for diffing against another engine)
    python scripts/perft.py --fen "<FEN>" --depth 2 --include-pass
"""
from __future__ import annotations

import argparse
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from engine.board import Position  # noqa: E402
from engine.movegen import generate_legal_moves, perft  # noqa: E402
from engine.notation import move_to_str  # noqa: E402

REFERENCE = {1: 31, 2: 961, 3: 30353}  # start position, 마상상마 both sides, no 한수쉼


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fen", default=None)
    ap.add_argument("--depth", type=int, default=3)
    ap.add_argument("--divide", action="store_true")
    ap.add_argument("--include-pass", action="store_true")
    args = ap.parse_args()
    pos = Position.from_fen(args.fen) if args.fen else Position.initial()

    if args.divide:
        total = 0
        t0 = time.perf_counter()
        for m in generate_legal_moves(pos, include_pass=args.include_pass):
            pos.make_move(m)
            n = perft(pos, args.depth - 1, args.include_pass) if args.depth > 1 else 1
            pos.unmake_move()
            total += n
            print(f"{move_to_str(m):<7}{n:>12,}")
        dt = time.perf_counter() - t0
        print(f"total  {total:>12,}   {dt * 1000:.0f} ms")
        return

    for d in range(1, args.depth + 1):
        t0 = time.perf_counter()
        n = perft(pos, d, args.include_pass)
        dt = time.perf_counter() - t0
        ref = REFERENCE.get(d) if args.fen is None and not args.include_pass else None
        mark = "" if ref is None else ("  OK" if ref == n else f"  MISMATCH (expected {ref:,})")
        print(f"perft({d}) = {n:>12,}   {dt * 1000:8.1f} ms   {n / dt if dt else 0:12,.0f} nodes/s{mark}")


if __name__ == "__main__":
    main()

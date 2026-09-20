#!/usr/bin/env python3
"""Download the Fairy-Stockfish largeboard build for this machine into engines/.

    python scripts/get_fairy_stockfish.py                 # generic x86-64 build (runs everywhere)
    python scripts/get_fairy_stockfish.py --build modern  # POPCNT/AVX2 CPUs (faster)
    python scripts/get_fairy_stockfish.py --build bmi2    # recent Intel/AMD Zen3+ (fastest)
    python scripts/get_fairy_stockfish.py --tag fairy_sf_14

Only x86-64 Windows/Linux binaries are published; on macOS or ARM build from source
(https://github.com/fairy-stockfish/Fairy-Stockfish, `make -j build ARCH=... largeboards=yes`)
and put the binary into engines/ or point JANGGI_FSF_PATH at it.
"""
from __future__ import annotations

import argparse
import os
import platform
import stat
import subprocess
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENGINES = os.path.join(ROOT, "engines")
REPO = "https://github.com/fairy-stockfish/Fairy-Stockfish/releases"


def asset_name(build: str) -> str:
    suffix = "" if build == "generic" else f"-{build}"
    ext = ".exe" if platform.system() == "Windows" else ""
    return f"fairy-stockfish-largeboard_x86-64{suffix}{ext}"


def resolve_latest_tag() -> str:
    req = urllib.request.Request(f"{REPO}/latest", method="HEAD")
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.geturl().rstrip("/").rsplit("/", 1)[-1]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--build", choices=["generic", "modern", "bmi2"], default="generic")
    ap.add_argument("--tag", default=None, help="release tag (default: latest)")
    ap.add_argument("--dest", default=ENGINES)
    args = ap.parse_args()
    if platform.machine().lower() not in ("x86_64", "amd64"):
        print(f"no prebuilt binary for {platform.machine()}; build from source (see docstring)")
        return 1
    tag = args.tag or resolve_latest_tag()
    name = asset_name(args.build)
    url = f"{REPO}/download/{tag}/{name}"
    os.makedirs(args.dest, exist_ok=True)
    dest = os.path.join(args.dest, name)
    print(f"downloading {url}")

    shown = [-1]

    def hook(blocks, bsize, total):
        if total > 0:
            done = min(blocks * bsize, total)
            step = int(done * 10 // total)
            if step != shown[0]:
                shown[0] = step
                sys.stdout.write(f"\r  {done / 1e6:5.1f} / {total / 1e6:5.1f} MB")
                sys.stdout.flush()

    urllib.request.urlretrieve(url, dest, hook)
    print()
    if platform.system() != "Windows":
        os.chmod(dest, os.stat(dest).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    # smoke test: the binary must answer "uci" and know the janggi variant
    try:
        out = subprocess.run([dest], input="uci\nquit\n", capture_output=True, text=True, timeout=30).stdout
    except OSError as exc:
        print(f"downloaded but cannot run it ({exc}); try --build generic")
        return 1
    if "janggi" not in out:
        print("downloaded binary does not list the janggi variant — is it a largeboard build?")
        return 1
    ident = next((l for l in out.splitlines() if l.startswith("id name")), "id name ?")
    print(f"OK: {ident[8:]} -> {dest}")
    print("서버를 재시작하면 UI의 '엔진' 선택에 Fairy-Stockfish가 나타납니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

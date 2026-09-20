#!/usr/bin/env bash
# Builds libjanggi_engine.so for the *host* (Linux x86-64) with the same sources/flags the
# Android CMake build uses, so the JNI host can be exercised with a JDK before touching a
# device.  Requires g++ >= 9 and a JDK (JAVA_HOME or `java` on PATH).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CPP="$HERE/../../engine/src/main/cpp"
OUT="${1:-$HERE/build}"
mkdir -p "$OUT/obj"
JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
JNI_INC="${JNI_INCLUDE:-$JAVA_HOME/include}"   # override JNI_INCLUDE when only a JRE is installed
CXX="${CXX:-g++}"
COMMON="-std=c++17 -O2 -fPIC -DLARGEBOARDS -DPRECOMPUTED_MAGICS -DNNUE_EMBEDDING_OFF -DUSE_PTHREADS -DNDEBUG -DIS_64BIT -fvisibility=hidden -I$CPP/fairy-stockfish -I$CPP"
FSF="benchmark bitbase bitboard endgame evaluate material misc movegen movepick pawns position psqt search thread timeman tt uci ucioption tune syzygy/tbprobe nnue/evaluate_nnue nnue/features/half_ka_v2 nnue/features/half_ka_v2_variants partner parser piece variant xboard"
objs=()
for f in $FSF; do
  o="$OUT/obj/$(echo "$f" | tr '/' '_').o"
  if [ ! -f "$o" ] || [ "$CPP/fairy-stockfish/$f.cpp" -nt "$o" ]; then
    echo "  CXX $f.cpp"; $CXX $COMMON -fno-exceptions -c "$CPP/fairy-stockfish/$f.cpp" -o "$o"
  fi
  objs+=("$o")
done
for f in engine_host janggi_engine_jni; do
  o="$OUT/obj/$f.o"
  echo "  CXX $f.cpp"; $CXX $COMMON -I"$JNI_INC" -I"$JNI_INC/linux" -c "$CPP/$f.cpp" -o "$o"
  objs+=("$o")
done
$CXX -shared -o "$OUT/libjanggi_engine.so" "${objs[@]}" -lpthread
echo "built $OUT/libjanggi_engine.so"

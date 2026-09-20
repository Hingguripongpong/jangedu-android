#!/usr/bin/env bash
# Re-vendors the pinned Fairy-Stockfish sources into android/engine/src/main/cpp/fairy-stockfish.
# Reads the pin from android/engine/fsf.properties, downloads the tag tarball, VERIFIES its SHA-256,
# and copies src/ (minus the Python/JS bindings).  Fails on any mismatch - no "latest" anywhere.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN="$ROOT/android/engine/fsf.properties"
DEST="$ROOT/android/engine/src/main/cpp/fairy-stockfish"

prop() { grep -E "^$1=" "$PIN" | cut -d= -f2- | tr -d '\r'; }
VERSION="$(prop FSF_VERSION)"; COMMIT="$(prop FSF_COMMIT)"; URL="$(prop FSF_TARBALL_URL)"; SHA="$(prop FSF_TARBALL_SHA256)"
[ -n "$VERSION" ] && [ -n "$COMMIT" ] && [ -n "$URL" ] && [ -n "$SHA" ] || { echo "incomplete pin file $PIN" >&2; exit 1; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
echo "downloading Fairy-Stockfish $VERSION ($COMMIT)"
curl -fsSL "$URL" -o "$TMP/fsf.tar.gz"
ACTUAL="$(sha256sum "$TMP/fsf.tar.gz" | cut -d' ' -f1)"
if [ "$ACTUAL" != "$SHA" ]; then echo "SHA-256 mismatch: expected $SHA got $ACTUAL" >&2; exit 1; fi
tar -xzf "$TMP/fsf.tar.gz" -C "$TMP"
SRC="$(find "$TMP" -maxdepth 1 -type d -name 'Fairy-Stockfish-*' | head -1)/src"
[ -f "$SRC/uci.cpp" ] || { echo "unexpected tarball layout" >&2; exit 1; }

rm -rf "$DEST"; mkdir -p "$DEST"
cp -r "$SRC"/*.cpp "$SRC"/*.h "$SRC/nnue" "$SRC/syzygy" "$SRC/incbin" "$DEST"/
cp "$(dirname "$SRC")/Copying.txt" "$(dirname "$SRC")/AUTHORS" "$DEST"/
rm -f "$DEST/pyffish.cpp" "$DEST/ffishjs.cpp"
cp "$DEST/Copying.txt" "$ROOT/android/licenses/GPL-3.0.txt"
cp "$DEST/Copying.txt" "$ROOT/android/app/src/main/assets/licenses/GPL-3.0.txt"
echo "vendored $(find "$DEST" -name '*.cpp' | wc -l) .cpp files into $DEST"
echo "verify: bash android/tools/desktop-jni-test/build.sh && bash android/tools/desktop-jni-test/run.sh"

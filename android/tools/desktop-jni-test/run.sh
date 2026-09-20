#!/usr/bin/env bash
# Compiles FairyStockfishNative.kt + the smoke test with kotlinc and runs it against build/libjanggi_engine.so.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
"$KOTLINC" "$HERE/../../engine/src/main/kotlin/com/example/janggiai/engine/fsf/FairyStockfishNative.kt" "$HERE/DesktopJniSmokeTest.kt" -include-runtime -d "$HERE/build/smoke.jar"
java -Djava.library.path="$HERE/build" -cp "$HERE/build/smoke.jar" DesktopJniSmokeTestKt

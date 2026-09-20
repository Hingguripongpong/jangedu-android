#!/usr/bin/env bash
# Runs the shipped Kotlin engine (game + engine modules) against build/libjanggi_engine.so.
# Needs kotlinc (KOTLINC) and kotlinx-coroutines-core-jvm.jar (COROUTINES_JAR; kotlinc/lib ships one).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
A="$HERE/../.."
KOTLINC="${KOTLINC:-kotlinc}"
COROUTINES_JAR="${COROUTINES_JAR:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib/kotlinx-coroutines-core-jvm.jar}"
[ -f "$COROUTINES_JAR" ] || { echo "set COROUTINES_JAR to kotlinx-coroutines-core-jvm.jar" >&2; exit 1; }
"$KOTLINC" -cp "$COROUTINES_JAR" "$A"/game/src/main/kotlin/com/example/janggiai/game/*.kt $(find "$A/engine/src/main/kotlin" -name '*.kt') \
    "$HERE/DesktopEngineApiTest.kt" -include-runtime -d "$HERE/build/api-test.jar" 2>&1 | grep -v '^warning' || true
java -Djava.library.path="$HERE/build" -cp "$HERE/build/api-test.jar:$COROUTINES_JAR" DesktopEngineApiTestKt

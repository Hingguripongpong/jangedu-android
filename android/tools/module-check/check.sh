#!/usr/bin/env bash
# Offline regression check for Gradle *module boundaries* (no Android SDK needed).
#
# Gradle compiles :game, :engine and :app as separate modules. Kotlin then refuses smart casts on nullable public
# properties declared in another module ("Smart cast to 'X' is impossible, because 'p' is a public API property
# declared in different module"). A single kotlinc run over all sources hides that, so this script compiles each
# module against the previous module's jar exactly like Gradle does, and runs the JVM unit tests with real JUnit.
#
# Needs: kotlinc (KOTLINC), kotlinx-coroutines-core-jvm.jar (COROUTINES_JAR, kotlinc/lib ships one),
#        junit-4.13.2.jar + hamcrest-core-1.3.jar (JUNIT_CP).  The Compose UI layer is not covered here (needs the
#        Android SDK); it is compiled by `gradlew :app:testDebugUnitTest`.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; A="$HERE/../.."
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib"
COROUTINES_JAR="${COROUTINES_JAR:-$KLIB/kotlinx-coroutines-core-jvm.jar}"
JUNIT_CP="${JUNIT_CP:?set JUNIT_CP=junit-4.13.2.jar:hamcrest-core-1.3.jar}"
OUT="${OUT:-$HERE/build}"; rm -rf "$OUT"; mkdir -p "$OUT"
kc() {  # compile; on failure print the compiler errors and stop at this step (later steps would only cascade)
  local log; log="$(mktemp)"
  if ! "$KOTLINC" -nowarn "$@" >"$log" 2>&1; then grep -v '^warning' "$log" || true; rm -f "$log"; echo "FAILED at: $STEP"; exit 1; fi
  rm -f "$log"
}

STEP="[1/5] :game  -> game.jar"; echo "$STEP"
kc "$A"/game/src/main/kotlin/com/example/janggiai/game/*.kt -d "$OUT/game.jar"
STEP="[2/5] :engine (against game.jar) -> engine.jar"; echo "$STEP"
kc -cp "$OUT/game.jar:$COROUTINES_JAR" $(find "$A/engine/src/main/kotlin" -name '*.kt') -d "$OUT/engine.jar"
STEP="[3/5] :app logic (game/*.kt + coach/*.kt, against game.jar + engine.jar)"; echo "$STEP"
kc -cp "$OUT/game.jar:$OUT/engine.jar:$COROUTINES_JAR" "$A"/app/src/main/kotlin/com/example/janggiai/game/*.kt "$A"/app/src/main/kotlin/com/example/janggiai/coach/*.kt -d "$OUT/app-logic"
STEP="[4/5] unit tests of all three modules (against the jars)"; echo "$STEP"
kc -cp "$OUT/game.jar:$JUNIT_CP" "$A"/game/src/test/kotlin/com/example/janggiai/game/*.kt -d "$OUT/game-test"
kc -cp "$OUT/game.jar:$OUT/engine.jar:$COROUTINES_JAR:$JUNIT_CP" "$A"/engine/src/test/kotlin/com/example/janggiai/engine/*.kt -d "$OUT/engine-test"
# App tests that touch androidx (ViewModel-level tests) need the Android SDK: Gradle runs them; skip them here.
APP_TESTS=""; for f in "$A"/app/src/test/kotlin/com/example/janggiai/*.kt; do
  if grep -q "^import androidx\." "$f"; then echo "   (skipped, needs Android SDK: $(basename "$f"))"; else APP_TESTS="$APP_TESTS $f"; fi
done
kc -cp "$OUT/game.jar:$OUT/engine.jar:$OUT/app-logic:$COROUTINES_JAR:$JUNIT_CP" $APP_TESTS -d "$OUT/app-test"
echo "[5/5] run tests with JUnit (every *Test.kt containing @Test)"
STD="$KLIB/kotlin-stdlib.jar"
classes() { # $1 = dir, $2 = package  (skips androidx-dependent tests, see above)
  for f in "$1"/*.kt; do grep -q "@Test" "$f" && ! grep -q "^import androidx\." "$f" && echo "$2.$(basename "$f" .kt)"; done
}
java -cp "$OUT/game-test:$OUT/game.jar:$STD:$JUNIT_CP" org.junit.runner.JUnitCore $(classes "$A/game/src/test/kotlin/com/example/janggiai/game" com.example.janggiai.game) | grep -E "^OK|^Tests run|FAIL"
( cd "$A/engine" && java -cp "$OUT/engine-test:$OUT/game.jar:$OUT/engine.jar:$STD:$COROUTINES_JAR:$JUNIT_CP" org.junit.runner.JUnitCore \
    $(classes "$A/engine/src/test/kotlin/com/example/janggiai/engine" com.example.janggiai.engine) | grep -E "^OK|^Tests run|FAIL" )
java -cp "$OUT/app-test:$OUT/app-logic:$OUT/game.jar:$OUT/engine.jar:$STD:$COROUTINES_JAR:$JUNIT_CP" org.junit.runner.JUnitCore \
    $(classes "$A/app/src/test/kotlin/com/example/janggiai" com.example.janggiai) | grep -E "^OK|^Tests run|FAIL"
echo "MODULE CHECK PASSED"

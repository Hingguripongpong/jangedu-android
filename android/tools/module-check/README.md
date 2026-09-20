# module-check

Compiles `:game` → `:engine` → `:app` logic **module by module** (each against the previous module's jar), the way
Gradle does, and runs the JVM unit tests with real JUnit 4.13.2. Catches errors that a single-pass compile hides —
in particular Kotlin's "smart cast is impossible ... public API property declared in different module", which broke
the first `gradlew :app:testDebugUnitTest` (AnalysisController.kt:60). Run it after touching any code that uses
types from another module.

```bash
KOTLINC=/path/to/kotlinc/bin/kotlinc \
JUNIT_CP=/path/to/junit-4.13.2.jar:/path/to/hamcrest-core-1.3.jar \
bash tools/module-check/check.sh          # must end with "MODULE CHECK PASSED"
```

The Compose UI layer needs the Android SDK and is only compiled by Gradle (`gradlew :app:testDebugUnitTest`).

# desktop-jni-test

Verifies the native engine host and the Kotlin engine API on a development machine, without the
Android SDK/NDK.  The same C++ sources and flags as the Android CMake build are compiled with g++
into a Linux `libjanggi_engine.so`; the JNI symbols are then called from Kotlin exactly as the app does.

```bash
bash build.sh                      # g++ + JDK headers (JNI_INCLUDE=... if you only have a JRE)
KOTLINC=/path/to/kotlinc bash run.sh       # DesktopJniSmokeTest: 18 checks (uci, perft, MultiPV 32/1, stop, restart)
KOTLINC=/path/to/kotlinc bash run_api.sh   # DesktopEngineApiTest: 18 checks (JanggiEngine API, cancel, legal moves == perft divide)
```

Both must print `ALL ... CHECKS PASSED`.  Run them after changing anything under
`engine/src/main/cpp/` or the pinned Fairy-Stockfish revision.

The same `CMakeLists.txt` that AGP uses can also be built directly with CMake (desktop branch):

```bash
cmake -S ../../engine/src/main/cpp -B build-cmake -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_CXX_FLAGS="-I$JAVA_HOME/include -I$JAVA_HOME/include/linux"
cmake --build build-cmake            # -> build-cmake/libjanggi_engine.so (verified with CMake 3.22.1)
```

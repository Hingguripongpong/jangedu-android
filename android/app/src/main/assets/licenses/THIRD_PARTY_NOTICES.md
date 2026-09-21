# Third-party software notices

This application ("장기 교육") bundles the following third-party software.  Full license texts
are in the `licenses/` directory and are shown inside the app (정보 → 오픈소스 라이선스).

## Fairy-Stockfish — GNU General Public License v3.0 or later

* Component: `libjanggi_engine.so` (native library) — built from the Fairy-Stockfish sources
  vendored **unmodified** under `engine/src/main/cpp/fairy-stockfish/`.
* Version: **fairy_sf_14**
* Commit: `f3e6969d11d1bec17eba26e7ae0e629ad4af71dd`
* Source: https://github.com/fairy-stockfish/Fairy-Stockfish/tree/fairy_sf_14
* Tarball: https://codeload.github.com/fairy-stockfish/Fairy-Stockfish/tar.gz/refs/tags/fairy_sf_14
  SHA-256 `db5e96cf47faf4bfd4a500f58ae86e46fee92c2f5544e78750fc01ad098cbad2`
* Copyright (C) 2018-2022 Fabian Fichter; Fairy-Stockfish is based on Stockfish,
  Copyright (C) 2004-2022 The Stockfish developers (see `AUTHORS` in the vendored directory).
* License: GPL-3.0-or-later — full text in `licenses/GPL-3.0.txt`.
* Modifications: **none** to the engine sources.  `main.cpp` is not compiled; it is replaced by
  `engine/src/main/cpp/engine_host.cpp` (an in-process host that feeds the engine's UCI loop from
  memory) and `janggi_engine_jni.cpp` (JNI bindings).  Both files are part of this app and are
  distributed under the GPL-3.0-or-later as well, because they link with the engine into one binary.
* Build configuration: `LARGEBOARDS`, `PRECOMPUTED_MAGICS`, `NNUE_EMBEDDING_OFF`, `USE_PTHREADS`
  (classical evaluation; no NNUE network file is bundled).  See `engine/src/main/cpp/CMakeLists.txt`.

### GPL compliance and corresponding source

This application, including its Kotlin/Compose application code, JNI bindings,
native engine host, build scripts, and the bundled Fairy-Stockfish engine, is
distributed under the GNU General Public License version 3 or later
(GPL-3.0-or-later).

The complete source code for this application is available at:

https://github.com/Hingguripongpong/jangedu-android

The repository contains the application source, JNI bindings,
`engine_host.cpp`, `janggi_engine_jni.cpp`, CMake/Gradle build configuration,
and the Fairy-Stockfish source used to build `libjanggi_engine.so`.

The full GPL version 3 license text is included with the application in
`licenses/GPL-3.0.txt` and in the source repository as `LICENSE`.

Fairy-Stockfish remains credited to its respective copyright holders as
described above. The program is provided WITHOUT ANY WARRANTY, as described
in the GPL.
## Android / JetBrains libraries — Apache License 2.0

AndroidX (Compose, Material 3, Lifecycle, Navigation, Activity, Core), Kotlin standard library and
kotlinx.coroutines are used under the Apache License, Version 2.0 (`licenses/Apache-2.0.txt`).
They are linked as ordinary Android dependencies and are not modified.

## Test-only dependencies

JUnit 4 (Eclipse Public License 1.0), AndroidX Test / Espresso (Apache-2.0).  Not shipped in the app.

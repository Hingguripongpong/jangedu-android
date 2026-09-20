package com.example.janggiai.engine.fsf

/**
 * The exact Fairy-Stockfish revision compiled into libjanggi_engine.so.  Must stay in sync with
 * `engine/fsf.properties` (verified by FairyStockfishBuildInfoTest) and with the sources vendored
 * under `engine/src/main/cpp/fairy-stockfish/` (refreshed by `scripts/fetch_fairy_stockfish.sh`).
 * No "latest": every build of the app ships a known, reproducible engine.
 */
object FairyStockfishBuildInfo {
    const val NAME = "Fairy-Stockfish"
    const val VERSION = "fairy_sf_14"
    const val COMMIT = "f3e6969d11d1bec17eba26e7ae0e629ad4af71dd"
    const val SOURCE_URL = "https://github.com/fairy-stockfish/Fairy-Stockfish/tree/fairy_sf_14"
    const val TARBALL_URL = "https://codeload.github.com/fairy-stockfish/Fairy-Stockfish/tar.gz/refs/tags/fairy_sf_14"
    const val TARBALL_SHA256 = "db5e96cf47faf4bfd4a500f58ae86e46fee92c2f5544e78750fc01ad098cbad2"
    const val LICENSE = "GNU General Public License v3.0 (or later)"
    const val LICENSE_ID = "GPL-3.0-or-later"
    const val AUTHOR = "Fabian Fichter and contributors; based on Stockfish (the Stockfish developers)"
    const val BUILD_FLAGS = "LARGEBOARDS PRECOMPUTED_MAGICS NNUE_EMBEDDING_OFF USE_PTHREADS (classical evaluation, no NNUE file)"
}

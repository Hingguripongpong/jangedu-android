# libjanggi_engine.so = Fairy-Stockfish (pinned, unmodified sources) + in-process UCI host + JNI.
#
# Flags mirror upstream `make build COMP=ndk ARCH=armv8 largeboards=yes` (see
# fairy-stockfish/Makefile): -DLARGEBOARDS (9x10 boards), -DPRECOMPUTED_MAGICS,
# -DNNUE_EMBEDDING_OFF (no 40 MB chess net; janggi uses classical eval), -DUSE_PTHREADS,
# -DIS_64BIT, and the SIMD/popcnt defines of the target ABI.
cmake_minimum_required(VERSION 3.22.1)
project(janggi_engine CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(FSF_DIR ${CMAKE_CURRENT_SOURCE_DIR}/fairy-stockfish)

# Every upstream translation unit except main.cpp (replaced by engine_host.cpp) and the
# Python/JS bindings (pyffish.cpp / ffishjs.cpp are not vendored).
set(FSF_SOURCES
    ${FSF_DIR}/benchmark.cpp
    ${FSF_DIR}/bitbase.cpp
    ${FSF_DIR}/bitboard.cpp
    ${FSF_DIR}/endgame.cpp
    ${FSF_DIR}/evaluate.cpp
    ${FSF_DIR}/material.cpp
    ${FSF_DIR}/misc.cpp
    ${FSF_DIR}/movegen.cpp
    ${FSF_DIR}/movepick.cpp
    ${FSF_DIR}/pawns.cpp
    ${FSF_DIR}/position.cpp
    ${FSF_DIR}/psqt.cpp
    ${FSF_DIR}/search.cpp
    ${FSF_DIR}/thread.cpp
    ${FSF_DIR}/timeman.cpp
    ${FSF_DIR}/tt.cpp
    ${FSF_DIR}/uci.cpp
    ${FSF_DIR}/ucioption.cpp
    ${FSF_DIR}/tune.cpp
    ${FSF_DIR}/syzygy/tbprobe.cpp
    ${FSF_DIR}/nnue/evaluate_nnue.cpp
    ${FSF_DIR}/nnue/features/half_ka_v2.cpp
    ${FSF_DIR}/nnue/features/half_ka_v2_variants.cpp
    ${FSF_DIR}/partner.cpp
    ${FSF_DIR}/parser.cpp
    ${FSF_DIR}/piece.cpp
    ${FSF_DIR}/variant.cpp
    ${FSF_DIR}/xboard.cpp
)

set(HOST_SOURCES
    ${CMAKE_CURRENT_SOURCE_DIR}/engine_host.cpp
    ${CMAKE_CURRENT_SOURCE_DIR}/janggi_engine_jni.cpp
)

add_library(janggi_engine SHARED ${FSF_SOURCES} ${HOST_SOURCES})

target_include_directories(janggi_engine PRIVATE ${FSF_DIR} ${CMAKE_CURRENT_SOURCE_DIR})

target_compile_definitions(janggi_engine PRIVATE
    LARGEBOARDS
    PRECOMPUTED_MAGICS
    NNUE_EMBEDDING_OFF
    USE_PTHREADS
    NDEBUG
)

# Upstream builds the engine with -fno-exceptions; the host file needs exceptions for
# std::thread error handling, so only the engine TUs get the flag.
set_source_files_properties(${FSF_SOURCES} PROPERTIES COMPILE_OPTIONS "-fno-exceptions")

target_compile_options(janggi_engine PRIVATE
    -O3
    -Wall -Wcast-qual -Wextra -Wshadow
    -Wno-unused-parameter
    -fvisibility=hidden
    -ffunction-sections -fdata-sections
)

# AGP passes -DANDROID_ABI=<abi>; the NDK toolchain file additionally sets CMAKE_ANDROID_ARCH_ABI.
# Accept either so the per-ABI flags cannot be silently skipped.
set(JANGGI_ABI "${ANDROID_ABI}")
if(NOT JANGGI_ABI)
    set(JANGGI_ABI "${CMAKE_ANDROID_ARCH_ABI}")
endif()

if(JANGGI_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(janggi_engine PRIVATE IS_64BIT USE_POPCNT USE_NEON)
elseif(JANGGI_ABI STREQUAL "x86_64")
    # Android x86_64 ABI guarantees SSE4.2 + POPCNT.
    target_compile_definitions(janggi_engine PRIVATE IS_64BIT USE_POPCNT USE_SSE2 USE_SSSE3 USE_SSE41)
    target_compile_options(janggi_engine PRIVATE -msse4.1 -mssse3 -msse2 -mpopcnt)
elseif(JANGGI_ABI STREQUAL "armeabi-v7a")
    target_compile_definitions(janggi_engine PRIVATE USE_POPCNT USE_NEON)
    target_compile_options(janggi_engine PRIVATE -mfpu=neon)
elseif(ANDROID)
    message(FATAL_ERROR "Unsupported Android ABI '${JANGGI_ABI}' (supported: arm64-v8a, x86_64, armeabi-v7a)")
elseif(CMAKE_SIZEOF_VOID_P EQUAL 8)
    # Desktop/host builds (tools/desktop-jni-test): generic 64-bit.
    target_compile_definitions(janggi_engine PRIVATE IS_64BIT)
endif()

if(ANDROID)
    find_library(log-lib log)
    target_link_libraries(janggi_engine ${log-lib} atomic)
    target_link_options(janggi_engine PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384)
else()
    find_package(Threads REQUIRED)
    target_link_libraries(janggi_engine Threads::Threads)
endif()

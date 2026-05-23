cmake_minimum_required(VERSION 3.22.1)
project(openflow_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# --- Disable everything we don't need from the upstream submodules ---
set(BUILD_SHARED_LIBS              OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE                    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE                 OFF CACHE BOOL "" FORCE)
set(GGML_CURL                      OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL                OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP                    OFF CACHE BOOL "" FORCE)

# Phase 1: pin a minimum ARM baseline that includes dotprod (SDOT / UDOT).
# armv8.2-a+dotprod is supported by every arm64 Android device sold since 2018
# (Cortex-A55/A75/A76 and newer; Snapdragon 845+, every Tensor, every Dimensity).
# This lets ggml-cpu's Q4_K_M / Q5_K_M GEMM kernels light up the dotprod path
# (gated on __ARM_FEATURE_DOTPROD). i8mm (ARMv8.6) is *not* enabled here —
# it's still rare outside flagship chips and would crash with SIGILL on older
# devices. Only applies to the arm64-v8a APK split; x86_64 (emulator) is
# unaffected because this branch is guarded by CMAKE_SYSTEM_PROCESSOR.
if (CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()

set(LLAMA_BUILD_TESTS              OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS              OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER             OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON             OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL                     OFF CACHE BOOL "" FORCE)

set(WHISPER_BUILD_TESTS            OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES          OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER           OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2                   OFF CACHE BOOL "" FORCE)
set(WHISPER_USE_SYSTEM_GGML        OFF CACHE BOOL "" FORCE)

# llama.cpp first — creates `ggml`, `ggml-base`, `ggml-cpu`, `llama`
add_subdirectory(third_party/llama.cpp EXCLUDE_FROM_ALL)

# whisper.cpp second — its CMakeLists has `if (NOT TARGET ggml)` so it will
# skip its own ggml setup and reuse llama.cpp's ggml target.
add_subdirectory(third_party/whisper.cpp EXCLUDE_FROM_ALL)

# --- JNI bridge ---
add_library(openflow_jni SHARED
    jni_whisper.cpp
    jni_llama.cpp
)

target_include_directories(openflow_jni PRIVATE
    third_party/whisper.cpp/include
    third_party/llama.cpp/include
)

target_link_libraries(openflow_jni PRIVATE
    whisper
    llama
    android
    log
)

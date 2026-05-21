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

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

# Phase 6 (experimental): Vulkan GPU backend. Default OFF.
#
# See docs/dev-harness/research/gpu-inference-feasibility.md for the full
# evaluation. TL;DR: technically possible but requires the LunarG Vulkan SDK
# on the host (provides SPIRV-Headers + the Vulkan CMake config that
# llama.cpp's ggml-vulkan does `find_package(Vulkan COMPONENTS glslc REQUIRED)`
# + `find_package(SPIRV-Headers REQUIRED)` against). NDK's bundled glslc alone
# is NOT enough. Also adds 5-10 min/ABI build time + ~5-8 MB APK + needs
# runtime CPU-fallback path that's not yet implemented.
#
# To experiment locally:
#   1. Install LunarG Vulkan SDK (https://vulkan.lunarg.com)
#   2. source the SDK setup script (or set VULKAN_SDK / CMAKE_PREFIX_PATH)
#   3. ./gradlew :app:assembleDebug -POpenflowEnableVulkan=true
#
# We auto-add NDK r25+'s bundled glslc to PATH so vulkan-shaders-gen finds it
# even if the SDK ships a different version. SPIRV-Headers and the Vulkan
# CMake config must come from the SDK.
option(OPENFLOW_ENABLE_VULKAN "Compile in ggml-vulkan backend (experimental, needs LunarG SDK)" OFF)
if (OPENFLOW_ENABLE_VULKAN)
    if (DEFINED CMAKE_ANDROID_NDK)
        set(_openflow_ndk_root "${CMAKE_ANDROID_NDK}")
    elseif (DEFINED ANDROID_NDK)
        set(_openflow_ndk_root "${ANDROID_NDK}")
    elseif (DEFINED ENV{ANDROID_NDK_HOME})
        set(_openflow_ndk_root "$ENV{ANDROID_NDK_HOME}")
    endif()
    if (DEFINED ANDROID_HOST_TAG)
        set(_openflow_ndk_host_tag "${ANDROID_HOST_TAG}")
    elseif (APPLE)
        set(_openflow_ndk_host_tag "darwin-x86_64")
    elseif (UNIX)
        set(_openflow_ndk_host_tag "linux-x86_64")
    elseif (WIN32)
        set(_openflow_ndk_host_tag "windows-x86_64")
    endif()
    set(_openflow_glslc "${_openflow_ndk_root}/shader-tools/${_openflow_ndk_host_tag}/glslc")
    if (EXISTS "${_openflow_glslc}")
        get_filename_component(_openflow_glslc_dir "${_openflow_glslc}" DIRECTORY)
        set(ENV{PATH} "${_openflow_glslc_dir}:$ENV{PATH}")
        message(STATUS "OpenFlow: prepending NDK shader-tools to PATH (${_openflow_glslc_dir})")
    endif()
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    message(STATUS "OpenFlow: Vulkan backend ENABLED (requires LunarG Vulkan SDK on host for SPIRV-Headers)")
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

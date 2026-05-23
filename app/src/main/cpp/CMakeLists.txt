cmake_minimum_required(VERSION 3.22.1)
project(openflow_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_C_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

list(PREPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_LIST_DIR}/cmake")

# --- ccache: object-level compile cache ---
# Set the compiler launcher BEFORE add_subdirectory(llama/whisper) so it covers
# every target (ggml / llama / whisper / openflow_jni), not just ggml. Because we
# set CMAKE_C/CXX_COMPILER_LAUNCHER here, ggml's own `if (... NOT
# CMAKE_CXX_COMPILER_LAUNCHER)` guard skips its redundant RULE_LAUNCH_COMPILE
# wiring (no double-ccache). Absent ccache this is a no-op — builds behave as before.
# Cross-worktree hits require ccache base_dir + hash_dir=false (see AGENTS.md).
find_program(CCACHE_PROGRAM ccache)
if (CCACHE_PROGRAM)
    set(CMAKE_C_COMPILER_LAUNCHER   "${CCACHE_PROGRAM}" CACHE STRING "" FORCE)
    set(CMAKE_CXX_COMPILER_LAUNCHER "${CCACHE_PROGRAM}" CACHE STRING "" FORCE)
    message(STATUS "OpenFlow: ccache enabled at ${CCACHE_PROGRAM}")
else()
    message(STATUS "OpenFlow: ccache not found; native build will not be cached")
endif()

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
# NDK r25+ bundles glslc and SPIR-V headers but not a SPIRV-Headers CMake
# package config, so OpenFlow provides a tiny FindSPIRV-Headers module under
# cpp/cmake for llama.cpp's find_package call.
option(OPENFLOW_ENABLE_VULKAN "Compile in ggml-vulkan backend (experimental, needs LunarG SDK)" OFF)
if (OPENFLOW_ENABLE_VULKAN)
    set(_openflow_vulkan_headers "${CMAKE_CURRENT_LIST_DIR}/third_party/Vulkan-Headers/include")
    if (EXISTS "${_openflow_vulkan_headers}/vulkan/vulkan.hpp")
        # NDK r29 ships Vulkan C headers but not Vulkan-Hpp. ggml-vulkan includes
        # <vulkan/vulkan.hpp>, so use Khronos headers pinned to NDK r29's
        # VK_HEADER_VERSION=275.
        include_directories(BEFORE SYSTEM "${_openflow_vulkan_headers}")
    else()
        message(FATAL_ERROR "OpenFlow: Vulkan-Headers submodule is missing vulkan/vulkan.hpp")
    endif()

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
    get_filename_component(_openflow_cmake_bin_dir "${CMAKE_COMMAND}" DIRECTORY)
    if (EXISTS "${_openflow_cmake_bin_dir}/ninja")
        set(ENV{PATH} "${_openflow_cmake_bin_dir}:$ENV{PATH}")
    endif()
    if (APPLE)
        set(_openflow_host_compiler_paths /usr/bin /opt/homebrew/opt/llvm/bin /usr/local/opt/llvm/bin)
    elseif (UNIX)
        set(_openflow_host_compiler_paths /usr/bin /usr/local/bin)
    endif()
    find_program(_openflow_host_c_compiler NAMES clang gcc cc PATHS ${_openflow_host_compiler_paths} NO_DEFAULT_PATH)
    find_program(_openflow_host_cxx_compiler NAMES clang++ g++ c++ PATHS ${_openflow_host_compiler_paths} NO_DEFAULT_PATH)
    execute_process(
        COMMAND uname -m
        OUTPUT_VARIABLE _openflow_host_processor
        OUTPUT_STRIP_TRAILING_WHITESPACE
        ERROR_QUIET
    )
    if (NOT _openflow_host_processor)
        set(_openflow_host_processor "${CMAKE_HOST_SYSTEM_PROCESSOR}")
    endif()
    if (NOT CMAKE_MAKE_PROGRAM OR NOT _openflow_host_c_compiler OR NOT _openflow_host_cxx_compiler)
        message(FATAL_ERROR "OpenFlow: Vulkan shader generator requires host Ninja/C/C++ compilers")
    endif()
    configure_file(
        "${CMAKE_CURRENT_LIST_DIR}/cmake/openflow-vulkan-host-toolchain.cmake.in"
        "${CMAKE_BINARY_DIR}/openflow-vulkan-host-toolchain.cmake"
        @ONLY
    )
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN
        "${CMAKE_BINARY_DIR}/openflow-vulkan-host-toolchain.cmake"
        CACHE FILEPATH "" FORCE
    )
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    message(STATUS "OpenFlow: Vulkan backend ENABLED")
endif()

option(OPENFLOW_ENABLE_OPENCL "Compile in ggml-opencl backend (experimental, requires OpenCL SDK + Python3)" OFF)
if (OPENFLOW_ENABLE_OPENCL)
    # Android's NDK does not ship OpenCL headers or a stable libOpenCL.so.
    # Build Khronos' ICD loader for each Android ABI instead of linking a host
    # SDK library. The loader is static so libopenflow_jni.so can still load on
    # devices without OpenCL; it will dlopen a vendor ICD only when available.
    set(OPENCL_ICD_LOADER_HEADERS_DIR
        "${CMAKE_CURRENT_LIST_DIR}/third_party/OpenCL-Headers"
        CACHE PATH "" FORCE
    )
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPENCL_HEADERS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(OPENCL_HEADERS_BUILD_CXX_TESTS OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_PIC ON CACHE BOOL "" FORCE)
    set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
    set(ENABLE_OPENCL_LAYERINFO OFF CACHE BOOL "" FORCE)
    add_subdirectory(third_party/OpenCL-ICD-Loader EXCLUDE_FROM_ALL)
    if (TARGET OpenCL)
        set_target_properties(OpenCL PROPERTIES POSITION_INDEPENDENT_CODE ON)
    endif()

    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_PROFILING OFF CACHE BOOL "" FORCE)
    set(GGML_OPENCL_TARGET_VERSION "300" CACHE STRING "" FORCE)
    message(STATUS "OpenFlow: OpenCL backend ENABLED (ggml-opencl)")
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

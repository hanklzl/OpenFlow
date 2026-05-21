---
name: native-build
description: >
  Use when modifying CMakeLists.txt, JNI source files, native build
  config in app/build.gradle.kts, or upgrading the whisper.cpp /
  llama.cpp submodules. Covers ggml conflict avoidance, ABI filters,
  NDK version pinning, and submodule update workflow.
  Trigger phrases: "CMake", "ggml conflict", "submodule update",
  "升级 whisper.cpp", "升级 llama.cpp", "JNI 编译失败", "native build".
  Read this BEFORE touching app/src/main/cpp/CMakeLists.txt or any
  externalNativeBuild config.
---

# Native build / CMake / submodule 约束

OpenFlow 的 `.so` 由 `app/src/main/cpp/CMakeLists.txt` 单独构建一个 `libopenflow_jni.so`，同时链 whisper.cpp 和 llama.cpp，**共享** ggml。

## 必读 gate

- [`../../../AGENTS.md`](../../../AGENTS.md)
- [`../../../docs/dev-harness/native/rules.md`](../../../docs/dev-harness/native/rules.md)

## 核心不变约束

### MUST: 添加 subdirectory 的顺序 — llama 在 whisper 之前

```cmake
add_subdirectory(third_party/llama.cpp EXCLUDE_FROM_ALL)
add_subdirectory(third_party/whisper.cpp EXCLUDE_FROM_ALL)
```

为什么：whisper.cpp 的 CMakeLists 有

```cmake
if (NOT TARGET ggml)
    # 否则 add_subdirectory(ggml) ...
endif()
```

llama.cpp 先添加会创建 `ggml` / `ggml-base` / `ggml-cpu` 等 target；whisper.cpp 检测到已存在就跳过自己的 ggml 子目录，直接复用。**反过来**会触发 "target 'ggml' already exists"。

### MUST: 禁用所有 examples/tests/tools

`CMakeLists.txt` 必须保留这些 `set(... OFF CACHE BOOL "" FORCE)`：

```cmake
set(LLAMA_BUILD_TESTS              OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES           OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS              OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER             OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON             OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL                     OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS            OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES         OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER           OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE                    OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE                 OFF CACHE BOOL "" FORCE)
set(GGML_CURL                      OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP                    OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL                OFF CACHE BOOL "" FORCE)
```

漏一个会让 APK 体积膨胀几十 MB（curl 静态链）或编译失败（OpenMP 在 NDK 缺乏 runtime）。

### MUST: 只打 arm64-v8a

`app/build.gradle.kts`：

```kotlin
ndk { abiFilters += listOf("arm64-v8a") }
```

加 `armeabi-v7a` / `x86_64` 会让 NDK 编译时间 x3-x4，APK 体积膨胀 3x。
Android 12+ 设备 99% 是 arm64；32-bit ARM 已经基本退场。

### MUST: 不要改 C++ 标准

`-std=c++17`。llama.cpp / whisper.cpp 都需要 C++17，不要降级到 14。
**禁止**升 C++20 / 23 ——upstream 未测试，编译时可能爆 `<bit>` / `<concepts>` 等。

### MUST: NDK 版本固定

当前 NDK `27.0.12077973`。升级前先在临时 worktree 完整 build + 真机跑通"长按 → 写入"全链路，再合并。

### MUST: submodule 升级流程

```bash
cd app/src/main/cpp/third_party/whisper.cpp
git fetch --tags && git checkout v1.x.x  # 用 tag，不要 master HEAD
cd ../../../../../..
git add app/src/main/cpp/third_party/whisper.cpp
./gradlew :app:assembleDebug    # 验证编译通过
# 跑一次"长按 → 录音 → 文本插入"
git commit -m "deps(whisper): upgrade to v1.x.x"
```

llama.cpp 同理。**禁止**同一 commit 里同时升级 whisper 和 llama——ggml 版本不兼容时无法分离回滚。

### MUST: JNI 函数命名严格

```
Java_<kotlin.package.with.underscores>_<ClassName>_<methodName>
```

OpenFlow 例子：

```cpp
JNIEXPORT jlong JNICALL
Java_com_hank_flow_open_asr_WhisperJni_nativeInit(...)
```

Kotlin：

```kotlin
package com.hank.flow.open.asr
object WhisperJni { external fun nativeInit(modelPath: String): Long }
```

**任何**重命名都必须两侧同步。Kotlin 端 typo 错误编译时不会暴露——只在 `loadLibrary` 之后第一次调用时 `UnsatisfiedLinkError`。

### MUST NOT: 启用 cmake.path 之外的 cmake 调用

`app/build.gradle.kts` 用 `externalNativeBuild.cmake.path`。**禁止**改成 `ndkBuild` 或自定义 task——AGP 8.7 对 cmake 集成最稳定。

### MUST: 静态链接所有 native deps

`BUILD_SHARED_LIBS=OFF`。Whisper / llama / ggml 都编译成 `.a`，最终只输出一个 `libopenflow_jni.so`。
分多个 `.so` 会引入 `dlopen` 依赖顺序问题。

## 调试技巧

- CMake 配置失败：看 `app/.cxx/Debug/<hash>/<abi>/CMakeFiles/CMakeOutput.log` 和 `CMakeError.log`
- `UnsatisfiedLinkError`：先 `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep openflow_jni` 确认 `.so` 在；再 `objdump -T libopenflow_jni.so | grep Java_` 看符号是否导出
- 编译慢：第一次包含 NDK 配置 + 所有 .cpp 编译（约 200 个 .o 文件），骁龙 Mac 5-7 分钟正常；后续 `assembleDebug` 走增量约 30s

## 验证命令

```bash
# 完整原生构建
./gradlew :app:externalNativeBuildDebug --console=plain

# 看哪些 native lib 进了 APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep '\.so'

# 看 JNI 符号
file app/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libopenflow_jni.so
```

## 参考

- `app/src/main/cpp/CMakeLists.txt`
- `app/build.gradle.kts`（`externalNativeBuild` 块）
- whisper.cpp 配置项：`app/src/main/cpp/third_party/whisper.cpp/CMakeLists.txt`
- llama.cpp 配置项：`app/src/main/cpp/third_party/llama.cpp/CMakeLists.txt`
- ggml 文档（共享 backend）：`app/src/main/cpp/third_party/llama.cpp/ggml/`

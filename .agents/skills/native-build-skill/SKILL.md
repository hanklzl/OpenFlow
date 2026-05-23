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

### MUST: ABI 与 GPU 出包走 productFlavor 双维度（不是 splits.abi）

ABI 由 `app/build.gradle.kts` 的 **flavor 维度** 控制，**不要**用 `splits.abi`——
AGP 不允许 `splits.abi` 与 `ndk.abiFilters` 同时设置，而 gpu 需要按 ABI 收窄（只出
arm64），故 ABI 必须做成 flavor 维度。两个维度 `backend`(cpu/gpu) × `abi`(arm64/x64)，
每变体设单个 `ndk.abiFilters` → 产物即「每 ABI 一个 APK」：

```kotlin
flavorDimensions += listOf("backend", "abi")
productFlavors {
    create("cpu") { dimension = "backend"; /* buildConfigField VULKAN/OPENCL = false */ }
    create("gpu") {
        dimension = "backend"
        externalNativeBuild { cmake { arguments += listOf("-DOPENFLOW_ENABLE_VULKAN=ON", "-DOPENFLOW_ENABLE_OPENCL=ON") } }
        /* buildConfigField VULKAN/OPENCL = true */
    }
    create("arm64") { dimension = "abi"; ndk { abiFilters += "arm64-v8a" } }
    create("x64")   { dimension = "abi"; ndk { abiFilters += "x86_64" } }
}
```

`arm64-v8a` 覆盖主流真机，`x86_64` 用于本地 Android emulator 验证。
仍禁止加 `armeabi-v7a` / `x86`，避免继续放大 NDK 编译时间与 APK 体积。

**变体裁剪**（`androidComponents.beforeVariants`，见 build.gradle.kts）：
- `gpu+x64` 始终禁用——Vulkan/OpenCL 只在真机 ARM 有意义，给 x86_64 编 GPU 纯属浪费（181 个 shader）。
- `gpu` 的 **debug** 默认禁用，避免日常 `assembleDebug` 顺带编 Vulkan shader；要本地验 GPU 用 `-PopenflowGpuDebug=true assembleGpuArm64Debug`。
- 启用集合：release = cpuArm64 / cpuX64 / gpuArm64；debug = cpuArm64 / cpuX64。

一条 `./gradlew :app:assembleRelease` 同时出三个包，产物路径按 flavor 隔离
（`apk/cpuArm64/`、`apk/cpuX64/`、`apk/gpuArm64/`），互不覆盖。**不再**用
`-POpenflowEnableVulkan/-POpenflowEnableOpenCl`，也**不再**跑两次 gradle。
GPU 的 `-DOPENFLOW_ENABLE_*=ON` 仍走 `externalNativeBuild.cmake.arguments`（现由 gpu flavor 提供），不新增 cmake 调用。

### MUST: native 编译加速用 ccache（不要预编译 .so 入库）

CMakeLists 顶部在 `add_subdirectory` 之前显式设 `CMAKE_C/CXX_COMPILER_LAUNCHER=ccache`
（`find_program(CCACHE_PROGRAM ccache)` 命中才设），覆盖 whisper/llama/ggml/openflow_jni
全部 target；ggml 自带的 `GGML_CCACHE` guard 见 launcher 已设会自动跳过、不重复。
未装 ccache 时为 no-op，行为同旧。

跨 worktree / clean 命中**必须**设全局 `base_dir=$HOME` + `hash_dir=false`（否则每个
worktree 不同绝对路径 → 全 miss）。实测：清掉 `.cxx` 重编 211 文件，命中 ~88%、墙钟
2m12s → 25s。

**不要**改成「预编译 `.so` 入 jniLibs」当前阶段——本仓频繁改 `jni_*.cpp` / submodule，
预编译会频繁过期。该路线留作 native 稳定后的后续阶段（届时 Git LFS + 指纹防过期）。

### MUST: 不要改 C++ 标准

`-std=c++17`。llama.cpp / whisper.cpp 都需要 C++17，不要降级到 14。
**禁止**升 C++20 / 23 ——upstream 未测试，编译时可能爆 `<bit>` / `<concepts>` 等。

### MUST: NDK 版本固定

当前 NDK `29.0.14206865`（r29，最新稳定版；r28+ 默认链接 16KB 段对齐，无需在 CMake 里加 `-Wl,-z,max-page-size=16384`）。升级前先在临时 worktree 完整 build + 真机跑通"长按 → 写入"全链路，再合并。

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

`app/build.gradle.kts` 用 `externalNativeBuild.cmake.path`。**禁止**改成 `ndkBuild` 或自定义 task——AGP 对 cmake 集成最稳定。（ccache 是通过 `CMAKE_CXX_COMPILER_LAUNCHER` 接进 cmake 的，不算「cmake.path 之外的调用」。）

### MUST: 静态链接所有 native deps

`BUILD_SHARED_LIBS=OFF`。Whisper / llama / ggml 都编译成 `.a`，最终只输出一个 `libopenflow_jni.so`。
分多个 `.so` 会引入 `dlopen` 依赖顺序问题。

## 调试技巧

- CMake 配置失败：看 `app/.cxx/Debug/<hash>/<abi>/CMakeFiles/CMakeOutput.log` 和 `CMakeError.log`（cpu/gpu 各有独立 hash 目录）
- `UnsatisfiedLinkError`：先 `unzip -l app/build/outputs/apk/cpuArm64/debug/OpenFlow-cpu-arm64-debug.apk | grep openflow_jni` 确认 `.so` 在；再 `llvm-objdump -T <so> | grep Java_` 看符号是否导出（应有 12 个 `Java_com_hank_*`）
- 编译慢：第一次约 200 个 .o，骁龙 Mac 冷构建 ~2-3 分钟；**装了 ccache 后** clean / 新 worktree 重编命中 ~88%、降到 ~25s。命中率低先查 `ccache -s` 与 `base_dir`/`hash_dir` 配置
- gpu .so（35M，含 Vulkan/OpenCL + 181 SPIR-V shader）比 cpu .so（3.6M stripped）大一截属正常

## 验证命令

```bash
# 单变体原生构建（cpu arm64）
./gradlew :app:assembleCpuArm64Debug --console=plain

# 一条命令出全部 Release 包（需签名 env）
./gradlew :app:assembleRelease --no-daemon

# 看哪些 native lib 进了 APK
unzip -l app/build/outputs/apk/cpuArm64/debug/OpenFlow-cpu-arm64-debug.apk | grep '\.so'

# 看 JNI 符号（NDK 自带 llvm-objdump）
SO=$(find app/build/intermediates/cxx -name 'libopenflow_jni.so' | head -1)
"$(find ~/Library/Android/sdk/ndk/29.0.14206865 -name llvm-objdump | head -1)" -T "$SO" | grep -c Java_com_hank

# ccache 命中率
ccache -s
```

## 参考

- `app/src/main/cpp/CMakeLists.txt`
- `app/build.gradle.kts`（`externalNativeBuild` 块）
- whisper.cpp 配置项：`app/src/main/cpp/third_party/whisper.cpp/CMakeLists.txt`
- llama.cpp 配置项：`app/src/main/cpp/third_party/llama.cpp/CMakeLists.txt`
- ggml 文档（共享 backend）：`app/src/main/cpp/third_party/llama.cpp/ggml/`

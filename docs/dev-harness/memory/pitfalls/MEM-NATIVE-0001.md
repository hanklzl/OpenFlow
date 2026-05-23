---
id: MEM-NATIVE-0001
created: 2026-05-23
updated: 2026-05-23
source: feat-vulkan-backend@20d8ccb
confidence: high
status: draft
promotes-to:
verified-at: 2026-05-23
---

# Android Vulkan 构建需要 Vulkan-Hpp 与 host shader generator 工具链

## 现象 / 事实

在 Android 交叉编译 `ggml-vulkan` 时，NDK r29 提供 Vulkan C headers 与 `glslc`，但不提供 `vulkan/vulkan.hpp`；同时 `vulkan-shaders-gen` 是 host 端工具，不能继承 Android toolchain。直接打开 `OPENFLOW_ENABLE_VULKAN` 会在 `ggml-vulkan` 编译期触发 `fatal error: 'vulkan/vulkan.hpp' file not found`，或让 shader generator 使用错误的 cross toolchain。

## 影响 / 为什么记

这是 R5 命中项：本次把 `:app:externalNativeBuildDebug -POpenflowEnableVulkan=true` 从构建失败修到 arm64-v8a / x86_64 都通过。以后升级 NDK、CMake 或 llama.cpp 的 Vulkan CMake 时，必须同时保留 Khronos `Vulkan-Headers` gitlink、NDK shader-tools PATH 注入，以及 `GGML_VULKAN_SHADERS_GEN_TOOLCHAIN` 指向的 host toolchain 文件。

## 如何复现 / 验证

最小验证命令：

```bash
./gradlew :app:externalNativeBuildDebug -POpenflowEnableVulkan=true --console=plain
file app/.cxx/Debug/*/arm64-v8a/Debug/vulkan-shaders-gen
file app/.cxx/Debug/*/x86_64/Debug/vulkan-shaders-gen
```

期望结果：两个 ABI 的 native build 成功，`vulkan-shaders-gen` 是当前构建机 host 可执行文件，而不是 Android ELF。

## 关联

- 相关代码：`app/src/main/cpp/CMakeLists.txt:36`
- 相关代码：`app/src/main/cpp/cmake/openflow-vulkan-host-toolchain.cmake.in:1`
- 相关 rule / incident：`docs/dev-harness/native/rules.md:32`

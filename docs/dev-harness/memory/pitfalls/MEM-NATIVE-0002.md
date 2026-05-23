---
id: MEM-NATIVE-0002
created: 2026-05-23
updated: 2026-05-23
source: feat-vulkan-backend@20d8ccb
confidence: high
status: draft
promotes-to:
verified-at: 2026-05-23
---

# Android OpenCL 后端使用静态 Khronos ICD loader

## 现象 / 事实

Android NDK 不提供 OpenCL headers，也不保证系统存在稳定可链接的 `libOpenCL.so`。OpenFlow 的 OpenCL 后端应把 Khronos `OpenCL-Headers` 与 `OpenCL-ICD-Loader` 作为 submodule 引入，并把 ICD loader 编成静态库链接进 `libopenflow_jni.so`，不要把动态 `libOpenCL.so` 打进 APK。

## 影响 / 为什么记

这是 R5 命中项：本次把 `:app:externalNativeBuildDebug -POpenflowEnableOpenCl=true` 的工具链缺口修到 arm64-v8a / x86_64 都通过。静态 loader 让 JNI 库在没有 OpenCL vendor ICD 的设备上仍可加载；运行时只在后端探测和显式 OpenCL 初始化时尝试使用厂商 OpenCL，同时 manifest 用 optional `uses-native-library` 表达设备能力依赖。

## 如何复现 / 验证

最小验证命令：

```bash
./gradlew :app:assembleDebug -POpenflowEnableOpenCl=true --console=plain
llvm-readelf -d app/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libopenflow_jni.so
llvm-readelf -d app/build/intermediates/cxx/Debug/*/obj/x86_64/libopenflow_jni.so
```

期望结果：两个 ABI 的构建成功，`libopenflow_jni.so` 不出现 `NEEDED libOpenCL.so`；APK 中也不应打包 `libOpenCL.so`。

## 关联

- 相关代码：`app/src/main/cpp/CMakeLists.txt:106`
- 相关代码：`app/src/main/cpp/cmake/FindOpenCL.cmake:1`
- 相关代码：`app/src/main/AndroidManifest.xml:29`
- 相关 rule / incident：`docs/dev-harness/native/rules.md:17`

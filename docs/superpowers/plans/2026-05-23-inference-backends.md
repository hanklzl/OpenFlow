# 推理后端接入实施计划

> **给后续 agent / worker 的要求**：执行本计划时优先使用 `superpowers:subagent-driven-development`，或在单线程执行时使用 `superpowers:executing-plans`。任务列表使用 checkbox 便于继续追踪。

**目标**：为支持 Vulkan / OpenCL 的设备增加实验性 LLM 推理加速路径。设置页必须能展示能力状态、允许用户选择后端，并在不支持时禁用或回退 CPU。

**架构**：CPU 继续作为默认路径。在 `SettingsStore`、`RecordingForegroundService`、`PolishEngine` 与 `LlamaJni` 之间加入轻量后端选择层。运行时可用性由构建开关、Android / 设备探测、ggml native 设备探测、首版 Snapdragon / Adreno allowlist 共同决定。`PolishEngine` 优先尝试用户选择的加速后端，模型加载失败时立即回退 CPU。

**技术栈**：Kotlin、DataStore Preferences、Jetpack Compose Material3、JNI、llama.cpp ggml backend、Gradle CMake 参数、JVM 单元测试。

## 文件结构

- 新增 `app/src/main/java/com/hank/flow/open/llm/InferenceBackend.kt`：定义 CPU / Vulkan / OpenCL 偏好、可用性状态、不可用原因与 `nGpuLayers`。
- 新增 `app/src/main/java/com/hank/flow/open/llm/InferenceBackendRuntime.kt`：收集 Android 与 native 能力，生成 detector；不得加载模型或执行推理。
- 修改 `app/src/main/java/com/hank/flow/open/llm/LlamaJni.kt`：暴露 native GPU offload 能力与后端设备摘要，并提供可测试的 `LlamaBridge`。
- 修改 `app/src/main/cpp/jni_llama.cpp`：通过 ggml backend registry 实现后端设备枚举与显式设备选择。
- 修改 `app/src/main/cpp/CMakeLists.txt` 与 `app/build.gradle.kts`：保留 Vulkan opt-in，新增 OpenCL opt-in，并传递清晰的构建开关。
- 新增 Khronos `Vulkan-Headers` submodule。Vulkan-Hpp 头文件必须与 NDK r29 的 `VK_HEADER_VERSION=275` 匹配，并由 CMake 优先放入 include path。
- 新增 Khronos `OpenCL-Headers` 与 `OpenCL-ICD-Loader` submodule。OpenCL 必须同时支持 `arm64-v8a` 和 `x86_64`；ICD loader 静态链接进 `libopenflow_jni.so`，让无 OpenCL 设备也能加载默认 JNI。
- 修改 `app/src/main/AndroidManifest.xml`：用 `<uses-native-library android:name="libOpenCL.so" android:required="false" />` 声明 Android 12+ 对厂商 OpenCL 的可选访问。
- 修改 `app/src/main/java/com/hank/flow/open/llm/PolishEngine.kt`：接收后端与 bridge，先试加速 `nGpuLayers`，失败后回退 CPU。
- 修改 `app/src/main/java/com/hank/flow/open/settings/SettingsStore.kt`：持久化用户后端偏好，不重命名现有 key。
- 修改 `app/src/main/java/com/hank/flow/open/ui/settings/SettingsScreen.kt` 与 `app/src/main/res/values/strings.xml`：增加可禁用的硬件加速设置行与中文说明。
- 修改 `app/src/main/java/com/hank/flow/open/service/RecordingForegroundService.kt`：把设置中的后端偏好传入 `PolishEngine`，并在模型或后端变化时重建缓存引擎。
- 新增 / 更新 `app/src/test/java/com/hank/flow/open/llm/` 下的 JVM 测试。

## 任务 1：后端策略测试

- [ ] 编写 `InferenceBackendDetectorTest` 失败测试：
  - CPU 永远可用。
  - 构建开关关闭时 Vulkan 不可用。
  - Vulkan 只有在构建开关、Android Vulkan 硬件能力、native GPU offload、Snapdragon / Adreno 策略都通过时可用。
  - 构建开关关闭时 OpenCL 不可用。
  - 首版 OpenCL 只对 Adreno native device 摘要开放。
  - `Auto` 在支持 Adreno OpenCL 且 build-supported 时选择 OpenCL，否则尝试 Vulkan，否则 CPU。
- [ ] 运行：
  `./gradlew :app:testDebugUnitTest --tests "com.hank.flow.open.llm.InferenceBackendDetectorTest"`
  预期：detector 类不存在时失败。
- [ ] 实现 `InferenceBackend.kt` / `InferenceBackendRuntime.kt`。
- [ ] 重跑同一测试并确认通过。

## 任务 2：PolishEngine 回退测试

- [ ] 把测试改成可注入 bridge，避免直接加载真实 JNI。
- [ ] 编写 `PolishEngineBackendTest` 失败测试：
  - CPU 偏好只调用一次 `nativeInit(..., 0)`。
  - Vulkan / OpenCL 偏好先调用加速 `nativeInit(..., -1)`。
  - 加速加载返回 `0` 时重试 CPU，并仍能返回润色结果。
  - JNI library 不可用时不触发 init，流水线保持安全回退。
- [ ] 运行：
  `./gradlew :app:testDebugUnitTest --tests "com.hank.flow.open.llm.PolishEngineBackendTest"`
  预期：实现前失败。
- [ ] 在 `PolishEngine.kt` 中实现 bridge、加速优先、CPU 回退。
- [ ] 重跑目标测试。

## 任务 3：设置持久化与 UI

- [ ] 在不引入 DataStore 冲突的前提下覆盖偏好 id 解析与默认值测试。
- [ ] 为 `FlowSettings` 增加 `llmAccelerationEnabled` 与 `llmInferenceBackend`，默认 `auto`，并提供对应 setter。
- [ ] 更新 `SettingsScreen` 初始值与设置项。
- [ ] 使用 `InferenceBackendRuntime` 渲染：
  - 标题：`硬件加速润色（实验）`
  - 开关：只有 resolved backend 支持时可开启。
  - 说明：展示构建缺失、设备不支持、无 Vulkan / OpenCL runtime、native backend 缺失等原因。
- [ ] 所有新增用户可见字符串写入 `res/values/strings.xml`。

## 任务 4：JNI 与 Native 构建

- [ ] 在 `LlamaJni.kt` 增加 `nativeSupportsGpuOffload` 与 `nativeListBackendDevices` 声明。
- [ ] 在 `jni_llama.cpp` 实现 JNI 方法，不改现有 Java 符号命名规则。
- [ ] CMake 要求：
  - 保持 llama.cpp 先于 whisper.cpp 的 `add_subdirectory` 顺序。
  - 保持 `GGML_BACKEND_DL=OFF`，examples / tools / tests 关闭，ABI filters 不变。
  - Vulkan 使用 Khronos `Vulkan-Headers` 提供 `vulkan/vulkan.hpp`，并生成 host toolchain 给 `vulkan-shaders-gen`，不得用 Android 交叉编译器生成 host shader 工具。
  - 新增 `OPENFLOW_ENABLE_OPENCL` opt-in，设置 `GGML_OPENCL=ON` 与 `GGML_OPENCL_USE_ADRENO_KERNELS=ON`。
  - 使用 Khronos `OpenCL-Headers` 与静态 `OpenCL-ICD-Loader`，不得依赖 host OpenCL SDK，也不得打包动态 `libOpenCL.so`。
  - 默认构建仍然 CPU-only，不要求额外 host SDK。

## 任务 5：流水线接线

- [ ] 更新 `RecordingForegroundService.ensurePolishEngine`，把后端偏好纳入缓存 key。
- [ ] 把选择后的后端与 `nGpuLayers` 传入 `PolishEngine`。
- [ ] 确保 warmup 与 commit 使用同一套后端解析逻辑。
- [ ] 通过现有日志体系记录 resolved backend 与 fallback 情况。

## 任务 6：验证

- [ ] 运行目标单测：
  `./gradlew :app:testDebugUnitTest --tests "com.hank.flow.open.llm.InferenceBackendDetectorTest" --tests "com.hank.flow.open.llm.PolishEngineBackendTest"`
- [ ] 运行完整 JVM 单测：
  `./gradlew :app:testDebugUnitTest`
- [ ] 运行 dev harness：
  `bash scripts/dev-harness/check.sh`
- [ ] 运行默认 Debug 构建：
  `./gradlew :app:assembleDebug`
- [ ] 如本机具备对应依赖，运行 opt-in 构建检查：
  `./gradlew :app:externalNativeBuildDebug -POpenflowEnableVulkan=true`
  `./gradlew :app:externalNativeBuildDebug -POpenflowEnableOpenCl=true`
- [ ] 对 Vulkan 额外验证：
  - `arm64-v8a` 和 `x86_64` 都完成 native build。
  - `vulkan-shaders-gen` 是可在 host 运行的二进制，不是 Android ABI 产物。
  - `libopenflow_jni.so` 按 ABI 正常链接 `libvulkan.so`。
- [ ] 对 OpenCL 额外验证：
  - `arm64-v8a` 和 `x86_64` 都完成 native build。
  - `libopenflow_jni.so` 没有 `NEEDED libOpenCL.so`。
  - ABI APK 不包含动态 `libOpenCL.so`。
- [ ] 如果本次没有可用 Snapdragon / Adreno 设备，明确记录仍需真机运行态验收。

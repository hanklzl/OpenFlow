# Android GPU 推理可行性评估

> 本文记录 ASR + 润色延迟优化后的 GPU 后端调研与实现状态。CPU 仍是默认路径，GPU 只作为实验能力接入。

## 结论摘要

- **OpenFlow 现在同时具备 Vulkan 与 OpenCL 的运行时契约**：设置页提供实验性硬件加速开关，用户可选择 `自动 / OpenCL / Vulkan`；JNI 可把 llama.cpp 模型加载限制到指定后端设备；`PolishEngine` 在加速加载失败时立即回退 CPU。
- **默认 APK 仍然是 CPU-only**：GPU 后端是编译期开关，分别通过 `-POpenflowEnableVulkan=true` 和 `-POpenflowEnableOpenCl=true` 启用。
- **Snapdragon 优先实验时，OpenCL 是性能候选，Vulkan 是 Android 平台稳定性候选**：OpenCL 不属于 Android 稳定 NDK API，因此只在更严格的 Adreno 策略下开放；真正运行还依赖设备厂商提供 OpenCL ICD。
- **OpenCL 采用静态 loader 方案**：OpenFlow 把 Khronos `OpenCL-Headers` 与 `OpenCL-ICD-Loader` 作为 submodule 引入，分别为 `arm64-v8a` 和 `x86_64` 编译静态 `libOpenCL.a`，再链接进 `libopenflow_jni.so`。APK 不打包动态 `libOpenCL.so`，所以没有 OpenCL 的设备也能安全加载 JNI。
- **Vulkan 构建链路已接通**：OpenFlow 把 Khronos `Vulkan-Headers` 固定到 `vulkan-sdk-1.3.275.0`，与 NDK r29 的 `VK_HEADER_VERSION=275` 匹配；同时为 `vulkan-shaders-gen` 生成 host toolchain，避免 Android 交叉编译器污染 host shader 工具。
- **NPU 路径暂不适合当前 GGUF + llama.cpp 架构**：Snapdragon QNN/HTP、MediaTek APU、Samsung ENN、MediaPipe LLM Inference 都需要重新量化或导出成厂商格式，相当于替换推理栈。
- **预期收益**：如果 Vulkan 或 OpenCL 在真机端跑通，Whisper encoder 可能提升 2-4 倍，LLM prefill 可能提升 2-3 倍；LLM decode 受统一内存带宽影响，通常只有 1.2-1.5 倍。短文本润色链路整体预计可减少 30%-50% 的后处理延迟。

## 默认启用前的剩余阻塞

| 阻塞项 | 说明 |
|---|---|
| **OpenCL 运行时依赖** | Android NDK 不把 OpenCL 暴露为稳定 API。编译期 headers / loader 已通过静态 Khronos loader 解决，但实际加速仍需要设备厂商 OpenCL ICD。Android 12+ 访问通过 `<uses-native-library android:name="libOpenCL.so" android:required="false" />` 声明为可选。 |
| **APK 体积** | Vulkan 会增加 SPIR-V shader 与 ggml-vulkan 代码；OpenCL 会增加 ggml-opencl 与静态 ICD loader 代码。 |
| **首次构建耗时** | ggml Vulkan shader 数量多，干净构建会明显增加每个 ABI 的构建时间。 |
| **驱动质量** | 首批只把 Qualcomm Adreno 作为明确目标。Mali / PowerVR / Tensor 需要设备矩阵验证后再开放。 |
| **与 KV prefix cache 的交互** | `llama_state_seq_get_data` / `set_data` 在部分 GPU offload 下的语义仍需验证；CPU-only 与 GPU context 之间的 cache blob 可能不可互换。 |

## 当前实现

- `app/build.gradle.kts`：暴露 `OpenflowEnableVulkan` 与 `OpenflowEnableOpenCl`，并同步到 `BuildConfig`，让 UI / 运行时能准确灰掉不可用后端。
- `app/src/main/cpp/CMakeLists.txt`：提供 `GGML_VULKAN` 与 `GGML_OPENCL` 的 opt-in 配置。Vulkan 接入 NDK `glslc`、本地 SPIR-V header 查找、Khronos `Vulkan-Headers` 与 host shader generator toolchain；OpenCL 为每个 ABI 静态构建 Khronos `OpenCL-ICD-Loader`，并通过 `cpp/cmake/FindOpenCL.cmake` 暴露给 ggml-opencl。
- `app/src/main/AndroidManifest.xml`：声明 Android 12+ 对厂商 `libOpenCL.so` 的可选访问，不让安装依赖 OpenCL。
- `app/src/main/cpp/jni_llama.cpp`：`nativeInit(..., backendName)` 通过 ggml backend registry 显式选择 `Vulkan` 或 `OpenCL` 设备，并传入单设备 `mparams.devices` 列表。未知或不可用后端返回 0，Kotlin 侧重试 CPU。
- `app/src/main/java/com/hank/flow/open/llm/InferenceBackend.kt`：集中描述构建能力、运行时能力、设备策略与回退原因。`Auto` 策略优先 Adreno OpenCL，再尝试 Snapdragon / Adreno Vulkan，否则 CPU。
- `app/src/main/java/com/hank/flow/open/ui/settings/SettingsScreen.kt`：提供用户开关和后端选择；当当前构建、系统或设备不满足条件时，硬件加速开关禁用并展示原因。

## 发布 GPU 模式前的路线

1. **构建矩阵**：同时产出 CPU-only 与 GPU-enabled APK。设备验证充分前不要把 GPU 编进所有默认包。
2. **失败探测持久化**：某台设备上 GPU model load 失败后写入状态，避免每次冷启动重复探测。
3. **Phase 5 + GPU 兼容性检查**：验证 KV prefix cache 在 CPU / GPU 之间是否可复用，必要时限制 cache 只在同后端 context 内使用。
4. **设备矩阵 smoke test**：至少覆盖 Snapdragon 8 Gen 2/3、Tensor G3、Dimensity 9000、Mali-G715、Mali-G610、Adreno 6xx。最低标准是 `polishStreaming` 能产出与 CPU-only 语义一致的文本。

## 为什么暂不接 NPU

| 厂商 / 路径 | 工具链 | 阻塞 |
|---|---|---|
| Qualcomm QNN (HTP/NPU) | QNN SDK + AIMET | 不支持 Qwen GGUF；需要转成 QNN 自有 INT8 格式，缺少 upstream 自动化路径。 |
| MediaTek APU (NeuroPilot) | NeuroPilot SDK | 同样没有 GGUF 输入路径，也没有 Qwen 现成转换链路。 |
| Samsung ENN | Exynos-only，通常需要合作方权限 | 不适合作为通用 Android 后端。 |
| Google EdgeTPU / Pixel Tensor | TFLite Delegate | 不接受 GGUF，需要重写推理栈。 |
| MediaPipe LLM Inference | Google reference implementation | 主要支持 Gemma 系列，当前 Qwen 模型不在适配路径内。 |

这些方案都不能直接加载 GGUF；接入它们等价于另选推理栈，不适合作为本次 llama.cpp 后端扩展。

## 相关代码

- `app/src/main/cpp/CMakeLists.txt`：`OPENFLOW_ENABLE_VULKAN` / `OPENFLOW_ENABLE_OPENCL`。
- `app/build.gradle.kts`：`-POpenflowEnableVulkan=true` / `-POpenflowEnableOpenCl=true` 参数传递。
- `app/src/main/cpp/third_party/Vulkan-Headers/`：Khronos Vulkan C / C++ headers，固定到 NDK r29 匹配版本。
- `app/src/main/cpp/third_party/OpenCL-Headers/`：Khronos OpenCL headers。
- `app/src/main/cpp/third_party/OpenCL-ICD-Loader/`：静态 Android ABI ICD loader。
- `app/src/main/cpp/jni_whisper.cpp:19`：`cparams.use_gpu = false`，后续若支持 Whisper GPU 需要改成设置驱动。
- `app/src/main/cpp/jni_llama.cpp`：llama.cpp backend registry 的显式后端选择。

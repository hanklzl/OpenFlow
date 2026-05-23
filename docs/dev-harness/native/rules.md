# Native / JNI / CMake Rules

`app/src/main/cpp/` 下任何改动 + submodule (whisper.cpp / llama.cpp) 升级的强约束。

## MUST

1. **`add_subdirectory(third_party/llama.cpp)` 必须先于 `add_subdirectory(third_party/whisper.cpp)`**。原因：whisper.cpp 的 `if (NOT TARGET ggml)` 守卫保证 ggml 不重定义；llama 先添加才能让 whisper 复用其 ggml。
2. **仅打 `arm64-v8a` + `x86_64`**：`app/build.gradle.kts` `abiFilters += listOf("arm64-v8a", "x86_64")`。`x86_64` 用于本地 emulator 验证。
3. **禁用所有 examples / tests / tools**（已在 `CMakeLists.txt`）：`LLAMA_BUILD_*`、`WHISPER_BUILD_*`、`GGML_*` 等关键 OFF。
4. **JNI 函数命名严格匹配 Kotlin `external` 声明**：
   - 格式 `Java_<kotlin.package.with.underscores>_<ClassName>_<methodName>`
   - 例：`Java_com_hank_flow_open_asr_WhisperJni_nativeInit`
   - 改 Kotlin 包名 / 类名时必须同步改 C++ 入口名
5. **whisper PCM 归一化在 JNI 层做**：`samples[i] = src[i] / 32768.0f`。Kotlin 侧只传 `ShortArray`。
6. **llama 每次 `nativeGenerate` 开头清 KV cache**：`llama_memory_clear(llama_get_memory(ctx), true)`。
7. **`llama_tokenize` 用两阶段调用**：第一次 `tokens=nullptr` 探测长度（返回负值），第二次按容量重试。
8. **`BUILD_SHARED_LIBS=OFF`**：whisper / llama / ggml 编译成 `.a`，最终只输出一个 `libopenflow_jni.so`。
9. **C++ 标准 `c++17`**：whisper.cpp / llama.cpp 都需要；不要降级到 14 也不要升 20。
10. **Submodule 升级流程**：
    - 用 tag（不要 master HEAD）：`git checkout v1.x.x`
    - 在本地完整跑通 `:app:assembleDebug` 并真机验证"长按 → 写入"全链路再 commit
    - 同一 commit 只升 whisper 或 llama 之一，不要同时升

## MUST NOT

1. **禁止改 `add_subdirectory` 顺序**（whisper 先于 llama）。
2. **禁止加 `armeabi-v7a` / `x86` abiFilter**：编译时间与 APK 体积会继续膨胀；当前只保留主流真机 `arm64-v8a` 与 emulator `x86_64`。
3. **禁止启用 `GGML_OPENMP`**：NDK 缺 OpenMP runtime，编译会失败。
4. **禁止启用 `LLAMA_CURL` / `GGML_CURL`**：会拉 curl 静态库进 APK，体积 +20MB+。
5. **禁止启用 `WHISPER_BUILD_*` / `LLAMA_BUILD_EXAMPLES`**：拉一堆没用的 binary 进编译。
6. **禁止在 Kotlin 侧做 PCM 归一化**：性能差且容易出 endianness bug。
7. **默认 `nativeInit(modelPath, ctxSize, nGpuLayers)` 的 `nGpuLayers` 必须为 0**：默认 APK 没编 ggml-vulkan（`OPENFLOW_ENABLE_VULKAN=OFF`），传 >0 会被忽略最多到 silent CPU fallback；启用了 Vulkan 构建后才允许在试验通道里传 >0，并必须实装 CPU 回退路径。详见 [gpu-inference-feasibility.md](./gpu-inference-feasibility.md)。
8. **禁止改 `externalNativeBuild` 为 `ndkBuild`**：AGP 对 cmake 集成最稳定。
9. **禁止省略 `llama_memory_clear`**：上一次润色的 KV 会污染本次，表现为偶发胡言乱语。

## SHOULD

1. 升级 NDK 时在临时 worktree 完整跑通再合并。
2. CMake 配置失败时先看 `app/.cxx/Debug/<hash>/<abi>/CMakeFiles/CMakeError.log`。
3. APK 内 .so 列表用 `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep '\.so'` 验证。

## 相关 incidents

- (暂无；首次违反时补 incident)

## 相关代码

- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/jni_whisper.cpp`
- `app/src/main/cpp/jni_llama.cpp`
- `app/src/main/cpp/third_party/whisper.cpp/` (submodule)
- `app/src/main/cpp/third_party/llama.cpp/` (submodule)
- `app/build.gradle.kts`（`externalNativeBuild` 与 `ndk` 块）
- `.gitmodules`
- `docs/dev-harness/native/gpu-inference-feasibility.md` — Vulkan / NPU 路径调研

---
name: whisper-cpp
description: >
  Use when adding, modifying, or debugging OpenFlow's whisper.cpp ASR
  integration: JNI bridge, model selection, audio format conversion,
  thread safety, or transcription quality issues.
  Trigger phrases: "whisper", "ASR 接入", "调 nativeTranscribe",
  "改 whisper 参数", "音频转文字". Read this BEFORE touching any of
  app/src/main/cpp/jni_whisper.cpp, app/src/main/java/com/hank/flow/open/asr/**,
  or app/src/main/cpp/third_party/whisper.cpp/**.
---

# whisper.cpp 接入约束

OpenFlow 使用 whisper.cpp 做完全离线的语音识别（multilingual GGML 模型）。本 skill 列出维护时**容易踩坑**的不变约束。

## 必读 gate

- [`../../../AGENTS.md`](../../../AGENTS.md)
- [`../../../docs/dev-harness/native/rules.md`](../../../docs/dev-harness/native/rules.md)
- [`../../../docs/dev-harness/pipeline/rules.md`](../../../docs/dev-harness/pipeline/rules.md)

## 当前调用链路

```
RecordingForegroundService.handleCommit
  └─ WhisperEngine.transcribe(pcm: ShortArray, language: "auto")
       └─ WhisperJni.nativeTranscribe(handle, pcm, lang)
            └─ jni_whisper.cpp: short→float 归一化 → whisper_full → segment 拼接
```

## 核心不变约束 (MUST / MUST NOT)

### MUST: PCM 归一化在 JNI 层做

`jni_whisper.cpp` 接收 `jshortArray` 后，必须用 `samples[i] = src[i] / 32768.0f` 转换为 float。
**禁止**让 Kotlin 侧做归一化（性能差且 Whisper 期望 mono float [-1,1]）。

### MUST: 单 context 串行访问

`whisper_context*` 内部状态不是 thread-safe。`WhisperEngine` 使用 Kotlin `Mutex` 串行化 `nativeInit / nativeTranscribe / nativeFree`。
**禁止**在多协程内同时调用同一个 handle。

### MUST: language 字段处理

```cpp
params.language = (strcmp(language, "auto") == 0) ? nullptr : language;
```
传 `"auto"` 时必须给 `nullptr`，否则 whisper 会尝试匹配语言名失败回退到英语。

### MUST NOT: 在 UI 线程调 transcribe

`whisper_full` 在 1.5 GB 内存设备上对 10 秒音频耗时 1-3 秒，必须在 `Dispatchers.Default` 中执行。`WhisperEngine.transcribe` 已经 `withContext(Dispatchers.Default)`，调用方不要再嵌套切线程。

### MUST: 模型加载失败软降级

```kotlin
if (!modelStore.isReady(model)) return ""
```
不抛异常、不弹 toast，让管道流过去；UI 由 `ModelDownloadScreen` 引导用户下载。

### MUST: PCM 采样率必须是 16kHz

`AudioRecorder.sampleRate = 16_000`。Whisper 内部期望 16kHz；其他采样率必须先重采样。
当前不支持其他采样率（OpenFlow 在 `AudioRecorder` 强制 16kHz）。

### MUST NOT: 改 jni_whisper.cpp 的 Java_ 函数命名

`Java_com_hank_flow_open_asr_WhisperJni_native<Method>` 必须与 Kotlin `external` 声明逐字匹配。
**重命名 Kotlin 包名或类名时**必须同步改 C++ 入口名，否则 `UnsatisfiedLinkError` 在运行时才暴露。

## 模型选择

`ModelCatalog`：

| ID | 大小 | 速度 (8 Gen 2) | 准确率 | 建议 |
|---|---|---|---|---|
| `ggml-tiny-q5_1` | 32 MB | < 0.5s/10s | 一般 | 极低端机 |
| `ggml-small-q5_1` (默认) | 190 MB | 1-2s/10s | 良好 | 主流 |

升级到 `medium` (~770 MB) 需要确认设备内存。

## 调参提示

`whisper_full_params` 关键字段：
- `params.n_threads = 4`：4 是骁龙 8 Gen 系列的甜蜜点；多了反而上下文切换损耗。
- `params.suppress_blank = true`：避免开头空白 token 把"嗯"扩成大段静音。
- `params.print_*` 全部 `false`：否则向 logcat 喷大量东西，影响调试。
- `params.translate = false`：本项目目标是转写不翻译，永远是 false。
- 不要启用 `params.token_timestamps`：除非要做字幕。

## 常见 incidents（参考）

- `incidents/INC-NATIVE-0001.md` — JNI 函数签名 mismatch 导致 UnsatisfiedLinkError（如果未来发生）
- `incidents/INC-PIPELINE-0001.md` — 多协程并发触发同一 whisper_context（如果未来发生）

> 实际 incidents 文档请参见 `docs/dev-harness/incidents/`。

## 参考

- whisper.cpp upstream: `app/src/main/cpp/third_party/whisper.cpp/`
- whisper.h API: `app/src/main/cpp/third_party/whisper.cpp/include/whisper.h`
- 我们的 JNI 实现：`app/src/main/cpp/jni_whisper.cpp`
- Kotlin wrapper：`app/src/main/java/com/hank/flow/open/asr/WhisperEngine.kt`

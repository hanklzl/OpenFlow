---
name: llama-cpp
description: >
  Use when adding, modifying, or debugging OpenFlow's llama.cpp polish
  pipeline: JNI bridge, GGUF model selection, chat template,
  sampler chain, KV cache management, or polish output quality.
  Trigger phrases: "llama", "polish", "润色", "prompt", "改采样参数",
  "Qwen 模板", "GGUF". Read this BEFORE touching any of
  app/src/main/cpp/jni_llama.cpp, app/src/main/java/com/hank/flow/open/llm/**,
  or app/src/main/cpp/third_party/llama.cpp/**.
---

# llama.cpp 接入约束

OpenFlow 使用 llama.cpp + Qwen 系列 GGUF 模型做"口语 → 书面"润色。本 skill 列出 LLM 接入的不变约束与调参建议。

## 必读 gate

- [`../../../AGENTS.md`](../../../AGENTS.md)
- [`../../../docs/dev-harness/native/rules.md`](../../../docs/dev-harness/native/rules.md)
- [`../../../docs/dev-harness/pipeline/rules.md`](../../../docs/dev-harness/pipeline/rules.md)

## 当前调用链路

```
RecordingForegroundService.handleCommit (if settings.polishEnabled)
  └─ PolishEngine.polish(rawText)
       └─ PolishPrompt.build(rawText)   # Qwen ChatML 模板
       └─ LlamaJni.nativeGenerate(handle, prompt, maxTokens, temp, topP)
            └─ jni_llama.cpp:
                tokenize → llama_memory_clear → batched decode →
                sampler chain → token-by-token generate → detokenize
```

## 核心不变约束 (MUST / MUST NOT)

### MUST: 每次 generate 前清 KV cache

```cpp
llama_memory_t mem = llama_get_memory(h->ctx);
llama_memory_clear(mem, /*data=*/true);
```
**禁止省略**。否则上一次润色的 KV 会污染本次输出（表现为偶发胡言乱语 / 重复上次的内容）。

### MUST: 单 context 串行访问

`llama_context*` 不是 thread-safe。`PolishEngine` 使用 Kotlin `Mutex` 串行化访问。
**禁止**同时跑两个润色任务（同 handle）。

### MUST: sampler chain 顺序

```cpp
top_p → temp → dist
```
顺序错了 sampler 不报错但结果不对。如果 `temperature == 0.0f`，跳过 temp + dist，直接 `llama_sampler_init_greedy()`。

### MUST: tokenize 两阶段调用

`llama_tokenize` 第一次传 `tokens=nullptr` 探测长度（返回负值即所需容量），第二次按容量重试：

```cpp
int32_t n = llama_tokenize(vocab, text.c_str(), len, nullptr, 0, addSpecial, parseSpecial);
// n is negative: required count = -n
std::vector<llama_token> tokens(-n);
n = llama_tokenize(vocab, text.c_str(), len, tokens.data(), -n, addSpecial, parseSpecial);
```

省略第一次会导致截断。

### MUST: prompt 用 Qwen ChatML 模板

```
<|im_start|>system\n{system}<|im_end|>\n
<|im_start|>user\n{user}<|im_end|>\n
<|im_start|>assistant\n
```

`PolishPrompt.build` 已经实现。**禁止**自己拼带 BOS/EOS 的裸字符串——Qwen 训练时遵循 ChatML 约定，不按模板的 prompt 输出质量大幅下降。

### MUST NOT: 添加 BOS 在 prompt 里

`llama_tokenize(...,  addSpecial=true, parseSpecial=true)` 已经处理 BOS。**禁止**在 prompt 字符串里手写 `<|begin_of_text|>`。

### MUST: 输出后处理

`PolishEngine.polish` 调用方：
```kotlin
.trim().removeSurrounding("\"")
```
Qwen 偶尔会给输出加引号；这不是模型缺陷而是训练数据特征。

### MUST NOT: 设置 n_gpu_layers > 0

Android 上 llama.cpp 没有靠谱的 GPU 后端（OpenCL/Vulkan 都未在 llama.cpp 主线启用）。`nativeInit` 调用方必须传 `nGpuLayers=0`。

### MUST NOT: 修改 jni_llama.cpp 的 Java_ 函数命名

`Java_com_hank_flow_open_llm_LlamaJni_native<Method>` 必须与 Kotlin `external` 声明逐字匹配。
**重命名 Kotlin 包名或类名时**必须同步改 C++ 入口名。

## 模型选择

`ModelCatalog`：

| ID | 大小 (Q4_K_M) | 速度 (8 Gen 2) | 中文润色质量 |
|---|---|---|---|
| `qwen2.5-1.5b-instruct-q4_k_m` (默认) | 1.1 GB | 8-15 tokens/s | 优秀 |
| `qwen3.5-2b-instruct-q4_k_m` | 1.4 GB | 6-12 tokens/s | 优秀+（更新数据） |
| `qwen3.5-0.8b-instruct-q4_k_m` | 0.6 GB | 15-25 tokens/s | 良好 |

> Qwen3.5 系列的 GGUF 仓库路径基于 Qwen 团队常规命名约定填写；实际仓库名若不同首次下载会 404，那时只需改 `ModelCatalog.kt` 中的 `hfPath`。

## 调参建议（针对润色任务）

| 参数 | 当前值 | 说明 |
|---|---|---|
| `n_ctx` | 1024 | 润色短文本足够；长录音切片后调用 |
| `n_batch` | 256 | prompt 处理批大小 |
| `n_threads` | 4 | 骁龙 8 系列甜蜜点 |
| `temperature` | 0.3 | 润色希望稳定，不要 > 0.5 |
| `top_p` | 0.9 | 标配 |
| `maxNewTokens` | 256 | 润色后通常比原文短 |

`top_k` 暂时没用；如果出现重复模式可以加 `repeat_penalty`，但实测润色场景不需要。

## Prompt 设计原则

`PolishPrompt.SYSTEM` 当前的关键约束：

- "保留原意"——避免模型自我发挥
- "不要添加任何新信息"——避免幻觉
- "去除嗯/呃/那个/就是"——明确目标
- "直接输出润色后的纯文本，不要任何前缀、引号或解释"——严格输出格式

修改 SYSTEM 前请用至少 10 个口语样本对比"修改前 vs 修改后"，避免"看起来更好的 prompt"实际输出变差。

## ggml 与 whisper.cpp 共享

llama.cpp 的 `add_subdirectory` 必须先于 whisper.cpp。详见 [native-build-skill](../native-build-skill/SKILL.md)。

## 参考

- llama.cpp upstream: `app/src/main/cpp/third_party/llama.cpp/`
- llama.h API: `app/src/main/cpp/third_party/llama.cpp/include/llama.h`
- 我们的 JNI 实现：`app/src/main/cpp/jni_llama.cpp`
- Kotlin wrapper：`app/src/main/java/com/hank/flow/open/llm/PolishEngine.kt`
- prompt 模板：`app/src/main/java/com/hank/flow/open/llm/PolishPrompt.kt`

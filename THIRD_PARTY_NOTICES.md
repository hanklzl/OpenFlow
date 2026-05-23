# 第三方组件与许可声明 / Third-Party Notices

OpenFlow 主体代码以 **GPL-3.0-or-later** 协议发布（见 [LICENSE](LICENSE)）。
分发的 APK 同时包含以下采用其它许可协议的第三方组件，本文档列出它们的归属与许可。GPL-3.0 与下方所列各 MIT 组件单向兼容，可合规地组合分发。

> OpenFlow's own source code is licensed under **GPL-3.0-or-later** (see [LICENSE](LICENSE)).
> The distributed APK also bundles the following third-party components under their own licenses, all of which are compatible with GPL-3.0 in this direction (MIT → GPL).

---

## 内嵌为 git submodule 的原生组件 / Vendored native components

### 1. whisper.cpp

- 协议 / License：**MIT**
- 版权 / Copyright：Copyright (c) 2023–2026 The ggml authors
- 上游 / Upstream：<https://github.com/ggml-org/whisper.cpp>
- 仓库内位置 / In-tree path：`app/src/main/cpp/third_party/whisper.cpp/`
- 协议原文 / License text：[`app/src/main/cpp/third_party/whisper.cpp/LICENSE`](app/src/main/cpp/third_party/whisper.cpp/LICENSE)
- 在 OpenFlow 中的用途：端侧离线语音识别（ASR）。

### 2. llama.cpp

- 协议 / License：**MIT**
- 版权 / Copyright：Copyright (c) 2023–2026 The ggml authors
- 上游 / Upstream：<https://github.com/ggml-org/llama.cpp>
- 仓库内位置 / In-tree path：`app/src/main/cpp/third_party/llama.cpp/`
- 协议原文 / License text：[`app/src/main/cpp/third_party/llama.cpp/LICENSE`](app/src/main/cpp/third_party/llama.cpp/LICENSE)
- 在 OpenFlow 中的用途：端侧 LLM 推理，用于把口语转写润色为书面表达。

两个 submodule 共享 **ggml** 张量库，ggml 的版权与许可同样归属 The ggml authors（MIT），随上述 submodule 一并分发。

---

## 不随仓库分发、由用户在运行时下载的资源 / Runtime-downloaded assets

下列资源**不在本仓库内**、也**不打包进 APK**，由 App 在运行时按需从对应作者发布的渠道下载：

- **whisper 模型权重**（GGUF 格式）：归属与许可见上游 [ggml-org/whisper.cpp](https://github.com/ggml-org/whisper.cpp) 的模型说明。
- **Qwen 系列 LLM 模型权重**（GGUF 格式，用于润色）：由阿里通义千问团队发布，遵循其各自的模型许可（Tongyi Qianwen License / Apache-2.0，视具体模型版本而定）。下载前请在阿里官方发布页（Hugging Face / ModelScope）核对当前许可文本。

用户对这些模型权重的使用须遵守对应发布方协议；OpenFlow 项目本身不对模型权重做再许可。

---

## 其它运行时依赖 / Runtime Maven dependencies

OpenFlow 通过 Gradle 引入若干 Maven 依赖（OkHttp、AndroidX、Jetpack Compose、Kotlin Coroutines、Logan 等）。这些依赖各自以 Apache-2.0 或 MIT 等宽松协议发布；具体清单可由以下命令导出：

```
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

如未来需要在 App 内显示「开源许可」清单，可考虑接入 Google 官方的 `oss-licenses-plugin` 自动生成。

---

## 协议变更与询问 / Changes & Questions

若新增或升级第三方组件，应同步更新本文件。任何关于协议合规的问题请通过 GitHub Issue 反馈：<https://github.com/hanklzl/OpenFlow/issues>。

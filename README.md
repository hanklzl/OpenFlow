# OpenFlow

**Android 离线语音输入助手** · whisper.cpp ASR + llama.cpp 润色 · 任意输入框直接写入

[![License: GPL v3](https://img.shields.io/github/license/hanklzl/OpenFlow.svg?color=blue)](LICENSE)
[![Release](https://img.shields.io/github/v/release/hanklzl/OpenFlow?include_prereleases&sort=semver)](https://github.com/hanklzl/OpenFlow/releases)
[![Android](https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/about/versions/12)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a%20%7C%20x86__64-blue)](https://github.com/hanklzl/OpenFlow/releases/latest)

[English](README_EN.md) · 文档站：<https://hanklzl.github.io/OpenFlow/>

---

> **长按悬浮球说一句话** → 端侧 whisper.cpp 转写 → 可选 Qwen 端侧润色 → 无障碍服务写回光标位置。
> 全程在你手机上完成，**不连服务器、不上传音频**。

## 特性

- **全程离线**：录音、ASR、LLM 润色都在本机完成，没有任何遥测或音频上传。
- **任意 App 输入框可用**：基于 Android 无障碍服务 + 悬浮窗，不挑应用 / 不依赖输入法。
- **两段式管线可关**：纯转写（whisper）/ 转写 + 润色（whisper + Qwen）两种模式自由切换。
- **模型可换**：在「模型」Tab 中下载并切换 whisper / Qwen GGUF 模型，按设备性能与场景挑选。
- **上滑取消**：录音时手指从悬浮球上滑可放弃本次结果，避免误触写入。
- **GPL-3.0 开源**：仓库代码遵循 GPL-3.0；whisper.cpp / llama.cpp 等子模块保留各自的 MIT 协议（见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)）。

## 快速开始

1. 从 [Releases](https://github.com/hanklzl/OpenFlow/releases) 下载对应 ABI 的 APK
   - 真机几乎都是 `arm64-v8a`
   - 模拟器选 `x86_64`
2. 安装后按引导依次授予：**无障碍服务** · **悬浮窗** · **麦克风**
3. 打开「模型」Tab，下载至少一个 ASR 模型（推荐先用最小的 whisper 模型试通管线）
4. 任意 App 的输入框聚焦时悬浮球出现 → **长按**开始录音 → 松手结束 → 文本自动写入

> 详细的权限引导、模型选型与常见问题见 [文档站](https://hanklzl.github.io/OpenFlow/)。

## 系统要求

| 项 | 要求 |
|---|---|
| 系统 | Android 12 (API 31) 及以上 |
| 架构 | arm64-v8a（推荐）/ x86_64（模拟器） |
| 存储 | 视所选模型而定，常见 100MB – 2GB |
| 权限 | 无障碍服务、悬浮窗（SYSTEM_ALERT_WINDOW）、麦克风、麦克风类型前台服务 |

## 项目状态

当前版本：**v0.1.0**（首个公开预览版）。详见 [CHANGELOG.md](CHANGELOG.md)。

## 从源码构建

```bash
git clone --recurse-submodules https://github.com/hanklzl/OpenFlow.git
cd OpenFlow
./gradlew :app:assembleDebug
```

首次构建会编译 whisper.cpp + llama.cpp 原生代码，约需 5–15 分钟。详细的构建/签名/发布流程见仓库内 [`AGENTS.md`](AGENTS.md)（面向开发者）。

## 协议

- 本项目代码：**GPL-3.0-or-later**，见 [LICENSE](LICENSE)。
- 第三方组件归属与许可：见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
- 模型权重不在本仓库内、不随 APK 分发，由用户运行时按需下载，须遵守对应发布方的许可。

## 致谢

OpenFlow 站在以下开源项目肩上：

- [whisper.cpp](https://github.com/ggml-org/whisper.cpp) · 离线 ASR 推理
- [llama.cpp](https://github.com/ggml-org/llama.cpp) · 端侧 LLM 推理
- [Qwen](https://github.com/QwenLM) 团队 · 提供高质量的中文小模型用于润色

设计灵感参考 Typeless Flow 与 [MusicFreeAndroid](https://github.com/maotoumao/MusicFreeAndroid)。

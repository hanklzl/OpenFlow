---
title: 首页
layout: default
nav_order: 1
description: "Android 离线语音输入助手 —— 长按悬浮球说一句话，结果直接写入光标位置"
permalink: /
---

# OpenFlow
{: .fs-9 }

Android 离线语音输入助手 —— 长按悬浮球说一句话，端侧 whisper.cpp 转写、可选 Qwen 润色，结果直接写入光标位置。
{: .fs-6 .fw-300 }

[下载 APK](https://github.com/hanklzl/OpenFlow/releases/latest){: .btn .btn-primary .fs-5 .mb-4 .mb-md-0 .mr-2 }
[查看源码](https://github.com/hanklzl/OpenFlow){: .btn .fs-5 .mb-4 .mb-md-0 }

---

## 这是什么

OpenFlow 是一个**完全离线**的 Android 语音输入工具。当任意 App 的输入框获得焦点时，屏幕上会出现一个悬浮球；**长按**悬浮球开始录音，松手即把识别结果写入光标位置。

- **录音、ASR、LLM 润色全部在手机本地完成**，不向任何服务器上传音频或文本。
- 基于 **Android 无障碍服务** + **悬浮窗**，不依赖输入法、不挑应用。
- 两段式管线可独立开关：纯转写 / 转写 + 润色。

## 三步上手

1. **下载安装** —— 从 [Releases](https://github.com/hanklzl/OpenFlow/releases) 选对应 ABI 的 APK；真机几乎都是 `arm64-v8a`。详见 [安装]({% link install.md %})。
2. **授予权限** —— 首次启动按引导授予「无障碍 / 悬浮窗 / 麦克风」。详见 [权限引导]({% link permissions.md %})。
3. **下载模型** —— 在 App 内「模型」Tab 下载至少一个 ASR 模型，按需再下一个润色模型。详见 [模型选型]({% link models.md %})。

## 主要特性

- **离线优先**：无网络也能用，不产生流量、不上传隐私
- **任意输入框可用**：聊天、笔记、邮箱、浏览器搜索框……只要能聚焦光标
- **上滑取消**：录音中手指上滑即放弃，避免误触结果被写入
- **模型可换**：whisper 与 Qwen 系列的 GGUF 都可以在 App 内切换
- **GPL-3.0 开源**：完整源码与构建脚本公开

## 系统要求

| 项 | 要求 |
|---|---|
| 系统 | Android 12 (API 31) 及以上 |
| 架构 | arm64-v8a（推荐）/ x86_64（模拟器） |
| 存储 | 视模型而定，常见 100MB – 2GB |
| 权限 | 无障碍服务、悬浮窗、麦克风、麦克风类型前台服务 |

## 项目状态

当前发布版本：**v0.1.0**（首个公开预览版）。功能可用但仍在打磨；欢迎在 [Issues](https://github.com/hanklzl/OpenFlow/issues) 反馈问题与建议。

## 协议

- 项目代码：**GPL-3.0-or-later**
- 第三方组件（whisper.cpp / llama.cpp 等）：保留各自原始 MIT 许可，详见仓库 [`THIRD_PARTY_NOTICES.md`](https://github.com/hanklzl/OpenFlow/blob/main/THIRD_PARTY_NOTICES.md)
- 模型权重：不在仓库与 APK 内，由用户运行时从对应作者发布页下载，须遵守对应许可

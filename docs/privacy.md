---
title: 隐私
layout: default
nav_order: 6
---

# 隐私说明

OpenFlow 的核心设计原则是：**音频不离机**。本页详细解释 App 实际进行了哪些数据处理、哪些网络请求、以及如何独立核实这些声明。

## 一句话总结

OpenFlow **不收集、不上传、不存储**用户的音频与识别文本。模型推理全部在你的手机本地完成。唯一的网络请求是你主动触发的模型下载。

## App 实际做什么

| 行为 | 是否发生 | 数据去向 |
|---|---|---|
| 录音（麦克风采集 PCM 数据） | 是 | 仅在内存 / 临时文件中作为 ASR 输入 |
| Whisper ASR 推理 | 是 | 仅本地 CPU，不发出任何网络请求 |
| Qwen 润色推理（可选） | 当用户开启时 | 仅本地 CPU，不发出任何网络请求 |
| 将识别文本写入光标 | 是 | 仅写入当前焦点输入框 |
| 模型下载 | 仅当用户在「模型」Tab 点击「下载」时 | 用户选定的 HuggingFace 镜像 |
| 日志收集 / 上传 | **否** | — |
| 崩溃上报 / 遥测 | **否** | — |
| 广告 SDK / 分析 SDK | **否** | — |

App 内部有诊断日志（基于 Logan，本地加密存储于 App 私有目录），**仅供本机问题排查使用，不上传任何远端**。

## 唯一的网络请求

**模型下载**：当你在「模型」Tab 点击「下载」时，App 会通过 OkHttp 向你在设置中选择的 HuggingFace 镜像发起 HTTP(S) GET 请求，下载指定 `.gguf` / `.bin` 文件。

- 请求 URL：`<镜像>/<hfPath>`，其中 `hfPath` 来自仓库内 [`ModelCatalog.kt`](https://github.com/hanklzl/OpenFlow/blob/main/app/src/main/java/com/hank/flow/open/model/ModelCatalog.kt)
- 请求头：只携带标准 `User-Agent` 与断点续传 `Range`，**不带任何用户标识**
- 镜像可选项由用户在 App 内自行配置；默认 HuggingFace 官方域名

App 内**没有**其它任何形式的网络访问。可全文搜索源码中的 `okhttp` / `HttpURLConnection` 确认。

## 权限的边界

| 权限 | 实际用途 | **不**用于 |
|---|---|---|
| 无障碍服务 | 感知输入框焦点 / 通过 `ACTION_SET_TEXT` 写入文本 | 读取屏幕其它内容、记录按键、监控其它 App |
| 悬浮窗 | 显示悬浮球 | 显示广告、覆盖其它 App 内容 |
| 麦克风 | 仅在用户长按悬浮球时录音 | 后台监听、长期录制 |
| 麦克风类型前台服务 | 让录音在前台合法进行（Android 14+ 要求） | — |

## 模型权重的隐私

下载的 `.gguf` / `.bin` 模型文件本身**不包含任何用户数据**，只是浮点权重。它们存放在 App 私有目录，**卸载 App 时一并清除**。

## 如何独立核实

1. **断网测试**：在系统设置中对 OpenFlow 限制所有后台数据，下载完模型后断开 Wi-Fi / 蜂窝，验证识别仍能正常使用。
2. **抓包验证**：用 mitmproxy / Charles 对 App 抓包，确认运行时除模型下载请求外**没有其它出站连接**。
3. **源码审计**：仓库公开（GPL-3.0），可逐文件审计网络相关代码。

## 关键文件 / 代码路径

- 录音：`app/src/main/java/com/hank/flow/open/audio/AudioRecorder.kt`
- ASR：`app/src/main/java/com/hank/flow/open/asr/`（whisper.cpp JNI 调用）
- 润色：`app/src/main/java/com/hank/flow/open/llm/`（llama.cpp JNI 调用）
- 文本写入：`app/src/main/java/com/hank/flow/open/insertion/`
- **唯一的网络层**：`app/src/main/java/com/hank/flow/open/model/ModelDownloader.kt`

## 反馈渠道

如发现 OpenFlow 实际行为与本声明不符，请立即通过 [GitHub Issues](https://github.com/hanklzl/OpenFlow/issues) 反馈，或邮件联系仓库维护者。

---
title: 模型选型
layout: default
nav_order: 4
---

# 模型选型

OpenFlow 把模型权重与 App 分发解耦。这样做的好处是：APK 始终很小（< 50 MB），用户可以按设备性能与使用场景挑选不同体积的模型。

模型在 App 内「**模型**」Tab 下载，支持选择 HuggingFace 镜像（解决国内访问问题）。下载到本地后即纯离线使用。

## 内置可选模型

> 以下大小为下载体积；推理时内存占用通常更高，请按自己设备实际可用 RAM 评估。
> 详细列表以当前版本 App 内显示为准（源码：[`ModelCatalog.kt`](https://github.com/hanklzl/OpenFlow/blob/main/app/src/main/java/com/hank/flow/open/model/ModelCatalog.kt)）。

### ASR（whisper）

| 模型 | 大小 | 建议场景 |
|---|---|---|
| Whisper tiny (q5_1) | ~32 MB | 验证安装、低配机、对识别精度要求不高的短句 |
| **Whisper small (q5_1)**（默认） | ~190 MB | **推荐**：精度/速度平衡好，中端机即可流畅 |

> 想用更大的 whisper 模型（medium / large-v3）？目前需自行替换文件并对应修改源码；后续可能加入更多内置选项。

### 润色（llama.cpp + Qwen）

润色是可选环节，关闭后仅做纯转写。

| 模型 | 大小 | 建议场景 |
|---|---|---|
| Qwen3 0.6B (Q4_K_M) — 轻量 | ~400 MB | 低端机首选 / 只想做轻量润色 |
| **Qwen2.5 1.5B Instruct (Q4_K_M)**（默认） | ~1.1 GB | **默认**：润色质量与速度的折中 |
| Qwen3 1.7B (Q4_K_M) — 更新更强 | ~1.1 GB | 中高端机；润色更稳定，理解长句更好 |

## 下载与切换

1. App 内「**模型**」Tab，按需点击「下载」
2. 同类模型可下多个，「设为当前」切换默认
3. 下载支持断点续传，弱网下也能慢慢拉完
4. 模型存放在 App 私有目录下，**卸载 App 会一并清除**

## 选型建议

- **首次试通管线**：先下 Whisper tiny + 不开润色，最小代价验证整个流程
- **日常使用**：Whisper small + Qwen2.5 1.5B 是默认推荐组合
- **设备资源紧张**：保留 Whisper small，把润色换成 Qwen3 0.6B 或干脆关闭润色
- **追求质量**：Whisper small + Qwen3 1.7B

## 镜像设置

如果默认 HuggingFace 域名访问慢，可在 App 设置中切换到镜像（如 `hf-mirror.com`）。镜像选择不会改变文件 SHA256，下载完成后会做完整性校验。

## 模型协议

- **whisper 权重**：归属 [whisper.cpp](https://github.com/ggml-org/whisper.cpp) 项目（原 ggerganov 个人仓库），遵循其模型说明。
- **Qwen 系列权重**：由阿里通义千问团队发布，遵循各自模型许可（Tongyi Qianwen License / Apache-2.0，**视具体模型版本而定**）。下载前请在 Qwen 在 HuggingFace 或 ModelScope 上的发布页核对当前许可条款。
- 上述权重**不在 OpenFlow 仓库内**，OpenFlow 不对模型权重做再许可。

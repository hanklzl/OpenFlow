---
title: 常见问题
layout: default
nav_order: 5
---

# 常见问题

## 用起来

### 悬浮球完全不出现？

按顺序检查：

1. 「设置 → 辅助功能」里 OpenFlow 的无障碍服务**是否开启**
2. 「设置 → 应用 → OpenFlow → 显示在其它应用上层」**是否允许**
3. 当前 App 的输入框是否真的获得了焦点（试着先点一下输入框）
4. 部分厂商 ROM 在锁屏 / 分屏 / 小窗模式下会拦截悬浮窗，恢复正常模式重试

仍然不行？参考 [权限引导]({% link permissions.md %})。

### 长按悬浮球没反应？

通常是麦克风权限被拒。在「设置 → 应用 → OpenFlow → 权限 → 麦克风」打开后重启 App。

如果通知栏没出现「正在录音」的前台服务提示，说明前台服务未启动，重启 App 即可。

### 识别出来的文字全是英文 / 乱码？

确认所选 whisper 模型支持你说话的语言。OpenFlow 默认的 whisper-small 是多语模型，中英文均可。如果用了纯英文 small.en 之类的变体，中文会被强制翻译成英文。

### 上滑取消手势太敏感 / 总误触？

录音时手指上滑超过一定阈值会取消本次结果。如觉得阈值过低，可在「设置」中调整（如有），或保持食指尽量稳定。

### 写入文本失败但识别框显示了结果？

某些 App 的输入框是 WebView 或自定义控件，不响应 `AccessibilityNodeInfo.ACTION_SET_TEXT`。这是 Android 系统层的限制，**OpenFlow 无法绕过**。常见复现：
- 部分浏览器内的网页输入框
- 部分国际 IM 的输入框

折中方案：把识别结果复制到剪贴板，再手动粘贴。

### 润色之后改得太离谱？

润色模型越小，越容易「过度发挥」。如果你只想要轻量整理（去口语词、补标点），优先关掉润色或选用更小的 Qwen 模型。也可以在「设置」中关闭润色，只用纯转写。

## 性能与电量

### App 占用多大空间？

APK 本身 < 50 MB。真正的占用来自模型：

- 最小：Whisper tiny（~32 MB），无润色
- 默认：Whisper small + Qwen2.5 1.5B（共 ~1.3 GB）
- 满配：Whisper small + Qwen3 1.7B（~1.3 GB）

详见 [模型选型]({% link models.md %})。

### 录一段话耗多少电？

ASR + 润色都是 CPU 推理。一次 5–10 秒的语音通常在中端机上 1–2 秒内完成；持续高频使用下电量消耗与玩中等负载游戏相近。

### 后台被系统杀掉怎么办？

把 OpenFlow 加入厂商系统的「电池白名单 / 后台保活白名单」即可。OpenFlow 的录音前台服务有合法 `FOREGROUND_SERVICE_MICROPHONE` 类型声明，Android 14+ 的系统会优先保留它。

## 隐私与安全

### 真的不上传任何东西吗？

是的。

- App 没有引入任何遥测 / 埋点 / 分析 SDK
- 录音、ASR、LLM 推理全部在本地完成
- 只有「下载模型」时会访问 HuggingFace 或你配置的镜像

完整说明见 [隐私]({% link privacy.md %})。

### 我怎么验证它没在偷偷上传？

仓库源码完全公开（GPL-3.0）：
- 网络访问只在 `app/src/main/java/com/hank/flow/open/model/ModelDownloader.kt` 用到 OkHttp 下载模型文件
- 全文搜索 `okhttp` / `HttpURLConnection` / `socket` 可自行确认

也可以用 Android 的「数据 → 限制后台数据」对 OpenFlow 完全断网，看是否仍能正常识别（模型下载完毕后应当完全可用）。

## 开发与构建

### 怎么自己改源码并构建？

```bash
git clone --recurse-submodules https://github.com/hanklzl/OpenFlow.git
cd OpenFlow
./gradlew :app:assembleDebug
```

首次构建会编译 whisper.cpp + llama.cpp，约 5–15 分钟。详细的开发约束见仓库 [`AGENTS.md`](https://github.com/hanklzl/OpenFlow/blob/main/AGENTS.md)。

### 想贡献代码或反馈 bug？

到 [Issues](https://github.com/hanklzl/OpenFlow/issues) 提单或发 Pull Request。请在 PR 描述里说明改动动机与影响范围。

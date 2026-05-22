---
name: accessibility-pipeline
description: >
  Use when designing or debugging end-to-end voice-input flows
  (long-press → record → ASR → polish → insert), permission gating,
  cancellation semantics, or settings toggles that affect the pipeline.
  Trigger phrases: "全链路", "pipeline", "上滑取消", "识别后写入",
  "录音流程", "权限引导", "ModelStore", "首次启动".
  Read this BEFORE making changes spanning multiple service /
  audio / asr / llm modules.
---

# ASR + Polish Pipeline 约束

OpenFlow 把用户的一次"长按 → 说话 → 松手"动作映射到下面的状态机：

```
IDLE (悬浮球贴边)
  │ ACTION_DOWN
  ▼
ARMING (250ms 缓冲；上滑取消窗口)
  │ 持续按下 250ms
  ▼
RECORDING (AudioRecord 启动；FGS 显示通知；波形/RMS 流)
  │ ACTION_MOVE 上滑超过阈值
  ▼
CANCELING (UI 变红；松手丢弃)
  │ ACTION_UP
  ▼
(if CANCELING) → 丢弃 PCM，FGS stopSelf
(if RECORDING) → stop AudioRecord → transcribe → (polish?) → insert → FGS stopSelf
```

## 必读 gate

- [`../../../AGENTS.md`](../../../AGENTS.md)
- [`../../../docs/dev-harness/pipeline/rules.md`](../../../docs/dev-harness/pipeline/rules.md)
- [`../whisper-cpp-skill/SKILL.md`](../whisper-cpp-skill/SKILL.md)
- [`../llama-cpp-skill/SKILL.md`](../llama-cpp-skill/SKILL.md)

## 核心不变约束

### MUST: 三动作 Intent 接口

`RecordingForegroundService` 只接受三个 action：
- `ACTION_START`：开始录音；FGS 启动并保持
- `ACTION_COMMIT`：停录音 → ASR → polish → 写入 → stopSelf
- `ACTION_CANCEL`：停录音 → 丢弃 PCM → stopSelf

**禁止**新增 action（"暂停"、"切换语言"等）使 FGS 状态机复杂化。设置变化通过 DataStore 流。

### MUST: 录音状态机由 FloatingBallView 单点判定

`FloatingBallView.state` 是手势状态机的真相源。其他组件（FGS / Accessibility）只接收已判定的 `onRecordStart / onRecordCommit / onRecordCancel` 回调。
**禁止**在 FGS 里再判断"是不是要取消"——会引入双源真相 bug。

### MUST: ASR / polish 失败软降级

```kotlin
val raw = transcribe(pcm)                  // 模型未就绪 → 返回 ""
val final = if (polish) polish(raw, llmId) else raw  // 模型未就绪 → 返回 raw
if (final.isNotBlank()) insertIntoFocusedEditable(final)
```

**禁止**抛异常或弹 Toast——失败应该静默；用户通过模型 tab 自己解决。

### MUST: 模型加载是惰性的

`WhisperEngine` / `PolishEngine` 第一次 transcribe / polish 时才 `nativeInit`。后续会复用同一 handle。
不要在 FGS `onCreate` 中预加载——会让"录音准备就绪"延迟 1-3 秒（whisper.cpp load 模型时间）。

### MUST NOT: 模型切换不重启 FGS

切换 LLM 模型（例如从 Qwen2.5-1.5B 切到 Qwen3-0.6B）后，`PolishEngine` 必须重建。当前实现：
```kotlin
private var polishEngine: PolishEngine? = null
```
切换时 FGS 会 stopSelf 然后下次创建。如果将来引入"FGS 长期常驻"，必须在 settings 切换时调 `polishEngine?.release(); polishEngine = null`。

### MUST: 焦点节点引用读自 AccessibilityService.instance

`RecordingForegroundService.insertIntoFocusedEditable`：
```kotlin
val node = FlowAccessibilityService.instance?.currentEditableNode()
```
不要把 node 当作 `Parcelable` 通过 Intent 传——它依赖 a11y 系统的进程间 binder，过 Parcel 后失效。

### MUST: 上滑取消阈值用 touchSlop 倍数

```kotlin
val cancelSwipeThreshold = touchSlop * 6
```
不要硬编码像素值——不同密度屏体验不一致。

### MUST: 长按延时尊重设置

`SettingsStore.swipeUpCancelEnabled = false` 时 `FloatingBallView` 必须无视 `CANCELING` 状态。**当前 v0.1 没接通**，TODO 见 `incidents/INC-PIPELINE-FUTURE-0001.md`。

### MUST NOT: 跨进程持久化 PCM

PCM 只能在 FGS 内存里活到 ASR 完成；**禁止**写到 `cacheDir` / `filesDir` 做"重放"。理由：录音是用户隐私，长存增加合规风险。

## 设置项与 pipeline 的对应

| Setting | 作用点 |
|---|---|
| `polishEnabled` | `handleCommit` 是否调 `polish` |
| `swipeUpCancelEnabled` | `FloatingBallView` 是否进入 CANCELING（待接通） |
| `waveformEnabled` | `FloatingBallView` 是否展开为波形 UI（待接通） |
| `editBeforeInsertEnabled` | 写入前是否弹卡片让用户改（待接通） |
| `whisperModelId` | `transcribe` 选哪个 whisper 模型 |
| `llmModelId` | `polish` 选哪个 LLM 模型 |
| `mirrorBase` | `ModelDownloader` 用 HF 官方还是 hf-mirror |

## 测试策略

- 单测：`WhisperEngine` / `PolishEngine` 的 `ensureLoaded` 软降级路径（需要 mock JNI）
- 集成：完整 FGS 状态机，至少要覆盖 ACTION_CANCEL 在录音中断的资源释放
- 真机：长按 → 说话 5 秒 → 写入到便签、微信、浏览器地址栏三个 App
- 真机：录音中上滑取消，确认 logcat 没看到 transcribe / polish 调用

## 参考

- `app/src/main/java/com/hank/flow/open/service/RecordingForegroundService.kt`
- `app/src/main/java/com/hank/flow/open/service/FloatingBallView.kt`
- `app/src/main/java/com/hank/flow/open/service/FlowAccessibilityService.kt`
- `app/src/main/java/com/hank/flow/open/audio/AudioRecorder.kt`
- `app/src/main/java/com/hank/flow/open/insertion/TextInserter.kt`
- `app/src/main/java/com/hank/flow/open/settings/SettingsStore.kt`

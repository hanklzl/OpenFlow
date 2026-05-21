# ASR + Polish Pipeline Rules

"录音 → ASR → polish → 写入"全链路 + engine wrapper + settings 影响面的强约束。

## MUST

1. **`RecordingForegroundService` 只接受 3 个 action**：`ACTION_START` / `ACTION_COMMIT` / `ACTION_CANCEL`。新增需求优先考虑放进 Settings/DataStore，不要扩展 action。
2. **手势状态机由 `FloatingBallView` 单点判定**：FGS / AccessibilityService 只接收已判定的 `onRecordStart / onRecordCommit / onRecordCancel`。
3. **ASR / polish 软降级**：模型未就绪（`ModelStore.isReady() == false`）时 transcribe 返回 `""`，polish 返回原文。**不抛异常 / 不弹 toast**。
4. **`WhisperEngine` / `PolishEngine` 通过 Kotlin `Mutex` 串行化** native handle 访问。
5. **模型加载是惰性的**：第一次 transcribe / polish 时才 `nativeInit`，避免拖慢"录音就绪"。
6. **焦点节点引用读自 `FlowAccessibilityService.instance?.currentEditableNode()`**，不要通过 Parcel / Bundle 传 `AccessibilityNodeInfo`。
7. **PCM 仅活在内存**：录音结束 → 转写 → 立即释放。不写到磁盘做"重放"。
8. **`Dispatchers.Default` 上跑 transcribe / polish**：避免阻塞 IO 线程池。

## MUST NOT

1. **禁止在 FGS 里再判断"要不要取消"**：双源真相 bug。取消只由 `FloatingBallView` 状态机判定。
2. **禁止把 PCM 缓冲跨进程传**：用户隐私，且 IPC 带宽限制大数据。
3. **禁止在录音中切换模型生效**：模型切换在下次 `ACTION_START` 时才应用。
4. **禁止给同一 engine handle 并发触发 `nativeTranscribe` / `nativeGenerate`**：whisper / llama context 不是 thread-safe。
5. **禁止在 PolishEngine 里写 prompt 拼接逻辑**：所有 prompt 模板放 `PolishPrompt`，便于版本对比和单测。

## SHOULD

1. 模型切换时调用 `engine?.release(); engine = null`，让下次重建。当前 FGS 每次 stopSelf 后会重建，自然清空。
2. 长录音 (>60s) 后续应分段处理（暂未实现）；目前 1024 ctx 对极长录音有截断风险。
3. 录音中实时 RMS 流（`AudioRecorder.frames`）可用于 UI 波形——当前 `FloatingBallView` 暂未消费。

## 设置项 → pipeline 影响表

| Setting | 当前接通状态 | 作用点 |
|---|---|---|
| `polishEnabled` | ✅ | `handleCommit` 是否调 `polish` |
| `swipeUpCancelEnabled` | ⚠️ 待接通 | `FloatingBallView` 是否进入 CANCELING |
| `waveformEnabled` | ⚠️ 待接通 | `FloatingBallView` 是否展开为波形 UI |
| `editBeforeInsertEnabled` | ⚠️ 待接通 | 写入前是否弹卡片让用户改 |
| `whisperModelId` | ✅ | `transcribe` 选哪个 whisper 模型 |
| `llmModelId` | ✅ | `polish` 选哪个 LLM 模型 |
| `mirrorBase` | ✅ | `ModelDownloader` 用哪个 HF 镜像 |

## 相关 incidents

- (暂无；首次违反时补 incident)

## 相关代码

- `app/src/main/java/com/hank/flow/open/service/RecordingForegroundService.kt`
- `app/src/main/java/com/hank/flow/open/audio/AudioRecorder.kt`
- `app/src/main/java/com/hank/flow/open/asr/WhisperEngine.kt`
- `app/src/main/java/com/hank/flow/open/llm/PolishEngine.kt`
- `app/src/main/java/com/hank/flow/open/llm/PolishPrompt.kt`
- `app/src/main/java/com/hank/flow/open/insertion/TextInserter.kt`
- `app/src/main/java/com/hank/flow/open/settings/SettingsStore.kt`
- `app/src/main/java/com/hank/flow/open/model/ModelStore.kt`

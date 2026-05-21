# AGENTS.md

面向本仓库 AI 编码助手的项目工作指引。

## 项目概览

OpenFlow 是一个**类 Typeless Flow 的 Android 语音输入助手**：在任意 App 输入框获得焦点时显示悬浮球，长按悬浮球录音，使用 **whisper.cpp 离线 ASR** 转写，可选用 **llama.cpp + Qwen2.5/Qwen3.5 端侧 LLM** 做口语到书面表达的润色，最后通过无障碍服务把文本写入光标位置。

包名 `com.hank.flow.open`，目标 Android 12+ (minSdk 31)。

## 文档入口（先读）

在动手实现前，按以下顺序读取文档：

1. `docs/DOCS_STATUS.md`（文档状态索引：当前规范 / 当前参考 / 历史记录）
2. `AGENTS.md`（当前仓库工作约束）
3. `docs/dev-harness/INDEX.md`（开发守门总入口；按域跳到对应 rules.md / incidents.md）
4. 与任务相关的"当前规范"文档

强制规则：

- 文档之间的引用必须使用相对路径，禁止使用 `/Users/...` 绝对路径。
- 跨仓库引用也必须使用相对路径（例如 `../MusicFreeAndroid/...`）。
- `docs/superpowers/plans/*.md`（若存在）默认视为历史执行快照，不可直接当作当前执行指令。

## Dev Harness 强制入口

任何涉及下述域的改动，动手前必须读取对应 rules.md：

- UI / Compose Screen：`docs/dev-harness/ui/rules.md`
- 无障碍服务 + 悬浮窗 + FGS：`docs/dev-harness/service/rules.md`
- JNI / 原生构建 / submodule：`docs/dev-harness/native/rules.md`
- ASR + Polish pipeline：`docs/dev-harness/pipeline/rules.md`
- 测试代码：`docs/dev-harness/test/rules.md`

每条 rule 都关联一条或多条 incident（`docs/dev-harness/incidents/index.md`）。
违反 rules.md 中标记 MUST / MUST NOT 的条款由人工 review 拦截；本地可跑 `bash scripts/dev-harness/check.sh` 自查。

## 项目记忆与守门约束

- 强约束：`docs/dev-harness/<area>/rules.md`
- 历史踩坑：`docs/dev-harness/incidents/index.md`（按 ID 反查到 area + rule + guard）
- AI 工作流：见 `.agents/skills/<area>-skill/`，软链到 `.claude/skills/`、`.codex/skills/`
- 历史决策快照：`docs/superpowers/specs/` 与 `plans/`（仅参考，不是当前规则源）
- 个人会话偏好（Claude Code only）：`~/.claude/projects/.../memory/MEMORY.md`，不进仓库

## Git Worktree 开发约束

- 默认使用 `git worktree` 进行功能开发，避免在主工作区直接切换或堆叠功能分支。
- worktree 默认创建在仓库根目录的 `.worktrees/` 下，路径格式为 `.worktrees/<branch-name>`。
- 创建本地 worktree 前必须确认 `.worktrees/` 已被忽略，避免 worktree 内容进入版本控制。
- 若用户未指定分支名，使用与任务语义一致的简短分支名。
- 文档、脚本和说明中引用 worktree 路径时使用相对路径，避免写入 `/Users/...` 绝对路径。
- worktree 分支合并回 `main` 时必须使用 `git merge --squash`，把分支上所有 commit 压成单个 commit。Commit message 使用 conventional commits 格式（`feat(scope): ...`、`fix(scope): ...`、`docs(scope): ...` 等）简要说明变更类型与范围；正文可补一两句"做了什么、为什么"，不要把每一步过程写进 message。提交使用中文。

## 构建命令

```bash
./gradlew :app:assembleDebug                  # 构建 Debug APK（含 NDK 原生编译，首次约 5-15 分钟）
./gradlew :app:assembleRelease                # 构建 Release APK（需签名环境变量）
./gradlew :app:testDebugUnitTest              # 单元测试
./gradlew :app:connectedDebugAndroidTest      # 仪器测试（需设备/模拟器）
./gradlew :app:lintDebug                      # 发布前 lint，日常 Debug 不需要

# 原生层
./gradlew :app:externalNativeBuildDebug       # 仅触发 CMake 构建（whisper.cpp + llama.cpp）
./gradlew :app:configureCMakeDebug            # 仅做 CMake configure

# Submodule 维护
git submodule update --init --recursive       # 首次 / 拉新分支后
git submodule update --remote --merge         # 升级到 upstream HEAD（务必 review 兼容性）
```

本地功能收尾默认验证 Debug 构建；不要求验证 Release 构建或 lint。
Release 构建只在签名环境变量齐备或任务明确涉及发布/签名时验证。

## 当前构建基线（已校验）

- minSdk：31 (Android 12)
- targetSdk：35 (Android 15)
- compileSdk：35
- Java compatibility：`VERSION_17`
- JVM toolchain：JDK 21
- Gradle Wrapper：`8.10.2`
- AGP：`8.7.3`
- Kotlin：`2.0.21`
- Compose BOM：`2024.10.01`
- NDK：`27.0.12077973`
- CMake：`3.22.1`
- ABI Filters：仅 `arm64-v8a`

## 模块架构

当前为单模块 `:app`，按职责分包。仅当复杂度需要时拆分独立 Gradle module。

```
app/src/main/
├── java/com/hank/flow/open/
│   ├── MainActivity.kt / OpenFlowApp.kt
│   ├── service/   # FlowAccessibilityService, OverlayController, FloatingBallView, RecordingForegroundService
│   ├── audio/     # AudioRecorder (16kHz mono PCM)
│   ├── asr/       # WhisperJni, WhisperEngine
│   ├── llm/       # LlamaJni, PolishEngine, PolishPrompt
│   ├── insertion/ # TextInserter（通过 AccessibilityNodeInfo 写入焦点 EditText）
│   ├── model/     # ModelCatalog, ModelStore, ModelDownloader (OkHttp + Range)
│   ├── settings/  # SettingsStore (DataStore)
│   ├── permission/# PermissionChecks
│   └── ui/        # Compose: home, modeldownload, settings, theme
└── cpp/
    ├── CMakeLists.txt              # 一个 .so 同时链 whisper+llama
    ├── jni_whisper.cpp
    ├── jni_llama.cpp
    └── third_party/
        ├── whisper.cpp/  # git submodule
        └── llama.cpp/    # git submodule
```

## 技术栈

- UI：Jetpack Compose + Material3
- 架构：单模块 + Service 边界 + Coroutine + Flow
- DI：暂不引入（项目体量小，按需引入 Hilt）
- 原生：whisper.cpp + llama.cpp（共享 ggml）+ JNI
- 录音：`AudioRecord`（`VOICE_RECOGNITION` source，16kHz/mono/PCM16）
- 输出：`AccessibilityNodeInfo.ACTION_SET_TEXT`（光标位置插入）
- 偏好：DataStore Preferences
- 网络：OkHttp（用于模型下载，Range 断点续传）
- 异步：Kotlin Coroutines + Flow

## 核心设计约束

### Service / Overlay 边界

新增或修改 `FlowAccessibilityService`、`OverlayController`、`RecordingForegroundService`、`FloatingBallView` 或其调用链前，必须读取并遵守 [docs/dev-harness/service/rules.md](docs/dev-harness/service/rules.md)。

- 悬浮窗只能由 `AccessibilityService` 触发显示与隐藏，不可由 Activity 或 FGS 直接 `WindowManager.addView`。
- 录音 FGS 启动必须使用 `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)`（Android 14+ 强制）。
- `RecordingForegroundService` 通过 `Intent.ACTION_START / ACTION_COMMIT / ACTION_CANCEL` 三动作交互；不引入 binder。
- 悬浮球长按 → 录音的延迟 `armDelayMs` 不得低于 200ms（防误触），默认 250ms。
- 焦点节点缓存（`lastEditableNode`）只在 `TYPE_VIEW_FOCUSED` / `TYPE_VIEW_TEXT_SELECTION_CHANGED` 中更新；`TYPE_WINDOW_STATE_CHANGED` 时必须置空。
- AccessibilityService 实例引用（`FlowAccessibilityService.instance`）只用于服务内 / FGS 跨进程不在用，禁止跨 Activity 持有。

### Native / JNI 约束

新增或修改 `cpp/` 下任意文件、`CMakeLists.txt`、submodule 版本或 JNI 签名前，必须读取并遵守 [docs/dev-harness/native/rules.md](docs/dev-harness/native/rules.md)。

- whisper.cpp 与 llama.cpp **共享 ggml**：CMakeLists 必须先 `add_subdirectory(third_party/llama.cpp)`，再 `add_subdirectory(third_party/whisper.cpp)`；whisper.cpp 的 `if (NOT TARGET ggml)` 守卫保证不重定义。**禁止改顺序**。
- 仅打 `arm64-v8a`；不引入 armeabi-v7a / x86 / x86_64（覆盖 99% Android 12+ 设备，APK 体积 -3x）。
- JNI 方法签名 `Java_com_hank_flow_open_<package>_<class>_<method>` 必须与 Kotlin `external` 声明精确匹配；改 Kotlin 包名 / 类名必须同步改 cpp 入口。
- whisper PCM 输入：JNI 接收 `jshortArray` 后必须除以 `32768.0f` 转 float，**禁止**让 Kotlin 侧做归一化。
- llama 生成：`llama_memory_clear(memory, true)` 必须在每次 `nativeGenerate` 开头清 KV，否则上次会话的 KV 会污染本次润色。
- Submodule 升级必须先在本地完整跑通 `:app:assembleDebug`，再 commit。

### ASR + Polish Pipeline 约束

修改 `RecordingForegroundService` 流水线、`WhisperEngine`、`PolishEngine`、`AudioRecorder` 或 `TextInserter` 前，必须读取并遵守 [docs/dev-harness/pipeline/rules.md](docs/dev-harness/pipeline/rules.md)。

- 单次会话的"录音 → ASR → polish → 写入"链路在 `Dispatchers.Default` 上单线程串行执行；不得并发触发同一引擎实例。
- `WhisperEngine` 与 `PolishEngine` 各自的 `nativeHandle` 通过 Kotlin `Mutex` 串行化访问。
- 模型未就绪（`ModelStore.isReady() == false`）时，ASR 返回空串，polish 直接返回原文；不抛异常、不阻塞 UI。
- `TextInserter.insertAtCursor` 优先用 `ACTION_SET_TEXT` 整体替换；不依赖剪贴板（`ACTION_PASTE` 在部分系统下不可靠）。
- 长按取消（`onRecordCancel`）必须保证：1) FGS 立即停 `stopSelf()`；2) AudioRecord 释放；3) 不调用 ASR / polish。

### UI / Compose 约束

新增或修改 Compose Screen 前，必须读取并遵守 [docs/dev-harness/ui/rules.md](docs/dev-harness/ui/rules.md)。

- 主应用三 tab：`主页 / 模型 / 设置`，分别对应 `HomeScreen / ModelDownloadScreen / SettingsScreen`。
- 长列表必须用 `LazyColumn`，不要把多个 Card 直接放在 `Column { verticalScroll() }` 里（少量 < 10 项可放过）。
- 权限状态由 Lifecycle `ON_RESUME` 触发刷新，**禁止**使用 `LaunchedEffect(Unit)` 单次拉取。
- 跨 Activity 重建必须存活的状态用 DataStore（SettingsStore）；Activity 内瞬态用 `remember` / `rememberSaveable`。
- 应用 UI 文本默认中文；新增 string 走 `res/values/strings.xml`。

### 模型 / 下载约束

- `ModelCatalog` 是不可变的内置清单；新增模型加 `ModelEntry` 字段，不要在运行时构造 entry。
- 下载使用 OkHttp + `Range` header 断点续传；写到 `<filename>.part`，校验完后 `renameTo(target)`。
- HuggingFace 镜像源在 `SettingsStore.mirrorBase` 控制，默认 `https://huggingface.co`，可切到 `https://hf-mirror.com`。
- 模型文件存放在 `context.filesDir/models/`；**禁止**写到 `cacheDir`（首次清理会丢失），也禁止用外部存储（权限复杂）。
- SHA-256 校验失败必须删除半成品并提示，不可静默使用损坏文件。

### 设置项约束

- 所有用户可见开关都走 `SettingsStore` (DataStore Preferences)。
- DataStore key 重命名 = 老用户配置丢失；MUST 保持向后兼容：要么不改 key，要么补迁移读旧 key 一次。
- 新增设置项默认值必须保证"首次使用零配置可用"：润色默认开、上滑取消默认开、波形默认开、识别后确认默认关、镜像默认官方源。

## 权限模型

依次申请：
1. `RECORD_AUDIO`（runtime，`ActivityResultContracts.RequestPermission`）
2. `POST_NOTIFICATIONS`（API 33+ runtime）
3. `SYSTEM_ALERT_WINDOW`（Settings 跳转 `ACTION_MANAGE_OVERLAY_PERMISSION`）
4. `FlowAccessibilityService`（Settings 跳转 `ACTION_ACCESSIBILITY_SETTINGS`）

`FOREGROUND_SERVICE_MICROPHONE` 在 manifest 声明，不需要 runtime 请求。

## 验收闸门

- 编译、单测、模拟器/真机验证、最终 review 集中执行。
- 默认构建闸门 `:app:assembleDebug`；不要因缺少 Release 签名而阻塞普通功能收尾。
- **运行态验收优先于"代码看起来没问题"的乐观判断**。
- 涉及悬浮球、录音、文本写入、模型加载的改动必须真机/模拟器跑通"长按 → 说话 → 写入"全链路。

## 当前优先事项（长期有效）

1. 在真机上验证 Qwen2.5-1.5B / Qwen3.5-2B / Qwen3.5-0.8B 三个 LLM 选项的实际下载路径与推理质量。
2. 完善 `FloatingBallView` 的实时波形可视化（已有 `Frame.rms` 数据流，只是没接到 view）。
3. 完善"识别后手动编辑确认"卡片（设置项已就位）。
4. Release 构建 + R8 + `@Keep` JNI 类的 ProGuard 规则。
5. 长录音 (>60s) 分段处理。

## 迭代工作流

1. 读 `AGENTS.md` 与对应 `docs/dev-harness/<area>/rules.md`
2. 将任务拆分为可执行、可验证的小步
3. 涉及 UI 时结合代码 + 真机/模拟器验证；不仅看截图
4. 涉及原生层时务必同时跑 `:app:assembleDebug` 验证 native 编译
5. 涉及悬浮球 / 无障碍 / FGS 时必须真机跑通至少一次
6. 先做运行态验收，再给出完成结论
7. 将过程中的错误和修正沉淀到 `docs/dev-harness/incidents/`

## 分析规则

- 不仅依赖截图，必须同时分析代码上下文
- 主动识别：缺失抽象、过时文档、隐藏前置依赖、并发风险
- 涉及 JNI 时要核对 cpp 与 Kotlin 两侧签名匹配
- 涉及 Service 时要核对 manifest 声明与代码行为匹配

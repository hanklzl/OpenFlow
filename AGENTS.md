# AGENTS.md

面向本仓库 AI 编码助手的项目工作指引。

## 项目概览

OpenFlow 是一个**类 Typeless Flow 的 Android 语音输入助手**：在任意 App 输入框获得焦点时显示悬浮球，长按悬浮球录音，使用 **whisper.cpp 离线 ASR** 转写，可选用 **llama.cpp + Qwen2.5/Qwen3 端侧 LLM** 做口语到书面表达的润色，最后通过无障碍服务把文本写入光标位置。

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
- **agent 自动暂存层**：`docs/dev-harness/memory/`（由 `memory-sediment-skill` 在开发收尾时写入；由 `harness-curator-skill` 自主合入到 rules / incidents。详见 [`docs/dev-harness/memory/README.md`](docs/dev-harness/memory/README.md)）
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

应用按 productFlavor 双维度出包：`backend`(cpu/gpu) × `abi`(arm64/x64)。

```bash
# 应用构建
./gradlew :app:assembleDebug                  # 日常 Debug：只编 cpu 两 ABI（cpuArm64Debug + cpuX64Debug），含 NDK 原生编译
./gradlew :app:assembleCpuArm64Debug          # 只编单个变体（真机 arm64；模拟器用 :app:assembleCpuX64Debug）——比 assembleDebug 快一半
./gradlew :app:assembleRelease                # 一条命令出全部 Release 包：cpu arm64-v8a + cpu x86_64 + gpu arm64-v8a（需签名环境变量）
./gradlew :app:assembleGpuArm64Debug -PopenflowGpuDebug=true   # 本地验 GPU 包（gpuDebug 默认禁用；含 Vulkan，多花 ~3 分钟）
./gradlew :app:testCpuArm64DebugUnitTest      # 单元测试（Robolectric/JVM，ABI 无关；旧名 testDebugUnitTest 已随 flavor 失效）
./gradlew :app:connectedCpuArm64DebugAndroidTest   # 仪器测试（需设备/模拟器）
./gradlew lint                                # 发布前 lint，日常 Debug 不需要

# 编译加速：ccache（对象级缓存，跨 worktree / clean / CI 命中；CMakeLists 自动探测）
brew install ccache                           # 本机一次性安装（CI 用 apt-get install ccache）
ccache --set-config base_dir=$HOME            # 跨 worktree 命中所需（配合 hash_dir=false）；只设一次
ccache --set-config hash_dir=false
ccache -s                                     # 查看命中率（冷构建后再跑一次应见高 hit）

# Submodule 维护
git submodule update --init --recursive       # 首次 / 拉新分支后
git submodule update --remote --merge         # 升级到 upstream HEAD（务必 review 兼容性）
```

GPU/CPU 不再用 `-POpenflowEnableVulkan/-POpenflowEnableOpenCl`，改由 `gpu` flavor 内置；
Release 一条 `assembleRelease` 同时出 CPU/GPU 三包，产物路径按 flavor 隔离、互不覆盖。

本地功能收尾默认验证 Debug 构建；不要求验证 Release 构建或 lint。
Release 构建只在签名环境变量齐备或任务明确涉及发布/签名时验证。

## 当前构建基线（已校验）

- minSdk：31 (Android 12)
- targetSdk：35 (Android 15)
- compileSdk：35
- Java compatibility：`VERSION_17`
- JVM toolchain：JDK 17（CI 与本地统一，AGP 9.2 最低要求）
- Gradle Wrapper：`9.4.1`
- AGP：`9.2.0`
- Kotlin：`2.3.21`
- Compose BOM：`2026.05.00`
- NDK：`29.0.14206865`（r29，最新稳定版；r28+ 默认 16KB 段对齐，无需 CMake 额外 flag）
- CMake：`3.22.1`
- ccache：native 编译加速（CMakeLists 顶部自动探测；未装则无缓存、行为同旧）。跨 worktree 命中需全局 `base_dir=$HOME` + `hash_dir=false`
- ABI / 出包：productFlavor `backend`(cpu/gpu) × `abi`(arm64/x64)。每变体单 ABI（`ndk.abiFilters`），取代旧 `splits.abi`。Release 出 cpu `arm64-v8a` + cpu `x86_64` + gpu `arm64-v8a`；gpu 不出 `x86_64`（`gpu+x64` 变体禁用），gpuDebug 默认禁用

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
- 仅打 `arm64-v8a` + `x86_64`；`x86_64` 用于本地 emulator 验证。不引入 armeabi-v7a / x86（覆盖 Android 12+ 主流设备，同时保留 emulator 可运行性）。
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

1. 在真机上验证 Qwen2.5-1.5B / Qwen3-1.7B / Qwen3-0.6B 三个 LLM 选项的实际下载路径与推理质量。
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
8. 会话收尾时由 [`memory-sediment-skill`](.agents/skills/memory-sediment-skill/SKILL.md) 评估是否往 `docs/dev-harness/memory/` 沉淀本次新发现（架构理解 / 踩过的坑 / 编码规范）；判定规则与去重见该 skill 的 references
9. 定期由 [`harness-curator-skill`](.agents/skills/harness-curator-skill/SKILL.md) 审计漂移、压缩 memory、晋升稳定条目到 rules / incidents——**agent 自主合入**，细粒度 commit，可 `git revert` 回滚

## 分析规则

- 不仅依赖截图，必须同时分析代码上下文
- 主动识别：缺失抽象、过时文档、隐藏前置依赖、并发风险
- 涉及 JNI 时要核对 cpp 与 Kotlin 两侧签名匹配
- 涉及 Service 时要核对 manifest 声明与代码行为匹配

---
name: openflow-release
description: >
  Use when cutting a new OpenFlow release: 决定 vX.Y.Z、改 version.properties、
  本地 preflight、推 tag、监控 CI、验收发布产物、回滚 release（含 R8 紧急回滚）。
  Trigger phrases: "发布 OpenFlow", "切版本", "出 release", "preflight",
  "推 tag", "回滚 release", "/openflow-release", "改 version.properties",
  "R8 翻车", "release 崩了", "签名", "keystore".
  Read this BEFORE 创建任何 vX.Y.Z tag 或编辑
  app/build.gradle.kts release 配置 / .github/workflows/android-release-apk.yml /
  version.properties / scripts/release/**.
---

# OpenFlow Release 发布约束

OpenFlow 用「tag 推送 → GitHub Actions 三 job → 签名 split APK + R8 mapping + gh-pages 版本清单」管道做发布。**Skill 是操作者向导而非自动化机器人**：被调用时逐步引导，**不**自动 push、**不**自动 tag、**不**自动 commit；在「真机冒烟」「推 tag」「观察 CI」三个手动节点必须停下等用户确认。

## 必读 gate

- [`../../../AGENTS.md`](../../../AGENTS.md)（工作树红线、commit message 规范）
- [`../../../RELEASE.md`](../../../RELEASE.md)（首次启用清单、secret 列表、命令模板）
- [`../../../CHANGELOG.md`](../../../CHANGELOG.md)（`<!-- next-release -->` 锚点必留）
- [`../native-build-skill/SKILL.md`](../native-build-skill/SKILL.md)（R8 + JNI 关联）
- [`../whisper-cpp-skill/SKILL.md`](../whisper-cpp-skill/SKILL.md)（release 装机 JNI 失败诊断入口）
- [`../llama-cpp-skill/SKILL.md`](../llama-cpp-skill/SKILL.md)（同上）

## 触发场景与不该触发

| 应触发 | 不该触发 |
|---|---|
| 「发布 v0.X.0」「切下一个版本」 | debug 构建失败 → 普通 Android 排查 |
| 「跑 preflight」「推 tag」 | 单测红 → `test-stability-skill` |
| 「release APK 装机即崩」「R8 翻车」 | 普通功能开发 / Compose UI 改动 |
| 「改 `version.properties`」「versionCode 怎么算」 | Native build 编译失败 → `native-build-skill` |
| 「签名失败」「keystore 找不到」 | ASR/llm 全链路 bug → `accessibility-pipeline-skill` |
| 「mapping 反混淆」「拿到崩溃栈怎么还原」 | 普通 bugfix 出 patch（无需走 release 流程） |

## 核心不变约束 MUST / MUST NOT

- **MUST**：`version.properties` 是版本号唯一来源。CI 「Validate version consistency」step 校验 tag 字面（去 `v` 前缀）必须等于 `versionName`，且 `versionCode == MAJOR*10000 + MINOR*100 + PATCH`。
- **MUST**：tag 推送前本地 `bash scripts/release/preflight.sh vX.Y.Z`，任意 step 退出码 ≠ 0 立即中止，**不要**绕过。
- **MUST**：真机装签名 release APK 验证全链路一次（R8 + JNI 问题**只在签名 release APK + 真机**能复现，debug 与 emulator 都看不见）。两种走法等效：
  - **自动**：`bash scripts/release/smoke-test.sh` 退出 0
  - **手动**：长按悬浮球 → 录中文 → ASR → 润色 → `ACTION_SET_TEXT` 写入第三方 EditText
- **MUST**：在 `.worktrees/release-vX.Y.Z` worktree 内操作，遵循 AGENTS.md「任何变更走 worktree」红线；不在 main 直接改。
- **MUST**：`mapping.zip` 必须随 GitHub Release 上传。线上崩溃栈反混淆**唯一**依赖该文件，丢失即永远无法还原 R8 后的栈。
- **MUST**：首次发布前 `gh secret list --env release` 核对 7 个 secret 齐备（4 个 `ANDROID_RELEASE_*` + 2 个 `LOGAN_*` + 可选 `ANTHROPIC_API_KEY`）。
- **MUST**：commit + tag 推送顺序固定为：先推 branch（`git push origin release-vX.Y.Z`）再推 tag（`git push origin vX.Y.Z`）。颠倒会让 CI 在 main 还没拿到 commit 时就跑 changelog prepend，导致冲突。
- **MUST NOT**：跳过 R8 mapping 上传步骤。哪怕「这次只是 hotfix」也必须留 mapping。
- **MUST NOT**：手动 push 到 `gh-pages` 分支。CI 是唯一写入方；手动推会让下次 CI rebase 失败。
- **MUST NOT**：用 `git tag -f` 强制覆盖已存在的 tag。回滚必须走「删 → 重推」流程（见下方回滚章节），强推会让已下载客户端的 update 检查混乱。
- **MUST NOT**：在 main 直接 push 应急 patch。即使是关 R8 这种 1 行改动，也走 worktree → squash 流程。

## End-to-end 发布步骤（7 步）

### 1. 决定 vX.Y.Z

语义化版本：
- 仅 bug 修复 / 文案 / 依赖小升 → **patch**（如 `v0.1.0` → `v0.1.1`）
- 用户感知的新功能 / 行为变化 → **minor**（如 `v0.1.0` → `v0.2.0`）
- 不兼容变化 / Manifest 重大调整 / 数据迁移 → **major**（`v0.X.Y` → `v1.0.0`）

### 2. 建 worktree

```bash
cd /Users/zili/code/android/OpenFlow
git worktree add .worktrees/release-vX.Y.Z -b release-vX.Y.Z
cd .worktrees/release-vX.Y.Z
```

### 3. 改 `version.properties`

公式：`versionCode = MAJOR*10000 + MINOR*100 + PATCH`。先算一次：

```bash
python3 -c "M,m,p=map(int,'X.Y.Z'.split('.')); print(M*10000+m*100+p)"
```

例：`v0.1.0` → 100，`v0.1.1` → 101，`v0.2.0` → 200，`v1.0.0` → 10000。

写入：
```
versionCode=<算出来的>
versionName=X.Y.Z
```

### 4. 本地 preflight

```bash
source .env.release.local
bash scripts/release/preflight.sh vX.Y.Z
```

必须全绿（version 一致性 / signing env / clean build / split APK 数量 / mapping.txt 存在 / lint / **GPU 包构建**）。任一 step ≠ 0 → 修问题再重跑，**不要**带红推 tag。

> **GPU 实验包**：preflight 与 CI 都会在 CPU 构建之后再跑一次 `./gradlew :app:assembleRelease -POpenflowEnableVulkan=true -POpenflowEnableOpenCl=true`。`build.gradle.kts` 在带任一 GPU 开关时把 `splits.abi` 收窄到 **arm64-v8a 单 ABI**，产出 `OpenFlow-vX.Y.Z-gpu-arm64-v8a.apk`（同 `applicationId`、同 `versionCode`，是 CPU 包的可选替代下载）。这一步要多花 ~5-10 分钟编译 181 个 Vulkan GLSL shader（NDK glslc）+ ~1-2 分钟 OpenCL ICD，故 preflight 本地会跑两次 release 构建、耗时约翻倍。GPU 后端运行时检测失败会自动回退 CPU，**因此 GPU 包不做真机门禁**——GPU init 在测试机失败不阻塞发布（区别于 CPU 链路崩溃）。mapping 仍只发 CPU 版（JVM 栈反混淆已够用）。

操作者本地 `.env.release.local` 缺失？参见 [`references/preflight-checklist.md`](references/preflight-checklist.md) 的「一次性配置」段。

### 5. 真机冒烟（**硬要求**）

装 preflight 输出的 release APK 到真机验证全链路一次。两种走法等效。

#### 自动版（推荐）

**一次性准备**（首次跑前完成）：

```bash
mkdir -p ~/.openflow-smoke-models
# Whisper 最轻档（32 MB）
curl -L -o ~/.openflow-smoke-models/ggml-tiny-q5_1.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin
# LLM 极速档（~400 MB）— 对应 ModelCatalog.llmTinyNewer
curl -L -o ~/.openflow-smoke-models/qwen3-0.6b-q4_k_m.gguf \
  https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf
```

**每次发布前**：

```bash
bash scripts/release/smoke-test.sh
# 退出码：0 通过 / 1 失败 / 2 setup 错（缺设备 / 缺 APK / 缺模型）
```

脚本动作：adb install release APK → push 模型到 /data/local/tmp → broadcast `INSTALL_MODEL_FROM_TMP` 让 app 自己搬到 filesDir/models/（幂等，size 一致跳过 copy）→ broadcast `RUN_ASR_FROM_ASSETS` 跑 jfk.wav → 监听 `OpenFlowLog`：

- ✅ `debug_asset_finished` → 退出 0
- ❌ `debug_asset_abort_*` / `service_start_failed` / `FATAL EXCEPTION` → 退出 1，失败时 `run-as` 尝试拉 Logan db 留 `smoke-fail-logan-<ts>.tar`
- ❌ 60s 超时 → 退出 1

依赖：`com.hank.flow.open.permission.DEBUG_SMOKE`（signature）保护 `DebugAsrReceiver` + `DebugAssetPipelineService` + `DebugModelInstallReceiver`，adb shell 默认通过；外部应用无法触发。

**首次约 50–70s（含 push ~400 MB Qwen3-0.6b），第二次起约 12–18s**。

#### 手动版（fallback）

设备没插 USB / 无 adb / 想验证全 UI 交互流（含 `ACTION_SET_TEXT` 写入第三方 EditText）时仍走人工：

1. 启动 → 三 tab 渲染正常
2. 长按悬浮球 → 录中文 → 释放 → ASR 出文 → 润色 → 文本插入第三方 EditText

如果任意环节崩了或卡住 → 进入下方「R8 紧急回滚」决策树。**真机绿（自动或手动任一）之前不要推 tag**。

### 6. commit + tag + push

```bash
git add version.properties
git commit -m "chore(release): bump to vX.Y.Z"
git tag vX.Y.Z
git push origin release-vX.Y.Z
git push origin vX.Y.Z
```

### 7. 监控 CI

```bash
gh run watch --exit-status
```

三个 job（`build-release-apk` / `publish-release` / `publish-version-manifest`）全绿后做验收（见下条）。任一 job 红 → 看下方「常见故障表」对症修。

## 验收 checklist

CI 全绿后逐条勾：

- [ ] `gh release view vX.Y.Z` 列出 4 个 asset：`OpenFlow-vX.Y.Z-arm64-v8a.apk`、`OpenFlow-vX.Y.Z-x86_64.apk`、`OpenFlow-vX.Y.Z-gpu-arm64-v8a.apk`（实验性 GPU 包，仅 arm64）、`mapping-vX.Y.Z.zip`。CPU APK 大小合理（> 10 MB 但 < 200 MB）；GPU 包因含 Vulkan/OpenCL 后端会明显更大。
- [ ] gh-pages 版本清单 OK：
  ```bash
  gh api repos/hanklzl/OpenFlow/contents/release/version.json?ref=gh-pages --jq .content | base64 -d | jq .
  ```
  字段验证：`schemaVersion == 2`、`variants["arm64-v8a"].sha256` 非空、`variants["x86_64"].sha256` 非空、`variants["gpu-arm64-v8a"].sha256` 非空、`tag == "vX.Y.Z"`。
- [ ] main 上自动 commit `docs(changelog): release vX.Y.Z [skip ci]` 存在：
  ```bash
  git -C /Users/zili/code/android/OpenFlow log --oneline -3
  ```
- [ ] release notes 内容合理（不为空 / 不只是「无变更」字样）。LLM 摘要走 fallback 时仍会列 commit 分类。

## 回滚标准 4 步

适用：tag 已推、CI 已发布产物，但发现包有严重问题需要召回。

1. **删 GitHub Release**：
   ```bash
   gh release delete vX.Y.Z --yes
   ```
2. **删远程 tag**：
   ```bash
   git push --delete origin vX.Y.Z
   ```
3. **revert CHANGELOG 自动 commit**：
   ```bash
   cd /Users/zili/code/android/OpenFlow
   git log --oneline -5   # 找 "docs(changelog): release vX.Y.Z [skip ci]" 的 sha
   # 在新 worktree 里 git revert <sha>，推 PR 合回 main
   ```
4. **若 `gh-pages/release/version.json` 已更新**，需要把 gh-pages 倒到前一 commit：
   ```bash
   git fetch origin gh-pages
   git log origin/gh-pages --oneline -3   # 找到 release vX.Y.Z 之前那条
   git push --force-with-lease origin <prev-sha>:gh-pages
   ```

**警告**：若已有用户下载 APK 并上传过崩溃报告，**不要**删 mapping.zip（哪怕 release 整体删了，也单独存一份）。崩溃栈反混淆唯一依赖。

## R8 紧急回滚

适用：release APK 装机即崩、debug 同步骤正常。

**第一反应：出 patch tag 关 R8**，**不要**先深挖崩溃栈。Mapping.txt 留作下个 minor 修复时反混淆用。

完整命令序列见 [`references/r8-rollback-playbook.md`](references/r8-rollback-playbook.md)。要点：

1. `.worktrees/release-vX.Y.(Z+1)` worktree
2. `app/build.gradle.kts` release buildType：`isMinifyEnabled = false`、`isShrinkResources = false`（保留 `signingConfig` 与 `proguardFiles`）
3. `version.properties` bump patch
4. preflight + 真机冒烟 + push tag

下个 minor 重开 R8 前必须用 mapping.txt 反混淆崩溃栈、定位具体 class/method、加 `-keep` 规则。

## 常见故障表

| 症状 | 根因 | 修复 |
|---|---|---|
| CI 「Validate version consistency」红 | `version.properties` 的 `versionName` 与 tag 名（去 `v` 前缀）字面不等，或 `versionCode` 公式不符 | 改 `version.properties`、删本地 tag、重推 |
| LLM 摘要空 / release notes 仅 commit 列表 | `ANTHROPIC_API_KEY` secret 未设或网络/配额异常 | 非阻塞，CI 仍出 release。设 secret 后下次自动恢复 |
| `mapping.zip` 缺失或 0 字节 | R8 未跑（`isMinifyEnabled=false`）或 packaging step 失败 | 检查 release buildType、看 CI 该 step 日志 |
| R8 后 reflection-heavy 路径崩 | 缺对应 `-keep` 规则 | 用 mapping.txt 反混淆 → 在 `app/proguard-rules.pro` 加规则。当前已覆盖 JNI / DataStore / Logan / OkHttp / Coroutines / enum / SourceFile |
| JNI 新增类后 release 崩、debug 不崩 | R8 把新类改名 → `loadLibrary` 后第一次调用 `UnsatisfiedLinkError` | 在 `app/proguard-rules.pro` 加 `-keep class com.hank.flow.open.<新类> { *; }`，**必须**与 JNI 类声明同 commit 提交 |
| `gh release upload` 失败「already exists」 | 同 tag 已存在 release（CI 重试或回滚后未清干净） | `gh release upload vX.Y.Z <file> --clobber`，或先 `gh release delete vX.Y.Z --yes` |
| CHANGELOG bot push 失败 | main 分支保护阻止 `github-actions[bot]` 直推 | 加 bot bypass 或操作者手动补 changelog 后 PR；不阻断 release 主流程 |
| `keytool` 列不出 alias | `ANDROID_RELEASE_STORE_PASSWORD` 错或 `.jks` 路径错 | 重新 `source .env.release.local`，确认 `keytool -list -v -keystore "$ANDROID_RELEASE_KEYSTORE_PATH"` 能跑通 |
| smoke `debug_asset_abort_no_model` | 模型 INSTALL broadcast 未生效 / 设备已有模型 size 与本机不一致 | 看 logcat `debug_model_install_*`；`smoke-test.sh --force-reinstall` 重传 |
| smoke setup exit 2「缺模型」 | `~/.openflow-smoke-models/` 缺文件 | 按 SKILL 第 5 步「一次性准备」段重下 |
| smoke `service_start_failed` | release APK signature permission 不匹配（用了不同 keystore） | 确认 `.env.release.local` 指向同一个 keystore；先 `adb uninstall com.hank.flow.open` 再试 |

## 首次启用须知（仅对 v0.1.0 等首次 release 适用）

- GitHub Environment `release` 必须先建。命令模板与 secret 列表见 `RELEASE.md` 的「首次启用清单」段。
- `CHANGELOG.md` 仅有 `<!-- next-release -->` 锚点为正常状态。
- 首次 release 的自动 notes 会列**自仓库初始化以来的所有 commit**（`generate-notes.sh` 在「无前任 tag」时 fallback 到根 commit）。CI 跑完后建议手动 trim `CHANGELOG.md` 那条 entry，把不相关的开发期 commit 删掉。
- `gh-pages` 分支由 CI 首跑时 orphan 创建，本地无需预创。
- 强烈建议先在 fork 上推 `v0.0.1` 走全链路一次再到主仓推 `v0.1.0`。注意 fork CI 同样会校验 versionName 与 tag 字面相等。

## 参考

- 当前 release 工作流：`.github/workflows/android-release-apk.yml`
- 当前 R8 规则（55 行）：`app/proguard-rules.pro`
- preflight 脚本：`scripts/release/preflight.sh`
- 版本清单生成：`scripts/release/build-version-json.sh`
- release notes 生成：`scripts/release/generate-notes.sh`
- changelog prepend：`scripts/release/prepend-changelog.sh`

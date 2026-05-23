# Changelog

本项目所有显著变更记录于此。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)；版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- 增加项目对外门面：`README.md` / `README_EN.md`、`LICENSE`（GPL-3.0-or-later）、`THIRD_PARTY_NOTICES.md`，以及基于 Jekyll 的 GitHub Pages 文档站（落地页 + 安装 / 权限引导 / 模型选型 / FAQ / 隐私）。

<!-- next-release -->

## [v0.1.1] - 2026-05-23

### 变更详情

#### 新功能
- feat(asr): 增加 Whisper 词典提示 (17614f3)
- feat(perf): Phase 6 — Vulkan 后端可行性评估 + 试验性 CMake 钩子 (2cb4469)
- feat(perf): Phase 5 — KV prefix 缓存 + pipeline/rules.md 补取消语义与失效条件 (a0efc1e)
- feat(perf): Phase 4 — 流式润色 + 首 token 即写入输入框 (3c03dbc)
- feat(perf): Phase 2/3 — Qwen3 极速档命名 + 录音中并行预热引擎 (a7b9638)
- feat(perf): Phase 1 — whisper 参数收紧 + llama 批量增大 + arm64 dotprod (ec021c4)
- feat(perf): Phase 0 — 引入端到端 pipeline_summary 与 polish metric piggyback (9f2e764)
- feat(history): 新增语音输入历史 Tab，可查看 / 回放 / 复制 / 清理 (494ccdc)

#### 修复
- fix(ci): port v0.1.0 hotfix 到 main — release workflow SIGPIPE + gh-pages init (62e3116)

#### 文档
- docs(changelog): release v0.1.0 [skip ci] (bfc6a1c)
- docs(public): 增加 README / LICENSE / GitHub Pages 主页 (063b8b5)

#### 杂项
- chore(release): bump to v0.1.1 + 修复 prev tag 查找可靠性 (e90347f)

#### 合并
- merge: 合并 main 的 history Tab — 解决 RFS.handleCommit 冲突 (a3c6917)

### 构建产物

- arm64-v8a: `OpenFlow-v0.1.1-arm64-v8a.apk` · 8.1MB · sha256 `0a750077fe20`
- x86_64: `OpenFlow-v0.1.1-x86_64.apk` · 8.4MB · sha256 `4e80504af01b`
- mapping: `mapping-v0.1.1.zip` · sha256 `9961062be541` (R8 反混淆用)

## [v0.1.0] - 2026-05-23

### 变更详情

#### 新功能
- feat(build): debug 包名加 .debug 后缀允许与 release 并存 (48c464d)
- feat(debug): 增加 ASR 与润色自验证链路 (4ff6941)
- feat(model): 支持在「模型」Tab 选择当前 ASR / LLM 模型 (367b94d)
- feat(log): 增加 ASR / 悬浮球 / DebugScreen 链路诊断打点 (47ca4dd)
- feat(release): 接入 vX.Y.Z 发布流水线 + openflow-release-skill (e98b6ff)
- feat(ux): 重设计悬浮球状态机与录音链路反馈 (a7278a8)
- feat(harness): 增加 memory 暂存层 + memory-sediment-skill + 升级 curator 自主合入 (e4880ea)
- feat(build): 增加 x86_64 ABI 支持 (e4bcf9a)
- feat(log): integrate Logan + diagnostic instrumentation + export action (97bcdf2)
- feat(infra): AI coding harness + DebugScreen (71fb62b)

#### 修复
- fix(ci): release workflow 两处修复（SIGPIPE + gh-pages init） (fd9e310)
- fix(fgs): onDestroy 释放 whisper/llama 引擎防止 native 泄漏 (8234c16)
- fix(asr): whisper_full n_threads 4→1 排查 NDK r29 推理 hang (b9ed67d)
- fix(ci): 修复 yes|sdkmanager 在 pipefail 下的 SIGPIPE 假阳性 (4b9076e)
- fix(model): 替换不存在的 Qwen3.5 模型为 Qwen3 系列 (3ffab1a)
- fix(service): 修复阻塞录音→ASR→润色→写入链路的两处缺陷 (3c0c6a7)

#### 文档
- docs(test): 强化功能迭代单测约束 (b632c69)

#### 杂项
- chore(repo): 忽略 .worktrees/ 目录 (cced782)
- chore(build): 升级到 AGP 9.2 + NDK r29 (16KB 段对齐) (9a95945)

#### 其它
- ci: add GitHub Actions debug build workflow (2395c3f)
- Step G: SettingsScreen + tabs + DataStore preferences (e57246f)
- Step E+F: whisper.cpp + llama.cpp submodules, JNI bridge, pipeline wiring (15ec0af)

### 构建产物

- arm64-v8a: `OpenFlow-v0.1.0-arm64-v8a.apk` · 7.5MB · sha256 `f3900adddff3`
- x86_64: `OpenFlow-v0.1.0-x86_64.apk` · 7.8MB · sha256 `fec2c3effee8`
- mapping: `mapping-v0.1.0.zip` · sha256 `c1602a9e70a0` (R8 反混淆用)

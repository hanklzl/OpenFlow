# DOCS_STATUS

OpenFlow 仓库内所有文档的状态分类索引。AI 助手在引用某文档前必须先确认其状态。

## 状态分类

- **当前规范**（current spec）：必须遵守的工作约束 / 强约束
- **当前参考**（current ref）：背景信息 / 设计取舍 / 选型理由；可用作上下文，但不是强约束
- **历史记录**（historical）：已被新规范替代或不再相关；仅供溯源

---

## 当前规范

| 文档 | 范围 |
|---|---|
| [../AGENTS.md](../AGENTS.md) | 仓库工作总指引 |
| [../CLAUDE.md](../CLAUDE.md) | AGENTS.md 的别名（Claude / Codex / Gemini 共用） |
| [dev-harness/INDEX.md](dev-harness/INDEX.md) | Dev Harness 总入口 |
| [dev-harness/ui/rules.md](dev-harness/ui/rules.md) | Compose UI 强约束 |
| [dev-harness/service/rules.md](dev-harness/service/rules.md) | AccessibilityService + Overlay + FGS 强约束 |
| [dev-harness/native/rules.md](dev-harness/native/rules.md) | JNI / CMake / submodule 强约束 |
| [dev-harness/pipeline/rules.md](dev-harness/pipeline/rules.md) | 录音 → ASR → polish → 写入全链路强约束 |
| [dev-harness/test/rules.md](dev-harness/test/rules.md) | 单测 / 仪器测试强约束 |
| [dev-harness/incidents/index.md](dev-harness/incidents/index.md) | 历史踩坑分类归档 |
| [../.agents/skills/whisper-cpp-skill/SKILL.md](../.agents/skills/whisper-cpp-skill/SKILL.md) | whisper.cpp 接入约束 |
| [../.agents/skills/llama-cpp-skill/SKILL.md](../.agents/skills/llama-cpp-skill/SKILL.md) | llama.cpp 接入约束 |
| [../.agents/skills/android-overlay-service-skill/SKILL.md](../.agents/skills/android-overlay-service-skill/SKILL.md) | 悬浮窗 / AccessibilityService / FGS 接入约束 |
| [../.agents/skills/native-build-skill/SKILL.md](../.agents/skills/native-build-skill/SKILL.md) | 原生构建 / submodule 维护 |
| [../.agents/skills/accessibility-pipeline-skill/SKILL.md](../.agents/skills/accessibility-pipeline-skill/SKILL.md) | 全链路 pipeline 设计约束 |
| [../.agents/skills/test-stability-skill/SKILL.md](../.agents/skills/test-stability-skill/SKILL.md) | 测试稳定性 |
| [../.agents/skills/harness-curator-skill/SKILL.md](../.agents/skills/harness-curator-skill/SKILL.md) | Harness 巡检 / drift 检测 |

## 当前参考

| 文档 | 内容 |
|---|---|
| [../.agents/skills/jetpack-compose-expert-skill/SKILL.md](../.agents/skills/jetpack-compose-expert-skill/SKILL.md) | Compose API 参考库（修改 / 复用自 MusicFreeAndroid） |
| `superpowers/specs/` | （暂无）设计 spec / 实现方案蓝图 |

## 历史记录

| 文档 | 状态 |
|---|---|
| `superpowers/plans/*.md` | （暂无）历史执行快照；不作为当前执行指令 |
| `~/.claude/plans/android-cli-android-typeless-flow-app-a-ticklish-toucan.md` | 初版项目脚手架计划（Step A–G）；已落地为当前代码，仅供溯源 |

## 引用规则

- 文档间引用必须用相对路径；禁止 `/Users/...` 绝对路径
- 跨仓库引用也用相对路径（例如 `../MusicFreeAndroid/...`）
- 历史记录类文档**禁止**作为当前执行依据，仅供回溯设计动机
- 当前规范有变化时同步更新本文件

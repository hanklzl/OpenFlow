---
name: harness-curator
description: >
  Use this skill to periodically audit OpenFlow's Dev Harness: detect
  drift between rules.md and current code, recurrence of indexed
  incidents, missing guards, and AI auto-memory entries that should
  be promoted to repo-level rules.
  Trigger phrases: "巡检 harness", "更新错误库", "盘点 incidents",
  "校核 rules", "校核 guard", "同步 user memory 项目级条目",
  "生成 harness 报告".

  This skill produces REPORT.md only — it does NOT modify
  incidents/index.md / rules.md directly. Patches in REPORT
  must be applied by a human reviewer.
---

# Harness Curator Skill (OpenFlow)

定期审计 OpenFlow Dev Harness 与代码事实的一致性。

## 必读 gate

- [`../../../AGENTS.md`](../../../AGENTS.md)
- [`../../../docs/dev-harness/INDEX.md`](../../../docs/dev-harness/INDEX.md)
- [`../../../docs/dev-harness/incidents/index.md`](../../../docs/dev-harness/incidents/index.md)
- 每域 `rules.md`（按需）
- `~/.claude/projects/.../memory/MEMORY.md`（识别项目级、可 promote 条目；个人会话偏好留原位）

## 不变约束

本 skill **不直接修改** `incidents/*.md` / `rules.md` / `INDEX.md`。仅产 `REPORT.md`，patch 由人合入。

## Workflow checklist

1. 创建 worktree：`git worktree add .worktrees/harness-curate-$(date +%F) -b harness/curate-$(date +%F) main`
2. 在 worktree 内执行盘点：
   - **代码事实** vs `rules.md` 描述（grep 关键 MUST/MUST NOT 短语对应的代码位置）
   - 自上次盘点后的 commit log 中是否有"修了又翻车"的提交
   - 新增的 `@Ignore` / `TODO(critical)` 是否登记到 incidents
3. 挖掘候选 incidents：参考 [references/memory-promotion.md](references/memory-promotion.md)
4. 输出 `REPORT.md`：参考 [references/report-template.md](references/report-template.md)
5. 不修改 incidents / rules，REPORT 中的 patch 待用户确认

## 重点扫描点（OpenFlow specific）

| 域 | 关键 grep / 检查 |
|---|---|
| native | `add_subdirectory(third_party/llama.cpp)` 必须先于 `add_subdirectory(third_party/whisper.cpp)`；`abiFilters` 只含 `arm64-v8a`；NDK 版本是否在 AGENTS.md baseline |
| service | manifest 中 `foregroundServiceType="microphone"` + `FOREGROUND_SERVICE_MICROPHONE` 权限；`FlowAccessibilityService.instance` 没有被 Activity 持有 |
| pipeline | `RecordingForegroundService` 只有 3 个 ACTION_*；`PolishEngine` / `WhisperEngine` 都用 `Mutex` |
| ui | `LocalLifecycleOwner` 来自 `androidx.lifecycle.compose`（不是 `androidx.compose.ui.platform`） |
| jni | `Java_com_hank_flow_open_<class>_<method>` 与 Kotlin `external` 一一对应 |
| build | `compileSdk` / `minSdk` / `targetSdk` 与 AGENTS.md baseline 一致 |

## 参考

- [curate-workflow.md](references/curate-workflow.md)
- [drift-detection.md](references/drift-detection.md)
- [memory-promotion.md](references/memory-promotion.md)
- [report-template.md](references/report-template.md)

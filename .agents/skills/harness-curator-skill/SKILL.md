---
name: harness-curator
description: >
  Use this skill to periodically audit OpenFlow's Dev Harness: detect
  drift between rules.md and current code; compact, retract, and promote
  entries from docs/dev-harness/memory/ to rules.md / incidents/index.md;
  recover recurring incidents; and align AGENTS.md baseline with libs.

  Trigger phrases: "巡检 harness", "更新错误库", "盘点 incidents",
  "校核 rules", "校核 guard", "晋升 memory", "压缩 memory",
  "审计 memory", "memory curate", "harness curate", "生成 harness 报告".

  This skill is now AUTONOMOUS: it commits compaction / retraction /
  promotion / drift fixes directly, with one commit per atomic change.
  REPORT.md is still produced as a per-run summary, but it accompanies
  the commits rather than blocking on human review.
---

# Harness Curator Skill (OpenFlow)

定期审计 OpenFlow Dev Harness 与代码事实、与 `docs/dev-harness/memory/` 暂存层的一致性。**自主合入**——细粒度独立 commit，REPORT.md 仅作摘要。

## 必读 gate

- [`../../../AGENTS.md`](../../../AGENTS.md)
- [`../../../docs/dev-harness/INDEX.md`](../../../docs/dev-harness/INDEX.md)
- [`../../../docs/dev-harness/incidents/index.md`](../../../docs/dev-harness/incidents/index.md)
- [`../../../docs/dev-harness/memory/README.md`](../../../docs/dev-harness/memory/README.md)（**暂存层条目格式与生命周期**）
- 每域 `rules.md`（按需）
- [`references/memory-compaction.md`](references/memory-compaction.md)（合并 / 淘汰 / 改写规则）
- [`references/memory-promotion.md`](references/memory-promotion.md)（晋升判定 + 自主合入流程）
- [`references/drift-detection.md`](references/drift-detection.md)（rule ↔ 代码对照）
- [`references/curate-workflow.md`](references/curate-workflow.md)（盘点工作流）

## 不变约束

- **细粒度独立 commit**——每一次 compaction / retraction / promotion / drift fix 单独一个 commit，commit message 必带：操作类型、entry id 或 rule 锚点、操作原因（≤ 1 行）。
- **`_retracted/` 永不删文件**：淘汰条目通过 `status: retracted` + 移到 `memory/_retracted/`，不 `rm`。
- **单次 curate 上限**（防失控）：晋升 ≤ 5、淘汰 ≤ 10、压缩 ≤ 10、drift fix ≤ 10；超额的留到下一次。
- **跨域晋升禁止跳目录**：`pitfalls/` 只能晋升到 `incidents/index.md`；`architecture/` / `conventions/` 只能晋升到 `<area>/rules.md`。
- **rules.md / incidents/index.md / AGENTS.md** 的修改也走自主合入，但每个 diff 必须有对应 memory entry 或 commit log 引证（写在 commit body）。
- REPORT.md 在 worktree 根目录生成，与所有 commit 一起进 PR；REPORT 是日志、不是把关。

## Workflow checklist

按顺序逐步完成：

1. **准备 worktree**：
   ```bash
   git worktree add .worktrees/harness-curate-$(date +%F) -b harness/curate-$(date +%F) main
   cd .worktrees/harness-curate-$(date +%F)
   ```
2. **Drift detection**（沿用原职责）：
   - 对照 [`references/drift-detection.md`](references/drift-detection.md) 的 grep 锚点表，逐条核验 `rules.md` 与代码事实。
   - 命中漂移 → 走 [`references/drift-detection.md`](references/drift-detection.md) 的"漂移信号"分级（P0 / P1）→ 直接改 `rules.md`（commit 类型 `docs(harness): fix drift ...`，body 引证 `<file>:<line>` 锚点 + commit sha）。
3. **Memory compaction**（新增职责）：
   - 扫描 `docs/dev-harness/memory/<subdir>/MEM-*.md`。
   - 按 [`references/memory-compaction.md`](references/memory-compaction.md) 处理：合并同 topic、提炼超长、标冲突、刷 `verified-at:`。
   - 每个合并 / 提炼 / 改写 → 一个 `docs(memory): compact MEM-XXX ...` commit。
4. **Memory retraction**（新增职责）：
   - 与代码事实冲突的条目（curator 用代码 grep 复核失败）→ `status: retracted` + 移到 `_retracted/` + 正文补 "## Retracted" 段。
   - 一个 commit `docs(memory): retract MEM-XXX <一句话原因>`。
5. **Memory promotion**（新增职责，**最关键的写入**）：
   - 按 [`references/memory-promotion.md`](references/memory-promotion.md) 的晋升判定逐条核对。
   - 满足晋升条件 → 写入目的地（`<area>/rules.md` 或 `incidents/index.md`）+ 改原 entry `status: promoted` + 填 `promotes-to:`。
   - 两步在**两个 commit** 内完成：
     - `docs(harness): promote rule from MEM-XXX <锚点>` （改 rules.md / incidents）
     - `docs(memory): mark MEM-XXX promoted to <锚点>` （改 memory entry）
6. **AGENTS.md baseline 校对**（沿用原职责）：
   - 比对 `gradle/libs.versions.toml` 与 AGENTS.md "当前构建基线"段，不一致 → 直接改 AGENTS.md，commit `docs(agents): refresh baseline <项目>`。
7. **生成 REPORT.md**：
   - 在 worktree 根写 `REPORT.md`，参考 [`references/report-template.md`](references/report-template.md)。
   - REPORT 仅是摘要，列出本次所有 commit 与改动；不再含"待人工 apply 的 patch"。
   - 最后一个 commit `docs(harness): curate report YYYY-MM-DD`。

## 模式对照（升级前 → 升级后）

| 维度 | 原 | 现 |
|---|---|---|
| 输出 | 只产 REPORT.md，patch 由人合入 | REPORT.md + 多个独立 commit，**agent 自主合入** |
| 范围 | `rules.md` / `incidents/` drift | 上述 + `memory/` 压缩、淘汰、晋升 + AGENTS.md baseline |
| 失控保护 | 人工 review patch | 细粒度 commit + 单次上限 + `_retracted/` 永留 + git revert |

## 重点扫描点（OpenFlow specific）

| 域 | 关键 grep / 检查 |
|---|---|
| native | `add_subdirectory(third_party/llama.cpp)` 必须先于 `add_subdirectory(third_party/whisper.cpp)`；`abiFilters` 只含 `arm64-v8a` + `x86_64`；NDK 版本与 AGENTS.md baseline 一致 |
| service | manifest 中 `foregroundServiceType="microphone"` + `FOREGROUND_SERVICE_MICROPHONE` 权限；`FlowAccessibilityService.instance` 没有被 Activity 持有 |
| pipeline | `RecordingForegroundService` 只有 3 个 ACTION_*；`PolishEngine` / `WhisperEngine` 都用 `Mutex` |
| ui | `LocalLifecycleOwner` 来自 `androidx.lifecycle.compose`（不是 `androidx.compose.ui.platform`） |
| jni | `Java_com_hank_flow_open_<class>_<method>` 与 Kotlin `external` 一一对应 |
| build | `compileSdk` / `minSdk` / `targetSdk` 与 AGENTS.md baseline 一致 |
| memory | `memory/<subdir>/MEM-*.md` 的 `verified-at:` 是否过老（> 60 天）；同 topic 重复条目；冲突 `status:` |

## 参考

- [curate-workflow.md](references/curate-workflow.md)
- [drift-detection.md](references/drift-detection.md)
- [memory-compaction.md](references/memory-compaction.md)（新增）
- [memory-promotion.md](references/memory-promotion.md)（升级为自主合入版本）
- [report-template.md](references/report-template.md)

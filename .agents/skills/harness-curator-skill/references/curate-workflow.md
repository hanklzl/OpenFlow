# Curate Workflow

完整的盘点流程。本文件是 [`SKILL.md`](../SKILL.md) Workflow checklist 的细化版本。

## 0. 准备 worktree

```bash
git worktree add .worktrees/harness-curate-$(date +%F) -b harness/curate-$(date +%F) main
cd .worktrees/harness-curate-$(date +%F)
```

## 1. Rule ↔ 代码盘点

针对每条 `docs/dev-harness/<area>/rules.md` 里的 MUST / MUST NOT，找代码 grep 锚点（详见 [`drift-detection.md`](drift-detection.md)）：

```bash
# 例：MUST add_subdirectory(llama) before whisper
grep -n "add_subdirectory.*llama.cpp" app/src/main/cpp/CMakeLists.txt
grep -n "add_subdirectory.*whisper.cpp" app/src/main/cpp/CMakeLists.txt
# 行号顺序必须 llama < whisper
```

记录每条 rule 的"当前真值"列在 REPORT。如果出现漂移：

- **P0 漂移**（rule 直接被违反）：直接改 rule.md / 代码，单独 commit `docs(harness): fix drift <area> <一句话>`，body 引用 `<file>:<line>` 与原 commit sha。
- **P1 漂移**（rule 引用的文件改名或已删）：直接改 rule.md 跟上重命名，commit `docs(harness): refresh rule reference <area>`。

## 2. Commit log 巡视

```bash
git log --since="6 weeks ago" --oneline --grep="fix\|revert" -i
```

挑出"修复过又翻车"或"和 rule 直接相关"的 commit：

- 该 commit 触发的问题已经在 `incidents/index.md` → 跳过。
- 还没在 incidents → 进入 [step 5](#5-memory-promotion)：先到 `memory/candidates/` 建一条 entry，下次 curate 再考虑晋升到 incidents。

## 3. Memory compaction

按 [`memory-compaction.md`](memory-compaction.md) 流程，对 `docs/dev-harness/memory/<subdir>/MEM-*.md` 做：

1. 同 topic 合并
2. 超长提炼
3. candidates 归类
4. 冲突标记
5. verified-at 刷新
6. confidence 升级

**每个操作独立 commit**。

## 4. Memory retraction

按 [`memory-compaction.md`](memory-compaction.md) §3，把符合条件的 entry 标 retracted 并移到 `_retracted/`。

**每条 retract 独立 commit**：`docs(memory): retract MEM-XXX <一句话原因>`。

## 5. Memory promotion

按 [`memory-promotion.md`](memory-promotion.md) 流程，把满足晋升条件的 entry 写到 `rules.md` / `incidents/index.md`。

**每条晋升两个独立 commit**：`docs(harness): promote ...` + `docs(memory): mark ... promoted`。

## 6. AGENTS.md baseline 校对

```bash
# libs.versions.toml 实际值
grep -E "agp|kotlin|compose-bom|ndk" gradle/libs.versions.toml

# AGENTS.md 当前构建基线段
grep -A20 "当前构建基线" AGENTS.md
```

不一致 → 直接改 AGENTS.md，commit `docs(agents): refresh baseline <项目>`。

## 7. 输出 REPORT.md

按 [`report-template.md`](report-template.md) 在 worktree 根写 `REPORT.md`，列出本次所有 commit 与改动摘要。最后 commit `docs(harness): curate report YYYY-MM-DD`。

## 8. 合入

worktree 上的所有 commit 通过 PR 走 squash 合入 main（按 AGENTS.md "Git Worktree 开发约束"）。

REPORT.md 在合并时进入 git history，可日后 `git log -- REPORT.md` 回查每次 curate 都改了什么。

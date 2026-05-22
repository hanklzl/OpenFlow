# REPORT Template

```markdown
# Harness Curate Report — YYYY-MM-DD

## Summary

- 盘点范围：commits <range> + memory snapshot <date>
- Drift fixes：N (P0 = m, P1 = k)
- Memory compaction：a (合并 c / 提炼 p / 归类 r / 冲突标记 nd)
- Memory retract：b
- Memory promote：g (rules += i, incidents += j)
- Verified-at refresh：v
- Confidence bump：u
- AGENTS.md baseline 校对：（变化条目，若有）

## Drift Fixes

### [P0/P1] <area>: <one-line>
- Rule: `docs/dev-harness/<area>/rules.md#<anchor>`
- Code fact: `<file>:<line>` (commit `<sha>`)
- Mismatch: <短描述>
- 修复 commit: `<sha>`（`docs(harness): fix drift ...`）

## Memory Compaction

| 操作 | MEM-ID | 一句话 | commit |
|---|---|---|---|
| compact | MEM-NATIVE-0003 absorbs MEM-NATIVE-0007 | ggml 重定义两条合并 | `<sha>` |
| summarize | MEM-PIPELINE-0001 | 正文 > 200 行，提取 .long.md | `<sha>` |
| classify | MEM-XXX from candidates → pitfalls | …… | `<sha>` |

## Memory Retract

| MEM-ID | 原因 | commit |
|---|---|---|
| MEM-NATIVE-0005 | NDK r29 升级后已不复现 | `<sha>` |

## Memory Promote

### rules.md 新增

| MEM-ID | 目的地 | commit A (rule) | commit B (memory mark) |
|---|---|---|---|

### incidents/index.md 新增

| MEM-ID | INC-ID | commit A | commit B |
|---|---|---|---|

## Deferred

本次因上限未处理的：

- 晋升候选：N 条（详见单独清单）
- compact 候选：M 条
- 留到下次 curate 处理

## 待决议（needs-decision）

| MEM-ID-A | MEM-ID-B | 冲突点 |
|---|---|---|

## 校验

- [ ] 所有 commit 都遵循 `docs(harness): ...` / `docs(memory): ...` / `docs(agents): ...` 格式
- [ ] 每条 promote 都有"A: 改 rules/incidents" + "B: 改 memory entry" 两个 commit
- [ ] 每条 retract 的源文件已 `git mv` 到 `memory/_retracted/`
- [ ] 单次上限未被突破（见 SKILL.md "不变约束"）
```

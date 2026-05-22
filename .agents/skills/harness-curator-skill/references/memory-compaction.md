# Memory Compaction

对 `docs/dev-harness/memory/` 暂存层做合并、淘汰、改写。本文件是 [`harness-curator-skill`](../SKILL.md) Workflow checklist step 3 / step 4 的细则。

## 0. 输入

每次 curate 开始时，列出当前所有 entry：

```bash
find docs/dev-harness/memory -name 'MEM-*.md' -not -path '*/_retracted/*'
```

## 1. 合并（compact）

### 1.1 同 topic 多条

两个或更多 entry 在 [deduplication.md](../../memory-sediment-skill/references/deduplication.md) 第 4 步意义上"语义高度重合"：

1. 选 `created:` 最早的为"主"，其余为"次"。
2. 次的正文中**新增价值**追加到主的正文末尾，主 entry `updated:` 改今天。
3. 次 entry `status: retracted` + 移到 `_retracted/` + 正文末尾追加 "## Retracted by compact"（说明被合并到 `MEM-XXX`）。
4. 两步独立 commit：
   - `docs(memory): compact MEM-XXX absorbs MEM-YYY` （改主 entry）
   - `docs(memory): retract MEM-YYY merged into MEM-XXX` （移次 entry）

### 1.2 超长 entry 提炼

如果一个 entry 正文 > 200 行：

1. 把正文里的"流程实录 / 调试细节" 移到 ID 同名 `.long.md` 文件（与 entry 并排），主 entry 只保留"现象 / 影响 / 复现锚点 / 关联"四节。
2. 主 entry `## 关联` 末追加 "详细记录见 `MEM-XXX.long.md`"。
3. 一个 commit `docs(memory): summarize MEM-XXX (long body extracted)`。

### 1.3 跨域 candidate 归类

`candidates/MEM-XXX.md` 中 area 明确的 entry：

1. 移动文件到 `<subdir>/MEM-XXX.md`（如 `pitfalls/`、`architecture/`）。
2. 一个 commit `docs(memory): classify MEM-XXX from candidates to <subdir>`。

## 2. 冲突标记

两个 entry 在事实层面冲突（不能并存）：

- 不要 retract 任何一方，先标 `status: needs-decision` 在两个 entry 都加。
- 在 REPORT.md 中"待决议"段列出。
- 下一次 curate 时，先用代码事实 grep 复核确认哪一方为真，再 retract 错的一方。

## 3. 淘汰判定（retract）

满足以下**任一**就 retract：

- **代码事实推翻**：curator 用 grep / 实际构建复核，发现 entry 的"现象"在当前代码中已不再成立（例如：升级 NDK r29 后某条 r28 时代的 pitfall 不复存在）。
- **被晋升后冗余**：`status: promoted` 已经 ≥ 60 天，且 rules.md / incidents 中的对应规则稳定，原 memory entry 信息已经被新位置完全吸收 → retract memory 副本。
- **过期未引用**：`updated:` ≥ 180 天且 `verified-at:` 也 ≥ 180 天，且本次 curate 用代码事实复核**找不到**对应锚点 → 视为已无价值，retract。
- **明显错记**：entry 写错了或者发现是 agent 误判（commit message 显式承认）。

retract 操作：

```bash
# 1) 在 entry 末尾追加 ## Retracted 段
# 2) frontmatter status -> retracted
# 3) git mv entry 到 memory/_retracted/
```

一个 commit `docs(memory): retract MEM-XXX <一句话原因>`。

## 4. 刷新 verified-at

curator 用 grep / 构建事实复核 entry 仍正确 → 更新 `verified-at:` 为今天。
单条更新就一个 commit：`docs(memory): refresh MEM-XXX verified-at`。

可批量：一次最多 `刷新上限 = 10`，超额下次再处理。

## 5. confidence 升级

entry 在 ≥ 2 次 curate 都通过 verified-at 刷新（即至少在系统中存在过 2 个 verified-at 节点）且未被 retract → 把 `confidence: medium` 升为 `high`。

`high` 是晋升的前置条件（见 [memory-promotion.md](memory-promotion.md)）。

一个 commit `docs(memory): bump MEM-XXX confidence medium -> high`。

## 6. 上限与裁剪

单次 curate 内：

| 操作 | 上限 |
|---|---|
| 合并（compact） | 10 |
| 淘汰（retract） | 10 |
| 提炼（summarize） | 5 |
| 刷新 verified-at | 10 |
| confidence 升级 | 5 |

超额时按"操作收益"排序保留：retract（清除错误信息） > compact > 其他。剩余的留下次 curate 处理，在 REPORT 中列举为 "deferred"。

# Memory（agent 自动写入暂存层）

本目录由 AI agent 在开发过程中自动维护，作为 [`docs/dev-harness/`](../) 的暂存层。
**不是当前规则源**——条目经 curator 审核晋升后才进入 [`<area>/rules.md`](../) 或 [`incidents/index.md`](../incidents/index.md)。

## 在记忆分层中的位置

```
[ 暂存层 ]  docs/dev-harness/memory/         ← 本目录（agent 自由写入）
   │
   │  晋升（harness-curator-skill 自主判定）
   ▼
[ 主规范 ]  docs/dev-harness/<area>/rules.md
            docs/dev-harness/incidents/index.md
```

## 目录约定

| 子目录 | 用途 | 晋升目的地 |
|---|---|---|
| `architecture/` | 对项目架构、模块边界、调用链的新理解 | `dev-harness/<area>/rules.md` |
| `pitfalls/` | 开发过程踩过的坑（修过的报错、绕过的限制） | `dev-harness/incidents/index.md` |
| `conventions/` | 编码规范、工具用法、命令模板 | `dev-harness/<area>/rules.md` |
| `candidates/` | 暂未归类、待 curator 决断的条目 | （由 curator 移到上述子目录或淘汰） |
| `_retracted/` | 已淘汰的条目，**保留可回查**，永不删除 | — |

## 单条条目

**一个条目 = 一个 `.md` 文件**，命名 `MEM-<AREA>-<NNNN>.md`，例如：

- `architecture/MEM-PIPELINE-0001.md`
- `pitfalls/MEM-NATIVE-0003.md`

`<AREA>` 与 dev-harness 的域命名一致：`UI` / `SERVICE` / `NATIVE` / `PIPELINE` / `TEST` / `BUILD` / `MODEL`。

### 必需的 frontmatter

```yaml
---
id: MEM-NATIVE-0001
created: 2026-05-22
updated: 2026-05-22
source: feat/memory-skill@<sha>  # 触发本条记录的 session/commit/PR
confidence: low | medium | high
status: draft | stable | promoted | retracted
promotes-to:                       # 仅在 status=promoted 时填写
verified-at: 2026-05-22            # curator 最近核验日期；新建时与 created 相同
---
```

`confidence` 默认 `medium`（命中 [write-criteria.md](../../../.agents/skills/memory-sediment-skill/references/write-criteria.md) 任一规则即可达到 medium）；只有 curator 经过 ≥ 1 次成功 drift 核验后才能升级到 `high`。

`status` 流转：

```
draft  ─(curator 通过 drift 核验)→  stable  ─(满足晋升条件)→  promoted
   │                                    │
   └──(curator 判定与代码事实冲突)──────┴──→  retracted（移到 _retracted/）
```

### 单条条目正文格式

```markdown
---
（如上 frontmatter）
---

# <一句话标题>

## 现象 / 事实
（agent 实测看到的代码事实、报错、行为）

## 影响 / 为什么记
（如果不记，下次会怎样；与哪条 rule.md / AGENTS.md 段相关）

## 如何复现 / 验证
（grep 锚点、命令、最小复现路径）

## 关联
- 相关代码：`<file>:<line>`
- 相关 rule / incident：`docs/dev-harness/<area>/rules.md#anchor` 或 `INC-<AREA>-<NNNN>`
```

## 写入与晋升约束

- 暂存层写入：**只能由** [`memory-sediment-skill`](../../../.agents/skills/memory-sediment-skill/SKILL.md) 写入；其他 agent / 人类直接写入会绕过判定规则（不建议但不强制）。
- 暂存层晋升：**只能由** [`harness-curator-skill`](../../../.agents/skills/harness-curator-skill/SKILL.md) 自主合入到 `rules.md` / `incidents/index.md`；不走人工 review。
- 失控保护通过：细粒度独立 commit（一个条目一个 commit）+ 明确判定规则 + git history 可回滚。

## 与其他记忆层的关系

| 层 | 位置 | 维护方 | 是否 commit |
|---|---|---|---|
| 强约束 | `dev-harness/<area>/rules.md` | curator 晋升（升级前由人工维护） | 是 |
| 历史踩坑 | `dev-harness/incidents/index.md` | curator 晋升 | 是 |
| **暂存层（本目录）** | `dev-harness/memory/` | memory-sediment-skill 写入 + harness-curator-skill 维护 | 是 |
| AI 工作流 skill | `.agents/skills/<name>-skill/` | 人工 | 是 |
| 历史决策快照 | `docs/superpowers/specs/`、`plans/` | 人工，参考级 | 是 |
| 个人会话偏好 | `~/.claude/projects/.../memory/` | Claude Code only | 否 |

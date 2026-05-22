# Commit Message 规范

每条新增 / 修改 / 刷新 memory 条目**单独一个 commit**，保证 `git revert <sha>` 能干净回滚一条。

## 总体格式

遵循仓库 [conventional commits](../../../../AGENTS.md#git-worktree-开发约束) 约定 + 中文：

```
docs(memory): <动词> <MEM-ID> <一句话标题>
```

动词只用以下五个之一：

| 动词 | 含义 | commit body 是否必需 |
|---|---|---|
| `add` | 新建条目 | 必需（≤ 3 句说明 R1-R5 命中的是哪条 + 主要影响） |
| `update` | 修改已有条目正文（合并新发现） | 必需（说明合并了什么） |
| `refresh` | 仅更新 `verified-at:` / `updated:` 时间戳 | 可选 |
| `retract` | 把条目标 retracted 并移到 `_retracted/`（**curator 用，sediment 不会发起**） | 必需 |
| `promote` | 标 promoted 并写 `promotes-to:`（**curator 用，sediment 不会发起**） | 必需 |

sediment skill **只使用 `add` / `update` / `refresh`**。`retract` / `promote` 由 [`harness-curator-skill`](../../harness-curator-skill/SKILL.md) 发起。

## 示例

### 新建条目

```
docs(memory): add MEM-NATIVE-0003 改 CMakeLists 顺序触发 ggml 重定义

R5 命中（修复了 :app:assembleDebug 失败）。
现象：whisper 的 add_subdirectory 早于 llama 时，ggml 目标重复
注册，linker 报 multiple definition。已在条目正文补 grep 锚点。
```

### 合并新发现到已有条目

```
docs(memory): update MEM-PIPELINE-0001 长录音分段的边界条件

合并本次发现的"60s 边界 ASR 模型最大输入"约束到既有条目，
confidence 从 medium 升为 high（本次重复触发证实了 R3）。
```

### 仅刷新时间戳

```
docs(memory): refresh MEM-SERVICE-0002 verified-at
```

## 多条目时的 commit 顺序

一次会话产出 N 条时：
1. 按 R5 → R1 → R3 → R2 → R4 顺序逐条 commit。
2. 同优先级内按 `MEM-ID` 字典序。
3. 不要 squash 多条进一个 commit——单条 revert 的能力比 commit 历史紧凑更重要。

## 不允许的写法

- ❌ `docs(memory): 沉淀本次会话` ——动词必须明确指向单个 ID
- ❌ `chore: update memory` ——type 必须是 `docs`、scope 必须是 `memory`
- ❌ `docs(memory): add MEM-NATIVE-0003 + MEM-NATIVE-0004 ...` ——一个 commit 只能动一条
- ❌ `feat(memory): ...` ——memory 是文档，不是功能

## body 撰写守则（在需要 body 时）

- ≤ 3 句话。
- 必须说"R1-R5 命中的是哪条"。
- 必须说"主要影响 / 与哪条 rule 或 incident 相关"（如果有）。
- 不写过程流水（"我先 grep 了 X 然后改了 Y"——这些放正文，不放 commit body）。
- 中文。

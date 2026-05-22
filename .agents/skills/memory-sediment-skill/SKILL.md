---
name: memory-sediment
description: >
  Use when wrapping up a development session in OpenFlow — assesses whether
  the session yielded knowledge worth committing to docs/dev-harness/memory/
  (architecture insights, pitfalls, conventions). Writes one entry per atomic
  finding with strict deduplication and a hard cap of 5 entries per session.

  Trigger phrases: "沉淀 memory", "memory sediment", "wrap up", "收尾",
  "开发结束", "本次会话有什么要记下来", "记一下", "记住", "下次别这么做",
  "/wrap-up", "/memorize", "finishing-a-development-branch 联动".

  This skill writes directly to docs/dev-harness/memory/<subdir>/ and commits
  per entry. It does NOT modify rules.md / incidents/index.md — those are
  curator's job.
---

# Memory Sediment Skill (OpenFlow)

在一次开发会话收尾时，由 agent 自主判断"是否值得把本次新学到的东西写进 [`docs/dev-harness/memory/`](../../../docs/dev-harness/memory/)"，并按条目落地为 commit。

## 必读 gate

- [`../../../AGENTS.md`](../../../AGENTS.md)（特别是"项目记忆与守门约束"与"迭代工作流"）
- [`../../../docs/dev-harness/memory/README.md`](../../../docs/dev-harness/memory/README.md)（条目格式、frontmatter、晋升目的地）
- [`references/write-criteria.md`](references/write-criteria.md)（**写入判定 5 条规则**）
- [`references/deduplication.md`](references/deduplication.md)（写入前去重流程）
- [`references/commit-format.md`](references/commit-format.md)（commit message 规范）

## 不变约束

- **只写 `docs/dev-harness/memory/`**，不修改 `rules.md` / `incidents/index.md` / `AGENTS.md` / `.agents/skills/`。
- **每条条目一个 commit**，commit message 严格按 [`references/commit-format.md`](references/commit-format.md)。
- **单次会话最多 5 条新增**（不含 `verified-at:` 时间戳更新）。超出时只保留命中规则最强的 5 条，其余在最终摘要中列举为"未沉淀候选"，由用户决定是否手动追加。
- **默认不写**——只有显式命中 [`references/write-criteria.md`](references/write-criteria.md) 5 条之一才能写。
- 不写"git log / 当前代码直接 derive 的事实"。
- 不写"单次会话临时状态"（待办、未完成调试、个人偏好）。

## Workflow checklist

按顺序逐步完成：

1. **扫描会话上下文**，分四类提取候选：
   - 架构新理解 → `architecture/`
   - 踩过的坑 → `pitfalls/`
   - 编码规范 / 工具用法 → `conventions/`
   - 跨域 / 拿不准 → `candidates/`
2. **判定**：对每条候选用 [`references/write-criteria.md`](references/write-criteria.md) 5 条规则裁决，**默认全部不写**，命中才写。
3. **去重**：用 [`references/deduplication.md`](references/deduplication.md) 流程：grep `docs/dev-harness/memory/` + `<area>/rules.md` + `incidents/index.md` + `AGENTS.md`。已存在则只更新 `verified-at:`，不新建条目。
4. **裁剪**：如果通过过滤 + 去重后剩余 > 5 条，按"规则命中强度"排序，保留前 5，其余列为"未沉淀候选"。
5. **生成 entry 文件**：
   - 选 `<AREA>`（UI / SERVICE / NATIVE / PIPELINE / TEST / BUILD / MODEL；拿不准 → `candidates/`）。
   - 用 `ls docs/dev-harness/memory/<subdir>/ | grep -oE 'MEM-<AREA>-[0-9]+' | sort -V | tail -1` 找下一个 `NNNN`。
   - 文件名 `MEM-<AREA>-<NNNN>.md`，frontmatter 与正文模板见 [`../../../docs/dev-harness/memory/README.md`](../../../docs/dev-harness/memory/README.md)。
   - 初始 `confidence: medium`、`status: draft`、`verified-at:` 同 `created:`。
6. **逐条 commit**：每条条目独立 commit，message 严格按 [`references/commit-format.md`](references/commit-format.md)。
7. **回写摘要**给用户：
   ```
   本次沉淀 N 条 memory：
     - <entry-id> <one-line> (<commit-sha>)
   未沉淀候选：M 条（如 > 5 才会出现）
     - <topic>: <reason rejected>
   ```

## 触发时机

主要场景：

- 用户显式触发：`/wrap-up`、`/memorize`、"沉淀 memory"、"记一下" 等。
- 与 [`superpowers:finishing-a-development-branch`](https://github.com/anthropics/superpowers) 联动：在该 skill 的"verify-tests-pass"之后、"present-options"之前**自动调用**本 skill。
- 修复了 CI / 构建 / 上线失败后（**必跑**，即使用户没主动要求）。

不要在以下场景跑：

- 单次会话内的小改（typo、改 import、格式化）。
- 用户只是问问题、看代码、不修改文件的会话。
- 已经在另一个会话刚刚跑过且没有新发现。

## 重点扫描点（OpenFlow specific）

回顾会话时，特别关注以下五类高价值信号：

| 信号 | 写哪 |
|---|---|
| JNI 签名与 Kotlin `external` 对不上、改了又改 | `pitfalls/` (NATIVE) |
| 改 `CMakeLists.txt` 顺序 / `add_subdirectory` 引发 ggml 重定义 | `pitfalls/` (NATIVE) |
| FGS `foregroundServiceType` / 权限不匹配导致 Service 启动失败 | `pitfalls/` (SERVICE) |
| AccessibilityService 焦点节点缓存被某事件类型污染 | `pitfalls/` (SERVICE) |
| `LocalLifecycleOwner` 包名搞错（`androidx.lifecycle.compose` vs `androidx.compose.ui.platform`） | `pitfalls/` (UI) |
| DataStore "multiple active instances" 在测试中 hang | `pitfalls/` (TEST) |
| `runTest` / `advanceUntilIdle` / `MainDispatcherRule` 的本项目特定用法 | `conventions/` (TEST) |
| 模型下载路径 / 镜像源 / SHA-256 校验细节 | `conventions/` (MODEL) |
| 用户明确说"这里这么写就好，不要拆"的判断 | `conventions/` (相关域) |
| 调用链上某个边界（Service / Overlay / FGS / Engine）的真实拥有者关系 | `architecture/` (SERVICE 或 PIPELINE) |

## 参考

- [write-criteria.md](references/write-criteria.md)（**判定 5 条规则**）
- [deduplication.md](references/deduplication.md)（去重流程）
- [commit-format.md](references/commit-format.md)（commit message 规范）

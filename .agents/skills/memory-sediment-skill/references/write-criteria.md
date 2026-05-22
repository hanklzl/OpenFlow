# 写入判定规则

**默认全部不写**——只有命中以下 5 条之一才允许写入 `docs/dev-harness/memory/`。

每个候选条目要单独逐条对照判定，命中越多越优先；未命中任何一条就丢弃。

## 5 条触发规则

### R1. 用户显式记忆指令

用户在本次会话明确说：
- "记一下：……"
- "记住……"
- "下次别（这么做）……"
- "把这个写到 memory / 工程里"

→ **必写**，按用户原话归类（pitfalls / conventions / architecture / candidates）。

### R2. 代码事实 ≠ 文档描述

agent 实测发现：
- AGENTS.md / `<area>/rules.md` / `incidents/index.md` 描述的事实与当前代码 / 构建 / 运行行为**不一致**
- 例如：AGENTS.md 写 NDK r28，实际 `libs.versions.toml` 是 r29

→ **写到 `architecture/` 或 `conventions/`**，frontmatter `confidence: medium`，附 grep 锚点。
（注意：与 [`harness-curator-skill`](../../harness-curator-skill/SKILL.md) 的 drift detection 是同一类发现，这里只负责"记下来"，纠 rules.md 由 curator 做。）

### R3. 同一会话重复修正 ≥ 2 次

会话中 **相同问题被踩过 ≥ 2 次**：
- agent 改了又改回去（revert oneself）
- 用户连续指出"还是不对"
- 测试连续 fail 同一断言

→ **必写到 `pitfalls/`**，frontmatter `confidence: high`（已经被踩过证实）。

### R4. 用户对非显然判断的明确认可

用户对 agent 一个**非显然**的判断/方案给出明确认可：
- "对，就这么写"（在 agent 提出反直觉做法之后）
- "这个判断是对的"
- "不用拆，就这一个 PR 走"

→ **写到 `conventions/`**，frontmatter `confidence: medium`。

注意区分：单纯的"OK / 好的 / 继续" **不算**——必须是对 agent 一个具体且非显然的判断的认可。

### R5. 修复 CI / 构建 / 上线失败

本次会话修复了：
- CI workflow 红 → 绿
- `:app:assembleDebug` 失败 → 成功
- 真机/模拟器跑不起来 → 跑起来
- Release 签名 / R8 相关失败

→ **必写到 `pitfalls/`**，frontmatter `confidence: high`，**必须**附完整复现步骤（命令、报错、修复 diff 摘要）。

## 任何情况都不写

以下信号**绝不写入**：

- **临时状态**：本次会话未完成的待办、调试中的猜测、还没验证的方案。
- **可 derive 的事实**：通过 `git log` / 当前代码可以直接看出来的（"我们新增了 X 类"——`git diff` 已经说了）。
- **近似重复**：在 `memory/` 或 `rules.md` / `incidents/index.md` / `AGENTS.md` 中已经有等价描述。
- **个人偏好**：用户的"我喜欢 4 空格"、"我更喜欢 var" 等会话级偏好（这些归个人 memory）。
- **过短无信息**：少于 ~30 字的内容（既不是新理解、也不是踩过的坑）。
- **AI 自责式**：agent 自己反省的"我下次应该 X"——除非用户明确认可，否则不算共享知识。

## 命中强度排序（用于裁剪到 5 条）

当过滤 + 去重后仍 > 5 条时，按以下顺序保留前 5：

1. R5 命中（CI / 构建 / 上线失败修复）
2. R1 命中（用户显式记忆指令）
3. R3 命中（重复修正 ≥ 2 次）
4. R2 命中（文档 ≠ 代码事实）
5. R4 命中（用户对非显然判断的认可）

同优先级时，按"影响域大小"再排（NATIVE / SERVICE > PIPELINE > UI > MODEL > TEST > BUILD）。

被裁掉的候选在最终摘要中列举为"未沉淀候选"，由用户决定是否手动追加。

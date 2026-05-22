# architecture/

存放"对项目架构、模块边界、调用链的新理解"。

每个条目一个 `MEM-<AREA>-<NNNN>.md`，frontmatter 与正文格式见 [`../README.md`](../README.md)。

## 何时写到这里

- agent 在阅读代码时发现 **现有 AGENTS.md / rules.md / 代码注释 没有写清楚** 的模块边界、调用链、设计取舍。
- 用户在解释/纠正一段实现时，给出的"为什么这样做"的架构理由。
- 重构后某个模块的职责发生变化，但 AGENTS.md 还没跟上。

## 晋升目的地

经 curator 验证稳定后，晋升到 `docs/dev-harness/<area>/rules.md` 或在 AGENTS.md 中补充对应段落。

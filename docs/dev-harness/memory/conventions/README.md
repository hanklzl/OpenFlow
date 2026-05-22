# conventions/

存放"编码规范、工具用法、命令模板"——用户在协作过程中给出的、跨任务复用的做法。

每个条目一个 `MEM-<AREA>-<NNNN>.md`，frontmatter 与正文格式见 [`../README.md`](../README.md)。

## 何时写到这里

- 用户对 agent 的某个非显然判断给出明确认可（"对，就这么写"、"这次别拆分"）。
- 用户给出一个跨任务可复用的命令 / 工具用法 / 代码模式（例如 "测试用 runTest + advanceUntilIdle"）。
- agent 实测发现"惯常做法"在本项目里**不适用**，需要用一个本项目特定的做法替代。

## 晋升目的地

经 curator 验证稳定后，晋升到 `docs/dev-harness/<area>/rules.md` 中的相关段落（增条）。

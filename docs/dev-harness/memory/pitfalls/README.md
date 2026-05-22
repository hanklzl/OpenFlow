# pitfalls/

存放"开发过程踩过的坑"——具体的报错、bug、绕过的限制。

每个条目一个 `MEM-<AREA>-<NNNN>.md`，frontmatter 与正文格式见 [`../README.md`](../README.md)。

## 何时写到这里

- 修了一个非显然的 bug（不是 typo / 不是单纯改 import）。
- 同一会话中相同问题踩了 ≥ 2 次。
- 修复了上线 / 构建 / CI 失败（**必沉淀**）。
- 用户明确说"记一下/下次别 X"且语义偏"踩过的坑"。

## 晋升目的地

经 curator 验证稳定后，由 curator 分配下一个可用的 `INC-<AREA>-<NNNN>`，
在 `docs/dev-harness/incidents/index.md` 中登记，并在本条目 frontmatter 的 `promotes-to:` 写入映射。

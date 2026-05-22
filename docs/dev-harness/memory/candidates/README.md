# candidates/

存放"暂未归类"或"跨域"的候选条目。memory-sediment-skill 拿不准属于 architecture / pitfalls / conventions 哪一类时，先丢到这里，等 curator 决断。

每个条目一个 `MEM-<AREA>-<NNNN>.md`，frontmatter 与正文格式见 [`../README.md`](../README.md)。

## curator 处理

curator 每次扫描会：

1. 把可归类的条目移到 `architecture/` / `pitfalls/` / `conventions/`。
2. 把信息不足的条目标 `status: retracted` 并移到 `_retracted/`。
3. 把跨域且确实有价值的条目原地保留，但在 frontmatter 注 `verified-at:`。

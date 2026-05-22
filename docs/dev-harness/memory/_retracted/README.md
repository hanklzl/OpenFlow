# _retracted/

已淘汰的条目移到这里，**永不删除**，方便日后回查"我们曾经记过什么、为什么否定了"。

每个条目保留原 `MEM-<AREA>-<NNNN>.md` 文件名，frontmatter 的 `status:` 改为 `retracted`，并在正文末尾追加：

```markdown
## Retracted
- date: 2026-05-22
- by: harness-curator-skill@<sha>
- reason: <一句话说明为什么淘汰，如：与 NDK r29 升级后的实际行为冲突>
```

不要直接 `rm` 文件。`git log` 也能回查，但保留在 `_retracted/` 让"还活着"和"已淘汰"在文件系统上一目了然。

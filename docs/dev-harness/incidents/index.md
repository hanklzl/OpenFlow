# Incidents — INDEX

历史踩坑分类归档。每条 incident 关联 area + rule + guard，便于反查"为什么 rules.md 里有这条"。

## 命名规范

`INC-<AREA>-<NNNN>`：
- AREA：`NATIVE` / `SERVICE` / `PIPELINE` / `UI` / `TEST` / `BUILD`
- NNNN：四位数字，按 area 自增

## 当前 incidents

| ID | Area | One-liner | Status |
|---|---|---|---|
| [INC-SERVICE-0001](INC-SERVICE-0001.md) | SERVICE | 悬浮球被自身 overlay 窗口的 WINDOW_STATE_CHANGED 立刻隐藏，全链路入口断开 | resolved |
| [INC-SERVICE-0002](INC-SERVICE-0002.md) | SERVICE | TextInserter 把 EditText 的 hint 当作已有文本拼接（如 "Search settings[BLANK_AUDIO]"） | resolved |

## Incident 模板

新增 incident 时复制到 `INC-<AREA>-<NNNN>.md`：

```markdown
# INC-<AREA>-<NNNN> — <一句话标题>

- **状态**：active / resolved / deprecated
- **首次发生**：YYYY-MM-DD
- **关联 commit**：`<sha>`
- **关联 rule**：`docs/dev-harness/<area>/rules.md#<anchor>`

## 现象

<具体表现，附 logcat / 错误码 / 截图路径>

## 根因

<分析；引用代码行号>

## 修复

<diff 摘要或 commit 链接>

## Guard

<grep guard / contract test / rule.md 新增条款；防止再次踩>

## 备注

<可选：留待解决的尾巴；相关讨论链接>
```

## 反查路径

- 看到某条 rule 想知道来由 → 在本文件搜 area + 关键字 → 跳到对应 INC
- 排查现象时怀疑曾遇到过 → 全文搜现象关键字
- AI 助手汇总到 `harness-curator-skill/REPORT.md` 时优先以本文件为索引

# REPORT Template

```markdown
# Harness Curate Report — YYYY-MM-DD

## Summary
- 盘点范围：commits <range> + memory <snapshot date>
- 漂移条目：N (P0 = m, P1 = k)
- 候选 incidents：j
- 候选 rule patches：i

## 漂移条目

### [P0] <area>: <one-line>
- Rule: `docs/dev-harness/<area>/rules.md#<anchor>`
- Code fact: `<file>:<line>` (commit `<sha>`)
- Mismatch: <短描述>
- 建议 patch:
  ```diff
  - 旧
  + 新
  ```

## 候选 incidents

### INC-<AREA>-<NNNN> — <one-line>
- 触发：<事件 / 多次回归>
- Root cause: <短描述>
- Guard：grep / contract test / rule.md 增条
- 建议文档：`docs/dev-harness/incidents/INC-<AREA>-<NNNN>.md`

## 候选 rule patches
（同上 patch 列表，集中归档便于一次合入）

## 不变量保持
- [ ] 本 REPORT 没有直接修改 `docs/dev-harness/`
- [ ] 所有 patch 都是 unified diff，可直接 `git apply`
```

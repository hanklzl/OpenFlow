# Memory Promotion

把个人 / 会话级记忆 promote 到仓库级 rule / incident。

## 来源

- Claude Code 的 `~/.claude/projects/<repo-hash>/memory/MEMORY.md`（用户级反复出现的偏好）
- 同一 incident 在 incidents/ 已记录过，但当前 rule.md 还没引用
- 多次会话中被同一 user feedback 修正的行为（"don't do X" 出现 ≥ 3 次）

## 候选条件

候选 entry 必须同时满足：

1. **可执行**：能落到 grep / lint / contract test 之一
2. **可复用**：不是一次性事故记忆（"今天 14:30 那个 commit"）
3. **不冒犯隐私**：不含个人偏好（"我喜欢 4 空格"）

## promote 路径

1. 候选 entry → 决定归属域（native / service / pipeline / ui / test / build）
2. 在 REPORT 中给出建议 `rules.md` patch（+/- diff）
3. 若有相关历史事故，建议新 incident（`docs/dev-harness/incidents/INC-<area>-<NNNN>.md`）
4. patch 由人审核合入；不要直接改

## promote 后的清理

合入后：
- 在源 MEMORY.md 中标注 `(promoted to docs/dev-harness/<area>/rules.md @ <date>)`
- 移除 MEMORY.md 中变得冗余的会话记忆条目

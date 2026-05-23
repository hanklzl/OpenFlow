---
id: MEM-BUILD-0003
created: 2026-05-23
updated: 2026-05-23
source: release-v0.1.1@62e3116
confidence: medium
status: draft
verified-at: 2026-05-23
---

# Release commit 应 ff-merge 回 main 后再打 tag，而非在 release branch 上 tag

## 现象 / 事实

OpenFlow release 流程的当前 skill ([`openflow-release-skill/SKILL.md`](.claude/skills/openflow-release-skill/SKILL.md) 第 6 步) 模板：

```bash
git commit -m "chore(release): bump to vX.Y.Z"
git tag vX.Y.Z
git push origin release-vX.Y.Z
git push origin vX.Y.Z
```

即在 `release-vX.Y.Z` branch 上 commit + tag、推 branch 和 tag。这种做法的结构性问题：

1. 如果在 release branch 上做了任何 hotfix（CI 修复、文档微调）而没合回 main，下次 release 会重复踩同样的坑（见 [[MEM-BUILD-0001]]）
2. tag 不在 main 祖先链上 → `git describe --tags --abbrev=0` 找不到 → 下次 release notes 范围计算失控（见 [[MEM-BUILD-0004]]）

v0.1.1 操作者明确要求改为：

```bash
# worktree 内 commit
git commit -m "chore(release): bump to vX.Y.Z"
# 回主仓库，ff-merge release branch 进 main
cd /Users/zili/code/android/OpenFlow
git checkout main
git merge --ff-only release-vX.Y.Z
# tag 打在 main HEAD（与 release branch HEAD 同 sha）
git tag vX.Y.Z
# 推 main 和 tag
git push origin main
git push origin vX.Y.Z
# 清理
git worktree remove --force .worktrees/release-vX.Y.Z
git branch -d release-vX.Y.Z
```

原话（v0.1.1 操作者）：「不是在 worktree 里面，commit 后合并回 main，然后创建 tag 并 push」。

## 影响 / 为什么记

- R1 命中（用户显式指令）。
- 保证每个 vX.Y.Z tag 都在 main 祖先链上，git describe 行为可预期，无 prev tag fallback 也能跑对（fallback 仍保留作防御兜底，见 [[MEM-BUILD-0004]]）。
- 任何 release branch 上的 hotfix（如 v0.1.0 的 fd9e310 SIGPIPE + gh-pages init）自动随 ff-merge 进入 main，下次 release 不会重新踩。
- 与 [`openflow-release-skill/SKILL.md`](.claude/skills/openflow-release-skill/SKILL.md) 第 6 步 + 「核心不变约束 MUST」段的「先推 branch（git push origin release-vX.Y.Z）再推 tag」直接冲突；该 skill 段落待 curator 更新。

## 如何复现 / 验证

```bash
# 验证 tag 在祖先链上：
git merge-base --is-ancestor v0.1.1 main && echo "OK: tag on main ancestry"
# v0.1.0（用旧 flow 推的）会返回非零：
git merge-base --is-ancestor v0.1.0 main || echo "FAIL: v0.1.0 not on main ancestry"
```

## 关联

- 相关 skill：`.claude/skills/openflow-release-skill/SKILL.md` 第 6 步「commit + tag + push」、「核心不变约束 MUST」段
- 关联条目：[[MEM-BUILD-0001]]（hotfix 不回流的另一面）、[[MEM-BUILD-0004]]（prev tag fallback）
- 历史佐证：`git merge-base --is-ancestor v0.1.0 main` 在 release-v0.1.1 操作时返回非零，触发了 prev tag 查找 bug

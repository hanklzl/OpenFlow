---
id: MEM-BUILD-0006
created: 2026-05-23
updated: 2026-05-23
source: feat/release-smoke-test@4fc59cd
confidence: high
status: draft
verified-at: 2026-05-23
---

# `.claude/skills/<name>/SKILL.md` 当前都是 plain file 副本，不是文档约定的 symlink

## 现象 / 事实

`docs/dev-harness/INDEX.md:23` 与 `AGENTS.md:44` 写：

> 技能 / 工作流：`.agents/skills/<area>-skill/`，**软链到** `.claude/skills/`、`.codex/skills/`。

`INDEX.md:42` 进一步要求：

> 1. symlinks 校验（`.agents/skills/*` 与 `.claude/skills/`、`.codex/skills/` 软链同步）

实测（在 `feat/release-smoke-test` worktree，截至 commit `4fc59cd`）：

```bash
$ for skill in openflow-release-skill whisper-cpp-skill llama-cpp-skill \
               native-build-skill memory-sediment-skill harness-curator-skill \
               accessibility-pipeline-skill android-overlay-service-skill \
               test-stability-skill; do
    path=".claude/skills/${skill}/SKILL.md"
    if [ -L "$path" ]; then echo "  $skill: symlink"
    elif [ -f "$path" ]; then echo "  $skill: PLAIN FILE (drift)"
    fi
done
```

输出：**9/9 全部 PLAIN FILE**。

内容比对：当前 `.agents/skills/X/SKILL.md` 与 `.claude/skills/X/SKILL.md` 字节一致（diff 静默），意味着至今所有修改都是**手动同步**——drift 还没到达可见后果，但机制已经断了。

## 影响 / 为什么记

- R2 命中（文档 ≠ 仓库实际状态）。
- 一旦有人只改其中一个副本就会出现**内容分歧 + 难发现**：Claude Code 用户和 Codex 用户拿到不同 skill。
- 本次 v0.1.1 release session 编辑 `.claude/skills/openflow-release-skill/SKILL.md` 时实际改的是 plain file 副本；后续 Plan Task 8 编辑 `.agents/skills/openflow-release-skill/SKILL.md`，刚好两个文件最终 identical（因为编辑内容相同）——下次不一定这么幸运。
- 与 [`feedback_skill_placement_convention.md`](../../../../.../memory/feedback_skill_placement_convention.md)（个人 memory）冲突：该 memory 称真身在 `.agents/skills/`、各 harness 镜像目录通过相对软链指回，实际不成立。
- 修复策略由 curator 决定：
  - **A**: 把 9 个 plain file 替换为相对 symlink → `.agents/skills/<name>/SKILL.md`，与 `.codex/skills/` 处理一致
  - **B**: 更新 INDEX.md / AGENTS.md 改口约定为「.claude/skills/ 是手动同步的副本，写入时需保持与 .agents/skills/ 一致」+ 加 pre-commit hook
- A 更省事、与文档约定一致，推荐。

## 如何复现 / 验证

```bash
cd /Users/zili/code/android/OpenFlow
ls -la .claude/skills/*/SKILL.md | awk '/^l/ {print "  symlink: "$NF} /^-/ {print "  plain: "$NF}'
```

预期（修复前）：9 行 `plain:`。预期（修复后）：9 行 `symlink:` 指向 `../../../.agents/skills/<name>/SKILL.md`。

## 关联

- 文档约定：`docs/dev-harness/INDEX.md:23,42`、`AGENTS.md:44`
- `.codex/skills/`：未在本次会话验证，可能同样 drift
- 关联 candidate：[`feedback_skill_placement_convention.md`](Claude Code 个人 memory)
- 发现 commit / context：Plan Task 8 spec reviewer 报告（commit `4fc59cd` 的 review）
- **本条目主要用途**：等 [`harness-curator-skill`](../../../.agents/skills/harness-curator-skill/SKILL.md) 巡检时决定修法 A 或 B 并落实

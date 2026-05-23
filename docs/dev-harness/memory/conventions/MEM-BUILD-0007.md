---
id: MEM-BUILD-0007
created: 2026-05-23
updated: 2026-05-23
source: feat-vulkan-backend@82c8abb
confidence: medium
status: draft
promotes-to:
verified-at: 2026-05-23
---

# OpenFlow 文档默认使用中文

## 现象 / 事实

用户在 GPU 后端接入会话中明确要求："所有文档都使用中文编写，这个加入到本工程的记忆中"。

## 影响 / 为什么记

后续在本仓库新增或修改面向工程协作的文档、计划、调研记录、开发说明时，默认使用中文编写。代码符号、命令、错误原文、外部资料标题可保留原文，但解释性正文应使用中文，避免同一工程文档语言风格漂移。

## 如何复现 / 验证

写入或更新 `docs/`、`AGENTS.md`、`.agents/skills/`、`docs/superpowers/`、`docs/dev-harness/` 下的 Markdown 文档前，检查正文语言是否以中文为主。

## 关联

- 相关规则：`AGENTS.md`（文档入口与 Git Worktree 开发约束）
- 相关约定：`docs/dev-harness/memory/README.md`

# Curate Workflow

## 0. 准备

```bash
git worktree add .worktrees/harness-curate-$(date +%F) -b harness/curate-$(date +%F) main
cd .worktrees/harness-curate-$(date +%F)
```

## 1. 盘点对照

针对每条 `docs/dev-harness/<area>/rules.md` 里的 MUST / MUST NOT，找代码 grep 锚点：

```bash
# 例：MUST add_subdirectory(llama) before whisper
grep -n "add_subdirectory.*llama.cpp" app/src/main/cpp/CMakeLists.txt
grep -n "add_subdirectory.*whisper.cpp" app/src/main/cpp/CMakeLists.txt
# 行号顺序必须 llama < whisper
```

记录每条 rule 的"当前真值"列在 REPORT。

## 2. Commit log 巡视

```bash
git log --since="6 weeks ago" --oneline --grep="fix\|revert" -i
```

挑出"修复过又改回去"或"和 rule 直接相关"的 commit，关联到 incident。

## 3. 候选 incident 提交

每次 Curate 至少应该输出：

- 已存在 incident 是否需要 reword
- 新 incident（若有事故）
- 已废弃 incident（功能已重写，原约束不再适用）

## 4. 输出

`REPORT.md` 落在 worktree 根目录，符合 `report-template.md` 的格式。**禁止**直接改 `docs/dev-harness/`。

## 5. 合入

人工 review REPORT；批准后用 `git merge --squash` 把 patch 落到 main。

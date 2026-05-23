---
id: MEM-BUILD-0002
created: 2026-05-23
updated: 2026-05-23
source: release-v0.1.1@62e3116
confidence: high
status: draft
verified-at: 2026-05-23
---

# 新 git worktree 必须手动 init submodule，否则 native build 立刻 fail

## 现象 / 事实

`git worktree add .worktrees/release-v0.1.1 -b release-v0.1.1` 完成后立即跑 `bash scripts/release/preflight.sh v0.1.1`，CMake configure 步骤直接失败：

```
CMake Error at CMakeLists.txt:89 (add_subdirectory):
  The source directory
    /Users/zili/code/android/OpenFlow/.worktrees/release-v0.1.1/app/src/main/cpp/third_party/llama.cpp
  does not contain a CMakeLists.txt file.
CMake Error at CMakeLists.txt:93 (add_subdirectory):
  The source directory
    .../third_party/whisper.cpp
  does not contain a CMakeLists.txt file.
```

`git submodule status` 显示前缀 `-`：

```
-40d5358d3c730b81729ba81cd5c44ed596d02510 app/src/main/cpp/third_party/llama.cpp
-8443cf05e3fa8ce1b32348e1bcbcf8fc31f7f3ae app/src/main/cpp/third_party/whisper.cpp
```

修复（单条命令解决）：

```bash
git submodule update --init --recursive --depth 1 \
  app/src/main/cpp/third_party/llama.cpp \
  app/src/main/cpp/third_party/whisper.cpp
```

## 影响 / 为什么记

`git worktree add` 共享主仓库的 `.git/modules/`，但每个 worktree 的 submodule working tree 是独立 checkout 的，**不会**随 `worktree add` 自动 populate。任何新 worktree（release / feature / bugfix）只要触发 native build / preflight，都会在 CMake configure 第一步死。

OpenFlow 当前 [[worktree-for-all-changes]] 红线要求所有变更走 worktree → 这条坑会持续踩。

与 [[openflow-release-skill]] SKILL.md 第 2 步「建 worktree」相关：当前 skill 只写 `git worktree add ...`，下一行紧接着改 `version.properties`，没有 submodule init 步骤。

## 如何复现 / 验证

```bash
# 任何新建的 worktree：
git worktree add /tmp/of-test-wt -b test-wt main
cd /tmp/of-test-wt
ls app/src/main/cpp/third_party/llama.cpp/   # 为空
ls app/src/main/cpp/third_party/whisper.cpp/ # 为空
./gradlew :app:assembleDebug                 # CMake configure fail

# 修复：
git submodule update --init --recursive --depth 1 \
  app/src/main/cpp/third_party/llama.cpp \
  app/src/main/cpp/third_party/whisper.cpp
```

## 关联

- 相关代码：`app/src/main/cpp/CMakeLists.txt:89,93`（两处 `add_subdirectory`）
- 相关 submodule 配置：`.gitmodules`
- 关联 skill：`.claude/skills/openflow-release-skill/SKILL.md` 第 2 步、`.claude/skills/native-build-skill/SKILL.md` submodule 段
- 关联条目：[[MEM-BUILD-0001]]（同样发生在 v0.1.1 release session）

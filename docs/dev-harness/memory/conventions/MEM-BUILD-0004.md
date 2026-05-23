---
id: MEM-BUILD-0004
created: 2026-05-23
updated: 2026-05-23
source: release-v0.1.1@62e3116
confidence: medium
status: draft
verified-at: 2026-05-23
---

# release notes 的 prev tag 查找必须容忍 tag 不在祖先链

## 现象 / 事实

`scripts/release/preflight.sh:72` 与 `.github/workflows/android-release-apk.yml` "Generate release notes" step 原先都用：

```bash
prev=$(git describe --tags --abbrev=0 2>/dev/null || git rev-list --max-parents=0 HEAD | tail -1)
```

在 v0.1.1 release session 实测：

```
$ git describe --tags --abbrev=0
fatal: No tags can describe '17614f394a3e057834ce488f71670e58c3d4f378'.
```

根因：v0.1.0 tag 指向 `fd9e310 fix(ci) ...`，该 commit 只在 `release-v0.1.0` branch 上，不在 main 祖先链（见 [[MEM-BUILD-0003]]）。`git describe` 严格要求 tag 是 HEAD 祖先，因此 fallback 到 `git rev-list --max-parents=0 HEAD | tail -1`（仓库根 commit）。结果 release notes 把仓库从初始化到 HEAD 的所有 commit 全列出来——v0.1.1 preflight 第一次跑出来包括 `97bcdf2 Logan`、`71fb62b infra`、`9a95945 NDK r29` 等 v0.1.0 之前的内容。

v0.1.1 在 `e90347f chore(release): bump to v0.1.1 + 修复 prev tag 查找可靠性` commit 同时改了两处，加 fallback：

```bash
prev=$(git describe --tags --abbrev=0 2>/dev/null) || true
if [ -z "$prev" ]; then
    prev=$(git tag --list 'v*' --sort=-v:refname | grep -vFx "$current_tag" | head -1)
fi
prev=${prev:-$(git rev-list --max-parents=0 HEAD | tail -1)}
```

CI 版本类似（用 `$GITHUB_REF_NAME` 替换 `$current_tag`）。

## 影响 / 为什么记

- R2 命中（`git describe` 假设 tag 必在祖先链，与实际仓库历史不符）+ R5 弱命中（preflight 跑出错的 release notes 不算 CI 失败但影响发布产物质量）。
- 即使 [[MEM-BUILD-0003]] 让以后的 release tag 都进 main 祖先链，**已发布的** v0.1.0 仍是孤儿 tag——所以这个 fallback 作为防御兜底**应当永久保留**，不要在「以后都按规矩做了」时移除。
- 行为对比（v0.1.1 tag 时）：
  - 旧逻辑 `prev` = 根 commit `651a35bc` → `git log $prev..HEAD` = 仓库全部历史
  - 新逻辑 `prev` = `v0.1.0` → `git log v0.1.0..HEAD` = 11 个 commit（v0.1.0 后真实新增）

## 如何复现 / 验证

```bash
# 触发 fallback 路径（v0.1.1 之前的状态）：
git checkout main
git describe --tags --abbrev=0 2>&1   # fatal: No tags can describe ...

# 新 fallback 选到 v0.1.0：
prev=$(git tag --list 'v*' --sort=-v:refname | grep -vFx "v0.1.1" | head -1)
echo "$prev"   # v0.1.0
git log "$prev..HEAD" --oneline | wc -l   # 11
```

## 关联

- 相关代码：`scripts/release/preflight.sh:71-77`、`.github/workflows/android-release-apk.yml:233-238`
- 引入修复 commit：`e90347f`
- 关联条目：[[MEM-BUILD-0001]]（hotfix 不回流的另一面）、[[MEM-BUILD-0003]]（ff-merge 回 main 是根因解决；本条目是兜底）
- 关联 skill：`.claude/skills/openflow-release-skill/SKILL.md` 第 4 步 preflight + 第 6 步 commit/tag/push

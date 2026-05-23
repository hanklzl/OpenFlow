---
id: MEM-BUILD-0001
created: 2026-05-23
updated: 2026-05-23
source: release-v0.1.1@62e3116
confidence: high
status: draft
verified-at: 2026-05-23
---

# release branch 上的 hotfix 必须回流 main，否则下个版本会重新踩同一个 CI 坑

## 现象 / 事实

v0.1.1 首次 `git push origin v0.1.1` 触发的 CI run 26333321910 在 `Build Release APK → Install Android NDK 29.0.14206865` 这一步立即失败，stderr 出现 `yes: standard output: Broken pipe` 后 `##[error]Process completed with exit code 1`。三 job 矩阵：

- `Build Release APK: failure`
- `Publish GitHub Release: skipped`
- `Publish version manifest: skipped`

排查发现 `.github/workflows/android-release-apk.yml` line 64–68 的 sdk install step 仍然写着：

```yaml
set -euxo pipefail
yes | sdkmanager --install ...
```

`yes` 因 sdkmanager 关闭 stdin 收到 SIGPIPE 退出非零 → `pipefail` 把整条管道判失败。

v0.1.0 发布时（commit `fd9e310`，留在 `release-v0.1.0` branch 上没回流 main）已经修过：

```diff
- yes | sdkmanager --install ...
+ (yes || true) | sdkmanager --install ...
```

同 commit 还修了 gh-pages init 用 `[ ! -d gh-pages/.git ]` 检测会被 actions/checkout continue-on-error 留下的空 `.git` 目录骗过，改为用 `git rev-parse --verify gh-pages`。

## 影响 / 为什么记

任何留在 `release-vX.Y.Z` branch 上、没合回 main 的 hotfix 都会被下一次 release 重新踩到。本次为修这两个老 bug 多花了：

- 1 次失败的 CI run（~1 分钟）
- 删 tag → 写 port commit → 重推 tag 的完整循环

v0.1.1 因此实际推了两次 tag（中间 `git push --delete origin v0.1.1 && git tag -d v0.1.1`）。

与 [[openflow-release-skill]] SKILL.md 的 commit + tag + push 步骤直接相关 —— 当前 skill 写的是「在 release branch 上 commit、push branch、push tag」，没要求 ff-merge 回 main，留下这个坑。

## 如何复现 / 验证

只要 `release-vX.Y.Z` branch 上有 commit 没回到 main 且涉及 `.github/workflows/` 或 `scripts/release/`，下一次 release 的 CI 就可能重新触发对应 bug。验证方法：

```bash
# 查 release branch 与 main 的 workflow / scripts 差异
git diff main..release-vX.Y.Z -- .github/workflows/ scripts/release/
```

如果有 diff 且 release branch 即将被 prune，hotfix 内容就丢了。

防御做法（v0.1.1 已用）：发布步骤改为 `git checkout main && git merge --ff-only release-vX.Y.Z && git tag vX.Y.Z`，让 release commit 进 main 祖先链；任何 release branch 上的 hotfix 自动进入 main。

## 关联

- 相关代码：`.github/workflows/android-release-apk.yml:64-68`（SIGPIPE fix）、`:324-336`（gh-pages init fix）
- 修复 commit：`62e3116 fix(ci): port v0.1.0 hotfix 到 main`
- 历史 commit：`fd9e310 fix(ci): release workflow 两处修复` (v0.1.0 tag 指向的孤儿 commit)
- 关联 skill：`.claude/skills/openflow-release-skill/SKILL.md`（第 6 步「commit + tag + push」应改为 ff-merge 后 tag）
- 关联条目：[[MEM-BUILD-0003]]（release commit 必须 ff-merge 回 main）、[[MEM-BUILD-0004]]（prev tag fallback 是同一根因的另一面）

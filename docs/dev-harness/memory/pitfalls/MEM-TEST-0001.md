---
id: MEM-TEST-0001
created: 2026-05-23
updated: 2026-05-23
source: feat/release-smoke-test@887b076
confidence: high
status: draft
verified-at: 2026-05-23
---

# macOS JVM 上 `File.copyTo(overwrite=true)` 在 target 是目录时不抛异常，而是 copy *into* directory

## 现象 / 事实

实现 `DebugModelInstaller.install(src, target, force)` 时，期望 `target.isDirectory` 场景下 `runCatching` 捕获到 `FileAlreadyExistsException` 并返回 `CopyFailed`。在 macOS JDK（OpenJDK 17）上实测：

```kotlin
val src = File("/tmp/src.bin").apply { writeBytes(...) }
val target = File("/tmp/some-existing-dir").apply { mkdirs() }
src.copyTo(target, overwrite = true)
// 不抛！实际会 copy 到 /tmp/some-existing-dir/src.bin（即把 dst 当成 parent dir）
```

这导致单测 `installReturnsCopyFailedWhenTargetIsADirectory` 在 macOS JVM 上失败（`Result is Done not CopyFailed`）。

修复（commit `887b076`）：在 `runCatching` 内 copy 前显式拦截：

```kotlin
return runCatching {
    target.parentFile?.mkdirs()
    if (target.isDirectory) error("target is a directory: ${target.absolutePath}")
    src.copyTo(target, overwrite = true)
    src.delete()
    InstallResult.Done(target.length())
}.getOrElse { InstallResult.CopyFailed(it) }
```

## 影响 / 为什么记

- R5 命中（修复了 :app:testDebugUnitTest 失败）+ R3 弱命中（plan 文档假设错被反复触发）。
- 不能依赖 `kotlin.io.copyTo` 的「target conflict 必抛」直觉——它的语义在不同 JVM/OS 实现上有微妙差异。
- 任何走 `runCatching { ... copyTo ... }` 路径的代码，如果对「target 是目录」有语义期望（拒绝），都应该显式 `isDirectory` 守卫。
- 与 [`docs/dev-harness/test/rules.md`](../../test/rules.md) 「OpenFlow 暂未引入 Robolectric/Paparazzi」呼应：纯 JVM 单测必须在开发者本地 JVM 实际跑通，不能只看 plan 设想。

## 如何复现 / 验证

```bash
cd /Users/zili/code/android/OpenFlow
./gradlew :app:testDebugUnitTest \
  --tests "com.hank.flow.open.debug.DebugModelInstallerTest.installReturnsCopyFailedWhenTargetIsADirectory"
```

如果某次重构去掉了 `isDirectory` 守卫，本测试在 macOS 上会失败（Linux 行为可能不同——尚未在 Linux JVM 上验证，是潜在 OS 依赖坑）。

## 关联

- 相关代码：`app/src/main/java/com/hank/flow/open/debug/DebugModelInstaller.kt:install()` (isDirectory 守卫)
- 测试：`app/src/test/java/com/hank/flow/open/debug/DebugModelInstallerTest.kt:installReturnsCopyFailedWhenTargetIsADirectory`
- 修复 commit：`887b076`（含 production fix + 8 用例测试）
- 关联条目：[[MEM-BUILD-0001]]（同会话发现：plan 假设错被实测纠正）

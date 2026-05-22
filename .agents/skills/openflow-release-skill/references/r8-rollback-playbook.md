# R8 紧急回滚 Playbook

适用场景：vX.Y.Z release APK 装机即崩（`UnsatisfiedLinkError` / `ClassNotFoundException` / DataStore reads NPE / Logan init crash 等），但 debug 版本同步骤正常。**第一反应：出 patch tag 关 R8**，不要先深挖崩溃栈。Mapping.txt 留作下个 minor 修复时反混淆用。

## 决策树

```
release APK 装机崩
  ├─ debug APK 同步骤复现？
  │   ├─ 是 → 不是 R8 问题，按普通 bug 排查（看 logcat、加日志、走 systematic-debugging）
  │   └─ 否 → 进入下方紧急回滚流程
```

## 紧急回滚步骤

### 1. 建 patch worktree

```bash
cd /Users/zili/code/android/OpenFlow
PATCH_TAG=v0.1.1   # 当前崩的是 v0.1.0 时；按实际 bump patch
git worktree add .worktrees/release-$PATCH_TAG -b release-$PATCH_TAG
cd .worktrees/release-$PATCH_TAG
```

### 2. 关 R8（仅本 patch；下个 minor 重开）

编辑 `app/build.gradle.kts` 的 release buildType：

```kotlin
release {
    signingConfig = signingConfigs.getByName("release")
    isMinifyEnabled = false       // 紧急回滚：暂关
    isShrinkResources = false     // 紧急回滚：暂关
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
    )
}
```

**保留** `signingConfig` 与 `proguardFiles`（规则文件还在，只是不生效；下个 minor 重开 minify 时立即恢复全部规则）。

### 3. bump `version.properties`

```
versionCode=101    # v0.1.0=100 → v0.1.1=101
versionName=0.1.1
```

公式仍是 `MAJOR*10000 + MINOR*100 + PATCH`。

### 4. 本地 preflight + 真机冒烟

```bash
source .env.release.local
bash scripts/release/preflight.sh v0.1.1
# 装机冒烟一次（长按 → ASR → 润色 → 插入），确认基本可用
```

### 5. commit + tag + push

```bash
git add app/build.gradle.kts version.properties
git commit -m "fix(release): v0.1.1 紧急关闭 R8，规避 v0.1.0 装机崩溃"
git tag v0.1.1
git push origin release-v0.1.1
git push origin v0.1.1
gh run watch --exit-status
```

### 6. CI 绿后用户公告

立即通知用户重新下载安装 v0.1.1。同时把 v0.1.0 的 `mapping.zip` 单独存一份（即使 v0.1.0 release 后续被删，mapping 不能丢）—— 反混淆崩溃栈唯一依赖。

## 后续 minor 重开 R8 的检查清单

下一个 minor（如 v0.2.0）时重新打开 R8 前：

- [ ] 用 v0.1.0 的 mapping.txt 反混淆该次崩溃栈，定位到具体 class / method
  - 工具：Android Studio → Tools → R8 Retrace；或 `java -jar retrace.jar mapping.txt stacktrace.txt`
- [ ] 在 `app/proguard-rules.pro` 加对应 `-keep` 规则
- [ ] 跑一次完整 release 构建 + 真机冒烟全链路（长按 → ASR → 润色 → 插入）确认不再崩
- [ ] 在 fork 上推 `v0.2.0-rc1` 走全链路一次（注意：本仓 CI 校验 versionName 字面等于 tag，`-rc1` 后缀会让 CI 失败；fork 临时改 CI 校验放过或走 `workflow_dispatch` 手动跑）
- [ ] 主仓推 `v0.2.0`

## 关联文件

- 当前 R8 规则：`app/proguard-rules.pro`（55 行覆盖 JNI / DataStore / Logan / OkHttp / Coroutines / enum / SourceFile）
- mapping.txt：CI 每次发布产生，归档在 GitHub Release 的 `mapping-vX.Y.Z.zip`
- Android Studio Retrace 入口：`Tools → R8 Retrace`
- 命令行 Retrace：NDK / SDK 自带 `retrace` 脚本，或 `r8retrace-cli` 命令

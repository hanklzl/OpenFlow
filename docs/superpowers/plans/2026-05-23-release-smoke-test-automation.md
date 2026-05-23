# Release 真机冒烟自动化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用 `bash scripts/release/smoke-test.sh` 一条命令完成 release APK 真机冒烟（install → push 模型 → broadcast 触发自验证 pipeline → logcat 判定），替代人工长按悬浮球。

**Architecture:** 复用 `4ff6941` 已落地的 debug 自验证基础设施；在 `main/AndroidManifest.xml` 用 signature permission `DEBUG_SMOKE` 暴露 `DebugAsrReceiver` + `DebugAssetPipelineService`；新增 `DebugModelInstallReceiver` + 纯函数 `DebugModelInstaller` 处理 `adb push /data/local/tmp` 到 `ModelStore.pathFor()` 的搬运；smoke 脚本 + preflight 提示 + skill 文档同步。

**Tech Stack:** Kotlin 2.x、Android SDK 31 target、bash 4+、adb (Platform Tools)、JUnit 4 (纯 JVM 单测，**不**用 Robolectric)、OpenFlowLog (Logan + logcat)

---

## Spec 与实际差异前置说明

实施期与 [spec](../specs/2026-05-23-release-smoke-test-automation-design.md) 一处对齐：

- **`OpenFlowLog.e(tag, event, t = null, fields = emptyMap())`** — 第 3 参是 `Throwable?`，spec 例子里写的 `e(tag, event, mapOf(...))` 会编译失败，本计划改用 `e(tag, event, fields = mapOf(...))`

**模型默认选最轻档**：smoke 用 `whisperTiny = ggml-tiny-q5_1` (32 MB) + `llmTinyNewer = qwen3-0.6b-q4_k_m` (~400 MB)，**不**跟随 `ModelCatalog.whisperDefault` (small, 190 MB) 与 `llmDefault` (qwen2.5-1.5b, 1.1 GB)。理由：smoke 验证的是 R8 + JNI + 整链路通畅，不是模型质量；用最轻档把 push + 加载时间压到最低。

## File Structure

| 操作 | 文件 | 职责 |
|---|---|---|
| 修改 | `app/src/main/java/com/hank/flow/open/log/OpenFlowLog.kt:26` | `Tag` enum 追加 `MODEL` |
| 创建 | `app/src/main/java/com/hank/flow/open/debug/DebugModelInstaller.kt` | **纯函数** install(src, target, force) → InstallResult；无 Android 依赖，单测友好 |
| 创建 | `app/src/main/java/com/hank/flow/open/debug/DebugModelInstallReceiver.kt` | BroadcastReceiver；解 intent → ModelStore.pathFor() → 委托 `DebugModelInstaller.install()` → 把 result 映射到 OpenFlowLog 调用 |
| 创建 | `app/src/test/java/com/hank/flow/open/debug/DebugModelInstallerTest.kt` | 纯 JVM 单测，覆盖 8 个用例 |
| 修改 | `app/src/main/AndroidManifest.xml` | 加 `<permission DEBUG_SMOKE>` + 3 个 `<receiver/service>` 声明 |
| 修改 | `app/src/debug/AndroidManifest.xml` | 删 `<receiver DebugAsrReceiver>` 与 `<service DebugAssetPipelineService>`（debug 包从 main 继承） |
| 创建 | `scripts/release/smoke-test.sh` | 自动化驱动脚本 ~120 行 |
| 修改 | `scripts/release/preflight.sh` | 末尾追加 adb device 检测 + smoke 推荐提示 |
| 修改 | `.claude/skills/openflow-release-skill/SKILL.md` | 第 5 步加自动版段、MUST 段改、常见故障表追加 3 行 |
| 修改 | `docs/superpowers/specs/2026-05-23-release-smoke-test-automation-design.md` | 反映 Section 0 三处实际差异 |

文件粒度选择理由：
- **`DebugModelInstaller`** 单文件（纯函数 + InstallResult sealed result），receiver 极薄 → 单测无需 Robolectric
- 与 `DebugAssetPipelineRunner` / `DebugAsrReceiver` 现有「receiver = intent 边界 + runner = 逻辑」分层一致

---

## Task 1: 给 OpenFlowLog.Tag 追加 MODEL

**Files:**
- Modify: `app/src/main/java/com/hank/flow/open/log/OpenFlowLog.kt:26`

- [ ] **Step 1: 修改 enum**

把第 26 行：
```kotlin
    enum class Tag { A11Y, OVERLAY, FGS, ASR, LLM, INSERT, AUDIO, APP }
```

改为：
```kotlin
    enum class Tag { A11Y, OVERLAY, FGS, ASR, LLM, INSERT, AUDIO, APP, MODEL }
```

`MODEL` 放尾部以保证现有调用站（用 ordinal 或反射的可能性极低，但保守起见）顺序不变。

- [ ] **Step 2: 跑全套单测确认无回归**

```bash
./gradlew :app:testDebugUnitTest --no-daemon
```

预期：BUILD SUCCESSFUL。Tag enum 增项是 source-compat 的。

- [ ] **Step 3: 跑 lint**

```bash
./gradlew :app:lintDebug --no-daemon
```

预期：BUILD SUCCESSFUL（lint 不抱怨 enum）。

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/hank/flow/open/log/OpenFlowLog.kt
git commit -m "chore(log): 给 OpenFlowLog.Tag 追加 MODEL 用于模型管理打点"
```

---

## Task 2: 实现 DebugModelInstaller 纯函数（含 InstallResult sealed）

**Files:**
- Create: `app/src/main/java/com/hank/flow/open/debug/DebugModelInstaller.kt`

- [ ] **Step 1: 写文件**

`app/src/main/java/com/hank/flow/open/debug/DebugModelInstaller.kt`：

```kotlin
package com.hank.flow.open.debug

import java.io.File

/**
 * 纯函数模型搬运器：把 src 文件（通常是 /data/local/tmp/<file>）放到 target
 * （ModelStore.pathFor(entry) 给出的 filesDir/models/<filename>）。
 *
 * 不依赖 Android Context，便于纯 JVM 单测。
 * Receiver 负责把 intent extras 解码成 src / target，再调用 install()。
 */
object DebugModelInstaller {

    sealed class InstallResult {
        data class Done(val targetSize: Long) : InstallResult()
        data class Skip(val targetSize: Long) : InstallResult()
        data class SrcMissing(val srcPath: String) : InstallResult()
        data class CopyFailed(val cause: Throwable) : InstallResult()
    }

    fun install(src: File, target: File, force: Boolean): InstallResult {
        if (!src.exists() || src.length() == 0L) {
            return InstallResult.SrcMissing(src.absolutePath)
        }
        if (!force && target.exists() && target.length() == src.length()) {
            return InstallResult.Skip(target.length())
        }
        return runCatching {
            target.parentFile?.mkdirs()
            src.copyTo(target, overwrite = true)
            src.delete()  // 不阻断结果；删失败时设备 /data/local/tmp 会留临时文件，下次 push 会覆盖
            InstallResult.Done(target.length())
        }.getOrElse { InstallResult.CopyFailed(it) }
    }
}
```

设计说明：
- 用 `sealed class` + `data class` 让 result pattern match 完整且 testable
- `src.delete()` 在 copy 成功后调；失败不影响主流程（设备 /data/local/tmp 自动清理周期会收回）
- 不做 sha256 校验：spec 选 size 比对的幂等策略，校验整文件读 1 GB 太慢

- [ ] **Step 2: 跑 build 确认编译**

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hank/flow/open/debug/DebugModelInstaller.kt
git commit -m "feat(debug): 引入 DebugModelInstaller 纯函数 + InstallResult sealed"
```

---

## Task 3: 写 DebugModelInstaller 单测（红 → 绿一并）

**Files:**
- Create: `app/src/test/java/com/hank/flow/open/debug/DebugModelInstallerTest.kt`

(因为 Task 2 已实现，这里 TDD 红绿循环退化为「写完整测试集 → 跑 → 全绿」。如果偏严格 TDD，可调整 Task 2 与 Task 3 顺序。)

- [ ] **Step 1: 写测试文件**

`app/src/test/java/com/hank/flow/open/debug/DebugModelInstallerTest.kt`：

```kotlin
package com.hank.flow.open.debug

import com.hank.flow.open.debug.DebugModelInstaller.InstallResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DebugModelInstallerTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("of-installer-test").toFile()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun newSrc(name: String, size: Int = 1024, byte: Byte = 0x42): File {
        val f = File(tmp, name)
        f.writeBytes(ByteArray(size) { byte })
        return f
    }

    private fun newTarget(name: String): File = File(File(tmp, "models").apply { mkdirs() }, name)

    @Test
    fun installCopiesSrcToTargetAndDeletesSrcOnFreshInstall() {
        val src = newSrc("ggml-tiny-q5_1.bin", size = 1_000_000)
        val target = newTarget("ggml-tiny-q5_1.bin")
        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.Done)
        assertEquals(1_000_000L, (result as InstallResult.Done).targetSize)
        assertTrue(target.exists())
        assertEquals(1_000_000L, target.length())
        assertFalse("src should be deleted on success", src.exists())
    }

    @Test
    fun installSkipsWhenTargetSameSizeAndForceFalse() {
        val src = newSrc("model.gguf", size = 4096)
        val target = newTarget("model.gguf")
        // 先预置 target 同 size
        target.writeBytes(ByteArray(4096) { 0x99.toByte() })

        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.Skip)
        assertEquals(4096L, (result as InstallResult.Skip).targetSize)
        // target 内容未被覆盖（仍是 0x99 不是 src 的 0x42）
        assertEquals(0x99.toByte(), target.readBytes()[0])
        // src 不应被删（skip 不消耗 src）
        assertTrue(src.exists())
    }

    @Test
    fun installOverwritesWhenForceTrueEvenSameSize() {
        val src = newSrc("model.gguf", size = 4096, byte = 0x42)
        val target = newTarget("model.gguf")
        target.writeBytes(ByteArray(4096) { 0x99.toByte() })

        val result = DebugModelInstaller.install(src, target, force = true)

        assertTrue(result is InstallResult.Done)
        assertEquals(0x42.toByte(), target.readBytes()[0])  // 已被覆盖
        assertFalse(src.exists())
    }

    @Test
    fun installCopiesWhenTargetExistsButSizeDiffers() {
        val src = newSrc("model.gguf", size = 8192)
        val target = newTarget("model.gguf")
        target.writeBytes(ByteArray(4096))  // 不同 size

        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.Done)
        assertEquals(8192L, target.length())
    }

    @Test
    fun installReturnsSrcMissingWhenSrcDoesNotExist() {
        val src = File(tmp, "nonexistent.bin")
        val target = newTarget("model.bin")

        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.SrcMissing)
        assertEquals(src.absolutePath, (result as InstallResult.SrcMissing).srcPath)
        assertFalse(target.exists())
    }

    @Test
    fun installReturnsSrcMissingWhenSrcIsEmpty() {
        val src = newSrc("empty.bin", size = 0)
        val target = newTarget("model.bin")

        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.SrcMissing)
        assertFalse(target.exists())
    }

    @Test
    fun installCreatesTargetParentDirsWhenMissing() {
        val src = newSrc("model.bin", size = 256)
        // 给 target 一个三层深、未创建的父目录
        val target = File(tmp, "nested/dir/structure/model.bin")
        assertFalse(target.parentFile.exists())

        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.Done)
        assertTrue(target.exists())
        assertTrue(target.parentFile.exists())
    }

    @Test
    fun installReturnsCopyFailedWhenTargetIsADirectory() {
        val src = newSrc("model.bin", size = 256)
        val target = newTarget("a-directory")
        target.mkdirs()  // target 是已存在的目录，copyTo 会抛

        val result = DebugModelInstaller.install(src, target, force = true)

        assertTrue("expected CopyFailed but got $result", result is InstallResult.CopyFailed)
        assertNotNull((result as InstallResult.CopyFailed).cause)
    }
}
```

- [ ] **Step 2: 跑测试，确认 8 个用例全绿**

```bash
./gradlew :app:testDebugUnitTest --no-daemon --tests "com.hank.flow.open.debug.DebugModelInstallerTest"
```

预期输出包含 `8 tests completed, 0 failed`、BUILD SUCCESSFUL。

如果有 fail：
- `installSkipsWhenTargetSameSizeAndForceFalse` 失败 → 检查 InstallResult.Skip 分支是否在 src.exists 之后
- `installReturnsCopyFailedWhenTargetIsADirectory` 失败 → kotlin.io.copyTo 在 target 是目录时会抛 `FileAlreadyExistsException`，runCatching 应捕获

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/hank/flow/open/debug/DebugModelInstallerTest.kt
git commit -m "test(debug): DebugModelInstaller 8 个用例覆盖 install/skip/force/missing/parent-mkdirs"
```

---

## Task 4: 写 DebugModelInstallReceiver（薄壳，委托 Installer）

**Files:**
- Create: `app/src/main/java/com/hank/flow/open/debug/DebugModelInstallReceiver.kt`

- [ ] **Step 1: 写文件**

`app/src/main/java/com/hank/flow/open/debug/DebugModelInstallReceiver.kt`：

```kotlin
package com.hank.flow.open.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hank.flow.open.debug.DebugModelInstaller.InstallResult
import com.hank.flow.open.log.OpenFlowLog
import com.hank.flow.open.model.ModelCatalog
import com.hank.flow.open.model.ModelStore
import java.io.File

/**
 * Smoke test 入口：把 adb push 到 /data/local/tmp/<file> 的模型搬到
 * ModelStore.pathFor() 实际位置（filesDir/models/<filename>）。
 *
 *   adb push <local-model> /data/local/tmp/<filename>
 *   adb shell am broadcast \
 *       -a com.hank.flow.open.debug.INSTALL_MODEL_FROM_TMP \
 *       -n com.hank.flow.open/com.hank.flow.open.debug.DebugModelInstallReceiver \
 *       --es srcPath /data/local/tmp/<filename> \
 *       --es modelId ggml-tiny-q5_1 \
 *       --ez force false
 *
 * 受 signature permission `com.hank.flow.open.permission.DEBUG_SMOKE` 保护，
 * 仅同签名应用 + adb shell 可调。
 */
class DebugModelInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val srcPath = intent.getStringExtra("srcPath")
        val modelId = intent.getStringExtra("modelId")
        val force = intent.getBooleanExtra("force", false)

        val entry = modelId?.let { ModelCatalog.byId(it) }
        if (entry == null || srcPath.isNullOrEmpty()) {
            OpenFlowLog.e(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_bad_args",
                fields = mapOf("modelId" to modelId, "srcPath" to srcPath),
            )
            OpenFlowLog.flush()
            return
        }

        val target = ModelStore(context.applicationContext).pathFor(entry)
        when (val result = DebugModelInstaller.install(File(srcPath), target, force)) {
            is InstallResult.Done -> OpenFlowLog.d(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_done",
                mapOf("modelId" to modelId, "size" to result.targetSize),
            )
            is InstallResult.Skip -> OpenFlowLog.d(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_skip",
                mapOf("modelId" to modelId, "size" to result.targetSize),
            )
            is InstallResult.SrcMissing -> OpenFlowLog.e(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_src_missing",
                fields = mapOf("srcPath" to result.srcPath),
            )
            is InstallResult.CopyFailed -> OpenFlowLog.e(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_failed",
                t = result.cause,
            )
        }
        OpenFlowLog.flush()
    }

    companion object {
        const val ACTION = "com.hank.flow.open.debug.INSTALL_MODEL_FROM_TMP"
    }
}
```

注意：
- `OpenFlowLog.e` 第 3 参 `t = result.cause` 或 `fields = mapOf(...)` 必须命名传，不能位置传
- `target.parentFile?.mkdirs()` 已经在 `DebugModelInstaller` 里做了，receiver 不再重复
- `context.applicationContext` 避免持有 BroadcastReceiver 隐式 context

- [ ] **Step 2: 跑 build 确认编译**

```bash
./gradlew :app:compileDebugKotlin --no-daemon
```

预期：BUILD SUCCESSFUL。如果报「too many arguments」或「type mismatch」 → 检查 `OpenFlowLog.e` 命名参数是否写对。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/hank/flow/open/debug/DebugModelInstallReceiver.kt
git commit -m "feat(debug): DebugModelInstallReceiver 委托 Installer 处理 adb push 模型"
```

---

## Task 5: Manifest 调整（main 加 permission + 3 receivers，debug 删 2）

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/debug/AndroidManifest.xml`

- [ ] **Step 1: 读现状**

```bash
cat app/src/main/AndroidManifest.xml | head -50
cat app/src/debug/AndroidManifest.xml
```

记下 `<application>` 标签的位置（用来 anchor edit）。

- [ ] **Step 2: 改 main/AndroidManifest.xml**

在 `<manifest>` 内、`<application>` 之前加 permission 定义：

```xml
    <permission
        android:name="com.hank.flow.open.permission.DEBUG_SMOKE"
        android:protectionLevel="signature" />
```

在 `<application>` 内（任意位置，建议放在已有 `<service>` 块附近以便维护）加 3 个声明：

```xml
        <receiver
            android:name=".debug.DebugAsrReceiver"
            android:exported="true"
            android:permission="com.hank.flow.open.permission.DEBUG_SMOKE">
            <intent-filter>
                <action android:name="com.hank.flow.open.debug.RUN_ASR_FROM_ASSETS" />
            </intent-filter>
        </receiver>

        <service
            android:name=".debug.DebugAssetPipelineService"
            android:exported="true"
            android:permission="com.hank.flow.open.permission.DEBUG_SMOKE" />

        <receiver
            android:name=".debug.DebugModelInstallReceiver"
            android:exported="true"
            android:permission="com.hank.flow.open.permission.DEBUG_SMOKE">
            <intent-filter>
                <action android:name="com.hank.flow.open.debug.INSTALL_MODEL_FROM_TMP" />
            </intent-filter>
        </receiver>
```

- [ ] **Step 3: 改 debug/AndroidManifest.xml**

删除 `<receiver DebugAsrReceiver>` 与 `<service DebugAssetPipelineService>` 两个块。预期改完文件为：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application>
    </application>

</manifest>
```

如果 `<application>` 内空了，可以保留或整个删 `<application>` 块（保留更稳，不影响 manifest merge）。

- [ ] **Step 4: 跑 :app:assembleDebug 验证 manifest merge 不冲突**

```bash
./gradlew :app:assembleDebug --no-daemon
```

预期：BUILD SUCCESSFUL。任何 manifest merge 冲突会被 AGP 在此报错。

- [ ] **Step 5: 也跑一次 :app:assembleRelease 确认 release manifest 拿到新声明**

```bash
source ~/.openflow-smoke-models/../OpenFlow/.env.release.local 2>/dev/null || \
  source $(git rev-parse --show-toplevel)/.env.release.local
./gradlew :app:assembleRelease --no-daemon
```

预期：BUILD SUCCESSFUL。若 R8 抱怨 `DebugModelInstallReceiver` / `DebugAsrReceiver` 被裁，需检查 `app/proguard-rules.pro` 是否覆盖 receiver/service keep 规则（按现有 keep `class * extends android.content.BroadcastReceiver` 应已覆盖；如未覆盖在此 task 同时加）。

- [ ] **Step 6: 用 aapt2 dump 验证 release APK 含 permission + 3 个声明**

```bash
APK=app/build/outputs/apk/release/OpenFlow-arm64-v8a-release.apk
$ANDROID_HOME/build-tools/*/aapt2 dump xmltree "$APK" --file AndroidManifest.xml | \
  grep -E "permission|DEBUG_SMOKE|DebugAsrReceiver|DebugAssetPipelineService|DebugModelInstallReceiver" | head -20
```

预期看到 4 条 `DEBUG_SMOKE` 引用（1 个 permission 定义 + 3 个 `android:permission` 引用）+ 3 个 component name。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/debug/AndroidManifest.xml
git commit -m "feat(debug): main manifest 用 signature permission 暴露 debug receiver/service，删 debug-only 副本"
```

---

## Task 6: 写 scripts/release/smoke-test.sh

**Files:**
- Create: `scripts/release/smoke-test.sh`

- [ ] **Step 1: 写脚本**

`scripts/release/smoke-test.sh`（注意：默认值与实际 ModelCatalog 对齐）：

```bash
#!/usr/bin/env bash
# OpenFlow release APK 真机自动冒烟。
# 流程：adb install → push 模型 → broadcast INSTALL → broadcast RUN_ASR → logcat 等结果。
# 退出码：0 成功 / 1 测试失败 / 2 setup 错（缺 APK / 设备 / 模型）。
set -euo pipefail

APK="${APK:-app/build/outputs/apk/release/OpenFlow-arm64-v8a-release.apk}"
DEVICE=""
MODELS_DIR="${MODELS_DIR:-$HOME/.openflow-smoke-models}"
WHISPER="ggml-tiny-q5_1"                       # ModelCatalog.whisperDefault.id
WHISPER_FILE="ggml-tiny-q5_1.bin"              # hfPath 末段；ModelStore.pathFor 会取这个名
LLM="qwen3-0.6b-q4_k_m"              # ModelCatalog.llmDefault.id
LLM_FILE="qwen3-0.6b-q4_k_m.gguf"
POLISH="true"
FORCE_REINSTALL="false"
TIMEOUT=60
PKG="com.hank.flow.open"

usage() {
    cat <<EOF >&2
Usage: $0 [options]
  --apk <path>          APK 路径 (default: $APK)
  --device <serial>     adb 设备序列号 (default: first attached)
  --models-dir <path>   本机模型目录 (default: \$HOME/.openflow-smoke-models)
  --whisper <id>        Whisper 模型 id (default: $WHISPER)
  --whisper-file <name> Whisper 模型文件名 (default: \${WHISPER}.bin via hfPath)
  --llm <id>            LLM 模型 id (default: $LLM)
  --llm-file <name>     LLM 模型文件名 (default: \${LLM}.gguf via hfPath)
  --no-polish           关闭 polish 阶段（默认开启）
  --force-reinstall     强制覆盖设备上已存在模型
  --timeout <sec>       pipeline 等结果超时 (default: $TIMEOUT)
EOF
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --device) DEVICE="$2"; shift 2 ;;
        --models-dir) MODELS_DIR="$2"; shift 2 ;;
        --whisper) WHISPER="$2"; shift 2 ;;
        --whisper-file) WHISPER_FILE="$2"; shift 2 ;;
        --llm) LLM="$2"; shift 2 ;;
        --llm-file) LLM_FILE="$2"; shift 2 ;;
        --no-polish) POLISH="false"; shift ;;
        --force-reinstall) FORCE_REINSTALL="true"; shift ;;
        --timeout) TIMEOUT="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "::error::unknown arg: $1" >&2; usage ;;
    esac
done

# 1. sanity
[ -f "$APK" ] || { echo "::error::APK not found: $APK" >&2; exit 2; }
devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
[ -n "$devices" ] || { echo "::error::no adb device connected (adb devices empty)" >&2; exit 2; }
[ -z "$DEVICE" ] && DEVICE=$(echo "$devices" | head -1)
[ -d "$MODELS_DIR" ] || { echo "::error::models dir not found: $MODELS_DIR" >&2; exit 2; }
echo "[smoke] device=$DEVICE apk=$APK polish=$POLISH"

# 2. install -g 自动授予 runtime 权限，避免麦克风/通知授权对话框阻塞
adb -s "$DEVICE" install -r -g "$APK"

# 3. logcat 后台跟（先清，避免历史污染）
adb -s "$DEVICE" logcat -c
LOG=$(mktemp -t openflow-smoke.XXXXXX.log)
adb -s "$DEVICE" logcat -s OpenFlow/MODEL:D OpenFlow/ASR:D OpenFlow/LLM:D AndroidRuntime:E > "$LOG" &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null || true; rm -f "$LOG"' EXIT
sleep 1  # 让 logcat 跑起来

# 4. preload 模型
preload_model() {
    local id="$1" fname="$2"
    local src="$MODELS_DIR/$fname"
    [ -f "$src" ] || {
        echo "::error::missing $src" >&2
        echo "  请把 $fname 放到 $MODELS_DIR/ 后重试" >&2
        exit 2
    }
    echo "[smoke] push $fname → /data/local/tmp/"
    adb -s "$DEVICE" push "$src" "/data/local/tmp/$fname" >/dev/null
    adb -s "$DEVICE" shell am broadcast \
        -a com.hank.flow.open.debug.INSTALL_MODEL_FROM_TMP \
        -n "$PKG/com.hank.flow.open.debug.DebugModelInstallReceiver" \
        --es srcPath "/data/local/tmp/$fname" \
        --es modelId "$id" \
        --ez force "$FORCE_REINSTALL" >/dev/null

    local deadline=$(( $(date +%s) + 30 ))
    while [ $(date +%s) -lt $deadline ]; do
        if grep -qE "debug_model_install_(done|skip).*modelId=$id" "$LOG"; then
            grep -E "debug_model_install_(done|skip).*modelId=$id" "$LOG" | tail -1
            return 0
        fi
        if grep -qE "debug_model_install_(failed|bad_args|src_missing)" "$LOG"; then
            echo "::error::install $id failed" >&2
            grep -E "debug_model_install_" "$LOG" | tail -5 >&2
            exit 1
        fi
        sleep 0.5
    done
    echo "::error::install $id timeout after 30s" >&2
    exit 1
}

preload_model "$WHISPER" "$WHISPER_FILE"
[ "$POLISH" = "true" ] && preload_model "$LLM" "$LLM_FILE"

# 5. broadcast pipeline
echo "[smoke] broadcast RUN_ASR_FROM_ASSETS"
adb -s "$DEVICE" shell am broadcast \
    -a com.hank.flow.open.debug.RUN_ASR_FROM_ASSETS \
    -n "$PKG/com.hank.flow.open.debug.DebugAsrReceiver" \
    --es wavAsset test/jfk.wav \
    --es whisperId "$WHISPER" \
    --es llmId "$LLM" \
    --ez polish "$POLISH" >/dev/null

# 6. 等结果
deadline=$(( $(date +%s) + TIMEOUT ))
while [ $(date +%s) -lt $deadline ]; do
    if grep -qE "debug_asset_finished" "$LOG"; then
        echo "[smoke] ✓ debug_asset_finished"
        grep -E "debug_asset_(asr|polish)_done|debug_asset_finished" "$LOG" | tail -3
        exit 0
    fi
    if grep -qE "debug_asset_abort_no_model|debug_asset_abort_no_llm" "$LOG"; then
        echo "::error::模型未就绪（install 未生效或 size 校验不过）" >&2
        grep -E "debug_asset_(whisper|llm)_ready|debug_asset_abort_" "$LOG" | tail -5 >&2
        exit 1
    fi
    if grep -qE "debug_asset_service_start_failed|FATAL EXCEPTION" "$LOG"; then
        echo "::error::service start failed / runtime crash" >&2
        tail -50 "$LOG" >&2
        adb -s "$DEVICE" exec-out run-as "$PKG" tar c -C files/logan . 2>/dev/null \
            > "smoke-fail-logan-$(date +%s).tar" || \
            echo "  （run-as 拉 Logan 失败；release APK debuggable=false 时常见，忽略）" >&2
        exit 1
    fi
    sleep 1
done

echo "::error::smoke timeout after ${TIMEOUT}s, last 30 log lines:" >&2
tail -30 "$LOG" >&2
exit 1
```

- [ ] **Step 2: 让脚本可执行 + 跑 --help 验证语法**

```bash
chmod +x scripts/release/smoke-test.sh
bash scripts/release/smoke-test.sh --help
```

预期：打印 Usage 块、退出码 2（usage 函数 exit 2）。

如果 syntax error → 看错信息修。

- [ ] **Step 3: 跑一次 sanity（无设备时）**

确保当前 adb 没有连真机时跑：

```bash
adb kill-server 2>/dev/null || true
adb devices  # 应只列 "List of devices attached" 后空行
bash scripts/release/smoke-test.sh 2>&1 | head -5
```

预期看到 `::error::no adb device connected` + exit 2。脚本不应执行 install 或 push。

- [ ] **Step 4: Commit**

```bash
git add scripts/release/smoke-test.sh
git commit -m "feat(release): scripts/release/smoke-test.sh 自动化真机冒烟驱动"
```

---

## Task 7: preflight.sh 末尾追加 adb device 检测块

**Files:**
- Modify: `scripts/release/preflight.sh`

- [ ] **Step 1: 读现状定位插入点**

```bash
tail -15 scripts/release/preflight.sh
```

找到最后那行 `echo "Preflight passed."`。新内容要插在它之前。

- [ ] **Step 2: 插入 detection block**

在 `echo "Preflight passed."` 之前追加：

```bash
echo "[dry] Optional: smoke test on attached device"
if command -v adb >/dev/null 2>&1 && \
   adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
    echo "  推荐："
    echo "    bash scripts/release/smoke-test.sh --apk $arm_apk"
    echo "  （要求 ~/.openflow-smoke-models/ 已放 whisper + llm 默认模型）"
else
    echo "  跳过：未检测到 adb 设备（smoke 可选；插入设备后单独跑 scripts/release/smoke-test.sh）"
fi

```

注意：`$arm_apk` 是 preflight 上文已定义的变量（见现有第 36 行附近）。

- [ ] **Step 3: 跑 preflight 干验证（用最近一次 release 的 tag 复跑）**

```bash
source .env.release.local
bash scripts/release/preflight.sh v0.1.1 2>&1 | tail -20
```

预期：`Preflight passed.` 之前出现 detection 段。如果当前插着设备：看到「推荐：...」+ 真实 apk 路径；如果没插：看到「跳过：...」。两种都不应让 preflight 失败。

- [ ] **Step 4: Commit**

```bash
git add scripts/release/preflight.sh
git commit -m "feat(release): preflight 末尾加 adb 设备检测，推荐跑 smoke-test.sh"
```

---

## Task 8: 更新 SKILL.md 第 5 步 + MUST 段 + 故障表

**Files:**
- Modify: `.claude/skills/openflow-release-skill/SKILL.md`

- [ ] **Step 1: 找到「真机冒烟（**硬要求**）」段（约 L120-131）**

```bash
grep -n "真机冒烟" .claude/skills/openflow-release-skill/SKILL.md
```

- [ ] **Step 2: 把整个第 5 步替换为新版本**

把原段：

```markdown
### 5. 真机冒烟（**硬要求**）

把 preflight 输出的 `app/build/outputs/apk/release/OpenFlow-arm64-v8a-release.apk` 装到一台真机，跑：

1. 启动 → 三 tab 渲染正常
2. 长按悬浮球 → 录一句中文 → 释放 → ASR 出文 → 润色 → 文本被 `ACTION_SET_TEXT` 写入第三方 `EditText`

如果第 2 步任意环节崩了或卡住 → 进入下方「R8 紧急回滚」决策树。**真机绿之前不要推 tag**。
```

替换为：

````markdown
### 5. 真机冒烟（**硬要求**）

装 preflight 输出的 release APK 到真机验证全链路一次。两种走法等效。

#### 自动版（推荐）

**一次性准备**（首次跑前完成）：

```bash
mkdir -p ~/.openflow-smoke-models
# Whisper 最轻档（32 MB）
curl -L -o ~/.openflow-smoke-models/ggml-tiny-q5_1.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin
# LLM 极速档（~400 MB）— 对应 ModelCatalog.llmTinyNewer
curl -L -o ~/.openflow-smoke-models/qwen3-0.6b-q4_k_m.gguf \
  https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf
```

**每次发布前**：

```bash
bash scripts/release/smoke-test.sh
# 退出码：0 通过 / 1 失败 / 2 setup 错（缺设备 / 缺 APK / 缺模型）
```

脚本动作：adb install release APK → push 模型到 /data/local/tmp → broadcast `INSTALL_MODEL_FROM_TMP` 让 app 自己搬到 filesDir/models/（幂等，size 一致跳过 copy）→ broadcast `RUN_ASR_FROM_ASSETS` 跑 jfk.wav → 监听 `OpenFlowLog`：

- ✅ `debug_asset_finished` → 退出 0
- ❌ `debug_asset_abort_*` / `service_start_failed` / `FATAL EXCEPTION` → 退出 1，失败时 `run-as` 尝试拉 Logan db 留 `smoke-fail-logan-<ts>.tar`
- ❌ 60s 超时 → 退出 1

依赖：`com.hank.flow.open.permission.DEBUG_SMOKE`（signature）保护 `DebugAsrReceiver` + `DebugAssetPipelineService` + `DebugModelInstallReceiver`，adb shell 默认通过；外部应用无法触发。

**首次约 50–70s（含 push ~400 MB Qwen3-0.6b），第二次起约 12–18s**。

#### 手动版（fallback）

设备没插 USB / 无 adb / 想验证全 UI 交互流（含 `ACTION_SET_TEXT` 写入第三方 EditText）时仍走人工：

1. 启动 → 三 tab 渲染正常
2. 长按悬浮球 → 录中文 → 释放 → ASR 出文 → 润色 → 文本插入第三方 EditText

如果任意环节崩了或卡住 → 进入下方「R8 紧急回滚」决策树。**真机绿（自动或手动任一）之前不要推 tag**。
````

- [ ] **Step 3: 更新 MUST 段（约 L42）**

把：

```markdown
- **MUST**：真机装签名 release APK 跑长按 → 录音 → ASR → 润色 → 插入全链路一次。R8 + JNI 问题**只在签名 release APK + 真机**能复现，debug 与 emulator 都看不见。
```

替换为：

```markdown
- **MUST**：真机装签名 release APK 验证全链路一次。两种走法等效：
  - **自动**：`bash scripts/release/smoke-test.sh` 退出 0
  - **手动**：长按悬浮球 → 录中文 → ASR → 润色 → `ACTION_SET_TEXT` 写入第三方 EditText
  R8 + JNI 问题**只在签名 release APK + 真机**能复现，debug 与 emulator 都看不见。
```

- [ ] **Step 4: 「常见故障表」追加 3 行**

在故障表末尾追加：

```markdown
| smoke `debug_asset_abort_no_model` | 模型 INSTALL broadcast 未生效 / 设备已有模型 size 与本机不一致 | 看 logcat `debug_model_install_*`；`smoke-test.sh --force-reinstall` 重传 |
| smoke setup exit 2「缺模型」 | `~/.openflow-smoke-models/` 缺文件 | 按 SKILL 第 5 步「一次性准备」段重下 |
| smoke `service_start_failed` | release APK signature permission 不匹配（用了不同 keystore） | 确认 `.env.release.local` 指向同一个 keystore；先 `adb uninstall com.hank.flow.open` 再试 |
```

- [ ] **Step 5: 跑一遍 grep 验证 3 个 anchor 都存在**

```bash
grep -nE "自动版（推荐）|smoke-test\.sh|DEBUG_SMOKE" .claude/skills/openflow-release-skill/SKILL.md
```

预期看到至少 3 处 hit。

- [ ] **Step 6: Commit**

```bash
git add .claude/skills/openflow-release-skill/SKILL.md
git commit -m "docs(skill): openflow-release-skill 第 5 步加自动版 smoke + MUST 二选一 + 故障表 3 行"
```

---

## Task 9: 把 spec 「已知限制 / 未覆盖」段里 LLM ID verify 项清掉

**Files:**
- Modify: `docs/superpowers/specs/2026-05-23-release-smoke-test-automation-design.md`

spec 用 `qwen3-0.6b-instruct-q4_k_m`，实际 catalog id 是 `qwen3-0.6b-q4_k_m`（无 `instruct`）。spec 「已知限制」段曾让实施期 verify，已在本计划落实，相应注脚可删。其余 spec 内容（含速度估算 ~80s）与本计划吻合，无需 sync。

- [ ] **Step 1: 找到对应行**

```bash
grep -n "ModelCatalog.llmDefault.id\|qwen3-0.6b-instruct-q4_k_m" docs/superpowers/specs/2026-05-23-release-smoke-test-automation-design.md
```

- [ ] **Step 2: 把 spec 中 `qwen3-0.6b-instruct-q4_k_m` 修正为 `qwen3-0.6b-q4_k_m`**

```bash
sed -i '' 's/qwen3-0\.6b-instruct-q4_k_m/qwen3-0.6b-q4_k_m/g' \
    docs/superpowers/specs/2026-05-23-release-smoke-test-automation-design.md
```

- [ ] **Step 3: 删 spec 「已知限制 / 未覆盖」段里的 LLM verify 注脚**

打开 spec 找到这一行：

```
- LLM 默认 ID 在 spec 中写作 `qwen3-0.6b-q4_k_m`，实施期需 verify 与 `ModelCatalog.llmDefault.id` 是否一致；不一致则更新 spec + smoke-test.sh 默认值
```

整行删除（plan 已显式选用 `llmTinyNewer = qwen3-0.6b-q4_k_m`，不再跟随 `llmDefault`）。

- [ ] **Step 4: 跑 grep 确认 spec 无残留**

```bash
grep -nE "qwen3-0\.6b-instruct-q4_k_m|ModelCatalog\.llmDefault\.id 是否一致" \
    docs/superpowers/specs/2026-05-23-release-smoke-test-automation-design.md
```

预期：无输出。

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-05-23-release-smoke-test-automation-design.md
git commit -m "docs(spec): smoke spec 修正 LLM id (无 instruct)，去掉已落实的 verify 注脚"
```

---

## Task 10: 集成验证 + memory 沉淀

**Files:**
- 仅文档；不改代码

- [ ] **Step 1: 手动跑一次完整 smoke（如有真机 + 模型）**

```bash
# 准备
mkdir -p ~/.openflow-smoke-models
[ -f ~/.openflow-smoke-models/ggml-tiny-q5_1.bin ] || \
  curl -L -o ~/.openflow-smoke-models/ggml-tiny-q5_1.bin \
    https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin
[ -f ~/.openflow-smoke-models/qwen3-0.6b-q4_k_m.gguf ] || \
  curl -L -o ~/.openflow-smoke-models/qwen3-0.6b-q4_k_m.gguf \
    https://huggingface.co/unsloth/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q4_K_M.gguf

# 跑
source .env.release.local
./gradlew :app:assembleRelease --no-daemon
bash scripts/release/smoke-test.sh
echo "exit=$?"
```

预期：首次 ~110s 输出 `[smoke] ✓ debug_asset_finished`，exit 0。

如无真机，跳到 Step 2。

- [ ] **Step 2: 制造失败用例验证 exit 1 路径（如有真机）**

```bash
# 强制覆盖 + 路径错
adb shell rm -f /data/local/tmp/non_existent.bin
adb shell am broadcast \
    -a com.hank.flow.open.debug.INSTALL_MODEL_FROM_TMP \
    -n com.hank.flow.open/com.hank.flow.open.debug.DebugModelInstallReceiver \
    --es srcPath /data/local/tmp/non_existent.bin \
    --es modelId ggml-tiny-q5_1
adb logcat -d -s OpenFlow/MODEL:D | tail -5
```

预期看到 `debug_model_install_src_missing`。

- [ ] **Step 3: 沉淀 memory（调用 memory-sediment-skill）**

启动一次 memory-sediment-skill 评估本次开发是否有新发现值得沉淀。最可能值得记的：

- 「OpenFlow 测试基础设施纯 JVM 无 Robolectric」→ `conventions/MEM-TEST-XXXX`
- 「ModelStore.pathFor 用 hfPath.substringAfterLast，不是 id+ext」→ `conventions/MEM-MODEL-XXXX`

具体由 skill 自主判定 + 单独 commit。

- [ ] **Step 4: 合 release-smoke 工作回 main（如在 worktree 操作）**

如果实施全程在 `.worktrees/release-smoke-test/` worktree：

```bash
cd /Users/zili/code/android/OpenFlow
git checkout main
git merge --ff-only release-smoke-test
git push origin main
git worktree remove --force .worktrees/release-smoke-test
git branch -d release-smoke-test
```

如果直接在 main 操作（用户允许），跳过此步：

```bash
git push origin main
```

- [ ] **Step 5: Task #9 标 completed**

更新原 conversation 里的 Task #9 状态为 completed。

---

## Self-Review Done

| 检查 | 结果 |
|---|---|
| Spec coverage | ✓ 7 项（manifest + permission / installer + receiver / smoke / preflight / SKILL / spec sync / 验证）全覆盖；spec 「测试 / 验证策略」对应 Task 3 + 10 |
| 类型一致 | ✓ `InstallResult.{Done, Skip, SrcMissing, CopyFailed}` 在 Installer/Receiver/Test 三处一致 |
| Placeholder | ✓ 无 TBD / 「类似 Task N」/ 「适当处理」 |
| 命令完整 | ✓ 每个 gradlew/adb/git 步骤含具体期望输出 |
| 现实差异处理 | ✓ Task 9 专门同步 spec；OpenFlowLog.e 签名差异在 Task 4 代码示例已修正 |

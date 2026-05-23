# Release 真机冒烟自动化设计

- **Date**: 2026-05-23
- **Status**: Design approved, ready for implementation plan
- **Motivation**: v0.1.1 release session 中真机冒烟被跳过（操作者要求「发布流程不能有人工的」），暴露 [`openflow-release-skill`](../../../.claude/skills/openflow-release-skill/SKILL.md) 第 5 步「真机冒烟（**硬要求**）」与「自动化发布」目标之间的缺口。

## 目标 / 非目标

**目标**

- 用 `adb` 一条命令完成「装 release APK → 准备模型 → 跑全链路 → 判定通过/失败」，替代人工长按悬浮球
- 复用 commit `4ff6941` 已落地的 debug 自验证基础设施（`DebugAsrReceiver` / `DebugAssetPipelineRunner` / `WavReader` / `assets/test/jfk.wav`）
- 覆盖 release-only 的两个真正风险：**R8 混淆 + JNI**（这是 debug 包看不见的）
- 不引入 CI 改动（CI 无真机），脚本本地工具定位

**非目标**

- 不替代 UI 完整交互测试（手动版仍保留作 fallback，用户验全 `ACTION_SET_TEXT` 写入第三方 EditText 等纯 UI 路径）
- 不接 Firebase Test Lab / BrowserStack（接口稳定后可后续扩展）
- 不在 CI 跑（CI 无真机）

## 现状盘点

commit `4ff6941 feat(debug): 增加 ASR 与润色自验证链路` 已实现的零件：

| 组件 | 文件 | 当前状态 |
|---|---|---|
| BroadcastReceiver | `app/src/main/java/com/hank/flow/open/debug/DebugAsrReceiver.kt` | 接 `am broadcast`，转给 service |
| Service | `app/src/main/java/com/hank/flow/open/debug/DebugAssetPipelineService.kt` | 跑 pipeline |
| Pipeline 主逻辑 | `app/src/main/java/com/hank/flow/open/debug/DebugAssetPipelineRunner.kt` | wav→Whisper→Polish 全链路 + 关键事件日志 |
| Wav 读取 | `app/src/main/java/com/hank/flow/open/debug/WavReader.kt` | 解析 PCM |
| 测试音频 | `app/src/main/assets/test/jfk.wav` (344 KB) | **已在 main 源集**（非 debug-only） |
| Receiver/Service 注册 | `app/src/debug/AndroidManifest.xml` | **仅 debug 包**，release 包不带 |
| 已发结束信号 | `OpenFlowLog.d(Tag.ASR, "debug_asset_finished", ...)` | OK |
| 已发失败信号 | `debug_asset_abort_no_model` / `debug_asset_abort_no_llm` / `debug_asset_service_start_failed` | OK |

缺口：

1. release 包不暴露 receiver/service
2. 模型预装入口（release APK 是 `android:debuggable="false"`，`run-as` 在 Android 12+ 不可用）
3. 自动化驱动脚本
4. 与 [`openflow-release-skill`](../../../.claude/skills/openflow-release-skill/SKILL.md) 第 5 步「真机冒烟」段的集成

## 整体架构

```
┌─ App side ────────────────────────────────────────────┐
│  main/AndroidManifest.xml                              │
│    + <permission DEBUG_SMOKE protectionLevel=signature>│
│    + <receiver DebugAsrReceiver         perm=DEBUG_SMOKE>
│    + <service  DebugAssetPipelineService perm=DEBUG_SMOKE>
│    + <receiver DebugModelInstallReceiver perm=DEBUG_SMOKE> (新增)
│  debug/AndroidManifest.xml ← 删对应 receiver/service │
│  debug/DebugModelInstallReceiver.kt ← 新增 ~50 行     │
└────────────────────────────────────────────────────────┘
                       │ adb shell am broadcast
                       ▼
┌─ Tool side ───────────────────────────────────────────┐
│  scripts/release/smoke-test.sh ← 新增 ~120 行          │
│    ├─ args/sanity (apk, device, models-dir, timeout)  │
│    ├─ adb install -r -g APK                           │
│    ├─ adb logcat -s OpenFlow:D AndroidRuntime:E & bg  │
│    ├─ preload_model: push + broadcast INSTALL_MODEL   │
│    │     (whisper + 可选 llm；幂等，size 一致跳过)    │
│    ├─ broadcast RUN_ASR_FROM_ASSETS jfk.wav           │
│    ├─ poll 1s: debug_asset_finished / abort_* / FATAL │
│    │     / 超时 60s                                    │
│    └─ exit 0 (pass) / 1 (fail) / 2 (setup)            │
│                                                        │
│  scripts/release/preflight.sh ← +10 行末尾追加         │
│    + detect adb device → 提示推荐跑 smoke-test.sh     │
│                                                        │
│  .claude/skills/openflow-release-skill/SKILL.md       │
│    + 第 5 步「真机冒烟」加「自动版（推荐）」段        │
│    + MUST 段改为「自动 OR 手动 等效」                 │
└────────────────────────────────────────────────────────┘
```

## 详细设计

### 1. Manifest + permission

`app/src/main/AndroidManifest.xml`（追加在 `<application>` 之前 + 内）：

```xml
<permission
    android:name="com.hank.flow.open.permission.DEBUG_SMOKE"
    android:protectionLevel="signature" />

<application ...>
  <!-- 移自 src/debug/AndroidManifest.xml -->
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

  <!-- 新增 -->
  <receiver
      android:name=".debug.DebugModelInstallReceiver"
      android:exported="true"
      android:permission="com.hank.flow.open.permission.DEBUG_SMOKE">
      <intent-filter>
          <action android:name="com.hank.flow.open.debug.INSTALL_MODEL_FROM_TMP" />
      </intent-filter>
  </receiver>
</application>
```

`app/src/debug/AndroidManifest.xml`：删除 `<receiver DebugAsrReceiver>` 与 `<service DebugAssetPipelineService>`（debug 包从 main 继承）。

**安全语义**：

- adb shell (uid=2000) 默认拥有 platform shell signature，调 broadcast 通过
- 同签名的另一个 OpenFlow APK（不存在场景但可能）拥有 permission，通过
- 第三方应用：声明 `<uses-permission DEBUG_SMOKE>` 在 install 时被忽略（无匹配 signer），调不动

### 2. DebugModelInstallReceiver（新文件 ~50 行）

`app/src/main/java/com/hank/flow/open/debug/DebugModelInstallReceiver.kt`：

```kotlin
package com.hank.flow.open.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hank.flow.open.log.OpenFlowLog
import com.hank.flow.open.model.ModelCatalog
import com.hank.flow.open.model.ModelStore
import java.io.File

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
                mapOf("modelId" to modelId, "srcPath" to srcPath),
            )
            return
        }

        val src = File(srcPath)
        val target = ModelStore(context).pathFor(entry).apply { parentFile?.mkdirs() }

        if (!src.exists() || src.length() == 0L) {
            OpenFlowLog.e(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_src_missing",
                mapOf("srcPath" to srcPath),
            )
            return
        }

        if (!force && target.exists() && target.length() == src.length()) {
            OpenFlowLog.d(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_skip",
                mapOf("modelId" to modelId, "size" to target.length()),
            )
            return
        }

        runCatching {
            src.copyTo(target, overwrite = true)
            src.delete()
            OpenFlowLog.d(
                OpenFlowLog.Tag.MODEL,
                "debug_model_install_done",
                mapOf("modelId" to modelId, "size" to target.length()),
            )
        }.onFailure {
            OpenFlowLog.e(OpenFlowLog.Tag.MODEL, "debug_model_install_failed", it)
        }
        OpenFlowLog.flush()
    }
}
```

实现细节：

- **依赖 `OpenFlowLog.Tag.MODEL`，当前 enum 不存在**（实际为 `{A11Y, OVERLAY, FGS, ASR, LLM, INSERT, AUDIO, APP}`，见 `app/src/main/java/com/hank/flow/open/log/OpenFlowLog.kt:26`）。实施期决策：
  - **方案 A（推荐）**：在 `OpenFlowLog.Tag` enum 追加 `MODEL`。一行改动，语义清晰，未来 `ModelDownloader` / `ModelStore` 的诊断打点都能用。
  - **方案 B**：复用 `Tag.APP`。零 enum 变动但语义混淆。
  - 默认走 A；同 commit 提交 receiver + enum 追加。
- 幂等：target 已存在 + size 一致 + `force=false` 跳过 copy，仅 log skip。
- 完成后删除 `/data/local/tmp/<file>` 避免设备空间堆积（受限存储下 500 MB 双拷贝代价高）。
- 失败 log + `OpenFlowLog.flush()` 立即落地到 Logan，便于 smoke 失败时 adb 拉取证据。

### 3. smoke-test.sh

`scripts/release/smoke-test.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail

APK="${APK:-app/build/outputs/apk/release/OpenFlow-arm64-v8a-release.apk}"
DEVICE=""
MODELS_DIR="${MODELS_DIR:-$HOME/.openflow-smoke-models}"
WHISPER="ggml-tiny-q5_1"
LLM="qwen3-0.6b-instruct-q4_k_m"  # 跟随 ModelCatalog.llmDefault 调整
POLISH="true"
FORCE_REINSTALL="false"
TIMEOUT=60
PKG="com.hank.flow.open"

# arg parse
while [ $# -gt 0 ]; do
    case "$1" in
        --apk)             APK="$2"; shift 2 ;;
        --device)          DEVICE="$2"; shift 2 ;;
        --models-dir)      MODELS_DIR="$2"; shift 2 ;;
        --whisper)         WHISPER="$2"; shift 2 ;;
        --llm)             LLM="$2"; shift 2 ;;
        --no-polish)       POLISH="false"; shift ;;
        --force-reinstall) FORCE_REINSTALL="true"; shift ;;
        --timeout)         TIMEOUT="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

# 1. sanity
[ -f "$APK" ] || { echo "::error::APK not found: $APK" >&2; exit 2; }
devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
[ -n "$devices" ] || { echo "::error::no adb device" >&2; exit 2; }
[ -z "$DEVICE" ] && DEVICE=$(echo "$devices" | head -1)
echo "[smoke] device=$DEVICE  apk=$APK"

# 2. install
adb -s "$DEVICE" install -r -g "$APK"

# 3. logcat 后台跟（先清，避免历史污染）
adb -s "$DEVICE" logcat -c
LOG=$(mktemp -t openflow-smoke.XXXXXX.log)
adb -s "$DEVICE" logcat -s OpenFlow:D AndroidRuntime:E > "$LOG" &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null || true; rm -f "$LOG"' EXIT

# 4. preload 模型
preload_model() {
    local id="$1" ext="$2"
    local fname="${id}${ext}"
    local src="$MODELS_DIR/$fname"
    [ -f "$src" ] || { echo "::error::missing $src" >&2; exit 2; }
    echo "[smoke] push $fname"
    adb -s "$DEVICE" push "$src" "/data/local/tmp/$fname" >/dev/null
    adb -s "$DEVICE" shell am broadcast \
        -a com.hank.flow.open.debug.INSTALL_MODEL_FROM_TMP \
        -n "$PKG/com.hank.flow.open.debug.DebugModelInstallReceiver" \
        --es srcPath "/data/local/tmp/$fname" \
        --es modelId "$id" \
        --ez force "$FORCE_REINSTALL" >/dev/null
    local deadline=$(( $(date +%s) + 30 ))
    while [ $(date +%s) -lt $deadline ]; do
        if grep -qE "debug_model_install_(done|skip).*$id" "$LOG"; then
            grep -E "debug_model_install_(done|skip).*$id" "$LOG" | tail -1
            return 0
        fi
        if grep -qE "debug_model_install_(failed|bad_args|src_missing)" "$LOG"; then
            echo "::error::install $id failed" >&2
            grep -E "debug_model_install_" "$LOG" | tail -5 >&2
            exit 1
        fi
        sleep 0.5
    done
    echo "::error::install $id timeout" >&2
    exit 1
}

preload_model "$WHISPER" ".bin"
[ "$POLISH" = "true" ] && preload_model "$LLM" ".gguf"

# 5. broadcast pipeline
echo "[smoke] broadcast RUN_ASR_FROM_ASSETS"
adb -s "$DEVICE" shell am broadcast \
    -a com.hank.flow.open.debug.RUN_ASR_FROM_ASSETS \
    -n "$PKG/com.hank.flow.open.debug.DebugAsrReceiver" \
    --es wavAsset test/jfk.wav \
    --es whisperId "$WHISPER" \
    --es llmId "$LLM" \
    --ez polish "$POLISH" >/dev/null

# 6. wait for result
deadline=$(( $(date +%s) + TIMEOUT ))
while [ $(date +%s) -lt $deadline ]; do
    if grep -qE "debug_asset_finished" "$LOG"; then
        echo "[smoke] ✓ debug_asset_finished"
        grep -E "debug_asset_(asr|polish)_done|debug_asset_finished" "$LOG" | tail -3
        exit 0
    fi
    if grep -qE "debug_asset_abort_no_model|debug_asset_abort_no_llm" "$LOG"; then
        echo "::error::模型未装好，先在设备上下载后重试" >&2
        grep -E "debug_asset_(whisper|llm)_ready|debug_asset_abort_" "$LOG" | tail -5 >&2
        exit 1
    fi
    if grep -qE "debug_asset_service_start_failed|FATAL EXCEPTION" "$LOG"; then
        echo "::error::service start failed / runtime crash" >&2
        tail -50 "$LOG" >&2
        adb -s "$DEVICE" exec-out run-as "$PKG" tar c -C files/logan . 2>/dev/null \
            > "smoke-fail-logan-$(date +%s).tar" || true
        exit 1
    fi
    sleep 1
done
echo "::error::smoke timeout after ${TIMEOUT}s, last 30 lines:" >&2
tail -30 "$LOG" >&2
exit 1
```

### 4. preflight 集成（追加 ~10 行）

`scripts/release/preflight.sh` 末尾「Preflight passed.」之前：

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

不强制集成因为：preflight 已含 lint + clean build + assembleRelease 跑得慢；smoke 对设备状态有强依赖；preflight 失败语义是「不可发布」，smoke 失败可能只是设备模型没装好不应混淆语义。

### 5. SKILL.md 更新

[`.claude/skills/openflow-release-skill/SKILL.md`](../../../.claude/skills/openflow-release-skill/SKILL.md) 第 5 步「真机冒烟（**硬要求**）」段：保留手动版作 fallback，加「自动版（推荐）」段，详见上文 Section 4'（含一次性准备 + 每次发布前 + 速度估计 + 内部动作）。

MUST 段从「全链路一次成功」改为「两种走法等效」。

「常见故障表」追加 3 行：`abort_no_model` / `setup 错（缺模型）exit 2` / `service_start_failed`。

## 测试策略

**单元测试** `DebugModelInstallReceiverTest.kt`（Robolectric）：

| 用例 | 期望 |
|---|---|
| 缺 srcPath / modelId / modelId 不在 ModelCatalog | log `debug_model_install_bad_args` |
| srcPath 不存在 / size 0 | log `debug_model_install_src_missing` |
| target 已存在 + size 一致 + force=false | log `debug_model_install_skip`，不 copy |
| target 已存在 + size 一致 + force=true | log `debug_model_install_done`，覆盖 |
| 正常 push + install | log `debug_model_install_done`，target 内容等于 src |

**Permission 验证**（手动，不进 CI）：用别的 keystore 签的 throw-away APK 跑同样 broadcast → 期望 `SecurityException: Permission Denial`。一次性确认后写进 incidents。

**集成验证**（开发完成后一次）：

1. release APK + 真机 + 预装模型 → `bash scripts/release/smoke-test.sh` → exit 0、首次 < 100s、后续 < 30s
2. 删 `/data/local/tmp/ggml-tiny-q5_1.bin` 立即跑 broadcast → 期望 `src_missing`、smoke exit 1
3. 把 `DebugAsrReceiver` 的 `ctx.startService(...)` 注释掉重新装 → 期望 60s timeout exit 1

**回归保护**：单测覆盖 8 用例；`app/src/debug/AndroidManifest.xml` 简化后 DebugAsrReceiverTest 仍能跑；preflight + smoke-test.sh 不进 CI；接口稳定后可平移到 Firebase Test Lab / BrowserStack。

## 速度 / 成本估计

| 阶段 | 时间 |
|---|---|
| install -r APK (8 MB) | ~3s |
| push whisper-tiny (39 MB) + broadcast install | ~5s |
| push qwen3-0.6b (500 MB) + broadcast install | ~60s **首次** |
| 第二次起（同 size 跳过 copy） | ~2s |
| broadcast pipeline + ASR + polish | ~10–15s |
| **首次总计** | ~80s |
| **后续每次** | ~15–20s |

## 已知限制 / 未覆盖

- 不覆盖 UI 全链路（`ACTION_SET_TEXT` 写入第三方 EditText 等纯 UI 路径），手动版仍要保留
- 模型版本升级时需手动重下到 `~/.openflow-smoke-models/`（或加 `--force-reinstall`）
- LLM 默认 ID 在 spec 中写作 `qwen3-0.6b-instruct-q4_k_m`，实施期需 verify 与 `ModelCatalog.llmDefault.id` 是否一致；不一致则更新 spec + smoke-test.sh 默认值
- 接口稳定后可平移 Firebase Test Lab / BrowserStack，本期不做

## 关联

- 历史 commit：`4ff6941 feat(debug): 增加 ASR 与润色自验证链路`（基础设施来源）
- 关联 skill：`.claude/skills/openflow-release-skill/SKILL.md` 第 5 步「真机冒烟」
- 关联 memory：[`MEM-BUILD-0001`](../../dev-harness/memory/pitfalls/MEM-BUILD-0001.md)（hotfix 回流） · [`MEM-BUILD-0003`](../../dev-harness/memory/conventions/MEM-BUILD-0003.md)（ff-merge 回 main）
- 触发场景：v0.1.1 release session 操作者跳过真机冒烟 + 提出「让 Debug 的广播放出来，release 包中带上也无所谓，直接从 adb 触发验证」

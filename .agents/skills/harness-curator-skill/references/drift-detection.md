# Drift Detection

## Rule ↔ Code 对照表（按域）

### native

| Rule | grep 锚点 |
|---|---|
| llama 在 whisper 之前 | `app/src/main/cpp/CMakeLists.txt` |
| 仅 arm64-v8a + x86_64 | `app/build.gradle.kts` `abiFilters` |
| 禁所有 examples/tests | `app/src/main/cpp/CMakeLists.txt` `BUILD_TESTS OFF` 等行 |

### service

| Rule | grep 锚点 |
|---|---|
| FGS type=microphone | `app/src/main/AndroidManifest.xml`、`RecordingForegroundService.startForegroundCompat` |
| 悬浮窗唯一拥有者 | `OverlayController.show` 的调用方限定为 `FlowAccessibilityService` |
| 焦点节点清理 | `FlowAccessibilityService.onAccessibilityEvent` 的 `TYPE_WINDOW_STATE_CHANGED` 分支 |

### pipeline

| Rule | grep 锚点 |
|---|---|
| 3 个 Intent action | `RecordingForegroundService.ACTION_START/COMMIT/CANCEL` 三常量 |
| Engine 串行化 | `WhisperEngine` / `PolishEngine` 的 `Mutex` |
| 软降级 | `transcribe` 内 `if (!modelStore.isReady) return ""` |

### ui

| Rule | grep 锚点 |
|---|---|
| LocalLifecycleOwner 来源 | `import androidx.lifecycle.compose.LocalLifecycleOwner` |
| 三 tab 结构 | `MainActivity.AppRoot` 的 `Tab` enum |

### build

| Rule | grep 锚点 |
|---|---|
| AGP / Gradle 版本 | `gradle/libs.versions.toml`、`gradle/wrapper/gradle-wrapper.properties` |
| compileSdk / minSdk | `app/build.gradle.kts` |

## 漂移信号

- **Silent rename**：某文件 / 类被改名但 rule.md 没跟上 → REPORT 列为 P0
- **Reintroduced anti-pattern**：grep 出 rule 明文禁止的代码模式 → REPORT 列为 P0
- **Dead rule**：rule 引用的文件 / 函数已删除 → REPORT 列为 P1（建议删除 rule）
- **Stale baseline**：AGENTS.md "当前构建基线" 与 `libs.versions.toml` 实际值不符 → REPORT 列为 P1

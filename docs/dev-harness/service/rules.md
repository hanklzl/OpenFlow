# Service / Overlay / FGS Rules

`FlowAccessibilityService` + `OverlayController` + `FloatingBallView` + `RecordingForegroundService` 四件套的强约束。

## MUST

1. **悬浮窗只能由 `FlowAccessibilityService` 触发 show / hide**。Activity / FGS 都不能直接 `WindowManager.addView`。
2. **FGS 启动用 `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MICROPHONE)`**（Android 14+ 强制）。manifest 必须同步声明 `foregroundServiceType="microphone"` 和 `FOREGROUND_SERVICE_MICROPHONE` 权限。
3. **`RecordingForegroundService` 仅接受 3 个 action**：`ACTION_START` / `ACTION_COMMIT` / `ACTION_CANCEL`。
4. **`AccessibilityService` 实例引用通过 `FlowAccessibilityService.instance` 暴露**。在 `onServiceConnected` 中赋值，`onDestroy` 中清空。
5. **`FlowAccessibilityService.lastEditableNode` 必须 `@Volatile`**：会被 FGS 的协程读取。
6. **`onAccessibilityEvent` 中**：
   - `TYPE_VIEW_FOCUSED` / `TYPE_VIEW_TEXT_SELECTION_CHANGED` 且 `source.isEditable` → 更新缓存 + 显示悬浮球
   - `TYPE_WINDOW_STATE_CHANGED` 隐藏悬浮球的判定**必须**同时满足 `pkg == ownPackage` **且** `cls.startsWith("$ownPackage.")`。仅判等包名会把自身 overlay 的 `cls=android.view.View` 状态变化误判成"用户切回了我方 Activity"，让刚显示的悬浮球立刻消失（见 [INC-SERVICE-0001](../incidents/INC-SERVICE-0001.md)）。判定必须封装在 `OverlayHideDecision.shouldHideOverlayForOwnWindowStateChange` 中，且对应 JVM 单测必须保持运行。
7. **悬浮窗用 `TYPE_APPLICATION_OVERLAY`**（Android 8+），不需要 `TYPE_PHONE` fallback（minSdk=31）。
8. **悬浮球长按延迟 `armDelayMs ≥ 200ms`**（当前 250ms），防误触。
9. **`TextInserter.insertAtCursor` 用 `ACTION_SET_TEXT` 整体替换 + `ACTION_SET_SELECTION` 调光标**。
10. **AccessibilityService 配置 `canRetrieveWindowContent="true"`**（`res/xml/accessibility_service_config.xml`）。

## MUST NOT

1. **禁止用 `ACTION_PASTE` + 剪贴板写入**：不可靠 + 污染用户剪贴板。
2. **禁止在 `onAccessibilityEvent` 里做长任务 / IO**：同步回调，会卡 a11y 管线。
3. **禁止用 `dataSync` / `connectedDevice` 等其他 FGS 类型**：与录音用途不匹配，审核会失败。
4. **禁止把 `AccessibilityNodeInfo` 通过 Parcelable 跨进程传**：依赖 a11y 系统进程间 binder，过 Parcel 后失效。
5. **禁止在 `FlowAccessibilityService` 中持有 Activity / Composable 引用**。
6. **禁止把 PCM 缓冲写到磁盘**：录音是用户隐私，不长存。

## SHOULD

1. 通知 channel 用 `IMPORTANCE_LOW` + `setSilent(true)` + `setOngoing(true)`，不打扰用户。
2. 悬浮球默认贴右边，可拖拽；松手用 `OverlayController.snapToEdge()` 自动贴边。
3. 上滑阈值用 `ViewConfiguration.get(context).scaledTouchSlop * 6` 倍而非硬编码像素。

## 相关 incidents

- [INC-SERVICE-0001](../incidents/INC-SERVICE-0001.md) — 悬浮球被自身 WINDOW_STATE_CHANGED 立刻隐藏

## 相关代码

- `app/src/main/java/com/hank/flow/open/service/FlowAccessibilityService.kt`
- `app/src/main/java/com/hank/flow/open/service/OverlayController.kt`
- `app/src/main/java/com/hank/flow/open/service/FloatingBallView.kt`
- `app/src/main/java/com/hank/flow/open/service/RecordingForegroundService.kt`
- `app/src/main/java/com/hank/flow/open/insertion/TextInserter.kt`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/xml/accessibility_service_config.xml`

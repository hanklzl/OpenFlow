---
name: android-overlay-service
description: >
  Use when adding, modifying, or debugging OpenFlow's floating-ball
  overlay (WindowManager), AccessibilityService event handling,
  or microphone foreground service lifecycle.
  Trigger phrases: "悬浮球", "无障碍服务", "FGS", "overlay 不显示",
  "AccessibilityService", "FOREGROUND_SERVICE_TYPE_MICROPHONE",
  "悬浮窗权限". Read this BEFORE touching any of
  app/src/main/java/com/hank/flow/open/service/**.
---

# Overlay + Accessibility + FGS 约束

OpenFlow 三个相互依赖的 Service 组件：
- `FlowAccessibilityService`：监听焦点 → 显隐悬浮球，缓存焦点节点供写入
- `OverlayController`：通过 `WindowManager` 添加/移除 `FloatingBallView`
- `RecordingForegroundService`：录音 + ASR + polish + 写入

本 skill 列出**容易踩坑**的不变约束。

## 必读 gate

- [`../../../AGENTS.md`](../../../AGENTS.md)
- [`../../../docs/dev-harness/service/rules.md`](../../../docs/dev-harness/service/rules.md)

## 核心不变约束

### MUST: AccessibilityService 是悬浮窗的唯一拥有者

只有 `FlowAccessibilityService.onAccessibilityEvent` 才能调 `OverlayController.show()`。
**禁止**让 Activity / FGS 直接 `WindowManager.addView`：
- Activity 调用时悬浮球会随 Activity 销毁；
- FGS 调用时锁屏 / 返回桌面后悬浮球行为不可预测。

### MUST: 焦点节点缓存的生命周期

```kotlin
TYPE_VIEW_FOCUSED / TYPE_VIEW_TEXT_SELECTION_CHANGED + isEditable → 更新 lastEditableNode + 显示悬浮球
TYPE_WINDOW_STATE_CHANGED + packageName == 自己 → 隐藏悬浮球 (跳到我们 App 时不需要)
```

`lastEditableNode` 必须是 `@Volatile`，因为 FGS 的协程会读它。

### MUST NOT: 在事件回调里做重操作

`onAccessibilityEvent` 是同步回调；阻塞 IO / 长循环会拖垮系统无障碍管线。
具体长操作放到 `Dispatchers.Default` 或干脆委托给 FGS。

### MUST: AccessibilityService 配置文件

`res/xml/accessibility_service_config.xml` 必须声明 `canRetrieveWindowContent="true"`，否则拿不到 source node。
事件类型至少包含 `typeViewFocused | typeViewTextSelectionChanged | typeWindowStateChanged`。

### MUST: FGS startForeground 类型匹配

Android 14+（API 34+）强制 FGS 类型 与 manifest `foregroundServiceType` 匹配。OpenFlow 录音用 microphone：

```kotlin
ServiceCompat.startForeground(
    this, NOTIF_ID, notification,
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
)
```

manifest：
```xml
<service android:foregroundServiceType="microphone" .../>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
```

**禁止**用 `dataSync` / `connectedDevice` 等其他类型——审核会失败 / 系统会立即 crash。

### MUST: 启动 FGS 前 RECORD_AUDIO 必须已授予

Android 14+：后台无 RECORD_AUDIO 时启动 microphone FGS 会抛 `SecurityException`。
`FlowAccessibilityService` 触发 FGS 前可以信任已授权（用户没授权时根本进不到悬浮球阶段——主页 4 步引导）。

### MUST: 悬浮窗类型用 TYPE_APPLICATION_OVERLAY

Android 8+：
```kotlin
WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
```
旧 `TYPE_PHONE` / `TYPE_SYSTEM_ALERT` 在 minSdk=31 下永远走不到，**禁止**保留 fallback 代码引入复杂度。

### MUST: 长按延时 ≥ 200ms

`FloatingBallView.armDelayMs = 250L`（毫秒）。短于 200ms 严重误触：用户拖动悬浮球时会被误判为录音开始。

### MUST NOT: AccessibilityService 持有 Activity / Composable 引用

`FlowAccessibilityService.instance` 是 service 实例的弱引用语义；**禁止**从这个 instance 反过来导航到 UI / 弹 Toast。
UI 反馈应该通过 FGS 的通知或 DataStore Flow。

### MUST: TextInserter 优先 ACTION_SET_TEXT

```kotlin
node.performAction(ACTION_SET_TEXT, bundle with new full text)
```
**禁止**依赖 `ACTION_PASTE` + 剪贴板：1) 部分系统 / 输入法过滤剪贴板； 2) 污染用户剪贴板。

### MUST: 写入后更新选区光标

```kotlin
node.performAction(ACTION_SET_SELECTION, bundle with start=end=cursorAfterInsert)
```
否则下次插入位置不对，且部分输入法会把光标重置到行首。

## 调试技巧

- `adb logcat -s FlowA11y FlowFGS OverlayController` 看三个服务的协作日志
- 悬浮球不出现：先 `adb shell settings get secure enabled_accessibility_services`，确认 `com.hank.flow.open/.service.FlowAccessibilityService` 在列表里
- 文本写不进去：先看 `node?.isEditable` 是不是 true；某些自绘 EditText（如游戏内）不暴露 a11y 节点

## 模拟器局限

- AVD 没有麦克风时 `AudioRecord` 状态会是 `STATE_UNINITIALIZED`；OpenFlow 已在 `AudioRecorder.start` 里检查并 return；不会 crash 但也不会有任何输出
- Overlay 在 Emulator 上工作正常；但 Pixel API 35 emulator 偶尔不触发 `TYPE_VIEW_FOCUSED`，建议真机验证

## 参考

- `app/src/main/java/com/hank/flow/open/service/FlowAccessibilityService.kt`
- `app/src/main/java/com/hank/flow/open/service/OverlayController.kt`
- `app/src/main/java/com/hank/flow/open/service/FloatingBallView.kt`
- `app/src/main/java/com/hank/flow/open/service/RecordingForegroundService.kt`
- `app/src/main/java/com/hank/flow/open/insertion/TextInserter.kt`
- `app/src/main/res/xml/accessibility_service_config.xml`
- `app/src/main/AndroidManifest.xml`

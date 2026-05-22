# INC-SERVICE-0001 — 悬浮球被自身 WINDOW_STATE_CHANGED 立即隐藏

- **状态**：resolved
- **首次发生**：2026-05-22
- **关联 commit**：`debug/pipeline-trace`（修复分支）
- **关联 rule**：[`../service/rules.md#must-window_state_changed-的隐藏判定`](../service/rules.md)

## 现象

模拟器（API 37，x86_64）下：用户在外部 App（Settings 搜索栏）点入 `android.widget.EditText`，悬浮球出现约 300-400ms 后立刻消失；用户来不及长按。logcat 关键证据：

```
22:22:31.822 OpenFlow/A11Y a11y_event type=VIEW_FOCUSED pkg=com.google.android.settings.intelligence cls=android.widget.EditText
22:22:31.847 OpenFlow/A11Y overlay_show_decided isEditable=true windowId=215
22:22:31.847 OpenFlow/OVERLAY overlay_show_call alreadyShown=false
22:22:31.905 OpenFlow/OVERLAY overlay_show_applied
22:22:32.267 OpenFlow/A11Y a11y_event type=WINDOW_STATE_CHANGED pkg=com.hank.flow.open cls=android.view.View
22:22:32.268 OpenFlow/A11Y overlay_hide_decided pkg=com.hank.flow.open matchesSelf=true
22:22:32.268 OpenFlow/OVERLAY overlay_hide_call hadView=true
```

第二条 `WINDOW_STATE_CHANGED` 的 `cls=android.view.View` 来自我们自己刚 `addView` 上去的悬浮窗，并不是用户回到了 OpenFlow 主界面。

## 根因

`FlowAccessibilityService.onAccessibilityEvent`（修复前）对 `TYPE_WINDOW_STATE_CHANGED` 的处理：

```kotlin
val matchesSelf = pkg == packageName
if (matchesSelf) {
    overlay.hide()
    lastEditableNode = null
}
```

`TYPE_APPLICATION_OVERLAY` 类型的窗口（通过 `WindowManager.addView` 加入）属于本应用进程，系统会以 `pkg=ownPackage, cls=android.view.View` 发出 `WINDOW_STATE_CHANGED`。仅用包名判等，会把"我们自己刚显示的悬浮球"误判成"用户切回了 OpenFlow Activity"，立刻 `hide()` 并清空 `lastEditableNode`，造成全链路从入口就被切断。

## 修复

新增纯函数 `service/OverlayHideDecision.kt`：

```kotlin
internal fun shouldHideOverlayForOwnWindowStateChange(
    eventPackage: String?, eventClass: String?, ownPackage: String,
): Boolean {
    if (eventPackage != ownPackage) return false
    val cls = eventClass ?: return false
    return cls.startsWith("$ownPackage.")
}
```

Activity 类全名以本包名前缀开头（如 `com.hank.flow.open.MainActivity`），悬浮窗的 `android.view.View` 不会命中，因此真实 Activity 进入前台才会隐藏。

`FlowAccessibilityService.onAccessibilityEvent` 改为调用上述纯函数，并把 `cls` 一并写入 `overlay_hide_decided` 事件，便于日后回溯。

## Guard

1. JVM 单测：`OverlayHideDecisionTest`（六个用例）覆盖
   - 真实 Activity → 应隐藏
   - **`cls=android.view.View` → 不应隐藏（本 incident 核心场景）**
   - 其它包 → 不应隐藏
   - `pkg` / `cls` 为 null → 不应隐藏（防御）
   - 包前缀匹配的其它 Activity → 应隐藏
2. `service/rules.md` MUST §6 已收紧描述："`pkg == ownPackage` 不足以判定，必须叠加 `cls.startsWith("$ownPackage.")` 排除自身 overlay 的 WINDOW_STATE_CHANGED"。

## 备注

调试过程中观察到的次生现象：现代 Compose-based App（Messages / Contacts）几乎不发 `TYPE_VIEW_FOCUSED`，悬浮球依赖该事件触发时在这些 App 上根本不会出现。该问题作为 follow-up（INC-PIPELINE-FUTURE）跟踪。

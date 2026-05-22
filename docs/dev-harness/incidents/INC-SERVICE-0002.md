# INC-SERVICE-0002 — TextInserter 把 EditText 的 hint 当作已有文本拼接

- **状态**：resolved
- **首次发生**：2026-05-22
- **关联 commit**：`debug/pipeline-trace`
- **关联 rule**：[`../pipeline/rules.md#text-insertion`](../pipeline/rules.md)

## 现象

模拟器上跑通"长按 → 录音 → ASR → 润色 → 写入"全链路，写入目标 = 系统 Settings 搜索栏（空 EditText，`hint="Search settings"`）。pipeline 完整运行：

```
22:41:54.700 OpenFlow/ASR  asr_done textLen=13 text="[BLANK_AUDIO]"
22:41:54.722 OpenFlow/INSERT inserter_call textLen=13 currentLen=15 selStart=-1 selEnd=-1 coercedStart=15 coercedEnd=15
22:41:54.768 OpenFlow/INSERT inserter_set_text ok=true
22:41:54.833 OpenFlow/INSERT insert_done ok=true textLen=13
```

但是 uiautomator dump 显示，写入完成后 EditText 的实际内容是：

```
text="Search settings[BLANK_AUDIO]"
hint="Search settings"
```

期望是 `"[BLANK_AUDIO]"`，实际是把 hint `"Search settings"` 当作已有内容拼接在前。

## 根因

`AccessibilityNodeInfo.getText()` 在 EditText 处于"显示 hint placeholder"状态时会返回 hint 字符串而不是空串。修复前的 `TextInserter`：

```kotlin
val current = node.text?.toString().orEmpty()   // 拿到的是 hint "Search settings"
val start = if (rawStart < 0) current.length    // start = 15
val newText = current.substring(0, start) + text + current.substring(end)
//          = "Search settings" + "[BLANK_AUDIO]" + ""
```

没有用 `AccessibilityNodeInfo.isShowingHintText`（API 26+）区分 hint 与真实内容，所以 hint 被当成"已有文本"，新内容追加在它后面，把 hint 字面变成了真实内容。

## 修复

抽出纯 Kotlin 结构 `insertion/TextInsertionPlan.kt`：

```kotlin
data class TextInsertionState(
    val currentText: String,
    val isShowingHint: Boolean,
    val rawSelectionStart: Int,
    val rawSelectionEnd: Int,
) {
    val effectiveCurrent: String
        get() = if (isShowingHint) "" else currentText
}
```

`TextInserter.insertAtCursor` 读取 `node.isShowingHintText` 一并塞进 `TextInsertionState`，splice 路径基于 `effectiveCurrent`，不再触碰 hint。同时把 `isShowingHint` / `effectiveLen` 一起写入 `inserter_call` 事件，便于以后回溯。

## Guard

1. JVM 单测：`TextInsertionPlanTest`（八个用例），其中 `treats_field_as_empty_when_node_is_showing_hint_text` 就是本 incident 的最小复现。
2. `pipeline/rules.md` MUST 新增条款："`TextInserter` 必须读取 `AccessibilityNodeInfo.isShowingHintText` 并将其为 true 时的 `text` 视为空内容；splice 必须基于该归一化后的值"。
3. `inserter_call` Logan 事件包含 `isShowingHint` / `effectiveLen` 字段，回归出现时能在 logcat 里直接看到 hint 是否被误读。

## 备注

- `AccessibilityNodeInfo.ACTION_SET_SELECTION` 后续在某些目标 App 上仍可能返回 `ok=false`（本次日志中 `inserter_set_selection ok=false`），但只影响光标位置而不影响 `ACTION_SET_TEXT` 已写入的内容，作为已知次要现象不开新 incident。
- `[BLANK_AUDIO]` 是 whisper.cpp 对模拟器静音输入的常见输出，可作为模拟器全链路验收的"基线占位文本"。

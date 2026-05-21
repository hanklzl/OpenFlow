# UI Rules

Compose / Activity / 主题 / 导航的强约束。AI 助手在动手前必须读完本文件。

## MUST

1. **三 tab 结构是固定的**：主页（HomeScreen）/ 模型（ModelDownloadScreen）/ 设置（SettingsScreen）。新增功能优先考虑挂到现有 tab 里，不要轻易加新 tab。
2. **`LocalLifecycleOwner` 必须从 `androidx.lifecycle.compose` import**。`androidx.compose.ui.platform.LocalLifecycleOwner` 在新 Compose 中已 deprecated，且行为不一致。
3. **权限状态刷新走 `Lifecycle.Event.ON_RESUME`**：用户从设置页跳回时必须重新查询，不能用 `LaunchedEffect(Unit)` 单次。
4. **长列表用 `LazyColumn`**：超过 ~10 个 Card 时必须切；少于 10 个可以放在 `Column { verticalScroll() }`。
5. **跨 Activity 重建必须存活的状态用 DataStore (SettingsStore)**；瞬态用 `remember` / `rememberSaveable`。
6. **新增 string 走 `res/values/strings.xml`**：UI 文本默认中文，必要时再加 `values-en/`。
7. **`MainActivity` 用 `enableEdgeToEdge()` + `Scaffold`**：保证 status bar / nav bar 沉浸式行为一致。
8. **主题色用 `MaterialTheme.colorScheme`**：不要直接写 `Color.parseColor("#...")`，悬浮球 `FloatingBallView`（自绘 Canvas）除外。

## MUST NOT

1. **禁止在 Compose 中直接 `Settings.canDrawOverlays(context)` 等系统 API 阻塞调用**：用 `PermissionChecks` 封装层。
2. **禁止用 `runBlocking { settings.current() }` 在 Composable 里**：用 `collectAsState(initial = ...)`。
3. **禁止把多个全屏页面塞在一个 Activity 内**：未来如需要新页面用 Navigation Compose（暂未引入，待需要时一次性加）。
4. **禁止在 Compose 中直接构造 Service Intent 启动 RecordingForegroundService**：这是 `FlowAccessibilityService` 的职责。

## SHOULD

1. UI 错误状态优先用文本提示（红字 + 重试按钮），不用全屏 dialog。
2. 设置开关用 Material3 `Switch`；模型下载用 `LinearProgressIndicator(progress = { ... })` 配 `LinearProgressIndicator` 新签名。
3. Compose 预览用 `@Preview(showBackground = true)`，避免裸 `@Preview` 在亮色主题下看不清。

## 相关 incidents

- (暂无；首次违反时补 incident)

## 相关代码

- `app/src/main/java/com/hank/flow/open/MainActivity.kt`
- `app/src/main/java/com/hank/flow/open/ui/home/HomeScreen.kt`
- `app/src/main/java/com/hank/flow/open/ui/modeldownload/ModelDownloadScreen.kt`
- `app/src/main/java/com/hank/flow/open/ui/settings/SettingsScreen.kt`
- `app/src/main/java/com/hank/flow/open/ui/theme/`

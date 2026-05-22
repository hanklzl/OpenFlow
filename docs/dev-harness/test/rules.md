# Test Rules

OpenFlow 单测 / 仪器测试的强约束。

## MUST

1. **功能迭代必须同步新增或更新对应 JVM 单元测试，并采用 TDD**：功能迭代包括新增功能、行为修改、bugfix、异常 / 降级处理变更。必须先写并运行会失败的测试，再实现最小代码使其通过。纯文档、格式化、注释、无行为变化的构建脚本调整可不新增业务单测，但仍必须通过现有 unit test gate。
2. **功能单测必须覆盖正常路径与异常 / 降级路径**：涉及设置、模型、ASR、polish、插入、权限、下载、Service 协作等行为时，至少覆盖主要成功路径和可预期失败 / 未就绪 / fallback 路径。
3. **JVM 单测必须快速、确定、无真实外部依赖**：禁止真实网络、真实 JNI `.so`、真实 `AudioRecord`、无限等待；使用 fake / mock / test dispatcher 保持可重复。
4. **定期 review 测试必要性**：删除或重写重复、脆弱、低价值、高耗时测试，避免测试套件膨胀后反向阻碍迭代。
5. **功能收尾必须通过 `./gradlew :app:testDebugUnitTest`**；优先运行 `bash scripts/dev-harness/check.sh` 覆盖 symlink、grep guard、test source compile 与 JVM unit test gate。
6. **ViewModel / Coroutine 单测用 `runTest(mainDispatcherRule.dispatcher) { advanceUntilIdle() }`**。
7. **DataStore 测试用 `UUID.randomUUID()` 后缀的 prefs 文件名**：避免 "multiple DataStores active" 异常。OpenFlow 主代码用 `openflow_settings`，测试代码用别的 key。
8. **JNI 单测用 mock**：`WhisperJni` / `LlamaJni` 静态方法在 JVM 单测中不可用，必须 mock。
9. **`AudioRecord` / Service 仪器测试**：放 `androidTest/`，不要塞进 JVM 单测。
10. **`@Ignore` 必须登记到 `docs/dev-harness/incidents/`**，并注明"什么时候可以解禁"。
11. **`gradle.properties` `-Xmx ≥ 4096m`**：whisper / llama 编译期内存压力大。

## MUST NOT

1. **禁止 `runBlocking { flow.first { predicate } }`**：collector 不发新值时 hang。
2. **禁止单测里加载 `libopenflow_jni.so`**：JVM 单测环境没 NDK runtime，`UnsatisfiedLinkError`。
3. **禁止单测里渲染 Compose 内容**：OpenFlow 暂未引入 Robolectric/Paparazzi；Compose 行为测试只在仪器测试跑。
4. **禁止悄悄 `@Ignore("flaky")`**：随时间累积成"测试坟场"。

## SHOULD

1. 用 `withTimeoutOrNull(5_000)` 包裹仪器测试的等待逻辑，避免无限挂。
2. 集成 / E2E 测试覆盖最少 1 个完整 pipeline：长按 → 假 PCM → 假转写 → 写入到 fake EditText 节点。

## 命令

```bash
./gradlew :app:testDebugUnitTest                              # JVM 单测
./gradlew :app:testDebugUnitTest --rerun-tasks                # 强制重跑
./gradlew :app:testDebugUnitTest --tests "<FQN>"              # 单类
./gradlew :app:connectedDebugAndroidTest                      # 仪器测试
./gradlew :app:compileDebugUnitTestKotlin                     # 仅编译测试源码（catch 测试 fixture 漂移）
```

## 相关 incidents

- (暂无)

## 相关代码

- `app/src/test/`（JVM 单测）
- `app/src/androidTest/`（仪器测试）
- `app/build.gradle.kts`（测试依赖）
- `gradle.properties`（JVM 内存）

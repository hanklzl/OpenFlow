---
name: test-stability
description: >
  Use whenever the task touches *Test.kt files, app/build.gradle.kts
  test wiring, gradle.properties JVM args, MainDispatcherRule, or any
  test stability concern (coroutine hangs, DataStore conflicts,
  Robolectric/Compose test setup, JNI in tests).
  Trigger phrases: "单测 hang", "runBlocking", "runTest", "advanceUntilIdle",
  "Robolectric", "DataStore multiple active", "@Ignore",
  "connectedAndroidTest", "JNI 测试".
---

# Test Stability Skill

OpenFlow 单测 / 集成测试 / 真机测试的稳定性约束。

## 必读 gate

- [`../../../AGENTS.md`](../../../AGENTS.md)
- [`../../../docs/dev-harness/test/rules.md`](../../../docs/dev-harness/test/rules.md)
- [`../../../docs/dev-harness/incidents/index.md`](../../../docs/dev-harness/incidents/index.md)

## 不变约束

### MUST: ViewModel / Coroutine 单测用 runTest

```kotlin
@get:Rule val mainDispatcherRule = MainDispatcherRule()

@Test
fun something() = runTest(mainDispatcherRule.dispatcher) {
    // ...
    advanceUntilIdle()
    // assertions
}
```

**禁止** `runBlocking { flow.first { predicate } }`——会在 collector 不发新值时 hang。

### MUST: DataStore 测试用唯一文件名

每个 test class 用 `UUID.randomUUID()` 后缀的 prefs 文件：

```kotlin
private val testPrefsName = "openflow_test_${UUID.randomUUID()}"
```

`@After` 取消 scope，避免 "There are multiple DataStores active for the same file" 异常。
OpenFlow 主代码用 `openflow_settings`，测试代码必须用别的 key。

### MUST: JNI 单测不走真 .so

`WhisperEngine` / `PolishEngine` 单测必须 mock `WhisperJni` / `LlamaJni` 静态方法（用 MockK 等）。
**禁止**让单测加载 `libopenflow_jni.so`——JVM 单测环境没有 Android NDK runtime，会 `UnsatisfiedLinkError`。

`WhisperJni.loaded` flag 已经设计为 false-safe：模型未加载时直接走软降级路径，便于测试。

### MUST: instrumentation 测试 bounded await

不要 `while (!flag) { sleep(10) }`；用 `withTimeoutOrNull(5_000) { flag.filterTrue().first() }`，失败时直接报错。

### MUST: @Ignore 必须登记

新增 `@Ignore` 必须：
1. 在注解里写明原因
2. 在 `docs/dev-harness/incidents/` 加对应 incident
3. 在 incident 里写明"什么时候可以解禁"

**禁止**悄悄 `@Ignore("flaky")` 然后让测试腐烂。

### MUST: gradle.properties JVM 内存

`-Xmx4096m` 起步。OpenFlow 集成的 whisper.cpp + llama.cpp 即使在编译期也会增加 Gradle 内存压力。
当前 `gradle.properties`：`org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8 -XX:+UseParallelGC`。

### MUST NOT: 单测里跑 AudioRecord

`AudioRecord` 依赖系统服务，JVM 单测拿不到。Audio 相关测试必须用 instrumentation (`androidTest/`)，并在 setup 里检查麦克风权限。

### MUST NOT: 单测里渲染 Compose 内容

OpenFlow 暂未引入 Robolectric / Paparazzi。Compose 行为测试只在真机/模拟器（`connectedAndroidTest`）跑。

## 命令

```bash
./gradlew :app:testDebugUnitTest                # JVM 单测
./gradlew :app:testDebugUnitTest --rerun-tasks  # 强制重跑（缓存怀疑）
./gradlew :app:connectedDebugAndroidTest        # 真机/模拟器仪器测试

# 单独跑一个 class
./gradlew :app:testDebugUnitTest --tests "com.hank.flow.open.asr.WhisperEngineTest"
```

## 参考

- 当前测试目录布局：`app/src/test/`（JVM）+ `app/src/androidTest/`（仪器）
- 测试基线 deps（已在 build.gradle.kts）：JUnit4 + Kotlin coroutines test（按需添加）

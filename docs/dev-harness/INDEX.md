# Dev Harness — INDEX

OpenFlow 的开发守门总入口。任何改动落到下面任一域时，**动手前**必须先读对应的 `rules.md`。

## 域

| 域 | 适用范围 | Rules |
|---|---|---|
| **UI** | Compose Screen / Activity / 主题 / 导航 | [ui/rules.md](ui/rules.md) |
| **Service** | AccessibilityService / OverlayController / RecordingForegroundService / FloatingBallView | [service/rules.md](service/rules.md) |
| **Native** | CMakeLists / cpp / JNI / submodule (whisper.cpp, llama.cpp) | [native/rules.md](native/rules.md) |
| **Pipeline** | 录音 → ASR → polish → 写入全链路；engine wrapper；settings 影响面 | [pipeline/rules.md](pipeline/rules.md) |
| **Test** | 单测 / 仪器测试 / build.gradle 测试配置 | [test/rules.md](test/rules.md) |

## Incidents

历史踩坑分类归档：[incidents/index.md](incidents/index.md)

按 ID 反查到 area + rule + guard。

## AI Workflow

技能 / 工作流：`.agents/skills/<area>-skill/`，软链到 `.claude/skills/`、`.codex/skills/`。
跨工具入口：

- whisper.cpp：[`.agents/skills/whisper-cpp-skill/SKILL.md`](../../.agents/skills/whisper-cpp-skill/SKILL.md)
- llama.cpp：[`.agents/skills/llama-cpp-skill/SKILL.md`](../../.agents/skills/llama-cpp-skill/SKILL.md)
- 悬浮窗 / 无障碍 / FGS：[`.agents/skills/android-overlay-service-skill/SKILL.md`](../../.agents/skills/android-overlay-service-skill/SKILL.md)
- 原生构建 / submodule：[`.agents/skills/native-build-skill/SKILL.md`](../../.agents/skills/native-build-skill/SKILL.md)
- 全链路 pipeline：[`.agents/skills/accessibility-pipeline-skill/SKILL.md`](../../.agents/skills/accessibility-pipeline-skill/SKILL.md)
- Compose 参考库：[`.agents/skills/jetpack-compose-expert-skill/SKILL.md`](../../.agents/skills/jetpack-compose-expert-skill/SKILL.md)
- 测试稳定性：[`.agents/skills/test-stability-skill/SKILL.md`](../../.agents/skills/test-stability-skill/SKILL.md)
- Harness 维护：[`.agents/skills/harness-curator-skill/SKILL.md`](../../.agents/skills/harness-curator-skill/SKILL.md)

## 自查

```bash
bash scripts/dev-harness/check.sh
```

跑：
1. symlinks 校验（`.agents/skills/*` 与 `.claude/skills/`、`.codex/skills/` 软链同步）
2. grep guards（rules.md 明文禁止的代码模式）
3. Kotlin 编译 + 单测（`./gradlew :app:compileDebugKotlin :app:testDebugUnitTest`，可 `--skip-contract-tests` 跳过）

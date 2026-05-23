---
id: MEM-BUILD-0005
created: 2026-05-23
updated: 2026-05-23
source: feat/release-smoke-test@ca8c578
confidence: medium
status: draft
verified-at: 2026-05-23
---

# Release smoke test 用 ModelCatalog 最轻档（whisper-tiny + qwen3-0.6b），不跟随 product default

## 现象 / 事实

`scripts/release/smoke-test.sh` 默认值（ca8c578 引入）：

```bash
WHISPER="ggml-tiny-q5_1"        # ModelCatalog.whisperTiny (32 MB)
LLM="qwen3-0.6b-q4_k_m"         # ModelCatalog.llmTinyNewer (~400 MB)
```

**故意不跟随** `ModelCatalog.whisperDefault` (`ggml-small-q5_1`, 190 MB) / `llmDefault` (`qwen2.5-1.5b-instruct-q4_k_m`, 1.1 GB)。

## 影响 / 为什么记

- R1 命中（v0.1.1 操作者明确指令：「smoke test，用最轻量的模型即可」）。
- smoke 验证目标是 R8 混淆通过 + JNI 解析正确 + AccessibilityService 路径打通 + WhisperEngine/PolishEngine 加载链路，**不是模型质量**。最轻档跑通就证明 release-only 风险点没炸。
- 模型 size 决定 smoke 首次跑时长：tiny + qwen3-0.6b（首次 ~50–70s）vs small + qwen2.5-1.5b（首次 ~110–140s）。差 ≈ 1 倍。
- 跟随 default 会带来奇怪耦合：每次 product 调默认模型 smoke 速度跟着变；想升 default 到更强模型时 smoke 时间被迫翻倍。

## 如何应用 / 验证

- smoke 默认值的两处定义（必须保持一致）：
  - `scripts/release/smoke-test.sh` `WHISPER` / `WHISPER_FILE` / `LLM` / `LLM_FILE` 顶部 env defaults
  - `.claude/skills/openflow-release-skill/SKILL.md` 第 5 步「自动版（推荐）」段的 `curl -L -o ~/.openflow-smoke-models/...` 模板
- 升级最轻档模型（如 catalog 加入 `whisperBase`、`llmSubMini` 等更小档）时同步改这两处。
- 不要把 smoke 默认值改成 `ModelCatalog.whisperDefault.id` / `llmDefault.id` 的字符串拼接——会让 smoke 与 product default 错误耦合。

## 关联

- 相关代码：`scripts/release/smoke-test.sh:9-14`（默认值定义）、`.claude/skills/openflow-release-skill/SKILL.md` 第 5 步
- 历史 spec：`docs/superpowers/specs/2026-05-23-release-smoke-test-automation-design.md`、`docs/superpowers/plans/2026-05-23-release-smoke-test-automation.md` Section 0
- 相关 catalog：`app/src/main/java/com/hank/flow/open/model/ModelCatalog.kt` (`whisperTiny`, `llmTinyNewer`)
- 关联条目：[[MEM-BUILD-0001]]、[[MEM-BUILD-0003]]、[[MEM-BUILD-0004]]（同一 release flow 系列）

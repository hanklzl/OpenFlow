# 写入前去重流程

每个候选条目在写入前**必须**走完这套去重检查，避免 `memory/` 长出近似条目。

## 1. 提取关键短语

从候选条目正文中提取 2-3 个最具区分度的关键短语（命令、类名、报错原文、API 名等）。例如：

- 候选："改 CMakeLists.txt 顺序后 ggml 重定义" → 关键短语：`ggml`、`add_subdirectory`、`whisper.cpp`
- 候选："LocalLifecycleOwner 包名走错" → 关键短语：`LocalLifecycleOwner`、`androidx.compose.ui.platform`

## 2. 逐层 grep

按以下顺序检索，**任一层命中**就触发对应处理：

```bash
# 1. 暂存层 memory/
grep -rn "<关键短语>" docs/dev-harness/memory/

# 2. 主规范层
grep -rn "<关键短语>" docs/dev-harness/<area>/rules.md
grep -rn "<关键短语>" docs/dev-harness/incidents/

# 3. AGENTS.md
grep -n "<关键短语>" AGENTS.md
```

## 3. 命中后的处理

| 命中位置 | 处理 |
|---|---|
| `memory/<subdir>/MEM-XXX.md` 存在等价条目 | **不新建**，把现有条目的 `updated:` 和 `verified-at:` 改成今天，commit message 用 `docs(memory): refresh ...` |
| `memory/<subdir>/MEM-XXX.md` 存在相关但不等价条目 | **新建**，但在正文 `## 关联` 段 link 到既有 entry |
| `<area>/rules.md` 已写入对应规则 | **不新建**，本次发现说明 rules.md 已经覆盖；可选：若发现 rules.md 描述与代码事实有偏差，改走 R2 写到 `architecture/` |
| `incidents/index.md` 有等价 incident | **不新建**，本次发现说明 incident 已存在；可选：若发现 guard 失效，改走 R3 写到 `pitfalls/` |
| `AGENTS.md` 已涵盖 | **不新建** |
| 全部未命中 | **新建条目**，按 [SKILL.md](../SKILL.md) Workflow checklist step 5 走 |

## 4. 软重复处理

如果两个关键短语都未 grep 命中，但**正文语义**与某条已存在条目高度重合（agent 自行判断），按以下规则：

- 若已存在条目 `status: stable` 或 `promoted` → 不新建，把发现作为该条目的 `## 关联` 补充。
- 若已存在条目 `status: draft` → 合并：把新内容追加到旧条目正文，`updated:` 改今天，`confidence:` 取两者中更高的。

## 5. 跨域去重

新条目可能跨域（e.g., 既是 NATIVE 又是 BUILD）。处理：

- 选**最主要受影响的域**作为 `<AREA>`。
- 在正文 `## 关联` 中列出次要域 + 相关 rules / incidents。
- 不要把同一条目复制到两个域下——一份就够。

## 6. 元信息更新（不新建条目时）

只更新 `verified-at:` 而不新建条目的情况，commit message：

```
docs(memory): refresh MEM-NATIVE-0003 verified-at
```

只更新 `updated:` 和正文（合并）的情况：

```
docs(memory): merge new finding into MEM-NATIVE-0003
```

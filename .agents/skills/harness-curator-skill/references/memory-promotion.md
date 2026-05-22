# Memory Promotion

把 `docs/dev-harness/memory/` 中的高置信条目晋升到 `dev-harness/<area>/rules.md` 或 `dev-harness/incidents/index.md`。

**升级版本**：curator 现在**自主合入**（不再产 patch 等人审）。失控保护通过细粒度 commit + 上限 + git revert 实现。

## 1. 晋升前置（must）

entry 必须**同时**满足：

1. `confidence: high`（首写为 medium；curator 经 ≥ 1 次成功 `verified-at` 刷新且未冲突 → 升为 high，见 [memory-compaction.md](memory-compaction.md) §5）。
2. `status: stable`（draft 不能晋升）。
3. 至少 ≥ 2 次 curate 周期通过 drift 核验（即 `verified-at:` 至少更新过 1 次，且本次扫描复核成功）。
4. 与现有 `<area>/rules.md` 与 `incidents/index.md` 无内容冲突（grep 关键短语，确认目的地没有反向描述）。
5. 类型对应：
   - `architecture/` / `conventions/` 条目 → `dev-harness/<area>/rules.md` 增条
   - `pitfalls/` 条目 → `dev-harness/incidents/index.md` 增 `INC-<AREA>-<NNNN>`
   - `candidates/` 永远**不能直接晋升**：先经 compaction 归类到 `<subdir>/` 才能进入晋升流程

## 2. 晋升流程

### 2.1 conventions / architecture → rules.md

步骤：

1. 选 `<AREA>`（与 entry 文件路径一致：UI / SERVICE / NATIVE / PIPELINE / TEST / BUILD / MODEL）。
2. 打开 `docs/dev-harness/<area>/rules.md`，按已有结构选合适小节，新增一条 MUST / MUST NOT / SHOULD 规则。
3. 规则文本要求：
   - 一句话 + 必要 grep 锚点。
   - 不直接复制 memory entry 原文——提炼成"规则该怎么说"。
   - 末尾用 markdown 注释引用源 entry：`<!-- promoted from MEM-NATIVE-0003 @ 2026-05-22 -->`
4. commit A：`docs(harness): promote rule from MEM-XXX <一句话> -> <area>/rules.md`，body 引用源 entry 路径。
5. 改源 entry：
   - frontmatter `status: promoted`
   - frontmatter `promotes-to: docs/dev-harness/<area>/rules.md#<新锚点>`
   - frontmatter `updated:` 改今天
6. commit B：`docs(memory): mark MEM-XXX promoted to <area>/rules.md`。

### 2.2 pitfalls → incidents/index.md

步骤：

1. 选 `<AREA>`（同上）。
2. 在 `docs/dev-harness/incidents/index.md` 找下一个可用的 `INC-<AREA>-<NNNN>`：
   ```bash
   grep -oE 'INC-<AREA>-[0-9]+' docs/dev-harness/incidents/index.md | sort -V | tail -1
   ```
3. 增加一条 incident，按 index.md 已有格式（描述 / 触发条件 / guard）。
4. 末尾 markdown 注释 `<!-- promoted from MEM-NATIVE-0003 @ 2026-05-22 -->`
5. commit A：`docs(harness): promote incident INC-<AREA>-<NNNN> from MEM-XXX`，body 引用源 entry。
6. 改源 entry：
   - `status: promoted`
   - `promotes-to: docs/dev-harness/incidents/index.md#INC-<AREA>-<NNNN>`
   - `updated:` 改今天
7. commit B：`docs(memory): mark MEM-XXX promoted to INC-<AREA>-<NNNN>`。

## 3. 上限

**单次 curate 最多 5 条晋升**。超额按 confidence 高低 + 影响域大小排序保留前 5，其余下次再处理，在 REPORT 中列为 "deferred promotion"。

## 4. 晋升后

- 源 memory entry **不**搬到 `_retracted/`，保留在原位（status=promoted）。
- 60 天后由 [memory-compaction.md](memory-compaction.md) §3 第二条判定"被晋升后冗余"再决定要不要 retract。
- 不要在晋升当下立即删除——保留 60 天观察窗口，让新晋升规则在实际开发中被验证。

## 5. 回滚（人工 escalation）

如果晋升出错（rule 写得不对、incident 误判），人工可以：

```bash
git revert <commit A 的 sha>  # 撤掉 rules/incidents 增条
git revert <commit B 的 sha>  # 撤掉 memory entry 状态更新
```

curator 在下次扫描时会看到 memory entry status=stable（已被 revert 回来），按晋升判定重新审视。

## 6. 历史候选来源

除 `memory/` 暂存层外，curator 也可参考：

- 仓库 commit log 中"反复修同一处"的 commit（间接信号）
- 个人 `~/.claude/projects/.../memory/MEMORY.md` 中**反复出现**的项目级偏好（用户级，仅在用户授权时参考；不强制 promote）

这两类不像 `memory/` 那样有结构化 frontmatter，需要 curator 先在 `memory/candidates/` 建一条 entry，下次 curate 再走标准晋升流程。

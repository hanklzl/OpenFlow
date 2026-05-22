# 发布流程

本仓库的发布流水线由 `.github/workflows/android-release-apk.yml` 驱动，对外只暴露一个触发点：**推送 `vX.Y.Z` tag 到 GitHub**。

## 一次性配置

### GitHub release environment secrets

在仓库 Settings → Environments → `release` 内配置：

| Secret | 用途 |
|---|---|
| `ANDROID_RELEASE_KEYSTORE_BASE64` | base64 编码的签名 keystore |
| `ANDROID_RELEASE_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_RELEASE_KEY_ALIAS` | key 别名 |
| `ANDROID_RELEASE_KEY_PASSWORD` | key 密码 |
| `LOGAN_AES_KEY` | 16 字节占位（详见下方说明） |
| `LOGAN_AES_IV` | 16 字节占位（详见下方说明） |
| `ANTHROPIC_API_KEY` | Claude API，用于 release notes 摘要；失败时回退到纯 commit 列表，不阻塞 |

> ⚠️ **OpenFlow 当前状态**：`app/src/main/java/com/hank/flow/open/log/OpenFlowLog.kt` 中 AES key / IV 为硬编码占位（`"0123456789abcdef"` / `"abcdef0123456789"`），CI 接收 `LOGAN_AES_KEY` / `LOGAN_AES_IV` secret 仅为结构 parity，不会被构建产物消费。未来若改成 `BuildConfig.LOGAN_AES_KEY` 注入方式，再启用对应密钥流转。当前在 GitHub Environment `release` 内设占位值即可（例如 16 位任意 hex）。

### `gh-pages` 分支

首次 tag push 时 CI 自动 `git checkout --orphan gh-pages` 创建分支并写入 `release/version.json`。无需手工初始化。

### 版本号 versionCode 公式

`versionCode = MAJOR * 10000 + MINOR * 100 + PATCH`。

例：
- `v0.1.0` → `100`
- `v0.2.0` → `200`
- `v1.0.0` → `10000`
- `v1.2.3` → `10203`

## 首次启用清单

第一次推送 `v0.1.0` 之前，按本清单一次性完成基础设施配置。已经跑过首发的版本可跳过本节。

### 1. 建立 `release` Environment

通过 `gh` CLI（推荐）：
```bash
gh api -X PUT repos/hanklzl/OpenFlow/environments/release
```

或仓库 web UI：Settings → Environments → New environment → 命名 `release` → Save。

### 2. 设置 7 个 secret

`release` Environment 建好后，在仓库根目录执行以下命令（替换实际值）：

```bash
# 4 个签名密钥（与 OpenFlow release 共用的 keystore，保证签名指纹一致）
gh secret set ANDROID_RELEASE_KEYSTORE_BASE64 --env release < /path/to/release.jks.base64
gh secret set ANDROID_RELEASE_STORE_PASSWORD --env release --body "..."
gh secret set ANDROID_RELEASE_KEY_ALIAS --env release --body "..."
gh secret set ANDROID_RELEASE_KEY_PASSWORD --env release --body "..."
# 2 个 Logan 占位（任意 16 hex；OpenFlow 暂不消费）
gh secret set LOGAN_AES_KEY --env release --body "0123456789abcdef"
gh secret set LOGAN_AES_IV --env release --body "abcdef0123456789"
# 1 个可选 Claude API（缺则 release notes fallback 到纯 commit 列表）
gh secret set ANTHROPIC_API_KEY --env release --body "sk-ant-..."
```

设置完成后可用 `gh secret list --env release` 核对 7 条记录齐备。

### 3. keystore base64 化

GitHub Secret 只能存文本，需先把二进制 `.jks` 转 base64：

- macOS：
  ```bash
  base64 -i /path/to/release.jks -o /path/to/release.jks.base64
  ```
- Linux：
  ```bash
  base64 -w0 release.jks > release.jks.base64
  ```

随后用 `gh secret set ANDROID_RELEASE_KEYSTORE_BASE64 --env release < /path/to/release.jks.base64` 注入即可。

### 4. 首次 release notes 偏长

`scripts/release/generate-notes.sh` 在「无前任 tag」时 fallback 到 `git rev-list --max-parents=0 HEAD | tail -1`（根 commit），首个 `v0.1.0` 的 CI notes 会列出仓库初始化以来的所有 commit。建议 CI 跑完后手动 trim `CHANGELOG.md` 那条 `release v0.1.0` entry，推一个 `docs(changelog): trim initial release notes` 跟进 commit。

### 5. `gh-pages` 分支无需预创

由 CI 首跑时 orphan 创建，本地无需 `git push origin --orphan gh-pages` 任何预创操作。

### 6. 可选预演

若有 fork，建议先在 fork 推 `v0.0.1` 走一遍全链路，所有 step 绿后再在主仓推 `v0.1.0`。

## 日常发布步骤

1. 决定语义化版本号 vX.Y.Z。
2. 修改 `version.properties`：
   ```properties
   versionCode=100
   versionName=0.1.0
   ```
3. **本地干跑 preflight**：
   ```bash
   bash scripts/release/preflight.sh v0.1.0
   ```
   该脚本会先执行 `./gradlew lint --no-daemon`，通过后再继续；任何报错都不要 push。
4. `git add version.properties && git commit -m "chore(release): bump to v0.1.0"`
5. `git tag v0.1.0`
6. `git push origin main && git push origin v0.1.0`
7. 观察 [GitHub Actions](https://github.com/hanklzl/OpenFlow/actions) 完成；验证：
   - Release 已创建并含 3 个 asset：`OpenFlow-v0.1.0-arm64-v8a.apk`、`OpenFlow-v0.1.0-x86_64.apk`、`mapping-v0.1.0.zip`
   - notes 末尾「构建产物」矩阵列全
   - `main` 上有 `docs(changelog): release v0.1.0 [skip ci]` 自动 commit
   - `gh-pages/release/version.json` schemaVersion=2、`variants` 双 key、`mapping.url` 指向 release asset
   - jsdelivr 镜像可拉：
     ```bash
     curl -I https://cdn.jsdelivr.net/gh/hanklzl/OpenFlow@gh-pages/release/version.json
     ```

   > **OpenFlow 现状**：`version.json` 中 `variants.*.download` 数组的第二项（jsDelivr 镜像）当前会 404 —— APK 是 GitHub Release asset，并不存在于 gh-pages tag 处。该字段保留作为「未来 OpenFlow 增加 in-app updater 时切到 CDN 镜像」的预留位。当前下载入口以 GitHub Release asset 为准。
8. 装一台测试机冷启动验证启动 dialog → 下载 → 安装链路（arm64 与 x86_64 模拟器各一次）。

## 本地干跑 CI step

每条 step 与 `.github/workflows/android-release-apk.yml` 内的同名 step 一一对应，命名前缀 `[dry] `。所有命令在仓库根目录执行。

### `[dry] Validate version consistency`

```bash
TAG=v0.1.0 bash -c '
  expected="${TAG#v}"
  actual=$(awk -F= "/^versionName/{print \$2}" version.properties | tr -d "[:space:]")
  [ "$expected" = "$actual" ] || { echo "::error::tag $TAG vs versionName $actual mismatch"; exit 1; }
  echo "OK: $TAG ↔ versionName=$actual"
'
```

### `[dry] Run release lint`

```bash
./gradlew lint --no-daemon
```

### `[dry] Build Release APK`

在本机配置一份**未入库** `.env.release.local`（`.gitignore` 已排除）：

```bash
export ANDROID_RELEASE_KEYSTORE_PATH=/abs/path/release.jks
export ANDROID_RELEASE_STORE_PASSWORD=...
export ANDROID_RELEASE_KEY_ALIAS=...
export ANDROID_RELEASE_KEY_PASSWORD=...
export LOGAN_AES_KEY=0123456789abcdef
export LOGAN_AES_IV=abcdef0123456789
```

```bash
source .env.release.local
./gradlew clean :app:assembleRelease --no-daemon
ls -lh app/build/outputs/apk/release/OpenFlow-arm64-v8a-release.apk \
       app/build/outputs/apk/release/OpenFlow-x86_64-release.apk
```

### `[dry] Compute APK sha256 + size`

```bash
for abi in arm64-v8a x86_64; do
  APK="app/build/outputs/apk/release/OpenFlow-${abi}-release.apk"
  sha256sum "$APK" | awk '{print $1}'
  wc -c < "$APK"
done
```

### `[dry] Pack mapping`

```bash
mkdir -p /tmp/of-mapping/mapping
cp app/build/outputs/mapping/release/mapping.txt /tmp/of-mapping/mapping/
(cd /tmp/of-mapping && zip -9q "mapping-v0.1.0.zip" mapping/mapping.txt)
sha256sum /tmp/of-mapping/mapping-v0.1.0.zip
```

### `[dry] Generate release notes`

```bash
PREV=$(git describe --tags --abbrev=0 2>/dev/null || git rev-list --max-parents=0 HEAD | tail -1)
CURR=HEAD
bash scripts/release/generate-notes.sh "$PREV" "$CURR" > /tmp/release_notes.md
less /tmp/release_notes.md
```

本地不愿调 LLM：`unset ANTHROPIC_API_KEY`，走 fallback。

### `[dry] Prepend CHANGELOG.md`

```bash
bash scripts/release/prepend-changelog.sh /tmp/release_notes.md vX.Y.Z --dry-run \
    | diff CHANGELOG.md -
```

`--dry-run` 输出"假如执行后的 CHANGELOG.md 全文"，与现状 diff 出新插入段。**本地不要去掉 `--dry-run` 真写文件**——这步只在 CI 内执行，避免与 CI 重复提交。

### `[dry] Build version.json`

```bash
bash scripts/release/build-version-json.sh \
    --version 0.1.0 \
    --version-code 100 \
    --tag v0.1.0 \
    --variant "arm64-v8a=OpenFlow-v0.1.0-arm64-v8a.apk,<sha_arm>,<size_arm>" \
    --variant "x86_64=OpenFlow-v0.1.0-x86_64.apk,<sha_x64>,<size_x64>" \
    --mapping-name "mapping-v0.1.0.zip" \
    --mapping-sha256 "<sha_mapping>" \
    --notes /tmp/release_notes.md \
    > /tmp/version.json
jq . /tmp/version.json
```

### `[dry] Full pre-flight`

```bash
bash scripts/release/preflight.sh v0.1.0
```

脚本串调上述 6 个 step，任一非 0 即停。**push tag 前跑通 preflight 是硬性约束**。

### 不可本地干跑的 step

| Step | 原因 | 替代验证 |
|---|---|---|
| `gh release create` | 真创建会污染线上 release | 在 fork 上用 `--draft` 跑一次 |
| `git push origin main`（CHANGELOG） | 真 push 污染 main | dry-run diff 已足够 |
| `git push origin gh-pages` | 同上 | 本地切到 gh-pages 看文件结构即可 |

## 回滚

> ⚠️ **若有用户已下载 APK 上传过崩溃报告，不要删 mapping.zip（崩溃栈反混淆唯一依赖）**。回滚仅删 tag / release / gh-pages 状态；mapping zip 应永久保留以便事后反混淆历史崩溃栈。

```bash
# 删 tag
git push origin :v0.1.0
git tag -d v0.1.0
# 删 release
gh release delete v0.1.0
# revert CHANGELOG commit
git revert <changelog-commit-sha>
git push origin main
# 删 gh-pages 对应 commit
git push --force-with-lease origin <gh-pages-prev-sha>:gh-pages
```

## 线上崩溃反混淆

线上某次崩溃需要恢复行号 / 类名，用对应 release tag 的 mapping zip：

```bash
gh release download v0.1.0 --pattern 'mapping-*.zip' --dir /tmp/of-retrace/
unzip /tmp/of-retrace/mapping-v0.1.0.zip -d /tmp/of-retrace/v0.1.0/

# CLI retrace
~/Library/Android/sdk/tools/proguard/bin/retrace.sh \
    /tmp/of-retrace/v0.1.0/mapping/mapping.txt \
    crash.txt
# 或者 IDEA: Tools → ReTrace → 选 mapping.txt + 贴堆栈
```

mapping zip 永久存在 GitHub Release asset 上，按 tag 一一对应。

## 故障排查

| 现象 | 排查 |
|---|---|
| `Validate version consistency` 红色失败 | 校对 `version.properties` 与 tag |
| LLM 摘要为空 | 不阻塞 release；可手工编辑 `CHANGELOG.md` 补摘要 |
| CHANGELOG push 失败 | main 并发推送；按 workflow warning log 手工 cherry-pick |
| 客户端拉不到 `version.json` | 检查 `gh-pages` 分支；jsdelivr 缓存最多 12h；强制刷新 `https://purge.jsdelivr.net/gh/hanklzl/OpenFlow@gh-pages/release/version.json` |
| 用户装不上 | 检查 applicationId（release vs debug 不可覆盖）；用户系统设置「允许此应用安装未知来源」未开 |
| 弹窗"安装包校验失败" | sha256 不匹配；通常是 jsdelivr 缓存旧 APK，命令同上强刷 |
| 「设备架构不受支持」对话框 | 设备 ABI 不在 `arm64-v8a / x86_64` 内（如 32-bit only）；引导用户手动到 GitHub Release 页确认 |
| Release 缺少 mapping zip | 检查 build-release-apk job 的 `Pack mapping` step 是否在 tag 路径触发；mapping.txt 必须先由 R8 生成 |
| Release 装机即崩 / `UnsatisfiedLinkError` on `WhisperJni.nativeTranscribe` 或 `LlamaJni.nativeGenerate` | 立刻触发 R8 紧急回滚（见 `.agents/skills/openflow-release-skill/references/r8-rollback-playbook.md`，Phase 7 落地后存在）。先出 patch tag 关 R8，再用 mapping.txt 反混淆崩溃栈，下一个 minor 重新打开 |

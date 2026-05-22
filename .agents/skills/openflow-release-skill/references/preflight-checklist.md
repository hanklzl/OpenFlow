# Release Pre-flight Checklist

发布 vX.Y.Z 前必须勾完每一项。任一项未确认则中止。

## 一次性配置（首次发布前一次性完成）

- [ ] GitHub Environment `release` 已建（`gh api -X PUT repos/<owner>/OpenFlow/environments/release`）
- [ ] 4 个签名 secret 已设（参见 `RELEASE.md` 命令模板）：
  - `ANDROID_RELEASE_KEYSTORE_BASE64`
  - `ANDROID_RELEASE_STORE_PASSWORD`
  - `ANDROID_RELEASE_KEY_ALIAS`
  - `ANDROID_RELEASE_KEY_PASSWORD`
- [ ] 2 个 LOGAN secret 已设（占位即可，本期代码不消费）：
  - `LOGAN_AES_KEY`
  - `LOGAN_AES_IV`
- [ ] 1 个可选 secret 已设（缺则 release notes 走 fallback）：
  - `ANTHROPIC_API_KEY`
- [ ] `gh secret list --env release` 验证 7 条全列

## 每次发布前

### 环境准备

- [ ] 本地有 `.env.release.local` 文件（与 `.env.release.local.example` 同结构）
- [ ] 4 个 `ANDROID_RELEASE_*` 已正确填写：
  ```bash
  source .env.release.local
  env | grep ANDROID_RELEASE   # 必须列 4 条
  ```
- [ ] keystore 可读且密码正确：
  ```bash
  keytool -list -v -keystore "$ANDROID_RELEASE_KEYSTORE_PATH" \
    -storepass "$ANDROID_RELEASE_STORE_PASSWORD"
  ```
  必须列出 alias 名 + SHA-256 指纹（与 MusicFreeAndroid 一致）

### Worktree 与版本号

- [ ] 在 worktree 中工作：`.worktrees/release-vX.Y.Z`，不在 main 直接改
- [ ] `version.properties` 的 `versionName` 与拟推 tag（去 `v` 前缀）**字面相等**
- [ ] `versionCode` 严格 = `MAJOR*10000 + MINOR*100 + PATCH`（用 python 算一次确认）
  ```bash
  python3 -c "M,m,p=map(int,'X.Y.Z'.split('.')); print(M*10000+m*100+p)"
  ```

### 构建与冒烟

- [ ] `bash scripts/release/preflight.sh vX.Y.Z` 6 step 全绿
- [ ] 真机装 preflight 输出的 `OpenFlow-arm64-v8a-release.apk`，跑长按 → 录音 → ASR → 润色 → 插入一次成功
- [ ] `app/build/outputs/mapping/release/mapping.txt` 存在且非空（preflight 已校验，此处复核）

### CI 与 main 状态

- [ ] `gh run list --limit 1 --workflow=android-release-apk.yml` 看是否有未完成 run（避免并发推 tag 冲突）
- [ ] main 没有未推送的本地 commit 待 prepend-changelog bot 推时冲突：
  ```bash
  cd /Users/zili/code/android/OpenFlow
  git fetch origin main
  git log origin/main..main --oneline   # 必须为空
  ```

### 应急联系

- [ ] 知道当前 GitHub Actions 配额还够跑一次 release（3 job，约 25–40 分钟）
- [ ] 推 tag 后能盯 `gh run watch --exit-status` 直到三 job 全绿（约 30 分钟内不离开终端）

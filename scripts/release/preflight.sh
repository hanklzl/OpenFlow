#!/usr/bin/env bash
# 本地干跑：模拟 CI 的关键 step，验证 release tag 推送前的所有条件就绪。
# 用法：bash scripts/release/preflight.sh vX.Y.Z
set -euo pipefail

if [ $# -lt 1 ]; then
    echo "Usage: $0 vX.Y.Z" >&2
    exit 1
fi
tag="$1"
expected="${tag#v}"

cd "$(git rev-parse --show-toplevel)"

echo "[OpenFlow] Preflight check for tag: $tag"
echo ""

echo "[dry] Validate version consistency"
actual=$(awk -F= '/^versionName/{print $2}' version.properties | tr -d '[:space:]')
[ "$expected" = "$actual" ] || {
    echo "::error::tag $tag vs versionName $actual mismatch" >&2
    exit 1
}
echo "  OK: $tag ↔ versionName=$actual"

echo "[dry] Run release lint"
./gradlew lint --no-daemon

echo "[dry] Build Release APK"
if [ -n "${ANDROID_RELEASE_KEYSTORE_PATH:-}" ] && [ -f "${ANDROID_RELEASE_KEYSTORE_PATH}" ]; then
    ./gradlew clean :app:assembleRelease --no-daemon
else
    echo "  WARN: ANDROID_RELEASE_KEYSTORE_PATH not set; skipping real Release build" >&2
    echo "  (CI 必跑；本地若无签名 env 跳过)"
fi
arm_apk="app/build/outputs/apk/release/OpenFlow-arm64-v8a-release.apk"
x64_apk="app/build/outputs/apk/release/OpenFlow-x86_64-release.apk"
mapping_src="app/build/outputs/mapping/release/mapping.txt"

if [ ! -f "$arm_apk" ] || [ ! -f "$x64_apk" ]; then
    echo "  WARN: per-ABI Release APK not present; downstream steps using stub data" >&2
    arm_apk=""; x64_apk=""; mapping_src=""
fi

echo "[dry] Compute APK sha256 + size"
sha_arm=""; size_arm=""; sha_x64=""; size_x64=""
if [ -n "$arm_apk" ]; then
    sha_arm=$(sha256sum "$arm_apk" | awk '{print $1}')
    size_arm=$(wc -c < "$arm_apk")
    echo "  arm64-v8a: sha256=$sha_arm size=$size_arm"
fi
if [ -n "$x64_apk" ]; then
    sha_x64=$(sha256sum "$x64_apk" | awk '{print $1}')
    size_x64=$(wc -c < "$x64_apk")
    echo "  x86_64:    sha256=$sha_x64 size=$size_x64"
fi

echo "[dry] Pack mapping"
mapping_name=""; mapping_sha256=""
if [ -n "$mapping_src" ] && [ -f "$mapping_src" ]; then
    mkdir -p "/tmp/of-mapping/mapping"
    cp "$mapping_src" "/tmp/of-mapping/mapping/"
    mapping_name="mapping-${tag}.zip"
    (cd /tmp/of-mapping && zip -9q "$mapping_name" mapping/mapping.txt)
    mapping_sha256=$(sha256sum "/tmp/of-mapping/$mapping_name" | awk '{print $1}')
    echo "  mapping zip: /tmp/of-mapping/${mapping_name}  sha256=$mapping_sha256"
else
    echo "  WARN: mapping.txt not present; skipping mapping pack" >&2
fi

echo "[dry] Build GPU Release APK (arm64-v8a, Vulkan + OpenCl)"
# GPU 实验包：第二次 assembleRelease 带 GPU 后端，build.gradle.kts 在带开关时把 splits 收窄到 arm64-v8a。
# 它会覆盖 apk/release/ 与 mapping/release/——CPU 的 sha/size 上面已存进变量、mapping 已打到 /tmp，
# 这里再把 CPU arm64 APK 留一份副本供末尾 smoke 建议引用。
sha_gpu=""; size_gpu=""
if [ -n "$arm_apk" ] && [ -n "${ANDROID_RELEASE_KEYSTORE_PATH:-}" ] && [ -f "${ANDROID_RELEASE_KEYSTORE_PATH}" ]; then
    cp "$arm_apk" /tmp/of-cpu-arm64.apk 2>/dev/null || true
    ./gradlew :app:assembleRelease --no-daemon \
        -POpenflowEnableVulkan=true -POpenflowEnableOpenCl=true
    gpu_apk="app/build/outputs/apk/release/OpenFlow-arm64-v8a-release.apk"
    if [ -f "$gpu_apk" ]; then
        sha_gpu=$(sha256sum "$gpu_apk" | awk '{print $1}')
        size_gpu=$(wc -c < "$gpu_apk")
        echo "  gpu-arm64-v8a: sha256=$sha_gpu size=$size_gpu"
        arm_apk=/tmp/of-cpu-arm64.apk   # CPU arm64 已被覆盖，smoke 建议指向保留副本
    else
        echo "::error::GPU arm64-v8a Release APK not produced" >&2
        exit 1
    fi
else
    echo "  WARN: 无签名 env 或无 CPU 构建，跳过 GPU Release 构建（CI 必跑）" >&2
fi

echo "[dry] Generate release notes"
# 优先用祖先链上的最近 tag；若当前 tag 不在祖先链上（如上次 release 的 hotfix 留在 release 分支没回流），fallback 到字面最近的 vX.Y.Z；都没有再回根 commit
prev=$(git describe --tags --abbrev=0 2>/dev/null) || true
if [ -z "$prev" ]; then
    prev=$(git tag --list 'v*' --sort=-v:refname | grep -vFx "$tag" | head -1)
fi
prev=${prev:-$(git rev-list --max-parents=0 HEAD | tail -1)}
notes="/tmp/release_notes-${tag}.md"
bash scripts/release/generate-notes.sh "$prev" HEAD > "$notes"
echo "  notes -> $notes"

echo "[dry] Prepend CHANGELOG.md (dry-run)"
bash scripts/release/prepend-changelog.sh "$notes" "$tag" --dry-run > /tmp/changelog-dry.md
diff CHANGELOG.md /tmp/changelog-dry.md || true

echo "[dry] Build version.json"
if [ -n "$sha_arm" ] && [ -n "$sha_x64" ]; then
    out="/tmp/version-${tag}.json"
    bash scripts/release/build-version-json.sh \
        --version "$expected" \
        --version-code "$(awk -F= '/^versionCode/{print $2}' version.properties | tr -d '[:space:]')" \
        --tag "$tag" \
        --variant "arm64-v8a=OpenFlow-${tag}-arm64-v8a.apk,${sha_arm},${size_arm}" \
        --variant "x86_64=OpenFlow-${tag}-x86_64.apk,${sha_x64},${size_x64}" \
        ${sha_gpu:+--variant "gpu-arm64-v8a=OpenFlow-${tag}-gpu-arm64-v8a.apk,${sha_gpu},${size_gpu}"} \
        ${mapping_name:+--mapping-name "$mapping_name"} \
        ${mapping_sha256:+--mapping-sha256 "$mapping_sha256"} \
        --notes "$notes" > "$out"
    jq . "$out"
    echo "  version.json -> $out"
else
    echo "  SKIP: no per-ABI hash to fill; rerun preflight after a successful Release build" >&2
fi

echo "[dry] Optional: smoke test on attached device"
if command -v adb >/dev/null 2>&1 && \
   adb devices | awk 'NR>1 && $2=="device" {found=1} END {exit !found}'; then
    echo "  推荐："
    echo "    bash scripts/release/smoke-test.sh --apk $arm_apk"
    echo "  （要求 ~/.openflow-smoke-models/ 已放 whisper + llm 默认模型）"
else
    echo "  跳过：未检测到 adb 设备（smoke 可选；插入设备后单独跑 scripts/release/smoke-test.sh）"
fi

echo "Preflight passed."

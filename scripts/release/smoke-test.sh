#!/usr/bin/env bash
# OpenFlow release APK 真机自动冒烟。
# 流程：adb install → push 模型 → broadcast INSTALL → broadcast RUN_ASR → logcat 等结果。
# 退出码：0 成功 / 1 测试失败 / 2 setup 错（缺 APK / 设备 / 模型）。
set -euo pipefail

APK="${APK:-app/build/outputs/apk/release/OpenFlow-arm64-v8a-release.apk}"
DEVICE=""
MODELS_DIR="${MODELS_DIR:-$HOME/.openflow-smoke-models}"
WHISPER="ggml-tiny-q5_1"                       # ModelCatalog.whisperTiny.id（最轻档 32 MB）
WHISPER_FILE="ggml-tiny-q5_1.bin"              # hfPath 末段；ModelStore.pathFor 会取这个名
LLM="qwen3-0.6b-q4_k_m"                        # ModelCatalog.llmTinyNewer.id（极速档 ~400 MB）
LLM_FILE="qwen3-0.6b-q4_k_m.gguf"
POLISH="true"
FORCE_REINSTALL="false"
TIMEOUT=60
PKG="com.hank.flow.open"

usage() {
    cat <<EOF >&2
Usage: $0 [options]
  --apk <path>          APK 路径 (default: $APK)
  --device <serial>     adb 设备序列号 (default: first attached)
  --models-dir <path>   本机模型目录 (default: \$HOME/.openflow-smoke-models)
  --whisper <id>        Whisper 模型 id (default: $WHISPER)
  --whisper-file <name> Whisper 模型文件名 (default: \${WHISPER}.bin via hfPath)
  --llm <id>            LLM 模型 id (default: $LLM)
  --llm-file <name>     LLM 模型文件名 (default: \${LLM}.gguf via hfPath)
  --no-polish           关闭 polish 阶段（默认开启）
  --force-reinstall     强制覆盖设备上已存在模型
  --timeout <sec>       pipeline 等结果超时 (default: $TIMEOUT)
EOF
    exit 2
}

while [ $# -gt 0 ]; do
    case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --device) DEVICE="$2"; shift 2 ;;
        --models-dir) MODELS_DIR="$2"; shift 2 ;;
        --whisper) WHISPER="$2"; shift 2 ;;
        --whisper-file) WHISPER_FILE="$2"; shift 2 ;;
        --llm) LLM="$2"; shift 2 ;;
        --llm-file) LLM_FILE="$2"; shift 2 ;;
        --no-polish) POLISH="false"; shift ;;
        --force-reinstall) FORCE_REINSTALL="true"; shift ;;
        --timeout) TIMEOUT="$2"; shift 2 ;;
        -h|--help) usage ;;
        *) echo "::error::unknown arg: $1" >&2; usage ;;
    esac
done

# 1. sanity
[ -f "$APK" ] || { echo "::error::APK not found: $APK" >&2; exit 2; }
devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
[ -n "$devices" ] || { echo "::error::no adb device connected (adb devices empty)" >&2; exit 2; }
[ -z "$DEVICE" ] && DEVICE=$(echo "$devices" | head -1)
[ -d "$MODELS_DIR" ] || { echo "::error::models dir not found: $MODELS_DIR" >&2; exit 2; }
echo "[smoke] device=$DEVICE apk=$APK polish=$POLISH"

# 2. install -g 自动授予 runtime 权限，避免麦克风/通知授权对话框阻塞
adb -s "$DEVICE" install -r -g "$APK"

# 3. logcat 后台跟（先清，避免历史污染）
adb -s "$DEVICE" logcat -c
LOG=$(mktemp -t openflow-smoke.XXXXXX.log)
adb -s "$DEVICE" logcat -s OpenFlow/MODEL:D OpenFlow/ASR:D OpenFlow/LLM:D AndroidRuntime:E > "$LOG" &
LOGCAT_PID=$!
trap 'kill $LOGCAT_PID 2>/dev/null || true; rm -f "$LOG"' EXIT
sleep 1  # 让 logcat 跑起来

# 4. preload 模型
preload_model() {
    local id="$1" fname="$2"
    local src="$MODELS_DIR/$fname"
    [ -f "$src" ] || {
        echo "::error::missing $src" >&2
        echo "  请把 $fname 放到 $MODELS_DIR/ 后重试" >&2
        exit 2
    }
    echo "[smoke] push $fname → /data/local/tmp/"
    adb -s "$DEVICE" push "$src" "/data/local/tmp/$fname" >/dev/null
    adb -s "$DEVICE" shell am broadcast \
        -a com.hank.flow.open.debug.INSTALL_MODEL_FROM_TMP \
        -n "$PKG/com.hank.flow.open.debug.DebugModelInstallReceiver" \
        --es srcPath "/data/local/tmp/$fname" \
        --es modelId "$id" \
        --ez force "$FORCE_REINSTALL" >/dev/null

    local deadline=$(( $(date +%s) + 30 ))
    while [ $(date +%s) -lt $deadline ]; do
        if grep -qE "debug_model_install_(done|skip).*modelId=$id" "$LOG"; then
            grep -E "debug_model_install_(done|skip).*modelId=$id" "$LOG" | tail -1
            return 0
        fi
        if grep -qE "debug_model_install_(failed|bad_args|src_missing)" "$LOG"; then
            echo "::error::install $id failed" >&2
            grep -E "debug_model_install_" "$LOG" | tail -5 >&2
            exit 1
        fi
        sleep 0.5
    done
    echo "::error::install $id timeout after 30s" >&2
    exit 1
}

preload_model "$WHISPER" "$WHISPER_FILE"
[ "$POLISH" = "true" ] && preload_model "$LLM" "$LLM_FILE"

# 5. broadcast pipeline
echo "[smoke] broadcast RUN_ASR_FROM_ASSETS"
adb -s "$DEVICE" shell am broadcast \
    -a com.hank.flow.open.debug.RUN_ASR_FROM_ASSETS \
    -n "$PKG/com.hank.flow.open.debug.DebugAsrReceiver" \
    --es wavAsset test/jfk.wav \
    --es whisperId "$WHISPER" \
    --es llmId "$LLM" \
    --ez polish "$POLISH" >/dev/null

# 6. 等结果
deadline=$(( $(date +%s) + TIMEOUT ))
while [ $(date +%s) -lt $deadline ]; do
    if grep -qE "debug_asset_finished" "$LOG"; then
        echo "[smoke] ✓ debug_asset_finished"
        grep -E "debug_asset_(asr|polish)_done|debug_asset_finished" "$LOG" | tail -3
        exit 0
    fi
    if grep -qE "debug_asset_abort_no_model|debug_asset_abort_no_llm" "$LOG"; then
        echo "::error::模型未就绪（install 未生效或 size 校验不过）" >&2
        grep -E "debug_asset_(whisper|llm)_ready|debug_asset_abort_" "$LOG" | tail -5 >&2
        exit 1
    fi
    if grep -qE "debug_asset_service_start_failed|FATAL EXCEPTION" "$LOG"; then
        echo "::error::service start failed / runtime crash" >&2
        tail -50 "$LOG" >&2
        adb -s "$DEVICE" exec-out run-as "$PKG" tar c -C files/logan . 2>/dev/null \
            > "smoke-fail-logan-$(date +%s).tar" || \
            echo "  （run-as 拉 Logan 失败；release APK debuggable=false 时常见，忽略）" >&2
        exit 1
    fi
    sleep 1
done

echo "::error::smoke timeout after ${TIMEOUT}s, last 30 log lines:" >&2
tail -30 "$LOG" >&2
exit 1

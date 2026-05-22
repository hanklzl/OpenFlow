#!/usr/bin/env bash
# Usage: generate-notes.sh <prev-ref> <head-ref>
# Output: markdown release notes on stdout
# Behavior:
#  - reads commits in <prev-ref>..<head-ref>
#  - classifies by conventional commit prefix
#  - if ANTHROPIC_API_KEY is set, queries Claude for a Chinese summary (<=200 chars)
#  - on any LLM failure, omits the summary section and continues (exit 0)
set -euo pipefail

PREV=${1:?"prev ref required"}
HEAD_REF=${2:?"head ref required"}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
LIB="$ROOT/scripts/release/lib"

# Date heading: head ref's commit date, ISO date
RELEASE_DATE=$(git -C "$ROOT" show -s --format=%cs "$HEAD_REF")
TAG_LABEL=$HEAD_REF
if [[ "$HEAD_REF" == "HEAD" ]]; then
    TAG_LABEL="HEAD"
fi

# Collect commit lines
COMMITS=$(git -C "$ROOT" log --pretty=format:'%H%x09%s' "$PREV..$HEAD_REF" || true)

CLASSIFIED=""
if [[ -n "$COMMITS" ]]; then
    CLASSIFIED=$(printf '%s\n' "$COMMITS" | awk -f "$LIB/commit-classify.awk")
fi

SUMMARY=""
if [[ -n "${ANTHROPIC_API_KEY:-}" && -n "$COMMITS" ]]; then
    # Compose user prompt
    USER_PROMPT="基于以下 commit 列表，生成不超过 200 字的中文版本亮点摘要，只点出对用户有感知的变化，不要逐条复述：\n\n${COMMITS}"
    PAYLOAD=$(jq -n --arg model "claude-haiku-4-5" --arg prompt "$USER_PROMPT" '
        {
            model: $model,
            max_tokens: 600,
            system: "你是技术 release notes 编辑。基于给定 commit 列表写一段不超过 200 字的中文版本亮点摘要，不重复列出每条 commit，只点出对用户最有感知的变化。",
            messages: [ { role: "user", content: $prompt } ]
        }
    ')
    if RESPONSE=$(curl -sS --max-time 30 \
        -H "x-api-key: $ANTHROPIC_API_KEY" \
        -H "anthropic-version: 2023-06-01" \
        -H "content-type: application/json" \
        --data "$PAYLOAD" \
        https://api.anthropic.com/v1/messages); then
        SUMMARY=$(printf '%s' "$RESPONSE" | jq -r '.content[0].text // ""')
    else
        echo "::warning::Claude API call failed" >&2
    fi
fi

# Emit markdown
printf '## [%s] - %s\n\n' "$TAG_LABEL" "$RELEASE_DATE"
if [[ -n "$SUMMARY" ]]; then
    printf '%s\n\n' "$SUMMARY"
fi
printf '### 变更详情\n\n'
if [[ -n "$CLASSIFIED" ]]; then
    printf '%s\n' "$CLASSIFIED"
else
    printf '_本次发布无新提交。_\n'
fi

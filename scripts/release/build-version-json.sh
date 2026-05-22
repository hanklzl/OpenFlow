#!/usr/bin/env bash
# Build version.json (schemaVersion = 2) for release/manifest publishing.
#
# Usage:
#   build-version-json.sh \
#     --version 1.2.3 \
#     --version-code 10203 \
#     --tag v1.2.3 \
#     --variant "arm64-v8a=OpenFlow-v1.2.3-arm64-v8a.apk,abc...,12345" \
#     --variant "x86_64=OpenFlow-v1.2.3-x86_64.apk,def...,12346" \
#     --mapping-name "mapping-v1.2.3.zip" \
#     --mapping-sha256 "9c4e..." \
#     --notes /tmp/release_notes.md \
#     [--no-jsdelivr]   # 默认带 jsdelivr 镜像；本 flag 关闭
#
# Output: 一个完整的 JSON 到 stdout。
set -euo pipefail

repo="${GITHUB_REPOSITORY:-hanklzl/OpenFlow}"
include_jsdelivr=1
version=""
version_code=""
tag=""
mapping_name=""
mapping_sha256=""
notes_file=""
declare -a variant_args=()

while [ $# -gt 0 ]; do
    case "$1" in
        --version)         version="$2"; shift 2 ;;
        --version-code)    version_code="$2"; shift 2 ;;
        --tag)             tag="$2"; shift 2 ;;
        --variant)         variant_args+=("$2"); shift 2 ;;
        --mapping-name)    mapping_name="$2"; shift 2 ;;
        --mapping-sha256)  mapping_sha256="$2"; shift 2 ;;
        --notes)           notes_file="$2"; shift 2 ;;
        --no-jsdelivr)     include_jsdelivr=0; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

[ -n "$version" ] || { echo "--version required" >&2; exit 1; }
[ -n "$version_code" ] || { echo "--version-code required" >&2; exit 1; }
[ -n "$tag" ] || { echo "--tag required" >&2; exit 1; }
[ "${#variant_args[@]}" -gt 0 ] || { echo "at least one --variant required" >&2; exit 1; }

released_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
release_notes_url="https://github.com/${repo}/releases/tag/${tag}"

# changeLog: 从 notes 文件抓首段 H2 以下的列表项，最多 8 行
declare -a change_log
if [ -n "$notes_file" ] && [ -f "$notes_file" ]; then
    while IFS= read -r line; do
        case "$line" in
            "- "*)
                clean="${line:2}"
                change_log+=("$clean")
                if [ "${#change_log[@]}" -ge 8 ]; then break; fi
                ;;
        esac
    done < "$notes_file"
fi

# 构造 variants JSON
variants_json="{}"
for v in "${variant_args[@]}"; do
    abi="${v%%=*}"
    rest="${v#*=}"
    IFS=',' read -r apk_name sha256 size <<< "$rest"
    gh_url="https://github.com/${repo}/releases/download/${tag}/${apk_name}"
    download_list=$(jq -n --arg gh "$gh_url" '[$gh]')
    if [ "$include_jsdelivr" = "1" ]; then
        jd_url="https://cdn.jsdelivr.net/gh/${repo}@${tag}/release/${apk_name}"
        download_list=$(jq -n --arg gh "$gh_url" --arg jd "$jd_url" '[$gh, $jd]')
    fi
    variant=$(jq -n \
        --argjson download "$download_list" \
        --argjson size "$size" \
        --arg sha "$sha256" \
        '{download: $download, size: $size, sha256: $sha}')
    variants_json=$(jq --arg key "$abi" --argjson v "$variant" '. + {($key): $v}' <<< "$variants_json")
done

# 构造 mapping
mapping_json="null"
if [ -n "$mapping_name" ] && [ -n "$mapping_sha256" ]; then
    mapping_url="https://github.com/${repo}/releases/download/${tag}/${mapping_name}"
    mapping_json=$(jq -n --arg url "$mapping_url" --arg sha "$mapping_sha256" '{url: $url, sha256: $sha}')
fi

# 序列化 changeLog
if [ "${#change_log[@]}" -eq 0 ]; then
    change_log_json='[]'
else
    change_log_json=$(printf '%s\n' "${change_log[@]}" | jq -R . | jq -s 'map(select(length > 0))')
fi

jq -n \
    --argjson schemaVersion 2 \
    --arg version "$version" \
    --argjson versionCode "$version_code" \
    --arg releasedAt "$released_at" \
    --arg releaseNotesUrl "$release_notes_url" \
    --argjson changeLog "$change_log_json" \
    --argjson variants "$variants_json" \
    --argjson mapping "$mapping_json" \
    '{
        schemaVersion: $schemaVersion,
        version: $version,
        versionCode: $versionCode,
        releasedAt: $releasedAt,
        releaseNotesUrl: $releaseNotesUrl,
        changeLog: $changeLog,
        variants: $variants
    } + (if $mapping == null then {} else {mapping: $mapping} end)'

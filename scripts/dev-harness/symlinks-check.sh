#!/usr/bin/env bash
# Verify every skill under .agents/skills/ is mirrored as a symlink in
# .claude/skills/ and .codex/skills/ (pointing back into .agents/skills/).

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

ALLOW_EMPTY=0
for arg in "$@"; do
  case "$arg" in
    --allow-empty) ALLOW_EMPTY=1 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

AGENTS_SKILLS_DIR=".agents/skills"
CLAUDE_SKILLS_DIR=".claude/skills"
CODEX_SKILLS_DIR=".codex/skills"

if [[ ! -d "$AGENTS_SKILLS_DIR" ]]; then
  echo "MISSING: $AGENTS_SKILLS_DIR" >&2
  exit 1
fi

skills=()
while IFS= read -r entry; do
  skills+=("$entry")
done < <(find "$AGENTS_SKILLS_DIR" -mindepth 1 -maxdepth 1 -type d -exec basename {} \;)

if [[ "${#skills[@]}" -eq 0 ]]; then
  if [[ "$ALLOW_EMPTY" -eq 1 ]]; then
    echo "INFO: $AGENTS_SKILLS_DIR is empty (--allow-empty)"
    exit 0
  fi
  echo "FAIL: no skills found under $AGENTS_SKILLS_DIR" >&2
  exit 1
fi

fail=0
for skill in "${skills[@]}"; do
  for mirror in "$CLAUDE_SKILLS_DIR" "$CODEX_SKILLS_DIR"; do
    link="$mirror/$skill"
    if [[ ! -L "$link" ]]; then
      # Allow real directories in mirrors (e.g. when a skill is local-only).
      if [[ -d "$link" ]]; then
        echo "OK (real dir): $link"
        continue
      fi
      echo "FAIL: $link missing (expected symlink to ../../.agents/skills/$skill)" >&2
      fail=1
      continue
    fi
    target=$(readlink "$link")
    expected="../../.agents/skills/$skill"
    if [[ "$target" != "$expected" ]]; then
      echo "FAIL: $link -> $target (expected $expected)" >&2
      fail=1
      continue
    fi
    echo "OK: $link -> $target"
  done
done

if [[ "$fail" -ne 0 ]]; then
  exit 1
fi

#!/usr/bin/env bash
# OpenFlow dev-harness local check driver.
#
# Runs:
#   1. symlinks-check.sh        — verify .claude/skills and .codex/skills mirror .agents/skills
#   2. grep-check.py            — grep guards for MUST/MUST NOT items in rules.md
#   3. Kotlin test source compile (catches test fixture drift)
#   4. Unit tests
#
# Flags:
#   --skip-tests              — skip step 4 (gradle test invocation)
#   --skip-compile-tests      — skip step 3
#   --allow-empty-symlinks    — allow .agents/skills/ to be empty (for first-time bootstrap)

set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

SKIP_TESTS=0
SKIP_COMPILE_TESTS=0
ALLOW_EMPTY_SYMLINKS=0
for arg in "$@"; do
  case "$arg" in
    --skip-tests) SKIP_TESTS=1 ;;
    --skip-compile-tests) SKIP_COMPILE_TESTS=1 ;;
    --allow-empty-symlinks) ALLOW_EMPTY_SYMLINKS=1 ;;
    *) echo "Unknown arg: $arg" >&2; exit 2 ;;
  esac
done

echo "==> Symlinks"
if [[ "$ALLOW_EMPTY_SYMLINKS" -eq 1 ]]; then
  bash scripts/dev-harness/symlinks-check.sh --allow-empty
else
  bash scripts/dev-harness/symlinks-check.sh
fi

echo "==> Grep guards"
python3 scripts/dev-harness/grep-check.py

if [[ "$SKIP_COMPILE_TESTS" -eq 0 ]]; then
  echo "==> Compile-only test sources (catches fixture drift)"
  ./gradlew :app:compileDebugUnitTestKotlin --no-daemon
fi

if [[ "$SKIP_TESTS" -eq 0 ]]; then
  echo "==> Unit tests"
  ./gradlew :app:testDebugUnitTest --no-daemon
fi

echo "==> OK"

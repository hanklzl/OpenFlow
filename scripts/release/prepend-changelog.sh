#!/usr/bin/env bash
# Usage: prepend-changelog.sh <notes-file> <tag> [--dry-run]
# Inserts notes content into CHANGELOG.md right after the "<!-- next-release -->" marker.
# With --dry-run, writes resulting file to stdout instead of modifying CHANGELOG.md.
set -euo pipefail

NOTES_FILE=${1:?"notes file required"}
TAG=${2:?"tag required"}
MODE=${3:-write}

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CHANGELOG="$ROOT/CHANGELOG.md"
MARKER="<!-- next-release -->"

if [[ ! -f "$CHANGELOG" ]]; then
    echo "::error::CHANGELOG.md not found at $CHANGELOG" >&2
    exit 1
fi
if ! grep -Fq "$MARKER" "$CHANGELOG"; then
    echo "::error::Marker '$MARKER' not present in CHANGELOG.md" >&2
    exit 1
fi
if [[ ! -f "$NOTES_FILE" ]]; then
    echo "::error::Notes file $NOTES_FILE not found" >&2
    exit 1
fi

OUTPUT=$(awk -v marker="$MARKER" -v notes_file="$NOTES_FILE" '
    BEGIN { inserted = 0 }
    {
        print
        if (!inserted && index($0, marker)) {
            print ""
            while ((getline line < notes_file) > 0) {
                print line
            }
            close(notes_file)
            inserted = 1
        }
    }
' "$CHANGELOG")

if [[ "$MODE" == "--dry-run" ]]; then
    printf '%s\n' "$OUTPUT"
else
    printf '%s\n' "$OUTPUT" > "$CHANGELOG"
fi

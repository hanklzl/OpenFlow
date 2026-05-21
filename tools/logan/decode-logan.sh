#!/usr/bin/env bash
set -euo pipefail

INPUT="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ALLOWED_OUTPUT_BASE="${SCRIPT_DIR}/out"
OUTPUT_DIR="${2:-$ALLOWED_OUTPUT_BASE}"
mkdir -p "$ALLOWED_OUTPUT_BASE"
ALLOWED_OUTPUT_BASE_ABS="$(cd "$ALLOWED_OUTPUT_BASE" && pwd -P)"
DECODER_SOURCE="${SCRIPT_DIR}/LoganLocalDecoder.java"
DECODER_CLASSES="${ALLOWED_OUTPUT_BASE_ABS}/.decoder-classes"
LOGAN_AES_KEY="${LOGAN_AES_KEY:-0123456789abcdef}"
LOGAN_AES_IV="${LOGAN_AES_IV:-abcdef0123456789}"

normalize_output_dir() {
  local request="$1"
  local candidate

  if [[ "$request" = "$ALLOWED_OUTPUT_BASE_ABS" || "$request" == "$ALLOWED_OUTPUT_BASE_ABS"/* ]]; then
    candidate="$request"
  elif [[ "$request" = /* ]]; then
    if [[ "$request" != "$ALLOWED_OUTPUT_BASE_ABS" && "$request" != "$ALLOWED_OUTPUT_BASE_ABS/"* ]]; then
      echo "Output directory must be under ${ALLOWED_OUTPUT_BASE_ABS}. Received: ${request}" >&2
      exit 1
    fi
    candidate="$request"
  else
    if [[ "$request" == *".."* ]]; then
      echo "Relative output path may not contain '..': ${request}" >&2
      exit 1
    fi
    candidate="$ALLOWED_OUTPUT_BASE_ABS/$request"
  fi

  mkdir -p "$candidate"
  local resolved="$(cd "$candidate" && pwd -P)"
  if [[ "$resolved" != "$ALLOWED_OUTPUT_BASE_ABS" && "$resolved" != "$ALLOWED_OUTPUT_BASE_ABS/"* ]]; then
    echo "Output directory must be under ${ALLOWED_OUTPUT_BASE_ABS}. Received: ${resolved}" >&2
    exit 1
  fi
  printf '%s\n' "$resolved"
}

require_tool() {
  local tool="$1"
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "Required tool not found: ${tool}" >&2
    exit 1
  fi
}

validate_secret() {
  local name="$1"
  local value="$2"
  if [[ "${#value}" -ne 16 ]]; then
    echo "${name} must be exactly 16 ASCII characters." >&2
    exit 1
  fi
}

if [[ -z "${INPUT}" ]]; then
  echo "Usage: tools/logan/decode-logan.sh <feedback-zip-or-logan-dir> [output-dir]" >&2
  exit 2
fi

if [[ -z "${OUTPUT_DIR}" ]]; then
  echo "Invalid output directory: ${OUTPUT_DIR}" >&2
  exit 1
fi

require_tool javac
require_tool java
validate_secret "LOGAN_AES_KEY" "${LOGAN_AES_KEY}"
validate_secret "LOGAN_AES_IV" "${LOGAN_AES_IV}"

OUTPUT_DIR="$(normalize_output_dir "${OUTPUT_DIR}")"
EXTRACTED_DIR="${OUTPUT_DIR}/extracted"
LOGAN_DIR="${OUTPUT_DIR}/logan"
DECODED_DIR="${OUTPUT_DIR}/decoded"

echo "Decode input: ${INPUT}"
echo "Output dir: ${OUTPUT_DIR}"
echo "Release logs require LOGAN_AES_KEY and LOGAN_AES_IV in the environment."
echo "Debug logs use the repository development key when environment variables are not set."

if [[ -d "${OUTPUT_DIR}" ]]; then
  find "${OUTPUT_DIR}" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
fi

mkdir -p "${EXTRACTED_DIR}" "${LOGAN_DIR}" "${DECODED_DIR}" "${DECODER_CLASSES}"

case "${INPUT}" in
  *.[zZ][iI][pP])
    require_tool unzip
    unzip -q -o "${INPUT}" -d "${EXTRACTED_DIR}"
    if [[ ! -d "${EXTRACTED_DIR}/logan" ]]; then
      echo "Feedback zip does not contain a logan/ directory." >&2
      exit 1
    fi
    cp -R "${EXTRACTED_DIR}/logan/." "${LOGAN_DIR}/"
    ;;
  *)
    if [[ -d "${INPUT}" ]]; then
      if [[ -d "${INPUT}/logan" ]]; then
        cp -R "${INPUT}/." "${EXTRACTED_DIR}/"
        cp -R "${INPUT}/logan/." "${LOGAN_DIR}/"
      else
        cp -R "${INPUT}/." "${LOGAN_DIR}/"
      fi
    else
      echo "Input must be a feedback zip file or a directory." >&2
      exit 1
    fi
    ;;
esac

javac -d "${DECODER_CLASSES}" "${DECODER_SOURCE}"

LOG_LIST="${OUTPUT_DIR}/.logan-files"
find "${LOGAN_DIR}" -type f ! -name '.*' -print > "${LOG_LIST}"
if [[ ! -s "${LOG_LIST}" ]]; then
  echo "No raw Logan files found under ${LOGAN_DIR}." >&2
  exit 1
fi

decoded_count=0
while IFS= read -r log_file; do
  relative_path="${log_file#"${LOGAN_DIR}/"}"
  decoded_file="${DECODED_DIR}/${relative_path}.txt"
  mkdir -p "$(dirname "${decoded_file}")"
  java -cp "${DECODER_CLASSES}" LoganLocalDecoder \
    "${log_file}" \
    "${decoded_file}" \
    "${LOGAN_AES_KEY}" \
    "${LOGAN_AES_IV}"
  decoded_count=$((decoded_count + 1))
done < "${LOG_LIST}"

rm -f "${LOG_LIST}"
echo "Decoded ${decoded_count} file(s) into ${DECODED_DIR}"

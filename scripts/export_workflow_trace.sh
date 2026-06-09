#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8083}"
SESSION_ID="${SESSION_ID:-}"
OUT_DIR="${OUT_DIR:-.demo-output/workflow-trace}"

if [[ -z "$SESSION_ID" ]]; then
  echo "SESSION_ID is required." >&2
  echo "Usage: SESSION_ID=<chat-session-id> BASE_URL=$BASE_URL $0" >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
python3 "$(dirname "$0")/export_workflow_trace.py" \
  --base-url "$BASE_URL" \
  --session-id "$SESSION_ID" \
  --out "$OUT_DIR/workflow-trace-$SESSION_ID.json"

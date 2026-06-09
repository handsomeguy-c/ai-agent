#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8083}"
AGENT_ID="${AGENT_ID:-}"
SESSION_ID="${SESSION_ID:-}"
KB_ID="${KB_ID:-}"
QUERY="${QUERY:-这个系统的 Agent、MCP 和 RAG 是怎么协作的？}"
DEMO_WRITE_MEMORY="${DEMO_WRITE_MEMORY:-true}"
OUT_DIR="${OUT_DIR:-.demo-output/$(date +%Y%m%d-%H%M%S)}"

mkdir -p "$OUT_DIR"

has_python() {
  command -v python3 >/dev/null 2>&1
}

pretty_json() {
  if has_python; then
    python3 -m json.tool
  else
    cat
  fi
}

request_json() {
  local method="$1"
  local url="$2"
  local body="${3:-}"
  local output="$4"

  if [[ -n "$body" ]]; then
    curl -sS -X "$method" "$url" \
      -H "Content-Type: application/json" \
      --data "$body" | tee "$output" | pretty_json
  else
    curl -sS -X "$method" "$url" | tee "$output" | pretty_json
  fi
}

assert_contains() {
  local file="$1"
  local expected="$2"
  if ! grep -q "$expected" "$file"; then
    echo "ASSERT FAILED: expected '$expected' in $file" >&2
    exit 1
  fi
}

json_value() {
  local file="$1"
  local expr="$2"
  python3 - "$file" "$expr" <<'PY'
import json
import sys

path, expr = sys.argv[1], sys.argv[2]
with open(path, "r", encoding="utf-8") as f:
    data = json.load(f)
value = data
for part in expr.split("."):
    if isinstance(value, dict):
        value = value.get(part)
    else:
        value = None
        break
print("" if value is None else value)
PY
}

echo "== MindPilot Agent Stack Demo =="
echo "BASE_URL=$BASE_URL"
echo "OUT_DIR=$OUT_DIR"
echo

echo "1) Health check"
request_json GET "$BASE_URL/api/health" "" "$OUT_DIR/01-health.json" || {
  echo "Backend is not reachable. Start it first, for example: docker compose up -d --build" >&2
  exit 1
}
echo

echo "2) ToolRegistry schema list: /api/tools/list"
request_json GET "$BASE_URL/api/tools/list" "" "$OUT_DIR/02-tool-registry-list.json"
assert_contains "$OUT_DIR/02-tool-registry-list.json" "sourceType"
assert_contains "$OUT_DIR/02-tool-registry-list.json" "inputSchema"
echo

echo "3) MCP initialize: /api/mcp/rag"
request_json POST "$BASE_URL/api/mcp/rag" '{
  "jsonrpc": "2.0",
  "id": "demo-init",
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {"tools": {}},
    "clientInfo": {"name": "mindpilot-demo-client", "version": "0.1.0"}
  }
}' "$OUT_DIR/03-mcp-initialize.json"
assert_contains "$OUT_DIR/03-mcp-initialize.json" "mindpilot-rag-memory-mcp-server"
echo

echo "4) MCP tools/list: server exposes schema and tool list"
request_json POST "$BASE_URL/api/mcp/rag" '{
  "jsonrpc": "2.0",
  "id": "demo-tools",
  "method": "tools/list",
  "params": {}
}' "$OUT_DIR/04-mcp-tools-list.json"
assert_contains "$OUT_DIR/04-mcp-tools-list.json" "rag.hybrid_search"
assert_contains "$OUT_DIR/04-mcp-tools-list.json" "memory.save_long_term"
assert_contains "$OUT_DIR/04-mcp-tools-list.json" "memory.recall"
assert_contains "$OUT_DIR/04-mcp-tools-list.json" "inputSchema"
echo

if [[ "$DEMO_WRITE_MEMORY" == "true" ]]; then
  if [[ -z "$AGENT_ID" ]]; then
    echo "5) Create demo Agent for memory foreign key"
    request_json POST "$BASE_URL/api/agents" '{
      "name": "MCP Memory Demo Agent",
      "description": "Demo agent used by scripts/demo_agent_stack.sh",
      "systemPrompt": "你是 MindPilot 演示 Agent。",
      "model": "deepseek-chat",
      "allowedTools": [],
      "allowedKbs": [],
      "chatOptions": {
        "temperature": 0.7,
        "topP": 1.0,
        "messageLength": 10,
        "executionMode": "react"
      }
    }' "$OUT_DIR/05-create-demo-agent.json"
    AGENT_ID="$(json_value "$OUT_DIR/05-create-demo-agent.json" "data.agentId")"
    assert_contains "$OUT_DIR/05-create-demo-agent.json" "agentId"
    echo "   AGENT_ID=$AGENT_ID"
    echo
  else
    echo "5) Use provided AGENT_ID=$AGENT_ID"
    echo
  fi

  if [[ -z "$SESSION_ID" ]]; then
    echo "6) Create demo ChatSession for memory foreign key"
    request_json POST "$BASE_URL/api/chat-sessions" "{
      \"agentId\": \"$AGENT_ID\",
      \"title\": \"MCP Memory Demo Session\"
    }" "$OUT_DIR/06-create-demo-session.json"
    SESSION_ID="$(json_value "$OUT_DIR/06-create-demo-session.json" "data.chatSessionId")"
    assert_contains "$OUT_DIR/06-create-demo-session.json" "chatSessionId"
    echo "   SESSION_ID=$SESSION_ID"
    echo
  else
    echo "6) Use provided SESSION_ID=$SESSION_ID"
    echo
  fi

  echo "7) MCP tools/call: write Mem0-style long-term memory"
  MEMORY_CONTENT="演示记忆：用户偏好回答先给结论，再给关键步骤。"
  request_json POST "$BASE_URL/api/mcp/rag" "{
    \"jsonrpc\": \"2.0\",
    \"id\": \"demo-memory-save\",
    \"method\": \"tools/call\",
    \"params\": {
      \"name\": \"memory.save_long_term\",
      \"arguments\": {
        \"agentId\": \"$AGENT_ID\",
        \"sessionId\": \"$SESSION_ID\",
        \"content\": \"$MEMORY_CONTENT\"
      }
    }
  }" "$OUT_DIR/07-mcp-memory-save.json"
  assert_contains "$OUT_DIR/07-mcp-memory-save.json" "saved long-term memory"
  echo

  echo "8) MCP tools/call: recall memory"
  request_json POST "$BASE_URL/api/mcp/rag" "{
    \"jsonrpc\": \"2.0\",
    \"id\": \"demo-memory-recall\",
    \"method\": \"tools/call\",
    \"params\": {
      \"name\": \"memory.recall\",
      \"arguments\": {
        \"agentId\": \"$AGENT_ID\",
        \"query\": \"用户偏好什么回答方式？\",
        \"limit\": 5
      }
    }
  }" "$OUT_DIR/08-mcp-memory-recall.json"
  assert_contains "$OUT_DIR/08-mcp-memory-recall.json" "memories"
  echo
else
  echo "5) Skip memory write because DEMO_WRITE_MEMORY=false"
  echo
fi

if [[ -n "$KB_ID" ]]; then
  echo "9) MCP tools/call: RAG hybrid search with KB_ID=$KB_ID"
  request_json POST "$BASE_URL/api/mcp/rag" "{
    \"jsonrpc\": \"2.0\",
    \"id\": \"demo-rag-search\",
    \"method\": \"tools/call\",
    \"params\": {
      \"name\": \"rag.hybrid_search\",
      \"arguments\": {
        \"kbId\": \"$KB_ID\",
        \"query\": \"$QUERY\"
      }
    }
  }" "$OUT_DIR/09-mcp-rag-hybrid-search.json"
  assert_contains "$OUT_DIR/09-mcp-rag-hybrid-search.json" "results"
  echo
else
  echo "9) Skip RAG hybrid search because KB_ID is empty."
  echo "   Re-run with: KB_ID=<your-kb-id> $0"
  echo
fi

echo "Demo evidence saved to: $OUT_DIR"
echo "Recommended interview proof points:"
echo "- 02-tool-registry-list.json proves ToolRegistry exposes tool schemas."
echo "- 04-mcp-tools-list.json proves MCP Server returns tool list and inputSchema."
echo "- 05/06 files prove the script prepared real Agent/Session rows for FK-safe memory demo."
echo "- 07/08 files prove MCP tools/call can write and recall long-term memory."
echo "- 09 file proves RAG is exposed as an MCP tool when KB_ID is provided."

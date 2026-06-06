# JChatMind Demo Guide

This guide is optimized for a short interview demo.

## 1. Start The Stack

```bash
cp .env.example .env
docker compose up -d --build
docker compose exec ollama ollama pull bge-m3
```

Open:

```text
http://localhost
```

## 2. Show MCP Adaptation

JChatMind exposes its RAG and memory capabilities as an MCP-style JSON-RPC server:

```text
POST /api/mcp/rag
```

Tool discovery request:

Initialize first:

```json
{
  "jsonrpc": "2.0",
  "id": "demo-init",
  "method": "initialize",
  "params": {
    "protocolVersion": "2024-11-05",
    "capabilities": {
      "tools": {}
    },
    "clientInfo": {
      "name": "demo-client",
      "version": "0.1.0"
    }
  }
}
```

Then discover tools:

```json
{
  "jsonrpc": "2.0",
  "id": "demo-1",
  "method": "tools/list",
  "params": {}
}
```

Expected tools:

- `rag.hybrid_search`: RAG hybrid retrieval with vector recall, ES BM25 coarse ranking, rerank, and context packing.
- `memory.save_long_term`: explicit Mem0-style long-term memory write.
- `memory.recall`: semantic memory recall.

The MCP client side is implemented by `McpRemoteTool`. Configure a server in `.env`, then select `McpRemoteTool` in the Agent tool list:

```dotenv
APP_MCP_ENABLED=true
APP_MCP_SERVERS_0_NAME=jchatmind-rag
APP_MCP_SERVERS_0_ENDPOINT=http://backend:8083/api/mcp/rag
APP_MCP_SERVERS_0_DESCRIPTION=JChatMind RAG and memory MCP server
```

## 3. Show RAG

1. Create a knowledge base.
2. Upload a Markdown document.
3. Confirm parse status is `SUCCESS` and chunk count is greater than 0.
4. Create an Agent and select that knowledge base.
5. Ask a question from the uploaded document.
6. Expand tool responses and citations in the chat UI.

What to explain:

- Markdown is parsed into structured sections.
- Each chunk stores contextual metadata: document name, section path, adjacent sections, and contextual summary.
- Query side runs LLM structured rewrite, vector recall, ES BM25 coarse retrieval, hybrid merge, cross-encoder rerank, and context packing.

## 4. Show Mem0-Style Memory

Ask:

```text
请记住，我偏好回答先给结论，再给关键步骤。
```

Then ask:

```text
你还记得我的回答偏好吗？
```

What to explain:

- Perceptual memory captures each user turn.
- Short-term memory is stored for moderately important turns.
- Long-term memory is stored for high-importance facts and preferences.
- Entity memory updates stable user facts.
- Long-term memory can also be written through MCP `memory.save_long_term`.

## 5. Show Plan-And-Execute Expert Collaboration

Create or edit an Agent and set execution mode to:

```text
Plan-and-Execute
```

Ask a task that needs knowledge and tools:

```text
基于知识库总结这个系统的核心架构，并说明哪些模块最适合写进简历。
```

What to explain:

- The Agent first acts as a central dispatcher.
- It creates a structured `Plan` with `PlanStep` objects: `stepId`, `stepType`, `expert`, `description`, `toolName`, `input`, and `expectedOutput`.
- The current run is tracked by `AgentExecutionState`, which stores user input, context state, current step index, plan, step results, replan count, and termination reason.
- Execution proceeds step by step. Each step writes a `StepResult` with status, observation, output, error, duration, and timestamps.
- If a retrieval/tool/MCP step fails, the workflow can trigger one `Replan` and restart with a new plan.
- `Plan`, `StepResult`, and `AgentExecutionState` are persisted in chat message metadata, so the workflow trace can be inspected through `/api/chat-messages/session/{sessionId}`.
- SSE shows planning, thinking, executing, and done states.

## 6. Show ES BM25

Elasticsearch is included in Docker Compose. Uploaded chunks are indexed into:

```text
jchatmind_chunks
```

The indexed field `contextualContent` includes:

- Section path
- Contextual summary
- Original chunk content

If ES is unavailable, JChatMind falls back to PostgreSQL keyword search, so the demo remains stable.

## 7. Show Cross-Encoder Rerank And LLM Query Rewrite

Enable:

```dotenv
APP_RERANK_ENABLED=true
APP_RERANK_ENDPOINT=http://reranker:8080/rerank
APP_RERANK_MODEL=BAAI/bge-reranker-v2-m3
APP_QUERY_REWRITE_LLM_ENABLED=true
APP_QUERY_REWRITE_MODEL=deepseek-chat
```

What to explain:

- Query rewrite uses the LLM to emit strict JSON: normalized query, intent, keywords, and multiple rewrites.
- Rerank sends query-document pairs to a cross-encoder endpoint and combines cross-encoder score with recall score.
- If the rerank service is down, the system falls back to local rerank so the demo remains stable.

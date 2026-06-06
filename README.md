# JChatMind

JChatMind is an AI Agent collaboration platform built with Spring Boot, Spring AI, React, PostgreSQL, and pgvector. It focuses on Agent execution modes, tool calling, memory management, and RAG-enhanced knowledge retrieval.

## Features

- **Agent modes**: ReAct, Plan-and-Execute, and Reflection. The frontend can switch execution mode when creating or editing an Agent.
- **Tool calling and MCP**: Centralized tool registry, Spring AI tool callbacks, `/api/tools/list` schema discovery, `/api/tools/call` managed invocation, an MCP client adapter with `initialize` / `tools/list` / `tools/call`, and a built-in RAG/memory MCP server at `/api/mcp/rag`.
- **Memory module**: Mem0-style perceptual memory, short-term memory, long-term memory, and entity memory with vector recall, explicit MCP long-term memory writes, entity fact updates, and time-decay ranking.
- **RAG pipeline**: Markdown parsing, contextual chunk enrichment, contextual indexing, LLM structured query rewrite/intent/keyword extraction, query embedding, vector retrieval, Elasticsearch BM25 coarse ranking, hybrid recall, cross-encoder rerank, and context packing.
- **Realtime UX**: SSE streams Agent status and generated content to the frontend.
- **Deployable stack**: Docker Compose with frontend Nginx, backend Spring Boot, PostgreSQL + pgvector, and optional Ollama embeddings. Only the frontend is exposed by default.

## Tech Stack

- Backend: Spring Boot 3.5, Spring AI 1.1, MyBatis, PostgreSQL, pgvector
- Frontend: React 19, Vite, Ant Design
- AI: DeepSeek / ZhipuAI chat models, Ollama `bge-m3` embeddings by default
- Deployment: Docker, Docker Compose, Nginx, Elasticsearch

## Local Development

Backend:

```bash
cd jchatmind
./mvnw -DskipTests compile
./mvnw spring-boot:run
```

Frontend:

```bash
cd ui
npm install
npm run dev
```

Default local URLs:

- Frontend: `http://127.0.0.1:5173`
- Backend: `http://localhost:8083`
- Health: `http://localhost:8083/api/health`

## Docker Deployment

See [DEPLOYMENT.md](./DEPLOYMENT.md).

Quick start:

```bash
cp .env.example .env
docker compose up -d --build
docker compose exec ollama ollama pull bge-m3
```

Open:

```text
http://localhost
```

## Required Environment Variables

At minimum, set these in `.env` for production-like deployment:

```dotenv
POSTGRES_PASSWORD=change_me_strong_password
DEEPSEEK_API_KEY=your_key
APP_CORS_ALLOWED_ORIGIN_PATTERNS=https://your-domain.com
```

For embeddings:

```dotenv
RAG_EMBEDDING_BASE_URL=http://ollama:11434
RAG_EMBEDDING_MODEL=bge-m3
```

For optional remote MCP tools:

```dotenv
APP_MCP_ENABLED=true
APP_MCP_SERVERS_0_NAME=browser
APP_MCP_SERVERS_0_ENDPOINT=http://your-mcp-server/mcp
```

For Elasticsearch BM25:

```dotenv
APP_ELASTICSEARCH_ENABLED=true
APP_ELASTICSEARCH_BASE_URL=http://elasticsearch:9200
APP_ELASTICSEARCH_INDEX_NAME=jchatmind_chunks
```

For cross-encoder rerank and LLM query rewrite:

```dotenv
APP_RERANK_ENABLED=true
APP_RERANK_ENDPOINT=http://reranker:8080/rerank
APP_RERANK_MODEL=BAAI/bge-reranker-v2-m3
APP_QUERY_REWRITE_LLM_ENABLED=true
APP_QUERY_REWRITE_MODEL=deepseek-chat
```

## Demo

See [DEMO.md](./DEMO.md) for the interview demo flow.

## Verification

```bash
cd jchatmind && ./mvnw -q -DskipTests compile
cd ../ui && npm run build
docker compose config
```

# JChatMind Deployment Guide

This guide describes the single-host Docker Compose deployment path for JChatMind.

## Requirements

- Docker 24+
- Docker Compose v2
- At least 4 GB RAM. More is recommended if you run Ollama locally.
- A valid LLM API key, for example `DEEPSEEK_API_KEY`.

## Quick Start

```bash
cp .env.example .env
```

Edit `.env` before starting:

- Change `POSTGRES_PASSWORD`.
- Fill `DEEPSEEK_API_KEY` or `ZHIPUAI_API_KEY`.
- Replace `APP_CORS_ALLOWED_ORIGIN_PATTERNS` with your domain.

Start the stack:

```bash
docker compose up -d --build
```

If you use the built-in Ollama service for embeddings, pull the embedding model once:

```bash
docker compose exec ollama ollama pull bge-m3
```

Open the app:

```text
http://localhost
```

Health check:

```bash
curl http://localhost/api/health
```

## Services

- `frontend`: Nginx static site and reverse proxy. Exposes `${FRONTEND_PORT:-80}`.
- `backend`: Spring Boot API. It is only reachable inside the Compose network; frontend Nginx proxies `/api` and `/sse` to it.
- `postgres`: PostgreSQL with pgvector. Initializes schema from `jchatmind_assert/jchatmind.sql`.
- `ollama`: Local embedding runtime. Used by default at `http://ollama:11434`.
- `elasticsearch`: BM25 coarse retrieval and contextual chunk indexing. Used by default at `http://elasticsearch:9200`.

## Production Notes

- Put a TLS reverse proxy such as Caddy, Nginx, or a cloud load balancer in front of `frontend`.
- Do not commit `.env`.
- Use a strong database password. Backend, PostgreSQL, and Ollama are private by default; only `frontend` exposes an HTTP port.
- Persisted data lives in Docker volumes: `postgres_data`, `document_data`, and `ollama_data`.
- For managed embeddings, point `RAG_EMBEDDING_BASE_URL` to your embedding service and remove or ignore the `ollama` service.

## Optional MCP Remote Tools

Remote MCP tools are disabled by default. To expose one HTTP/JSON-RPC MCP server through the unified tool registry, set:

```dotenv
APP_MCP_ENABLED=true
APP_MCP_SERVERS_0_NAME=browser
APP_MCP_SERVERS_0_ENDPOINT=http://mcp-browser:3000/mcp
APP_MCP_SERVERS_0_DESCRIPTION=Browser automation MCP server
```

After startup, `/api/tools/list` includes local Spring AI tools plus discovered remote MCP schemas. Agents call remote tools through `McpRemoteTool`, which dispatches `tools/call` to the configured server.

JChatMind also exposes its own RAG and memory tools as a built-in MCP server:

```text
POST /api/mcp/rag
```

It provides `rag.hybrid_search`, `memory.save_long_term`, and `memory.recall`, which is useful for demonstrating MCP adaptation without depending on an external service.

## Elasticsearch BM25

Uploaded Markdown chunks are indexed into Elasticsearch with contextual fields. The backend first tries ES BM25 coarse retrieval, then falls back to PostgreSQL keyword search if ES is unavailable:

```dotenv
APP_ELASTICSEARCH_ENABLED=true
APP_ELASTICSEARCH_BASE_URL=http://elasticsearch:9200
APP_ELASTICSEARCH_INDEX_NAME=jchatmind_chunks
```

## Useful Commands

```bash
docker compose ps
docker compose logs -f backend
docker compose logs -f frontend
docker compose down
docker compose down -v
```

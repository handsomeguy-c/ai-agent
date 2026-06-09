CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE agent (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    
    name TEXT NOT NULL,                    -- Agent 名称
    description TEXT,                      -- 描述（用户可见）
    system_prompt TEXT,                    -- 系统指令
    model TEXT,                            -- 默认使用的模型
    allowed_tools JSONB,                   -- 允许使用的工具列表
    allowed_kbs JSONB,                     -- 允许访问的知识库
    chat_options JSONB,                    -- 其它配置项（温度、top_p、最大token）
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE chat_session (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    agent_id UUID REFERENCES agent(id) ON DELETE SET NULL,  -- 绑定的 Agent
    
    title TEXT,                          -- 自动生成的标题
    metadata JSONB,                      -- 扩展（例如输入语言、设备类型）
  
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE chat_message (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,

    role TEXT NOT NULL,                      -- user / assistant / system / tool
    content TEXT,                            -- 主体内容
    metadata JSONB,                          -- 工具调用、RAG 片段、模型参数等
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    name TEXT NOT NULL,
    description TEXT,
    metadata JSONB,                         -- 业务属性，如行业/标签

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,

    filename TEXT NOT NULL,
    filetype TEXT,                          -- pdf / md / txt 等
    size BIGINT,                            -- 文件大小
    metadata JSONB,                         -- 页数、上传方式、解析参数等

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE chunk_bge_m3 (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,

    content TEXT NOT NULL,                  -- 切片后的文本内容
    metadata JSONB,                         -- 页码、段落号、chunk index 等

    embedding VECTOR(1024) NOT NULL,        -- bge_m3 模型是 1024 维的向量

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE session_summary (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    summary TEXT NOT NULL,
    covered_until_message_id UUID,
    covered_message_count INT,
    token_estimate INT,
    version INT DEFAULT 1,
    metadata JSONB,

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE memory (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    user_id UUID,
    agent_id UUID REFERENCES agent(id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    content TEXT NOT NULL,
    source_session_id UUID REFERENCES chat_session(id) ON DELETE SET NULL,
    source_message_ids JSONB,
    importance_score NUMERIC(4,3),
    status TEXT DEFAULT 'active',
    embedding VECTOR(1024),
    metadata JSONB,

    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    last_accessed_at TIMESTAMP
);

CREATE TABLE tool_execution_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    session_id UUID REFERENCES chat_session(id) ON DELETE CASCADE,
    message_id UUID,
    tool_name TEXT NOT NULL,
    arguments JSONB,
    result TEXT,
    status TEXT NOT NULL,
    duration_ms INT,
    error_message TEXT,
    retry_count INT DEFAULT 0,

    created_at TIMESTAMP DEFAULT NOW()
);

-- 给向量加索引
CREATE INDEX idx_chunk_embedding
ON chunk_bge_m3
USING ivfflat (embedding vector_l2_ops)
WITH (lists = 100);

CREATE INDEX idx_memory_agent_status
ON memory(agent_id, status);

CREATE INDEX idx_memory_embedding
ON memory
USING ivfflat (embedding vector_l2_ops)
WITH (lists = 100);

CREATE INDEX idx_chunk_bge_m3_content_fts
ON chunk_bge_m3
USING gin (to_tsvector('simple', COALESCE(content, '') || ' ' || COALESCE(metadata::text, '')));

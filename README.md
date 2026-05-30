
* **Agent 三模式**：支持 ReAct、Plan-and-Execute、Reflection 三种执行模式，前端创建/编辑 Agent 时可切换；后端运行时分别执行工具循环、先规划后执行、执行后反思校验。
* **工具调用框架**：工具统一由 `ToolFacadeService` 注册和治理，提供 `/api/tools/list` 输出工具 schema，提供 `/api/tools/call` 统一封装工具调用，便于接入 MCP 风格的远程工具发现和调用。
* **记忆模块**：按 Memo 思路实现感知记忆、短期记忆、长期记忆、实体记忆；支持会话滑动窗口、摘要压缩、长期记忆抽取、向量召回、实体事实更新和时间衰减召回。
* **RAG 管线**：Markdown 结构化切分后入库，查询侧增加意图识别、关键词抽取、Query Rewrite、Query Embedding，召回侧实现向量检索 + BM25/关键词检索混合召回，再接本地 rerank 精排和上下文 packing。
* **安全配置**：模型 API Key 和邮件授权码已改为环境变量读取，避免 GitHub 泄露密钥。



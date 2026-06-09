# MindPilot 项目业务逻辑与实现文档

本文档用于完整梳理 MindPilot 的业务逻辑、技术流程和模块边界，方便与其他项目横向对比，也方便面试时从“用户请求如何进入系统”讲到“Agent 如何规划、检索、调用工具、写入记忆、生成答案”。

## 1. 项目定位

MindPilot 是一个基于 Spring Boot + Spring AI 的 AI Agent 协作平台。它不是简单调用大模型接口，而是围绕 Agent 任务执行闭环做了后端工程化封装。

核心目标是把用户的一次请求转成一个可控的任务执行流：

```text
用户输入
-> 创建 Agent Runtime
-> 加载会话上下文和长期记忆
-> 根据执行模式选择 ReAct / Plan-and-Execute / Reflection
-> 必要时调用工具、RAG、MCP 远程工具
-> 工具结果写回 Observation / StepResult
-> 结果聚合、引用生成、终止控制
-> SSE 推送给前端
```

项目主要解决四类问题：

- Agent 如何可控执行，而不是让模型自由跑。
- 工具如何统一注册、发现、调用和追踪。
- 长期记忆如何从对话中抽取、存储、召回并注入上下文。
- RAG 如何完成从文档入库到混合检索、精排和引用生成的闭环。

## 2. 整体架构

系统由前端、后端、数据库、向量检索、BM25 检索和可选模型服务组成。

```text
React 前端
-> Spring Boot API
-> Agent Runtime
-> Spring AI ChatClient
-> Tool Calling / MCP Client / RAG / Memory
-> PostgreSQL + pgvector
-> Elasticsearch BM25
-> Ollama bge-m3 embedding
```

主要模块如下：

- `Agent 模块`：负责 ReAct、Plan-and-Execute、Reflection 三种模式。
- `Workflow 模块`：负责 AgentExecutionState、Plan、PlanStep、StepResult、ContextState。
- `工具模块`：负责本地工具、MCP 工具、工具 schema 和工具调用日志。
- `MCP 模块`：内置 RAG/Memory MCP Server，同时提供 MCP Client 适配器。
- `Memory 模块`：负责感知记忆、短期记忆、长期记忆、实体记忆。
- `RAG 模块`：负责文档解析、结构化切分、Contextual Retrieval、向量检索、BM25、rerank 和 context packing。
- `SSE 模块`：负责把 Agent 状态和生成内容实时推给前端。
- `部署模块`：通过 Docker Compose 组织前端、后端、Postgres、Elasticsearch、Ollama。

## 3. 一次用户请求的完整链路

用户在前端输入问题后，前端调用创建聊天消息接口。后端保存用户消息后发布 `ChatEvent`。

后端监听到事件后做两件事：

1. 抽取本轮输入中的记忆。
2. 创建本轮 Agent Runtime 并执行。

整体流程如下：

```text
POST /api/chat-messages
-> ChatMessageFacadeService 保存用户消息
-> 发布 ChatEvent
-> ChatEventListener 监听事件
-> MemoryService.captureTurn 抽取记忆
-> AgentFactory.create 创建 Agent Runtime
-> ConversationContextService 加载会话窗口和摘要
-> MemoryService.buildMemoryPrompt 召回长期记忆
-> 解析 Agent 可用工具和知识库
-> Agent Runtime 执行 Agent
```

这条链路的关键点是：每一次用户请求都会创建一个新的 Agent Runtime，但它会加载同一个 session 的历史上下文、摘要、长期记忆、工具配置和知识库配置。

## 4. Agent 执行模式

项目支持三种 Agent 执行模式。

### 4.1 ReAct 模式

ReAct 适合简单或中等复杂任务。模型每一轮根据上下文决定直接回答还是调用工具。

执行流程：

```text
Agent think
-> LLM 判断是否需要 tool call
-> 如果有 tool call，后端执行工具
-> 工具返回 Observation
-> Observation 写回上下文
-> 本轮生成 REACT-n StepResult
-> StepResult 写回 AgentExecutionState
-> StepResult 持久化到 chat_message.metadata
-> 下一轮继续 think
-> 没有工具调用或触发 terminate 后结束
```

当前实现中，ReAct 不只是聊天循环。Agent Runtime 会把 `workflowState.status` 标记为 `EXECUTING`，每一轮更新 `currentStepIndex` 和 scratchpad 里的轮次信息。

如果模型发起工具调用，后端会通过 `ToolDispatcher` 执行工具，并把 `ToolObservation` 聚合成 `StepResult`：

```text
stepId = REACT-n
stepType = TOOL_CALL
expert = ToolExpert
observation = 工具返回结果
metadata.dispatcher = ToolDispatcher
metadata.toolCallCount = 本轮工具数量
```

如果模型没有发起工具调用，说明本轮已经可以直接回答，系统会生成 `DIRECT_ANSWER` 类型的 `StepResult`，并写入 `AgentExecutionState.finalAnswer` 和 `terminationReason`。

这个模式的优点是灵活，模型可以边观察边行动；同时后端会维护轮次、最大步数、工具调用结果和终止原因，避免模型无限循环。

### 4.2 Plan-and-Execute 模式

Plan-and-Execute 适合复杂任务。系统会先规划，再按步骤执行。

当前项目已经引入结构化工作流对象：

- `AgentExecutionState`：本轮 Agent 执行状态。
- `Plan`：结构化计划。
- `PlanStep`：计划中的单个步骤。
- `StepResult`：每一步执行结果。
- `ContextState`：上下文状态。

`AgentExecutionState` 会记录：

```text
executionId
agentId / sessionId
userInput
executionMode
status
currentStepIndex
toolCallCount / maxToolCalls
replanCount / maxReplans
plan
stepResults
contextState
finalAnswer
terminationReason
```

`ContextState` 会记录：

```text
userInput
shortTermMemory
memoryPrompt
availableToolNames
availableKnowledgeBaseIds
citations
scratchpad
```

其中 `shortTermMemory` 是本轮加载的会话窗口摘要，`memoryPrompt` 是按感知记忆、短期记忆、长期记忆和实体记忆召回后的长期上下文提示。

执行流程：

```text
创建 AgentExecutionState
-> Planner 生成结构化 Plan
-> Plan 写入 AgentExecutionState
-> Plan 持久化到 chat_message.metadata
-> Executor 按 PlanStep 逐步执行
-> CentralDispatcher 根据 stepType / expert 分派专家
-> 每一步生成 StepResult
-> StepResult 写回 AgentExecutionState
-> StepResult 持久化到 chat_message.metadata
-> 如果工具或检索失败，触发一次 Replan
-> 最后 Verifier 审核
-> 写入 terminationReason
```

Plan-and-Execute 的单步失败不只看异常。当前实现会把下面情况标记为失败 StepResult：

```text
工具调用返回 FAILED 或 fallbackUsed=true
RAG / KnowledgeExpert 步骤未检索到有效证据
```

失败 StepResult 会带上：

```text
errorMessage = 工具错误或“检索结果不足”
metadata.hasFailedTool
metadata.insufficientRetrieval
```

如果错误信息命中工具、检索或 MCP 关键字，`shouldReplan()` 会在 `maxReplans` 范围内触发一次重新规划。

`PlanStep` 包含这些字段：

```text
stepId
stepType
expert
description
toolName
input
expectedOutput
status
```

`StepResult` 包含这些字段：

```text
stepId
stepType
expert
status
observation
output
errorMessage
durationMs
metadata
startedAt
finishedAt
```

这使得复杂任务不是一次性丢给模型，而是变成可追踪、可审计、可重规划的工作流。

中心调度器会把每个 Step 路由到对应专家职责：

```text
RETRIEVAL / MCP_TOOL_CALL -> KnowledgeExpert
MEMORY_READ / MEMORY_WRITE -> MemoryExpert
TOOL_CALL -> ToolExpert
SYNTHESIS / DIRECT_ANSWER -> SynthesisExpert
VERIFICATION -> Verifier
PLANNING -> Planner
```

当前代码中，专家路由不是写死在 prompt 里的描述，而是由独立的 `ExpertAgentRouter` 完成。Agent Runtime 在解析 Plan 和执行 `PlanStep` 时都会调用路由器，得到 `ExpertAgentRoute`：

```text
PlanStep
-> ExpertAgentRouter.route()
-> ExpertAgentRoute
   - expert
   - profile
   - routingPolicy
   - recommendedTool
   - requiresToolCall
   - readOnly
-> 写入 ContextState.scratchpad
-> 写入 StepResult.metadata
```

执行时还会把专家分派证据写入 `StepResult.metadata`：

```text
delegatedBy = CentralDispatcher
expert = KnowledgeExpert / MemoryExpert / ToolExpert / SynthesisExpert / Verifier
expertProfile = 当前专家职责说明
routingPolicy = route_to_rag_or_knowledge_tool / route_to_tool_dispatcher / ...
recommendedTool = Planner 推荐工具
requiresToolCall = 是否需要工具调用
readOnly = 是否只读步骤
```

同时 `ContextState.scratchpad` 会记录 `activeExpert`、`activeStepType` 和 `routingPolicy`，表示当前正在执行哪类专家步骤，以及中心调度器选择了哪条执行路径。

### 4.3 传给 LLM 的消息结构

项目传给 LLM 的不是单个字符串，而是 Spring AI 的 `Prompt + Message` 结构。可以理解为：

```text
Prompt
-> chatOptions
   -> internalToolExecutionEnabled = false
-> messages
   -> SystemMessage: 长期记忆召回内容
   -> SystemMessage: Agent 原始系统提示词
   -> SystemMessage: 历史摘要或历史工具结果
   -> UserMessage: 历史用户输入和当前问题
   -> AssistantMessage: 历史模型回答，可能包含 tool_calls
   -> ToolResponseMessage: 工具 Observation
   -> SystemMessage: 当前 PlanStep 单步执行指令
-> dynamic system prompt
   -> ReAct thinkPrompt / Plan JSON prompt / Reflection prompt
-> toolCallbacks
   -> ToolRegistry 暴露的工具 schema
```

也就是说，`AgentExecutionState` 不会被整个对象直接塞给模型，而是拆成消息和动态提示词：

- 历史上下文、记忆、工具观察结果进入 `messages`。
- 当前执行角色和约束进入 `.system(...)`。
- 工具能力通过 `toolCallbacks(...)` 暴露给模型。
- 模型返回 tool call 后，后端通过 `ToolDispatcher` 执行，再把结果写成 `ToolResponseMessage` 和 `StepResult`。

### 4.4 Reflection 模式

Reflection 模式适合需要审核质量的回答。它会在普通执行完成后做一次反思校验。

校验内容包括：

- 是否覆盖用户问题。
- 是否存在没有依据的断言。
- 是否需要补充引用来源。
- 是否需要修订最终答案。

当前实现中，Reflection 会进入 `VERIFYING` 状态，并生成结构化审核结果：

```text
stepId = REFLECT-n
stepType = VERIFICATION
expert = ReflectionAgent
metadata.revised = 是否修订最终答案
metadata.checkedItems = coverage / evidence / citation / hallucinationRisk
```

如果模型判断无需修订，系统只保存审核 StepResult，不额外生成新的回答；如果需要修订，会把修订答案保存为新的 AssistantMessage，并更新 `AgentExecutionState.finalAnswer` 和 `terminationReason`。

Reflection 更像答案质量审核，不等于完整 Replan。Replan 是执行失败后的重新规划，Reflection 是答案生成后的质量检查。

## 5. 工具体系

项目的工具体系围绕 Function Calling + MCP 设计。

工具调用链路如下：

```text
LLM 输出 tool call
-> Agent Loop 接收 tool call
-> ToolRegistry 根据 toolName 查找 ToolDefinition
-> ToolDispatcher 做 schema validation / permission / timeout / retry / fallback
-> 根据 sourceType 路由
   -> LOCAL: 调用本地 Java ToolCallback
   -> MCP: 通过 McpRemoteTools 调用远程 MCP Server
-> 工具结果封装成 ToolResponseMessage
-> 写入 Observation / StepResult
-> 写回 AgentExecutionState
-> 下一轮再给 LLM
```

手动工具调用和工具治理链路如下：

```text
/api/tools/call
-> ToolFacadeService
-> ToolDispatcher
-> ToolRegistry 查找 ToolDefinition
-> schema validation / permission check / timeout / retry
-> 根据 sourceType 路由
   -> LOCAL: 调用本地 Java ToolCallback
   -> MCP: 通过 McpRemoteTools 调用远程 MCP Server
-> 统一封装 ToolObservation
-> 返回 ToolCallResponseDTO
```

### 5.1 工具注册

本地工具通过 Java Bean 和 Spring AI `@Tool` 注解暴露。

当前已有工具类型：

- `KnowledgeTools`：知识库检索工具。
- `FileSystemTools`：文件系统工具。
- `EmailTools`：邮件工具。
- `DataBaseTools`：数据库查询工具。
- `DirectAnswerTool`：直接回答工具。
- `TerminateTool`：终止工具。
- `McpRemoteTools`：MCP 远程工具适配器。

`ToolRegistryImpl` 负责把本地 Java 工具和远程 MCP 工具统一转换成 `ToolDefinition`。

`ToolDefinition` 保存工具元信息：

```text
toolName
description
inputSchema
type
sourceType
source
serverName
remoteToolName
timeoutMs
maxRetries
permissionLevel
returnDirect
localCallback
```

`ToolFacadeServiceImpl` 是对外门面，负责调用 Registry 和 Dispatcher。

### 5.2 工具发现

工具 schema 可以通过接口暴露：

```text
GET /api/tools/list
```

返回字段包括：

```text
name
description
type
source
sourceType
inputSchema
timeoutMs
maxRetries
permissionLevel
returnDirect
```

这相当于工具注册中心里的工具文档。

### 5.3 工具分发

Agent loop 内部仍使用 Spring AI 的 Function Calling schema 让模型生成 tool call，但工具执行不再交给默认 `ToolCallingManager`。

当前主链路是：

```text
AssistantMessage.ToolCall
-> ToolInvocation(toolName, arguments, agentId, sessionId)
-> ToolDispatcher.dispatch(...)
-> ToolObservation
-> ToolResponseMessage
-> chatMemory / StepResult / ToolExecutionLog
```

手动调用时可以使用：

```text
POST /api/tools/call
```

执行结果统一封装为：

```text
ToolCallResponseDTO
```

`ToolDispatcherImpl` 会根据 `ToolDefinition.sourceType` 选择执行路径：

```text
LOCAL -> 直接调用本地 ToolCallback
MCP -> 调用 McpRemoteTools -> MCP Server tools/call
```

调度前会做基础治理：

- `schema validation`：基于 inputSchema 做轻量 JSON Schema 校验，覆盖 required、type、enum、字符串长度、数值上下界、数组长度和嵌套 properties。
- `permission check`：敏感工具需要 Agent 授权上下文。
- `timeout`：每次工具调用有最大执行时间。
- `retry`：失败后按 maxRetries 重试。
- `fallback`：多次失败后返回 fallback observation，避免流程直接无声中断。

执行结果封装成 `ToolObservation`：

```text
toolName
sourceType
result
status
errorMessage
durationMs
retryCount
fallbackUsed
```

在 Plan-and-Execute 模式下，工具结果会进一步写成 `StepResult`。

### 5.4 MCP Client

项目提供 `McpRemoteTools` 作为 MCP Client 适配器。

它支持 MCP 风格 JSON-RPC 生命周期：

```text
initialize
notifications/initialized
tools/list
tools/call
```

使用方式：

```text
配置 MCP Server endpoint
-> Agent 选择 McpRemoteTool
-> Client 初始化远程 Server
-> 拉取远程工具 schema
-> ToolRegistry 合并为 sourceType=MCP 的 ToolDefinition
-> 根据 toolName 调用远程工具
```

`McpRemoteTools.listRemoteToolSchemas()` 会读取远程 `tools/list` 返回的 `inputSchema` 和 `_meta`，并补齐：

```text
sourceType = MCP
permissionLevel = AGENT_ALLOWED
timeoutMs = 10000
maxRetries = 1
source = MCP:{serverName}[capability=...,readOnly=...]
```

### 5.5 MCP Server

项目内置 RAG/Memory MCP Server：

```text
POST /api/mcp/rag
```

支持工具：

- `rag.hybrid_search`：执行混合检索。
- `memory.save_long_term`：显式写入长期记忆。
- `memory.recall`：召回相关记忆。

MCP Server 返回能力信息：

```text
protocolVersion
capabilities
serverInfo
tools
inputSchema
_meta
```

`tools/list` 会返回每个工具的 JSON Schema：

```text
rag.hybrid_search
- required: kbId, query

memory.save_long_term
- required: agentId, sessionId, content

memory.recall
- required: agentId, query
- optional: limit
```

`_meta` 会标记工具能力，例如 `capability=rag/memory`、`readOnlyHint` 和 `memoryLayer=long_term`，方便 MCP Client 做展示、治理或权限判断。

这样项目既可以作为 MCP Client 调用外部工具，也可以作为 MCP Server 暴露自己的 RAG 和 Memory 能力。

## 6. 长期记忆业务逻辑

项目参考 Mem0 思路实现轻量级长期记忆。它不是把所有聊天记录塞进 prompt，而是从对话中抽取稳定、重要、可复用的信息。

记忆写入链路：

```text
用户输入
-> ChatEventListener
-> MemoryService.captureTurn
-> 重要性评分
-> 分层写入 memory 表
-> 生成 embedding
-> 写入 pgvector
```

### 6.1 记忆分层

项目将记忆分为四层：

- `perceptual`：感知记忆，每轮用户输入的即时感知。
- `short_term`：短期记忆，中等重要的信息。
- `long_term`：长期记忆，高重要度事实、偏好、目标、项目背景。
- `entity`：实体记忆，用户姓名、偏好、技术栈、稳定事实。

### 6.2 重要性评分

系统会根据内容做启发式评分。

提高重要性的信号包括：

- 包含“记住”“以后”“偏好”“我的”等表达。
- 包含“项目”“简历”“方案”“Agent”“RAG”等项目相关信息。
- 能抽取出用户姓名、偏好、稳定事实。

重要性高的内容会进入长期记忆。

### 6.3 实体记忆更新

实体记忆用于保存稳定事实。

示例：

```text
我叫张三
我的技术栈是 Spring AI
我喜欢先给结论
```

会抽取为：

```text
用户姓名是 张三
用户的技术栈是 Spring AI
用户偏好：先给结论
```

当前更新策略偏向 `latest_fact_wins`，也就是新事实覆盖旧事实。

### 6.4 记忆召回

下一轮用户提问时，Agent 创建阶段会召回相关记忆。

召回流程：

```text
当前用户问题
-> 生成 query embedding
-> 到 memory 表按向量相似度召回
-> 如果向量召回失败，降级关键词召回
-> 更新 lastAccessedAt
-> 按 entity / long_term / short_term / perceptual 分组
-> 生成 memoryPrompt
-> 注入 Agent 上下文
```

生成的 memoryPrompt 类似：

```text
【可召回记忆】
实体记忆：
- 用户偏好：先给结论

长期记忆：
- 用户正在准备 AI Agent 简历项目

请仅在与当前问题相关时使用这些记忆；如果记忆与用户最新意图冲突，以用户最新输入为准。
```

### 6.5 通过 MCP 写入长期记忆

长期记忆也可以通过 MCP 工具显式写入：

```text
memory.save_long_term
```

这使得 Agent 在执行复杂任务时，可以把重要事实作为工具调用结果写入长期记忆，而不是只依赖自动抽取。

## 7. RAG 建库逻辑

RAG 建库是从原始文档变成可检索知识片段的过程。

整体流程：

```text
上传文档
-> 保存文件和 document 记录
-> 文档解析
-> 结构化切分
-> 生成 contextual metadata
-> 生成 embedding
-> 写入 pgvector
-> 写入 Elasticsearch BM25 索引
-> 更新文档解析状态
```

### 7.1 文档保存

用户在知识库页面上传 Markdown 文档。

后端会先做三件事：

1. 创建 `document` 记录。
2. 保存原始文件到本地存储目录。
3. 将文档解析状态标记为 `PENDING`。

### 7.2 文档解析

当前优先支持 Markdown 文档。

Markdown 文档优先按照标题、章节、段落解析。

解析时会保留：

```text
title
content
headingLevel
sectionIndex
charStart
charEnd
```

当前解析器还内置了轻量法律/合同/裁判文本结构识别规则：

- 法律文本按法条、款、项切分。
- 合同文本按合同条款、章节、义务主体切分。
- 裁判文书按裁判要旨、法院观点、事实认定、争议焦点切分。
- 技术文档按标题、接口、参数说明、示例代码切分。

识别到下面这些边界时，会把它们提升为独立 section：

```text
第X章 / 第X节 / 第X条 / 第X款
合同条款
裁判要旨
争议焦点
法院认为 / 本院认为 / 法院观点
1. / 1.1 / 1、 形式的条款编号
```

### 7.3 结构化切分

切分原则是先结构、后长度。

优先按自然语义结构切分：

```text
标题
章节
段落
条款
法条
接口块
代码说明块
```

如果某个章节太长，再按段落聚合做二次长度切分，并生成 `part 1 / part 2` 这样的子 section，避免一个 chunk 太长导致 embedding 和 prompt 都被噪声稀释。

切分后保留 metadata：

```text
title
sectionPath
sourceFileName
headingLevel
chunkIndex
charStart
charEnd
tokenCount
previousTitle
nextTitle
contextualSummary
```

这些 metadata 后续会进入向量检索、BM25 检索、引用展示和上下文组装。

当前解析版本会写入文档 metadata：

```text
parserVersion = markdown-legal-contextual-v3
embeddingModel = bge-m3
```

### 7.4 Contextual Retrieval 建库增强

普通 chunk 很容易丢失上下文。

例如一个 chunk 内容是：

```text
其优点如下：
1. 可扩展性强
2. 部署简单
```

如果不带标题和章节路径，模型不知道“其”指什么。

项目在建库阶段会为每个 chunk 补充上下文说明：

```text
文档: xxx.md
章节路径: RAG 管理模块 > 建库流程
上一章节: 文档上传
下一章节: 混合检索
片段摘要: 本节介绍 Markdown 解析和上下文索引
```

然后将：

```text
上下文说明 + 标题 + 原始正文
```

一起生成 embedding。

这样做的好处：

- 提升孤立 chunk 的语义完整性。
- 提高向量召回准确率。
- 提高 BM25 对标题、章节、专有名词的匹配能力。
- 方便最终答案生成 citation。

### 7.5 向量索引

项目使用 Ollama `bge-m3` 生成 embedding，默认维度是 1024。

向量写入 PostgreSQL + pgvector：

```text
chunk_bge_m3.embedding VECTOR(1024)
```

pgvector 用于语义检索。

### 7.6 BM25 倒排索引

项目接入 Elasticsearch 做 BM25 粗排。

写入 ES 的核心字段包括：

```text
chunkId
kbId
docId
docName
title
sectionPath
contextualSummary
content
contextualContent
metadata
```

其中 `contextualContent` 由这些内容拼接：

```text
sectionPath
contextualSummary
原始 chunk content
```

这样 BM25 不只是匹配正文，也能匹配章节路径和上下文摘要。

如果 Elasticsearch 不可用，系统会降级到 PostgreSQL 关键词检索。

## 8. RAG 在线检索逻辑

在线检索是用户提问后，从知识库召回相关 chunk 并注入 Agent 的过程。

整体流程：

```text
用户 query
-> LLM Query Rewrite
-> query embedding
-> pgvector 向量召回
-> Elasticsearch BM25 关键词召回
-> 混合合并
-> cross-encoder rerank
-> context packing
-> 返回 citations
-> 注入 Agent prompt
```

### 8.1 Query 预处理

项目优先使用 LLM 做结构化 query rewrite。

LLM 输出 JSON：

```json
{
  "originalQuery": "原始问题",
  "normalizedQuery": "标准化问题",
  "intent": "knowledge_qa",
  "keywords": ["关键词1", "关键词2"],
  "rewrites": ["改写查询1", "改写查询2"]
}
```

如果 LLM 不可用，则降级到规则方式：

- 正则提取中文词和英文 token。
- 去掉停用词。
- 基于关键词生成多路 rewrite。
- 简单判断意图类别。

### 8.2 向量召回

系统对 rewrite query 生成 embedding，然后到 pgvector 中按向量距离召回。

适合解决：

- 语义相似。
- 用户换一种说法。
- 概念性问题。
- 非精确关键词匹配。

### 8.3 BM25 召回

系统使用 ES BM25 对 `contextualContent` 做关键词检索。

适合解决：

- 专有名词。
- 字段名。
- 接口名。
- 错误码。
- 精确短语。

### 8.4 混合召回

向量召回和 BM25 召回会合并。

当前使用 weighted RRF + 少量归一化原始分进行融合：

```text
向量结果提供语义相关性
BM25 结果提供关键词精确匹配
vector: weight = 0.65
keyword/BM25: weight = 0.35
rrfScore += weight / (60 + rank)
hybridScore = rrfScore + 0.2 * normalizedRawScore
去重后形成候选集
```

每个结果的 metadata 会保留检索证据：

```text
retrievalSources
vectorRank
keywordRank
vectorScore
keywordScore
rrfScore
hybridScore
fusionMethod
```

`KnowledgeTool` 返回给 LLM 的上下文里也会带上召回来源和 rank，便于模型知道该片段是语义召回、关键词召回，还是两路都命中。

这样比单一路径更稳。

### 8.5 Rerank 精排

粗召回后，项目支持 cross-encoder rerank。

流程：

```text
query + candidate chunk
-> 发送给 reranker endpoint
-> 返回 relevance score
-> 与原召回分数融合
-> 重新排序
```

如果 reranker 服务不可用，则降级到本地启发式 rerank。

### 8.6 Context Packing

最终不会把所有 chunk 都塞给模型，而是做上下文打包。

打包时会保留：

```text
章节路径
上下文摘要
原始 chunk 内容
相关度分数
来源文档
```

这样模型生成答案时可以引用来源，并减少无关内容进入 prompt。

### 8.7 Citation 生成

RAG 工具返回结果时会生成 citations。

每条 citation 包含：

```text
index
chunkId
kbId
docId
docName
title
score
snippet
```

前端可以展示来源，模型回答也可以使用 `[1] [2]` 标注依据。

## 9. MCP 与 RAG/Memory 的结合

项目把 RAG 和 Memory 能力包装成 MCP Server。

内置 MCP endpoint：

```text
POST /api/mcp/rag
```

支持生命周期：

```text
initialize
notifications/initialized
tools/list
tools/call
```

支持工具：

```text
rag.hybrid_search
memory.save_long_term
memory.recall
```

这样可以把项目内部能力作为标准化外部工具暴露给其他 Agent 或 MCP Client。

同时项目也提供 MCP Client：

```text
McpRemoteTools
```

它可以连接外部 MCP Server，拉取工具 schema，并通过 `tools/call` 调用远程工具。

远程 MCP 工具会进入统一工具体系：

```text
MCP tools/list
-> ToolSchemaDTO
-> ToolRegistryImpl
-> ToolDefinition(sourceType=MCP)
-> ToolDispatcher
-> McpRemoteTools.tools/call
```

## 10. SSE 实时交互逻辑

Agent 执行过程中会通过 SSE 向前端推送状态。

状态类型包括：

```text
AI_PLANNING
AI_THINKING
AI_EXECUTING
AI_DONE
AI_CONTENT_START
AI_CONTENT_DELTA
AI_CONTENT_DONE
AI_CONTENT_ERROR
```

前端可以看到：

- 正在规划。
- 正在思考。
- 正在调用工具。
- 流式生成回答。
- 工具调用结果。
- RAG 引用来源。

这让 Agent 执行过程不是黑盒。

## 11. 数据模型概览

核心数据表包括：

- `agent`：Agent 配置，包括模型、系统提示词、可用工具、可用知识库、执行模式。
- `chat_session`：聊天会话。
- `chat_message`：聊天消息和 metadata，保存 tool calls、tool response、plan、stepResult、workflowState。
- `knowledge_base`：知识库。
- `document`：上传文档和解析状态。
- `chunk_bge_m3`：文档 chunk、metadata、embedding。
- `memory`：长期记忆、短期记忆、实体记忆。
- `session_summary`：会话摘要。
- `tool_execution_log`：工具执行日志。

`chat_message.metadata` 是重要扩展点。

它可以保存：

```text
toolCalls
toolResponse
citations
plan
stepResult
workflowState
importanceScore
memoryAction
```

这样不新增复杂表，也能追踪 Agent 执行过程。

## 12. 降级与兜底策略

项目设计了多处降级逻辑，保证演示和本地开发稳定。

- LLM Query Rewrite 失败时，降级规则 query rewrite。
- ES BM25 不可用时，降级 PostgreSQL 关键词检索。
- Cross-encoder rerank 不可用时，降级本地 rerank。
- 记忆向量生成失败时，降级关键词召回。
- Plan 结构化解析失败时，降级启发式 Plan。
- 工具执行失败时，`ToolDispatcher` 会返回 fallback observation；ReAct 会记录失败工具状态，Plan-and-Execute 会生成失败 StepResult 并可触发一次 Replan。

这些降级策略让系统不会因为某个外部服务不可用而整体崩掉。

## 13. 当前项目已经实现的能力

已经实现：

- ReAct、Plan-and-Execute、Reflection 三种执行模式。
- AgentExecutionState 驱动的结构化工作流。
- Plan、PlanStep、StepResult、ContextState。
- AgentState 显式记录短期记忆窗口、长期记忆提示、工具列表、工具调用计数和终止原因。
- ReflectionAgent 质量审核 StepResult。
- 本地工具 Function Calling。
- ToolRegistry、ToolDispatcher、ToolDefinition、ToolObservation。
- 工具 schema validation、权限校验、timeout、retry 和 fallback 工具治理。
- MCP Client。
- 内置 RAG/Memory MCP Server。
- Mem0 风格分层记忆。
- Markdown 文档解析和结构化切分。
- Contextual Retrieval。
- pgvector 向量检索。
- Elasticsearch BM25。
- LLM Query Rewrite、意图识别、关键词抽取。
- Hybrid Search。
- Cross-encoder rerank endpoint 接入。
- Context Packing。
- Citation 生成。
- SSE 实时状态推送。
- Docker Compose 部署。

## 14. 当前仍可继续增强的能力

仍可增强：

- Spring AI Function Calling schema 已和 ToolDispatcher 主链路打通；后续可以继续补更细的工具审计面板。
- 工具 schema validation 已覆盖常见 JSON Schema 字段；后续可以接第三方完整 JSON Schema validator 支持 oneOf、anyOf、pattern 等高级能力。
- 权限体系目前是基础 permissionLevel，后续可以结合用户、Agent、租户和工具风险等级做细粒度授权。
- MCP 可以继续支持 stdio、SSE transport，而不仅是 HTTP JSON-RPC 风格。
- Rerank 需要实际部署 cross-encoder 服务，当前代码是 endpoint 接入。
- Memory 可以增强冲突检测、记忆合并、遗忘策略和人工管理页面。
- RAG 可以补 Recall@K、MRR、nDCG 等评测体系。
- ES 可以补显式 index mapping，而不是依赖自动字段推断。
- 前端可以补工具列表、MCP Server、记忆管理、Workflow Trace 可视化页面。

## 15. 面试讲法

可以这样总结项目：

```text
这个项目不是简单套壳大模型，而是围绕 Agent 执行闭环做了工程化设计。
用户请求进入系统后，会创建本轮 AgentExecutionState，加载会话摘要、长期记忆、可用工具和知识库。
简单任务走 ReAct，复杂任务走 Plan-and-Execute，生成结构化 Plan 和 PlanStep。
每一步执行后都会生成 StepResult，工具调用、RAG 检索、MCP 远程工具结果都会作为 Observation 写回状态对象。
长期记忆参考 Mem0 思路做分层存储和语义召回。
RAG 建库阶段做 Markdown 结构化切分、Contextual Retrieval、pgvector 向量索引和 ES BM25 索引。
查询阶段做 LLM Query Rewrite、向量召回、BM25 召回、混合排序、cross-encoder rerank 和 context packing。
最后通过 SSE 把 Agent 状态和生成内容实时推送给前端。
```

如果被问项目边界，可以这样回答：

```text
当前已经实现核心闭环，能完成 Agent 规划、工具调用、MCP 适配、记忆召回和 RAG 检索增强。
后续可以继续增强工具治理、MCP 多 transport、RAG 评测体系和记忆冲突处理。
```

package com.kama.jchatmind.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.CitationDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.dto.RagToolResponseDTO;
import com.kama.jchatmind.model.entity.ToolExecutionLog;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolExecutionLogService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    // 智能体 ID
    private String agentId;

    // 名称
    private String name;

    // 描述
    private String description;

    // 默认系统提示词
    private String systemPrompt;

    // 交互实例
    private ChatClient chatClient;

    // 状态
    private AgentState agentState;

    // 可用的工具
    private List<ToolCallback> availableTools;

    // 可访问的知识库
    private List<KnowledgeBaseDTO> availableKbs;

    // 工具调用管理器
    private ToolCallingManager toolCallingManager;

    // 模型的聊天记录
    private ChatMemory chatMemory;

    // 模型的聊天会话 ID
    private String chatSessionId;

    // 最多循环次数
    private static final Integer MAX_STEPS = 20;

    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    // SpringAI 自带的 ChatOptions, 不是 AgentDTO.ChatOptions
    private ChatOptions chatOptions;

    // SSE 服务, 用于发送消息给前端
    private SseService sseService;

    private ChatMessageConverter chatMessageConverter;

    private ChatMessageFacadeService chatMessageFacadeService;

    private ToolExecutionLogService toolExecutionLogService;

    private ObjectMapper objectMapper;

    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;

    private AgentDTO.ExecutionMode executionMode = AgentDTO.ExecutionMode.REACT;

    private String memoryPrompt;

    // AI 返回的，已经持久化，但是需要 sse 发给前端的消息
    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    private final List<CitationDTO> pendingCitations = new ArrayList<>();

    public JChatMind() {
    }

    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     ObjectMapper objectMapper,
                     ToolExecutionLogService toolExecutionLogService,
                     AgentDTO.ExecutionMode executionMode,
                     String memoryPrompt
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.chatClient = chatClient;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.sseService = sseService;

        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.objectMapper = objectMapper;
        this.toolExecutionLogService = toolExecutionLogService;
        this.executionMode = executionMode == null ? AgentDTO.ExecutionMode.REACT : executionMode;
        this.memoryPrompt = memoryPrompt;

        this.agentState = AgentState.IDLE;

        // 保存聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        // 添加系统提示
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        // 关闭 SpringAI 自带的内部的工具调用自动执行功能
        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        // 工具调用管理器
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    // 打印工具调用信息
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    // 持久化 Message, 返回 chatMessageId
    // 需要 Agent 持久化的 Message 子类有以下两类
    // AssistantMessage
    // ToolResponseMessage

    // SystemMessage 不需要持久化
    // UserMessage 在每次用户发送问题之间就已经持久化过了
    private void saveMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .citations(pendingCitations.isEmpty() ? null : new ArrayList<>(pendingCitations))
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
            pendingCitations.clear();
            if (assistantMessage.getToolCalls() == null || assistantMessage.getToolCalls().isEmpty()) {
                this.chatMemory.add(chatSessionId, assistantMessage);
            }
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            // 持久化 ToolResponseMessage
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
                chatMessageDTO.setId(chatMessage.getChatMessageId());
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("不支持的 Message 类型: " + message.getClass().getName());
        }
    }

    // 刷新 pendingMessages, 将数据通过 sse 发送给前端
    private void refreshPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            if (shouldStreamMessage(message)) {
                streamMessage(vo);
                continue;
            }
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }

    private boolean shouldStreamMessage(ChatMessageDTO message) {
        return message.getRole() == ChatMessageDTO.RoleType.ASSISTANT
                && StringUtils.hasLength(message.getContent())
                && (message.getMetadata() == null
                || message.getMetadata().getToolCalls() == null
                || message.getMetadata().getToolCalls().isEmpty());
    }

    private void streamMessage(ChatMessageVO vo) {
        sseService.send(this.chatSessionId, SseMessage.builder()
                .type(SseMessage.Type.AI_CONTENT_START)
                .payload(SseMessage.Payload.builder().message(vo).build())
                .metadata(SseMessage.Metadata.builder().chatMessageId(vo.getId()).build())
                .build());

        String content = vo.getContent();
        int chunkSize = 12;
        for (int i = 0; i < content.length(); i += chunkSize) {
            String delta = content.substring(i, Math.min(i + chunkSize, content.length()));
            sseService.send(this.chatSessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_CONTENT_DELTA)
                    .payload(SseMessage.Payload.builder().contentDelta(delta).build())
                    .metadata(SseMessage.Metadata.builder().chatMessageId(vo.getId()).build())
                    .build());
        }

        sseService.send(this.chatSessionId, SseMessage.builder()
                .type(SseMessage.Type.AI_CONTENT_DONE)
                .payload(SseMessage.Payload.builder().message(vo).done(true).build())
                .metadata(SseMessage.Metadata.builder().chatMessageId(vo.getId()).build())
                .build());
    }

    private void sendAgentStatus(SseMessage.Type type, String statusText) {
        if (sseService == null) {
            return;
        }
        sseService.send(this.chatSessionId, SseMessage.builder()
                .type(type)
                .payload(SseMessage.Payload.builder()
                        .statusText(statusText)
                        .done(type == SseMessage.Type.AI_CONTENT_ERROR)
                        .build())
                .build());
    }

    // thinkPrompt 应该放到 system 中还是
    private boolean think() {
        String thinkPrompt = """
                现在你是一个智能的的具体「决策模块」
                请根据当前对话上下文，决定下一步的动作。
                                
                【执行模式】
                %s

                【长期记忆】
                %s

                【额外信息】
                - 你目前拥有的知识库列表以及描述：%s
                - 如果有缺失的上下文时，优先从知识库中进行搜索
                - 如果知识库工具返回了带编号的来源，请在回答中用 [1] [2] 这样的标记标注关键结论依据
                """.formatted(modeInstruction(), StringUtils.hasLength(memoryPrompt) ? memoryPrompt : "暂无可用长期记忆。", this.availableKbs);

        // 将 thinkPrompt 通过 .user(thinkPrompt) 的方式构造进入 chatClient 中
        // 既能让每次 messageList 的最后一条是 本条提示词，
        // 又能够避免将 thinkPrompt 加入到聊天记录中
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        this.lastChatResponse = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .call()
                .chatClientResponse()
                .chatResponse();

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse
                .getResult()
                .getOutput();

        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        // 保存
        saveMessage(output);
        refreshPendingMessages();

        // 打印工具调用
        logToolCalls(toolCalls);

        // 如果工具调用不为空，则进入执行阶段
        return !toolCalls.isEmpty();
    }

    // 执行
    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        long startedAt = System.currentTimeMillis();
        sendAgentStatus(SseMessage.Type.AI_EXECUTING, "AI 正在调用工具");
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);
        long durationMs = System.currentTimeMillis() - startedAt;

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        collectRagCitations(toolResponseMessage);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));

        log.info("工具调用结果：{}", collect);

        // 保存工具调用
        saveMessage(toolResponseMessage);
        recordToolExecutionLogs(toolResponseMessage, durationMs);
        refreshPendingMessages();

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    private void recordToolExecutionLogs(ToolResponseMessage toolResponseMessage, long durationMs) {
        if (toolExecutionLogService == null) {
            return;
        }
        List<AssistantMessage.ToolCall> toolCalls = this.lastChatResponse == null
                ? List.of()
                : this.lastChatResponse.getResult().getOutput().getToolCalls();
        if (toolCalls == null) {
            toolCalls = List.of();
        }
        for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
            try {
                String arguments = toolCalls.stream()
                        .filter(call -> response.name().equals(call.name())
                                || (response.id() != null && response.id().equals(call.id())))
                        .findFirst()
                        .map(AssistantMessage.ToolCall::arguments)
                        .orElse("{}");
                toolExecutionLogService.record(ToolExecutionLog.builder()
                        .sessionId(chatSessionId)
                        .toolName(response.name())
                        .arguments(arguments)
                        .result(response.responseData())
                        .status("SUCCESS")
                        .durationMs(Math.toIntExact(Math.min(durationMs, Integer.MAX_VALUE)))
                        .retryCount(0)
                        .build());
            } catch (Exception e) {
                log.warn("记录工具执行日志失败: tool={}, error={}", response.name(), e.getMessage());
            }
        }
    }

    private void collectRagCitations(ToolResponseMessage toolResponseMessage) {
        for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
            try {
                RagToolResponseDTO ragResponse = objectMapper.readValue(response.responseData(), RagToolResponseDTO.class);
                if (ragResponse.getCitations() != null) {
                    pendingCitations.clear();
                    pendingCitations.addAll(ragResponse.getCitations());
                }
            } catch (Exception ignored) {
                // 旧格式工具结果保持兼容，不影响正常回答。
            }
        }
    }

    // 单个步骤模板
    private void step() {
        if (think()) {
            execute();
        } else { // 没有工具调用
            agentState = AgentState.FINISHED;
        }
    }

    private String modeInstruction() {
        return switch (executionMode) {
            case PLAN_AND_EXECUTE -> """
                    Plan-and-Execute：先使用规划结果拆解任务，再逐步调用工具执行；每一步尽量说明依据，执行完成后汇总。
                    如果计划中的某一步缺少证据，优先调用知识库或可用工具补全。
                    """;
            case REFLECTION -> """
                    Reflection：按 ReAct 完成任务后，在最终回答前做自检，检查是否遗漏用户目标、工具证据和引用来源。
                    如发现问题，给出修订后的答案；如无问题，保持答案简洁。
                    """;
            case REACT -> """
                    ReAct：在思考、工具调用、观察结果之间循环；能直接回答时直接回答，需要外部信息时调用工具。
                    """;
        };
    }

    private void producePlan() {
        sendAgentStatus(SseMessage.Type.AI_PLANNING, "AI 正在生成执行计划");
        String planPrompt = """
                你是中心调度 Planner，请根据用户目标、长期记忆、知识库和可用工具生成 3-6 步执行计划。
                输出要求：
                1. 每一步必须有明确目标。
                2. 标注需要调用的工具或知识库。
                3. 不要编造工具结果，计划完成后等待执行阶段处理。
                """;
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        ChatResponse planResponse = this.chatClient
                .prompt(prompt)
                .system(planPrompt)
                .call()
                .chatClientResponse()
                .chatResponse();

        if (planResponse == null || planResponse.getResult() == null) {
            return;
        }
        AssistantMessage planMessage = planResponse.getResult().getOutput();
        if (planMessage != null && StringUtils.hasLength(planMessage.getText())) {
            saveMessage(planMessage);
            refreshPendingMessages();
        }
    }

    private void reflect() {
        sendAgentStatus(SseMessage.Type.AI_THINKING, "AI 正在反思校验");
        String reflectionPrompt = """
                你是 Reflection 校验模块。请基于当前对话、工具结果和知识库引用检查最终回答：
                - 是否覆盖用户问题。
                - 是否存在没有依据的断言。
                - 是否需要补充来源标记。
                如果需要修订，请直接给出修订后的最终回答；如果无需修订，只输出“无需修订”。
                """;
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        ChatResponse reflectionResponse = this.chatClient
                .prompt(prompt)
                .system(reflectionPrompt)
                .call()
                .chatClientResponse()
                .chatResponse();

        if (reflectionResponse == null || reflectionResponse.getResult() == null) {
            return;
        }
        AssistantMessage reflectionMessage = reflectionResponse.getResult().getOutput();
        if (reflectionMessage == null || !StringUtils.hasLength(reflectionMessage.getText())) {
            return;
        }
        String text = reflectionMessage.getText().trim();
        if ("无需修订".equals(text)) {
            return;
        }
        saveMessage(reflectionMessage);
        refreshPendingMessages();
    }

    private void runLoop() {
        for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
            int currentStep = i + 1;
            sendAgentStatus(SseMessage.Type.AI_THINKING, "AI 正在思考第 " + currentStep + " 步");
            step();
            if (currentStep >= MAX_STEPS) {
                agentState = AgentState.FINISHED;
                log.warn("Max steps reached, stopping agent");
            }
        }
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            if (executionMode == AgentDTO.ExecutionMode.PLAN_AND_EXECUTE) {
                producePlan();
            }
            runLoop();
            if (executionMode == AgentDTO.ExecutionMode.REFLECTION) {
                reflect();
            }
            sendAgentStatus(SseMessage.Type.AI_DONE, "AI 已完成");
            agentState = AgentState.FINISHED;
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            try {
                sendAgentStatus(SseMessage.Type.AI_CONTENT_ERROR, "生成失败：" + e.getMessage());
            } catch (Exception ignored) {
                // SSE 连接可能已经断开，避免掩盖真正的生成异常。
            }
            throw new RuntimeException("Error running agent", e);
        }
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}

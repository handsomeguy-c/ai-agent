package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.workflow.ExpertAgentRoute;
import com.kama.jchatmind.model.workflow.PlanStep;
import com.kama.jchatmind.model.workflow.StepType;
import com.kama.jchatmind.service.ExpertAgentRouter;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ExpertAgentRouterImpl implements ExpertAgentRouter {

    @Override
    public ExpertAgentRoute route(PlanStep planStep) {
        StepType stepType = planStep == null || planStep.getStepType() == null
                ? StepType.DIRECT_ANSWER
                : planStep.getStepType();
        String expert = planStep != null && StringUtils.hasLength(planStep.getExpert())
                ? planStep.getExpert()
                : resolveExpert(stepType);
        return ExpertAgentRoute.builder()
                .expert(expert)
                .stepType(stepType)
                .profile(profile(expert, stepType))
                .routingPolicy(routingPolicy(stepType))
                .recommendedTool(planStep == null ? null : planStep.getToolName())
                .requiresToolCall(requiresToolCall(stepType))
                .readOnly(isReadOnly(stepType))
                .build();
    }

    private String resolveExpert(StepType stepType) {
        return switch (stepType) {
            case RETRIEVAL, MCP_TOOL_CALL -> "KnowledgeExpert";
            case MEMORY_READ, MEMORY_WRITE -> "MemoryExpert";
            case TOOL_CALL -> "ToolExpert";
            case SYNTHESIS, DIRECT_ANSWER -> "SynthesisExpert";
            case VERIFICATION -> "Verifier";
            case PLANNING -> "Planner";
        };
    }

    private String profile(String expert, StepType stepType) {
        String normalized = expert == null ? "" : expert;
        if (normalized.contains("KnowledgeExpert") || stepType == StepType.RETRIEVAL || stepType == StepType.MCP_TOOL_CALL) {
            return "KnowledgeExpert: 负责 RAG 检索、MCP RAG 工具、证据收集和引用依据。";
        }
        if (normalized.contains("MemoryExpert") || stepType == StepType.MEMORY_READ || stepType == StepType.MEMORY_WRITE) {
            return "MemoryExpert: 负责感知记忆、短期记忆、长期记忆和实体记忆的召回或写入。";
        }
        if (normalized.contains("ToolExpert") || stepType == StepType.TOOL_CALL) {
            return "ToolExpert: 负责 Function Calling 参数生成、本地工具和 MCP 工具调用。";
        }
        if (normalized.contains("SynthesisExpert") || stepType == StepType.SYNTHESIS || stepType == StepType.DIRECT_ANSWER) {
            return "SynthesisExpert: 负责聚合 StepResult、工具观察结果、记忆和检索证据生成答案。";
        }
        if (normalized.contains("Verifier") || stepType == StepType.VERIFICATION) {
            return "Verifier: 负责检查任务覆盖度、证据充分性、幻觉风险和终止条件。";
        }
        return "Planner: 负责拆解任务、选择专家和定义成功标准。";
    }

    private String routingPolicy(StepType stepType) {
        return switch (stepType) {
            case RETRIEVAL -> "route_to_rag_or_knowledge_tool";
            case MCP_TOOL_CALL -> "route_to_mcp_client";
            case MEMORY_READ, MEMORY_WRITE -> "route_to_memory_service_or_memory_mcp";
            case TOOL_CALL -> "route_to_tool_dispatcher";
            case SYNTHESIS, DIRECT_ANSWER -> "route_to_synthesis_context";
            case VERIFICATION -> "route_to_verifier";
            case PLANNING -> "route_to_planner";
        };
    }

    private boolean requiresToolCall(StepType stepType) {
        return stepType == StepType.RETRIEVAL
                || stepType == StepType.MCP_TOOL_CALL
                || stepType == StepType.MEMORY_READ
                || stepType == StepType.MEMORY_WRITE
                || stepType == StepType.TOOL_CALL;
    }

    private boolean isReadOnly(StepType stepType) {
        return stepType != StepType.MEMORY_WRITE && stepType != StepType.TOOL_CALL;
    }
}

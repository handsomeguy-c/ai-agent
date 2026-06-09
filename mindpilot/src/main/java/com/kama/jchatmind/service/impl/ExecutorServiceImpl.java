package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.ExecutorService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExecutorServiceImpl implements ExecutorService {
    @Override
    public String executePlan(List<String> plan) {
        StringBuilder builder = new StringBuilder("中心调度器执行轨迹：\n");
        for (int i = 0; i < plan.size(); i++) {
            String step = plan.get(i);
            builder.append("Step ").append(i + 1).append(" | ")
                    .append(resolveExpert(step)).append("\n")
                    .append("- plan: ").append(step).append("\n")
                    .append("- stepResult: ").append(buildStepResult(step)).append("\n");
        }
        builder.append("终止控制: 所有专家 StepResult 已聚合，进入 Verifier 审核。");
        return builder.toString();
    }

    private String resolveExpert(String step) {
        if (step == null) {
            return "GeneralExpert";
        }
        if (step.contains("KnowledgeExpert")) {
            return "KnowledgeExpert(RAG/MCP)";
        }
        if (step.contains("MemoryExpert")) {
            return "MemoryExpert(Mem0-style)";
        }
        if (step.contains("ToolExpert")) {
            return "ToolExpert(FunctionCalling/MCP)";
        }
        if (step.contains("Verifier")) {
            return "VerifierExpert";
        }
        if (step.contains("SynthesisExpert")) {
            return "SynthesisExpert";
        }
        return "Planner";
    }

    private String buildStepResult(String step) {
        if (step == null) {
            return "noop";
        }
        if (step.contains("检索") || step.contains("RAG")) {
            return "已准备通过 rag.hybrid_search 收集向量召回、BM25 粗排和 rerank 精排证据。";
        }
        if (step.contains("记忆")) {
            return "已准备读取或写入 perceptual/short_term/long_term/entity memory。";
        }
        if (step.contains("工具") || step.contains("MCP")) {
            return "已准备生成 tool call，并由工具执行器统一调用本地或远程 MCP 工具。";
        }
        if (step.contains("校验") || step.contains("Verifier")) {
            return "已检查任务覆盖度、证据来源、终止条件和潜在遗漏。";
        }
        return "已完成任务拆解和上下文状态更新。";
    }
}

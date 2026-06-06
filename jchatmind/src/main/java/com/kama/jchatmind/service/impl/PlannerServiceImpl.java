package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.service.PlannerService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class PlannerServiceImpl implements PlannerService {
    @Override
    public List<String> plan(String userInput) {
        String normalized = userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
        if (normalized.contains("知识库") || normalized.contains("rag") || normalized.contains("文档")) {
            return List.of(
                    "Planner: 识别用户问题、约束和需要检索的知识库范围",
                    "KnowledgeExpert: 调用 RAG/MCP 工具执行混合检索并收集证据",
                    "SynthesisExpert: 将检索片段、上下文索引和用户目标聚合为答案",
                    "Verifier: 检查答案是否有来源依据并标注引用"
            );
        }
        if (normalized.contains("记住") || normalized.contains("偏好") || normalized.contains("长期记忆")) {
            return List.of(
                    "Planner: 判断本轮输入是否需要写入记忆",
                    "MemoryExpert: 抽取偏好、事实和长期目标",
                    "MemoryExpert: 通过 MCP memory.save_long_term 写入长期记忆",
                    "Verifier: 校验记忆层级和写入结果"
            );
        }
        if (normalized.contains("工具") || normalized.contains("mcp") || normalized.contains("调用")) {
            return List.of(
                    "Planner: 拆解任务并选择本地工具或远程 MCP 工具",
                    "ToolExpert: 生成 tool call 参数并执行工具",
                    "SynthesisExpert: 根据工具观察结果汇总答案",
                    "Verifier: 检查工具结果是否满足用户目标"
            );
        }
        return List.of(
                "Planner: 理解用户问题并拆分执行步骤",
                "MemoryExpert: 召回感知、短期和长期记忆作为背景",
                "KnowledgeExpert: 必要时检索知识库补充证据",
                "SynthesisExpert: 生成最终回答",
                "Verifier: 校验覆盖度、依据和终止条件"
        );
    }
}

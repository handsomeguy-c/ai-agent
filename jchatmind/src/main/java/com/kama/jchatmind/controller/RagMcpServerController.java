package com.kama.jchatmind.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.RagSearchResultDTO;
import com.kama.jchatmind.model.entity.Memory;
import com.kama.jchatmind.service.HybridSearchService;
import com.kama.jchatmind.service.MemoryService;
import lombok.AllArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp")
@AllArgsConstructor
public class RagMcpServerController {

    private final HybridSearchService hybridSearchService;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    @PostMapping("/rag")
    public Map<String, Object> handle(@RequestBody Map<String, Object> request) {
        String method = String.valueOf(request.getOrDefault("method", ""));
        Object id = request.get("id");
        try {
            Object result = switch (method) {
                case "initialize" -> initialize();
                case "notifications/initialized" -> Map.of("status", "initialized");
                case "tools/list" -> Map.of("tools", tools());
                case "tools/call" -> callTool(asMap(request.get("params")));
                default -> throw new IllegalArgumentException("Unsupported MCP method: " + method);
            };
            return jsonRpc(id, result, null);
        } catch (Exception e) {
            return jsonRpc(id, null, Map.of(
                    "code", -32000,
                    "message", e.getMessage() == null ? e.toString() : e.getMessage()
            ));
        }
    }

    private Map<String, Object> initialize() {
        return Map.of(
                "protocolVersion", "2024-11-05",
                "capabilities", Map.of(
                        "tools", Map.of("listChanged", false)
                ),
                "serverInfo", Map.of(
                        "name", "jchatmind-rag-memory-mcp-server",
                        "version", "0.1.0"
                )
        );
    }

    private Object callTool(Map<String, Object> params) {
        String name = String.valueOf(params.getOrDefault("name", ""));
        Map<String, Object> arguments = asMap(params.get("arguments"));
        return switch (name) {
            case "rag.hybrid_search" -> ragHybridSearch(arguments);
            case "memory.save_long_term" -> saveLongTermMemory(arguments);
            case "memory.recall" -> recallMemory(arguments);
            default -> throw new IllegalArgumentException("Unknown MCP tool: " + name);
        };
    }

    private Map<String, Object> ragHybridSearch(Map<String, Object> arguments) {
        String kbId = stringArg(arguments, "kbId");
        String query = stringArg(arguments, "query");
        if (!StringUtils.hasText(kbId) || !StringUtils.hasText(query)) {
            throw new IllegalArgumentException("kbId and query are required");
        }
        List<RagSearchResultDTO> results = hybridSearchService.search(kbId, query);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "json",
                        "json", Map.of(
                                "query", query,
                                "results", results
                        )
                ))
        );
    }

    private Map<String, Object> saveLongTermMemory(Map<String, Object> arguments) {
        String agentId = stringArg(arguments, "agentId");
        String sessionId = stringArg(arguments, "sessionId");
        String content = stringArg(arguments, "content");
        Memory memory = memoryService.saveLongTermMemory(agentId, sessionId, content);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "text",
                        "text", "saved long-term memory: " + memory.getId()
                )),
                "memoryId", memory.getId()
        );
    }

    private Map<String, Object> recallMemory(Map<String, Object> arguments) {
        String agentId = stringArg(arguments, "agentId");
        String query = stringArg(arguments, "query");
        int limit = intArg(arguments, "limit", 5);
        List<Memory> memories = memoryService.recall(agentId, query, limit);
        return Map.of(
                "content", List.of(Map.of(
                        "type", "json",
                        "json", Map.of(
                                "query", query,
                                "memories", memories
                        )
                ))
        );
    }

    private List<Map<String, Object>> tools() {
        return List.of(
                tool("rag.hybrid_search", "执行 JChatMind RAG 混合检索，返回向量召回、BM25 粗排、rerank 精排后的片段。",
                        Map.of("kbId", "string", "query", "string")),
                tool("memory.save_long_term", "写入 Mem0 风格长期记忆，并生成向量用于后续召回。",
                        Map.of("agentId", "string", "sessionId", "string", "content", "string")),
                tool("memory.recall", "按语义相似度、重要性和时间衰减召回 Agent 记忆。",
                        Map.of("agentId", "string", "query", "string", "limit", "number"))
        );
    }

    private Map<String, Object> tool(String name, String description, Map<String, String> properties) {
        Map<String, Object> jsonProperties = new LinkedHashMap<>();
        properties.forEach((key, type) -> jsonProperties.put(key, Map.of("type", type)));
        return Map.of(
                "name", name,
                "description", description,
                "inputSchema", Map.of(
                        "type", "object",
                        "properties", jsonProperties,
                        "required", properties.keySet()
                )
        );
    }

    private Map<String, Object> jsonRpc(Object id, Object result, Object error) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        if (error != null) {
            response.put("error", error);
        } else {
            response.put("result", result);
        }
        return response;
    }

    private Map<String, Object> asMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        return objectMapper.convertValue(value, Map.class);
    }

    private String stringArg(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int intArg(Map<String, Object> arguments, String key, int defaultValue) {
        Object value = arguments.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}

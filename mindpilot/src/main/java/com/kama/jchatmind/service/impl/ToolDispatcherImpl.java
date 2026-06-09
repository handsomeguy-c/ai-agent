package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.McpRemoteTools;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.tool.PermissionLevel;
import com.kama.jchatmind.model.tool.ToolDefinition;
import com.kama.jchatmind.model.tool.ToolInvocation;
import com.kama.jchatmind.model.tool.ToolObservation;
import com.kama.jchatmind.model.tool.ToolSourceType;
import com.kama.jchatmind.service.ToolDispatcher;
import com.kama.jchatmind.service.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class ToolDispatcherImpl implements ToolDispatcher {

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final McpRemoteTools mcpRemoteTools;

    public ToolDispatcherImpl(
            ToolRegistry toolRegistry,
            ObjectMapper objectMapper,
            McpRemoteTools mcpRemoteTools
    ) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.mcpRemoteTools = mcpRemoteTools;
    }

    @Override
    public ToolObservation dispatch(ToolInvocation invocation) {
        ToolDefinition definition = toolRegistry.findByName(invocation.getToolName())
                .orElseThrow(() -> new BizException("工具不存在或未注册: " + invocation.getToolName()));
        long startedAt = System.currentTimeMillis();
        int retries = Math.max(0, definition.getMaxRetries() == null ? 0 : definition.getMaxRetries());
        int attempt = 0;
        Exception lastError = null;

        while (attempt <= retries) {
            try {
                validatePermission(definition, invocation);
                validateSchema(definition, invocation.getArguments());
                String result = invokeWithTimeout(definition, invocation);
                return ToolObservation.builder()
                        .toolName(definition.getToolName())
                        .sourceType(definition.getSourceType())
                        .result(result)
                        .status("SUCCESS")
                        .durationMs(System.currentTimeMillis() - startedAt)
                        .retryCount(attempt)
                        .fallbackUsed(false)
                        .build();
            } catch (Exception e) {
                lastError = e;
                attempt++;
                log.warn("工具调用失败，准备重试: tool={}, attempt={}, error={}",
                        definition.getToolName(), attempt, e.getMessage());
            }
        }

        return ToolObservation.builder()
                .toolName(definition.getToolName())
                .sourceType(definition.getSourceType())
                .status("FAILED")
                .errorMessage(lastError == null ? "unknown tool error" : lastError.getMessage())
                .durationMs(System.currentTimeMillis() - startedAt)
                .retryCount(Math.max(0, attempt - 1))
                .fallbackUsed(true)
                .result(buildFallbackResult(definition, lastError))
                .build();
    }

    private String invokeWithTimeout(ToolDefinition definition, ToolInvocation invocation) throws Exception {
        Callable<String> callable = () -> invoke(definition, invocation);
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(callable);
            return future.get(definition.getTimeoutMs(), TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private String invoke(ToolDefinition definition, ToolInvocation invocation) throws Exception {
        if (definition.getSourceType() == ToolSourceType.MCP) {
            return mcpRemoteTools.callRemoteTool(
                    definition.getServerName(),
                    definition.getRemoteToolName(),
                    objectMapper.writeValueAsString(invocation.getArguments() == null ? Map.of() : invocation.getArguments())
            );
        }
        if (definition.getLocalCallback() == null) {
            throw new BizException("本地工具缺少 ToolCallback: " + definition.getToolName());
        }
        return definition.getLocalCallback().call(
                objectMapper.writeValueAsString(invocation.getArguments() == null ? Map.of() : invocation.getArguments())
        );
    }

    private void validatePermission(ToolDefinition definition, ToolInvocation invocation) {
        if (definition.getPermissionLevel() == PermissionLevel.SENSITIVE
                && (invocation.getAgentId() == null || invocation.getAgentId().isBlank())) {
            throw new BizException("敏感工具需要 Agent 授权上下文: " + definition.getToolName());
        }
    }

    private void validateSchema(ToolDefinition definition, Map<String, Object> arguments) {
        if (definition.getInputSchema() == null || definition.getInputSchema().isBlank()) {
            return;
        }
        try {
            JsonNode schema = objectMapper.readTree(definition.getInputSchema());
            JsonNode argumentsNode = objectMapper.valueToTree(arguments == null ? Map.of() : arguments);
            JsonNode required = schema.path("required");
            if (required.isArray()) {
                for (JsonNode field : required) {
                    String fieldName = field.asText();
                    if (arguments == null || !arguments.containsKey(fieldName) || arguments.get(fieldName) == null) {
                        throw new BizException("工具参数缺少必填字段: " + fieldName);
                    }
                }
            }
            validateJsonSchema(schema, argumentsNode, "$");
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.debug("工具 schema 解析失败，跳过严格校验: tool={}, error={}", definition.getToolName(), e.getMessage());
        }
    }

    private void validateJsonSchema(JsonNode schema, JsonNode value, String path) {
        if (schema == null || schema.isMissingNode() || schema.isNull()) {
            return;
        }
        validateEnum(schema, value, path);
        validateType(schema, value, path);
        validateBounds(schema, value, path);

        String type = schema.path("type").asText("");
        if ("object".equals(type) || schema.has("properties")) {
            validateObject(schema, value, path);
        }
        if ("array".equals(type) && value.isArray()) {
            JsonNode itemSchema = schema.path("items");
            if (!itemSchema.isMissingNode()) {
                for (int i = 0; i < value.size(); i++) {
                    validateJsonSchema(itemSchema, value.get(i), path + "[" + i + "]");
                }
            }
        }
    }

    private void validateType(JsonNode schema, JsonNode value, String path) {
        JsonNode typeNode = schema.path("type");
        if (typeNode.isMissingNode()) {
            return;
        }
        if (typeNode.isArray()) {
            for (JsonNode allowedType : typeNode) {
                if (matchesType(value, allowedType.asText())) {
                    return;
                }
            }
            throw new BizException("工具参数类型不匹配: " + path + " 期望 " + typeNode);
        }
        String expectedType = typeNode.asText();
        if (!matchesType(value, expectedType)) {
            throw new BizException("工具参数类型不匹配: " + path + " 期望 " + expectedType);
        }
    }

    private boolean matchesType(JsonNode value, String expectedType) {
        return switch (expectedType) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    private void validateEnum(JsonNode schema, JsonNode value, String path) {
        JsonNode enumNode = schema.path("enum");
        if (!enumNode.isArray()) {
            return;
        }
        for (JsonNode allowed : enumNode) {
            if (Objects.equals(allowed, value)) {
                return;
            }
        }
        throw new BizException("工具参数枚举值非法: " + path + " = " + value);
    }

    private void validateBounds(JsonNode schema, JsonNode value, String path) {
        if (value.isTextual()) {
            int length = value.asText().length();
            if (schema.has("minLength") && length < schema.path("minLength").asInt()) {
                throw new BizException("工具参数长度不足: " + path);
            }
            if (schema.has("maxLength") && length > schema.path("maxLength").asInt()) {
                throw new BizException("工具参数长度超限: " + path);
            }
        }
        if (value.isNumber()) {
            double numericValue = value.asDouble();
            if (schema.has("minimum") && numericValue < schema.path("minimum").asDouble()) {
                throw new BizException("工具参数数值过小: " + path);
            }
            if (schema.has("maximum") && numericValue > schema.path("maximum").asDouble()) {
                throw new BizException("工具参数数值过大: " + path);
            }
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.path("minItems").asInt()) {
                throw new BizException("工具参数数组长度不足: " + path);
            }
            if (schema.has("maxItems") && value.size() > schema.path("maxItems").asInt()) {
                throw new BizException("工具参数数组长度超限: " + path);
            }
        }
    }

    private void validateObject(JsonNode schema, JsonNode value, String path) {
        if (!value.isObject()) {
            return;
        }
        JsonNode properties = schema.path("properties");
        if (!properties.isObject()) {
            return;
        }
        properties.fields().forEachRemaining(entry -> {
            JsonNode childValue = value.get(entry.getKey());
            if (childValue != null && !childValue.isNull()) {
                validateJsonSchema(entry.getValue(), childValue, path + "." + entry.getKey());
            }
        });
    }

    private String buildFallbackResult(ToolDefinition definition, Exception error) {
        return "工具调用失败，已触发 fallback。tool=%s, sourceType=%s, error=%s"
                .formatted(
                        definition.getToolName(),
                        definition.getSourceType(),
                        error == null ? "unknown" : error.getMessage()
                );
    }
}

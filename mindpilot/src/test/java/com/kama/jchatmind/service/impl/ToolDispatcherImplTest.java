package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.ToolType;
import com.kama.jchatmind.model.tool.PermissionLevel;
import com.kama.jchatmind.model.tool.ToolDefinition;
import com.kama.jchatmind.model.tool.ToolInvocation;
import com.kama.jchatmind.model.tool.ToolObservation;
import com.kama.jchatmind.model.tool.ToolSourceType;
import com.kama.jchatmind.service.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ToolDispatcherImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldInvokeLocalToolWhenSchemaIsValid() {
        ToolDefinition definition = baseDefinition()
                .localCallback(callbackReturning("echo-ok"))
                .build();
        ToolDispatcherImpl dispatcher = dispatcherWith(definition);

        ToolObservation observation = dispatcher.dispatch(ToolInvocation.builder()
                .toolName("echo")
                .agentId("agent-1")
                .arguments(Map.of("query", "hello"))
                .build());

        assertThat(observation.getStatus()).isEqualTo("SUCCESS");
        assertThat(observation.getResult()).isEqualTo("echo-ok");
        assertThat(observation.getFallbackUsed()).isFalse();
        assertThat(observation.getRetryCount()).isZero();
    }

    @Test
    void shouldReturnFallbackObservationWhenRequiredFieldMissing() {
        ToolDefinition definition = baseDefinition().build();
        ToolDispatcherImpl dispatcher = dispatcherWith(definition);

        ToolObservation observation = dispatcher.dispatch(ToolInvocation.builder()
                .toolName("echo")
                .agentId("agent-1")
                .arguments(Map.of())
                .build());

        assertThat(observation.getStatus()).isEqualTo("FAILED");
        assertThat(observation.getFallbackUsed()).isTrue();
        assertThat(observation.getErrorMessage()).contains("工具参数缺少必填字段: query");
        assertThat(observation.getResult()).contains("工具调用失败，已触发 fallback");
    }

    @Test
    void shouldReturnFallbackObservationWhenSensitiveToolHasNoAgentContext() {
        ToolDefinition definition = baseDefinition()
                .permissionLevel(PermissionLevel.SENSITIVE)
                .build();
        ToolDispatcherImpl dispatcher = dispatcherWith(definition);

        ToolObservation observation = dispatcher.dispatch(ToolInvocation.builder()
                .toolName("echo")
                .arguments(Map.of("query", "hello"))
                .build());

        assertThat(observation.getStatus()).isEqualTo("FAILED");
        assertThat(observation.getFallbackUsed()).isTrue();
        assertThat(observation.getErrorMessage()).contains("敏感工具需要 Agent 授权上下文");
    }

    private ToolDefinition.ToolDefinitionBuilder baseDefinition() {
        return ToolDefinition.builder()
                .toolName("echo")
                .description("Echo test tool")
                .inputSchema("""
                        {
                          "type": "object",
                          "properties": {
                            "query": {"type": "string", "minLength": 1}
                          },
                          "required": ["query"]
                        }
                        """)
                .type(ToolType.FIXED)
                .sourceType(ToolSourceType.LOCAL)
                .source("test")
                .timeoutMs(1000L)
                .maxRetries(0)
                .permissionLevel(PermissionLevel.PUBLIC)
                .returnDirect(false)
                .localCallback(callbackReturning("unused"));
    }

    private ToolDispatcherImpl dispatcherWith(ToolDefinition definition) {
        ToolRegistry registry = new ToolRegistry() {
            @Override
            public List<ToolDefinition> listDefinitions() {
                return List.of(definition);
            }

            @Override
            public Optional<ToolDefinition> findByName(String toolName) {
                return definition.getToolName().equals(toolName) ? Optional.of(definition) : Optional.empty();
            }
        };
        return new ToolDispatcherImpl(registry, objectMapper, null);
    }

    private ToolCallback callbackReturning(String result) {
        return new ToolCallback() {
            @Override
            public String call(String toolInput) {
                return result;
            }

            @Override
            public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("echo")
                        .description("Echo test tool")
                        .inputSchema("{}")
                        .build();
            }
        };
    }
}

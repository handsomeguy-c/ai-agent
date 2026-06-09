package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.model.tool.ToolDefinition;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Exposes ToolRegistry definitions to the LLM while execution is handled by ToolDispatcher.
 */
public class RegistryToolCallback implements ToolCallback {

    private final ToolDefinition definition;

    public RegistryToolCallback(ToolDefinition definition) {
        this.definition = definition;
    }

    @Override
    public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
        return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name(definition.getToolName())
                .description(definition.getDescription())
                .inputSchema(definition.getInputSchema())
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return ToolMetadata.builder()
                .returnDirect(Boolean.TRUE.equals(definition.getReturnDirect()))
                .build();
    }

    @Override
    public String call(String toolInput) {
        throw new UnsupportedOperationException("Tool execution is routed through ToolDispatcher.");
    }
}

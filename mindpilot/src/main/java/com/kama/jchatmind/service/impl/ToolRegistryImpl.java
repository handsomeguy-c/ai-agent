package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.tools.McpRemoteTools;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import com.kama.jchatmind.model.dto.ToolSchemaDTO;
import com.kama.jchatmind.model.tool.PermissionLevel;
import com.kama.jchatmind.model.tool.ToolDefinition;
import com.kama.jchatmind.model.tool.ToolSourceType;
import com.kama.jchatmind.service.ToolRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ToolRegistryImpl implements ToolRegistry {

    private static final long DEFAULT_TIMEOUT_MS = 10_000L;
    private static final int DEFAULT_MAX_RETRIES = 1;

    private final List<Tool> tools;

    public ToolRegistryImpl(List<Tool> tools) {
        this.tools = tools;
    }

    @Override
    public List<ToolDefinition> listDefinitions() {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        for (Tool tool : tools) {
            for (ToolCallback callback : buildToolCallbacks(tool)) {
                definitions.put(callback.getToolDefinition().name(), ToolDefinition.builder()
                        .toolName(callback.getToolDefinition().name())
                        .description(callback.getToolDefinition().description())
                        .inputSchema(callback.getToolDefinition().inputSchema())
                        .type(tool.getType())
                        .sourceType(ToolSourceType.LOCAL)
                        .source(tool.getName())
                        .timeoutMs(DEFAULT_TIMEOUT_MS)
                        .maxRetries(DEFAULT_MAX_RETRIES)
                        .permissionLevel(resolvePermissionLevel(tool, callback.getToolDefinition().name()))
                        .returnDirect(callback.getToolMetadata().returnDirect())
                        .localCallback(callback)
                        .build());
            }
            if (tool instanceof McpRemoteTools mcpRemoteTools) {
                for (ToolSchemaDTO remoteSchema : mcpRemoteTools.listRemoteToolSchemas()) {
                    RemoteToolName remoteToolName = parseRemoteToolName(remoteSchema.getName());
                    definitions.put(remoteSchema.getName(), ToolDefinition.builder()
                            .toolName(remoteSchema.getName())
                            .description(remoteSchema.getDescription())
                            .inputSchema(remoteSchema.getInputSchema())
                            .type(ToolType.OPTIONAL)
                            .sourceType(ToolSourceType.MCP)
                            .source(remoteSchema.getSource())
                            .serverName(remoteToolName.serverName())
                            .remoteToolName(remoteToolName.toolName())
                            .timeoutMs(DEFAULT_TIMEOUT_MS)
                            .maxRetries(DEFAULT_MAX_RETRIES)
                            .permissionLevel(PermissionLevel.AGENT_ALLOWED)
                            .returnDirect(false)
                            .build());
                }
            }
        }
        return new ArrayList<>(definitions.values());
    }

    @Override
    public Optional<ToolDefinition> findByName(String toolName) {
        return listDefinitions().stream()
                .filter(definition -> definition.getToolName().equals(toolName))
                .findFirst();
    }

    private List<ToolCallback> buildToolCallbacks(Tool tool) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tool)
                .build()
                .getToolCallbacks();
        return Arrays.asList(callbacks);
    }

    private PermissionLevel resolvePermissionLevel(Tool tool, String toolName) {
        String lower = (tool.getName() + " " + toolName).toLowerCase();
        if (lower.contains("file") || lower.contains("email") || lower.contains("database")) {
            return PermissionLevel.SENSITIVE;
        }
        return tool.getType() == ToolType.FIXED ? PermissionLevel.PUBLIC : PermissionLevel.AGENT_ALLOWED;
    }

    private RemoteToolName parseRemoteToolName(String schemaName) {
        if (schemaName == null || !schemaName.startsWith("mcp.")) {
            return new RemoteToolName("", schemaName);
        }
        String remaining = schemaName.substring("mcp.".length());
        int split = remaining.indexOf('.');
        if (split < 0) {
            return new RemoteToolName(remaining, remaining);
        }
        return new RemoteToolName(remaining.substring(0, split), remaining.substring(split + 1));
    }

    private record RemoteToolName(String serverName, String toolName) {
    }
}

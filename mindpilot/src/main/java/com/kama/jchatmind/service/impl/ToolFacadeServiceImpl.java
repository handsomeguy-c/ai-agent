package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import com.kama.jchatmind.model.dto.ToolCallResponseDTO;
import com.kama.jchatmind.model.dto.ToolSchemaDTO;
import com.kama.jchatmind.model.tool.ToolDefinition;
import com.kama.jchatmind.model.tool.ToolInvocation;
import com.kama.jchatmind.model.tool.ToolObservation;
import com.kama.jchatmind.service.ToolDispatcher;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.service.ToolRegistry;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
public class ToolFacadeServiceImpl implements ToolFacadeService {

    private final List<Tool> tools;
    private final ToolRegistry toolRegistry;
    private final ToolDispatcher toolDispatcher;

    @Override
    public List<Tool> getAllTools() {
        return tools;
    }

    @Override
    public List<Tool> getOptionalTools() {
        return getToolsByType(ToolType.OPTIONAL);
    }

    @Override
    public List<Tool> getFixedTools() {
        return getToolsByType(ToolType.FIXED);
    }

    @Override
    public List<ToolSchemaDTO> listToolSchemas() {
        return toolRegistry.listDefinitions().stream()
                .map(this::toSchemaDTO)
                .toList();
    }

    @Override
    public ToolCallResponseDTO callTool(String name, Map<String, Object> arguments) {
        ToolObservation observation = toolDispatcher.dispatch(ToolInvocation.builder()
                .toolName(name)
                .arguments(arguments == null ? Map.of() : arguments)
                .build());
        return ToolCallResponseDTO.builder()
                .name(observation.getToolName())
                .result(observation.getResult())
                .status(observation.getStatus())
                .errorMessage(observation.getErrorMessage())
                .durationMs(observation.getDurationMs())
                .retryCount(observation.getRetryCount())
                .fallbackUsed(observation.getFallbackUsed())
                .build();
    }

    private List<Tool> getToolsByType(ToolType type) {
        return tools.stream()
                .filter(tool -> tool.getType().equals(type))
                .toList();
    }

    private ToolSchemaDTO toSchemaDTO(ToolDefinition definition) {
        return ToolSchemaDTO.builder()
                .name(definition.getToolName())
                .description(definition.getDescription())
                .type(definition.getType())
                .source(definition.getSource())
                .sourceType(definition.getSourceType())
                .inputSchema(definition.getInputSchema())
                .timeoutMs(definition.getTimeoutMs())
                .maxRetries(definition.getMaxRetries())
                .permissionLevel(definition.getPermissionLevel())
                .returnDirect(definition.getReturnDirect())
                .build();
    }
}

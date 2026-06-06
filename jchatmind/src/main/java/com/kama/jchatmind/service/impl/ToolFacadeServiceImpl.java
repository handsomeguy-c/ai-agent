package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.tools.McpRemoteTools;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.agent.tools.ToolType;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.dto.ToolCallResponseDTO;
import com.kama.jchatmind.model.dto.ToolSchemaDTO;
import com.kama.jchatmind.service.ToolFacadeService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
@Slf4j
public class ToolFacadeServiceImpl implements ToolFacadeService {

    private final List<Tool> tools;
    private final ObjectMapper objectMapper;

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
        List<ToolSchemaDTO> schemas = new ArrayList<>();
        for (Tool tool : tools) {
            for (ToolCallback callback : buildToolCallbacks(tool)) {
                schemas.add(ToolSchemaDTO.builder()
                        .name(callback.getToolDefinition().name())
                        .description(callback.getToolDefinition().description())
                        .type(tool.getType())
                        .source(tool.getName())
                        .inputSchema(callback.getToolDefinition().inputSchema())
                        .returnDirect(callback.getToolMetadata().returnDirect())
                        .build());
            }
            if (tool instanceof McpRemoteTools mcpRemoteTools) {
                schemas.addAll(mcpRemoteTools.listRemoteToolSchemas());
            }
        }
        return schemas;
    }

    @Override
    public ToolCallResponseDTO callTool(String name, Map<String, Object> arguments) {
        ToolCallback callback = callbackIndex().get(name);
        if (callback == null) {
            throw new BizException("工具不存在或未注册: " + name);
        }
        try {
            String result = callback.call(toJson(arguments == null ? Map.of() : arguments));
            return ToolCallResponseDTO.builder()
                    .name(name)
                    .result(result)
                    .status("SUCCESS")
                    .build();
        } catch (Exception e) {
            log.warn("工具调用失败: name={}, arguments={}, error={}", name, arguments, e.toString(), e);
            return ToolCallResponseDTO.builder()
                    .name(name)
                    .status("FAILED")
                    .errorMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                    .build();
        }
    }

    private List<Tool> getToolsByType(ToolType type) {
        return tools.stream()
                .filter(tool -> tool.getType().equals(type))
                .toList();
    }

    private Map<String, ToolCallback> callbackIndex() {
        Map<String, ToolCallback> callbacks = new LinkedHashMap<>();
        for (Tool tool : tools) {
            for (ToolCallback callback : buildToolCallbacks(tool)) {
                callbacks.put(callback.getToolDefinition().name(), callback);
            }
        }
        return callbacks;
    }

    private List<ToolCallback> buildToolCallbacks(Tool tool) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(tool)
                .build()
                .getToolCallbacks();
        return Arrays.asList(callbacks);
    }

    private String toJson(Map<String, Object> arguments) throws JsonProcessingException {
        return objectMapper.writeValueAsString(arguments);
    }
}

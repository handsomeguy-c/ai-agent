package com.kama.jchatmind.service;

import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.model.dto.ToolCallResponseDTO;
import com.kama.jchatmind.model.dto.ToolSchemaDTO;

import java.util.Map;

import java.util.List;

public interface ToolFacadeService {
    List<Tool> getAllTools();

    List<Tool> getOptionalTools();

    List<Tool> getFixedTools();

    List<ToolSchemaDTO> listToolSchemas();

    ToolCallResponseDTO callTool(String name, Map<String, Object> arguments);
}

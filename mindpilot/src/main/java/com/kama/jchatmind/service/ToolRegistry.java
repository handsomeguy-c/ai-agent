package com.kama.jchatmind.service;

import com.kama.jchatmind.model.tool.ToolDefinition;

import java.util.List;
import java.util.Optional;

public interface ToolRegistry {
    List<ToolDefinition> listDefinitions();

    Optional<ToolDefinition> findByName(String toolName);
}

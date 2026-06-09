package com.kama.jchatmind.model.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum StepType {
    PLANNING,
    RETRIEVAL,
    TOOL_CALL,
    MCP_TOOL_CALL,
    MEMORY_READ,
    MEMORY_WRITE,
    SYNTHESIS,
    VERIFICATION,
    DIRECT_ANSWER;

    @JsonCreator
    public static StepType from(String value) {
        if (value == null || value.isBlank()) {
            return DIRECT_ANSWER;
        }
        String normalized = value.trim()
                .replace("-", "_")
                .replace(" ", "_")
                .toUpperCase();
        for (StepType stepType : values()) {
            if (stepType.name().equals(normalized)) {
                return stepType;
            }
        }
        if (normalized.contains("RAG") || normalized.contains("RETRIEVAL")) {
            return RETRIEVAL;
        }
        if (normalized.contains("MCP")) {
            return MCP_TOOL_CALL;
        }
        if (normalized.contains("MEMORY") && normalized.contains("WRITE")) {
            return MEMORY_WRITE;
        }
        if (normalized.contains("MEMORY")) {
            return MEMORY_READ;
        }
        if (normalized.contains("TOOL")) {
            return TOOL_CALL;
        }
        if (normalized.contains("VERIFY")) {
            return VERIFICATION;
        }
        if (normalized.contains("SYNTHESIS")) {
            return SYNTHESIS;
        }
        return DIRECT_ANSWER;
    }
}

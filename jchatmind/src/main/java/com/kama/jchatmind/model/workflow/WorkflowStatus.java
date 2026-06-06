package com.kama.jchatmind.model.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum WorkflowStatus {
    CREATED,
    PLANNING,
    EXECUTING,
    VERIFYING,
    REPLANNING,
    FINISHED,
    FAILED;

    @JsonCreator
    public static WorkflowStatus from(String value) {
        if (value == null || value.isBlank()) {
            return CREATED;
        }
        String normalized = value.trim().replace("-", "_").replace(" ", "_").toUpperCase();
        for (WorkflowStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        return CREATED;
    }
}

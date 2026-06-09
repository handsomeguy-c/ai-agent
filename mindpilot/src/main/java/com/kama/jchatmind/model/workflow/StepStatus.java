package com.kama.jchatmind.model.workflow;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum StepStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED;

    @JsonCreator
    public static StepStatus from(String value) {
        if (value == null || value.isBlank()) {
            return PENDING;
        }
        String normalized = value.trim().replace("-", "_").replace(" ", "_").toUpperCase();
        for (StepStatus status : values()) {
            if (status.name().equals(normalized)) {
                return status;
            }
        }
        return PENDING;
    }
}

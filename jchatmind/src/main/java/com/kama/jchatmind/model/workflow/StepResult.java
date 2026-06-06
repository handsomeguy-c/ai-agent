package com.kama.jchatmind.model.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepResult {
    private String stepId;
    private StepType stepType;
    private String expert;
    private StepStatus status;
    private String observation;
    private Object output;
    private String errorMessage;
    private Long durationMs;
    private Map<String, Object> metadata;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}

package com.kama.jchatmind.model.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanStep {
    private String stepId;
    private StepType stepType;
    private String expert;
    private String description;
    private String toolName;
    private Map<String, Object> input;
    private String expectedOutput;
    private StepStatus status;
}

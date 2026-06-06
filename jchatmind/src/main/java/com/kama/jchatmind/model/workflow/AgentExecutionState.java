package com.kama.jchatmind.model.workflow;

import com.kama.jchatmind.model.dto.AgentDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionState {
    @Builder.Default
    private String executionId = UUID.randomUUID().toString();
    private String agentId;
    private String sessionId;
    private String userInput;
    private AgentDTO.ExecutionMode executionMode;
    private WorkflowStatus status;
    private Plan plan;
    private ContextState contextState;
    @Builder.Default
    private List<StepResult> stepResults = new ArrayList<>();
    private Integer currentStepIndex;
    @Builder.Default
    private Integer replanCount = 0;
    @Builder.Default
    private Integer maxReplans = 1;
    private String finalAnswer;
    private String terminationReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public void addStepResult(StepResult result) {
        this.stepResults.add(result);
        this.updatedAt = LocalDateTime.now();
    }
}

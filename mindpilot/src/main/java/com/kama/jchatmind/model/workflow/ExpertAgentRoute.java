package com.kama.jchatmind.model.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExpertAgentRoute {
    private String expert;
    private StepType stepType;
    private String profile;
    private String routingPolicy;
    private String recommendedTool;
    private boolean requiresToolCall;
    private boolean readOnly;

    public Map<String, Object> toMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("delegatedBy", "CentralDispatcher");
        metadata.put("expert", expert);
        metadata.put("expertProfile", profile);
        metadata.put("routingPolicy", routingPolicy);
        metadata.put("recommendedTool", recommendedTool == null ? "" : recommendedTool);
        metadata.put("requiresToolCall", requiresToolCall);
        metadata.put("readOnly", readOnly);
        return metadata;
    }
}

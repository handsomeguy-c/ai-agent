package com.kama.jchatmind.model.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolInvocation {
    private String toolName;
    private Map<String, Object> arguments;
    private String agentId;
    private String sessionId;
}

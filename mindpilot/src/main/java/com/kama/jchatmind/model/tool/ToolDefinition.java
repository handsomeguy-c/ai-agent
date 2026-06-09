package com.kama.jchatmind.model.tool;

import com.kama.jchatmind.agent.tools.ToolType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.tool.ToolCallback;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {
    private String toolName;
    private String description;
    private String inputSchema;
    private ToolType type;
    private ToolSourceType sourceType;
    private String source;
    private String serverName;
    private String remoteToolName;
    private Long timeoutMs;
    private Integer maxRetries;
    private PermissionLevel permissionLevel;
    private Boolean returnDirect;
    private ToolCallback localCallback;
}

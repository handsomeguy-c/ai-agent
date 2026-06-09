package com.kama.jchatmind.model.dto;

import com.kama.jchatmind.agent.tools.ToolType;
import com.kama.jchatmind.model.tool.PermissionLevel;
import com.kama.jchatmind.model.tool.ToolSourceType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSchemaDTO {
    private String name;
    private String description;
    private ToolType type;
    private String source;
    private ToolSourceType sourceType;
    private String inputSchema;
    private Long timeoutMs;
    private Integer maxRetries;
    private PermissionLevel permissionLevel;
    private Boolean returnDirect;
}

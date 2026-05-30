package com.kama.jchatmind.model.dto;

import com.kama.jchatmind.agent.tools.ToolType;
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
    private String inputSchema;
    private Boolean returnDirect;
}

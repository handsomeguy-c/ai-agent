package com.kama.jchatmind.model.tool;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolObservation {
    private String toolName;
    private ToolSourceType sourceType;
    private String result;
    private String status;
    private String errorMessage;
    private Long durationMs;
    private Integer retryCount;
    private Boolean fallbackUsed;
}

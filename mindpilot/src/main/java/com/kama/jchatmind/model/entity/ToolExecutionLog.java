package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ToolExecutionLog {
    private String id;
    private String sessionId;
    private String messageId;
    private String toolName;
    private String arguments;
    private String result;
    private String status;
    private Integer durationMs;
    private String errorMessage;
    private Integer retryCount;
    private LocalDateTime createdAt;
}

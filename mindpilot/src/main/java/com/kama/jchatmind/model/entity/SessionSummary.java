package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SessionSummary {
    private String id;
    private String sessionId;
    private String summary;
    private String coveredUntilMessageId;
    private Integer coveredMessageCount;
    private Integer tokenEstimate;
    private Integer version;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

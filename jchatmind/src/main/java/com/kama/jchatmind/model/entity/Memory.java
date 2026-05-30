package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Memory {
    private String id;
    private String userId;
    private String agentId;
    private String type;
    private String content;
    private String sourceSessionId;
    private String sourceMessageIds;
    private Double importanceScore;
    private String status;
    private float[] embedding;
    private String metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastAccessedAt;
}

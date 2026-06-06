package com.kama.jchatmind.model.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Plan {
    private String planId;
    private String goal;
    private Integer version;
    @Builder.Default
    private List<PlanStep> steps = new ArrayList<>();
    private String createdBy;
    private LocalDateTime createdAt;
    private String replanReason;
}

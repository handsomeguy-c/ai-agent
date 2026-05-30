package com.kama.jchatmind.model.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AgentDTO {
    private String id;

    private String name;

    private String description;

    private String systemPrompt;

    private ModelType model;

    private List<String> allowedTools;

    private List<String> allowedKbs;

    private ChatOptions chatOptions;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Getter
    @AllArgsConstructor
    public enum ModelType {
        DEEPSEEK_CHAT("deepseek-chat"),
        GLM_4_6("glm-4.6");

        @JsonValue
        private final String modelName;

        public static ModelType fromModelName(String modelName) {
            for (ModelType type : ModelType.values()) {
                if (type.modelName.equals(modelName)) {
                    return type;
                }
            }
            throw new IllegalArgumentException("Unknown model type: " + modelName);
        }
    }

    @Data
    @AllArgsConstructor
    @Builder
    public static class ChatOptions {
        private Double temperature;
        private Double topP;
        private Integer messageLength; // 聊天消息窗口长度
        private ExecutionMode executionMode; // Agent 执行模式

        private static final Double DEFAULT_TEMPERATURE = 0.7;
        private static final Double DEFAULT_TOP_P = 1.0;
        private static final Integer DEFAULT_MESSAGE_LENGTH = 10;
        private static final ExecutionMode DEFAULT_EXECUTION_MODE = ExecutionMode.REACT;

        public static ChatOptions defaultOptions() {
            return ChatOptions.builder()
                    .temperature(DEFAULT_TEMPERATURE)
                    .topP(DEFAULT_TOP_P)
                    .messageLength(DEFAULT_MESSAGE_LENGTH)
                    .executionMode(DEFAULT_EXECUTION_MODE)
                    .build();
        }
    }

    @Getter
    @AllArgsConstructor
    public enum ExecutionMode {
        REACT("react"),
        PLAN_AND_EXECUTE("plan-and-execute"),
        REFLECTION("reflection");

        @JsonValue
        private final String modeName;

        @JsonCreator
        public static ExecutionMode fromModeName(String modeName) {
            if (modeName == null || modeName.isBlank()) {
                return REACT;
            }
            for (ExecutionMode mode : ExecutionMode.values()) {
                if (mode.modeName.equalsIgnoreCase(modeName) || mode.name().equalsIgnoreCase(modeName)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("Unknown execution mode: " + modeName);
        }
    }
}

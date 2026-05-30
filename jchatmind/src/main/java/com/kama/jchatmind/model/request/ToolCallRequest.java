package com.kama.jchatmind.model.request;

import lombok.Data;

import java.util.Map;

@Data
public class ToolCallRequest {
    private String name;
    private Map<String, Object> arguments;
}

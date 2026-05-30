package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallResponseDTO {
    private String name;
    private String result;
    private String status;
    private String errorMessage;
}

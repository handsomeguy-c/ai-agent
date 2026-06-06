package com.kama.jchatmind.model.workflow;

import com.kama.jchatmind.model.dto.CitationDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContextState {
    private String userInput;
    private String memoryPrompt;
    @Builder.Default
    private List<String> availableToolNames = new ArrayList<>();
    @Builder.Default
    private List<String> availableKnowledgeBaseIds = new ArrayList<>();
    @Builder.Default
    private List<CitationDTO> citations = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> scratchpad = new LinkedHashMap<>();
}

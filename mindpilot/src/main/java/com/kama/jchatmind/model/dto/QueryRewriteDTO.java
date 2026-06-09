package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryRewriteDTO {
    private String originalQuery;
    private String normalizedQuery;
    private String intent;
    private List<String> keywords;
    private List<String> rewrites;
}

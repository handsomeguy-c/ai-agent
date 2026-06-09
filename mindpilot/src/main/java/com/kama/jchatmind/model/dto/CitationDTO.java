package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CitationDTO {
    private Integer index;
    private String chunkId;
    private String kbId;
    private String docId;
    private String docName;
    private String title;
    private Double score;
    private String snippet;
}

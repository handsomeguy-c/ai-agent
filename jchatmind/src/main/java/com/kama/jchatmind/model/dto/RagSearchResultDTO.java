package com.kama.jchatmind.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchResultDTO {
    private String chunkId;
    private String kbId;
    private String docId;
    private String docName;
    private String content;
    private Double distance;
    private Double score;
    private ChunkMeta metadata;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChunkMeta {
        private String title;
        private Integer headingLevel;
        private Integer chunkIndex;
        private Integer charStart;
        private Integer charEnd;
        private Integer tokenCount;
        private String sourceFileName;
    }
}

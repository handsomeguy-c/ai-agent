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
        private String sectionPath;
        private String contextualSummary;
        private String previousTitle;
        private String nextTitle;
        private Integer headingLevel;
        private Integer chunkIndex;
        private Integer charStart;
        private Integer charEnd;
        private Integer tokenCount;
        private String sourceFileName;
        private List<String> retrievalSources;
        private Integer vectorRank;
        private Integer keywordRank;
        private Double vectorScore;
        private Double keywordScore;
        private Double rrfScore;
        private Double hybridScore;
        private String fusionMethod;
    }
}

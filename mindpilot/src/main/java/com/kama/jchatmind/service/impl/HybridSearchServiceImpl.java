package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.QueryRewriteDTO;
import com.kama.jchatmind.model.dto.RagSearchResultDTO;
import com.kama.jchatmind.service.HybridSearchService;
import com.kama.jchatmind.service.QueryRewriteService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.service.RerankService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@AllArgsConstructor
@Slf4j
public class HybridSearchServiceImpl implements HybridSearchService {

    private static final int VECTOR_LIMIT = 8;
    private static final int KEYWORD_LIMIT = 8;
    private static final int RRF_K = 60;
    private static final int FINAL_LIMIT = 6;

    private final RagService ragService;
    private final QueryRewriteService queryRewriteService;
    private final RerankService rerankService;

    @Override
    public List<RagSearchResultDTO> search(String kbId, String query) {
        QueryRewriteDTO queryProfile = queryRewriteService.analyze(query);
        Map<String, RagSearchResultDTO> merged = new LinkedHashMap<>();
        Map<String, FusionStats> fusionStats = new LinkedHashMap<>();

        List<String> rewrites = queryProfile.getRewrites() == null ? List.of(query) : queryProfile.getRewrites();
        for (int i = 0; i < Math.min(3, rewrites.size()); i++) {
            try {
                List<RagSearchResultDTO> vectorResults = ragService.similaritySearch(kbId, rewrites.get(i), VECTOR_LIMIT);
                addRankedResults(merged, fusionStats, vectorResults, RetrievalSource.VECTOR, 0.65);
            } catch (Exception e) {
                log.warn("向量召回失败，保留关键词召回结果: {}", e.getMessage());
            }
        }

        try {
            List<RagSearchResultDTO> keywordResults = ragService.keywordSearch(
                    kbId,
                    queryProfile.getNormalizedQuery(),
                    queryProfile.getKeywords(),
                    KEYWORD_LIMIT
            );
            addRankedResults(merged, fusionStats, keywordResults, RetrievalSource.KEYWORD, 0.35);
        } catch (Exception e) {
            log.warn("关键词召回失败: {}", e.getMessage());
        }

        List<RagSearchResultDTO> candidates = new ArrayList<>(merged.values());
        for (RagSearchResultDTO candidate : candidates) {
            FusionStats stats = fusionStats.get(candidate.getChunkId());
            applyFusionMetadata(candidate, stats);
        }

        candidates.sort(Comparator.comparing(RagSearchResultDTO::getScore, Comparator.nullsLast(Comparator.reverseOrder())));
        List<RagSearchResultDTO> reranked = rerankService.rerank(query, candidates);
        return reranked.stream().limit(FINAL_LIMIT).toList();
    }

    private void addRankedResults(
            Map<String, RagSearchResultDTO> merged,
            Map<String, FusionStats> fusionStats,
            List<RagSearchResultDTO> results,
            RetrievalSource source,
            double weight
    ) {
        for (int i = 0; i < results.size(); i++) {
            RagSearchResultDTO result = results.get(i);
            if (result.getChunkId() == null) {
                continue;
            }
            merged.putIfAbsent(result.getChunkId(), result);
            int rank = i + 1;
            FusionStats stats = fusionStats.computeIfAbsent(result.getChunkId(), ignored -> new FusionStats());
            double rawScore = result.getScore() == null ? 0.0 : result.getScore();
            stats.sources.add(source.name().toLowerCase());
            if (source == RetrievalSource.VECTOR) {
                stats.vectorRank = stats.vectorRank == null ? rank : Math.min(stats.vectorRank, rank);
                stats.vectorScore = Math.max(stats.vectorScore == null ? 0.0 : stats.vectorScore, rawScore);
            } else {
                stats.keywordRank = stats.keywordRank == null ? rank : Math.min(stats.keywordRank, rank);
                stats.keywordScore = Math.max(stats.keywordScore == null ? 0.0 : stats.keywordScore, rawScore);
            }
            stats.rrfScore += weight * (1.0 / (RRF_K + rank));
            stats.normalizedScore += weight * normalizeScore(rawScore);
        }
    }

    private void applyFusionMetadata(RagSearchResultDTO candidate, FusionStats stats) {
        if (stats == null) {
            return;
        }
        double hybridScore = stats.rrfScore + 0.2 * stats.normalizedScore;
        candidate.setScore(hybridScore);
        RagSearchResultDTO.ChunkMeta metadata = candidate.getMetadata();
        if (metadata == null) {
            metadata = new RagSearchResultDTO.ChunkMeta();
            candidate.setMetadata(metadata);
        }
        metadata.setRetrievalSources(new ArrayList<>(stats.sources));
        metadata.setVectorRank(stats.vectorRank);
        metadata.setKeywordRank(stats.keywordRank);
        metadata.setVectorScore(stats.vectorScore);
        metadata.setKeywordScore(stats.keywordScore);
        metadata.setRrfScore(stats.rrfScore);
        metadata.setHybridScore(hybridScore);
        metadata.setFusionMethod("weighted_rrf_plus_normalized_score");
    }

    private double normalizeScore(double score) {
        if (score <= 0) {
            return 0.0;
        }
        return score / (score + 1.0);
    }

    private enum RetrievalSource {
        VECTOR,
        KEYWORD
    }

    private static class FusionStats {
        private final Set<String> sources = new LinkedHashSet<>();
        private Integer vectorRank;
        private Integer keywordRank;
        private Double vectorScore;
        private Double keywordScore;
        private double rrfScore;
        private double normalizedScore;
    }
}

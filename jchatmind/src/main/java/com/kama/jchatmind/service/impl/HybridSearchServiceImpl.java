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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, Double> scores = new LinkedHashMap<>();

        List<String> rewrites = queryProfile.getRewrites() == null ? List.of(query) : queryProfile.getRewrites();
        for (int i = 0; i < Math.min(3, rewrites.size()); i++) {
            try {
                List<RagSearchResultDTO> vectorResults = ragService.similaritySearch(kbId, rewrites.get(i), VECTOR_LIMIT);
                addRankedResults(merged, scores, vectorResults, 0.65);
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
            addRankedResults(merged, scores, keywordResults, 0.35);
        } catch (Exception e) {
            log.warn("关键词召回失败: {}", e.getMessage());
        }

        List<RagSearchResultDTO> candidates = new ArrayList<>(merged.values());
        for (RagSearchResultDTO candidate : candidates) {
            Double hybridScore = scores.get(candidate.getChunkId());
            if (hybridScore != null) {
                candidate.setScore(Math.max(candidate.getScore() == null ? 0.0 : candidate.getScore(), hybridScore));
            }
        }

        candidates.sort(Comparator.comparing(RagSearchResultDTO::getScore, Comparator.nullsLast(Comparator.reverseOrder())));
        List<RagSearchResultDTO> reranked = rerankService.rerank(query, candidates);
        return reranked.stream().limit(FINAL_LIMIT).toList();
    }

    private void addRankedResults(
            Map<String, RagSearchResultDTO> merged,
            Map<String, Double> scores,
            List<RagSearchResultDTO> results,
            double weight
    ) {
        for (int i = 0; i < results.size(); i++) {
            RagSearchResultDTO result = results.get(i);
            if (result.getChunkId() == null) {
                continue;
            }
            merged.putIfAbsent(result.getChunkId(), result);
            double semanticScore = result.getScore() == null ? 0.0 : result.getScore();
            double rankScore = weight * (1.0 / (RRF_K + i + 1));
            scores.merge(result.getChunkId(), semanticScore * weight + rankScore, Double::sum);
        }
    }
}

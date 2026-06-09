package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ElasticsearchProperties;
import com.kama.jchatmind.model.dto.RagSearchResultDTO;
import com.kama.jchatmind.service.ElasticsearchChunkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ElasticsearchChunkServiceImpl implements ElasticsearchChunkService {

    private final ElasticsearchProperties properties;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public ElasticsearchChunkServiceImpl(
            ElasticsearchProperties properties,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.baseUrl(properties.getBaseUrl()).build();
    }

    @Override
    public void indexChunk(String chunkId, String kbId, String docId, String docName, String content, RagSearchResultDTO.ChunkMeta metadata) {
        if (!properties.isEnabled() || !StringUtils.hasText(chunkId)) {
            return;
        }
        try {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("chunkId", chunkId);
            doc.put("kbId", kbId);
            doc.put("docId", docId);
            doc.put("docName", docName);
            doc.put("title", metadata == null ? null : metadata.getTitle());
            doc.put("sectionPath", metadata == null ? null : metadata.getSectionPath());
            doc.put("contextualSummary", metadata == null ? null : metadata.getContextualSummary());
            doc.put("content", content);
            doc.put("contextualContent", buildContextualContent(content, metadata));
            doc.put("metadata", metadata);
            webClient.put()
                    .uri("/{index}/_doc/{id}", properties.getIndexName(), chunkId)
                    .bodyValue(doc)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
        } catch (Exception e) {
            log.warn("ES chunk index failed: chunkId={}, error={}", chunkId, e.getMessage());
        }
    }

    @Override
    public List<RagSearchResultDTO> bm25Search(String kbId, String query, List<String> keywords, int limit) {
        if (!properties.isEnabled() || !StringUtils.hasText(kbId) || !StringUtils.hasText(query)) {
            return List.of();
        }
        try {
            Map<String, Object> body = buildSearchBody(kbId, query, keywords, limit);
            String response = webClient.post()
                    .uri("/{index}/_search", properties.getIndexName())
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return parseSearchResponse(response);
        } catch (Exception e) {
            log.warn("ES BM25 search failed, fallback to Postgres keyword search: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> buildSearchBody(String kbId, String query, List<String> keywords, int limit) {
        List<Map<String, Object>> should = new ArrayList<>();
        should.add(Map.of("match", Map.of("contextualContent", Map.of("query", query, "boost", 2.0))));
        should.add(Map.of("match", Map.of("title", Map.of("query", query, "boost", 1.6))));
        if (keywords != null) {
            for (String keyword : keywords) {
                if (StringUtils.hasText(keyword)) {
                    should.add(Map.of("match", Map.of("contextualContent", keyword)));
                }
            }
        }
        return Map.of(
                "size", limit,
                "query", Map.of(
                        "bool", Map.of(
                                "filter", List.of(Map.of("term", Map.of("kbId.keyword", kbId))),
                                "should", should,
                                "minimum_should_match", 1
                        )
                )
        );
    }

    private List<RagSearchResultDTO> parseSearchResponse(String response) throws Exception {
        if (!StringUtils.hasText(response)) {
            return List.of();
        }
        JsonNode hits = objectMapper.readTree(response).at("/hits/hits");
        if (!hits.isArray()) {
            return List.of();
        }
        List<RagSearchResultDTO> results = new ArrayList<>();
        for (JsonNode hit : hits) {
            JsonNode source = hit.path("_source");
            RagSearchResultDTO.ChunkMeta meta = null;
            if (source.has("metadata") && !source.path("metadata").isNull()) {
                meta = objectMapper.convertValue(source.path("metadata"), RagSearchResultDTO.ChunkMeta.class);
            }
            double esScore = hit.path("_score").asDouble(0.0);
            results.add(RagSearchResultDTO.builder()
                    .chunkId(source.path("chunkId").asText())
                    .kbId(source.path("kbId").asText())
                    .docId(source.path("docId").asText())
                    .docName(source.path("docName").asText(null))
                    .content(source.path("content").asText(""))
                    .score(esScore <= 0 ? 0.0 : esScore / (esScore + 8.0))
                    .metadata(meta)
                    .build());
        }
        return results;
    }

    private String buildContextualContent(String content, RagSearchResultDTO.ChunkMeta metadata) {
        if (metadata == null) {
            return content == null ? "" : content;
        }
        return ("""
                %s
                %s
                %s
                """).formatted(
                metadata.getSectionPath() == null ? "" : metadata.getSectionPath(),
                metadata.getContextualSummary() == null ? "" : metadata.getContextualSummary(),
                content == null ? "" : content
        ).trim();
    }
}

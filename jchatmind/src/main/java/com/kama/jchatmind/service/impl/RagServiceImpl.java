package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagSearchResultDTO;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.service.RagService;
import lombok.Data;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class RagServiceImpl implements RagService {

    // 封装本地的模型调用
    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final ObjectMapper objectMapper;

    public RagServiceImpl(WebClient.Builder builder, ChunkBgeM3Mapper chunkBgeM3Mapper, ObjectMapper objectMapper) {
        this.webClient = builder.baseUrl("http://localhost:11434").build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.objectMapper = objectMapper;
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    private float[] doEmbed(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", "bge-m3",
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        return resp.getEmbedding();
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<RagSearchResultDTO> similaritySearch(String kbId, String query) {
        return similaritySearch(kbId, query, 3);
    }

    @Override
    public List<RagSearchResultDTO> similaritySearch(String kbId, String query, int limit) {
        String queryEmbedding = toPgVector(doEmbed(query));
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, limit);
        return chunks.stream().map(this::toSearchResult).toList();
    }

    @Override
    public List<RagSearchResultDTO> keywordSearch(String kbId, String query, List<String> keywords, int limit) {
        if ((query == null || query.isBlank()) && (keywords == null || keywords.isEmpty())) {
            return List.of();
        }
        List<ChunkBgeM3> chunks = chunkBgeM3Mapper.keywordSearch(kbId, query == null ? "" : query, keywords, limit);
        return chunks.stream().map(this::toSearchResult).toList();
    }

    private RagSearchResultDTO toSearchResult(ChunkBgeM3 chunk) {
        Double distance = chunk.getDistance();
        Double score = distance == null ? null : 1.0 / (1.0 + distance);
        RagSearchResultDTO.ChunkMeta meta = parseChunkMeta(chunk);
        String docName = chunk.getDocName();
        if (docName == null && meta != null) {
            docName = meta.getSourceFileName();
        }
        return RagSearchResultDTO.builder()
                .chunkId(chunk.getId())
                .kbId(chunk.getKbId())
                .docId(chunk.getDocId())
                .docName(docName)
                .content(chunk.getContent())
                .distance(distance)
                .score(score)
                .metadata(meta)
                .build();
    }

    private RagSearchResultDTO.ChunkMeta parseChunkMeta(ChunkBgeM3 chunk) {
        if (chunk.getMetadata() == null || chunk.getMetadata().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(chunk.getMetadata(), RagSearchResultDTO.ChunkMeta.class);
        } catch (Exception e) {
            return RagSearchResultDTO.ChunkMeta.builder()
                    .sourceFileName(chunk.getDocName())
                    .build();
        }
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }
}

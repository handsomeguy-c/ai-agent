package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.CitationDTO;
import com.kama.jchatmind.model.dto.RagSearchResultDTO;
import com.kama.jchatmind.model.dto.RagToolResponseDTO;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.ContextPackingService;
import com.kama.jchatmind.service.HybridSearchService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class KnowledgeTools implements Tool {

    private final HybridSearchService hybridSearchService;
    private final ContextPackingService contextPackingService;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final ObjectMapper objectMapper;

    public KnowledgeTools(
            HybridSearchService hybridSearchService,
            ContextPackingService contextPackingService,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            ObjectMapper objectMapper
    ) {
        this.hybridSearchService = hybridSearchService;
        this.contextPackingService = contextPackingService;
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "用于从知识库执行语义检索（RAG）。输入知识库 ID 和查询文本，返回与查询最相关的内容片段。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从知识库中执行相似性检索（RAG）。参数 query 必填；kbsId 可选，不传时自动检索全部知识库并返回最相关片段。"
    )
    public String knowledgeQuery(String kbsId, String query) {
        List<RagSearchResultDTO> results = searchKnowledge(kbsId, query);
        List<CitationDTO> citations = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            RagSearchResultDTO result = results.get(i);
            RagSearchResultDTO.ChunkMeta meta = result.getMetadata();
            citations.add(CitationDTO.builder()
                    .index(i + 1)
                    .chunkId(result.getChunkId())
                    .kbId(result.getKbId())
                    .docId(result.getDocId())
                    .docName(result.getDocName())
                    .title(meta == null ? null : meta.getTitle())
                    .score(result.getScore())
                    .snippet(toSnippet(result.getContent()))
                    .build());
        }

        String context = buildGroundedContext(results);

        RagToolResponseDTO response = RagToolResponseDTO.builder()
                .query(query)
                .context(context)
                .citations(citations)
                .build();
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            return context;
        }
    }

    private List<RagSearchResultDTO> searchKnowledge(String kbsId, String query) {
        if (StringUtils.hasText(kbsId)) {
            List<RagSearchResultDTO> results = hybridSearchService.search(kbsId, query);
            return results.isEmpty() ? fallbackRecentChunks(List.of(kbsId)) : results;
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectAll();
        if (knowledgeBases.isEmpty()) {
            return List.of();
        }

        List<RagSearchResultDTO> merged = new ArrayList<>();
        for (KnowledgeBase knowledgeBase : knowledgeBases) {
            try {
                merged.addAll(hybridSearchService.search(knowledgeBase.getId(), query));
            } catch (Exception ignored) {
                // 单个知识库检索失败不影响其他知识库召回。
            }
        }
        if (merged.isEmpty()) {
            return fallbackRecentChunks(knowledgeBases.stream().map(KnowledgeBase::getId).toList());
        }
        return merged.stream()
                .sorted(Comparator.comparing(RagSearchResultDTO::getScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(6)
                .toList();
    }

    private List<RagSearchResultDTO> fallbackRecentChunks(List<String> kbIds) {
        List<RagSearchResultDTO> fallback = new ArrayList<>();
        for (String kbId : kbIds) {
            fallback.addAll(chunkBgeM3Mapper.selectRecentByKbId(kbId, 3).stream()
                    .map(this::toFallbackResult)
                    .toList());
            if (fallback.size() >= 6) {
                break;
            }
        }
        return fallback.stream().limit(6).toList();
    }

    private RagSearchResultDTO toFallbackResult(ChunkBgeM3 chunk) {
        RagSearchResultDTO.ChunkMeta meta = null;
        if (chunk.getMetadata() != null && !chunk.getMetadata().isBlank()) {
            try {
                meta = objectMapper.readValue(chunk.getMetadata(), RagSearchResultDTO.ChunkMeta.class);
            } catch (Exception ignored) {
                meta = RagSearchResultDTO.ChunkMeta.builder()
                        .sourceFileName(chunk.getDocName())
                        .build();
            }
        }
        return RagSearchResultDTO.builder()
                .chunkId(chunk.getId())
                .kbId(chunk.getKbId())
                .docId(chunk.getDocId())
                .docName(chunk.getDocName())
                .content(chunk.getContent())
                .distance(chunk.getDistance())
                .score(0.45)
                .metadata(meta)
                .build();
    }

    private String toSnippet(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160) + "...";
    }

    private String buildGroundedContext(List<RagSearchResultDTO> results) {
        String packed = contextPackingService.pack(results, 2200);
        if (packed.isBlank()) {
            return "未检索到相关知识片段。";
        }
        return results.stream()
                .map(result -> {
                    int index = results.indexOf(result) + 1;
                    RagSearchResultDTO.ChunkMeta meta = result.getMetadata();
                    String title = meta == null || meta.getTitle() == null ? "未命名片段" : meta.getTitle();
                    String sectionPath = meta == null || meta.getSectionPath() == null ? title : meta.getSectionPath();
                    String contextualSummary = meta == null || meta.getContextualSummary() == null ? "" : meta.getContextualSummary();
                    String retrievalSources = meta == null || meta.getRetrievalSources() == null ? "unknown" : String.join("+", meta.getRetrievalSources());
                    String rankTrace = meta == null
                            ? ""
                            : "召回: %s, vectorRank=%s, keywordRank=%s, fusion=%s"
                            .formatted(
                                    retrievalSources,
                                    meta.getVectorRank() == null ? "-" : meta.getVectorRank(),
                                    meta.getKeywordRank() == null ? "-" : meta.getKeywordRank(),
                                    meta.getFusionMethod() == null ? "-" : meta.getFusionMethod()
                            );
                    String docName = result.getDocName() == null ? "未知文档" : result.getDocName();
                    String content = result.getContent() == null ? "" : result.getContent();
                    return "[%d] 来源: %s / %s\n相关度: %.4f\n%s\n上下文:\n%s\n内容:\n%s"
                            .formatted(index, docName, sectionPath, result.getScore() == null ? 0.0 : result.getScore(), rankTrace, contextualSummary, content);
                })
                .collect(Collectors.joining("\n\n"));
    }
}

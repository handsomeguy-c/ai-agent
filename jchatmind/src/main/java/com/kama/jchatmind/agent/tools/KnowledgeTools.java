package com.kama.jchatmind.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.CitationDTO;
import com.kama.jchatmind.model.dto.RagSearchResultDTO;
import com.kama.jchatmind.model.dto.RagToolResponseDTO;
import com.kama.jchatmind.service.ContextPackingService;
import com.kama.jchatmind.service.HybridSearchService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class KnowledgeTools implements Tool {

    private final HybridSearchService hybridSearchService;
    private final ContextPackingService contextPackingService;
    private final ObjectMapper objectMapper;

    public KnowledgeTools(HybridSearchService hybridSearchService, ContextPackingService contextPackingService, ObjectMapper objectMapper) {
        this.hybridSearchService = hybridSearchService;
        this.contextPackingService = contextPackingService;
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
            description = "从指定知识库中执行相似性检索（RAG）。参数为知识库 ID（kbsId）和查询文本（query），返回与查询最相关的知识片段。"
    )
    public String knowledgeQuery(String kbsId, String query) {
        List<RagSearchResultDTO> results = hybridSearchService.search(kbsId, query);
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
                    String docName = result.getDocName() == null ? "未知文档" : result.getDocName();
                    String content = result.getContent() == null ? "" : result.getContent();
                    return "[%d] 来源: %s / %s\n相关度: %.4f\n内容:\n%s"
                            .formatted(index, docName, title, result.getScore() == null ? 0.0 : result.getScore(), content);
                })
                .collect(Collectors.joining("\n\n"));
    }
}

package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.RagSearchResultDTO;
import com.kama.jchatmind.service.ContextPackingService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ContextPackingServiceImpl implements ContextPackingService {
    @Override
    public String pack(List<RagSearchResultDTO> chunks, int tokenBudget) {
        StringBuilder builder = new StringBuilder();
        int used = 0;
        for (RagSearchResultDTO chunk : chunks) {
            String packedChunk = formatChunk(chunk);
            int cost = Math.max(1, packedChunk.length() / 4);
            if (used + cost > tokenBudget) {
                break;
            }
            builder.append(packedChunk).append("\n\n");
            used += cost;
        }
        return builder.toString().trim();
    }

    private String formatChunk(RagSearchResultDTO chunk) {
        RagSearchResultDTO.ChunkMeta meta = chunk.getMetadata();
        StringBuilder builder = new StringBuilder();
        if (meta != null) {
            if (meta.getSectionPath() != null && !meta.getSectionPath().isBlank()) {
                builder.append("章节路径: ").append(meta.getSectionPath()).append("\n");
            } else if (meta.getTitle() != null && !meta.getTitle().isBlank()) {
                builder.append("标题: ").append(meta.getTitle()).append("\n");
            }
            if (meta.getContextualSummary() != null && !meta.getContextualSummary().isBlank()) {
                builder.append(meta.getContextualSummary()).append("\n");
            }
        }
        builder.append(chunk.getContent() == null ? "" : chunk.getContent());
        return builder.toString().trim();
    }
}

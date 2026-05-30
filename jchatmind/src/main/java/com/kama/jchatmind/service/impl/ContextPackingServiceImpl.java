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
            int cost = chunk.getContent() == null ? 0 : Math.max(1, chunk.getContent().length() / 4);
            if (used + cost > tokenBudget) {
                break;
            }
            builder.append(chunk.getContent()).append("\n\n");
            used += cost;
        }
        return builder.toString().trim();
    }
}

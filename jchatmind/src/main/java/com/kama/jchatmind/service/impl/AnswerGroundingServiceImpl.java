package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.RagSearchResultDTO;
import com.kama.jchatmind.service.AnswerGroundingService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AnswerGroundingServiceImpl implements AnswerGroundingService {
    @Override
    public boolean hasReliableEvidence(List<RagSearchResultDTO> chunks) {
        return chunks != null && chunks.stream()
                .anyMatch(chunk -> chunk.getScore() != null && chunk.getScore() >= 0.2);
    }
}

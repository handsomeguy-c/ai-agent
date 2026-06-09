package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagSearchResultDTO;

import java.util.List;

public interface AnswerGroundingService {
    boolean hasReliableEvidence(List<RagSearchResultDTO> chunks);
}

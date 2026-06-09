package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagSearchResultDTO;

import java.util.List;

public interface ContextPackingService {
    String pack(List<RagSearchResultDTO> chunks, int tokenBudget);
}

package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagSearchResultDTO;

import java.util.List;

public interface RerankService {
    List<RagSearchResultDTO> rerank(String query, List<RagSearchResultDTO> candidates);
}

package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagSearchResultDTO;

import java.util.List;

public interface HybridSearchService {
    List<RagSearchResultDTO> search(String kbId, String query);
}

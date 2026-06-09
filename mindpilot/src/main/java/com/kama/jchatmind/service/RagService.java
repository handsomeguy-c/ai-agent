package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagSearchResultDTO;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    List<RagSearchResultDTO> similaritySearch(String kbId, String query);

    List<RagSearchResultDTO> similaritySearch(String kbId, String query, int limit);

    List<RagSearchResultDTO> keywordSearch(String kbId, String query, List<String> keywords, int limit);
}

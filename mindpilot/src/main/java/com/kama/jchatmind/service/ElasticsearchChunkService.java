package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagSearchResultDTO;

import java.util.List;

public interface ElasticsearchChunkService {
    void indexChunk(String chunkId, String kbId, String docId, String docName, String content, RagSearchResultDTO.ChunkMeta metadata);

    List<RagSearchResultDTO> bm25Search(String kbId, String query, List<String> keywords, int limit);
}

package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.Memory;

import java.util.List;

public interface MemoryService {
    List<Memory> getActiveMemories(String agentId);

    List<Memory> recall(String agentId, String query, int limit);

    String buildMemoryPrompt(String agentId, String query, int limit);

    void captureTurn(String agentId, String sessionId, String content);

    void saveMemory(Memory memory);

    void archiveMemory(String memoryId);
}

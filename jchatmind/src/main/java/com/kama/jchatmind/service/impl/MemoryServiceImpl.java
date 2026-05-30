package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.MemoryMapper;
import com.kama.jchatmind.model.entity.Memory;
import com.kama.jchatmind.service.MemoryService;
import com.kama.jchatmind.service.QueryRewriteService;
import com.kama.jchatmind.service.RagService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@AllArgsConstructor
@Slf4j
public class MemoryServiceImpl implements MemoryService {

    private static final Pattern ENTITY_NAME_PATTERN = Pattern.compile("我(?:叫|是)\\s*([\\p{IsHan}a-zA-Z0-9_\\-]{2,20})");
    private static final Pattern ENTITY_FACT_PATTERN = Pattern.compile("我的([\\p{IsHan}a-zA-Z0-9_\\-]{2,20})(?:是|为|=)\\s*([^，。；;\\n]{1,80})");
    private static final Pattern PREFERENCE_PATTERN = Pattern.compile("我(?:喜欢|偏好|希望|习惯)\\s*([^，。；;\\n]{2,80})");

    private final MemoryMapper memoryMapper;
    private final RagService ragService;
    private final QueryRewriteService queryRewriteService;
    private final ObjectMapper objectMapper;

    @Override
    public List<Memory> getActiveMemories(String agentId) {
        return memoryMapper.selectActiveByAgentId(agentId);
    }

    @Override
    public List<Memory> recall(String agentId, String query, int limit) {
        if (!StringUtils.hasLength(agentId) || limit <= 0) {
            return List.of();
        }
        List<Memory> recalled = List.of();
        float[] queryEmbedding = embedOrNull(query);
        if (queryEmbedding != null) {
            try {
                recalled = memoryMapper.recallByEmbedding(agentId, toPgVector(queryEmbedding), limit);
            } catch (Exception e) {
                log.warn("记忆向量召回失败，降级为关键词召回: {}", e.getMessage());
            }
        }
        if (recalled == null || recalled.isEmpty()) {
            recalled = memoryMapper.selectActiveByAgentId(agentId).stream()
                    .filter(memory -> isTextRelated(query, memory.getContent()))
                    .limit(limit)
                    .toList();
        }
        if (recalled == null || recalled.isEmpty()) {
            return List.of();
        }
        recalled.forEach(memory -> {
            if (memory.getId() != null) {
                memoryMapper.touchById(memory.getId());
            }
        });
        return recalled;
    }

    @Override
    public String buildMemoryPrompt(String agentId, String query, int limit) {
        List<Memory> memories = recall(agentId, query, limit);
        if (memories.isEmpty()) {
            return "";
        }
        Map<String, List<Memory>> grouped = new LinkedHashMap<>();
        grouped.put("entity", new ArrayList<>());
        grouped.put("long_term", new ArrayList<>());
        grouped.put("short_term", new ArrayList<>());
        grouped.put("perceptual", new ArrayList<>());
        for (Memory memory : memories) {
            grouped.computeIfAbsent(memory.getType(), ignored -> new ArrayList<>()).add(memory);
        }

        StringBuilder builder = new StringBuilder("【可召回记忆】\n");
        appendMemoryLayer(builder, "实体记忆", grouped.get("entity"));
        appendMemoryLayer(builder, "长期记忆", grouped.get("long_term"));
        appendMemoryLayer(builder, "短期记忆", grouped.get("short_term"));
        appendMemoryLayer(builder, "感知记忆", grouped.get("perceptual"));
        builder.append("请仅在与当前问题相关时使用这些记忆；如果记忆与用户最新意图冲突，以用户最新输入为准。");
        return builder.toString();
    }

    @Override
    public void captureTurn(String agentId, String sessionId, String content) {
        if (!StringUtils.hasLength(agentId) || !StringUtils.hasLength(content)) {
            return;
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        double importance = scoreImportance(normalized);
        saveLayerMemory(agentId, sessionId, "perceptual", normalized, Math.min(0.5, importance));
        if (importance >= 0.35) {
            saveLayerMemory(agentId, sessionId, "short_term", normalized, Math.min(0.75, importance + 0.08));
        }
        if (importance >= 0.65) {
            saveLayerMemory(agentId, sessionId, "long_term", normalized, importance);
        }
        upsertEntityMemories(agentId, sessionId, normalized, importance);
    }

    @Override
    public void saveMemory(Memory memory) {
        LocalDateTime now = LocalDateTime.now();
        memory.setStatus(memory.getStatus() == null ? "active" : memory.getStatus());
        memory.setCreatedAt(now);
        memory.setUpdatedAt(now);
        memory.setLastAccessedAt(memory.getLastAccessedAt() == null ? now : memory.getLastAccessedAt());
        memoryMapper.insert(memory);
    }

    @Override
    public void archiveMemory(String memoryId) {
        memoryMapper.archiveById(memoryId);
    }

    private void saveLayerMemory(String agentId, String sessionId, String type, String content, double importance) {
        Memory memory = Memory.builder()
                .agentId(agentId)
                .type(type)
                .content(content)
                .sourceSessionId(sessionId)
                .importanceScore(clamp(importance))
                .status("active")
                .embedding(embedOrNull(content))
                .metadata(toJson(Map.of(
                        "memoryLayer", type,
                        "extractor", "heuristic-memo-v1",
                        "keywords", queryRewriteService.analyze(content).getKeywords()
                )))
                .build();
        saveMemory(memory);
    }

    private void upsertEntityMemories(String agentId, String sessionId, String content, double baseImportance) {
        Map<String, String> facts = extractEntityFacts(content);
        for (Map.Entry<String, String> fact : facts.entrySet()) {
            String entity = fact.getKey();
            String factContent = fact.getValue();
            Memory existing = memoryMapper.selectEntityByAgentAndName(agentId, entity);
            LocalDateTime now = LocalDateTime.now();
            if (existing == null) {
                saveMemory(Memory.builder()
                        .agentId(agentId)
                        .type("entity")
                        .content(factContent)
                        .sourceSessionId(sessionId)
                        .importanceScore(Math.max(0.82, baseImportance))
                        .status("active")
                        .embedding(embedOrNull(factContent))
                        .metadata(toJson(Map.of(
                                "entity", entity,
                                "memoryLayer", "entity",
                                "extractor", "heuristic-memo-v1"
                        )))
                        .build());
                continue;
            }
            existing.setContent(factContent);
            existing.setSourceSessionId(sessionId);
            existing.setImportanceScore(Math.max(existing.getImportanceScore() == null ? 0.0 : existing.getImportanceScore(), 0.86));
            existing.setEmbedding(embedOrNull(factContent));
            existing.setUpdatedAt(now);
            existing.setLastAccessedAt(now);
            existing.setMetadata(toJson(Map.of(
                    "entity", entity,
                    "memoryLayer", "entity",
                    "extractor", "heuristic-memo-v1",
                    "updatePolicy", "latest_fact_wins"
            )));
            memoryMapper.updateById(existing);
        }
    }

    private Map<String, String> extractEntityFacts(String content) {
        Map<String, String> facts = new LinkedHashMap<>();
        Matcher nameMatcher = ENTITY_NAME_PATTERN.matcher(content);
        if (nameMatcher.find()) {
            String name = nameMatcher.group(1).trim();
            facts.put("user.name", "用户姓名是 " + name);
        }
        Matcher factMatcher = ENTITY_FACT_PATTERN.matcher(content);
        while (factMatcher.find()) {
            String key = factMatcher.group(1).trim();
            String value = factMatcher.group(2).trim();
            facts.put("user." + key, "用户的" + key + "是 " + value);
        }
        Matcher preferenceMatcher = PREFERENCE_PATTERN.matcher(content);
        if (preferenceMatcher.find()) {
            String preference = preferenceMatcher.group(1).trim();
            facts.put("user.preference", "用户偏好：" + preference);
        }
        return facts;
    }

    private double scoreImportance(String content) {
        double score = Math.min(0.42, content.length() / 900.0);
        String lower = content.toLowerCase(Locale.ROOT);
        if (content.contains("记住") || content.contains("以后") || content.contains("偏好") || content.contains("我的")) {
            score += 0.35;
        }
        if (content.contains("项目") || content.contains("简历") || content.contains("要求") || content.contains("方案")
                || lower.contains("github") || lower.contains("agent") || lower.contains("rag")) {
            score += 0.25;
        }
        if (ENTITY_NAME_PATTERN.matcher(content).find() || ENTITY_FACT_PATTERN.matcher(content).find() || PREFERENCE_PATTERN.matcher(content).find()) {
            score += 0.3;
        }
        return clamp(score);
    }

    private boolean isTextRelated(String query, String content) {
        if (!StringUtils.hasLength(query) || !StringUtils.hasLength(content)) {
            return true;
        }
        List<String> keywords = queryRewriteService.analyze(query).getKeywords();
        String lowerContent = content.toLowerCase(Locale.ROOT);
        return keywords.isEmpty() || keywords.stream().anyMatch(keyword -> lowerContent.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    private void appendMemoryLayer(StringBuilder builder, String title, List<Memory> memories) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        builder.append(title).append("：\n");
        for (Memory memory : memories) {
            builder.append("- ")
                    .append(memory.getContent())
                    .append("（importance=")
                    .append(String.format(Locale.ROOT, "%.2f", memory.getImportanceScore() == null ? 0.0 : memory.getImportanceScore()))
                    .append("）\n");
        }
    }

    private float[] embedOrNull(String text) {
        if (!StringUtils.hasLength(text)) {
            return null;
        }
        try {
            return ragService.embed(text);
        } catch (Exception e) {
            log.warn("生成记忆向量失败，降级为关键词召回: {}", e.getMessage());
            return null;
        }
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }
}

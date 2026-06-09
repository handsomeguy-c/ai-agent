package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.config.QueryRewriteProperties;
import com.kama.jchatmind.model.dto.QueryRewriteDTO;
import com.kama.jchatmind.service.QueryRewriteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class QueryRewriteServiceImpl implements QueryRewriteService {

    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}]{2,8}|[a-zA-Z][a-zA-Z0-9_\\-]{1,30}");
    private static final Set<String> STOP_WORDS = Set.of(
            "什么", "怎么", "如何", "为什么", "是否", "一个", "这个", "那个", "以及", "或者", "the", "and", "for", "with"
    );

    private final ChatClientRegistry chatClientRegistry;
    private final QueryRewriteProperties queryRewriteProperties;
    private final ObjectMapper objectMapper;

    public QueryRewriteServiceImpl(
            ChatClientRegistry chatClientRegistry,
            QueryRewriteProperties queryRewriteProperties,
            ObjectMapper objectMapper
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.queryRewriteProperties = queryRewriteProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public QueryRewriteDTO analyze(String query) {
        QueryRewriteDTO llmResult = analyzeWithLlm(query);
        if (llmResult != null) {
            return llmResult;
        }
        return analyzeWithRules(query);
    }

    private QueryRewriteDTO analyzeWithLlm(String query) {
        if (!queryRewriteProperties.isLlmEnabled() || !StringUtils.hasText(query)) {
            return null;
        }
        ChatClient chatClient = chatClientRegistry.get(queryRewriteProperties.getModel());
        if (chatClient == null) {
            return null;
        }
        try {
            String systemPrompt = """
                    你是 RAG 查询理解模块，请将用户问题改写为严格 JSON。
                    输出字段：
                    - originalQuery: 原始问题
                    - normalizedQuery: 去除口语化和冗余后的标准问题
                    - intent: knowledge_qa | troubleshooting | comparison | task_planning | code_or_api | memory_write
                    - keywords: 3-8 个关键词，优先名词、实体、模块名
                    - rewrites: 2-4 个适合向量检索和 BM25 检索的改写查询
                    只输出 JSON，不要 Markdown，不要解释。
                    """;
            ChatResponse response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(query)
                    .call()
                    .chatClientResponse()
                    .chatResponse();
            if (response == null || response.getResult() == null) {
                return null;
            }
            AssistantMessage output = response.getResult().getOutput();
            if (output == null || !StringUtils.hasText(output.getText())) {
                return null;
            }
            QueryRewriteDTO dto = objectMapper.readValue(extractJson(output.getText()), QueryRewriteDTO.class);
            if (!StringUtils.hasText(dto.getNormalizedQuery())) {
                dto.setNormalizedQuery(normalize(query));
            }
            if (dto.getOriginalQuery() == null) {
                dto.setOriginalQuery(query);
            }
            if (dto.getKeywords() == null) {
                dto.setKeywords(extractKeywords(dto.getNormalizedQuery()));
            }
            if (dto.getRewrites() == null || dto.getRewrites().isEmpty()) {
                dto.setRewrites(buildRewrites(dto.getNormalizedQuery(), dto.getKeywords()));
            }
            return dto;
        } catch (Exception e) {
            log.warn("LLM query rewrite failed, fallback to rules: {}", e.getMessage());
            return null;
        }
    }

    private QueryRewriteDTO analyzeWithRules(String query) {
        String normalized = normalize(query);
        List<String> keywords = extractKeywords(normalized);
        List<String> rewrites = buildRewrites(normalized, keywords);
        return QueryRewriteDTO.builder()
                .originalQuery(query)
                .normalizedQuery(normalized)
                .intent(classifyIntent(normalized))
                .keywords(keywords)
                .rewrites(rewrites)
                .build();
    }

    @Override
    public List<String> rewrite(String query) {
        return analyze(query).getRewrites();
    }

    private String extractJson(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String normalize(String query) {
        if (query == null) {
            return "";
        }
        return query.replaceAll("\\s+", " ")
                .replaceAll("[？?！!。；;]+", " ")
                .trim();
    }

    private String classifyIntent(String query) {
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("报错") || lower.contains("异常") || lower.contains("失败") || lower.contains("error")) {
            return "troubleshooting";
        }
        if (lower.contains("对比") || lower.contains("区别") || lower.contains("比较") || lower.contains(" vs ")) {
            return "comparison";
        }
        if (lower.contains("步骤") || lower.contains("规划") || lower.contains("计划") || lower.contains("怎么做")) {
            return "task_planning";
        }
        if (lower.contains("代码") || lower.contains("接口") || lower.contains("sql") || lower.contains("class")) {
            return "code_or_api";
        }
        return "knowledge_qa";
    }

    private List<String> extractKeywords(String query) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        Matcher matcher = KEYWORD_PATTERN.matcher(query);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (token.isBlank()) {
                continue;
            }
            String normalized = token.toLowerCase(Locale.ROOT);
            if (STOP_WORDS.contains(normalized)) {
                continue;
            }
            keywords.add(token);
            if (keywords.size() >= 8) {
                break;
            }
        }
        return new ArrayList<>(keywords);
    }

    private List<String> buildRewrites(String normalized, List<String> keywords) {
        LinkedHashSet<String> rewrites = new LinkedHashSet<>();
        if (!normalized.isBlank()) {
            rewrites.add(normalized);
        }
        if (!keywords.isEmpty()) {
            rewrites.add(String.join(" ", keywords));
            rewrites.add("请检索与 " + String.join("、", keywords) + " 直接相关的定义、流程、限制和示例");
        }
        if (normalized.length() > 12) {
            rewrites.add("请根据知识库回答：" + normalized);
        }
        return rewrites.isEmpty() ? List.of("") : new ArrayList<>(rewrites);
    }
}

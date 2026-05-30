package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.QueryRewriteDTO;
import com.kama.jchatmind.service.QueryRewriteService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class QueryRewriteServiceImpl implements QueryRewriteService {

    private static final Pattern KEYWORD_PATTERN = Pattern.compile("[\\p{IsHan}]{2,8}|[a-zA-Z][a-zA-Z0-9_\\-]{1,30}");
    private static final Set<String> STOP_WORDS = Set.of(
            "什么", "怎么", "如何", "为什么", "是否", "一个", "这个", "那个", "以及", "或者", "the", "and", "for", "with"
    );

    @Override
    public QueryRewriteDTO analyze(String query) {
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

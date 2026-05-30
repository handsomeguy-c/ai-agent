package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.RagSearchResultDTO;
import com.kama.jchatmind.service.RerankService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RerankServiceImpl implements RerankService {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}]{2,8}|[a-zA-Z][a-zA-Z0-9_\\-]{1,30}");

    @Override
    public List<RagSearchResultDTO> rerank(String query, List<RagSearchResultDTO> candidates) {
        Set<String> queryTokens = tokenize(query);
        double avgDocLength = candidates.stream()
                .map(RagSearchResultDTO::getContent)
                .mapToInt(content -> Math.max(1, tokenList(content).size()))
                .average()
                .orElse(1.0);
        Map<String, Long> documentFrequency = queryTokens.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        token -> candidates.stream()
                                .map(RagSearchResultDTO::getContent)
                                .filter(content -> content != null && content.toLowerCase(Locale.ROOT).contains(token))
                                .count()
                ));
        return candidates.stream()
                .peek(candidate -> candidate.setScore(scoreCandidate(query, queryTokens, candidate, documentFrequency, candidates.size(), avgDocLength)))
                .sorted(Comparator.comparing(RagSearchResultDTO::getScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private double scoreCandidate(
            String query,
            Set<String> queryTokens,
            RagSearchResultDTO candidate,
            Map<String, Long> documentFrequency,
            int documentCount,
            double avgDocLength
    ) {
        double base = candidate.getScore() == null ? 0.0 : candidate.getScore();
        String content = candidate.getContent() == null ? "" : candidate.getContent();
        String title = candidate.getMetadata() == null || candidate.getMetadata().getTitle() == null
                ? ""
                : candidate.getMetadata().getTitle();
        String haystack = (title + " " + content).toLowerCase(Locale.ROOT);

        double overlap = 0.0;
        if (!queryTokens.isEmpty()) {
            long hitCount = queryTokens.stream().filter(haystack::contains).count();
            overlap = (double) hitCount / queryTokens.size();
        }

        double exactMatchBoost = query != null && !query.isBlank() && haystack.contains(query.toLowerCase(Locale.ROOT)) ? 0.12 : 0.0;
        double titleBoost = !title.isBlank() && queryTokens.stream().anyMatch(token -> title.toLowerCase(Locale.ROOT).contains(token)) ? 0.08 : 0.0;
        double lengthPenalty = content.length() > 2200 ? 0.06 : 0.0;
        double bm25 = bm25Score(content, queryTokens, documentFrequency, documentCount, avgDocLength);
        return Math.max(0.0, base * 0.52 + normalizeBm25(bm25) * 0.26 + overlap * 0.14 + exactMatchBoost + titleBoost - lengthPenalty);
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private double bm25Score(
            String content,
            Set<String> queryTokens,
            Map<String, Long> documentFrequency,
            int documentCount,
            double avgDocLength
    ) {
        if (content == null || content.isBlank() || queryTokens.isEmpty()) {
            return 0.0;
        }
        List<String> documentTokens = tokenList(content);
        int docLength = Math.max(1, documentTokens.size());
        double k1 = 1.5;
        double b = 0.75;
        double score = 0.0;
        for (String token : queryTokens) {
            long termFrequency = documentTokens.stream().filter(token::equals).count();
            if (termFrequency == 0) {
                continue;
            }
            long df = documentFrequency.getOrDefault(token, 0L);
            double idf = Math.log(1 + (documentCount - df + 0.5) / (df + 0.5));
            double numerator = termFrequency * (k1 + 1);
            double denominator = termFrequency + k1 * (1 - b + b * docLength / avgDocLength);
            score += idf * numerator / denominator;
        }
        return score;
    }

    private double normalizeBm25(double score) {
        return score <= 0 ? 0 : score / (score + 2.0);
    }

    private List<String> tokenList(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return tokens;
    }
}

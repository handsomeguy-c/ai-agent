package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.mapper.SessionSummaryMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.entity.SessionSummary;
import com.kama.jchatmind.service.SessionSummaryService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@AllArgsConstructor
public class SessionSummaryServiceImpl implements SessionSummaryService {

    private static final int MAX_SUMMARY_CHARS = 1800;

    private final SessionSummaryMapper sessionSummaryMapper;
    private final ChatMessageMapper chatMessageMapper;
    private final ChatMessageConverter chatMessageConverter;

    @Override
    public SessionSummary getLatestSummary(String sessionId) {
        return sessionSummaryMapper.selectLatestBySessionId(sessionId);
    }

    @Override
    public SessionSummary rebuildSummary(String sessionId) {
        List<ChatMessage> messages = chatMessageMapper.selectBySessionId(sessionId);
        String summaryText = buildExtractiveSummary(messages);
        SessionSummary summary = SessionSummary.builder()
                .sessionId(sessionId)
                .summary(summaryText)
                .coveredUntilMessageId(messages.isEmpty() ? null : messages.get(messages.size() - 1).getId())
                .coveredMessageCount(messages.size())
                .tokenEstimate(estimateTokenCount(summaryText))
                .version(1)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        sessionSummaryMapper.deleteBySessionId(sessionId);
        sessionSummaryMapper.insert(summary);
        return summary;
    }

    private String buildExtractiveSummary(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("当前会话摘要：\n");
        for (ChatMessage message : messages) {
            ChatMessageDTO dto = toDTO(message);
            if (dto == null || dto.getContent() == null || dto.getContent().isBlank()) {
                continue;
            }
            builder.append("- ")
                    .append(dto.getRole().getRole())
                    .append(": ")
                    .append(compact(dto.getContent()))
                    .append("\n");
            if (builder.length() >= MAX_SUMMARY_CHARS) {
                break;
            }
        }
        if (builder.length() > MAX_SUMMARY_CHARS) {
            return builder.substring(0, MAX_SUMMARY_CHARS) + "...";
        }
        return builder.toString();
    }

    private ChatMessageDTO toDTO(ChatMessage message) {
        try {
            return chatMessageConverter.toDTO(message);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String compact(String text) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 220 ? normalized : normalized.substring(0, 220) + "...";
    }

    private int estimateTokenCount(String text) {
        return text == null ? 0 : Math.max(1, text.length() / 4);
    }
}
